package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

import io.emonlens.server.storage.entity.EvaluatorEntity;

/**
 * 样本级评估结果聚合：把"样本 × 评估器"的结果列表归一为样本级 PASS/FAIL/UNKNOWN/ERROR。
 * DatasetRunWorker（服务端执行）与 SdkEvalResultService（外部结果回写后重聚合）共用，
 * 保证两条写入路径对同一样本给出一致口径。
 */
public final class RunItemAggregator {

    private RunItemAggregator() {
    }

    /** FAIL 优先，其次 ERROR，再 UNKNOWN，全 PASS 才是 PASS；无结果视为 ERROR。 */
    public static String aggregateLabel(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return EvaluatorEntity.LABEL_ERROR;
        }
        boolean error = false;
        boolean unknown = false;
        for (Map<String, Object> result : results) {
            String label = string(result.get("label"));
            if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
                return EvaluatorEntity.LABEL_FAIL;
            }
            if (EvaluatorEntity.LABEL_ERROR.equals(label)) {
                error = true;
            } else if (!EvaluatorEntity.LABEL_PASS.equals(label)) {
                unknown = true;
            }
        }
        return error ? EvaluatorEntity.LABEL_ERROR
                : unknown ? EvaluatorEntity.LABEL_UNKNOWN : EvaluatorEntity.LABEL_PASS;
    }

    public static String aggregateReasonCode(List<Map<String, Object>> results, String aggregateLabel) {
        if (results != null) {
            for (Map<String, Object> result : results) {
                if (aggregateLabel.equals(string(result.get("label")))
                        && result.get("reasonCode") != null) {
                    return string(result.get("reasonCode"));
                }
            }
        }
        return EvaluatorEntity.LABEL_PASS.equals(aggregateLabel) ? "PASS" : "MULTI_EVALUATOR_UNKNOWN";
    }

    public static String aggregateReason(List<Map<String, Object>> results) {
        List<String> parts = new java.util.ArrayList<>();
        if (results != null) {
            for (Map<String, Object> result : results) {
                String key = string(result.get("evaluatorKey"));
                String label = string(result.get("label"));
                String reason = string(result.get("reason"));
                parts.add("[" + key + "] " + label + (reason == null || reason.isBlank()
                        ? "" : ": " + reason));
            }
        }
        return String.join("; ", parts);
    }

    public static BigDecimal averageScore(List<Map<String, Object>> results) {
        if (results == null) {
            return null;
        }
        double sum = 0;
        int n = 0;
        for (Map<String, Object> result : results) {
            Object score = result.get("score");
            if (score instanceof Number) {
                sum += ((Number) score).doubleValue();
                n++;
            }
        }
        return n == 0 ? null : BigDecimal.valueOf(sum / n).setScale(2, RoundingMode.HALF_UP);
    }

    /** 样本 resultDetails.status 口径：与 label 对应的执行态。 */
    public static String statusOf(String aggregateLabel) {
        if (EvaluatorEntity.LABEL_ERROR.equals(aggregateLabel)) {
            return "ERROR";
        }
        return EvaluatorEntity.LABEL_UNKNOWN.equals(aggregateLabel) ? "UNKNOWN" : "DONE";
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
