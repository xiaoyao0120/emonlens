"""DeepSeek Harness（DSH）会话 → EmonLens span 的离线桥接。

背景：DSH 把每个会话写成 `<cwd标记>/session-<uuid>/session.v4.jsonl.zstd`（zstd 压缩的 JSONL 事件流）。
它不是 hooks 协议（不像 Claude Code/Codex 有外部钩子），所以本桥接**读它落盘的事件流**再转成 span，
与 claude_hooks / codex_hooks 解析 transcript 是同一思路，区别只是数据源是压缩文件、且支持回放历史。

映射（一个 turn = 一条 trace）：
    DSH 事件                         EmonLens span
    user/message + turn/start  ─┐
    turn/end                   ─┴→ agent 根 span（spanName=dsh_turn，input=用户消息，output=本轮最后一条助手消息）
    assistant/message          → llm 子 span（模型、inputTokens/outputTokens/cachedInputTokens、耗时=到下一次事件）
    tool/call → tool/result    → tool 子 span（toolName、input=参数、output=结果，isError 时 status=ERROR）

只依赖标准库（zstd 走 Python 3.14+ 的 compression.zstd），与 SDK 其余部分保持一致。
"""
import glob
import hashlib
import json
import os
import time
import urllib.error
import urllib.request

DEFAULT_ROOT = os.path.expanduser("~/.dsh/sessions")
SESSION_GLOB = "**/session.v4.jsonl.zstd"

# 单段文本上限：DSH 的 tool 结果可能很大，避免把整份文件塞进一个 span
MAX_TEXT = 8000


def _decompress(path):
    """读取并解压会话文件，返回 JSONL 记录列表。缺少 zstd 支持时抛出可读错误。"""
    try:
        from compression import zstd  # Python 3.14+
    except ImportError:  # pragma: no cover - 低版本解释器
        try:
            import zstandard as zstd  # type: ignore
        except ImportError:
            raise RuntimeError(
                "需要 zstd 支持：升级到 Python 3.14+（compression.zstd）或 pip install zstandard"
            )
        with open(path, "rb") as fh:
            raw = zstd.ZstdDecompressor().stream_reader(fh).read()
    else:
        with open(path, "rb") as fh:
            raw = zstd.decompress(fh.read())
    records = []
    for line in raw.decode("utf-8", "replace").splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            records.append(json.loads(line))
        except ValueError:
            continue
    return records


def find_session_files(root=None):
    root = os.path.expanduser(root or DEFAULT_ROOT)
    return sorted(glob.glob(os.path.join(root, SESSION_GLOB), recursive=True))


def _iso(ms):
    """DSH 时间是 epoch 毫秒；EmonLens 收 ISO8601 字符串。"""
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(ms / 1000.0)) + ".%03d" % (int(ms) % 1000)


def _text_of(message):
    """assistant/user/tool 消息的 content 是 [{type,text}]，拼起来取纯文本。"""
    if not isinstance(message, dict):
        return ""
    parts = message.get("content")
    if isinstance(parts, str):
        return parts
    out = []
    for part in parts or []:
        if isinstance(part, dict) and part.get("text"):
            out.append(str(part["text"]))
    return "\n".join(out)


def _clip(text):
    text = text or ""
    return text if len(text) <= MAX_TEXT else text[:MAX_TEXT] + "…[truncated]"


def build_traces(records, session_id, agent_key="dsh", with_content=True):
    """把一条会话的记录转成若干 trace。返回 [{traceId, spans:[...]}, ...]，按 turn 升序。"""
    if not records:
        return []
    preset = ""
    cwd = ""
    traces = {}
    order = []

    def bucket(turn):
        key = int(turn or 0)
        if key not in traces:
            traces[key] = {"turn": key, "spans": [], "user": "", "assistant": "", "model": "",
                           "start": None, "end": None, "tools": {}, "error": None}
            order.append(key)
        return traces[key]

    for rec in records:
        rtype = rec.get("type")
        data = rec.get("data") or {}
        when = rec.get("time")
        if rtype == "session":
            preset = rec.get("agentPreset") or ""
            cwd = rec.get("cwd") or ""
        elif rtype == "turn/start":
            t = bucket(data.get("turn"))
            t["start"] = when
        elif rtype == "turn/end":
            t = bucket(data.get("turn"))
            t["end"] = when
            reason = data.get("reason") or {}
            if reason.get("error"):
                t["error"] = reason["error"].get("code") or reason.get("kind") or "ERROR"
        elif rtype == "user/message":
            turn = data.get("turn")
            if turn is None:
                # 有的版本 user/message 不带 turn，归到下一个已开的 turn；没有就落到 0
                turn = order[-1] if order else 0
            t = bucket(turn)
            text = _text_of(data)
            if text:
                t["user"] = text
        elif rtype == "assistant/message":
            t = bucket(data.get("turn"))
            message = data.get("message") or {}
            source = message.get("source") or {}
            model = source.get("model") or ""
            if model:
                t["model"] = model
            text = _text_of(message)
            if text:
                t["assistant"] = text
            usage = data.get("usage") or {}
            span_id = "llm-%d-%d" % (int(data.get("turn") or 0), int(data.get("step") or 0))
            t["spans"].append({
                "spanId": span_id,
                "spanType": "llm",
                "spanName": "assistant_turn",
                "startTime": _iso(when or 0),
                "endTime": _iso(when or 0),
                "status": "OK",
                "modelName": model or None,
                "inputTokens": usage.get("inputTokens"),
                "outputTokens": usage.get("outputTokens"),
                "cachedInputTokens": usage.get("cacheReadTokens"),
                "input": _clip(t["user"] if with_content else None) if with_content else None,
                "output": _clip(text) if with_content else None,
            })
        elif rtype == "tool/call":
            t = bucket(data.get("turn"))
            call_id = data.get("callId") or ""
            t["tools"][call_id] = {
                "name": data.get("name") or "tool",
                "arguments": data.get("arguments") or "",
                "start": when,
            }
        elif rtype == "tool/result":
            t = bucket(data.get("turn"))
            message = data.get("message") or {}
            call_id = message.get("toolCallId") or (message.get("source") or {}).get("callId") or ""
            call = t["tools"].pop(call_id, None) or {"name": "tool", "arguments": "", "start": when}
            failed = bool(message.get("isError"))
            t["spans"].append({
                # spanId 服务端限 32 字符，DSH 的 callId 是 32 位十六进制，直接用会超长被拒；
                # 哈希后既满足长度又保持确定性（同一调用重放得到同一 spanId，服务端据此幂等去重）
                "spanId": "tool-%s" % (hashlib.sha256((call_id or str(len(t["spans"]))).encode()).hexdigest()[:16]),
                "spanType": "tool",
                "spanName": call["name"],
                "toolName": call["name"],
                "startTime": _iso(call["start"] or when or 0),
                "endTime": _iso(when or 0),
                "status": "ERROR" if failed else "OK",
                "errorType": "TOOL_ERROR" if failed else None,
                "input": _clip(call["arguments"]) if with_content else None,
                "output": _clip(_text_of(message)) if with_content else None,
            })

    out = []
    for key in order:
        t = traces[key]
        # 没有任何时间信息的"孤儿"桶（例如 turn 记录缺失）无法形成合法 span，直接跳过而不是上报 1970 时间
        if not t["start"] and not t["end"]:
            continue
        span_id_root = "root"
        root = {
            "spanId": span_id_root,
            "spanType": "agent",
            "spanName": "dsh_turn",
            "startTime": _iso(t["start"] or t["end"] or 0),
            "endTime": _iso(t["end"] or t["start"] or 0),
            "status": "ERROR" if t["error"] else "OK",
            "errorType": t["error"],
            "input": _clip(t["user"]) if with_content else None,
            "output": _clip(t["assistant"]) if with_content else None,
            "attributes": {"turn": key, "cwd": cwd, "agentPreset": preset},
        }
        spans = [root]
        for child in t["spans"]:
            child["parentSpanId"] = span_id_root
            spans.append(child)
        trace_id = ("dsh-%s-t%d" % (session_id[:24], key))[:64]
        for span in spans:
            span["traceId"] = trace_id
            span["agentKey"] = agent_key
            span["agentVersion"] = preset or "v1"
            span["sessionId"] = session_id
        out.append({"traceId": trace_id, "turn": key, "spans": spans})
    return out


def push(host, ingest_key, spans, timeout=30):
    """POST 到 /api/v1/ingest/events。返回 (http_status, body_dict)。"""
    body = json.dumps([s for s in spans if s], ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        host.rstrip("/") + "/api/v1/ingest/events",
        data=body,
        headers={"Content-Type": "application/json", "X-Trust-Key": ingest_key},
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            return resp.status, json.loads(resp.read() or b"{}")
    except urllib.error.HTTPError as e:
        raw = e.read()
        try:
            return e.code, json.loads(raw or b"{}")
        except ValueError:
            return e.code, {"_raw": raw[:200].decode("utf-8", "replace")}


def sync(host, ingest_key, root=None, session=None, limit=None, with_content=True,
         dry_run=False, agent_key="dsh", batch=100):
    """回放会话文件。session 可按目录名或会话 id 前缀筛选；limit 限制会话文件数（最近的优先）。"""
    files = find_session_files(root)
    if session:
        files = [f for f in files if session in f]
    files = sorted(files, key=os.path.getmtime, reverse=True)
    if limit:
        files = files[:limit]

    report = {"sessions": 0, "traces": 0, "spans": 0, "pushedTraces": 0, "skipped": 0, "errors": []}
    for path in files:
        session_id = os.path.basename(os.path.dirname(path)).replace("session-", "")
        try:
            records = _decompress(path)
        except Exception as e:  # 单个文件坏掉不影响整批
            report["errors"].append({"file": path, "error": str(e)})
            continue
        traces = build_traces(records, session_id, agent_key=agent_key, with_content=with_content)
        report["sessions"] += 1
        report["traces"] += len(traces)
        report["spans"] += sum(len(t["spans"]) for t in traces)
        if dry_run:
            report["skipped"] += len(traces)
            continue
        # 按 span 数切批：服务端单请求上限 500。一个 trace 的 span 可能自己就超过上限
        # （实测有一轮 717 个 span），所以必须允许同一 trace 跨多个请求——traceId 不变即可。
        def flush(spans, turn):
            if not spans:
                return
            status, body = push(host, ingest_key, spans)
            if status >= 400 or (isinstance(body, dict) and body.get("code") not in (0, None)):
                report["errors"].append({"session": session_id, "turn": turn, "body": body})
                return
            # 服务端会按 span 拒绝非法记录（例如时间缺失），只看 code 会把"部分丢失"当成功
            data = (body or {}).get("data") or {}
            rejected = data.get("rejected") or []
            accepted = data.get("acceptedCount")
            if rejected:
                report["errors"].append({"session": session_id, "turn": turn,
                                         "rejected": rejected[:3], "rejectedCount": len(rejected)})
            if accepted is not None and accepted != len(spans):
                report["errors"].append({"session": session_id, "turn": turn,
                                         "accepted": accepted, "sent": len(spans)})

        limit_per_request = max(1, min(500, batch))
        for trace in traces:
            spans = trace["spans"]
            for i in range(0, len(spans), limit_per_request):
                flush(spans[i:i + limit_per_request], trace["turn"])
            report["pushedTraces"] += 1
    return report


def main(argv=None):
    import argparse
    parser = argparse.ArgumentParser(
        description="把 DeepSeek Harness 会话回放进 EmonLens（一个 turn = 一条 trace）")
    parser.add_argument("--host", default=os.environ.get("EMONLENS_HOST", ""), help="EmonLens 地址")
    parser.add_argument("--ingest-key", default=os.environ.get("EMONLENS_INGEST_KEY", ""), help="Ingest Key（tk-...）")
    parser.add_argument("--root", default=None, help="DSH sessions 目录，默认 ~/.dsh/sessions")
    parser.add_argument("--session", default=None, help="只同步匹配的会话（目录名或会话 id 片段）")
    parser.add_argument("--limit", type=int, default=None, help="最多处理多少个会话文件（按修改时间倒序）")
    parser.add_argument("--agent-key", default="dsh", help="上报使用的 agentKey，默认 dsh")
    parser.add_argument("--no-content", action="store_true", help="只上报时间线/用量，不发送对话正文")
    parser.add_argument("--dry-run", action="store_true", help="只解析不发送，打印统计")
    args = parser.parse_args(argv)

    if not args.host:
        parser.error("缺少 --host（或设置 EMONLENS_HOST）")
    if not args.dry_run and not args.ingest_key:
        parser.error("缺少 --ingest-key（或设置 EMONLENS_INGEST_KEY）")

    report = sync(args.host, args.ingest_key, root=args.root, session=args.session,
                  limit=args.limit, with_content=not args.no_content,
                  dry_run=args.dry_run, agent_key=args.agent_key)
    print("会话 %d 个，trace %d 条，span %d 个" % (
        report["sessions"], report["traces"], report["spans"]))
    if args.dry_run:
        print("dry-run：未发送")
    else:
        print("已上报 trace %d 条" % report["pushedTraces"])
    if report["errors"]:
        print("错误 %d 条：" % len(report["errors"]))
        for err in report["errors"][:5]:
            print("  ", err)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
