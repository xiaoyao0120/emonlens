"""CI 集成（R1-4）：把一次评测的结果渲染成 PR 评论，并幂等更新同一条评论。

设计要点：
- 只依赖标准库（CI 环境不装额外依赖也能跑）；
- 评论内容只包含计数、Gate 检查项、prompt/commit 标识，**不含样本正文**；
- 同一条 PR 上反复运行只更新一条评论（按隐藏 marker 定位），不刷屏；
- 渲染逻辑是纯函数，便于单测；网络部分失败不静默（CI 里"看起来成功其实没评论"最难查）。
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any, Dict, List, Optional

MARKER = "<!-- emonlens:eval -->"
DEFAULT_API = "https://api.github.com"
DECISION_LABELS = {"PASS": "✅ PASS", "FAIL": "❌ FAIL", "BLOCKED": "⛔ BLOCKED"}
VERIFICATION_REASON_LABELS = {
    "EXPIRED": "Gate 已过期",
    "SUPERSEDED": "已被更新的 Gate 取代",
    "CANDIDATE_FINGERPRINT_MISSING": "缺少候选指纹",
    "CANDIDATE_FINGERPRINT_CHANGED": "候选指纹已变化",
    "RUN_MISSING": "run 已不存在",
}


class CiError(RuntimeError):
    """CI 辅助失败（配置、网络、评论接口错误）。"""


def build_report(run: Optional[Dict[str, Any]],
                 gate: Optional[Dict[str, Any]] = None,
                 verification: Optional[Dict[str, Any]] = None,
                 baseline: Optional[Dict[str, Any]] = None,
                 baseline_diff: Optional[Dict[str, Any]] = None,
                 prompt: Optional[Dict[str, Any]] = None,
                 commit_sha: Optional[str] = None,
                 host: Optional[str] = None) -> Dict[str, Any]:
    """把 run / gate / verification / baseline 汇总成评论所需的最小数据集。"""
    run = run or {}
    gate = gate or {}
    verification = verification or {}
    report: Dict[str, Any] = {
        "runId": run.get("id") or run.get("runId"),
        "status": run.get("status"),
        "itemCount": run.get("itemCount"),
        "passCount": run.get("passCount"),
        "failCount": run.get("failCount"),
        "unknownCount": run.get("unknownCount"),
        "errorCount": run.get("errorCount"),
        "incompleteCount": run.get("incompleteCount"),
        "coverage": run.get("coverage"),
        "passRate": run.get("passRate"),
        "gateId": gate.get("id"),
        "decision": gate.get("decision"),
        "policyKey": gate.get("policyKey"),
        "checks": _failing_checks(gate.get("checks")),
        "usable": verification.get("usable"),
        "verificationReasons": verification.get("reasons") or [],
        "prompt": prompt or {
            "name": run.get("promptName"),
            "version": run.get("promptVersion"),
            "label": run.get("promptLabel"),
        },
        "runLabel": run.get("runLabel"),
        "commitSha": commit_sha or run.get("commitSha"),
        "host": (host or os.environ.get("EMONLENS_HOST") or "").rstrip("/"),
    }
    if baseline:
        report["baseline"] = {
            "runId": baseline.get("id") or baseline.get("runId"),
            "passCount": baseline.get("passCount"),
            "failCount": baseline.get("failCount"),
            "coverage": baseline.get("coverage"),
            "decision": (baseline.get("gate") or {}).get("decision"),
        }
    if baseline_diff:
        summary = baseline_diff.get("summary") or {}
        report["baselineDiff"] = {
            "compatible": baseline_diff.get("compatible"),
            "reasons": baseline_diff.get("reasons") or [],
            "passToFail": summary.get("passToFail"),
            "failToPass": summary.get("failToPass"),
            "promptChanged": (baseline_diff.get("promptComparison") or {}).get("changed"),
        }
    return report


def _failing_checks(checks: Any) -> List[Dict[str, Any]]:
    if not isinstance(checks, list):
        return []
    rows = []
    for check in checks:
        status = str((check or {}).get("status") or "").upper()
        if status in ("PASS", "SKIPPED", ""):
            continue
        rows.append({
            "code": check.get("code"),
            "status": status,
            "detail": check.get("actual") or check.get("detail") or check.get("message"),
        })
    return rows


def render_comment(report: Dict[str, Any]) -> str:
    """渲染 PR 评论 markdown（稳定、幂等、不含正文）。"""
    decision = str(report.get("decision") or "UNKNOWN").upper()
    headline = DECISION_LABELS.get(decision, "⚠️ " + decision)
    lines: List[str] = [MARKER, "### EmonLens 评测门禁：" + headline, ""]
    lines.append("| 指标 | 值 |")
    lines.append("| --- | --- |")
    lines.append("| run | `%s`（%s） |" % (_dash(report.get("runId")), _dash(report.get("status"))))
    lines.append("| 样本 | %s 条，通过 %s / 失败 %s |"
                 % (_dash(report.get("itemCount")), _dash(report.get("passCount")),
                    _dash(report.get("failCount"))))
    lines.append("| UNKNOWN / ERROR / 未执行 | %s / %s / %s |"
                 % (_dash(report.get("unknownCount")), _dash(report.get("errorCount")),
                    _dash(report.get("incompleteCount"))))
    lines.append("| 覆盖率 / 通过率 | %s%% / %s%% |"
                 % (_dash(report.get("coverage")), _dash(report.get("passRate"))))
    lines.append("| Gate | `%s`（policy=%s） |"
                 % (_dash(report.get("gateId")), _dash(report.get("policyKey"))))
    prompt = report.get("prompt") or {}
    if prompt.get("name"):
        lines.append("| Prompt | `%s` v%s（%s） |"
                     % (prompt.get("name"), _dash(prompt.get("version")),
                        _dash(prompt.get("label"))))
    if report.get("commitSha"):
        lines.append("| 提交 | `%s` |" % _short_sha(report.get("commitSha")))
    if report.get("runLabel"):
        lines.append("| 运行标签 | `%s` |" % report.get("runLabel"))
    lines.append("")

    checks = report.get("checks") or []
    if checks:
        lines.append("**未通过的 Gate 检查项**")
        lines.append("")
        lines.append("| 检查项 | 结论 | 说明 |")
        lines.append("| --- | --- | --- |")
        for check in checks:
            lines.append("| `%s` | %s | %s |" % (check.get("code"), check.get("status"),
                                                 _dash(check.get("detail"))))
        lines.append("")
    elif decision == "PASS":
        lines.append("全部 Gate 检查项通过。")
        lines.append("")

    reasons = report.get("verificationReasons") or []
    if report.get("usable") is False or reasons:
        lines.append("**Gate 可用性**：" + ("可用" if report.get("usable") else "不可用（不得作为放行依据）"))
        for reason in reasons:
            key = str(reason).split(":")[0]
            lines.append("- `%s`%s" % (reason, "（%s）" % VERIFICATION_REASON_LABELS[key]
                                       if key in VERIFICATION_REASON_LABELS else ""))
        lines.append("")

    baseline = report.get("baseline") or {}
    if baseline:
        lines.append("**与基线对比**：基线 run `%s`（通过 %s / 失败 %s，Gate %s）"
                     % (_dash(baseline.get("runId")), _dash(baseline.get("passCount")),
                        _dash(baseline.get("failCount")), _dash(baseline.get("decision"))))
        diff = report.get("baselineDiff") or {}
        if diff:
            if diff.get("compatible") is False:
                lines.append("- 不可比原因：%s" % ", ".join(diff.get("reasons") or ["UNKNOWN"]))
            else:
                lines.append("- PASS→FAIL %s 条，FAIL→PASS %s 条"
                             % (_dash(diff.get("passToFail")), _dash(diff.get("failToPass"))))
            if diff.get("promptChanged"):
                lines.append("- 注意：两次 run 绑定的 prompt 版本不同，结论只说明相对差异")
        lines.append("")

    host = report.get("host")
    if host:
        lines.append("<sub>由 EmonLens 生成 · %s/quality?runId=%s</sub>"
                     % (host, _dash(report.get("runId"))))
    else:
        lines.append("<sub>由 EmonLens 生成</sub>")
    return "\n".join(lines)


def _dash(value: Any) -> str:
    return "—" if value is None or value == "" else str(value)


def _short_sha(sha: Any) -> str:
    text = str(sha or "")
    return text[:12]


def _request(method: str, url: str, token: str, body: Optional[Dict[str, Any]] = None) -> Any:
    data = None
    headers = {"Accept": "application/vnd.github+json", "Authorization": "Bearer " + token,
               "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "emonlens-ci"}
    if body is not None:
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            payload = response.read().decode("utf-8")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        raise CiError("GitHub API HTTP %s %s: %s" % (exc.code, url, detail[:400])) from exc
    except urllib.error.URLError as exc:
        raise CiError("GitHub API request failed %s: %s" % (url, exc.reason)) from exc
    if not payload:
        return None
    try:
        return json.loads(payload)
    except ValueError:
        return None


def find_existing_comment(repo: str, issue_number: int, token: str,
                          api_url: str = DEFAULT_API) -> Optional[int]:
    """按隐藏 marker 找到本 bot 之前发的评论 id（没有返回 None）。"""
    page = 1
    while page <= 10:
        url = ("%s/repos/%s/issues/%d/comments?per_page=100&page=%d"
               % (api_url.rstrip("/"), repo, issue_number, page))
        comments = _request("GET", url, token) or []
        if not comments:
            return None
        for comment in comments:
            if MARKER in str((comment or {}).get("body") or ""):
                return int(comment.get("id"))
        if len(comments) < 100:
            return None
        page += 1
    return None


def upsert_comment(repo: str, issue_number: int, body: str, token: str,
                   api_url: str = DEFAULT_API) -> Dict[str, Any]:
    """幂等更新：已有 marker 评论则 PATCH，否则 POST 新建。返回 {action, commentId}。"""
    if not repo or "/" not in repo:
        raise CiError("repo must look like owner/name, got: %r" % repo)
    if not issue_number:
        raise CiError("issue_number is required")
    existing = find_existing_comment(repo, issue_number, token, api_url)
    base = "%s/repos/%s/issues/%d/comments" % (api_url.rstrip("/"), repo, issue_number)
    if existing:
        _request("PATCH", base + "/%d" % existing, token, {"body": body})
        return {"action": "updated", "commentId": existing}
    created = _request("POST", base, token, {"body": body}) or {}
    return {"action": "created", "commentId": created.get("id")}


def run_comment(report_path: str, repo: Optional[str], issue_number: Optional[int],
                token: Optional[str], api_url: str = DEFAULT_API,
                dry_run: bool = False) -> Dict[str, Any]:
    """从 report JSON 渲染并（可选）发布评论。dry_run 只打印。"""
    with open(report_path, "r", encoding="utf-8") as handle:
        report = json.load(handle)
    body = render_comment(report)
    if dry_run or not repo:
        return {"action": "dry-run", "body": body}
    if not issue_number:
        raise CiError("PR number is required to update the comment")
    if not token:
        raise CiError("GitHub token is required to update the comment")
    result = upsert_comment(repo, issue_number, body, token, api_url)
    result["body"] = body
    return result


def _main(argv: Optional[List[str]] = None) -> int:
    import argparse

    parser = argparse.ArgumentParser(prog="emonlens.ci",
                                     description="Render / upsert the EmonLens PR comment")
    parser.add_argument("--report", required=True, help="report JSON produced by `emonlens eval run --report-json`")
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--pr", type=int, default=_pr_number_from_event())
    parser.add_argument("--token", default=os.environ.get("GITHUB_TOKEN"))
    parser.add_argument("--api-url", default=os.environ.get("GITHUB_API_URL", DEFAULT_API))
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args(argv)
    try:
        result = run_comment(args.report, args.repo, args.pr, args.token,
                             args.api_url, args.dry_run)
    except CiError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 4
    except OSError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 4
    if result.get("action") == "dry-run":
        print(result.get("body"))
    else:
        print("comment %s: %s" % (result.get("action"), result.get("commentId")))
    return 0


def _pr_number_from_event() -> Optional[int]:
    """从 GitHub 事件载荷里取 PR 号（PR 事件用 number，其他事件兜底 pull_request.number）。"""
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path or not os.path.exists(path):
        return None
    try:
        with open(path, "r", encoding="utf-8") as handle:
            payload = json.load(handle)
    except (OSError, ValueError):
        return None
    number = payload.get("number")
    if isinstance(number, int):
        return number
    pr = payload.get("pull_request") or {}
    return pr.get("number") if isinstance(pr.get("number"), int) else None


if __name__ == "__main__":
    sys.exit(_main())
