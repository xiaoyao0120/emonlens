import importlib
import json

from emonlens import cli


def _result(status="COMPLETED", decision="PASS", unknown=0, fail=0, error=0):
    return {
        "run": {
            "id": "run-1",
            "status": status,
            "passCount": 1,
            "failCount": fail,
            "unknownCount": unknown,
            "errorCount": error,
            "incompleteCount": 0,
            "coverage": 100.0,
        },
        "gate": {"decision": decision} if decision else None,
    }


def test_cli_eval_run_pass(monkeypatch):
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _result())
    assert cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait"]) == 0


def test_cli_eval_run_fail_and_blocked(monkeypatch):
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _result(decision="FAIL", fail=1))
    assert cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait"]) == 2
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _result(decision="BLOCKED", unknown=1))
    assert cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait"]) == 3


def test_cli_eval_json_and_assert(monkeypatch, capsys):
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _result())
    code = cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite",
                     "--wait", "--json", "--assert"])
    assert code == 0
    payload = json.loads(capsys.readouterr().out)
    assert payload["run"]["status"] == "COMPLETED"


def test_cli_eval_run_without_gate_passes_when_counts_clean(monkeypatch):
    result = _result()
    result["gate"] = None
    monkeypatch.setattr(cli, "evaluate", lambda **kw: result)
    assert cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait"]) == 0


def test_cli_gate_check_exit_codes(monkeypatch):
    monkeypatch.setattr(cli, "gate_check", lambda *a, **kw: {
        "usable": True, "decision": "PASS", "reasons": []})
    assert cli.main(["gate", "check", "--gate-id", "g1"]) == 0

    monkeypatch.setattr(cli, "gate_check", lambda *a, **kw: {
        "usable": True, "decision": "FAIL", "reasons": []})
    assert cli.main(["gate", "check", "--gate-id", "g1"]) == 2

    monkeypatch.setattr(cli, "gate_check", lambda *a, **kw: {
        "usable": False, "decision": "PASS", "reasons": ["EXPIRED"]})
    assert cli.main(["gate", "check", "--gate-id", "g1"]) == 3


def test_cli_missing_arguments_exits_nonzero():
    try:
        cli.main(["eval", "run"])
    except SystemExit as exc:
        assert exc.code != 0


# ---------- R1-4：CI 报告与评论 ----------

def _report_run():
    return {
        "run": {
            "id": "run-77",
            "status": "COMPLETED",
            "itemCount": 4,
            "passCount": 4,
            "failCount": 0,
            "unknownCount": 0,
            "errorCount": 0,
            "incompleteCount": 0,
            "coverage": 100.0,
            "passRate": 100.0,
            "promptName": "support-assistant",
            "promptVersion": 3,
            "promptLabel": "production",
        },
        "gate": {"id": "gate-3", "decision": "PASS", "policyKey": "release-admission",
                 "checks": [{"code": "COVERAGE", "status": "PASS", "actual": "coverage=100.0%"}]},
        "runId": "run-77",
    }


def test_cli_writes_report_json_with_baseline_diff(monkeypatch, tmp_path):
    report_path = tmp_path / "report.json"
    monkeypatch.setattr(cli, "evaluate", lambda **kw: _report_run())
    monkeypatch.setattr(cli, "gate_check", lambda *a, **kw: {"usable": True, "reasons": []})
    monkeypatch.setattr(cli, "baseline_run", lambda *a, **kw: {
        "resolved": True,
        "run": {"id": "run-70", "passCount": 3, "failCount": 1, "coverage": 100.0},
        "gate": {"decision": "PASS"},
    })
    monkeypatch.setattr(cli, "run_diff", lambda *a, **kw: {
        "diff": {"compatible": True, "summary": {"passToFail": 0, "failToPass": 1},
                 "promptComparison": {"changed": False}},
    })
    code = cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait",
                     "--prompt-name", "support-assistant", "--commit-sha", "abc123",
                     "--baseline-label", "baseline", "--report-json", str(report_path)])
    assert code == 0
    report = json.loads(report_path.read_text(encoding="utf-8"))
    assert report["runId"] == "run-77"
    assert report["decision"] == "PASS"
    assert report["usable"] is True
    assert report["baseline"]["runId"] == "run-70"
    assert report["baselineDiff"]["failToPass"] == 1
    assert report["prompt"]["name"] == "support-assistant"
    assert report["commitSha"] == "abc123"


def test_cli_passes_prompt_binding_arguments(monkeypatch):
    captured = {}

    def fake_evaluate(**kwargs):
        captured.update(kwargs)
        return _report_run()

    monkeypatch.setattr(cli, "evaluate", fake_evaluate)
    cli.main(["eval", "run", "--dataset", "ds", "--suite", "suite", "--wait",
              "--prompt-name", "p", "--prompt-version", "4", "--run-label", "candidate"])
    assert captured["prompt_name"] == "p"
    assert captured["prompt_version"] == 4
    assert captured["run_label"] == "candidate"


def test_cli_gate_check_resolves_gate_from_run(monkeypatch):
    monkeypatch.setattr(cli, "gate_check", lambda *a, **kw: {
        "usable": True, "decision": "PASS", "reasons": []})
    ev = importlib.import_module("emonlens.evaluate")
    monkeypatch.setattr(ev, "get_run", lambda *a, **kw: {"gate": {"id": "gate-9"}})
    assert cli.main(["gate", "check", "--run-id", "run-77"]) == 0


def test_cli_gate_check_without_gate_returns_blocked(monkeypatch):
    ev = importlib.import_module("emonlens.evaluate")
    monkeypatch.setattr(ev, "get_run", lambda *a, **kw: {"gate": None})
    assert cli.main(["gate", "check", "--run-id", "run-77"]) == 3


def test_cli_ci_comment_dry_run(monkeypatch, tmp_path, capsys):
    from emonlens import ci
    report_path = tmp_path / "report.json"
    report_path.write_text(json.dumps(ci.build_report(_report_run()["run"], _report_run()["gate"],
                                                      {"usable": True})), encoding="utf-8")
    assert cli.main(["ci", "comment", "--report", str(report_path), "--dry-run"]) == 0
    assert ci.MARKER in capsys.readouterr().out
