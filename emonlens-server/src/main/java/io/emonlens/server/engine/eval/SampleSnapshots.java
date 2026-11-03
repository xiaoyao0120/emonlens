package io.emonlens.server.engine.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 样本快照（et_dataset_run_item.sample_snapshot_json）→ {@link EvaluationSample} 的唯一解析入口。
 * DatasetRunWorker（服务端执行）与 SdkEvalResultService（SDK 载荷下发）共用，保证
 * 两条读取路径看到同一份冻结样本语义；运行后编辑样本不影响已受理 run 的判定。
 */
public final class SampleSnapshots {

    private SampleSnapshots() {
    }

    public static EvaluationSample from(Map<String, Object> snapshot) {
        EvaluationSample sample = new EvaluationSample();
        sample.setItemId(asString(snapshot.get("itemId")));
        sample.setItemOrder(asInteger(snapshot.get("itemOrder")));
        sample.setTraceId(asString(snapshot.get("traceId")));
        sample.setSourceTraceId(asString(snapshot.get("sourceTraceId")));
        sample.setAgentKey(asString(snapshot.get("agentKey")));
        sample.setAgentVersion(asString(snapshot.get("agentVersion")));
        sample.setInput(asString(snapshot.get("input")));
        sample.setCandidateOutput(asString(snapshot.get("candidateOutput")));
        sample.setExpectedOutput(asString(snapshot.get("expectedOutput")));
        sample.setSchemaVersion(asInteger(snapshot.get("schemaVersion")));
        sample.setCaseHash(asString(snapshot.get("caseHash")));
        sample.setRetrievalContext(mapList(snapshot.get("retrievalContext")));
        sample.setExpectedTools(mapList(snapshot.get("expectedTools")));
        sample.setToolsCalled(mapList(snapshot.get("toolsCalled")));
        sample.setTurns(mapList(snapshot.get("turns")));
        Map<String, Object> expectations = asMap(snapshot.get("expectations"));
        sample.setExpectations(expectations == null ? new LinkedHashMap<>() : expectations);
        sample.setSpanCount(asInteger(snapshot.get("spanCount")));
        sample.setErrorCount(asInteger(snapshot.get("errorCount")));
        sample.setDurationMs(asLong(snapshot.get("durationMs")));
        sample.setToolCalls(asLong(snapshot.get("toolCalls")));
        sample.setLlmCalls(asLong(snapshot.get("llmCalls")));
        sample.setTraceDigest(asString(snapshot.get("traceDigest")));
        sample.setContents(stringMap(snapshot.get("contents")));
        sample.setContentCaptureModes(stringMap(snapshot.get("contentCaptureModes")));
        Map<String, Boolean> truncated = new LinkedHashMap<>();
        Map<?, ?> rawTruncated = asMap(snapshot.get("contentTruncated"));
        if (rawTruncated != null) {
            for (Map.Entry<?, ?> e : rawTruncated.entrySet()) {
                truncated.put(String.valueOf(e.getKey()), Boolean.TRUE.equals(e.getValue()));
            }
        }
        sample.setContentTruncated(truncated);
        sample.setContentStatus(asString(snapshot.get("contentStatus")));
        sample.setContentStatusReason(asString(snapshot.get("contentStatusReason")));
        Object complete = snapshot.get("candidateComplete");
        sample.setCandidateComplete(complete instanceof Boolean ? (Boolean) complete : null);
        return sample;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapList(Object value) {
        List<Map<String, Object>> result = new java.util.ArrayList<>();
        if (!(value instanceof List)) {
            return result;
        }
        for (Object item : (List<?>) value) {
            Map<String, Object> map = asMap(item);
            if (map != null) {
                result.add(map);
            }
        }
        return result;
    }

    private static Map<String, String> stringMap(Object value) {
        Map<String, String> result = new LinkedHashMap<>();
        Map<?, ?> map = asMap(value);
        if (map != null) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                result.put(String.valueOf(e.getKey()), e.getValue() == null
                        ? null : String.valueOf(e.getValue()));
            }
        }
        return result;
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }
}
