import importlib

import pytest

# emonlens.__init__ 导出了同名函数 evaluate；这里显式取子模块
ev = importlib.import_module("emonlens.evaluate")


def _run(status="COMPLETED", decision="PASS", fail=0, unknown=0, error=0, incomplete=0):
    return {
        "run": {
            "id": "run-1",
            "status": status,
            "passCount": 2,
            "failCount": fail,
            "unknownCount": unknown,
            "errorCount": error,
            "incompleteCount": incomplete,
            "coverage": 100.0,
        },
        "gate": {"decision": decision} if decision else None,
    }


def test_evaluate_submits_idempotent_request_and_polls(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test/")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok-eval")
    calls = []
    responses = [
        {"runId": "run-1", "status": "PENDING", "created": True},
        _run(),
    ]

    def fake_request(method, url, token, body=None, idempotency_key=None):
        calls.append({"method": method, "url": url, "token": token,
                      "body": body, "key": idempotency_key})
        return responses.pop(0)

    monkeypatch.setattr(ev, "_request", fake_request)
    result = ev.evaluate("ds-1", suite_id="suite-1", mode="TRACE",
                         wait=True, timeout=2, poll_interval=0.01)

    assert result["runId"] == "run-1"
    assert result["run"]["status"] == "COMPLETED"
    assert calls[0]["method"] == "POST"
    assert calls[0]["url"] == "http://emonlens.test/api/v1/open/eval-runs"
    assert calls[0]["token"] == "tok-eval"
    assert calls[0]["body"] == {"datasetId": "ds-1", "mode": "TRACE", "suiteId": "suite-1"}
    assert calls[0]["key"]
    assert calls[1]["method"] == "GET"
    assert calls[1]["url"].endswith("/api/v1/open/eval-runs/run-1")


def test_evaluate_without_wait_returns_submission(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok-eval")
    monkeypatch.setattr(ev, "_request", lambda *a, **kw: {"runId": "run-2", "status": "PENDING"})
    result = ev.evaluate("ds-2", evaluator_ids=["e-1"], wait=False)
    assert result["runId"] == "run-2"


def test_evaluate_requires_host_and_token(monkeypatch):
    monkeypatch.delenv("EMONLENS_HOST", raising=False)
    monkeypatch.delenv("EMONLENS_API_TOKEN", raising=False)
    with pytest.raises(ev.EmonLensError):
        ev.evaluate("ds-1", suite_id="s")


def test_assert_test_passes_and_fails():
    ev.assert_test(_run())
    with pytest.raises(ev.EmonLensAssertionError):
        ev.assert_test(_run(decision="BLOCKED"))
    with pytest.raises(ev.EmonLensAssertionError):
        ev.assert_test(_run(unknown=1))
    with pytest.raises(ev.EmonLensAssertionError):
        ev.assert_test(_run(fail=1))


def test_gate_check_uses_verification_endpoint(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok-eval")
    calls = []

    def fake_request(method, url, token, body=None, idempotency_key=None):
        calls.append((method, url))
        return {"usable": True, "decision": "PASS", "reasons": []}

    monkeypatch.setattr(ev, "_request", fake_request)
    result = ev.gate_check("gate-1")
    assert result["usable"] is True
    assert calls == [("GET", "http://emonlens.test/api/v1/open/quality-gates/gate-1/verification")]


def test_wait_for_run_tolerates_transient_poll_failures(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok-eval")
    attempts = {"n": 0}

    def flaky_request(method, url, token, body=None, idempotency_key=None):
        attempts["n"] += 1
        if attempts["n"] == 1:
            raise ev.EmonLensError("connection reset")
        return _run()  # 终态 COMPLETED

    monkeypatch.setattr(ev, "_request", flaky_request)
    result = ev.wait_for_run("run-1", timeout=5, poll_interval=0.01)
    assert result["run"]["status"] == "COMPLETED"
    assert attempts["n"] == 2


def test_wait_for_run_raises_after_consecutive_poll_failures(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok-eval")
    attempts = {"n": 0}

    def always_fail(method, url, token, body=None, idempotency_key=None):
        attempts["n"] += 1
        raise ev.EmonLensError("connection reset")

    monkeypatch.setattr(ev, "_request", always_fail)
    with pytest.raises(ev.EmonLensError):
        ev.wait_for_run("run-1", timeout=30, poll_interval=0.01)
    # 连续 MAX_POLL_FAILURES 次失败后抛错，不再无限重试
    assert attempts["n"] == ev.MAX_POLL_FAILURES


# ---------- R1-3 / R1-4：prompt 绑定、基线解析、run diff ----------

def test_evaluate_binds_prompt_version_and_ci_metadata(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok-eval")
    captured = {}

    def fake_request(method, url, token, body=None, idempotency_key=None):
        captured["body"] = body
        return {"runId": "run-9", "status": "PENDING"}

    monkeypatch.setattr(ev, "_request", fake_request)
    ev.evaluate("ds-1", evaluator_ids=["e-1"], mode="REPLAY", wait=False,
                prompt_name="support-assistant", prompt_label="production",
                run_label="baseline", commit_sha="abc123")
    assert captured["body"]["promptName"] == "support-assistant"
    assert captured["body"]["promptLabel"] == "production"
    assert "promptVersion" not in captured["body"]
    assert captured["body"]["runLabel"] == "baseline"
    assert captured["body"]["commitSha"] == "abc123"


def test_evaluate_rejects_prompt_version_without_name():
    with pytest.raises(ev.EmonLensError):
        ev.evaluate("ds-1", evaluator_ids=["e-1"], prompt_version=3, wait=False)


def test_baseline_run_encodes_query(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok-eval")
    urls = []

    def fake_request(method, url, token, body=None, idempotency_key=None):
        urls.append(url)
        return {"resolved": True, "run": {"id": "run-base"}}

    monkeypatch.setattr(ev, "_request", fake_request)
    result = ev.baseline_run("ds-1", label="baseline")
    assert result["resolved"] is True
    assert urls == ["http://emonlens.test/api/v1/open/eval-runs?datasetId=ds-1&label=baseline"]


def test_run_diff_uses_explicit_baseline(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok-eval")
    urls = []

    def fake_request(method, url, token, body=None, idempotency_key=None):
        urls.append(url)
        return {"diff": {"compatible": True}}

    monkeypatch.setattr(ev, "_request", fake_request)
    ev.run_diff("run-cand", baseline_run_id="run-base", label="baseline")
    assert urls[0].endswith("/api/v1/open/eval-runs/run-cand/diff?label=baseline&baselineRunId=run-base")


def test_get_run_requires_id():
    with pytest.raises(ev.EmonLensError):
        ev.get_run("")
