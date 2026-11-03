package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvalTaskEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * M3：后台执行数据集运行；workerThreads 控制本实例并行线程数。
 * - 串行处理样本，无自动重试；每条样本最多一次生成、一次 Judge。
 * - 进程启动时把本链路残留的 RUNNING 标为 INTERRUPTED，未执行项保留为未完成。
 * - 租约 fencing、预算预留账本、取消、断点续跑由 DatasetRunService 支持；本类在 item 边界停机。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatasetRunWorker {

    private final DatasetRunService datasetRunService;
    private final DatasetRunItemMapper datasetRunItemMapper;
    private final DatasetReplayService datasetReplayService;
    private final EvaluationRunService evaluationRunService;
    private final DatasetRunCredentialStore credentialStore;
    private final RunTokenBudgetStore tokenBudgetStore;
    private final EvalTaskService evalTaskService;
    private final EvalCacheService evalCacheService;
    private final SdkEvalResultService sdkEvalResultService;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;

    private volatile boolean running = true;
    private final List<Thread> workers = new ArrayList<>();

    /** SDK 宽限清理的扫描间隔：清理本身是轻查询，但不必每个轮询周期都跑 */
    private static final long SDK_SWEEP_INTERVAL_MS = 60_000L;

    @PostConstruct
    public void start() {
        if (!properties.getWorker().isEnabled()) {
            log.info("dataset run worker disabled by emonlens.worker.enabled=false");
            return;
        }
        try {
            datasetRunService.recoverInterrupted();
        } catch (Exception e) {
            log.error("dataset run interruption recovery failed", e);
        }
        int threadCount = Math.max(1, Math.min(8, properties.getDatasetRun().getWorkerThreads()));
        for (int i = 0; i < threadCount; i++) {
            Thread thread = new Thread(this::loop, "dataset-run-worker-" + i);
            thread.setDaemon(true);
            workers.add(thread);
            thread.start();
        }
        log.info("dataset run workers started, threads={}", threadCount);
    }

    @PreDestroy
    public void stop() {
        running = false;
        for (Thread thread : workers) {
            thread.interrupt();
        }
    }

    private void loop() {
        long lastSdkSweep = 0;
        while (running) {
            try {
                datasetRunService.recoverExpiredLeases();
                long now = System.currentTimeMillis();
                if (now - lastSdkSweep > SDK_SWEEP_INTERVAL_MS) {
                    lastSdkSweep = now;
                    sdkEvalResultService.recoverExpiredSdkTasks();
                }
                DatasetRunEntity run = datasetRunService.claimNextPending();
                if (run != null) {
                    try {
                        execute(run);
                    } catch (Exception e) {
                        log.error("dataset run execution failed, run={}", run.getId(), e);
                        try {
                            abort(run, "运行执行异常：" + (e.getMessage() == null
                                    ? e.getClass().getSimpleName() : e.getMessage()));
                        } catch (Exception abortError) {
                            log.error("dataset run abort failed, run={}", run.getId(), abortError);
                        }
                    }
                    continue;
                }
                Thread.sleep(Math.max(200, properties.getDatasetRun().getPollIntervalMs()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.error("dataset run worker loop error", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void execute(DatasetRunEntity run) {
        // 同一 run 的所有外部调用使用受理时冻结的凭据/配置；执行完即从内存移除
        credentialStore.bind(run.getId());
        tokenBudgetStore.bind(run.getId(), run.getLeaseToken(), run.getMaxTokens());
        try {
            executeBound(run);
        } finally {
            tokenBudgetStore.clear();
            credentialStore.clear();
            credentialStore.remove(run.getId());
        }
    }

    private void executeBound(DatasetRunEntity run) {
        String leaseToken = run.getLeaseToken();
        Map<String, Object> config = parseMap(run.getConfigSnapshotJson());
        if (config == null) {
            abort(run, "配置快照缺失，无法执行");
            return;
        }
        String mode = asString(config.get("runMode"));
        List<EvaluatorEntity> evaluators = evaluatorsFrom(config);
        if (evaluators.isEmpty()) {
            abort(run, "评估器快照缺失，无法执行");
            return;
        }
        List<DatasetRunItemEntity> items = new ArrayList<>(datasetRunService.itemsOf(run.getId()));
        Map<String, EvalTaskEntity> tasks = evalTaskService.loadForRun(run.getId());
        items.sort(Comparator.comparingInt(this::orderOf).thenComparing(DatasetRunItemEntity::getId,
                Comparator.nullsLast(Comparator.naturalOrder())));

        Integer budgetLimit = budgetLimit(config);
        int estimatedCalls = estimatedExternalCalls(mode, evaluators);
        // 预算耗尽属于“未执行完”而非“执行失败”：标记 INTERRUPTED，保留剩余样本可续跑
        boolean stoppedByBudget = false;
        for (int index = 0; index < items.size(); index++) {
            DatasetRunItemEntity item = items.get(index);
            if (!running || !datasetRunService.renewLease(run.getId(), leaseToken)) {
                return;
            }
            if (item.getLabel() != null) {
                continue;
            }
            // 在 item 执行前把最坏调用数原子写入持久账本；崩溃后预留仍保留，避免预算被退回。
            if (budgetLimit != null) {
                DatasetRunService.ExternalCallReservation reservation =
                        datasetRunService.reserveExternalCalls(run.getId(), leaseToken,
                                estimatedCalls, budgetLimit);
                if (reservation == DatasetRunService.ExternalCallReservation.LEASE_LOST) {
                    return;
                }
                if (reservation == DatasetRunService.ExternalCallReservation.BUDGET_EXCEEDED) {
                    markBudgetExceeded(items.subList(index, items.size()), budgetLimit);
                    evalTaskService.skipPendingTasks(run.getId(), "BUDGET_EXCEEDED",
                            "外部调用预算已用尽，任务未执行");
                    stoppedByBudget = true;
                    break;
                }
            }
            try {
                executeItem(run, item, mode, evaluators, config, tasks);
            } catch (RunTokenBudgetStore.BudgetExceededException e) {
                markTokenBudgetExceeded(items.subList(index, items.size()), e.getMessage());
                evalTaskService.skipPendingTasks(run.getId(), "TOKEN_BUDGET_EXCEEDED", e.getMessage());
                stoppedByBudget = true;
                break;
            }
            if (!datasetRunService.renewLease(run.getId(), leaseToken)) {
                return;
            }
            datasetRunService.refreshProgress(run);
        }
        if (datasetRunService.renewLease(run.getId(), leaseToken)) {
            datasetRunService.finish(run, leaseToken, stoppedByBudget);
        }
    }

    private void executeItem(DatasetRunEntity run, DatasetRunItemEntity item, String mode,
                             List<EvaluatorEntity> evaluators, Map<String, Object> config,
                             Map<String, EvalTaskEntity> tasks) {
        // SDK 评估器由外部执行回写，服务端只跑其余类型；SDK 任务在账本里保持 PENDING，
        // 其样本级结论先按 AWAITING_SDK_RESULTS 的 UNKNOWN 占位，回写后重聚合翻正。
        List<EvaluatorEntity> serverEvaluators = evaluators.stream()
                .filter(e -> !EvaluatorEntity.TYPE_SDK.equals(e.getType()))
                .collect(java.util.stream.Collectors.toList());
        if (!evalTaskService.beginItem(tasks, item.getId(), Set.of(EvaluatorEntity.TYPE_SDK))) {
            log.info("item tasks no longer runnable, skip item, run={} item={}", run.getId(), item.getId());
            return;
        }
        Map<String, Object> snapshot = parseMap(item.getSampleSnapshotJson());
        if (snapshot == null) {
            recordFailure(item, EvaluatorEntity.LABEL_ERROR, "SNAPSHOT_MISSING",
                    "样本快照无法解析", evaluators, 0, 0, null, null, null, null, null, tasks);
            return;
        }
        EvaluationSample sample = SampleSnapshots.from(snapshot);
        long start = System.currentTimeMillis();
        int generationCalls = 0;
        Integer generationPromptTokens = null;
        Integer generationCompletionTokens = null;
        String replayOutputForColumn = null;
        int maxChars = properties.getDatasetRun().getMaxSnapshotChars();

        try {
            if (DatasetRunSnapshotService.MODE_REPLAY.equals(mode)) {
                String input = sample.getInput();
                if (input == null || input.isBlank()) {
                    recordFailure(item, EvaluatorEntity.LABEL_ERROR, "INPUT_MISSING",
                            "样本缺少 input，无法重放", evaluators, 0, 0, sample,
                            null, null, null, null, tasks);
                    return;
                }
                Map<String, Object> generation = asMap(config.get("generation"));
                String systemPrompt = generation == null ? null : asString(generation.get("systemPrompt"));
                String model = generation == null ? null : asString(generation.get("model"));
                DatasetReplayService.ReplayResult replay;
                generationCalls = 1;
                try {
                    replay = datasetReplayService.replay(
                            run.getWorkspaceId(), "dataset-replay", input, systemPrompt, model);
                } catch (LlmUnavailableException e) {
                    // 未配置端点时没有真正发出外部调用，不把费用/调用数记成 1
                    if (String.valueOf(e.getMessage()).contains("not configured")) {
                        generationCalls = 0;
                    }
                    throw e;
                }
                generationPromptTokens = replay.getPromptTokens();
                generationCompletionTokens = replay.getCompletionTokens();
                sample.setTraceId(replay.getTraceId());
                sample.setSourceTraceId(asString(snapshot.get("sourceTraceId")));
                if (sample.getAgentKey() == null || sample.getAgentKey().isBlank()) {
                    sample.setAgentKey(DatasetRunSnapshotService.REPLAY_AGENT_KEY);
                    sample.setAgentVersion(DatasetRunSnapshotService.REPLAY_AGENT_VERSION);
                }
                sample.setCandidateOutput(replay.getOutput());
                sample.setSpanCount(2);
                sample.setErrorCount(0);
                sample.setDurationMs(replay.getLatencyMs());
                sample.setToolCalls(0L);
                sample.setLlmCalls(1L);
                boolean complete = replay.getOutput() == null || replay.getOutput().length() <= maxChars;
                sample.setCandidateComplete(complete);
                sample.setContentStatus(complete ? "COMPLETE" : "CONTENT_TRUNCATED");
                sample.setContentStatusReason(complete ? null
                        : "重放输出超过快照上限 " + maxChars + " 字符，无法完整评估");
                sample.getContents().put("prompt", input);
                sample.getContentCaptureModes().put("prompt", "FULL");
                sample.getContentTruncated().put("prompt", false);
                if (replay.getOutput() != null) {
                    sample.getContents().put("completion", replay.getOutput());
                    sample.getContentCaptureModes().put("completion", "FULL");
                    sample.getContentTruncated().put("completion", !complete);
                }
                replayOutputForColumn = storeText(replay.getOutput(), maxChars);
            } else if ("TRACE_MISSING".equals(sample.getContentStatus())
                    || "TRACE_NOT_COMPLETED".equals(sample.getContentStatus())) {
                recordFailure(item, EvaluatorEntity.LABEL_UNKNOWN, sample.getContentStatus(),
                        sample.getContentStatusReason(), evaluators, 0, 0, sample,
                        sample.getTraceId(), sample.getSourceTraceId(), null, null, tasks);
                return;
            }

            List<Map<String, Object>> evaluatorResults = new ArrayList<>();
            int judgeCalls = 0;
            Integer judgePromptTokens = null;
            Integer judgeCompletionTokens = null;
            for (EvaluatorEntity evaluator : serverEvaluators) {
                Map<String, Object> resultMap = new LinkedHashMap<>();
                resultMap.put("evaluatorId", evaluator.getId());
                resultMap.put("evaluatorKey", evaluator.getEvaluatorKey());
                resultMap.put("evaluatorVersion", evaluator.getVersion());
                resultMap.put("type", evaluator.getType());
                EvalTaskEntity task = tasks.get(item.getId() + "|" + evaluator.getId());
                long evaluatorStart = System.currentTimeMillis();
                if (task != null && !evalTaskService.start(task)) {
                    // 任务在开始前已被取消/收敛：不再发起外部调用，避免为已取消任务付费。
                    log.info("eval task no longer runnable, skip evaluator, task={} run={}",
                            task.getId(), run.getId());
                    continue;
                }
                try {
                    EvalResultEntity result;
                    EvalCacheService.CacheKey cacheKey = evalCacheService.key(
                            run.getWorkspaceId(), evaluator, sample);
                    if (cacheKey != null) {
                        result = evalCacheService.get(run.getWorkspaceId(), cacheKey);
                        if (result == null) {
                            result = evaluationRunService.evaluateSample(evaluator, sample);
                            evalCacheService.put(run.getWorkspaceId(), cacheKey, result);
                        }
                    } else {
                        result = evaluationRunService.evaluateSample(evaluator, sample);
                    }
                    String label = normalizeLabel(result.getLabel());
                    int calls = Boolean.TRUE.equals(result.getJudgeCalled()) ? 1 : 0;
                    judgeCalls += calls;
                    judgePromptTokens = plus(judgePromptTokens, result.getPromptTokens());
                    judgeCompletionTokens = plus(judgeCompletionTokens, result.getCompletionTokens());
                    resultMap.put("label", label);
                    resultMap.put("score", result.getScore());
                    resultMap.put("reasonCode", result.getReasonCode());
                    resultMap.put("reason", truncate(result.getReason() == null ? "" : result.getReason(), 5000));
                    resultMap.put("judgeModel", result.getJudgeModel());
                    if (result.getCriterionResultsJson() != null
                            && !result.getCriterionResultsJson().isEmpty()) {
                        resultMap.put("criteria", parseJson(result.getCriterionResultsJson()));
                    }
                    resultMap.put("judgeMode", result.getJudgeMode());
                    resultMap.put("preset", result.getPreset());
                    if (result.getJudgeTemplateKey() != null) {
                        Map<String, Object> template = new LinkedHashMap<>();
                        template.put("key", result.getJudgeTemplateKey());
                        template.put("version", result.getJudgeTemplateVersion());
                        template.put("hash", result.getJudgeTemplateHash());
                        resultMap.put("judgeTemplate", template);
                    }
                    if (result.getClaimsJson() != null && !result.getClaimsJson().isEmpty()) {
                        resultMap.put("claims", parseJson(result.getClaimsJson()));
                    }
                    if (result.getClaimAggregateJson() != null
                            && !result.getClaimAggregateJson().isEmpty()) {
                        resultMap.put("claimAggregate", parseJson(result.getClaimAggregateJson()));
                    }
                    resultMap.put("judgeCalled", calls > 0);
                    resultMap.put("cacheHit", Boolean.TRUE.equals(result.getCacheHit()));
                    resultMap.put("promptTokens", result.getPromptTokens());
                    resultMap.put("completionTokens", result.getCompletionTokens());
                    if (task != null) {
                        evalTaskService.complete(task, result,
                                (int) (System.currentTimeMillis() - evaluatorStart));
                    }
                } catch (RunTokenBudgetStore.BudgetExceededException e) {
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    if (task != null) {
                        evalTaskService.fail(task, "TOKEN_BUDGET_EXCEEDED", message,
                                (int) (System.currentTimeMillis() - evaluatorStart));
                    }
                    evalTaskService.skipRemainingItemTasks(tasks, item.getId(), "TOKEN_BUDGET_EXCEEDED",
                            "token 预算不足，任务未执行");
                    throw e;
                } catch (Exception e) {
                    String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    resultMap.put("label", EvaluatorEntity.LABEL_ERROR);
                    resultMap.put("score", null);
                    resultMap.put("reasonCode", "EVALUATOR_EXECUTION_FAILED");
                    resultMap.put("reason", truncate(message, 5000));
                    resultMap.put("judgeCalled", false);
                    if (task != null) {
                        evalTaskService.fail(task, "EVALUATOR_EXECUTION_FAILED", message,
                                (int) (System.currentTimeMillis() - evaluatorStart));
                    }
                }
                evaluatorResults.add(resultMap);
            }
            for (EvaluatorEntity evaluator : evaluators) {
                if (EvaluatorEntity.TYPE_SDK.equals(evaluator.getType())) {
                    evaluatorResults.add(awaitingSdkEntry(evaluator));
                }
            }

            String aggregateLabel = RunItemAggregator.aggregateLabel(evaluatorResults);
            String aggregateReasonCode = RunItemAggregator.aggregateReasonCode(evaluatorResults, aggregateLabel);
            String aggregateReason = RunItemAggregator.aggregateReason(evaluatorResults);
            BigDecimal aggregateScore = RunItemAggregator.averageScore(evaluatorResults);
            long latencyMs = System.currentTimeMillis() - start;

            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", RunItemAggregator.statusOf(aggregateLabel));
            details.put("reasonCode", aggregateReasonCode);
            details.put("reason", truncate(aggregateReason, 5000));
            details.put("reasonTruncated", aggregateReason.length() > 5000);
            details.put("evaluators", evaluatorResults);
            details.put("candidateOutput", truncate(sample.getCandidateOutput(), maxChars));
            details.put("candidateStored", sample.getCandidateOutput() == null
                    || sample.getCandidateOutput().length() <= maxChars);
            details.put("judgeAdvice", judgeCalls > 0);
            details.put("judgeModels", evaluatorResults.stream()
                    .map(r -> r.get("judgeModel")).filter(v -> v != null)
                    .distinct().collect(java.util.stream.Collectors.toList()));
            details.put("caseStatus", sample.getContentStatus());
            details.put("latencyMs", latencyMs);
            details.put("calls", calls(generationCalls, judgeCalls));
            details.put("usage", usage(generationCalls, judgeCalls, generationPromptTokens,
                    generationCompletionTokens, judgePromptTokens, judgeCompletionTokens));
            if (EvaluatorEntity.LABEL_ERROR.equals(aggregateLabel)) {
                details.put("errorCode", aggregateReasonCode);
                details.put("error", truncate(aggregateReason, 5000));
            }

            record(item, aggregateLabel, aggregateReasonCode, aggregateReason, aggregateScore,
                    sample.getTraceId(), sample.getSourceTraceId(), replayOutputForColumn, details,
                    evaluatorResults, generationCalls, judgeCalls,
                    generationPromptTokens, generationCompletionTokens,
                    judgePromptTokens, judgeCompletionTokens);
        } catch (RunTokenBudgetStore.BudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            recordFailure(item, EvaluatorEntity.LABEL_ERROR, "ITEM_EXECUTION_FAILED", message, evaluators,
                    generationCalls, 0, sample, sample.getTraceId(), sample.getSourceTraceId(),
                    replayOutputForColumn, new ExecutionUsage(generationCalls, generationPromptTokens,
                            generationCompletionTokens), tasks);
        }
    }

    /** SDK 评估器的样本级占位条目：结果回写前按 UNKNOWN 参与聚合，保持计数守恒。 */
    private Map<String, Object> awaitingSdkEntry(EvaluatorEntity evaluator) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("evaluatorId", evaluator.getId());
        entry.put("evaluatorKey", evaluator.getEvaluatorKey());
        entry.put("evaluatorVersion", evaluator.getVersion());
        entry.put("type", evaluator.getType());
        entry.put("label", EvaluatorEntity.LABEL_UNKNOWN);
        entry.put("score", null);
        entry.put("reasonCode", "AWAITING_SDK_RESULTS");
        entry.put("reason", "等待 SDK 评测结果回写");
        entry.put("judgeCalled", false);
        return entry;
    }

    private void recordFailure(DatasetRunItemEntity item, String label, String reasonCode, String reason,
                               List<EvaluatorEntity> evaluators, int generationCalls, int judgeCalls,
                               EvaluationSample sample, String traceId, String sourceTraceId,
                               String replayOutput, ExecutionUsage usage,
                               Map<String, EvalTaskEntity> tasks) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (EvaluatorEntity evaluator : evaluators) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("evaluatorId", evaluator.getId());
            result.put("evaluatorKey", evaluator.getEvaluatorKey());
            result.put("evaluatorVersion", evaluator.getVersion());
            result.put("type", evaluator.getType());
            result.put("label", label);
            result.put("score", null);
            result.put("reasonCode", reasonCode);
            result.put("reason", truncate(reason, 5000));
            result.put("judgeCalled", false);
            results.add(result);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", RunItemAggregator.statusOf(label));
        details.put("reasonCode", reasonCode);
        details.put("reason", truncate(reason, 5000));
        details.put("evaluators", results);
        details.put("candidateOutput", sample == null ? null : truncate(sample.getCandidateOutput(),
                properties.getDatasetRun().getMaxSnapshotChars()));
        details.put("caseStatus", sample == null ? null : sample.getContentStatus());
        details.put("calls", calls(generationCalls, judgeCalls));
        details.put("usage", usage(generationCalls, judgeCalls,
                usage == null ? null : usage.generationPromptTokens,
                usage == null ? null : usage.generationCompletionTokens,
                null, null));
        if (EvaluatorEntity.LABEL_ERROR.equals(label)) {
            details.put("errorCode", reasonCode);
            details.put("error", truncate(reason, 5000));
        }
        record(item, label, reasonCode, reason, null, traceId, sourceTraceId, replayOutput, details,
                results, generationCalls, judgeCalls,
                usage == null ? null : usage.generationPromptTokens,
                usage == null ? null : usage.generationCompletionTokens, null, null);
        evalTaskService.finishItemTasks(tasks, item.getId(), label, reasonCode, reason);
    }

    private void record(DatasetRunItemEntity item, String label, String reasonCode, String reason,
                        BigDecimal score, String traceId, String sourceTraceId, String replayOutput,
                        Map<String, Object> details, List<Map<String, Object>> evaluatorResults,
                        int generationCalls, int judgeCalls,
                        Integer generationPromptTokens, Integer generationCompletionTokens,
                        Integer judgePromptTokens, Integer judgeCompletionTokens) {
        item.setLabel(label);
        item.setScore(score);
        item.setReason(truncate(reason == null ? "" : reason, 1000));
        if (traceId != null) {
            item.setTraceId(traceId);
        }
        if (sourceTraceId != null) {
            item.setSourceTraceId(sourceTraceId);
        }
        item.setReplayOutput(replayOutput);
        if (details == null) {
            details = new LinkedHashMap<>();
            details.put("status", EvaluatorEntity.LABEL_ERROR.equals(label) ? "ERROR"
                    : EvaluatorEntity.LABEL_UNKNOWN.equals(label) ? "UNKNOWN" : "DONE");
            details.put("reasonCode", reasonCode);
            details.put("reason", truncate(reason == null ? "" : reason, 5000));
            details.put("calls", calls(generationCalls, judgeCalls));
            details.put("usage", usage(generationCalls, judgeCalls, generationPromptTokens,
                    generationCompletionTokens, judgePromptTokens, judgeCompletionTokens));
        }
        item.setEvaluatorResultsJson(toJson(evaluatorResults == null ? List.of() : evaluatorResults));
        item.setEvaluatorResults(evaluatorResults);
        item.setResultDetailsJson(toJson(details));
        item.setResultDetails(details);
        datasetRunItemMapper.updateById(item);
    }

    @SuppressWarnings("unchecked")
    private List<EvaluatorEntity> evaluatorsFrom(Map<String, Object> config) {
        List<EvaluatorEntity> evaluators = new ArrayList<>();
        Object rawList = config.get("evaluators");
        List<?> values;
        if (rawList instanceof List) {
            values = (List<?>) rawList;
        } else {
            Map<?, ?> single = asMap(config.get("evaluator"));
            values = single == null ? List.of() : List.of(single);
        }
        for (Object raw : values) {
            Map<String, Object> evaluator = asMap(raw);
            if (evaluator == null) {
                continue;
            }
            EvaluatorEntity entity = new EvaluatorEntity();
            entity.setId(asString(evaluator.get("id")));
            entity.setEvaluatorKey(asString(evaluator.get("key")));
            entity.setType(asString(evaluator.get("type")));
            entity.setVersion(asInteger(evaluator.get("version")));
            entity.setWorkspaceId(asString(config.get("workspaceId")));
            try {
                entity.setSpecJson(objectMapper.writeValueAsString(evaluator.get("spec")));
            } catch (Exception e) {
                continue;
            }
            evaluators.add(entity);
        }
        return evaluators;
    }

    private static final class ExecutionUsage {
        private final int generationCalls;
        private final Integer generationPromptTokens;
        private final Integer generationCompletionTokens;

        private ExecutionUsage(int generationCalls, Integer generationPromptTokens,
                               Integer generationCompletionTokens) {
            this.generationCalls = generationCalls;
            this.generationPromptTokens = generationPromptTokens;
            this.generationCompletionTokens = generationCompletionTokens;
        }
    }

    @SuppressWarnings("unchecked")
    private Integer budgetLimit(Map<String, Object> config) {
        Map<String, Object> budget = asMap(config.get("budget"));
        return budget == null ? null : asInteger(budget.get("maxExternalCalls"));
    }

    /** 每个 item 最坏情况：REPLAY 一次生成 + 每个 LLM Judge 一次。 */
    private int estimatedExternalCalls(String mode, List<EvaluatorEntity> evaluators) {
        int estimate = DatasetRunSnapshotService.MODE_REPLAY.equals(mode) ? 1 : 0;
        for (EvaluatorEntity evaluator : evaluators) {
            if (EvaluatorEntity.TYPE_LLM_JUDGE.equals(evaluator.getType())) {
                estimate++;
            }
        }
        return estimate;
    }

    private void markTokenBudgetExceeded(List<DatasetRunItemEntity> items, String message) {
        for (DatasetRunItemEntity item : items) {
            if (item.getLabel() != null) {
                continue;
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", "INCOMPLETE");
            details.put("reasonCode", "TOKEN_BUDGET_EXCEEDED");
            details.put("reason", "token 预算不足，样本未执行：" + message);
            details.put("calls", calls(0, 0));
            details.put("usage", usage(0, 0, null, null, null, null));
            details.put("evaluators", List.of());
            item.setResultDetailsJson(toJson(details));
            item.setResultDetails(details);
            datasetRunItemMapper.updateById(item);
        }
    }

    private void markBudgetExceeded(List<DatasetRunItemEntity> items, int limit) {
        for (DatasetRunItemEntity item : items) {
            if (item.getLabel() != null) {
                continue;
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("status", "INCOMPLETE");
            details.put("reasonCode", "BUDGET_EXCEEDED");
            details.put("reason", "外部调用预算已用尽（上限 " + limit
                    + "）；该样本未执行，避免继续产生费用");
            details.put("calls", calls(0, 0));
            details.put("usage", usage(0, 0, null, null, null, null));
            details.put("evaluators", List.of());
            item.setResultDetailsJson(toJson(details));
            item.setResultDetails(details);
            datasetRunItemMapper.updateById(item);
        }
    }

    private void abort(DatasetRunEntity run, String message) {
        String leaseToken = run.getLeaseToken();
        if (!datasetRunService.renewLease(run.getId(), leaseToken)) {
            return;
        }
        log.error("dataset run aborted, run={}, reason={}", run.getId(), message);
        for (DatasetRunItemEntity item : datasetRunService.itemsOf(run.getId())) {
            if (!datasetRunService.renewLease(run.getId(), leaseToken)) {
                return;
            }
            if (item.getLabel() == null) {
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("status", "INCOMPLETE");
                details.put("reasonCode", "RUN_ABORTED");
                details.put("reason", message);
                item.setResultDetailsJson(toJson(details));
                datasetRunItemMapper.updateById(item);
            }
        }
        datasetRunService.finish(run, leaseToken);
    }

    private String normalizeLabel(String label) {
        if (EvaluatorEntity.LABEL_PASS.equals(label) || EvaluatorEntity.LABEL_FAIL.equals(label)
                || EvaluatorEntity.LABEL_UNKNOWN.equals(label) || EvaluatorEntity.LABEL_ERROR.equals(label)) {
            return label;
        }
        if (EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)
                || EvaluatorEntity.LABEL_NOT_APPLICABLE.equals(label)) {
            return EvaluatorEntity.LABEL_UNKNOWN;
        }
        return EvaluatorEntity.LABEL_UNKNOWN;
    }

    private Map<String, Object> calls(int generation, int judge) {
        Map<String, Object> calls = new LinkedHashMap<>();
        calls.put("generation", generation);
        calls.put("judge", judge);
        calls.put("external", generation + judge);
        return calls;
    }

    private Map<String, Object> usage(int generationCalls, int judgeCalls,
                                      Integer generationPrompt, Integer generationCompletion,
                                      Integer judgePrompt, Integer judgeCompletion) {
        Map<String, Object> usage = new LinkedHashMap<>();
        boolean external = generationCalls + judgeCalls > 0;
        boolean generationKnown = generationCalls == 0
                || (generationPrompt != null && generationCompletion != null);
        boolean judgeKnown = judgeCalls == 0
                || (judgePrompt != null && judgeCompletion != null);
        Integer prompt = plus(generationPrompt, judgePrompt);
        Integer completion = plus(generationCompletion, judgeCompletion);
        boolean known = external && generationKnown && judgeKnown && prompt != null && completion != null;
        usage.put("known", known);
        usage.put("promptTokens", prompt);
        usage.put("completionTokens", completion);
        usage.put("totalTokens", known ? prompt + completion : null);
        return usage;
    }

    private int orderOf(DatasetRunItemEntity item) {
        Map<String, Object> snapshot = parseMap(item.getSampleSnapshotJson());
        Integer order = snapshot == null ? null : asInteger(snapshot.get("itemOrder"));
        return order == null ? Integer.MAX_VALUE : order;
    }

    private Object parseJson(String json) {
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("dataset run json parse failed: {}", e.getMessage());
            return null;
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("dataset run json parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("dataset run json serialization failed", e);
            return "{\"status\":\"ERROR\",\"reasonCode\":\"SERIALIZATION_FAILED\"}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer asInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private Long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : null;
    }

    private Integer plus(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a + b;
    }

    private String storeText(String text, int maxChars) {
        if (text == null || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, Math.min(1000, maxChars));
    }

    private String truncate(String text, int maxChars) {
        if (text == null) {
            return null;
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }
}
