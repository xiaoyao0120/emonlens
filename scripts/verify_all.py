#!/usr/bin/env python3
"""EmonLens 全功能黑盒验证（对运行实例逐域走查核心闭环）。

用法：
    python3 scripts/verify_all.py [BASE_URL] [USER] [PASS]
    密码必填：产品不提供默认口令，缺 PASS 时直接退出（退出码 2）。
    例：python3 scripts/verify_all.py http://127.0.0.1:8300 admin <密码>

产出：逐项 PASS/FAIL 清单 + 汇总；退出码 0=全绿 1=存在失败 2=参数错误。
说明：配置类资源（监测规则/评估器/价格/开放 token）验证后清理；e2e- 前缀的
trace 数据保留（服务端保留策略自然过期），汇总末尾附手工清理 SQL。
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timedelta

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8300").rstrip("/")
USER = sys.argv[2] if len(sys.argv) > 2 else "admin"
PASS = sys.argv[3] if len(sys.argv) > 3 else ""
if not PASS:
    print("错误：缺少密码参数。产品不提供默认口令，请显式传入 admin 密码：", file=sys.stderr)
    print(f"  python3 scripts/verify_all.py {BASE} admin <密码>", file=sys.stderr)
    sys.exit(2)

RESULTS = []
TOKEN = ""


def check(domain, name, ok_flag, detail=""):
    RESULTS.append((domain, name, bool(ok_flag), str(detail)))
    mark = "\033[32mPASS\033[0m" if ok_flag else "\033[31mFAIL\033[0m"
    print(f"  {mark} [{domain}] {name}" + (f" — {detail}" if (detail and not ok_flag) else ""))


def req(method, path, body=None, token=None, key=None, api_token=None, idempotency=None):
    """返回 (http_code, json_obj)。平台契约：业务错误也是 HTTP 200 + code!=0。"""
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if key:
        headers["X-Trust-Key"] = key
    if api_token:
        headers["X-Api-Token"] = api_token
    if idempotency:
        headers["Idempotency-Key"] = idempotency
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=30) as resp:
            code, payload = resp.status, resp.read()
    except urllib.error.HTTPError as e:
        code, payload = e.code, e.read()
    except Exception as e:
        return 0, {"_error": str(e)}
    try:
        return code, json.loads(payload)
    except Exception:
        return code, {"_raw": payload[:200].decode("utf-8", "replace")}


def ok(j):
    return isinstance(j, dict) and j.get("code") == 0


def data_of(j):
    return j.get("data") if isinstance(j, dict) else None


def section(title):
    print(f"\n== {title} ==")


def now_iso(offset_s=0):
    # 服务端按本地墙上时间解析 naive ISO（与 hooks/UI 一致）：这里必须用本地时区，
    # 否则 e2e 数据整体落到 8 小时前，排序与时间范围断言全部错位
    return (datetime.now() + timedelta(seconds=offset_s)).strftime("%Y-%m-%dT%H:%M:%S")


# prod 部署门禁会禁用 dev-trust-key-001；对生产实例验收时用环境变量传入真实 key
INGEST_KEY = os.environ.get("EMONLENS_INGEST_KEY", "dev-trust-key-001")


def ingest_spans(spans, key=None, expect_ok=True, name=None):
    key = key or INGEST_KEY
    code, j = req("POST", "/api/v1/ingest/events", spans, key=key)
    if expect_ok:
        check("ingest", name or f"ingest 受理 {len(spans)} 条 span",
              ok(j) and (data_of(j) or {}).get("acceptedCount") == len(spans), j)
    return j


# ---------------------------------------------------------------- 1. 认证与 RBAC
def verify_auth():
    section("1. 认证与 RBAC")
    global TOKEN
    code, j = req("POST", "/api/v1/auth/login", {"username": USER, "password": PASS})
    login_ok = ok(j) and (data_of(j) or {}).get("token")
    check("auth", "登录成功并返回 JWT", login_ok, j)
    if not login_ok:
        biz = (j or {}).get("code")
        print(f"  提示：登录失败（业务码 {biz}）——用实际 admin 密码重跑：make verify PASS=<密码>",
              file=sys.stderr)
    TOKEN = (data_of(j) or {}).get("token", "")
    code, j = req("GET", "/api/v1/auth/me", token=TOKEN)
    me = data_of(j) or {}
    check("auth", "auth/me 返回当前用户与权限集",
          ok(j) and ((me.get("user") or {}).get("username") == USER) and len(me.get("permissions") or []) > 0, j)
    code, j = req("POST", "/api/v1/auth/login", {"username": USER, "password": "wrong-password"})
    check("auth", "错误密码被拒（业务码非 0）", isinstance(j, dict) and j.get("code") not in (0, None), j)
    code, j = req("POST", "/api/v1/traces/search", {"pageNumber": 1})
    check("auth", "无 token 访问业务 API 被拒", isinstance(j, dict) and j.get("code") not in (0, None), j)
    code, j = req("GET", "/api/v1/sys/menus", token=TOKEN)
    menus = data_of(j) or []
    check("rbac", "菜单元数据可读（登录即可）", ok(j) and len(menus) > 0, j)
    code, j = req("GET", "/api/v1/sys/users?pageNumber=1&pageSize=5", token=TOKEN)
    check("rbac", "用户管理列表（admin 权限）", ok(j), j)
    code, j = req("GET", "/api/v1/sys/roles", token=TOKEN)
    check("rbac", "角色列表（系统内置 3 角色）", ok(j) and len(data_of(j) or []) >= 3, j)


# ---------------------------------------------------------------- 2. 数据准备
def seed_e2e_data():
    section("2. 数据准备（ingest 构造完整链路数据）")
    tid = "e2e-trace-" + uuid.uuid4().hex[:8]
    sid = "e2e-sess-" + uuid.uuid4().hex[:6]
    t0 = now_iso(-120)
    spans = [
        {"traceId": tid, "spanId": "root", "agentKey": "e2e-agent", "agentVersion": "v1",
         "spanType": "agent", "spanName": "e2e_root", "sessionId": sid, "userId": "e2e-user",
         "startTime": t0, "endTime": now_iso(-100), "status": "OK",
         "input": "e2e 验证：工单 #1024 的处理进度", "output": "额度为 ¥50,000",
         "modelName": "e2e-model", "inputTokens": 1000, "outputTokens": 200},
        {"traceId": tid, "spanId": "llm-1", "parentSpanId": "root", "agentKey": "e2e-agent",
         "spanType": "llm", "spanName": "call_model", "sessionId": sid, "userId": "e2e-user",
         "startTime": t0, "endTime": now_iso(-110), "status": "OK",
         "input": "工单 #1024 的处理进度是多少", "output": "¥50,000",
         "modelName": "e2e-model", "inputTokens": 800, "outputTokens": 150,
         "cachedInputTokens": 640},
        {"traceId": tid, "spanId": "tool-1", "parentSpanId": "root", "agentKey": "e2e-agent",
         "spanType": "tool", "spanName": "query_credit", "toolName": "query_credit",
         "sessionId": sid, "startTime": now_iso(-115), "endTime": now_iso(-112), "status": "OK",
         "input": "{\"id\":\"zhangsan\"}", "output": "{\"balance\":50000}"},
    ]
    ingest_spans(spans)
    time.sleep(2)  # 等 worker 批处理落库 + trace 收口
    return tid, sid


# ---------------------------------------------------------------- 3. Trace 域
def verify_trace(tid):
    section("3. Trace 检索 / 详情 / 标签 / 评论 / 人工评分")
    # 按 agent 过滤而非依赖全局排序：真实 agent 流量大时（如 codex 高频轮次）首页不含 e2e trace
    code, j = req("POST", "/api/v1/traces/search",
                  {"pageNumber": 1, "pageSize": 10, "agentKeys": ["e2e-agent"]}, token=TOKEN)
    recs = (data_of(j) or {}).get("records") or []
    hit = next((r for r in recs if r.get("traceId") == tid), None)
    check("trace", "搜索可见新上报的 trace", hit is not None, f"{len(recs)} 条中未见 {tid}")
    if not hit:
        return
    check("trace", "summary 带模型与 span 计数",
          hit.get("modelName") == "e2e-model" and (hit.get("spanCount") or 0) >= 3, hit)
    code, j = req("GET", f"/api/v1/traces/{tid}", token=TOKEN)
    det = data_of(j) or {}
    spans = det.get("spans") or []
    llm = next((s for s in spans if s.get("spanType") == "llm"), None)
    check("trace", "详情含 llm 子 span 且带 tokens",
          llm is not None and (llm.get("inputTokens") or 0) == 800, llm)
    check("trace", "llm span 缓存命中量（cachedInputTokens）透出",
          llm is not None and (llm.get("cachedInputTokens") or 0) == 640, llm)
    check("trace", "详情含输入摘要",
          bool(((det.get("summary") or {}).get("inputPreview"))), (det.get("summary") or {}).get("inputPreview"))
    code, j = req("POST", f"/api/v1/traces/{tid}/tags", {"tags": ["e2e-verify"]}, token=TOKEN)
    check("trace", "打标签", ok(j), j)
    code, j = req("GET", f"/api/v1/traces/{tid}/tags", token=TOKEN)
    tags = data_of(j) or []
    check("trace", "读回标签", "e2e-verify" in tags if tags and isinstance(tags[0], str)
          else any(t.get("tag") == "e2e-verify" for t in tags), tags)
    code, j = req("POST", f"/api/v1/traces/{tid}/comments", {"content": "e2e 验证评论"}, token=TOKEN)
    check("trace", "添加评论", ok(j), j)
    code, j = req("GET", f"/api/v1/traces/{tid}/comments", token=TOKEN)
    comments = data_of(j) or []
    check("trace", "读回评论", any("e2e 验证评论" in ((c.get("content") or "") if isinstance(c, dict) else str(c))
                                  for c in comments), comments)
    code, j = req("POST", f"/api/v1/traces/{tid}/scores", {"score": 4.5, "comment": "e2e 人工评分"}, token=TOKEN)
    check("trace", "提交人工评分", ok(j), j)
    code, j = req("GET", f"/api/v1/traces/{tid}/scores", token=TOKEN)
    check("trace", "读回人工评分", ok(j) and len(data_of(j) or []) >= 1, j)


# ---------------------------------------------------------------- 4. Session
def verify_session(sid):
    section("4. 会话聚合")
    code, j = req("GET", "/api/v1/sessions?pageNumber=1&pageSize=20", token=TOKEN)
    recs = (data_of(j) or {}).get("records") or []
    hit = next((s for s in recs if s.get("sessionId") == sid), None)
    check("session", "会话列表聚合到 e2e 会话", hit is not None, j)
    if not hit:
        return
    check("session", "会话带调用计数", (hit.get("traceCount") or 0) >= 1, hit)
    code, j = req("GET", f"/api/v1/sessions/{sid}", token=TOKEN)
    det = data_of(j) or {}
    check("session", "会话详情含 tokens 汇总",
          (det.get("totalTokens") or 0) >= 950, det.get("totalTokens"))
    check("session", "会话详情缓存命中量汇总",
          (det.get("cachedInputTokens") or 0) >= 640, det.get("cachedInputTokens"))
    check("session", "会话详情含时间线", len(det.get("traces") or []) >= 1, len(det.get("traces") or []))


# ---------------------------------------------------------------- 5. Agent 与 IngestKey
def verify_agent_key():
    section("5. Agent 同步 / IngestKey 签发与吊销")
    code, j = req("PUT", "/api/v1/agents/e2e-agent",
                  {"name": "e2e 验证 Agent", "riskLevel": "MEDIUM"}, token=TOKEN)
    check("agent", "同步 agent 元数据（upsert）", ok(j), j)
    code, j = req("GET", "/api/v1/agents", token=TOKEN)
    agents = data_of(j) or []
    check("agent", "agent 列表可见", any((a.get("agentKey") or "") == "e2e-agent" for a in agents), j)
    code, j = req("GET", "/api/v1/agents/health", token=TOKEN)
    check("agent", "接入健康度聚合", ok(j), j)
    code, j = req("POST", "/api/v1/ingest-keys", {"name": "e2e-key"}, token=TOKEN)
    d = data_of(j) or {}
    key = d.get("key") or d.get("plainKey") or ""
    issued_key_id = d.get("id")          # 用签发返回的 id 精确吊销（列表匹配会误删他人同名 key）
    check("agent", "签发 ingest key（明文仅返回一次）", ok(j) and bool(key), d)
    if key:
        spans = [{"traceId": "e2e-key-" + uuid.uuid4().hex[:6], "spanId": "root",
                  "agentKey": "e2e-key-agent", "spanType": "agent", "spanName": "root",
                  "startTime": now_iso(), "endTime": now_iso(), "status": "OK"}]
        code, j = req("POST", "/api/v1/ingest/events", spans, key=key)
        check("agent", "新签发 key 可正常上报", ok(j) and (data_of(j) or {}).get("acceptedCount") == 1, j)
    code, j = req("GET", "/api/v1/ingest-keys", token=TOKEN)
    keys = data_of(j) or []
    check("agent", "key 列表不回显明文", ok(j) and all(not k.get("key") for k in keys), keys)
    new_id = issued_key_id or next((k.get("id") for k in keys if k.get("name") == "e2e-key"), None)
    if new_id:
        code, j = req("DELETE", f"/api/v1/ingest-keys/{new_id}", token=TOKEN)
        check("agent", "吊销 ingest key", ok(j), j)
        if key:
            spans = [{"traceId": "e2e-revoked-" + uuid.uuid4().hex[:6], "spanId": "root",
                      "agentKey": "e2e-key-agent", "spanType": "agent", "spanName": "root",
                      "startTime": now_iso(), "endTime": now_iso(), "status": "OK"}]
            code, j = req("POST", "/api/v1/ingest/events", spans, key=key)
            check("agent", "已吊销 key 上报被拒", isinstance(j, dict) and j.get("code") != 0, j)


# ---------------------------------------------------------------- 6. 监测 → Finding/Incident
def _find_monitor(key):
    code, j = req("GET", "/api/v1/monitors?pageNumber=1&pageSize=50", token=TOKEN)
    recs = (data_of(j) or {}).get("records") if isinstance(data_of(j), dict) else data_of(j)
    return next((m for m in (recs or []) if (m.get("monitorKey") or "") == key), None)


def ensure_monitor():
    """确保 VALIDATION 规则存在且启用。必须在坏 trace 入库前调用：
    检测是 trace 完成事件驱动的，事后启用不会回溯已完成的 trace。"""
    mon = _find_monitor("e2e-llm-error")
    if mon:
        code, j = req("POST", f"/api/v1/monitors/{mon['id']}/enable", token=TOKEN)
        check("monitor", "复用既有 e2e 监测规则并启用", ok(j), j)
        return mon
    code, j = req("POST", "/api/v1/monitors",
                  {"agentKey": "e2e-agent", "monitorKey": "e2e-llm-error", "kind": "VALIDATION",
                   "spec": {"kind": "VALIDATION", "spanType": "llm", "where": "status == 'ERROR'"},
                   "severity": "HIGH"}, token=TOKEN)
    check("monitor", "创建 VALIDATION 监测规则", ok(j), j)
    return _find_monitor("e2e-llm-error")


def verify_monitor_flow(bad_tid, mon):
    section("6. 行为监测 → Finding/Incident 告警链路")
    code, j = req("GET", "/api/v1/monitors?pageNumber=1&pageSize=20", token=TOKEN)
    recs = (data_of(j) or {}).get("records") if isinstance(data_of(j), dict) else data_of(j)
    check("monitor", "规则列表可见", any((m.get("monitorKey") or "") == "e2e-llm-error" for m in (recs or [])), j)
    if mon:
        code, j = req("POST", f"/api/v1/monitors/{mon['id']}/dry-run", {"limit": 20}, token=TOKEN)
        dry = data_of(j) or []
        check("monitor", "规则 dry-run（逐 trace 命中明细）",
              ok(j) and isinstance(dry, list) and all(
                  {"traceId", "matched", "violations"} <= set(x) for x in dry[:3]),
              f"{len(dry)} 条" if ok(j) else j)
    # 检测由 TRACE_COMPLETED 事件异步驱动；轮询等待，避免事件消费高峰时误报
    deadline = time.time() + 30
    code, j = req("GET", f"/api/v1/findings?traceId={bad_tid}&pageNumber=1&pageSize=10", token=TOKEN)
    recs = (data_of(j) or {}).get("records") if isinstance(data_of(j), dict) else data_of(j)
    while time.time() < deadline and not (ok(j) and len(recs or []) >= 1):
        time.sleep(2)
        code, j = req("GET", f"/api/v1/findings?traceId={bad_tid}&pageNumber=1&pageSize=10", token=TOKEN)
        recs = (data_of(j) or {}).get("records") if isinstance(data_of(j), dict) else data_of(j)
    check("monitor", "llm ERROR 触发 Finding", ok(j) and len(recs or []) >= 1, j)
    code, j = req("GET", "/api/v1/incidents?pageNumber=1&pageSize=10", token=TOKEN)
    check("incident", "Incident 列表可达", ok(j), j)
    code, j = req("GET", "/api/v1/event-dead?pageNumber=1&pageSize=5", token=TOKEN)
    check("deadletter", "死信队列接口", ok(j), j)
    return mon


# ---------------------------------------------------------------- 7. 评估器与评估
def _find_evaluator(key):
    code, j = req("GET", "/api/v1/evaluators?pageNumber=1&pageSize=50", token=TOKEN)
    recs = (data_of(j) or {}).get("records") if isinstance(data_of(j), dict) else data_of(j)
    return next((e for e in (recs or []) if (e.get("evaluatorKey") or "") == key), None)


def verify_evaluation(tid):
    section("7. 评估器 / 评估结果")
    ev = _find_evaluator("e2e-no-error")
    if ev:
        code, j = req("POST", f"/api/v1/evaluators/{ev['id']}/enable", token=TOKEN)
        check("evaluator", "复用既有 e2e 评估器并启用", ok(j), j)
    else:
        code, j = req("POST", "/api/v1/evaluators",
                      {"evaluatorKey": "e2e-no-error", "type": "EXPRESSION",
                       "spec": {"type": "EXPRESSION", "passWhen": "error_count == 0"}}, token=TOKEN)
        check("evaluator", "创建 EXPRESSION 评估器", ok(j), j)
        ev = _find_evaluator("e2e-no-error")
    code, j = req("GET", "/api/v1/evaluators?pageNumber=1&pageSize=20", token=TOKEN)
    recs = (data_of(j) or {}).get("records") if isinstance(data_of(j), dict) else data_of(j)
    check("evaluator", "评估器列表可见", any((e.get("evaluatorKey") or "") == "e2e-no-error" for e in (recs or [])), j)
    if not ev:
        return None
    code, j = req("POST", "/api/v1/evaluations/run",
                  {"evaluatorId": ev["id"], "traceIds": [tid]}, token=TOKEN)
    check("evaluation", "对 trace 执行评估", ok(j), j)
    time.sleep(2)
    code, j = req("GET", "/api/v1/eval-results?pageNumber=1&pageSize=10", token=TOKEN)
    recs = (data_of(j) or {}).get("records") or []
    hit = next((r for r in recs if r.get("traceId") == tid), None)
    check("evaluation", "评估结果落库且结论明确", hit is not None and hit.get("label") in ("PASS", "FAIL"), hit)
    code, j = req("GET", "/api/v1/eval-results/overview", token=TOKEN)
    check("evaluation", "评估结果总览", ok(j), j)
    code, j = req("POST", f"/api/v1/evaluators/{ev['id']}/test", {"traceId": tid}, token=TOKEN)
    check("evaluator", "评估器单条试测", ok(j), j)
    return ev["id"]


# ---------------------------------------------------------------- 8. 数据集
def verify_dataset(tid, evaluator_id):
    section("8. 数据集 / 回归实验")
    ds_key = "e2e-ds-" + uuid.uuid4().hex[:6]
    code, j = req("POST", "/api/v1/datasets", {"datasetKey": ds_key, "description": "e2e 回归集"}, token=TOKEN)
    ds = data_of(j) or {}
    ds_id = ds.get("id") or ""
    check("dataset", "创建数据集", ok(j) and bool(ds_id), j)
    if not ds_id:
        return
    code, j = req("POST", f"/api/v1/datasets/{ds_id}/items",
                  {"input": "工单 #1024 的处理进度", "expectedOutput": "¥50,000", "traceId": tid}, token=TOKEN)
    check("dataset", "添加数据集条目（关联 trace）", ok(j), j)
    code, j = req("GET", f"/api/v1/datasets/{ds_id}", token=TOKEN)
    items = (data_of(j) or {}).get("items") or []
    check("dataset", "读回条目", len(items) >= 1, j)
    code, j = req("POST", f"/api/v1/datasets/{ds_id}/runs",
                  {"evaluatorId": evaluator_id, "mode": "TRACE"}, token=TOKEN)
    run = data_of(j) or {}
    run_id = run.get("runId") or run.get("id") or ""
    check("dataset", "发起回归实验（TRACE 模式）", ok(j), j)
    time.sleep(2)
    if run_id:
        code, j = req("GET", f"/api/v1/datasets/{ds_id}/runs/{run_id}", token=TOKEN)
        d2 = (data_of(j) or {}).get("run") or {}
        check("dataset", "回归结果可查（itemCount ≥1）", ok(j) and (d2.get("itemCount") or 0) >= 1, d2)
    item_id = (items[0] or {}).get("id") if items else None
    if item_id:
        code, j = req("DELETE", f"/api/v1/datasets/{ds_id}/items/{item_id}", token=TOKEN)
        check("dataset", "删除条目", ok(j), j)
    code, j = req("DELETE", f"/api/v1/datasets/{ds_id}", token=TOKEN)
    check("dataset", "删除数据集", ok(j), j)
    return evaluator_id


# ---------------------------------------------------------------- 9. Prompt / Playground
def verify_prompt_playground():
    section("9. Prompt 版本化 / Playground")
    name = "e2e-prompt"
    code, j = req("POST", "/api/v1/prompts",
                  {"name": name, "prompt": "你是 e2e 助手。问题：{{question}}",
                   "config": {"model": "e2e-model", "temperature": 0.1}, "setProduction": True}, token=TOKEN)
    check("prompt", "创建 prompt 并置 production", ok(j), j)
    code, j = req("POST", "/api/v1/prompts",
                  {"name": name, "prompt": "你是 e2e 助手 v2。问题：{{question}}",
                   "config": {"model": "e2e-model"}}, token=TOKEN)
    check("prompt", "追加 prompt v2", ok(j), j)
    code, j = req("GET", f"/api/v1/prompts/{name}/versions", token=TOKEN)
    versions = data_of(j) or []
    check("prompt", "版本列表 ≥2", len(versions) >= 2, versions)
    code, j = req("GET", f"/api/v1/prompts/{name}/usage", token=TOKEN)
    check("prompt", "版本用量统计", ok(j), j)
    code, j = req("GET", f"/api/v1/ingest/prompts/{name}/resolve?label=production", key=INGEST_KEY)
    d = data_of(j) or {}
    check("prompt", "数据面 resolve 拉取（SDK 通道）", ok(j) and (d.get("version") or 0) >= 1, d)
    code, j = req("POST", "/api/v1/playground/run",
                  {"prompt": "问题：{{question}}", "variables": {"question": "1+1"},
                   "model": "e2e-model"}, token=TOKEN)
    d = data_of(j) or {}
    check("playground", "试跑请求有明确结论（OK 或 LLM_UNAVAILABLE 降级）",
          ok(j) and d.get("status") in ("OK", "LLM_UNAVAILABLE", "ERROR"), d)
    code, j = req("DELETE", f"/api/v1/prompts/{name}", token=TOKEN)
    check("prompt", "删除 prompt", ok(j), j)


# ---------------------------------------------------------------- 9.1 矩阵 / prompt 绑定（0.2.0）
def verify_matrix_and_prompt_binding():
    section("9.1 Playground 矩阵 / prompt 版本绑定（0.2.0）")
    ds_name = "e2e-matrix-ds"
    code, j = req("POST", "/api/v1/datasets", {"datasetKey": ds_name}, token=TOKEN)
    dataset_id = (data_of(j) or {}).get("id") or ""
    if not dataset_id:
        code, j = req("GET", "/api/v1/datasets", token=TOKEN)
        for row in data_of(j) or []:
            if row.get("datasetKey") == ds_name:
                dataset_id = row.get("id")
    check("matrix", "准备矩阵用数据集", bool(dataset_id), j)
    if not dataset_id:
        return
    req("POST", f"/api/v1/datasets/{dataset_id}/items",
        {"input": "e2e 矩阵样本", "expectedOutput": "e2e"}, token=TOKEN)

    # production 同名唯一：带 setProduction 的新版本要把旧版本的 production 摘掉
    prompt_name = "e2e-bind-prompt"
    req("POST", "/api/v1/prompts", {"name": prompt_name, "prompt": "{{input}}", "setProduction": True}, token=TOKEN)
    req("POST", "/api/v1/prompts", {"name": prompt_name, "prompt": "{{input}} v2", "setProduction": True}, token=TOKEN)
    code, j = req("GET", f"/api/v1/prompts/{prompt_name}/versions", token=TOKEN)
    versions = data_of(j) or []
    production_versions = [v.get("version") for v in versions
                           if "production" in (v.get("labels") or [])]
    check("prompt", "production 同名唯一", len(production_versions) == 1, production_versions)

    # 矩阵预估（纯计算，不消耗 LLM）
    estimate_body = {"datasetId": dataset_id, "sampleLimit": 1,
                     "columns": [{"key": "c1", "label": "c1", "promptName": prompt_name,
                                  "promptLabel": "production", "model": "e2e-model"},
                                 {"key": "c2", "label": "c2", "prompt": "{{input}}", "model": "e2e-model"}]}
    code, j = req("POST", "/api/v1/playground/matrices/estimate", estimate_body, token=TOKEN)
    est = data_of(j) or {}
    check("matrix", "矩阵成本预估", ok(j) and est.get("cellCount") == 2, est)

    # 未确认成本必须拒绝受理
    code, j = req("POST", "/api/v1/playground/matrices", estimate_body, token=TOKEN,
                  idempotency="e2e-matrix-guard")
    check("matrix", "未确认成本时拒绝受理", not ok(j), j.get("message"))

    # 确认后受理（真实调用 LLM，失败也应留下可读状态）
    accepted_body = dict(estimate_body, confirmEstimate=True)
    code, j = req("POST", "/api/v1/playground/matrices", accepted_body, token=TOKEN,
                  idempotency="e2e-matrix-run")
    matrix_id = (data_of(j) or {}).get("id") or ""
    check("matrix", "矩阵受理（confirmEstimate=true）", bool(matrix_id), j)

    # 从 prompt 反查评估证据（没有 run 时是空列表，也是可用状态）
    code, j = req("GET", f"/api/v1/prompts/{prompt_name}/runs", token=TOKEN)
    check("prompt", "prompt → run 评估证据可查", ok(j), j)

    req("DELETE", f"/api/v1/prompts/{prompt_name}", token=TOKEN)
    req("DELETE", f"/api/v1/datasets/{dataset_id}", token=TOKEN)



# ---------------------------------------------------------------- 10. 标注 / 用户洞察 / 价格
def verify_ops_pages(tid):
    section("10. 标注队列 / 用户洞察 / 模型价格 / 用量")
    code, j = req("POST", "/api/v1/annotation-queues", {"name": "e2e 抽检", "description": "e2e"}, token=TOKEN)
    q = data_of(j) or {}
    qid = q.get("id") or ""
    check("annotation", "创建标注队列", ok(j) and bool(qid), j)
    if qid:
        code, j = req("POST", f"/api/v1/annotation-queues/{qid}/items", {"traceIds": [tid]}, token=TOKEN)
        check("annotation", "入队 trace", ok(j), j)
        code, j = req("GET", f"/api/v1/annotation-queues/{qid}", token=TOKEN)
        items = (data_of(j) or {}).get("items") or []
        check("annotation", "队列条目可读", len(items) >= 1, j)
        if items:
            code, j = req("POST", f"/api/v1/annotation-queues/{qid}/items/{items[0]['id']}/complete",
                          {"score": 4, "comment": "e2e"}, token=TOKEN)
            check("annotation", "完成一条标注", ok(j), j)
        code, j = req("DELETE", f"/api/v1/annotation-queues/{qid}", token=TOKEN)
        check("annotation", "删除标注队列", ok(j), j)
    code, j = req("GET", "/api/v1/users?pageNumber=1&pageSize=10", token=TOKEN)
    check("users", "用户洞察列表", ok(j), j)
    code, j = req("GET", "/api/v1/users/e2e-user/detail", token=TOKEN)
    check("users", "用户洞察详情", ok(j), j)
    code, j = req("POST", "/api/v1/model-prices",
                  {"model": "e2e-model", "inputPerMillion": 1.5, "outputPerMillion": 6.0, "currency": "USD"}, token=TOKEN)
    if not ok(j) and "already exists" in str(j.get("message", "")):
        # 上轮清理失败残留：按 id 删掉后重试
        code, j = req("GET", "/api/v1/model-prices", token=TOKEN)
        for p in (data_of(j) or []):
            if (p.get("model") or "") == "e2e-model":
                req("DELETE", f"/api/v1/model-prices/{p['id']}", token=TOKEN)
        code, j = req("POST", "/api/v1/model-prices",
                      {"model": "e2e-model", "inputPerMillion": 1.5, "outputPerMillion": 6.0, "currency": "USD"}, token=TOKEN)
    check("price", "配置模型价格", ok(j), j)
    price_id = None
    code, j = req("GET", "/api/v1/model-prices", token=TOKEN)
    for p in (data_of(j) or []):
        if (p.get("model") or "") == "e2e-model":
            price_id = p.get("id")
    check("price", "价格列表可见", price_id is not None, j)
    code, j = req("GET", "/api/v1/model-prices/usage", token=TOKEN)
    check("price", "用量成本页（含成本估算）", ok(j), j)


# ---------------------------------------------------------------- 11. 治理：通知/审计/开放API/设置
def verify_governance():
    section("11. 通知 / 审计 / 开放 API / 系统设置")
    code, j = req("GET", "/api/v1/notify/templates", token=TOKEN)
    check("notify", "通知模板列表（系统种子）", ok(j) and len(data_of(j) or []) >= 1, j)
    code, j = req("GET", "/api/v1/notify/senders", token=TOKEN)
    check("notify", "通知发件人列表", ok(j), j)
    code, j = req("GET", "/api/v1/notify/records?pageNumber=1&pageSize=5", token=TOKEN)
    check("notify", "通知发送记录", ok(j), j)
    code, j = req("GET", "/api/v1/audit-logs?pageNumber=1&pageSize=10", token=TOKEN)
    recs = (data_of(j) or {}).get("records") or []
    check("audit", "审计日志（本验证写操作留痕）", ok(j) and len(recs) >= 1, len(recs))
    code, j = req("POST", "/api/v1/open-tokens", {"name": "e2e-open", "scopes": "score:read"}, token=TOKEN)
    d = data_of(j) or {}
    tok = d.get("token") or d.get("accessToken") or ""
    check("openapi", "签发开放 API token（明文仅一次）", ok(j) and bool(tok), d)
    if tok:
        code, j = req("GET", "/api/v1/open/score/agents/e2e-agent")
        check("openapi", "无 token 访问开放 API 被拒", isinstance(j, dict) and j.get("code") != 0, j)
        code, j = req("GET", "/api/v1/open/score/agents/e2e-agent", api_token=tok)
        check("openapi", "带 token 读取信任分（scope 生效）", ok(j), j)
        ids = []
        code, j = req("GET", "/api/v1/open-tokens", token=TOKEN)
        ids = [t.get("id") for t in (data_of(j) or []) if t.get("name") == "e2e-open"]
        for i in ids:
            req("DELETE", f"/api/v1/open-tokens/{i}", token=TOKEN)
        if ids:
            code, j = req("GET", "/api/v1/open/score/agents/e2e-agent", api_token=tok)
            check("openapi", "吊销后 token 失效", isinstance(j, dict) and j.get("code") != 0, j)
    code, j = req("GET", "/api/v1/sys/settings/eval-llm", token=TOKEN)
    check("settings", "系统设置可读（LLM 评估设置）", ok(j), j)


# ---------------------------------------------------------------- 12. 评分/总览/限流/OTLP
def verify_score_dashboard():
    section("12. 信任评分 / 总览 / 限流 / OTLP")
    code, j = req("GET", "/api/v1/score/overview", token=TOKEN)
    check("score", "信任分总览", ok(j), j)
    # 全新部署：score-runner 启动后会做一次兜底重算；这里轮询等快照生成
    deadline = time.time() + 30
    code, j = req("GET", "/api/v1/score/agents/e2e-agent", token=TOKEN)
    while time.time() < deadline and not (ok(j) and (data_of(j) or {}).get("status")):
        time.sleep(2)
        code, j = req("GET", "/api/v1/score/agents/e2e-agent", token=TOKEN)
    check("score", "agent 信任分（含状态）", ok(j) and (data_of(j) or {}).get("status") in
          ("TRUSTED", "AT_RISK", "CRITICAL", "UNKNOWN", "INSUFFICIENT_EVIDENCE",
           "WATCH", "DEGRADED", "UNTRUSTED", "NO_DATA"), j)
    code, j = req("GET", "/api/v1/dashboard/summary", token=TOKEN)
    check("dashboard", "总览汇总可达", ok(j), j)
    code, j = req("GET", "/api/v1/observations/stats", token=TOKEN)
    d = data_of(j) or {}
    check("stats", "容量统计字段齐全", all(k in d for k in
          ("queueSize", "queueCapacity", "acceptedTotal", "droppedTotal",
           "rateLimitedTotal", "rateLimitedWorkspaces")), d)
    code, j = req("POST", "/api/v1/ingest/events", [], key="bad-key")
    check("ingest", "错误 ingest key 被拒", isinstance(j, dict) and j.get("code") != 0, j)
    otlp = {"resourceSpans": [{"resource": {"attributes": []}, "scopeSpans": [{
        "spans": [{"traceId": uuid.uuid4().hex + uuid.uuid4().hex[:16], "spanId": uuid.uuid4().hex[:16],
                   "name": "otlp-span", "kind": "SPAN_KIND_INTERNAL",
                   "startTimeUnixNano": str(int(time.time() * 1e9)),
                   "endTimeUnixNano": str(int((time.time() + 1) * 1e9)),
                   "attributes": [{"key": "emonlens.agent_key", "value": {"stringValue": "e2e-otlp"}}],
                   "status": {}}]}]}]}
    code, j = req("POST", "/api/v1/ingest/otlp/v1/traces", otlp, key=INGEST_KEY)
    check("otlp", "OTLP/JSON 上报受理", ok(j), j)


def cleanup(mon, ev_id, bad_tid):
    section("清理配置类资源")
    if mon and mon.get("id"):
        req("POST", f"/api/v1/monitors/{mon['id']}/disable", token=TOKEN)
        print(f"  监测规则 {mon['monitorKey']} 已停用（无删除 API，可经页面管理）")
    if ev_id:
        req("POST", f"/api/v1/evaluators/{ev_id}/disable", token=TOKEN)
        print("  评估器 e2e-no-error 已停用")
    code, j = req("GET", "/api/v1/model-prices", token=TOKEN)
    for p in (data_of(j) or []):
        if (p.get("model") or "") == "e2e-model" and p.get("id"):
            req("DELETE", f"/api/v1/model-prices/{p['id']}", token=TOKEN)
    print("  模型价格 e2e-model 已删除")
    print("  e2e- 前缀 trace 保留（自然过期），如需立即清理：")
    print("    DELETE FROM et_span_event WHERE trace_id LIKE 'e2e-%';")
    print("    DELETE FROM et_trace_summary WHERE trace_id LIKE 'e2e-%';")
    _ = bad_tid


def main():
    print(f"== EmonLens 全功能黑盒验证 @ {BASE} ==")
    verify_auth()
    if not TOKEN:
        print("登录失败，终止")
        return 1
    tid, sid = seed_e2e_data()
    mon = ensure_monitor()          # 先启用规则，再灌会命中的坏 trace（检测为完成事件驱动）
    bad_tid = "e2e-bad-" + uuid.uuid4().hex[:8]
    ingest_spans([
        {"traceId": bad_tid, "spanId": "root", "agentKey": "e2e-agent", "spanType": "agent",
         "spanName": "root", "startTime": now_iso(-30), "endTime": now_iso(-29), "status": "OK",
         "input": "触发验证规则"},
        {"traceId": bad_tid, "spanId": "llm-err", "parentSpanId": "root", "agentKey": "e2e-agent",
         "spanType": "llm", "spanName": "call_model", "startTime": now_iso(-30), "endTime": now_iso(-29),
         "status": "ERROR", "errorType": "RateLimitError", "modelName": "e2e-model",
         "input": "hi", "output": ""},
    ], name="ingest 构造 ERROR llm span（供监测规则触发）")
    verify_trace(tid)
    verify_session(sid)
    verify_agent_key()
    verify_monitor_flow(bad_tid, mon)
    ev_id = verify_evaluation(tid)
    verify_dataset(tid, ev_id)
    verify_prompt_playground()
    verify_matrix_and_prompt_binding()
    verify_ops_pages(tid)
    verify_governance()
    verify_score_dashboard()
    cleanup(mon, ev_id, bad_tid)
    total = len(RESULTS)
    fails = [r for r in RESULTS if not r[2]]
    print(f"\n== 汇总：{total - len(fails)}/{total} 通过，{len(fails)} 失败 ==")
    for domain, name, _, detail in fails:
        print(f"  FAIL [{domain}] {name} — {detail}")
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
