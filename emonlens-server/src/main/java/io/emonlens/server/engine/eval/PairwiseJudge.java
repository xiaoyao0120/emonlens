package io.emonlens.server.engine.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 成对偏好判定的唯一实现（R1-2 起由 dataset run 的 PairwiseEvaluation 与
 * Playground 矩阵 Diff 共用）。
 *
 * <p>口径保持 pairwise/v2 与 pairwise/v3 不变：每个方向独立调用，两方向映射回
 * 原始 A/B 后一致才算偏好，冲突/调用不全 → UNKNOWN；多 judge 模型按多数派聚合，
 * 任一模型失败即 UNKNOWN，不做“多数派掩盖失败”。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PairwiseJudge {

    /** 判定口径：忽略长度/风格/模型身份，只比较同一输入下的候选优劣。 */
    public static final String SYSTEM_PROMPT = "You are an impartial pairwise evaluator. "
            + "Compare candidate_a and candidate_b for the same input. "
            + "Ignore length, style, and any assumed model identity. "
            + "Return strict JSON only: {\"winner\":\"A\"|\"B\"|\"TIE\","
            + "\"confidence\":0..1,\"reason\":\"...\"}.";

    public static final String REASON_BOTH_DIRECTIONS_AGREE = "BOTH_DIRECTIONS_AGREE";
    public static final String REASON_DIRECTIONS_DISAGREE = "DIRECTIONS_DISAGREE";
    public static final String REASON_DIRECTION_CALL_FAILED = "DIRECTION_CALL_FAILED_OR_NO_MAJORITY";
    public static final String REASON_MODEL_MAJORITY = "MODEL_MAJORITY";
    public static final String REASON_MODEL_DISAGREEMENT = "MODEL_DISAGREEMENT";
    public static final String REASON_MODEL_CALL_FAILED = "MODEL_CALL_FAILED_OR_DIRECTION_CONFLICT";

    private static final String UNKNOWN = "UNKNOWN";

    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    /** 一次成对判定：A/B/TIE/UNKNOWN + 原因码 + 逐次调用留痕。 */
    public static class Verdict {
        private final String preference;
        private final String reasonCode;
        private final String reason;
        private final List<Map<String, Object>> calls;

        public Verdict(String preference, String reasonCode, String reason,
                       List<Map<String, Object>> calls) {
            this.preference = preference;
            this.reasonCode = reasonCode;
            this.reason = reason;
            this.calls = calls;
        }

        public String getPreference() {
            return preference;
        }

        public String getReasonCode() {
            return reasonCode;
        }

        public String getReason() {
            return reason;
        }

        public List<Map<String, Object>> getCalls() {
            return calls;
        }
    }

    public Verdict judge(String input, String reference, String outputA, String outputB,
                         List<String> models, int samples, boolean randomize,
                         java.util.Random random, int[] positionCounts) {
        List<Map<String, Object>> calls = new ArrayList<>();
        List<String> verdicts = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (String model : models) {
            String directionAB = judgeDirection("AB", input, reference, outputA, outputB,
                    model, samples, calls, randomize, random, positionCounts);
            String directionBA = judgeDirection("BA", input, reference, outputB, outputA,
                    model, samples, calls, randomize, random, positionCounts);
            if (UNKNOWN.equals(directionAB) || UNKNOWN.equals(directionBA)) {
                verdicts.add(UNKNOWN);
                reasons.add(REASON_DIRECTION_CALL_FAILED);
            } else if (directionAB.equals(directionBA)) {
                verdicts.add(directionAB);
                reasons.add(REASON_BOTH_DIRECTIONS_AGREE);
            } else {
                verdicts.add(UNKNOWN);
                reasons.add(REASON_DIRECTIONS_DISAGREE);
            }
        }
        if (models.size() == 1) {
            return new Verdict(verdicts.get(0), reasons.get(0), labelReason(verdicts.get(0), reasons.get(0)),
                    calls);
        }
        if (verdicts.stream().anyMatch(UNKNOWN::equals)) {
            return new Verdict(UNKNOWN, REASON_MODEL_CALL_FAILED,
                    "多 judge 模型存在失败或方向冲突，不按多数派硬判", calls);
        }
        int a = 0;
        int b = 0;
        int tie = 0;
        for (String verdict : verdicts) {
            if ("A".equals(verdict)) {
                a++;
            } else if ("B".equals(verdict)) {
                b++;
            } else if ("TIE".equals(verdict)) {
                tie++;
            }
        }
        int majority = models.size() / 2 + 1;
        if (a >= majority) {
            return new Verdict("A", REASON_MODEL_MAJORITY, "多 judge 模型多数偏好 A", calls);
        }
        if (b >= majority) {
            return new Verdict("B", REASON_MODEL_MAJORITY, "多 judge 模型多数偏好 B", calls);
        }
        if (tie >= majority) {
            return new Verdict("TIE", REASON_MODEL_MAJORITY, "多 judge 模型多数判为持平", calls);
        }
        return new Verdict(UNKNOWN, REASON_MODEL_DISAGREEMENT, "多 judge 模型无多数偏好", calls);
    }

    private String labelReason(String verdict, String reasonCode) {
        if (REASON_BOTH_DIRECTIONS_AGREE.equals(reasonCode)) {
            return "两方向一致偏好 " + verdict;
        }
        if (REASON_DIRECTIONS_DISAGREE.equals(reasonCode)) {
            return "两方向结论冲突，按 UNKNOWN 处理";
        }
        return "方向调用失败或无多数，按 UNKNOWN 处理";
    }

    private String judgeDirection(String direction, String input, String reference,
                                  String left, String right, String model, int samples,
                                  List<Map<String, Object>> calls, boolean randomize,
                                  java.util.Random random, int[] positionCounts) {
        int a = 0;
        int b = 0;
        int tie = 0;
        boolean failed = false;
        for (int i = 0; i < samples; i++) {
            boolean swapped = randomize && random.nextBoolean();
            boolean firstIsA = !swapped == "AB".equals(direction);
            if (positionCounts != null) {
                positionCounts[firstIsA ? 0 : 1]++;
            }
            Map<String, Object> call = new LinkedHashMap<>();
            call.put("direction", direction);
            call.put("sample", i + 1);
            call.put("judgeModel", model);
            call.put("positionOrder", firstIsA ? "AB" : "BA");
            try {
                String user = toJson(Map.of(
                        "input", input,
                        "reference", reference == null ? "" : reference,
                        "candidate_a", swapped ? right : left,
                        "candidate_b", swapped ? left : right));
                LlmClient.ChatReply reply = llmClient.chatWithUsage(SYSTEM_PROMPT, user, model, 0.0);
                String raw = parseWinner(reply.content());
                call.put("rawWinner", raw);
                String mapped = UNKNOWN;
                if ("A".equals(raw)) {
                    mapped = firstIsA ? "A" : "B";
                } else if ("B".equals(raw)) {
                    mapped = firstIsA ? "B" : "A";
                } else if ("TIE".equals(raw)) {
                    mapped = "TIE";
                }
                call.put("mappedWinner", mapped);
                call.put("reason", parseReason(reply.content()));
                if ("A".equals(mapped)) {
                    a++;
                } else if ("B".equals(mapped)) {
                    b++;
                } else if ("TIE".equals(mapped)) {
                    tie++;
                } else {
                    failed = true;
                }
            } catch (Exception e) {
                call.put("error", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                failed = true;
            }
            calls.add(call);
        }
        if (failed) {
            return UNKNOWN;
        }
        int half = samples / 2;
        if (a > half) {
            return "A";
        }
        if (b > half) {
            return "B";
        }
        if (tie > half) {
            return "TIE";
        }
        return UNKNOWN;
    }

    private String parseWinner(String content) {
        Map<String, Object> map = parseObject(content);
        Object winner = map.get("winner");
        if (winner == null) {
            return UNKNOWN;
        }
        String value = String.valueOf(winner).trim().toUpperCase();
        return Set.of("A", "B", "TIE").contains(value) ? value : UNKNOWN;
    }

    private String parseReason(String content) {
        Map<String, Object> map = parseObject(content);
        Object reason = map.get("reason");
        if (reason == null) {
            return "";
        }
        String value = String.valueOf(reason);
        return value.length() > 500 ? value.substring(0, 500) : value;
    }

    private Map<String, Object> parseObject(String content) {
        if (content == null) {
            return Map.of();
        }
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(trimmed.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new io.emonlens.common.EmonLensException(io.emonlens.common.ErrorCode.BAD_REQUEST,
                    "pairwise payload 无法序列化");
        }
    }
}
