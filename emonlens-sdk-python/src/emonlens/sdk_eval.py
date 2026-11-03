"""SDK 评测器运行时：在用户环境执行 scorer，结果回写 EmonLens 任务账本。

服务端的 SDK 评估器只声明输入契约（spec.requiredFields），判定逻辑留在用户代码里；
本模块负责闭环：拉取待评测任务 → 执行 scorer → 回写结果。

用法一（CI 一条命令）::

    emonlens eval run --dataset ds1 --evaluator ev1 --wait --sdk-runner ./my_scorers.py

用法二（编程接口）::

    import emonlens

    @emonlens.scorer("amount-check")
    def check(sample: emonlens.SdkSample):
        return sample.raw["expectedOutput"] in (sample.raw.get("candidateOutput") or "")

    result = emonlens.evaluate(dataset_id=..., evaluator_ids=[...], wait=True)
    summary = emonlens.run_sdk_scorers(result["runId"], "./my_scorers.py")

scorer 返回值按宽容度归一：bool（True=PASS/False=FAIL）、数值（0-1 之外按 UNKNOWN）、
dict（{"verdict"/"score"/"reason"/"reasonCode"}）或 ScorerResult。scorer 抛异常记 ERROR，
不会让整批回写失败。
"""
from __future__ import annotations

import importlib.util
import json
import os
import time
import urllib.parse
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from .evaluate import (
    EmonLensError,
    _host,
    _request,
    _short,
    _token,
)

TERMINAL_RUN_STATES = {"COMPLETED", "PARTIAL_FAILED", "FAILED", "INTERRUPTED", "CANCELLED"}
VALID_VERDICTS = {"PASS", "FAIL", "UNKNOWN", "ERROR"}


@dataclass
class SdkSample:
    """一条待评测任务：task 定位信息 + 样本载荷（无 eval:content scope 时正文为空）。"""

    task_id: str
    item_id: str
    evaluator_key: str
    evaluator_version: Optional[int]
    required_fields: List[str] = field(default_factory=list)
    content_restricted: bool = False
    raw: Dict[str, Any] = field(default_factory=dict)

    @property
    def input(self) -> Optional[str]:
        return self.raw.get("input")

    @property
    def candidate_output(self) -> Optional[str]:
        return self.raw.get("candidateOutput")

    @property
    def expected_output(self) -> Optional[str]:
        return self.raw.get("expectedOutput")

    def missing_fields(self) -> List[str]:
        """契约里声明但载荷缺失的字段；scorer 可据此返回 UNKNOWN 而不是硬判。"""
        aliases = {
            "input": "input",
            "candidate_output": "candidateOutput",
            "expected_output": "expectedOutput",
            "retrieval_context": "retrievalContext",
            "expected_tools": "expectedTools",
            "tools_called": "toolsCalled",
            "turns": "turns",
            "trace_digest": "traceDigest",
        }
        missing = []
        for name in self.required_fields:
            key = aliases.get(name, name)
            if not self.raw.get(key):
                missing.append(name)
        return missing


@dataclass
class ScorerResult:
    """scorer 的判定结果；verdict 必须是 PASS/FAIL/UNKNOWN/ERROR。"""

    verdict: str
    score: Optional[float] = None
    reason: Optional[str] = None
    reason_code: Optional[str] = None
    model: Optional[str] = None
    prompt_tokens: Optional[int] = None
    completion_tokens: Optional[int] = None
    latency_ms: Optional[int] = None


@dataclass
class ScorerOutcome:
    """单条任务的回写结果：applied / duplicate / conflict / rejected / error。"""

    task_id: str
    item_id: str
    evaluator_key: str
    outcome: str
    detail: Dict[str, Any] = field(default_factory=dict)


# 注册表：evaluator_key -> scorer callable；模块被 import 时经 @scorer 填充
SCORERS: Dict[str, Callable[[SdkSample], Any]] = {}


def scorer(evaluator_key: str) -> Callable:
    """把函数注册为某个 evaluator_key 的 scorer；重复注册覆盖并告警。"""

    def decorator(func: Callable[[SdkSample], Any]) -> Callable:
        if evaluator_key in SCORERS and SCORERS[evaluator_key] is not func:
            print("warning: scorer for %s overwritten by %s"
                  % (evaluator_key, getattr(func, "__name__", func)), file=__import__("sys").stderr)
        SCORERS[evaluator_key] = func
        return func

    return decorator


def load_scorer_module(path: str) -> Dict[str, Callable[[SdkSample], Any]]:
    """加载 scorer 模块文件；@emonlens.scorer 装饰器在 import 时完成注册。"""
    before = set(SCORERS)
    spec = importlib.util.spec_from_file_location("emonlens_sdk_scorers", path)
    if spec is None or spec.loader is None:
        raise EmonLensError("cannot load scorer module: %s" % path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return {key: SCORERS[key] for key in SCORERS if key not in before}


def fetch_sdk_tasks(run_id: str, host: Optional[str] = None,
                    api_token: Optional[str] = None) -> Dict[str, Any]:
    """拉取 run 的 SDK 任务清单；正文是否包含取决于 token 是否有 eval:content scope。"""
    if not run_id:
        raise EmonLensError("run_id is required")
    data = _request("GET", _host(host) + "/api/v1/open/eval-runs/"
                    + urllib.parse.quote(run_id) + "/sdk-tasks", _token(api_token))
    data.setdefault("records", [])
    return data


def push_sdk_results(run_id: str, results: List[Dict[str, Any]],
                     host: Optional[str] = None, api_token: Optional[str] = None) -> Dict[str, Any]:
    """把 scorer 结果回写到任务账本；终态不可覆盖，幂等重复返回 DUPLICATE。"""
    if not run_id:
        raise EmonLensError("run_id is required")
    if not results:
        raise EmonLensError("results is empty; nothing to push")
    return _request("POST", _host(host) + "/api/v1/open/eval-runs/"
                    + urllib.parse.quote(run_id) + "/results", _token(api_token),
                    {"results": results})


def run_sdk_scorers(run_id: str, runner: Any, host: Optional[str] = None,
                    api_token: Optional[str] = None,
                    raise_on_conflict: bool = False) -> Dict[str, Any]:
    """对一个已终态的 run 执行全部 SDK scorer 并回写。

    runner：scorer 模块文件路径，或 {evaluator_key: callable} 字典。
    返回汇总 {"runId", "executed", "pushed", "outcomes"}；conflict 在
    raise_on_conflict=True 时抛 EmonLensError（默认只记录，交给 Gate 拦截）。
    """
    scorers = _resolve_scorers(runner)
    listing = fetch_sdk_tasks(run_id, host=host, api_token=api_token)
    outcomes: List[ScorerOutcome] = []
    pushes: List[Dict[str, Any]] = []
    started = time.monotonic()
    for record in listing.get("records") or []:
        sample = _sample_from(record, content_restricted=bool(listing.get("contentRestricted")))
        func = scorers.get(sample.evaluator_key)
        if func is None:
            outcomes.append(ScorerOutcome(sample.task_id, sample.item_id, sample.evaluator_key,
                                          "error", {"reasonCode": "SCORER_NOT_REGISTERED",
                                                    "reason": "no scorer registered for %s"
                                                              % sample.evaluator_key}))
            continue
        result = _invoke_scorer(func, sample)
        outcomes.append(ScorerOutcome(sample.task_id, sample.item_id, sample.evaluator_key,
                                      "scored", {"verdict": result.verdict}))
        pushes.append({
            "itemId": sample.item_id,
            "evaluatorKey": sample.evaluator_key,
            "verdict": result.verdict,
            "score": result.score,
            "reasonCode": result.reason_code,
            "reason": result.reason,
            "model": result.model,
            "promptTokens": result.prompt_tokens,
            "completionTokens": result.completion_tokens,
            "latencyMs": result.latency_ms,
        })
    pushed: Dict[str, Any] = {}
    if pushes:
        pushed = push_sdk_results(run_id, pushes, host=host, api_token=api_token)
        for entry in pushed.get("results") or []:
            key = (entry.get("itemId"), entry.get("evaluatorKey"))
            for outcome in outcomes:
                if (outcome.item_id, outcome.evaluator_key) == key:
                    outcome.outcome = str(entry.get("outcome") or outcome.outcome).lower()
                    # outcome 是归一后的小写状态；其余字段进 detail，防止大写原值覆盖
                    outcome.detail = {k: v for k, v in entry.items()
                                      if k not in ("itemId", "evaluatorKey", "outcome")}
                    if outcome.outcome == "conflict" and raise_on_conflict:
                        raise EmonLensError(
                            "sdk result conflict: itemId=%s evaluator=%s settled=%s"
                            % (outcome.item_id, outcome.evaluator_key,
                               outcome.detail.get("settledVerdict")))
                    break
    return {
        "runId": run_id,
        "runStatus": listing.get("runStatus"),
        "executed": len(outcomes),
        "pushed": sum(1 for o in outcomes if o.outcome == "applied"),
        "duplicates": sum(1 for o in outcomes if o.outcome == "duplicate"),
        "conflicts": sum(1 for o in outcomes if o.outcome == "conflict"),
        "openSdkTasks": pushed.get("openSdkTasks", listing.get("openSdkTasks")),
        "elapsedMs": int((time.monotonic() - started) * 1000),
        "outcomes": [{"taskId": o.task_id, "itemId": o.item_id, "evaluatorKey": o.evaluator_key,
                      "outcome": o.outcome, **o.detail} for o in outcomes],
    }


def _resolve_scorers(runner: Any) -> Dict[str, Callable[[SdkSample], Any]]:
    if isinstance(runner, (str, bytes, os.PathLike)):
        return load_scorer_module(os.fspath(runner))
    if isinstance(runner, dict):
        return dict(runner)
    raise EmonLensError("runner must be a module path or {evaluator_key: callable} dict")


def _sample_from(record: Dict[str, Any], content_restricted: bool) -> SdkSample:
    return SdkSample(
        task_id=str(record.get("taskId") or ""),
        item_id=str(record.get("itemId") or ""),
        evaluator_key=str(record.get("evaluatorKey") or ""),
        evaluator_version=record.get("evaluatorVersion"),
        required_fields=list(record.get("requiredFields") or []),
        content_restricted=content_restricted,
        raw=dict(record.get("sample") or {}),
    )


def _invoke_scorer(func: Callable[[SdkSample], Any], sample: SdkSample) -> ScorerResult:
    started = time.monotonic()
    try:
        return _normalize_result(func(sample), started)
    except Exception as exc:  # scorer 是用户代码：异常记 ERROR，不让整批失败
        return ScorerResult(
            verdict="ERROR",
            reason_code="SCORER_RAISED",
            reason="%s: %s" % (type(exc).__name__, _short(str(exc), 300)),
            latency_ms=int((time.monotonic() - started) * 1000),
        )


def _normalize_result(value: Any, started: float) -> ScorerResult:
    latency = int((time.monotonic() - started) * 1000)
    if isinstance(value, ScorerResult):
        value.verdict = _normalize_verdict(value.verdict)
        if value.latency_ms is None:
            value.latency_ms = latency
        return value
    if isinstance(value, bool):
        return ScorerResult(verdict="PASS" if value else "FAIL", latency_ms=latency)
    if isinstance(value, (int, float)):
        return ScorerResult(verdict="PASS" if 0 <= float(value) < 1 else "UNKNOWN",
                            score=float(value), latency_ms=latency)
    if isinstance(value, dict):
        verdict = _normalize_verdict(value.get("verdict") or value.get("label"))
        score = value.get("score")
        return ScorerResult(
            verdict=verdict,
            score=float(score) if isinstance(score, (int, float)) else None,
            reason=_short(str(v)) if (v := value.get("reason")) else None,
            reason_code=str(value["reasonCode"]) if value.get("reasonCode") else None,
            model=value.get("model"),
            prompt_tokens=value.get("promptTokens"),
            completion_tokens=value.get("completionTokens"),
            latency_ms=latency,
        )
    raise EmonLensError("scorer returned unsupported type: %s" % type(value).__name__)


def _normalize_verdict(verdict: Any) -> str:
    raw = str(verdict or "").strip().upper()
    return raw if raw in VALID_VERDICTS else "UNKNOWN"


def summary_json(summary: Dict[str, Any]) -> str:
    return json.dumps(summary, ensure_ascii=False, indent=2)
