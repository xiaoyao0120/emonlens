"""评测客户端：调用 EmonLens 开放 API 提交数据集 run、轮询、消费 Gate。

零第三方依赖，只用 urllib。与观测 SDK 的 init() 分离：
- 观测用 EMONLENS_INGEST_KEY（X-Trust-Key）
- 评测用 EMONLENS_API_TOKEN（X-Api-Token，scope eval:run/eval:read/score:read）

未配置 host/token 时直接报错，不静默成功——CI 里"什么都没跑但退出 0"是最危险的失败。
"""
from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any, Dict, Iterable, List, Optional

TERMINAL_RUN_STATES = {"COMPLETED", "PARTIAL_FAILED", "FAILED", "INTERRUPTED", "CANCELLED"}
DEFAULT_POLL_SECONDS = 3.0
MAX_POLL_SECONDS = 10.0
# 轮询期间容忍的连续网络/服务端瞬时失败次数：CI 可能跑几十分钟，
# 一次抖动就判死太脆；连续超过该次数仍失败才抛错。
MAX_POLL_FAILURES = 3


class EmonLensError(RuntimeError):
    """评测请求失败（配置、权限、HTTP、服务端错误）。"""


class EmonLensTimeoutError(EmonLensError):
    """run 在 timeout 内未到终态。"""


class EmonLensAssertionError(AssertionError):
    """assert_test 的断言失败；消息不含样本正文。"""


def _host(host: Optional[str]) -> str:
    value = host or os.environ.get("EMONLENS_HOST", "")
    if not value:
        raise EmonLensError("EMONLENS_HOST is required (or pass host=...)")
    return value.rstrip("/")


def _token(api_token: Optional[str]) -> str:
    value = api_token or os.environ.get("EMONLENS_API_TOKEN", "")
    if not value:
        raise EmonLensError("EMONLENS_API_TOKEN is required (or pass api_token=...); "
                            "create a token with eval:run/eval:read/score:read scope")
    return value


def _request(method: str, url: str, token: str, body: Optional[Dict[str, Any]] = None,
             idempotency_key: Optional[str] = None) -> Dict[str, Any]:
    data = None
    headers = {"X-Api-Token": token, "Accept": "application/json"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise EmonLensError("HTTP %s %s: %s" % (exc.code, url, _short(detail))) from exc
    except urllib.error.URLError as exc:
        raise EmonLensError("request failed %s: %s" % (url, exc.reason)) from exc
    try:
        parsed = json.loads(payload)
    except ValueError as exc:
        raise EmonLensError("invalid JSON response from %s: %s" % (url, _short(payload))) from exc
    if parsed.get("code") not in (0, None):
        raise EmonLensError("API error %s: %s" % (parsed.get("code"), parsed.get("message")))
    return parsed.get("data") or {}


def evaluate(
    dataset_id: str,
    suite_id: Optional[str] = None,
    evaluator_ids: Optional[Iterable[str]] = None,
    mode: str = "TRACE",
    revision_id: Optional[str] = None,
    candidate_label: Optional[str] = None,
    system_prompt: Optional[str] = None,
    model: Optional[str] = None,
    max_external_calls: Optional[int] = None,
    max_tokens: Optional[int] = None,
    review_mode: Optional[str] = None,
    prompt_name: Optional[str] = None,
    prompt_version: Optional[int] = None,
    prompt_label: Optional[str] = None,
    run_label: Optional[str] = None,
    commit_sha: Optional[str] = None,
    wait: bool = True,
    timeout: float = 900.0,
    poll_interval: float = DEFAULT_POLL_SECONDS,
    idempotency_key: Optional[str] = None,
    host: Optional[str] = None,
    api_token: Optional[str] = None,
) -> Dict[str, Any]:
    """提交一次数据集评测；wait=True 时轮询到终态并返回 run + gate。

    prompt_name/prompt_version/prompt_label（R1-3）把 run 绑定到不可变的 prompt 版本，
    该引用进候选指纹，并在 Gate verification 里随 production 指针移动一起失效。
    run_label/commit_sha（R1-4）用于 CI 基线解析与提交回溯。
    """
    if not dataset_id:
        raise EmonLensError("dataset_id is required")
    if not suite_id and not evaluator_ids:
        raise EmonLensError("suite_id or evaluator_ids is required")
    if prompt_version is not None and not prompt_name:
        raise EmonLensError("prompt_version requires prompt_name")

    body: Dict[str, Any] = {"datasetId": dataset_id, "mode": mode or "TRACE"}
    if suite_id:
        body["suiteId"] = suite_id
    if evaluator_ids:
        body["evaluatorIds"] = list(evaluator_ids)
    for key, value in (
        ("revisionId", revision_id),
        ("candidateLabel", candidate_label),
        ("systemPrompt", system_prompt),
        ("model", model),
        ("maxExternalCalls", max_external_calls),
        ("maxTokens", max_tokens),
        ("reviewMode", review_mode),
        ("promptName", prompt_name),
        ("promptVersion", prompt_version),
        ("promptLabel", prompt_label),
        ("runLabel", run_label),
        ("commitSha", commit_sha),
    ):
        if value is not None:
            body[key] = value

    key = idempotency_key or ("sdk-" + uuid.uuid4().hex)
    base = _host(host)
    token = _token(api_token)
    data = _request("POST", base + "/api/v1/open/eval-runs", token, body, key)
    run_id = data.get("runId")
    if not run_id:
        raise EmonLensError("server returned no runId: %s" % _short(json.dumps(data)))
    if not wait:
        return data
    return wait_for_run(run_id, timeout=timeout, poll_interval=poll_interval,
                        host=host, api_token=api_token)


def wait_for_run(
    run_id: str,
    timeout: float = 900.0,
    poll_interval: float = DEFAULT_POLL_SECONDS,
    host: Optional[str] = None,
    api_token: Optional[str] = None,
) -> Dict[str, Any]:
    """轮询 run 到终态；超时抛 EmonLensTimeoutError。"""
    base = _host(host)
    token = _token(api_token)
    deadline = time.monotonic() + max(1.0, timeout)
    delay = max(0.2, poll_interval)
    last: Dict[str, Any] = {}
    failures = 0
    while True:
        try:
            data = _request("GET", base + "/api/v1/open/eval-runs/" + urllib.parse.quote(run_id),
                            token)
            failures = 0
        except EmonLensError as exc:
            failures += 1
            if failures >= MAX_POLL_FAILURES:
                raise
            if time.monotonic() >= deadline:
                raise EmonLensTimeoutError(
                    "run %s did not finish within %.0fs (poll failed: %s)"
                    % (run_id, timeout, exc))
            time.sleep(delay)
            delay = min(MAX_POLL_SECONDS, delay * 1.5)
            continue
        last = data
        run = data.get("run") or {}
        status = run.get("status")
        if status in TERMINAL_RUN_STATES:
            data.setdefault("runId", run_id)
            return data
        if time.monotonic() >= deadline:
            raise EmonLensTimeoutError(
                "run %s did not finish within %.0fs (last status=%s)"
                % (run_id, timeout, status))
        time.sleep(delay)
        delay = min(MAX_POLL_SECONDS, delay * 1.5)


def assert_test(result: Dict[str, Any], fail_on: Optional[Iterable[str]] = None) -> bool:
    """CI 断言：FAIL/BLOCKED/UNKNOWN 默认都失败；返回 True 或抛 AssertionError。"""
    fail_on = {str(v).upper() for v in (fail_on or ("FAIL", "BLOCKED", "UNKNOWN"))}
    run = result.get("run") or {}
    gate = result.get("gate") or {}
    status = str(run.get("status") or "")
    decision = str(gate.get("decision") or "").upper()
    unknown = int(run.get("unknownCount") or 0)
    fail = int(run.get("failCount") or 0)
    error = int(run.get("errorCount") or 0)
    incomplete = int(run.get("incompleteCount") or 0)

    reasons: List[str] = []
    if status in ("FAILED", "PARTIAL_FAILED", "INTERRUPTED", "CANCELLED"):
        reasons.append("run status=" + status)
    if decision and decision in fail_on:
        reasons.append("gate=" + decision)
    if "UNKNOWN" in fail_on and unknown > 0:
        reasons.append("unknownCount=%d" % unknown)
    if "FAIL" in fail_on and fail > 0:
        reasons.append("failCount=%d" % fail)
    if error > 0:
        reasons.append("errorCount=%d" % error)
    if incomplete > 0:
        reasons.append("incompleteCount=%d" % incomplete)
    if reasons:
        raise EmonLensAssertionError(
            "EmonLens eval failed: runId=%s, %s"
            % (run.get("id") or result.get("runId") or "?", "; ".join(reasons)))
    return True


def gate_check(gate_id: str, host: Optional[str] = None,
               api_token: Optional[str] = None) -> Dict[str, Any]:
    """读取历史 Gate 的 verification；CI 必须消费 usable/decision，而不是只读旧 PASS。"""
    if not gate_id:
        raise EmonLensError("gate_id is required")
    base = _host(host)
    token = _token(api_token)
    return _request("GET", base + "/api/v1/open/quality-gates/"
                    + urllib.parse.quote(gate_id) + "/verification", token)


def get_run(run_id: str, host: Optional[str] = None,
            api_token: Optional[str] = None) -> Dict[str, Any]:
    """读取 run 状态与最新 Gate（不返回样本正文）。"""
    if not run_id:
        raise EmonLensError("run_id is required")
    base = _host(host)
    token = _token(api_token)
    return _request("GET", base + "/api/v1/open/eval-runs/" + urllib.parse.quote(run_id), token)


def baseline_run(dataset_id: str, label: str = "baseline", host: Optional[str] = None,
                 api_token: Optional[str] = None) -> Dict[str, Any]:
    """解析 CI 基线（R1-4）：按 runLabel 取该数据集最近一条 run + 最新 Gate。

    返回 {"resolved": bool, "run": {...}, "gate": {...}}；没有基线时 resolved=False，
    由调用方决定是“跳过基线对比”还是“失败”，SDK 不替用户猜。
    """
    if not dataset_id:
        raise EmonLensError("dataset_id is required")
    base = _host(host)
    token = _token(api_token)
    query = urllib.parse.urlencode({"datasetId": dataset_id, "label": label or "baseline"})
    return _request("GET", base + "/api/v1/open/eval-runs?" + query, token)


def run_diff(run_id: str, baseline_run_id: Optional[str] = None, label: str = "baseline",
             host: Optional[str] = None, api_token: Optional[str] = None) -> Dict[str, Any]:
    """候选与基线的 diff 摘要（不含样本正文）。"""
    if not run_id:
        raise EmonLensError("run_id is required")
    base = _host(host)
    token = _token(api_token)
    params = {"label": label or "baseline"}
    if baseline_run_id:
        params["baselineRunId"] = baseline_run_id
    url = (base + "/api/v1/open/eval-runs/" + urllib.parse.quote(run_id)
           + "/diff?" + urllib.parse.urlencode(params))
    return _request("GET", url, token)


def _short(text: str, limit: int = 500) -> str:
    text = text or ""
    return text if len(text) <= limit else text[:limit] + "..."
