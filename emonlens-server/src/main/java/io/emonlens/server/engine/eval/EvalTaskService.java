package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.Sha256Utils;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvalAttemptEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvalTaskEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.mapper.EvalAttemptMapper;
import io.emonlens.server.storage.mapper.EvalTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Run/Task/Attempt 事实源首切片。
 *
 * <p>一个样本 × 一个评估器 = 一条 {@code et_eval_task}；一次任务执行 = 一条
 * {@code et_eval_attempt}。任务终态只写一次：迟到的外部调用结果不能覆盖 CANCELLED /
 * SKIPPED / 已完成的结论。任务行不保存候选正文，正文仍由内容权限控制。</p>
 *
 * <p>账本写入是评估结论的旁路证据，不阻塞既有 dataset run：写入失败会记录 ERROR 日志，
 * 但不会把已经得到的判定改写成 ERROR。查询侧用 attempt/task 缺失暴露证据不完整。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvalTaskService {

    private static final int MAX_PAGE_SIZE = 200;

    private final EvalTaskMapper evalTaskMapper;
    private final EvalAttemptMapper evalAttemptMapper;
    private final ObjectMapper objectMapper;
    private final PlatformTransactionManager transactionManager;

    // ==================== 物化与续跑复制 ====================

    /** 受理 run 后按“样本 × 评估器”写 PENDING 任务。调用方负责在同一事务内提交。 */
    public void materialize(DatasetRunEntity run, List<DatasetRunItemEntity> runItems,
                            List<Map<String, Object>> evaluatorSnapshots) {
        if (run == null || runItems == null || runItems.isEmpty()
                || evaluatorSnapshots == null || evaluatorSnapshots.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        int itemOrder = 0;
        for (DatasetRunItemEntity item : runItems) {
            int evaluatorOrder = 0;
            for (Map<String, Object> snapshot : evaluatorSnapshots) {
                EvalTaskEntity task = baseTask(run, item, snapshot, itemOrder, evaluatorOrder, now);
                evalTaskMapper.insert(task);
                evaluatorOrder++;
            }
            itemOrder++;
        }
    }

    /**
     * 续跑/重试：已执行样本的任务与 attempt 原样复制；待执行样本（resetRunItemIds）
     * 重置为 PENDING。源 run 的任务行不被改写。
     */
    public void copyForAttempt(String sourceRunId, DatasetRunEntity targetRun,
                               List<DatasetRunItemEntity> targetItems,
                               List<Map<String, Object>> evaluatorSnapshots,
                               Set<String> resetRunItemIds) {
        if (targetRun == null || targetItems == null || targetItems.isEmpty()
                || evaluatorSnapshots == null || evaluatorSnapshots.isEmpty()) {
            return;
        }
        List<EvalTaskEntity> sourceTasks = evalTaskMapper.selectList(
                Wrappers.<EvalTaskEntity>lambdaQuery().eq(EvalTaskEntity::getRunId, sourceRunId));
        Map<String, EvalTaskEntity> sourceByKey = new LinkedHashMap<>();
        for (EvalTaskEntity sourceTask : sourceTasks) {
            sourceByKey.put(taskKey(sourceTask.getItemId(), sourceTask.getEvaluatorId()), sourceTask);
        }
        Set<String> reset = resetRunItemIds == null ? Set.of() : resetRunItemIds;
        LocalDateTime now = LocalDateTime.now();
        int itemOrder = 0;
        for (DatasetRunItemEntity item : targetItems) {
            int evaluatorOrder = 0;
            for (Map<String, Object> snapshot : evaluatorSnapshots) {
                EvalTaskEntity task = baseTask(targetRun, item, snapshot, itemOrder, evaluatorOrder, now);
                EvalTaskEntity sourceTask = sourceByKey.get(
                        taskKey(item.getItemId(), asString(snapshot.get("id"))));
                boolean copyEvidence = false;
                if (reset.contains(item.getId())) {
                    // 待执行样本：旧 run 的部分任务不复制，避免把“上一条链路”的证据挂到新 attempt。
                    task.setExecutionStatus(EvalTaskEntity.STATUS_PENDING);
                } else if (sourceTask == null) {
                    // 历史 run 没有任务级证据：保留样本级结论，但明确标 UNKNOWN，不伪造 PASS/FAIL。
                    task.setExecutionStatus(EvalTaskEntity.STATUS_UNKNOWN);
                    task.setVerdict(EvaluatorEntity.LABEL_UNKNOWN);
                    task.setReasonCode("LEGACY_TASK_MISSING");
                    task.setReason("历史 run 没有任务级证据，仅保留样本级结果");
                    task.setFinishedAt(now);
                } else if (EvalTaskEntity.STATUS_PENDING.equals(sourceTask.getExecutionStatus())
                        || EvalTaskEntity.STATUS_RUNNING.equals(sourceTask.getExecutionStatus())) {
                    task.setExecutionStatus(EvalTaskEntity.STATUS_SKIPPED);
                    task.setReasonCode("SOURCE_TASK_NOT_TERMINAL");
                    task.setReason("源 run 中断时该任务未形成终态，保留样本级结果");
                    task.setFinishedAt(now);
                } else {
                    copyTask(task, sourceTask);
                    copyEvidence = true;
                }
                evalTaskMapper.insert(task);
                if (copyEvidence) {
                    int copied = copyAttempts(sourceTask, task);
                    task.setAttemptCount(copied);
                    evalTaskMapper.updateById(task);
                }
                evaluatorOrder++;
            }
            itemOrder++;
        }
    }

    private EvalTaskEntity baseTask(DatasetRunEntity run, DatasetRunItemEntity item,
                                    Map<String, Object> snapshot, int itemOrder, int evaluatorOrder,
                                    LocalDateTime now) {
        EvalTaskEntity task = new EvalTaskEntity();
        task.setWorkspaceId(run.getWorkspaceId());
        task.setRunId(run.getId());
        task.setRunItemId(item.getId());
        task.setDatasetId(run.getDatasetId());
        task.setItemId(item.getItemId());
        task.setItemOrder(itemOrder);
        task.setEvaluatorId(asString(snapshot.get("id")));
        task.setEvaluatorKey(asString(snapshot.get("key")));
        task.setEvaluatorType(asString(snapshot.get("type")));
        task.setEvaluatorVersion(asInt(snapshot.get("version"), 1));
        task.setEvaluatorOrder(evaluatorOrder);
        task.setEvaluatorFingerprint(asString(snapshot.get("fingerprint")));
        Object rawTemplate = snapshot.get("judgeTemplate");
        if (rawTemplate instanceof Map) {
            Map<?, ?> template = (Map<?, ?>) rawTemplate;
            task.setJudgeTemplateKey(template.get("key") == null
                    ? null : String.valueOf(template.get("key")));
            task.setJudgeTemplateVersion(template.get("version") instanceof Number
                    ? ((Number) template.get("version")).intValue() : null);
            task.setJudgeTemplateHash(template.get("hash") == null
                    ? null : String.valueOf(template.get("hash")));
        }
        task.setExecutionStatus(EvalTaskEntity.STATUS_PENDING);
        task.setAttemptCount(0);
        task.setCreatedAt(now);
        return task;
    }

    private void copyTask(EvalTaskEntity target, EvalTaskEntity source) {
        target.setExecutionStatus(source.getExecutionStatus());
        target.setVerdict(source.getVerdict());
        target.setScore(source.getScore());
        target.setReasonCode(source.getReasonCode());
        target.setReason(source.getReason());
        target.setModel(source.getModel());
        target.setPromptTokens(source.getPromptTokens());
        target.setCompletionTokens(source.getCompletionTokens());
        target.setTotalTokens(source.getTotalTokens());
        target.setLatencyMs(source.getLatencyMs());
        target.setResultJson(source.getResultJson());
        target.setEvidenceHash(source.getEvidenceHash());
        target.setJudgeTemplateKey(source.getJudgeTemplateKey());
        target.setJudgeTemplateVersion(source.getJudgeTemplateVersion());
        target.setJudgeTemplateHash(source.getJudgeTemplateHash());
        target.setCacheKey(source.getCacheKey());
        target.setStartedAt(source.getStartedAt());
        target.setFinishedAt(source.getFinishedAt());
    }

    private int copyAttempts(EvalTaskEntity sourceTask, EvalTaskEntity targetTask) {
        List<EvalAttemptEntity> sourceAttempts = evalAttemptMapper.selectList(
                Wrappers.<EvalAttemptEntity>lambdaQuery()
                        .eq(EvalAttemptEntity::getTaskId, sourceTask.getId())
                        .orderByAsc(EvalAttemptEntity::getCallSeq));
        for (EvalAttemptEntity source : sourceAttempts) {
            EvalAttemptEntity copy = new EvalAttemptEntity();
            copy.setWorkspaceId(targetTask.getWorkspaceId());
            copy.setRunId(targetTask.getRunId());
            copy.setTaskId(targetTask.getId());
            copy.setCallSeq(source.getCallSeq());
            copy.setOutcome(source.getOutcome());
            copy.setVerdict(source.getVerdict());
            copy.setModel(source.getModel());
            copy.setPromptTokens(source.getPromptTokens());
            copy.setCompletionTokens(source.getCompletionTokens());
            copy.setTotalTokens(source.getTotalTokens());
            copy.setLatencyMs(source.getLatencyMs());
            copy.setReasonCode(source.getReasonCode());
            copy.setDetailJson(source.getDetailJson());
            copy.setCreatedAt(source.getCreatedAt());
            evalAttemptMapper.insert(copy);
        }
        return sourceAttempts.size();
    }

    // ==================== 执行期状态推进 ====================

    /** 当前 run 的任务索引：runItemId|evaluatorId → task。历史 run 没有任务时返回空 Map。 */
    public Map<String, EvalTaskEntity> loadForRun(String runId) {
        Map<String, EvalTaskEntity> result = new LinkedHashMap<>();
        if (runId == null) {
            return result;
        }
        List<EvalTaskEntity> tasks = evalTaskMapper.selectList(
                Wrappers.<EvalTaskEntity>lambdaQuery()
                        .eq(EvalTaskEntity::getRunId, runId)
                        .orderByAsc(EvalTaskEntity::getItemOrder)
                        .orderByAsc(EvalTaskEntity::getEvaluatorOrder));
        for (EvalTaskEntity task : tasks) {
            result.put(taskKey(task.getRunItemId(), task.getEvaluatorId()), task);
        }
        return result;
    }

    public EvalTaskEntity reload(String taskId) {
        return taskId == null ? null : evalTaskMapper.selectById(taskId);
    }

    /**
     * 样本开始执行前认领该样本的全部任务：PENDING→RUNNING。这样取消只收掉尚未开始的样本，
     * 已开始生成/评审的样本仍能写入迟到结果；若全部任务已被取消则返回 false，调用方应跳过该样本。
     * deferredTypes 中的评估器类型不认领（SDK 任务由外部回写，保持 PENDING 直到结果到达）。
     */
    public boolean beginItem(Map<String, EvalTaskEntity> tasks, String runItemId,
                             Set<String> deferredTypes) {
        if (tasks == null || tasks.isEmpty() || runItemId == null) {
            return true;
        }
        Set<String> deferred = deferredTypes == null ? Set.of() : deferredTypes;
        int total = 0;
        int claimed = 0;
        for (EvalTaskEntity task : tasks.values()) {
            if (!runItemId.equals(task.getRunItemId())) {
                continue;
            }
            if (deferred.contains(task.getEvaluatorType())) {
                continue;
            }
            total++;
            if (EvalTaskEntity.STATUS_RUNNING.equals(task.getExecutionStatus())) {
                claimed++;
                continue;
            }
            if (!EvalTaskEntity.STATUS_PENDING.equals(task.getExecutionStatus())) {
                continue;
            }
            try {
                LocalDateTime now = LocalDateTime.now();
                int updated = evalTaskMapper.update(null, Wrappers.<EvalTaskEntity>lambdaUpdate()
                        .eq(EvalTaskEntity::getId, task.getId())
                        .eq(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING)
                        .set(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_RUNNING)
                        .set(EvalTaskEntity::getStartedAt, now));
                if (updated > 0) {
                    task.setExecutionStatus(EvalTaskEntity.STATUS_RUNNING);
                    task.setStartedAt(now);
                    claimed++;
                }
            } catch (RuntimeException e) {
                log.error("eval task item claim failed, task={}", task.getId(), e);
                claimed++;
            }
        }
        return total == 0 || claimed > 0;
    }

    /** PENDING → RUNNING。返回 false 表示任务已被取消/收敛，调用方不应再发起外部调用。 */
    public boolean start(EvalTaskEntity task) {
        if (task == null) {
            return true;
        }
        if (EvalTaskEntity.STATUS_RUNNING.equals(task.getExecutionStatus())) {
            return true;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            int updated = evalTaskMapper.update(null, Wrappers.<EvalTaskEntity>lambdaUpdate()
                    .eq(EvalTaskEntity::getId, task.getId())
                    .eq(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING)
                    .set(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_RUNNING)
                    .set(EvalTaskEntity::getStartedAt, now));
            if (updated > 0) {
                task.setExecutionStatus(EvalTaskEntity.STATUS_RUNNING);
                task.setStartedAt(now);
                return true;
            }
            return false;
        } catch (RuntimeException e) {
            // 账本故障不应让评估器停止；complete 的条件更新仍会防止覆盖终态。
            log.error("eval task start failed, task={}", task.getId(), e);
            return true;
        }
    }

    /** 写入评估器判定与 attempt；任务行与 attempt 在同一事务内提交。 */
    public void complete(EvalTaskEntity task, EvalResultEntity result, int latencyMs) {
        if (task == null || result == null) {
            return;
        }
        String rawLabel = normalizeRawLabel(result.getLabel());
        String label = normalizeLabel(rawLabel);
        String status = statusOf(label);
        String reasonCode = result.getReasonCode() == null || result.getReasonCode().isBlank()
                ? defaultReasonCode(rawLabel) : result.getReasonCode();
        int callSeq = nextCallSeq(task);
        Map<String, Object> resultJson = taskResult(rawLabel, label, reasonCode, result, latencyMs);
        String json = toJson(resultJson);
        LocalDateTime now = LocalDateTime.now();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                int updated = evalTaskMapper.update(null, Wrappers.<EvalTaskEntity>lambdaUpdate()
                        .eq(EvalTaskEntity::getId, task.getId())
                        .eq(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_RUNNING)
                        .set(EvalTaskEntity::getExecutionStatus, status)
                        .set(EvalTaskEntity::getVerdict, label)
                        .set(EvalTaskEntity::getScore, result.getScore())
                        .set(EvalTaskEntity::getReasonCode, truncate(reasonCode, 64))
                        .set(EvalTaskEntity::getReason, truncate(result.getReason(), 1000))
                        .set(EvalTaskEntity::getModel, truncate(result.getJudgeModel(), 128))
                        .set(EvalTaskEntity::getPromptTokens, result.getPromptTokens())
                        .set(EvalTaskEntity::getCompletionTokens, result.getCompletionTokens())
                        .set(EvalTaskEntity::getTotalTokens, totalTokens(result))
                        .set(EvalTaskEntity::getLatencyMs, latencyMs)
                        .set(EvalTaskEntity::getAttemptCount, callSeq)
                        .set(EvalTaskEntity::getCacheKey, result.getCacheKey())
                        .set(EvalTaskEntity::getResultJson, json)
                        .set(EvalTaskEntity::getEvidenceHash, Sha256Utils.hash(json))
                        .set(EvalTaskEntity::getFinishedAt, now));
                if (updated == 0) {
                    throw new TaskStateChangedException();
                }
                insertAttempt(task, callSeq, attemptOutcome(status), rawLabel, result, latencyMs,
                        reasonCode, resultJson);
            });
            task.setExecutionStatus(status);
            task.setVerdict(label);
            task.setAttemptCount(callSeq);
            task.setCacheKey(result.getCacheKey());
            task.setFinishedAt(now);
        } catch (TaskStateChangedException e) {
            log.warn("eval task not running, result ignored, task={} status={}",
                    task.getId(), task.getExecutionStatus());
        } catch (RuntimeException e) {
            log.error("eval task complete failed, task={}", task.getId(), e);
        }
    }

    /** 执行异常/预算不足：任务标记 FAILED，并原子记一条失败 attempt。 */
    public void fail(EvalTaskEntity task, String reasonCode, String reason, int latencyMs) {
        if (task == null) {
            return;
        }
        int callSeq = nextCallSeq(task);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("reasonCode", reasonCode);
        detail.put("reason", truncate(reason, 2000));
        detail.put("latencyMs", latencyMs);
        String json = toJson(detail);
        LocalDateTime now = LocalDateTime.now();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                int updated = evalTaskMapper.update(null, Wrappers.<EvalTaskEntity>lambdaUpdate()
                        .eq(EvalTaskEntity::getId, task.getId())
                        .eq(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_RUNNING)
                        .set(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_FAILED)
                        .set(EvalTaskEntity::getVerdict, EvaluatorEntity.LABEL_ERROR)
                        .set(EvalTaskEntity::getReasonCode, truncate(reasonCode, 64))
                        .set(EvalTaskEntity::getReason, truncate(reason, 1000))
                        .set(EvalTaskEntity::getLatencyMs, latencyMs)
                        .set(EvalTaskEntity::getAttemptCount, callSeq)
                        .set(EvalTaskEntity::getResultJson, json)
                        .set(EvalTaskEntity::getFinishedAt, now));
                if (updated == 0) {
                    throw new TaskStateChangedException();
                }
                EvalAttemptEntity attempt = new EvalAttemptEntity();
                attempt.setWorkspaceId(task.getWorkspaceId());
                attempt.setRunId(task.getRunId());
                attempt.setTaskId(task.getId());
                attempt.setCallSeq(callSeq);
                attempt.setOutcome(EvalAttemptEntity.OUTCOME_FAILED);
                attempt.setVerdict(EvaluatorEntity.LABEL_ERROR);
                attempt.setLatencyMs(latencyMs);
                attempt.setReasonCode(truncate(reasonCode, 64));
                attempt.setDetailJson(json);
                attempt.setCreatedAt(now);
                evalAttemptMapper.insert(attempt);
            });
            task.setExecutionStatus(EvalTaskEntity.STATUS_FAILED);
            task.setVerdict(EvaluatorEntity.LABEL_ERROR);
            task.setAttemptCount(callSeq);
            task.setFinishedAt(now);
        } catch (TaskStateChangedException e) {
            // 任务已被取消/收敛：保留既有终态。
        } catch (RuntimeException e) {
            log.error("eval task fail failed, task={}", task.getId(), e);
        }
    }

    /**
     * 样本级早退（快照缺失、trace 未完成、内容不可读等）：把该样本尚未形成终态的任务
     * 收敛到与 item 相同的语义，避免任务永久 PENDING。
     */
    public void finishItemTasks(Map<String, EvalTaskEntity> tasks, String runItemId, String label,
                                String reasonCode, String reason) {
        if (tasks == null || tasks.isEmpty() || runItemId == null) {
            return;
        }
        String normalized = normalizeLabel(label);
        String status = statusOf(normalized);
        String verdict = normalized;
        updateItemTasks(tasks, runItemId, status, verdict, reasonCode, reason);
    }

    /** 预算耗尽：当前样本剩余任务直接 SKIPPED，不伪装成 UNKNOWN。 */
    public void skipRemainingItemTasks(Map<String, EvalTaskEntity> tasks, String runItemId,
                                       String reasonCode, String reason) {
        updateItemTasks(tasks, runItemId, EvalTaskEntity.STATUS_SKIPPED, null, reasonCode, reason);
    }

    private void updateItemTasks(Map<String, EvalTaskEntity> tasks, String runItemId, String status,
                                 String verdict, String reasonCode, String reason) {
        for (EvalTaskEntity task : tasks.values()) {
            if (!runItemId.equals(task.getRunItemId())) {
                continue;
            }
            if (isTerminal(task.getExecutionStatus())) {
                continue;
            }
            try {
                LocalDateTime now = LocalDateTime.now();
                int updated = evalTaskMapper.update(null, Wrappers.<EvalTaskEntity>lambdaUpdate()
                        .eq(EvalTaskEntity::getId, task.getId())
                        .in(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING,
                                EvalTaskEntity.STATUS_RUNNING)
                        .set(EvalTaskEntity::getExecutionStatus, status)
                        .set(EvalTaskEntity::getVerdict, verdict)
                        .set(EvalTaskEntity::getReasonCode, truncate(reasonCode, 64))
                        .set(EvalTaskEntity::getReason, truncate(reason, 1000))
                        .set(EvalTaskEntity::getFinishedAt, now));
                if (updated > 0) {
                    task.setExecutionStatus(status);
                    task.setVerdict(verdict);
                    task.setFinishedAt(now);
                }
            } catch (RuntimeException e) {
                log.error("eval task settle failed, task={}", task.getId(), e);
            }
        }
    }

    /** 取消 run：包括尚未回写的 SDK 任务（用户显式取消优先于一切外部等待）。 */
    public void cancelOpenTasks(String runId, String reasonCode, String reason) {
        updateOpenTasks(runId, EvalTaskEntity.STATUS_CANCELLED, List.of(EvalTaskEntity.STATUS_PENDING),
                reasonCode, reason, true);
    }

    /** 中断/租约过期：worker 已不可信，PENDING 与 RUNNING 都收敛，禁止迟到结果覆盖。 */
    public void interruptOpenTasks(String runId, String reasonCode, String reason) {
        updateOpenTasks(runId, EvalTaskEntity.STATUS_SKIPPED,
                List.of(EvalTaskEntity.STATUS_PENDING, EvalTaskEntity.STATUS_RUNNING),
                reasonCode, reason, false);
    }

    public void skipPendingTasks(String runId, String reasonCode, String reason) {
        updateOpenTasks(runId, EvalTaskEntity.STATUS_SKIPPED, List.of(EvalTaskEntity.STATUS_PENDING),
                reasonCode, reason, false);
    }

    /** run 正常收口时兜底：仍未形成终态的服务端任务标 SKIPPED；SDK 任务留给外部回写或宽限清理。 */
    public void finalizeOpenTasks(String runId) {
        updateOpenTasks(runId, EvalTaskEntity.STATUS_SKIPPED,
                List.of(EvalTaskEntity.STATUS_PENDING, EvalTaskEntity.STATUS_RUNNING),
                "RUN_FINISHED_WITHOUT_RESULT", "run 已收口，该任务没有形成终态", false);
    }

    /** 终端 run 上超过宽限期仍未收敛的任务；供 worker 轮询兜底，避免 RUNNING 永久悬挂。 */
    public List<String> staleOpenRunIds(LocalDateTime cutoff, int limit) {
        int size = Math.max(1, Math.min(500, limit));
        List<EvalTaskEntity> open = evalTaskMapper.selectList(Wrappers.<EvalTaskEntity>lambdaQuery()
                .in(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING,
                        EvalTaskEntity.STATUS_RUNNING)
                // PENDING 看创建时间；RUNNING 看开始时间，避免长 run 取消时误收敛刚开始的任务。
                .apply("((execution_status = 'PENDING' AND created_at < {0})"
                        + " OR (execution_status = 'RUNNING'"
                        + " AND (started_at IS NULL OR started_at < {0})))", cutoff)
                // SDK 任务的结果在 run 终态后仍允许回写，由 SDK 专属宽限清理收敛
                .and(w -> w.isNull(EvalTaskEntity::getEvaluatorType)
                        .or().ne(EvalTaskEntity::getEvaluatorType, EvaluatorEntity.TYPE_SDK))
                .orderByAsc(EvalTaskEntity::getCreatedAt)
                .last("LIMIT " + size));
        return open.stream().map(EvalTaskEntity::getRunId).distinct()
                .collect(java.util.stream.Collectors.toList());
    }

    /** 终端 run 的遗留任务：取消的 run 未开始任务保持 CANCELLED，执行中任务按中断记 SKIPPED。 */
    public void settleTerminalRunTasks(String runId, String runStatus) {
        if (DatasetRunEntity.STATUS_CANCELLED.equals(runStatus)) {
            updateOpenTasks(runId, EvalTaskEntity.STATUS_CANCELLED,
                    List.of(EvalTaskEntity.STATUS_PENDING), "CANCELLED", "运行已取消，任务未执行", true);
            updateOpenTasks(runId, EvalTaskEntity.STATUS_SKIPPED,
                    List.of(EvalTaskEntity.STATUS_RUNNING), "RUN_CANCELLED_TASK_ORPHANED",
                    "运行取消后 worker 未回写终态，任务按中断收敛", true);
        } else {
            updateOpenTasks(runId, EvalTaskEntity.STATUS_SKIPPED,
                    List.of(EvalTaskEntity.STATUS_PENDING, EvalTaskEntity.STATUS_RUNNING),
                    "RUN_TERMINAL_TASK_ORPHANED", "run 已结束但任务未形成终态，按中断收敛", false);
        }
    }

    private void updateOpenTasks(String runId, String status, List<String> fromStatuses,
                                 String reasonCode, String reason) {
        updateOpenTasks(runId, status, fromStatuses, reasonCode, reason, false);
    }

    /**
     * @param includeSdk false 时跳过 SDK 任务：它们的结果由外部回写，只有显式取消和
     *                   SDK 宽限清理可以收敛，服务端执行生命周期不得扫掉。
     */
    private void updateOpenTasks(String runId, String status, List<String> fromStatuses,
                                 String reasonCode, String reason, boolean includeSdk) {
        if (runId == null) {
            return;
        }
        try {
            var wrapper = Wrappers.<EvalTaskEntity>lambdaUpdate()
                    .eq(EvalTaskEntity::getRunId, runId)
                    .in(EvalTaskEntity::getExecutionStatus, fromStatuses);
            if (!includeSdk) {
                wrapper.and(w -> w.isNull(EvalTaskEntity::getEvaluatorType)
                        .or().ne(EvalTaskEntity::getEvaluatorType, EvaluatorEntity.TYPE_SDK));
            }
            evalTaskMapper.update(null, wrapper
                    .set(EvalTaskEntity::getExecutionStatus, status)
                    .set(EvalTaskEntity::getReasonCode, truncate(reasonCode, 64))
                    .set(EvalTaskEntity::getReason, truncate(reason, 1000))
                    .set(EvalTaskEntity::getFinishedAt, LocalDateTime.now()));
        } catch (RuntimeException e) {
            log.error("eval task bulk settle failed, run={}", runId, e);
        }
    }

    // ==================== SDK 外部回写 ====================

    /** run 中仍未终态的 SDK 任务；run 收口后它们等待外部回写，直到宽限期被清理。 */
    public List<EvalTaskEntity> openSdkTasks(String runId) {
        if (runId == null) {
            return List.of();
        }
        return evalTaskMapper.selectList(Wrappers.<EvalTaskEntity>lambdaQuery()
                .eq(EvalTaskEntity::getRunId, runId)
                .eq(EvalTaskEntity::getEvaluatorType, EvaluatorEntity.TYPE_SDK)
                .in(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING,
                        EvalTaskEntity.STATUS_RUNNING)
                .orderByAsc(EvalTaskEntity::getItemOrder)
                .orderByAsc(EvalTaskEntity::getEvaluatorOrder));
    }

    public int countOpenSdkTasks(String runId) {
        if (runId == null) {
            return 0;
        }
        Long count = evalTaskMapper.selectCount(Wrappers.<EvalTaskEntity>lambdaQuery()
                .eq(EvalTaskEntity::getRunId, runId)
                .eq(EvalTaskEntity::getEvaluatorType, EvaluatorEntity.TYPE_SDK)
                .in(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING,
                        EvalTaskEntity.STATUS_RUNNING));
        return count == null ? 0 : count.intValue();
    }

    /**
     * SDK 结果回写：任务尚未被服务端认领（保持 PENDING），这里直接从 PENDING/RUNNING 落终态。
     * 终态不可覆盖——外部结果与迟到结果一样受"只写一次"约束。
     */
    public boolean completeExternal(EvalTaskEntity task, EvalResultEntity result, int latencyMs) {
        if (task == null || result == null) {
            return false;
        }
        String rawLabel = normalizeRawLabel(result.getLabel());
        String label = normalizeLabel(rawLabel);
        String status = statusOf(label);
        String reasonCode = result.getReasonCode() == null || result.getReasonCode().isBlank()
                ? defaultReasonCode(rawLabel) : result.getReasonCode();
        int callSeq = nextCallSeq(task);
        Map<String, Object> resultJson = taskResult(rawLabel, label, reasonCode, result, latencyMs);
        resultJson.put("origin", "SDK");
        String json = toJson(resultJson);
        LocalDateTime now = LocalDateTime.now();
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                int updated = evalTaskMapper.update(null, Wrappers.<EvalTaskEntity>lambdaUpdate()
                        .eq(EvalTaskEntity::getId, task.getId())
                        .in(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING,
                                EvalTaskEntity.STATUS_RUNNING)
                        .set(EvalTaskEntity::getExecutionStatus, status)
                        .set(EvalTaskEntity::getVerdict, label)
                        .set(EvalTaskEntity::getScore, result.getScore())
                        .set(EvalTaskEntity::getReasonCode, truncate(reasonCode, 64))
                        .set(EvalTaskEntity::getReason, truncate(result.getReason(), 1000))
                        .set(EvalTaskEntity::getModel, truncate(result.getJudgeModel(), 128))
                        .set(EvalTaskEntity::getPromptTokens, result.getPromptTokens())
                        .set(EvalTaskEntity::getCompletionTokens, result.getCompletionTokens())
                        .set(EvalTaskEntity::getTotalTokens, totalTokens(result))
                        .set(EvalTaskEntity::getLatencyMs, latencyMs)
                        .set(EvalTaskEntity::getAttemptCount, callSeq)
                        .set(EvalTaskEntity::getResultJson, json)
                        .set(EvalTaskEntity::getEvidenceHash, Sha256Utils.hash(json))
                        .set(EvalTaskEntity::getStartedAt, task.getStartedAt() == null
                                ? now : task.getStartedAt())
                        .set(EvalTaskEntity::getFinishedAt, now));
                if (updated == 0) {
                    throw new TaskStateChangedException();
                }
                insertAttempt(task, callSeq, attemptOutcome(status), rawLabel, result, latencyMs,
                        reasonCode, resultJson);
            });
            task.setExecutionStatus(status);
            task.setVerdict(label);
            task.setScore(result.getScore());
            task.setReasonCode(truncate(reasonCode, 64));
            task.setReason(truncate(result.getReason(), 1000));
            task.setModel(truncate(result.getJudgeModel(), 128));
            task.setResultJson(json);
            task.setEvidenceHash(Sha256Utils.hash(json));
            task.setAttemptCount(callSeq);
            task.setStartedAt(task.getStartedAt() == null ? now : task.getStartedAt());
            task.setFinishedAt(now);
            return true;
        } catch (TaskStateChangedException e) {
            return false;
        } catch (RuntimeException e) {
            log.error("eval task external complete failed, task={}", task.getId(), e);
            return false;
        }
    }

    /**
     * SDK 宽限清理：终端 run 上超过宽限期仍未回写的 SDK 任务标 SKIPPED。
     * 返回被清理的任务（调用方负责按 item 重聚合并刷新 run 计数）。
     */
    public List<EvalTaskEntity> settleExpiredSdkTasks(LocalDateTime cutoff, int limit) {
        int size = Math.max(1, Math.min(500, limit));
        List<EvalTaskEntity> expired = evalTaskMapper.selectList(Wrappers.<EvalTaskEntity>lambdaQuery()
                .eq(EvalTaskEntity::getEvaluatorType, EvaluatorEntity.TYPE_SDK)
                .in(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING,
                        EvalTaskEntity.STATUS_RUNNING)
                .lt(EvalTaskEntity::getCreatedAt, cutoff)
                .orderByAsc(EvalTaskEntity::getCreatedAt)
                .last("LIMIT " + size));
        for (EvalTaskEntity task : expired) {
            int updated = evalTaskMapper.update(null, Wrappers.<EvalTaskEntity>lambdaUpdate()
                    .eq(EvalTaskEntity::getId, task.getId())
                    .in(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_PENDING,
                            EvalTaskEntity.STATUS_RUNNING)
                    .set(EvalTaskEntity::getExecutionStatus, EvalTaskEntity.STATUS_SKIPPED)
                    .set(EvalTaskEntity::getReasonCode, "SDK_RESULT_NOT_RECEIVED")
                    .set(EvalTaskEntity::getReason, "SDK 评测结果超过宽限期未回写")
                    .set(EvalTaskEntity::getFinishedAt, LocalDateTime.now()));
            if (updated > 0) {
                task.setExecutionStatus(EvalTaskEntity.STATUS_SKIPPED);
            }
        }
        return expired.stream()
                .filter(t -> EvalTaskEntity.STATUS_SKIPPED.equals(t.getExecutionStatus()))
                .collect(java.util.stream.Collectors.toList());
    }

    // ==================== 查询 ====================

    /** run 的任务分页 + 汇总；历史 run 没有任务行时 legacy=true。 */
    public Map<String, Object> list(String workspaceId, String datasetId, String runId,
                                    String status, String evaluatorKey,
                                    int pageNumber, int pageSize, boolean contentAllowed) {
        int current = Math.max(1, pageNumber);
        int size = Math.max(1, Math.min(MAX_PAGE_SIZE, pageSize));
        var wrapper = Wrappers.<EvalTaskEntity>lambdaQuery()
                .eq(EvalTaskEntity::getWorkspaceId, workspaceId)
                .eq(EvalTaskEntity::getDatasetId, datasetId)
                .eq(EvalTaskEntity::getRunId, runId)
                .eq(status != null && !status.isBlank(), EvalTaskEntity::getExecutionStatus,
                        status == null ? null : status.trim().toUpperCase())
                .eq(evaluatorKey != null && !evaluatorKey.isBlank(), EvalTaskEntity::getEvaluatorKey,
                        evaluatorKey == null ? null : evaluatorKey.trim())
                .orderByAsc(EvalTaskEntity::getItemOrder)
                .orderByAsc(EvalTaskEntity::getEvaluatorOrder)
                .orderByAsc(EvalTaskEntity::getId);
        Page<EvalTaskEntity> page = evalTaskMapper.selectPage(new Page<>(current, size), wrapper);
        List<EvalTaskEntity> all = evalTaskMapper.selectList(Wrappers.<EvalTaskEntity>lambdaQuery()
                .eq(EvalTaskEntity::getWorkspaceId, workspaceId)
                .eq(EvalTaskEntity::getDatasetId, datasetId)
                .eq(EvalTaskEntity::getRunId, runId));

        Map<String, Object> summary = summary(all);
        Set<String> evaluatorKeys = new LinkedHashSet<>();
        for (EvalTaskEntity task : all) {
            if (task.getEvaluatorKey() != null) {
                evaluatorKeys.add(task.getEvaluatorKey());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", runId);
        result.put("datasetId", datasetId);
        result.put("legacy", all.isEmpty());
        result.put("contentRestricted", !contentAllowed);
        result.put("summary", summary);
        result.put("evaluatorKeys", new ArrayList<>(evaluatorKeys));
        result.put("pageNumber", current);
        result.put("pageSize", size);
        result.put("total", page.getTotal());
        List<String> taskIds = page.getRecords().stream().map(EvalTaskEntity::getId)
                .collect(java.util.stream.Collectors.toList());
        Map<String, List<EvalAttemptEntity>> attemptsByTask = new LinkedHashMap<>();
        if (!taskIds.isEmpty()) {
            List<EvalAttemptEntity> attempts = evalAttemptMapper.selectList(
                    Wrappers.<EvalAttemptEntity>lambdaQuery()
                            .in(EvalAttemptEntity::getTaskId, taskIds)
                            .orderByAsc(EvalAttemptEntity::getCallSeq));
            for (EvalAttemptEntity attempt : attempts) {
                attemptsByTask.computeIfAbsent(attempt.getTaskId(), key -> new ArrayList<>()).add(attempt);
            }
        }
        result.put("records", page.getRecords().stream()
                .map(task -> toVO(task, attemptsByTask.getOrDefault(task.getId(), List.of()),
                        contentAllowed))
                .collect(java.util.stream.Collectors.toList()));
        return result;
    }

    private Map<String, Object> summary(List<EvalTaskEntity> tasks) {
        int pending = 0, running = 0, succeeded = 0, unknown = 0, failed = 0, skipped = 0, cancelled = 0;
        int pass = 0, fail = 0, unknownVerdict = 0, error = 0;
        for (EvalTaskEntity task : tasks) {
            String status = task.getExecutionStatus();
            if (EvalTaskEntity.STATUS_PENDING.equals(status)) pending++;
            else if (EvalTaskEntity.STATUS_RUNNING.equals(status)) running++;
            else if (EvalTaskEntity.STATUS_SUCCEEDED.equals(status)) succeeded++;
            else if (EvalTaskEntity.STATUS_UNKNOWN.equals(status)) unknown++;
            else if (EvalTaskEntity.STATUS_FAILED.equals(status)) failed++;
            else if (EvalTaskEntity.STATUS_SKIPPED.equals(status)) skipped++;
            else if (EvalTaskEntity.STATUS_CANCELLED.equals(status)) cancelled++;
            String verdict = task.getVerdict();
            if (EvaluatorEntity.LABEL_PASS.equals(verdict)) pass++;
            else if (EvaluatorEntity.LABEL_FAIL.equals(verdict)) fail++;
            else if (EvaluatorEntity.LABEL_UNKNOWN.equals(verdict)) unknownVerdict++;
            else if (EvaluatorEntity.LABEL_ERROR.equals(verdict)) error++;
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", tasks.size());
        summary.put("pending", pending);
        summary.put("running", running);
        summary.put("succeeded", succeeded);
        summary.put("unknown", unknown);
        summary.put("failed", failed);
        summary.put("skipped", skipped);
        summary.put("cancelled", cancelled);
        summary.put("pass", pass);
        summary.put("fail", fail);
        summary.put("unknownVerdict", unknownVerdict);
        summary.put("error", error);
        summary.put("open", pending + running);
        return summary;
    }

    private Map<String, Object> toVO(EvalTaskEntity task, List<EvalAttemptEntity> attempts,
                                     boolean contentAllowed) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", task.getId());
        vo.put("runId", task.getRunId());
        vo.put("runItemId", task.getRunItemId());
        vo.put("itemId", task.getItemId());
        vo.put("itemOrder", task.getItemOrder());
        vo.put("evaluatorId", task.getEvaluatorId());
        vo.put("evaluatorKey", task.getEvaluatorKey());
        vo.put("evaluatorType", task.getEvaluatorType());
        vo.put("evaluatorVersion", task.getEvaluatorVersion());
        vo.put("evaluatorFingerprint", task.getEvaluatorFingerprint());
        vo.put("status", task.getExecutionStatus());
        vo.put("verdict", task.getVerdict());
        vo.put("score", task.getScore());
        vo.put("reasonCode", task.getReasonCode());
        vo.put("reason", contentAllowed ? task.getReason() : null);
        vo.put("model", task.getModel());
        vo.put("promptTokens", task.getPromptTokens());
        vo.put("completionTokens", task.getCompletionTokens());
        vo.put("totalTokens", task.getTotalTokens());
        vo.put("latencyMs", task.getLatencyMs());
        vo.put("attemptCount", task.getAttemptCount());
        vo.put("attempts", attempts.stream().map(this::attemptVO).collect(java.util.stream.Collectors.toList()));
        vo.put("evidenceHash", task.getEvidenceHash());
        vo.put("judgeTemplateKey", task.getJudgeTemplateKey());
        vo.put("judgeTemplateVersion", task.getJudgeTemplateVersion());
        vo.put("judgeTemplateHash", task.getJudgeTemplateHash());
        vo.put("cacheKey", task.getCacheKey());
        vo.put("startedAt", task.getStartedAt());
        vo.put("finishedAt", task.getFinishedAt());
        vo.put("createdAt", task.getCreatedAt());
        if (contentAllowed) {
            Map<String, Object> parsed = parseMap(task.getResultJson());
            if (parsed != null) {
                if (parsed.get("criteria") != null) {
                    vo.put("criteria", parsed.get("criteria"));
                }
                if (parsed.get("claims") != null) {
                    vo.put("claims", parsed.get("claims"));
                }
                if (parsed.get("claimAggregate") != null) {
                    vo.put("claimAggregate", parsed.get("claimAggregate"));
                }
                if (parsed.get("judgeTemplate") != null) {
                    vo.put("judgeTemplate", parsed.get("judgeTemplate"));
                }
                if (parsed.get("judgeMode") != null) {
                    vo.put("judgeMode", parsed.get("judgeMode"));
                }
                if (parsed.get("preset") != null) {
                    vo.put("preset", parsed.get("preset"));
                }
            }
        }
        return vo;
    }

    private Map<String, Object> attemptVO(EvalAttemptEntity attempt) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", attempt.getId());
        vo.put("callSeq", attempt.getCallSeq());
        vo.put("outcome", attempt.getOutcome());
        vo.put("verdict", attempt.getVerdict());
        vo.put("model", attempt.getModel());
        vo.put("promptTokens", attempt.getPromptTokens());
        vo.put("completionTokens", attempt.getCompletionTokens());
        vo.put("totalTokens", attempt.getTotalTokens());
        vo.put("latencyMs", attempt.getLatencyMs());
        vo.put("reasonCode", attempt.getReasonCode());
        vo.put("createdAt", attempt.getCreatedAt());
        return vo;
    }

    public void deleteByRunIds(List<String> runIds) {
        if (runIds == null || runIds.isEmpty()) {
            return;
        }
        evalAttemptMapper.delete(Wrappers.<EvalAttemptEntity>lambdaQuery()
                .in(EvalAttemptEntity::getRunId, runIds));
        evalTaskMapper.delete(Wrappers.<EvalTaskEntity>lambdaQuery()
                .in(EvalTaskEntity::getRunId, runIds));
    }

    // ==================== 内部工具 ====================

    private void insertAttempt(EvalTaskEntity task, int callSeq, String outcome, String verdict,
                               EvalResultEntity result, int latencyMs, String reasonCode,
                               Map<String, Object> detail) {
        EvalAttemptEntity attempt = new EvalAttemptEntity();
        attempt.setWorkspaceId(task.getWorkspaceId());
        attempt.setRunId(task.getRunId());
        attempt.setTaskId(task.getId());
        attempt.setCallSeq(callSeq);
        attempt.setOutcome(outcome);
        attempt.setVerdict(verdict);
        attempt.setModel(truncate(result.getJudgeModel(), 128));
        attempt.setPromptTokens(result.getPromptTokens());
        attempt.setCompletionTokens(result.getCompletionTokens());
        attempt.setTotalTokens(totalTokens(result));
        attempt.setLatencyMs(latencyMs);
        attempt.setReasonCode(truncate(reasonCode, 64));
        attempt.setDetailJson(toJson(detail));
        attempt.setCreatedAt(LocalDateTime.now());
        evalAttemptMapper.insert(attempt);
    }

    private Map<String, Object> taskResult(String rawLabel, String label, String reasonCode,
                                           EvalResultEntity result, int latencyMs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("label", label);
        detail.put("rawLabel", rawLabel);
        detail.put("score", result.getScore());
        detail.put("reasonCode", reasonCode);
        detail.put("reason", truncate(result.getReason(), 2000));
        detail.put("judgeModel", result.getJudgeModel());
        detail.put("judgeMode", result.getJudgeMode());
        detail.put("preset", result.getPreset());
        detail.put("judgeCalled", result.getJudgeCalled());
        detail.put("cacheHit", Boolean.TRUE.equals(result.getCacheHit()));
        detail.put("promptTokens", result.getPromptTokens());
        detail.put("completionTokens", result.getCompletionTokens());
        detail.put("latencyMs", latencyMs);
        if (result.getJudgeTemplateKey() != null) {
            Map<String, Object> template = new LinkedHashMap<>();
            template.put("key", result.getJudgeTemplateKey());
            template.put("version", result.getJudgeTemplateVersion());
            template.put("hash", result.getJudgeTemplateHash());
            detail.put("judgeTemplate", template);
        }
        if (result.getClaimsJson() != null && !result.getClaimsJson().isBlank()) {
            try {
                detail.put("claims", objectMapper.readTree(result.getClaimsJson()));
            } catch (Exception e) {
                log.debug("claim results not serializable for task: {}", e.getMessage());
            }
        }
        if (result.getClaimAggregateJson() != null && !result.getClaimAggregateJson().isBlank()) {
            try {
                detail.put("claimAggregate", objectMapper.readTree(result.getClaimAggregateJson()));
            } catch (Exception e) {
                log.debug("claim aggregate not serializable for task: {}", e.getMessage());
            }
        }
        if (result.getCriterionResultsJson() != null && !result.getCriterionResultsJson().isBlank()) {
            try {
                detail.put("criteria", objectMapper.readTree(result.getCriterionResultsJson()));
            } catch (Exception e) {
                log.debug("criterion results not serializable for task: {}", e.getMessage());
            }
        }
        return detail;
    }

    private int nextCallSeq(EvalTaskEntity task) {
        return (task.getAttemptCount() == null ? 0 : task.getAttemptCount()) + 1;
    }

    private Integer totalTokens(EvalResultEntity result) {
        if (result.getPromptTokens() == null && result.getCompletionTokens() == null) {
            return null;
        }
        return (result.getPromptTokens() == null ? 0 : result.getPromptTokens())
                + (result.getCompletionTokens() == null ? 0 : result.getCompletionTokens());
    }

    private String defaultReasonCode(String label) {
        if (EvaluatorEntity.LABEL_PASS.equals(label)) {
            return "PASS";
        }
        if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
            return "FAIL";
        }
        if (EvaluatorEntity.LABEL_UNKNOWN.equals(label)) {
            return "UNKNOWN";
        }
        if (EvaluatorEntity.LABEL_NOT_APPLICABLE.equals(label)) {
            return "NOT_APPLICABLE";
        }
        if (EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)) {
            return "EVAL_UNAVAILABLE";
        }
        return label;
    }

    private String statusOf(String label) {
        if (EvaluatorEntity.LABEL_PASS.equals(label) || EvaluatorEntity.LABEL_FAIL.equals(label)) {
            return EvalTaskEntity.STATUS_SUCCEEDED;
        }
        if (EvaluatorEntity.LABEL_UNKNOWN.equals(label)) {
            return EvalTaskEntity.STATUS_UNKNOWN;
        }
        return EvalTaskEntity.STATUS_FAILED;
    }

    private String attemptOutcome(String status) {
        if (EvalTaskEntity.STATUS_SUCCEEDED.equals(status)) {
            return EvalAttemptEntity.OUTCOME_SUCCEEDED;
        }
        if (EvalTaskEntity.STATUS_UNKNOWN.equals(status)) {
            return EvalAttemptEntity.OUTCOME_UNKNOWN;
        }
        return EvalAttemptEntity.OUTCOME_FAILED;
    }

    private boolean isTerminal(String status) {
        return status != null && !EvalTaskEntity.STATUS_PENDING.equals(status)
                && !EvalTaskEntity.STATUS_RUNNING.equals(status);
    }

    /** 与 DatasetRunWorker 的 item 级归一口径一致：EVAL_UNAVAILABLE / N/A 都归为 UNKNOWN。 */
    private String normalizeLabel(String label) {
        String raw = normalizeRawLabel(label);
        if (EvaluatorEntity.LABEL_PASS.equals(raw) || EvaluatorEntity.LABEL_FAIL.equals(raw)
                || EvaluatorEntity.LABEL_UNKNOWN.equals(raw) || EvaluatorEntity.LABEL_ERROR.equals(raw)) {
            return raw;
        }
        return EvaluatorEntity.LABEL_UNKNOWN;
    }

    /** 保留评估器原始标签用于 attempt 审计；未知标签按 UNKNOWN 处理。 */
    private String normalizeRawLabel(String label) {
        if (label == null || label.isBlank()) {
            return EvaluatorEntity.LABEL_ERROR;
        }
        String raw = label.trim().toUpperCase();
        if (EvaluatorEntity.LABEL_PASS.equals(raw) || EvaluatorEntity.LABEL_FAIL.equals(raw)
                || EvaluatorEntity.LABEL_UNKNOWN.equals(raw) || EvaluatorEntity.LABEL_ERROR.equals(raw)
                || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(raw)
                || EvaluatorEntity.LABEL_NOT_APPLICABLE.equals(raw)) {
            return raw;
        }
        return EvaluatorEntity.LABEL_UNKNOWN;
    }

    private String taskKey(String runItemId, String evaluatorId) {
        return runItemId + "|" + evaluatorId;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("eval task json serialize failed: {}", e.getMessage());
            return "{}";
        }
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

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private int asInt(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static final class TaskStateChangedException extends RuntimeException {
    }
}
