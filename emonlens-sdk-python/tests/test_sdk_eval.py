import importlib
import json
import textwrap

import pytest

# emonlens.__init__ 导出 scorer 等符号；这里显式取子模块，避免与顶层重名混淆
se = importlib.import_module("emonlens.sdk_eval")
import emonlens


@pytest.fixture(autouse=True)
def _clean_registry():
    se.SCORERS.clear()
    yield
    se.SCORERS.clear()


def _listing(records, content_restricted=False):
    return {"runId": "run-1", "runStatus": "COMPLETED",
            "contentRestricted": content_restricted,
            "openSdkTasks": len(records), "records": records}


def _record(item_id="i-1", key="amount-check", sample=None):
    return {"taskId": "t-" + item_id, "itemId": item_id, "itemOrder": 0,
            "evaluatorId": "ev-1", "evaluatorKey": key, "evaluatorVersion": 1,
            "requiredFields": ["input", "candidate_output"],
            "sample": sample or {"input": "q", "candidateOutput": "a", "expectedOutput": "a"}}


def test_scorer_decorator_registers_in_module_registry():
    @emonlens.scorer("k-1")
    def check(sample):
        return True

    @emonlens.scorer("k-1")
    def replacement(sample):
        return False

    assert se.SCORERS["k-1"] is replacement


def test_normalize_result_accepts_bool_number_dict_and_dataclass():
    started = __import__("time").monotonic()
    assert se._normalize_result(True, started).verdict == "PASS"
    assert se._normalize_result(False, started).verdict == "FAIL"
    assert se._normalize_result(0.9, started).verdict == "PASS"
    assert se._normalize_result(0.9, started).score == pytest.approx(0.9)
    assert se._normalize_result(7, started).verdict == "UNKNOWN"  # 0-1 之外的裸数值不可解释
    as_dict = se._normalize_result({"verdict": "fail", "score": 2, "reason": "x",
                                    "reasonCode": "NO"}, started)
    assert as_dict.verdict == "FAIL" and as_dict.score == 2 and as_dict.reason_code == "NO"
    direct = se._normalize_result(se.ScorerResult(verdict="weird"), started)
    assert direct.verdict == "UNKNOWN"


def test_run_sdk_scorers_pushes_and_reports_conflicts(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok")

    calls = []

    def fake_request(method, url, token, body=None, idempotency_key=None):
        calls.append((method, url, body))
        if method == "GET":
            return _listing([_record(), _record(item_id="i-2", key="unknown-eval")])
        assert method == "POST"
        assert body["results"][0]["verdict"] == "PASS"
        assert body["results"][0]["itemId"] == "i-1"
        return {"results": [{"itemId": "i-1", "evaluatorKey": "amount-check",
                             "outcome": "APPLIED"}],
                "openSdkTasks": 0}

    monkeypatch.setattr(se, "_request", fake_request)

    summary = se.run_sdk_scorers("run-1", {"amount-check": lambda sample: {
        "verdict": "PASS", "score": 1.0, "reasonCode": "OK"}})

    assert summary["executed"] == 2
    assert summary["pushed"] == 1
    assert summary["conflicts"] == 0
    assert summary["outcomes"][0]["outcome"] == "applied"
    # 未注册 scorer 的任务记 error，不产生回写，也不让整批失败
    assert summary["outcomes"][1]["outcome"] == "error"
    assert summary["outcomes"][1]["reasonCode"] == "SCORER_NOT_REGISTERED"
    assert calls[-1][0] == "POST" and calls[-1][2] is not None


def test_scorer_exception_becomes_error_result_not_crash():
    def boom(sample):
        raise ValueError("bad input")

    result = se._invoke_scorer(boom, se._sample_from(_record(), False))
    assert result.verdict == "ERROR"
    assert result.reason_code == "SCORER_RAISED"
    assert "bad input" in result.reason


def test_load_scorer_module_registers_decorated_functions(tmp_path):
    module = tmp_path / "scorers.py"
    module.write_text(textwrap.dedent("""
        import emonlens

        @emonlens.scorer("file-check")
        def check(sample):
            return sample.expected_output == sample.candidate_output
    """), encoding="utf-8")
    registered = se.load_scorer_module(str(module))
    assert "file-check" in registered
    sample = se._sample_from(_record(sample={"input": "q", "candidateOutput": "a",
                                             "expectedOutput": "a"}), False)
    result = se._invoke_scorer(registered["file-check"], sample)
    assert result.verdict == "PASS"


def test_sdk_sample_missing_fields_maps_snake_case_to_payload_keys():
    sample = se._sample_from(_record(sample={"input": "q"}), False)
    assert sample.missing_fields() == ["candidate_output"]
    full = se._sample_from(_record(sample={"input": "q", "candidateOutput": "a"}), False)
    assert full.missing_fields() == []


def test_run_sdk_scorers_raises_on_conflict_when_asked(monkeypatch):
    monkeypatch.setenv("EMONLENS_HOST", "http://emonlens.test")
    monkeypatch.setenv("EMONLENS_API_TOKEN", "tok")

    def fake_request(method, url, token, body=None, idempotency_key=None):
        if method == "GET":
            return _listing([_record()])
        return {"results": [{"itemId": "i-1", "evaluatorKey": "amount-check",
                             "outcome": "CONFLICT", "settledVerdict": "FAIL"}],
                "openSdkTasks": 0}

    monkeypatch.setattr(se, "_request", fake_request)
    with pytest.raises(emonlens.EmonLensError, match="conflict"):
        se.run_sdk_scorers("run-1", {"amount-check": lambda s: True}, raise_on_conflict=True)
