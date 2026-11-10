"""DeepSeek Harness 回放桥接测试（stdlib unittest，pytest 兼容）。

验收点：
1. 一轮对话 → 一条 trace：根 span 的 input/output 取自该轮的用户消息与最后一条助手消息
2. 工具调用成对映射：spanName=toolName、status 错误时 ERROR、耗时来自 call→result 两个事件
3. 多轮各自独立：traceId 按轮次区分，不互相覆盖
4. spanId 长度不超过服务端上限 32（曾因直接用 DSH 的 32 位 callId 拼前缀被整批拒绝）
5. 没有时间信息的孤儿桶不上报（避免 1970 时间）
6. 服务端返回 rejected 时不算成功（否则"部分丢失"会被当成功）
"""
import json
import os
import sys
import unittest
from unittest import mock

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

from emonlens import dsh_sync  # noqa: E402


def rec(rtype, seq, time_ms, data):
    return {"type": rtype, "seq": seq, "time": time_ms, "data": data}


SESSION_ID = "ff91948e-520e-41a7-ab77-7535dfa8ae20"

RECORDS = [
    {"type": "session", "version": 4, "id": SESSION_ID, "createdAt": 1794023404300,
     "cwd": "D:/work", "isSeeded": False, "delegationDepth": 0, "agentPreset": "default"},
    rec("turn/start", 11, 1794023540938, {"turn": 1}),
    rec("user/message", 12, 1794023541000,
        {"turn": 1, "role": "user", "content": [{"type": "text", "text": "帮我查订单"}]}),
    rec("assistant/message", 14, 1794023545000,
        {"turn": 1, "step": 1,
         "message": {"role": "assistant", "content": [{"type": "text", "text": "我查一下"}],
                     "source": {"kind": "model", "provider": "deepseek", "model": "deepseek-v4.1-flash"}},
         "usage": {"inputTokens": 120, "outputTokens": 30, "totalTokens": 150, "cacheReadTokens": 64}}),
    rec("tool/call", 15, 1794023546000, {"turn": 1, "step": 1,
                                         "callId": "2ce36135ef215e048f0b1c2d3e4f5a6b", "name": "pwsh",
                                         "arguments": "{\"command\":\"Get-Date\"}"}),
    rec("tool/result", 16, 1794023550000,
        {"turn": 1, "step": 1,
         "message": {"role": "tool", "toolCallId": "2ce36135ef215e048f0b1c2d3e4f5a6b",
                     "content": [{"type": "text", "text": "2026-11-10"}], "isError": False}}),
    rec("assistant/message", 17, 1794023551000,
        {"turn": 1, "step": 2,
         "message": {"role": "assistant", "content": [{"type": "text", "text": "订单已发货"}],
                     "source": {"kind": "model", "provider": "deepseek", "model": "deepseek-v4.1-flash"}},
         "usage": {"inputTokens": 200, "outputTokens": 20, "totalTokens": 220}}),
    rec("turn/end", 18, 1794023555000, {"turn": 1, "reason": {"kind": "stop"}}),
    # 第二轮：工具失败
    rec("turn/start", 19, 1794023560000, {"turn": 2}),
    rec("user/message", 20, 1794023560100,
        {"turn": 2, "role": "user", "content": [{"type": "text", "text": "再查一次"}]}),
    rec("tool/call", 21, 1794023560200, {"turn": 2, "step": 1,
                                         "callId": "ffffffffffffffffffffffffffffffff", "name": "pwsh",
                                         "arguments": "{\"command\":\"boom\"}"}),
    rec("tool/result", 22, 1794023561200,
        {"turn": 2, "step": 1,
         "message": {"role": "tool", "toolCallId": "ffffffffffffffffffffffffffffffff",
                     "content": [{"type": "text", "text": "command failed"}], "isError": True}}),
    rec("turn/end", 23, 1794023562000, {"turn": 2, "reason": {"kind": "stop"}}),
]


class BuildTracesTest(unittest.TestCase):
    def setUp(self):
        self.traces = dsh_sync.build_traces(RECORDS, SESSION_ID)

    def test_one_trace_per_turn(self):
        self.assertEqual([t["turn"] for t in self.traces], [1, 2])
        self.assertEqual(len({t["traceId"] for t in self.traces}), 2)

    def test_root_span_carries_prompt_and_final_answer(self):
        root = self.traces[0]["spans"][0]
        self.assertEqual(root["spanType"], "agent")
        self.assertEqual(root["spanName"], "dsh_turn")
        self.assertEqual(root["input"], "帮我查订单")
        self.assertEqual(root["output"], "订单已发货")  # 该轮最后一条助手消息
        self.assertEqual(root["sessionId"], SESSION_ID)
        self.assertEqual(root["agentKey"], "dsh")

    def test_llm_span_carries_model_and_tokens(self):
        llm = [s for s in self.traces[0]["spans"] if s["spanType"] == "llm"]
        self.assertEqual(len(llm), 2)
        self.assertEqual(llm[0]["modelName"], "deepseek-v4.1-flash")
        self.assertEqual(llm[0]["inputTokens"], 120)
        self.assertEqual(llm[0]["cachedInputTokens"], 64)

    def test_tool_span_pairs_call_and_result_with_real_duration(self):
        tool = [s for s in self.traces[0]["spans"] if s["spanType"] == "tool"][0]
        self.assertEqual(tool["toolName"], "pwsh")
        self.assertIn("Get-Date", tool["input"])
        self.assertEqual(tool["output"], "2026-11-10")
        # 断言耗时而不是绝对字符串：_iso 走本地时区，写死绝对值会在别的时区失败
        import datetime as _dt
        fmt = "%Y-%m-%dT%H:%M:%S.%f"
        start = _dt.datetime.strptime(tool["startTime"], fmt)
        end = _dt.datetime.strptime(tool["endTime"], fmt)
        self.assertEqual((end - start).total_seconds(), 4.0)  # call 与 result 相差 4000ms
        self.assertLess(start, end)

    def test_failed_tool_marks_error(self):
        tool = [s for s in self.traces[1]["spans"] if s["spanType"] == "tool"][0]
        self.assertEqual(tool["status"], "ERROR")
        self.assertEqual(tool["errorType"], "TOOL_ERROR")

    def test_span_ids_fit_server_limit(self):
        for trace in self.traces:
            for span in trace["spans"]:
                self.assertLessEqual(len(span["spanId"]), 32, span["spanId"])
                self.assertLessEqual(len(span["traceId"]), 64, span["traceId"])

    def test_no_content_mode_drops_body_but_keeps_timing(self):
        traces = dsh_sync.build_traces(RECORDS, SESSION_ID, with_content=False)
        root = traces[0]["spans"][0]
        self.assertIsNone(root["input"])
        self.assertIsNone(root["output"])
        tool = [s for s in traces[0]["spans"] if s["spanType"] == "tool"][0]
        self.assertIsNone(tool["input"])
        self.assertEqual(tool["toolName"], "pwsh")  # 时间线/工具名保留

    def test_orphan_bucket_without_timing_is_skipped(self):
        records = [rec("user/message", 1, 1794023500000,
                       {"role": "user", "content": [{"type": "text", "text": "没有 turn 记录"}]})]
        self.assertEqual(dsh_sync.build_traces(records, SESSION_ID), [])


class PushReportingTest(unittest.TestCase):
    """服务端 rejected 不能当成功——否则"部分丢失"会被静默忽略。"""

    REJECT_BODY = {"code": 0, "data": {"acceptedCount": 1,
                                       "rejected": [{"index": 2, "reason": "spanId must be <= 32 chars"}]}}

    def test_rejected_spans_are_reported_as_errors(self):
        calls = []

        def fake_push(host, key, spans, timeout=30):
            calls.append(len(spans))
            return 200, dict(self.REJECT_BODY)

        with mock.patch.object(dsh_sync, "push", side_effect=fake_push), \
                mock.patch.object(dsh_sync, "_decompress", return_value=RECORDS):
            report = dsh_sync.sync("http://x", "tk-test", root=None)
        self.assertTrue(report["errors"], "被拒的 span 必须进 errors")
        joined = json.dumps(report["errors"], ensure_ascii=False)
        self.assertIn("spanId must be <= 32 chars", joined)


if __name__ == "__main__":
    unittest.main()
