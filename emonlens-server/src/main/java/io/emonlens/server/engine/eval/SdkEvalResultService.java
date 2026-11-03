package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvalTaskEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * SDK 评测器（EvaluatorEntity.TYPE_SDK）的外部执行通道：
 * 服务端声明输入契约（spec.requiredFields），判定逻辑在用户环境执行，结果经开放 API 回写。
 *
 * <p>生命周期：worker 执行样本时跳过 SDK 评估器，为其写入 AWAITING_SDK_RESULTS 的 UNKNOWN
 * 占位条目（计数守恒）；run 收口后 SDK 任务保持 PENDING，外部通过本服务拉取载荷、回写结果；
 * 每次回写后按任务账本重聚合样本并刷新 run 计数。只有显式取消与宽限清理能收敛未回写任务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SdkEvalResultService {

    private final DatasetRunService datasetRunService;
    private final EvalTaskService evalTaskService;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;

    // ==================== 载荷下发 ====================

    /**
     * run 的 SDK 任务清单与样本载荷。无内容权限时只下发 hash / 标签 / 状态，
     * 正文一律不出开放 API——与导出、任务查询的脱敏口径一致。
     */
    public Map<String, Object> sdkTasks(String workspaceId, String runId, boolean contentAllowed) {
        DatasetRunEntity run = datasetRunService.requireRun(workspaceId, runId);
        Map<String, Map<String, Object>> evaluatorsById =
                indexById(evaluatorsFrom(parseMap(run.getConfigSnapshotJson())));
        Map<String, EvalTaskEntity> tasks = evalTaskService.loadForRun(runId);
        Map<String, DatasetRunItemEntity> itemsById = new LinkedHashMap<>();
        for (DatasetRunItemEntity item : datasetRunService.itemsOf(runId)) {
            itemsById.put(item.getId(), item);
        }

        List<Map<String, Object>> records = new ArrayList<>();
        for (EvalTaskEntity task : evalTaskService.openSdkTasks(runId)) {
            DatasetRunItemEntity item = itemsById.get(task.getRunItemId());
            if (item == null) {
                continue;
            }
            Map<String, Object> evaluator = evaluatorsById.get(task.getEvaluatorId());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("taskId", task.getId());
            record.put("itemId", task.getItemId());
            record.put("itemOrder", task.getItemOrder());
            record.put("evaluatorId", task.getEvaluatorId());
            record.put("evaluatorKey", task.getEvaluatorKey());
            record.put("evaluatorVersion", task.getEvaluatorVersion());
            record.put("requiredFields", evaluator == null ? List.of()
                    : evaluator.getOrDefault("requiredFields", List.of()));
            record.put("sample", samplePayload(item, contentAllowed));
            records.add(record);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("runStatus", run.getStatus());
        result.put("contentRestricted", !contentAllowed);
        result.put("openSdkTasks", records.size());
        result.put("records", records);
        return result;
    }

    /** 单条任务的样本载荷：来自受理时冻结的快照，REPLAY 补充执行期生成的候选输出。 */
    private Map<String, Object> samplePayload(DatasetRunItemEntity item, boolean contentAllowed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Map<String, Object> snapshot = parseMap(item.getSampleSnapshotJson());
        payload.put("caseHash", snapshot == null ? null : snapshot.get("caseHash"));
        payload.put("schemaVersion", snapshot == null ? null : snapshot.get("schemaVersion"));
        payload.put("contentStatus", snapshot == null ? null : snapshot.get("contentStatus"));
        if (!contentAllowed) {
            return payload;
        }
        if (snapshot == null) {
            return payload;
        }
        EvaluationSample sample = SampleSnapshots.from(snapshot);
        payload.put("input", sample.getInput());
        payload.put("expectedOutput", sample.getExpectedOutput());
        payload.put("candidateOutput", firstNonBlank(sample.getCandidateOutput(),
                candidateFromDetails(item)));
        payload.put("retrievalContext", sample.getRetrievalContext());
        payload.put("expectedTools", sample.getExpectedTools());
        payload.put("toolsCalled", sample.getToolsCalled());
        payload.put("turns", sample.getTurns());
        payload.put("expectations", sample.getExpectations());
        payload.put("traceDigest", sample.getTraceDigest());
        payload.put("candidateComplete", sample.getCandidateComplete());
        return payload;
    }

    private String candidateFromDetails(DatasetRunItemEntity item) {
        Map<String, Object> details = parseMap(item.getResultDetailsJson());
        return details == null ? null : asString(details.get("candidateOutput"));
    }

    // ==================== 结果回写 ====================

    public Map<String, Object> pushResults(String workspaceId, String runId,
                                           List<SdkResultInput> inputs) {
        DatasetRunEntity run = datasetRunService.requireRun(workspaceId, runId);
        if (!isTerminal(run.getStatus())) {
            throw new EmonLensException(ErrorCode.CONFLICT,
                    "run 仍在执行（" + run.getStatus() + "）；等待终态后再回写 SDK 结果");
        }
        if (inputs == null || inputs.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "results 不能为空");
        }
        Map<String, Object> config = parseMap(run.getConfigSnapshotJson());
        List<Map<String, Object>> evaluators = evaluatorsFrom(config);
        Map<String, Map<String, Object>> evaluatorsByKey = indexByKey(evaluators);
        Map<String, Map<String, Object>> evaluatorsById = indexById(evaluators);
        Map<String, DatasetRunItemEntity> itemsByItemId = new LinkedHashMap<>();
        for (DatasetRunItemEntity item : datasetRunService.itemsOf(runId)) {
            itemsByItemId.put(item.getItemId(), item);
        }
        Map<String, EvalTaskEntity> tasks = evalTaskService.loadForRun(runId);

        List<Map<String, Object>> outcomes = new ArrayList<>();
        java.util.Set<String> touchedItems = new java.util.LinkedHashSet<>();
        for (SdkResultInput input : inputs) {
            outcomes.add(pushOne(run, evaluatorsByKey, evaluatorsById, itemsByItemId, tasks,
                    input, touchedItems));
        }
        for (String runItemId : touchedItems) {
            reaggregateItem(runId, runItemId, tasks, evaluators);
        }
        datasetRunService.refreshTerminalCounters(runId);

        DatasetRunEntity fresh = datasetRunService.requireRun(workspaceId, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("results", outcomes);
        result.put("openSdkTasks", evalTaskService.countOpenSdkTasks(runId));
        result.put("passCount", fresh.getPassCount());
        result.put("failCount", fresh.getFailCount());
        return result;
    }

    private Map<String, Object> pushOne(DatasetRunEntity run,
                                        Map<String, Map<String, Object>> evaluatorsByKey,
                                        Map<String, Map<String, Object>> evaluatorsById,
                                        Map<String, DatasetRunItemEntity> itemsByItemId,
                                        Map<String, EvalTaskEntity> tasks,
                                        SdkResultInput input,
                                        java.util.Set<String> touchedItems) {
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("itemId", input.getItemId());
        outcome.put("evaluatorKey", input.getEvaluatorKey());
        DatasetRunItemEntity item = itemsByItemId.get(input.getItemId());
        if (item == null) {
            outcome.put("outcome", "REJECTED");
            outcome.put("reasonCode", "ITEM_NOT_IN_RUN");
            return outcome;
        }
        Map<String, Object> evaluator = evaluatorsByKey.get(input.getEvaluatorKey());
        if (evaluator == null || !EvaluatorEntity.TYPE_SDK.equals(evaluator.get("type"))) {
            outcome.put("outcome", "REJECTED");
            outcome.put("reasonCode", "EVALUATOR_NOT_SDK");
            return outcome;
        }
        EvalTaskEntity task = tasks.get(item.getId() + "|" + evaluator.get("id"));
        if (task == null) {
            outcome.put("outcome", "REJECTED");
            outcome.put("reasonCode", "TASK_NOT_FOUND");
            return outcome;
        }

        EvalResultEntity result = new EvalResultEntity();
        result.setLabel(normalizeVerdict(input.getVerdict()));
        result.setScore(input.getScore() == null ? null : BigDecimal.valueOf(input.getScore()));
        result.setReasonCode(input.getReasonCode());
        result.setReason(truncate(input.getReason(), 2000));
        result.setJudgeModel(input.getModel());
        result.setPromptTokens(input.getPromptTokens());
        result.setCompletionTokens(input.getCompletionTokens());
        int latencyMs = input.getLatencyMs() == null ? 0 : input.getLatencyMs();

        if (evalTaskService.completeExternal(task, result, latencyMs)) {
            touchedItems.add(item.getId());
            outcome.put("outcome", "APPLIED");
            outcome.put("verdict", task.getVerdict());
            return outcome;
        }
        // 终态不可覆盖：与已落账本结论一致视为幂等重复，不一致视为冲突
        EvalTaskEntity settled = evalTaskService.reload(task.getId());
        outcome.put("verdict", input.getVerdict());
        if (settled != null && sameVerdict(settled.getVerdict(), result.getLabel())
                && sameScore(settled.getScore(), result.getScore())) {
            outcome.put("outcome", "DUPLICATE");
        } else {
            outcome.put("outcome", "CONFLICT");
            outcome.put("settledVerdict", settled == null ? null : settled.getVerdict());
        }
        return outcome;
    }

    // ==================== 样本重聚合 ====================

    /**
     * 回写/清理后重建样本结论：以任务账本为事实源替换 SDK 占位条目，
     * 服务端条目保持不动，聚合口径与 DatasetRunWorker 完全一致。
     */
    private void reaggregateItem(String runId, String runItemId, Map<String, EvalTaskEntity> tasks,
                                 List<Map<String, Object>> evaluators) {
        DatasetRunItemEntity item = datasetRunService.itemOf(runId, runItemId);
        if (item == null) {
            return;
        }
        Map<String, Map<String, Object>> sdkById = new LinkedHashMap<>();
        for (Map<String, Object> evaluator : evaluators) {
            if (EvaluatorEntity.TYPE_SDK.equals(evaluator.get("type"))) {
                sdkById.put(asString(evaluator.get("id")), evaluator);
            }
        }
        List<Map<String, Object>> entries = parseList(item.getEvaluatorResultsJson());
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> entry : entries) {
            if (sdkById.containsKey(asString(entry.get("evaluatorId")))) {
                continue;
            }
            merged.add(entry);
        }
        for (Map<String, Object> evaluator : sdkById.values()) {
            EvalTaskEntity task = tasks.get(runItemId + "|" + evaluator.get("id"));
            merged.add(sdkEntry(evaluator, task));
        }

        String label = RunItemAggregator.aggregateLabel(merged);
        item.setLabel(label);
        item.setScore(RunItemAggregator.averageScore(merged));
        item.setReason(truncate(RunItemAggregator.aggregateReason(merged), 1000));
        item.setEvaluatorResultsJson(toJson(merged));
        item.setEvaluatorResults(merged);
        Map<String, Object> details = parseMap(item.getResultDetailsJson());
        if (details == null) {
            details = new LinkedHashMap<>();
        }
        details.put("status", RunItemAggregator.statusOf(label));
        details.put("reasonCode", RunItemAggregator.aggregateReasonCode(merged, label));
        details.put("reason", truncate(RunItemAggregator.aggregateReason(merged), 5000));
        details.put("evaluators", merged);
        item.setResultDetailsJson(toJson(details));
        item.setResultDetails(details);
        datasetRunService.saveItem(item);
    }

    /** SDK 任务 → 样本级条目：有判定用判定，被清理/未回写按 UNKNOWN 诚实降级。 */
    private Map<String, Object> sdkEntry(Map<String, Object> evaluator, EvalTaskEntity task) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("evaluatorId", evaluator.get("id"));
        entry.put("evaluatorKey", evaluator.get("key"));
        entry.put("evaluatorVersion", evaluator.get("version"));
        entry.put("type", EvaluatorEntity.TYPE_SDK);
        if (task != null && task.getVerdict() != null && !task.getVerdict().isBlank()) {
            entry.put("label", task.getVerdict());
            entry.put("score", task.getScore());
            entry.put("reasonCode", task.getReasonCode());
            entry.put("reason", task.getReason());
        } else if (task != null && EvalTaskEntity.STATUS_SKIPPED.equals(task.getExecutionStatus())) {
            entry.put("label", EvaluatorEntity.LABEL_UNKNOWN);
            entry.put("score", null);
            entry.put("reasonCode", task.getReasonCode() == null
                    ? "SDK_RESULT_NOT_RECEIVED" : task.getReasonCode());
            entry.put("reason", task.getReason());
        } else {
            entry.put("label", EvaluatorEntity.LABEL_UNKNOWN);
            entry.put("score", null);
            entry.put("reasonCode", "AWAITING_SDK_RESULTS");
            entry.put("reason", "等待 SDK 评测结果回写");
        }
        entry.put("origin", "SDK");
        entry.put("judgeCalled", false);
        return entry;
    }

    // ==================== 宽限清理 ====================

    /**
     * 终端 run 上超过宽限期仍未回写的 SDK 任务按 SKIPPED 收敛（SDK_RESULT_NOT_RECEIVED），
     * 并重聚合受影响样本。活动 run 的任务不受影响。调用方自行控制频率（worker 按分钟节流）。
     *
     * @return 收敛的任务数
     */
    public int recoverExpiredSdkTasks() {
        int graceHours = Math.max(1, properties.getEval().getSdkResultGraceHours());
        List<EvalTaskEntity> expired = evalTaskService.settleExpiredSdkTasks(
                LocalDateTime.now().minusHours(graceHours), 500);
        if (expired.isEmpty()) {
            return 0;
        }
        Map<String, java.util.Set<String>> runItems = new LinkedHashMap<>();
        for (EvalTaskEntity task : expired) {
            runItems.computeIfAbsent(task.getRunId(), key -> new java.util.LinkedHashSet<>())
                    .add(task.getRunItemId());
        }
        int settled = 0;
        for (Map.Entry<String, java.util.Set<String>> entry : runItems.entrySet()) {
            DatasetRunEntity run = datasetRunService.requireRunQuietly(entry.getKey());
            if (run == null || !isTerminal(run.getStatus())) {
                continue;
            }
            Map<String, EvalTaskEntity> tasks = evalTaskService.loadForRun(entry.getKey());
            List<Map<String, Object>> evaluators = evaluatorsFrom(parseMap(run.getConfigSnapshotJson()));
            for (String runItemId : entry.getValue()) {
                reaggregateItem(entry.getKey(), runItemId, tasks, evaluators);
                settled++;
            }
            datasetRunService.refreshTerminalCounters(entry.getKey());
            log.info("expired sdk tasks settled, run={}, items={}", entry.getKey(), entry.getValue().size());
        }
        return settled;
    }

    // ==================== 输入与工具 ====================

    /** 回写请求的单条结果；verdict 支持 PASS/FAIL/UNKNOWN/ERROR（大小写不敏感）。 */
    @Data
    public static class SdkResultInput {
        private String itemId;
        private String evaluatorKey;
        private String verdict;
        private Double score;
        private String reasonCode;
        private String reason;
        private String model;
        private Integer promptTokens;
        private Integer completionTokens;
        private Integer latencyMs;
    }

    private String normalizeVerdict(String verdict) {
        if (verdict == null || verdict.isBlank()) {
            return EvaluatorEntity.LABEL_ERROR;
        }
        String raw = verdict.trim().toUpperCase();
        if (EvaluatorEntity.LABEL_PASS.equals(raw) || EvaluatorEntity.LABEL_FAIL.equals(raw)
                || EvaluatorEntity.LABEL_UNKNOWN.equals(raw) || EvaluatorEntity.LABEL_ERROR.equals(raw)) {
            return raw;
        }
        return EvaluatorEntity.LABEL_UNKNOWN;
    }

    private boolean sameVerdict(String settled, String incoming) {
        return settled != null && settled.equals(normalizeVerdict(incoming));
    }

    private boolean sameScore(BigDecimal settled, BigDecimal incoming) {
        if (settled == null && incoming == null) {
            return true;
        }
        return settled != null && incoming != null
                && settled.compareTo(incoming) == 0;
    }

    private boolean isTerminal(String status) {
        return !DatasetRunEntity.STATUS_PENDING.equals(status)
                && !DatasetRunEntity.STATUS_RUNNING.equals(status);
    }

    /** run 配置快照 → 冻结评估器列表（唯一，按快照顺序）。 */
    private List<Map<String, Object>> evaluatorsFrom(Map<String, Object> config) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (config == null) {
            return result;
        }
        Object rawList = config.get("evaluators");
        if (!(rawList instanceof List)) {
            return result;
        }
        for (Object raw : (List<?>) rawList) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> evaluator = new LinkedHashMap<>();
            Map<?, ?> source = (Map<?, ?>) raw;
            evaluator.put("id", source.get("id"));
            evaluator.put("key", source.get("key"));
            evaluator.put("type", source.get("type"));
            evaluator.put("version", source.get("version"));
            Object spec = source.get("spec");
            evaluator.put("requiredFields", spec instanceof Map
                    ? ((Map<?, ?>) spec).get("requiredFields") : null);
            result.add(evaluator);
        }
        return result;
    }

    private Map<String, Map<String, Object>> indexByKey(List<Map<String, Object>> evaluators) {
        return index(evaluators, "key");
    }

    private Map<String, Map<String, Object>> indexById(List<Map<String, Object>> evaluators) {
        return index(evaluators, "id");
    }

    private Map<String, Map<String, Object>> index(List<Map<String, Object>> evaluators, String field) {
        Map<String, Map<String, Object>> index = new LinkedHashMap<>();
        for (Map<String, Object> evaluator : evaluators) {
            index.put(asString(evaluator.get(field)), evaluator);
        }
        return index;
    }

    private String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        return b;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception e) {
            return null;
        }
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {
                    });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("sdk eval result json serialization failed", e);
            return "{}";
        }
    }
}
