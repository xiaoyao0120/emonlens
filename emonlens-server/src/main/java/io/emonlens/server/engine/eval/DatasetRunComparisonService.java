package io.emonlens.server.engine.eval;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;

import lombok.RequiredArgsConstructor;

/**
 * 两次 dataset run 的可比性与逐样本 diff。
 * 缺快照/缺 sampleSetHash/缺 evaluator 指纹视为“无证据”，不能当成相等；
 * 不可比时只返回原因与 run 摘要，不输出可被误用的回归列表。
 */
@Service
@RequiredArgsConstructor
public class DatasetRunComparisonService {

    private static final String PASS = EvaluatorEntity.LABEL_PASS;
    private static final String FAIL = EvaluatorEntity.LABEL_FAIL;
    private static final String UNKNOWN = EvaluatorEntity.LABEL_UNKNOWN;

    private final ObjectMapper objectMapper;

    public Map<String, Object> compare(DatasetRunEntity baseline, List<DatasetRunItemEntity> baselineItems,
                                       DatasetRunEntity candidate, List<DatasetRunItemEntity> candidateItems,
                                       boolean contentAllowed) {
        Map<String, Object> baseConfig = config(baseline);
        Map<String, Object> candConfig = config(candidate);
        List<String> reasons = new ArrayList<>();

        if (!snapshotUsable(baseline, baseConfig)) {
            reasons.add("BASELINE_SNAPSHOT_MISSING");
        }
        if (!snapshotUsable(candidate, candConfig)) {
            reasons.add("CANDIDATE_SNAPSHOT_MISSING");
        }
        if (!DatasetRunEntity.STATUS_COMPLETED.equals(baseline.getStatus())) {
            reasons.add("BASELINE_NOT_COMPLETED:" + baseline.getStatus());
        }
        if (!DatasetRunEntity.STATUS_COMPLETED.equals(candidate.getStatus())) {
            reasons.add("CANDIDATE_NOT_COMPLETED:" + candidate.getStatus());
        }

        String baseMode = firstNonBlank(baseline.getRunMode(), asString(baseConfig.get("runMode")));
        String candMode = firstNonBlank(candidate.getRunMode(), asString(candConfig.get("runMode")));
        if (baseMode == null || candMode == null) {
            reasons.add("RUN_MODE_EVIDENCE_MISSING");
        } else if (!baseMode.equals(candMode)) {
            reasons.add("RUN_MODE_MISMATCH");
        }

        String baseHash = asString(baseConfig.get("sampleSetHash"));
        String candHash = asString(candConfig.get("sampleSetHash"));
        if (isBlank(baseHash) || isBlank(candHash)) {
            reasons.add("SAMPLE_SET_EVIDENCE_MISSING");
        } else if (!baseHash.equals(candHash)) {
            reasons.add("SAMPLE_SET_MISMATCH");
        }

        List<String> baseEvaluators = evaluatorFingerprints(baseConfig);
        List<String> candEvaluators = evaluatorFingerprints(candConfig);
        if (baseEvaluators.isEmpty() || candEvaluators.isEmpty()) {
            reasons.add("EVALUATOR_EVIDENCE_MISSING");
        } else if (!baseEvaluators.equals(candEvaluators)) {
            reasons.add("EVALUATOR_MISMATCH");
        }

        if (Objects.equals(baseline.getId(), candidate.getId())) {
            reasons.add("SELF_COMPARISON");
        }

        boolean compatible = reasons.isEmpty();
        Map<String, DatasetRunItemEntity> baseById = byItemId(baselineItems);
        Map<String, DatasetRunItemEntity> candById = byItemId(candidateItems);

        Map<String, Object> summary = emptySummary(compatible);
        List<Map<String, Object>> diffs = new ArrayList<>();
        List<Map<String, Object>> regressions = new ArrayList<>();
        List<Map<String, Object>> fixes = new ArrayList<>();
        if (compatible) {
            Set<String> allIds = new LinkedHashSet<>(baseById.keySet());
            allIds.addAll(candById.keySet());
            int passToPass = 0, failToFail = 0, passToFail = 0, failToPass = 0;
            int unknownCount = 0, errorCount = 0, other = 0, missingCount = 0;
            int paired = 0;
            for (String itemId : allIds) {
                DatasetRunItemEntity base = baseById.get(itemId);
                DatasetRunItemEntity cand = candById.get(itemId);
                String baseLabel = base == null ? null : base.getLabel();
                String candLabel = cand == null ? null : cand.getLabel();
                String transition = transition(baseLabel, candLabel);
                if (base != null && cand != null) {
                    paired++;
                }
                boolean unknown = UNKNOWN.equals(baseLabel) || UNKNOWN.equals(candLabel);
                boolean error = isError(baseLabel) || isError(candLabel);
                if (base == null || cand == null) {
                    missingCount++;
                } else if (unknown) {
                    unknownCount++;
                } else if (error) {
                    errorCount++;
                } else if ("PASS->PASS".equals(transition)) passToPass++;
                else if ("FAIL->FAIL".equals(transition)) failToFail++;
                else if ("PASS->FAIL".equals(transition)) passToFail++;
                else if ("FAIL->PASS".equals(transition)) failToPass++;
                else other++;

                Map<String, Object> row = new LinkedHashMap<>();
                row.put("itemId", itemId);
                row.put("transition", transition);
                row.put("baselineLabel", baseLabel);
                row.put("candidateLabel", candLabel);
                row.put("baselineScore", base == null ? null : base.getScore());
                row.put("candidateScore", cand == null ? null : cand.getScore());
                if (contentAllowed) {
                    Map<String, Object> sample = cand != null ? cand.getSampleSnapshot() : base.getSampleSnapshot();
                    row.put("input", sample == null ? null : sample.get("input"));
                    row.put("expectedOutput", sample == null ? null : sample.get("expectedOutput"));
                }
                diffs.add(row);
                if ("PASS->FAIL".equals(transition)) {
                    regressions.add(row);
                } else if ("FAIL->PASS".equals(transition)) {
                    fixes.add(row);
                }
            }
            summary.put("diffAvailable", true);
            summary.put("totalItems", diffs.size());
            summary.put("paired", paired);
            summary.put("missingCount", missingCount);
            summary.put("passToPass", passToPass);
            summary.put("failToFail", failToFail);
            summary.put("passToFail", passToFail);
            summary.put("failToPass", failToPass);
            summary.put("unknownCount", unknownCount);
            summary.put("errorCount", errorCount);
            summary.put("other", other);
            summary.put("regressionCount", regressions.size());
            summary.put("fixCount", fixes.size());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("compatible", compatible);
        payload.put("reasons", reasons);
        payload.put("diffAvailable", compatible);
        payload.put("baseline", runSummary(baseline));
        payload.put("candidate", runSummary(candidate));
        payload.put("summary", summary);
        payload.put("regressions", compatible ? regressions : List.of());
        payload.put("fixes", compatible ? fixes : List.of());
        payload.put("items", compatible ? diffs : List.of());
        payload.put("contentRestricted", !contentAllowed);
        // R1-3：prompt 版本不同时仍可比（这正是替换 prompt 的目的），但结论只能说明相对差异，
        // 不能当作“同一 prompt 的回归”。降级提示与不可比原因分开表达。
        payload.put("promptComparison", promptComparison(baseline, candidate));
        return payload;
    }

    private Map<String, Object> promptComparison(DatasetRunEntity baseline, DatasetRunEntity candidate) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseline", promptRef(baseline));
        result.put("candidate", promptRef(candidate));
        boolean baseBound = baseline.getPromptName() != null;
        boolean candBound = candidate.getPromptName() != null;
        boolean changed = baseBound != candBound
                || (baseBound && (!Objects.equals(baseline.getPromptName(), candidate.getPromptName())
                    || !Objects.equals(baseline.getPromptVersion(), candidate.getPromptVersion())
                    || !Objects.equals(baseline.getPromptHash(), candidate.getPromptHash())));
        result.put("changed", changed);
        result.put("bound", baseBound && candBound);
        if (changed) {
            result.put("note", "两次 run 绑定的 prompt 版本不同：结论只说明相对差异，不能当作同一 prompt 的回归证据");
        }
        return result;
    }

    private Map<String, Object> promptRef(DatasetRunEntity run) {
        Map<String, Object> ref = new LinkedHashMap<>();
        ref.put("name", run.getPromptName());
        ref.put("version", run.getPromptVersion());
        ref.put("label", run.getPromptLabel());
        ref.put("hash", run.getPromptHash());
        return ref;
    }

    private Map<String, Object> emptySummary(boolean compatible) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("diffAvailable", compatible);
        summary.put("totalItems", 0);
        summary.put("paired", 0);
        summary.put("missingCount", 0);
        summary.put("passToPass", 0);
        summary.put("failToFail", 0);
        summary.put("passToFail", 0);
        summary.put("failToPass", 0);
        summary.put("unknownCount", 0);
        summary.put("errorCount", 0);
        summary.put("other", 0);
        summary.put("regressionCount", 0);
        summary.put("fixCount", 0);
        return summary;
    }

    private Map<String, Object> runSummary(DatasetRunEntity run) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", run.getId());
        m.put("status", run.getStatus());
        m.put("runMode", run.getRunMode());
        m.put("datasetRevisionId", run.getDatasetRevisionId());
        m.put("candidateLabel", run.getCandidateLabel());
        m.put("candidateFingerprint", run.getCandidateFingerprint());
        m.put("passCount", run.getPassCount());
        m.put("failCount", run.getFailCount());
        m.put("unknownCount", run.getUnknownCount());
        m.put("errorCount", run.getErrorCount());
        m.put("incompleteCount", run.getIncompleteCount());
        m.put("passRate", run.getPassRate());
        m.put("coverage", run.getCoverage());
        m.put("itemCount", run.getItemCount());
        m.put("promptName", run.getPromptName());
        m.put("promptVersion", run.getPromptVersion());
        m.put("promptLabel", run.getPromptLabel());
        m.put("promptHash", run.getPromptHash());
        m.put("runLabel", run.getRunLabel());
        m.put("commitSha", run.getCommitSha());
        return m;
    }

    private String transition(String base, String cand) {
        if (base == null) return "MISSING->" + label(cand);
        if (cand == null) return label(base) + "->MISSING";
        if (PASS.equals(base) && PASS.equals(cand)) return "PASS->PASS";
        if (FAIL.equals(base) && FAIL.equals(cand)) return "FAIL->FAIL";
        if (PASS.equals(base) && FAIL.equals(cand)) return "PASS->FAIL";
        if (FAIL.equals(base) && PASS.equals(cand)) return "FAIL->PASS";
        if (UNKNOWN.equals(base) || UNKNOWN.equals(cand)) return "UNKNOWN_CHANGE";
        return label(base) + "->" + label(cand);
    }

    private boolean isError(String label) {
        return EvaluatorEntity.LABEL_ERROR.equals(label)
                || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)
                || EvaluatorEntity.LABEL_NOT_APPLICABLE.equals(label);
    }

    private String label(String value) {
        return value == null ? "UNKNOWN" : value;
    }

    private Map<String, DatasetRunItemEntity> byItemId(List<DatasetRunItemEntity> items) {
        Map<String, DatasetRunItemEntity> result = new LinkedHashMap<>();
        for (DatasetRunItemEntity item : items) {
            result.put(item.getItemId(), item);
        }
        return result;
    }

    /** 支持新格式 evaluators[] 与旧格式 evaluator；key/version/fingerprint 任一缺失即视为无证据。 */
    private List<String> evaluatorFingerprints(Map<String, Object> config) {
        List<?> raw = null;
        Object list = config.get("evaluators");
        if (list instanceof List && !((List<?>) list).isEmpty()) {
            raw = (List<?>) list;
        } else {
            Object singular = config.get("evaluator");
            if (singular instanceof Map) {
                raw = List.of(singular);
            }
        }
        if (raw == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (Object value : raw) {
            if (!(value instanceof Map)) {
                return List.of();
            }
            Map<?, ?> map = (Map<?, ?>) value;
            String key = asString(map.get("key"));
            String version = asString(map.get("version"));
            String fingerprint = asString(map.get("fingerprint"));
            if (isBlank(key) || isBlank(version) || isBlank(fingerprint)) {
                return List.of();
            }
            result.add(key + "@" + version + "#" + fingerprint);
        }
        Collections.sort(result);
        return result;
    }

    private boolean snapshotUsable(DatasetRunEntity run, Map<String, Object> config) {
        return !isBlank(run.getConfigSnapshotJson()) && config != null && !config.isEmpty();
    }

    private Map<String, Object> config(DatasetRunEntity run) {
        if (run.getConfigSnapshot() != null && !run.getConfigSnapshot().isEmpty()) {
            return run.getConfigSnapshot();
        }
        if (run.getConfigSnapshotJson() == null || run.getConfigSnapshotJson().isEmpty()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(run.getConfigSnapshotJson(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String firstNonBlank(String first, String second) {
        if (!isBlank(first)) {
            return first;
        }
        return isBlank(second) ? null : second;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
