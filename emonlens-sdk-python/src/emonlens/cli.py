"""emonlens CLI：把评测/Gate 接进 CI。

退出码：
  0 PASS / verification usable
  2 FAIL
  3 BLOCKED / UNKNOWN / 证据不足
  4 配置、网络或服务端错误
  5 超时
"""
from __future__ import annotations

import argparse
import json
import os
import sys
from typing import List, Optional

from .evaluate import (
    EmonLensError,
    EmonLensTimeoutError,
    assert_test,
    baseline_run,
    evaluate,
    gate_check,
    run_diff,
    wait_for_run,
)
from .sdk_eval import run_sdk_scorers


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="emonlens", description="EmonLens evaluation CLI")
    parser.add_argument("--host", default=None, help="EmonLens base URL (or EMONLENS_HOST)")
    parser.add_argument("--api-token", default=None,
                        help="open API token (or EMONLENS_API_TOKEN)")
    sub = parser.add_subparsers(dest="command", required=True)

    eval_parser = sub.add_parser("eval", help="dataset evaluation")
    eval_sub = eval_parser.add_subparsers(dest="eval_command", required=True)

    run = eval_sub.add_parser("run", help="submit a dataset run and optionally wait")
    run.add_argument("--dataset", required=True, help="dataset id")
    group = run.add_mutually_exclusive_group(required=True)
    group.add_argument("--suite", help="immutable eval suite id")
    group.add_argument("--evaluator", action="append", help="evaluator id (repeatable)")
    run.add_argument("--mode", default="TRACE", choices=["TRACE", "REPLAY"])
    run.add_argument("--revision", default=None, help="dataset revision id")
    run.add_argument("--candidate-label", default=None)
    run.add_argument("--wait", action="store_true", default=False,
                     help="wait until run reaches a terminal state")
    run.add_argument("--timeout", type=float, default=900.0)
    run.add_argument("--poll-interval", type=float, default=3.0)
    run.add_argument("--json", action="store_true", help="machine-readable output")
    run.add_argument("--assert", action="store_true", dest="assert_result",
                     help="exit non-zero on FAIL/BLOCKED/UNKNOWN")
    run.add_argument("--sdk-runner", default=None, metavar="PATH",
                     help="Python 模块路径：run 终态后执行 @emonlens.scorer 并回写结果"
                          "（需要等待终态，自动启用 --wait）")
    # R1-3：把 run 绑定到不可变 prompt 版本
    run.add_argument("--prompt-name", default=None, help="bind the run to this prompt name")
    run.add_argument("--prompt-version", type=int, default=None,
                     help="pin an explicit prompt version (otherwise resolved by --prompt-label)")
    run.add_argument("--prompt-label", default=None,
                     help="prompt label used when --prompt-version is omitted (default production)")
    # R1-4：CI 基线、提交回溯与 PR 评论报告
    run.add_argument("--run-label", default=None, help="run label, e.g. baseline")
    run.add_argument("--commit-sha", default=None, help="commit SHA for traceability")
    run.add_argument("--baseline-label", default=None,
                     help="resolve this runLabel as baseline and include the diff in --report-json")
    run.add_argument("--report-json", default=None, metavar="PATH",
                     help="write a CI report (run/gate/verification/baseline diff) for emonlens.ci")

    sdk = eval_sub.add_parser("sdk", help="对已有 run 执行 SDK scorer 并回写")
    sdk.add_argument("--run", required=True, help="dataset run id")
    sdk.add_argument("--runner", required=True, metavar="PATH",
                     help="scorer 模块路径（@emonlens.scorer 注册）")
    sdk.add_argument("--json", action="store_true")

    gate_parser = sub.add_parser("gate", help="quality gate")
    gate_sub = gate_parser.add_subparsers(dest="gate_command", required=True)
    check = gate_sub.add_parser("check", help="verify a historical gate")
    target = check.add_mutually_exclusive_group(required=True)
    target.add_argument("--gate-id", default=None)
    target.add_argument("--run-id", default=None,
                        help="resolve the latest gate of this run, then verify it")
    check.add_argument("--json", action="store_true")

    ci_parser = sub.add_parser("ci", help="CI helpers (PR comment)")
    ci_sub = ci_parser.add_subparsers(dest="ci_command", required=True)
    comment = ci_sub.add_parser("comment", help="render and upsert the PR comment from a report")
    comment.add_argument("--report", required=True, help="report JSON from `eval run --report-json`")
    comment.add_argument("--repo", default=None, help="owner/name (or GITHUB_REPOSITORY)")
    comment.add_argument("--pr", type=int, default=None, help="PR number (or GITHUB_EVENT_PATH)")
    comment.add_argument("--token", default=None, help="GitHub token (or GITHUB_TOKEN)")
    comment.add_argument("--dry-run", action="store_true", help="print the comment instead of posting")
    return parser


def main(argv: Optional[List[str]] = None) -> int:
    args = _parser().parse_args(argv)
    try:
        if args.command == "eval":
            if getattr(args, "eval_command", None) == "sdk":
                return _eval_sdk(args)
            return _eval_run(args)
        if args.command == "gate":
            return _gate_check(args)
        if args.command == "ci":
            return _ci_comment(args)
        return 4
    except EmonLensTimeoutError as exc:
        print("timeout: %s" % exc, file=sys.stderr)
        return 5
    except EmonLensError as exc:
        print("error: %s" % exc, file=sys.stderr)
        return 4
    except AssertionError as exc:
        print(str(exc), file=sys.stderr)
        return 3


def _eval_run(args) -> int:
    sdk_runner = getattr(args, "sdk_runner", None)
    if sdk_runner and not args.wait:
        # SDK scorer 需要在 run 终态后执行，等待是前置条件
        args.wait = True
    if getattr(args, "report_json", None) and not args.wait:
        # 报告需要终态与 Gate 结论，未等待时只能给出 runId
        args.wait = True
    result = evaluate(
        dataset_id=args.dataset,
        suite_id=args.suite,
        evaluator_ids=args.evaluator,
        mode=args.mode,
        revision_id=args.revision,
        candidate_label=args.candidate_label,
        prompt_name=getattr(args, "prompt_name", None),
        prompt_version=getattr(args, "prompt_version", None),
        prompt_label=getattr(args, "prompt_label", None),
        run_label=getattr(args, "run_label", None),
        commit_sha=getattr(args, "commit_sha", None),
        wait=args.wait,
        timeout=args.timeout,
        poll_interval=args.poll_interval,
        host=args.host,
        api_token=args.api_token,
    )
    if sdk_runner and args.wait:
        result = _run_sdk_stage(result, sdk_runner, args)
    if getattr(args, "report_json", None):
        _write_report(result, args)
    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        run = result.get("run") or {}
        gate = result.get("gate") or {}
        print("runId=%s status=%s pass=%s fail=%s unknown=%s error=%s coverage=%s gate=%s"
              % (result.get("runId") or run.get("id"), run.get("status"),
                 run.get("passCount"), run.get("failCount"), run.get("unknownCount"),
                 run.get("errorCount"), run.get("coverage"), gate.get("decision")))
    if not args.wait:
        return 0
    if args.assert_result:
        assert_test(result)
        return 0
    return _exit_code(result)


def _write_report(result, args) -> None:
    """写 CI 报告：run + gate + verification + 基线 diff（不含样本正文）。"""
    from .ci import build_report

    run = result.get("run") or {}
    gate = result.get("gate") or {}
    verification = {}
    gate_id = gate.get("id")
    if gate_id:
        try:
            verification = gate_check(gate_id, host=args.host, api_token=args.api_token)
        except EmonLensError:
            verification = {}
    baseline = None
    diff = None
    label = getattr(args, "baseline_label", None)
    if label:
        try:
            resolved = baseline_run(args.dataset, label=label, host=args.host,
                                    api_token=args.api_token)
            if resolved.get("resolved"):
                baseline = resolved.get("run") or {}
                baseline["gate"] = resolved.get("gate")
                if run.get("id") and baseline.get("id") != run.get("id"):
                    diff = run_diff(run.get("id"), baseline.get("id"), label=label,
                                    host=args.host, api_token=args.api_token).get("diff")
        except EmonLensError:
            baseline = None
    report = build_report(run, gate, verification, baseline, diff,
                          commit_sha=getattr(args, "commit_sha", None), host=args.host)
    path = args.report_json
    with open(path, "w", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
    print("report: %s" % path)


def _eval_sdk(args) -> int:
    summary = run_sdk_scorers(args.run, args.runner, host=args.host, api_token=args.api_token)
    if args.json:
        print(json.dumps(summary, ensure_ascii=False, indent=2))
    else:
        print("runId=%s executed=%s applied=%s duplicates=%s conflicts=%s openSdkTasks=%s"
              % (summary.get("runId"), summary.get("executed"), summary.get("pushed"),
                 summary.get("duplicates"), summary.get("conflicts"),
                 summary.get("openSdkTasks")))
    if int(summary.get("conflicts") or 0) > 0:
        return 2
    return 0


def _run_sdk_stage(result, runner_path: str, args) -> dict:
    """run 终态后执行 SDK scorer；回写会改变计数，重新拉取 run + gate 再返回。"""
    summary = run_sdk_scorers(result.get("runId"), runner_path,
                              host=args.host, api_token=args.api_token)
    result["sdkRunner"] = summary
    refreshed = wait_for_run(result["runId"], timeout=args.timeout,
                             poll_interval=args.poll_interval,
                             host=args.host, api_token=args.api_token)
    result["run"] = refreshed.get("run") or result.get("run")
    result["gate"] = refreshed.get("gate") or result.get("gate")
    return result


def _gate_check(args) -> int:
    gate_id = args.gate_id
    if not gate_id:
        from .evaluate import get_run
        run_view = get_run(args.run_id, host=args.host, api_token=args.api_token)
        gate = run_view.get("gate") or {}
        gate_id = gate.get("id")
        if not gate_id:
            print("error: run %s has no quality gate yet" % args.run_id, file=sys.stderr)
            return 3
    verification = gate_check(gate_id, host=args.host, api_token=args.api_token)
    if args.json:
        print(json.dumps(verification, ensure_ascii=False, indent=2))
    else:
        print("gateId=%s usable=%s decision=%s reasons=%s"
              % (gate_id, verification.get("usable"), verification.get("decision"),
                 ",".join(verification.get("reasons") or [])))
    usable = bool(verification.get("usable"))
    decision = str(verification.get("decision") or "").upper()
    if usable and decision == "PASS":
        return 0
    if decision == "FAIL":
        return 2
    return 3


def _ci_comment(args) -> int:
    from .ci import CiError, run_comment

    try:
        result = run_comment(args.report, args.repo, args.pr, args.token,
                             os.environ.get("GITHUB_API_URL", "https://api.github.com"),
                             args.dry_run)
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


def _exit_code(result) -> int:
    run = result.get("run") or {}
    gate = result.get("gate") or {}
    status = str(run.get("status") or "")
    decision = str(gate.get("decision") or "").upper() if gate else ""
    if status in ("FAILED", "PARTIAL_FAILED", "INTERRUPTED", "CANCELLED") or decision == "FAIL":
        return 2
    if int(run.get("errorCount") or 0) > 0 or int(run.get("incompleteCount") or 0) > 0:
        return 2
    if int(run.get("unknownCount") or 0) > 0:
        return 3
    if decision == "BLOCKED":
        return 3
    return 0


if __name__ == "__main__":
    sys.exit(main())
