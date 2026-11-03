"""CI 集成测试（R1-4）：PR 评论渲染与幂等更新。

验收项：
1. 评论带隐藏 marker（幂等定位）与 Gate 结论标题；
2. 只列出未通过的检查项，PASS 不刷屏；
3. Gate verification 不可用时明确写"不得作为放行依据"；
4. 与基线对比给出 PASS→FAIL / FAIL→PASS 与不可比原因；prompt 版本不同要显式提示；
5. 同一条 PR 重复运行只更新同一条评论（PATCH），不会新建第二条；
6. dry-run 不发网络请求；PR 号可从事件载荷解析；
7. 评论里不含样本正文（只有计数与标识）。
"""
import importlib
import json
import os
import tempfile

import pytest

from emonlens import ci


def _run(pass_count=8, fail_count=0, unknown=0, error=0, incomplete=0):
    return {
        "id": "run-42",
        "status": "COMPLETED",
        "itemCount": 10,
        "passCount": pass_count,
        "failCount": fail_count,
        "unknownCount": unknown,
        "errorCount": error,
        "incompleteCount": incomplete,
        "coverage": 100.0,
        "passRate": 80.0,
        "promptName": "support-assistant",
        "promptVersion": 3,
        "promptLabel": "production",
        "runLabel": None,
        "commitSha": "abcdef0123456789",
    }


def _gate(decision="PASS", checks=None):
    return {
        "id": "gate-7",
        "decision": decision,
        "policyKey": "release-admission",
        "checks": checks if checks is not None else [
            {"code": "COVERAGE", "status": "PASS", "actual": "coverage=100.0%"},
        ],
    }


def test_render_comment_contains_marker_and_conclusion():
    body = ci.render_comment(ci.build_report(_run(), _gate(), {"usable": True, "reasons": []},
                                             host="http://emonlens.test"))
    assert body.startswith(ci.MARKER)
    assert "✅ PASS" in body
    assert "`run-42`" in body
    assert "`gate-7`" in body
    assert "support-assistant" in body
    assert "abcdef012345" in body
    assert "全部 Gate 检查项通过。" in body


def test_render_comment_lists_only_failing_checks():
    gate = _gate(decision="BLOCKED", checks=[
        {"code": "COVERAGE", "status": "PASS", "actual": "coverage=100.0%"},
        {"code": "UNKNOWN_BUDGET", "status": "BLOCKED", "actual": "unknownCount=2"},
        {"code": "FAIL_THRESHOLD", "status": "FAIL", "actual": "failCount=3"},
    ])
    report = ci.build_report(_run(unknown=2, fail_count=3), gate,
                             {"usable": False, "reasons": ["DECISION_BLOCKED", "EXPIRED"]})
    body = ci.render_comment(report)
    assert "⛔ BLOCKED" in body
    assert "UNKNOWN_BUDGET" in body and "FAIL_THRESHOLD" in body
    assert "COVERAGE" not in body
    assert "不可用（不得作为放行依据）" in body
    assert "Gate 已过期" in body
    assert [check["code"] for check in report["checks"]] == ["UNKNOWN_BUDGET", "FAIL_THRESHOLD"]


def test_render_comment_includes_baseline_and_prompt_change_hint():
    baseline = {"id": "run-40", "passCount": 7, "failCount": 1, "coverage": 100.0,
                "gate": {"decision": "PASS"}}
    diff = {"compatible": True,
            "summary": {"passToFail": 1, "failToPass": 0},
            "promptComparison": {"changed": True}}
    body = ci.render_comment(ci.build_report(_run(), _gate(), {"usable": True}, baseline, diff))
    assert "基线 run `run-40`" in body
    assert "PASS→FAIL 1 条" in body
    assert "prompt 版本不同" in body


def test_render_comment_reports_incomparable_diff():
    baseline = {"id": "run-40", "passCount": 7, "failCount": 0, "coverage": 90.0}
    diff = {"compatible": False, "reasons": ["SAMPLE_SET_MISMATCH"], "summary": {}}
    body = ci.render_comment(ci.build_report(_run(), _gate(), {"usable": True}, baseline, diff))
    assert "不可比原因：SAMPLE_SET_MISMATCH" in body


def test_upsert_comment_updates_existing_marker_comment(monkeypatch):
    calls = []

    def fake_request(method, url, token, body=None):
        calls.append({"method": method, "url": url, "body": body})
        if method == "GET":
            return [{"id": 11, "body": "unrelated"}, {"id": 22, "body": ci.MARKER + "\nold"}]
        return {"id": 22}

    monkeypatch.setattr(ci, "_request", fake_request)
    result = ci.upsert_comment("acme/repo", 5, ci.MARKER + "\nnew", "gh-token")

    assert result == {"action": "updated", "commentId": 22}
    assert [call["method"] for call in calls] == ["GET", "PATCH"]
    assert calls[1]["url"].endswith("/repos/acme/repo/issues/5/comments/22")
    assert calls[1]["body"]["body"].endswith("new")


def test_upsert_comment_creates_when_absent(monkeypatch):
    calls = []

    def fake_request(method, url, token, body=None):
        calls.append(method)
        if method == "GET":
            return []
        return {"id": 33}

    monkeypatch.setattr(ci, "_request", fake_request)
    result = ci.upsert_comment("acme/repo", 9, ci.MARKER + "\nbody", "gh-token")
    assert result == {"action": "created", "commentId": 33}
    assert calls == ["GET", "POST"]


def test_upsert_comment_validates_repo_and_token():
    with pytest.raises(ci.CiError):
        ci.upsert_comment("", 1, "body", "token")
    with pytest.raises(ci.CiError):
        ci.upsert_comment("acme/repo", 0, "body", "token")


def test_run_comment_dry_run_does_not_touch_network(tmp_path, monkeypatch):
    report_path = tmp_path / "report.json"
    report_path.write_text(json.dumps(ci.build_report(_run(), _gate(), {"usable": True})),
                           encoding="utf-8")

    def boom(*args, **kwargs):
        raise AssertionError("network must not be used in dry-run")

    monkeypatch.setattr(ci, "_request", boom)
    result = ci.run_comment(str(report_path), None, None, None, dry_run=True)
    assert result["action"] == "dry-run"
    assert ci.MARKER in result["body"]


def test_pr_number_from_event(tmp_path, monkeypatch):
    payload = tmp_path / "event.json"
    payload.write_text(json.dumps({"number": 17, "pull_request": {"number": 17}}), encoding="utf-8")
    monkeypatch.setenv("GITHUB_EVENT_PATH", str(payload))
    assert ci._pr_number_from_event() == 17

    payload.write_text(json.dumps({"pull_request": {"number": 21}}), encoding="utf-8")
    assert ci._pr_number_from_event() == 21

    monkeypatch.setenv("GITHUB_EVENT_PATH", str(tmp_path / "missing.json"))
    assert ci._pr_number_from_event() is None


def test_ci_module_main_exit_codes(tmp_path, monkeypatch, capsys):
    report_path = tmp_path / "report.json"
    report_path.write_text(json.dumps(ci.build_report(_run(), _gate(), {"usable": True})),
                           encoding="utf-8")
    assert ci._main(["--report", str(report_path), "--dry-run"]) == 0
    assert ci.MARKER in capsys.readouterr().out

    assert ci._main(["--report", str(tmp_path / "absent.json"), "--dry-run"]) == 4
