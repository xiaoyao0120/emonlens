package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetRevisionEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.DatasetRunSlotEntity;
import io.emonlens.server.storage.entity.EvalSuiteEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.DatasetRunSlotMapper;
import io.emonlens.server.storage.mapper.ModelPriceMapper;
import io.emonlens.server.storage.mapper.EvaluatorMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * M2/M3：数据集运行的受理、幂等、快照落库与状态汇总。
 * 单实例内串行受理；不回读数据集/评估器以外的可变配置。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetRunService {

    private final DatasetMapper datasetMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final DatasetRunMapper datasetRunMapper;
    private final DatasetRunItemMapper datasetRunItemMapper;
    private final DatasetRunSlotMapper datasetRunSlotMapper;
    private final DomainPackService domainPackService;
    private final ModelPriceMapper modelPriceMapper;
    private final EvaluatorMapper evaluatorMapper;
    private final DatasetRevisionService revisionService;
    private final DatasetRunSnapshotService snapshotService;
    private final EvalSuiteService evalSuiteService;
    private final HttpLlmClient llmClient;
    private final DatasetRunCredentialStore credentialStore;
    private final EvalTaskService evalTaskService;
    private final io.emonlens.server.engine.prompt.PromptService promptService;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;
    private final PlatformTransactionManager transactionManager;

    private final Object acceptLock = new Object();

    @Data
    public static class AcceptResult {
        private DatasetRunEntity run;
        private boolean created;
    }

    /**
     * 异步受理：返回已落库的 run。同键同请求返回原 run；同键不同请求 409；活动运行存在时 429。
     * 事务与受理锁在同一临界区内完成，避免并发提交绕过唯一键。
     */
    public AcceptResult accept(String workspaceId, String datasetId, String evaluatorId,
                               String mode, String systemPrompt, String model, String requestKey) {
        return accept(workspaceId, datasetId, List.of(evaluatorId), null, mode, systemPrompt, model,
                null, null, null, null, null, null, requestKey, null);
    }

    public AcceptResult accept(String workspaceId, String datasetId, List<String> evaluatorIds,
                               String revisionId, String mode, String systemPrompt, String model,
                               String candidateLabel, String candidateFingerprint,
                               Integer maxExternalCalls, String suiteId, String requestKey) {
        return accept(workspaceId, datasetId, evaluatorIds, revisionId, mode, systemPrompt, model,
                candidateLabel, candidateFingerprint, maxExternalCalls, null, null, suiteId,
                requestKey, null);
    }

    public AcceptResult accept(String workspaceId, String datasetId, List<String> evaluatorIds,
                               String revisionId, String mode, String systemPrompt, String model,
                               String candidateLabel, String candidateFingerprint,
                               Integer maxExternalCalls, Long maxTokens,
                               String suiteId, String requestKey) {
        return accept(workspaceId, datasetId, evaluatorIds, revisionId, mode, systemPrompt, model,
                candidateLabel, candidateFingerprint, maxExternalCalls, maxTokens, null,
                suiteId, requestKey, null);
    }

    public AcceptResult accept(String workspaceId, String datasetId, List<String> evaluatorIds,
                               String revisionId, String mode, String systemPrompt, String model,
                               String candidateLabel, String candidateFingerprint,
                               Integer maxExternalCalls, Long maxTokens, String reviewMode,
                               String suiteId, String requestKey, java.math.BigDecimal maxCost) {
        return accept(workspaceId, datasetId, evaluatorIds, revisionId, mode, systemPrompt, model,
                candidateLabel, candidateFingerprint, maxExternalCalls, maxTokens, reviewMode,
                suiteId, requestKey, maxCost, null, null, null);
    }

    /**
     * R1-3：受理时额外绑定 prompt 版本引用、基线标签与提交号。
     *
     * @param promptRef name 必填；version 为空时按 label（缺省 production）解析
     * @param runLabel  CI 基线标签（如 baseline），仅供按标签取最近 run
     * @param commitSha 触发提交号，进快照做可追溯，不进候选指纹
     */
    public AcceptResult accept(String workspaceId, String datasetId, List<String> evaluatorIds,
                               String revisionId, String mode, String systemPrompt, String model,
                               String candidateLabel, String candidateFingerprint,
                               Integer maxExternalCalls, Long maxTokens, String reviewMode,
                               String suiteId, String requestKey, java.math.BigDecimal maxCost,
                               io.emonlens.server.engine.prompt.PromptService.PromptRef promptRef,
                               String runLabel, String commitSha) {
        Integer budget = normalizeMaxExternalCalls(maxExternalCalls);
        Long tokenBudget = normalizeMaxTokens(maxTokens);
        String normalizedReviewMode = normalizeReviewMode(reviewMode);
        String suiteKey = suiteId == null ? "" : suiteId.trim();
        List<String> ids;
        List<EvaluatorEntity> suiteEvaluators = null;
        Map<String, Object> suiteSnapshot = null;
        if (!suiteKey.isEmpty()) {
            if (evaluatorIds != null && !evaluatorIds.isEmpty()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "suiteId 与 evaluatorIds 不能同时指定");
            }
            EvalSuiteEntity suite = evalSuiteService.requireOwned(workspaceId, suiteKey);
            suiteEvaluators = evalSuiteService.frozenEvaluators(suite);
            if (suiteEvaluators.isEmpty()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "eval suite 没有评估器");
            }
            ids = new ArrayList<>();
            for (EvaluatorEntity evaluator : suiteEvaluators) {
                ids.add(evaluator.getId());
            }
            suiteSnapshot = evalSuiteService.snapshot(suite);
        } else {
            ids = normalizeEvaluatorIds(evaluatorIds);
        }
        String key = requestKey == null ? "" : requestKey.trim();
        if (key.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        if (key.length() > 128) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能超过 128 字符");
        }
        String runMode = DatasetRunSnapshotService.MODE_REPLAY.equalsIgnoreCase(mode)
                ? DatasetRunSnapshotService.MODE_REPLAY : DatasetRunSnapshotService.MODE_TRACE;
        String revisionKey = revisionId == null ? "" : revisionId.trim();
        // prompt 引用在受理时解析成不可变版本：之后 production 指针再移动也不影响本 run 的语义，
        // 但 Gate verification 会把“指针已移动”判成结论过期（见 QualityGateService）。
        io.emonlens.server.engine.prompt.PromptService.PromptRef resolvedPrompt = null;
        io.emonlens.server.storage.entity.PromptEntity resolvedPromptEntity = null;
        if (promptRef != null && promptRef.getName() != null && !promptRef.getName().isBlank()) {
            String promptName = promptRef.getName().trim();
            resolvedPromptEntity = promptService.resolve(workspaceId, promptName,
                    promptRef.getVersion(), promptRef.getLabel());
            resolvedPrompt = promptService.refOf(resolvedPromptEntity,
                    promptRef.getLabel() == null || promptRef.getLabel().isBlank()
                            ? (promptRef.getVersion() == null
                                ? io.emonlens.server.engine.prompt.PromptService.LABEL_PRODUCTION
                                : io.emonlens.server.engine.prompt.PromptService.LABEL_LATEST)
                            : promptRef.getLabel().trim());
        }
        String normalizedRunLabel = runLabel == null || runLabel.isBlank() ? null : runLabel.trim();
        if (normalizedRunLabel != null && normalizedRunLabel.length() > 64) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runLabel 不能超过 64 字符");
        }
        String normalizedCommit = commitSha == null || commitSha.isBlank() ? null : commitSha.trim();
        if (normalizedCommit != null && normalizedCommit.length() > 64) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "commitSha 不能超过 64 字符");
        }
        String requestHash = requestHash(datasetId, ids, revisionKey, runMode, systemPrompt, model,
                candidateLabel, candidateFingerprint, budget, tokenBudget, normalizedReviewMode,
                suiteKey, resolvedPrompt, normalizedRunLabel, normalizedCommit);

        synchronized (acceptLock) {
            DatasetRunEntity existing = datasetRunMapper.selectOne(Wrappers.<DatasetRunEntity>lambdaQuery()
                    .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                    .eq(DatasetRunEntity::getRequestKey, key));
            if (existing != null) {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new EmonLensException(ErrorCode.CONFLICT,
                            "同一 Idempotency-Key 已用于不同请求；主动重跑请使用新的 key");
                }
                enrich(existing, itemsOf(existing.getId()), false, true);
                return accepted(existing, false);
            }
            enforceActiveRunCapacity(workspaceId);

            DatasetEntity dataset = requireDataset(workspaceId, datasetId);
            List<EvaluatorEntity> evaluators = suiteEvaluators != null
                    ? suiteEvaluators : loadEvaluators(workspaceId, ids);
            DatasetRevisionEntity revision;
            List<DatasetItemEntity> items;
            if (!revisionKey.isEmpty()) {
                revision = revisionService.requireOwned(workspaceId, datasetId, revisionKey);
                items = revisionService.toItems(revision);
            } else {
                items = datasetItemMapper.selectList(Wrappers.<DatasetItemEntity>lambdaQuery()
                        .eq(DatasetItemEntity::getDatasetId, datasetId)
                        .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                                .or().isNull(DatasetItemEntity::getStatus)));
                revision = revisionService.freeze(dataset, items, "run-auto", "system");
                items = revisionService.toItems(revision);
            }
            if (items.isEmpty()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "数据集没有样本，无法运行");
            }
            int maxItems = properties.getDatasetRun().getMaxItems();
            if (items.size() > maxItems) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "单批最多 " + maxItems + " 条样本，当前 " + items.size() + " 条；请拆分数据集后再运行");
            }

            DatasetRunSnapshotService.FrozenRun frozen = snapshotService.freeze(
                    dataset, items, evaluators, revision, runMode, systemPrompt, model, budget,
                    tokenBudget, suiteSnapshot, resolvedPrompt, normalizedCommit);
            frozen.getConfigSnapshot().put("reviewMode", normalizedReviewMode);
            if (maxCost != null) {
                frozen.getConfigSnapshot().put("maxCost", maxCost);
            }
            frozen.getConfigSnapshot().put("domainPackId", dataset.getDomainPackId());
            // 受理时固定当次有效凭据/配置（仅内存，不落库）；执行期间不再读取默认模型
            SysSettingService.EvalLlm credentials = llmClient.effectiveConfig();

            // CI 基线标签唯一：新 run 拿走的标签必须从同数据集其它 run 上摘掉，
            // 否则“按 label 取最近一条”之外还会存在第二条自称基线的 run。
            if (normalizedRunLabel != null) {
                datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                        .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                        .eq(DatasetRunEntity::getDatasetId, datasetId)
                        .eq(DatasetRunEntity::getRunLabel, normalizedRunLabel)
                        .set(DatasetRunEntity::getRunLabel, null));
            }

            DatasetRunEntity run = new DatasetRunEntity();
            run.setWorkspaceId(workspaceId);
            run.setDatasetId(datasetId);
            run.setDatasetRevisionId(revision.getId());
            run.setDomainPackId(dataset.getDomainPackId());
            run.setSuiteId(suiteKey.isEmpty() ? null : suiteKey);
            run.setEvaluatorId(evaluators.get(0).getId());
            run.setEvaluatorKey(evaluators.get(0).getEvaluatorKey());
            run.setCandidateLabel(candidateLabel);
            // 指纹必须由冻结快照推导，不能由调用方覆盖，否则 Gate 会信任一个与配置无关的字符串。
            String derivedFingerprint = (String) frozen.getConfigSnapshot().get("candidateFingerprint");
            if (candidateFingerprint != null && !candidateFingerprint.isBlank()
                    && !candidateFingerprint.trim().equals(derivedFingerprint)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "candidateFingerprint 必须由冻结配置推导，不能由调用方指定");
            }
            run.setCandidateFingerprint(derivedFingerprint);
            run.setRunMode(runMode);
            run.setModel(DatasetRunSnapshotService.MODE_REPLAY.equals(runMode)
                    ? (String) ((Map<?, ?>) frozen.getConfigSnapshot().get("generation")).get("model") : null);
            if (resolvedPrompt != null) {
                run.setPromptName(resolvedPrompt.getName());
                run.setPromptVersion(resolvedPrompt.getVersion());
                run.setPromptLabel(resolvedPrompt.getLabel());
                run.setPromptHash(resolvedPrompt.getHash());
            }
            run.setRunLabel(normalizedRunLabel);
            run.setCommitSha(normalizedCommit);
            run.setStatus(DatasetRunEntity.STATUS_PENDING);
            run.setItemCount(frozen.getItems().size());
            run.setExternalCallsReserved(0);
            run.setMaxTokens(tokenBudget);
            run.setTokensReserved(0L);
            run.setTokensSpent(0L);
            run.setPassCount(0);
            run.setFailCount(0);
            run.setRequestKey(key);
            run.setRequestHash(requestHash);
            run.setConfigSnapshotJson(toJson(frozen.getConfigSnapshot()));
            run.setStartedAt(LocalDateTime.now());
            run.setConfigSnapshot(frozen.getConfigSnapshot());
            run.setLegacyNoSnapshot(false);

            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    datasetRunMapper.insert(run);
                    acquireAdmissionSlot(run.getId());
                    credentialStore.put(run.getId(), credentials);
                    List<DatasetRunItemEntity> insertedItems = new ArrayList<>();
                    for (DatasetRunSnapshotService.FrozenItem fi : frozen.getItems()) {
                        DatasetRunItemEntity ri = new DatasetRunItemEntity();
                        ri.setRunId(run.getId());
                        ri.setItemId(fi.getItemId());
                        ri.setTraceId(fi.getTraceId());
                        ri.setSourceTraceId(fi.getSourceTraceId());
                        Object agentKey = fi.getSampleSnapshot().get("agentKey");
                        Object agentVersion = fi.getSampleSnapshot().get("agentVersion");
                        ri.setAgentKey(agentKey == null ? null : String.valueOf(agentKey));
                        ri.setAgentVersion(agentVersion == null ? null : String.valueOf(agentVersion));
                        ri.setSampleSnapshotJson(toJson(fi.getSampleSnapshot()));
                        ri.setResultDetailsJson("{\"status\":\"PENDING\"}");
                        datasetRunItemMapper.insert(ri);
                        insertedItems.add(ri);
                    }
                    evalTaskService.materialize(run, insertedItems,
                            evaluatorSnapshots(frozen.getConfigSnapshot()));
                });
            } catch (DuplicateKeyException e) {
                credentialStore.remove(run.getId());
                DatasetRunEntity raced = datasetRunMapper.selectOne(Wrappers.<DatasetRunEntity>lambdaQuery()
                        .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                        .eq(DatasetRunEntity::getRequestKey, key));
                if (raced != null && requestHash.equals(raced.getRequestHash())) {
                    enrich(raced, itemsOf(raced.getId()), false, true);
                    return accepted(raced, false);
                }
                throw new EmonLensException(ErrorCode.CONFLICT,
                        "同一 Idempotency-Key 已用于不同请求；主动重跑请使用新的 key");
            } catch (RuntimeException e) {
                credentialStore.remove(run.getId());
                throw e;
            }
            if (resolvedPromptEntity != null) {
                promptService.recordResolution(resolvedPromptEntity, resolvedPrompt.getLabel(),
                        io.emonlens.server.engine.prompt.PromptService.SOURCE_RUN, null);
            }
            return accepted(run, true);
        }
    }

    private AcceptResult accepted(DatasetRunEntity run, boolean created) {
        AcceptResult result = new AcceptResult();
        result.setRun(run);
        result.setCreated(created);
        return result;
    }

    /**
     * 断点续跑：从 INTERRUPTED run 复制冻结配置与样本，已执行 item 证据原样保留，
     * 未执行 item 重新进入 PENDING。新 run 是独立 attempt，不改写原 run。
     * 预算运行按持久化 external_calls_reserved 账本携带剩余额度，避免新 attempt 重新获得完整预算。
     */
    public DatasetRunEntity resume(String workspaceId, String datasetId, String runId, String requestKey) {
        return createAttempt(workspaceId, datasetId, runId, requestKey, AttemptMode.RESUME);
    }

    /** 只重试 PARTIAL_FAILED run 中 label=ERROR 的样本；UNKNOWN 视为已有判定，不重试。 */
    public DatasetRunEntity retryFailed(String workspaceId, String datasetId, String runId, String requestKey) {
        return createAttempt(workspaceId, datasetId, runId, requestKey, AttemptMode.RETRY_FAILED);
    }

    private enum AttemptMode { RESUME, RETRY_FAILED }

    private DatasetRunEntity createAttempt(String workspaceId, String datasetId, String runId, String requestKey,
                                           AttemptMode mode) {
        String action = mode == AttemptMode.RESUME ? "续跑" : "重试";
        String key = requestKey == null ? "" : requestKey.trim();
        if (key.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        if (key.length() > 128) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能超过 128 字符");
        }
        Map<String, Object> resumeHash = new TreeMap<>();
        resumeHash.put("resumeFrom", runId);
        resumeHash.put("datasetId", datasetId);
        if (mode == AttemptMode.RETRY_FAILED) {
            resumeHash.put("retryFailed", true);
        }
        String requestHash = Sha256Utils.hash(toJson(resumeHash));
        synchronized (acceptLock) {
            DatasetRunEntity existing = datasetRunMapper.selectOne(Wrappers.<DatasetRunEntity>lambdaQuery()
                    .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                    .eq(DatasetRunEntity::getRequestKey, key));
            if (existing != null) {
                if (!requestHash.equals(existing.getRequestHash())
                        || !runId.equals(existing.getResumedFromRunId())) {
                    throw new EmonLensException(ErrorCode.CONFLICT,
                            "同一 Idempotency-Key 已用于不同的续跑/重试请求");
                }
                enrich(existing, itemsOf(existing.getId()), false, true);
                return existing;
            }
            enforceActiveRunCapacity(workspaceId);
            requireDataset(workspaceId, datasetId);
            DatasetRunEntity source = requireRun(workspaceId, datasetId, runId);
            if (mode == AttemptMode.RESUME
                    && !DatasetRunEntity.STATUS_INTERRUPTED.equals(source.getStatus())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "只有 INTERRUPTED run 可以断点续跑");
            }
            if (mode == AttemptMode.RETRY_FAILED
                    && !DatasetRunEntity.STATUS_PARTIAL_FAILED.equals(source.getStatus())
                    && !DatasetRunEntity.STATUS_FAILED.equals(source.getStatus())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "只有 PARTIAL_FAILED/FAILED run 可以重试 ERROR 样本");
            }
            if (source.getConfigSnapshotJson() == null || source.getConfigSnapshotJson().isBlank()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "历史 run 没有冻结配置，无法" + action);
            }
            Map<String, Object> config = parseMap(source.getConfigSnapshotJson());
            if (config == null || config.isEmpty()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "冻结配置无法解析，无法" + action);
            }
            // 指纹/revision 必须以冻结 config 为准，不能只信 run 列；不一致说明证据被改写。
            String configFingerprint = config.get("candidateFingerprint") == null
                    ? null : String.valueOf(config.get("candidateFingerprint"));
            Object revisionRaw = config.get("datasetRevision");
            String configRevisionId = revisionRaw instanceof Map
                    ? Objects.toString(((Map<?, ?>) revisionRaw).get("id"), null) : null;
            if (configFingerprint == null || configFingerprint.isBlank()
                    || !Objects.equals(configFingerprint, source.getCandidateFingerprint())
                    || configRevisionId == null
                    || !Objects.equals(configRevisionId, source.getDatasetRevisionId())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "run 冻结配置与指纹/revision 绑定不一致，拒绝" + action);
            }
            Long existingAttempt = datasetRunMapper.selectCount(Wrappers.<DatasetRunEntity>lambdaQuery()
                    .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                    .eq(DatasetRunEntity::getResumedFromRunId, source.getId()));
            if (existingAttempt != null && existingAttempt > 0) {
                throw new EmonLensException(ErrorCode.CONFLICT,
                        "该源 run 已创建后续 attempt；请从最新 attempt 继续，避免证据链分叉");
            }
            List<DatasetRunItemEntity> sourceItems = itemsOf(source.getId());
            if (mode == AttemptMode.RETRY_FAILED
                    && sourceItems.stream().anyMatch(i -> i.getLabel() == null)) {
                throw new EmonLensException(ErrorCode.CONFLICT,
                        "源 run 仍有未执行样本，请先续跑完成后再重试 ERROR");
            }
            long pending = mode == AttemptMode.RESUME
                    ? sourceItems.stream().filter(i -> i.getLabel() == null).count()
                    : sourceItems.stream().filter(i -> EvaluatorEntity.LABEL_ERROR.equals(i.getLabel())).count();
            if (pending == 0) {
                throw new EmonLensException(ErrorCode.CONFLICT, mode == AttemptMode.RESUME
                        ? "该 run 没有未执行样本，无需续跑"
                        : "该 run 没有 ERROR 样本，无需重试");
            }
            Long attemptMaxTokens = null;
            Map<?, ?> sourceBudget = asMap(config.get("budget"));
            Object limitRaw = sourceBudget == null ? null : sourceBudget.get("maxExternalCalls");
            Integer sourceLimit = strictInteger(limitRaw);
            if (limitRaw != null && sourceLimit == null) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "预算配置损坏：maxExternalCalls 不是整数，拒绝" + action);
            }
            if (sourceLimit != null) {
                Integer sourceReserved = source.getExternalCallsReserved();
                if (sourceReserved == null) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST,
                            "历史预算 run 未启用调用账本，无法安全" + action + "；请新建 run");
                }
                if (sourceReserved < 0) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST,
                            "预算账本损坏：externalCallsReserved 为负数");
                }
                int remaining = sourceLimit - sourceReserved;
                int perItem = estimatedCallsPerItem(source, config);
                if (remaining < perItem) {
                    throw new EmonLensException(ErrorCode.CONFLICT,
                            "外部调用预算剩余 " + remaining + " 次，不足以完成一条样本（至少 "
                                    + perItem + " 次），无法" + action);
                }
                Map<String, Object> budget = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : sourceBudget.entrySet()) {
                    budget.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                Integer original = strictInteger(sourceBudget.get("originalMaxExternalCalls"));
                int priorResumeConsumed = Math.max(0, optionalInt(sourceBudget.get("resumeConsumedCalls")));
                budget.put("originalMaxExternalCalls", original == null ? sourceLimit : original);
                budget.put("resumeConsumedCalls", priorResumeConsumed + sourceReserved);
                budget.put("maxExternalCalls", remaining);
                config.put("budget", budget);
            }
            Long sourceMaxTokens = source.getMaxTokens();
            if (sourceMaxTokens != null) {
                Long sourceSpent = source.getTokensSpent();
                Long sourceReserved = source.getTokensReserved();
                if (sourceSpent == null || sourceReserved == null || sourceSpent < 0 || sourceReserved < 0) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST,
                            "token 预算账本缺失或损坏，拒绝" + action + "；请新建 run");
                }
                long remainingTokens = sourceMaxTokens - sourceSpent - sourceReserved;
                if (remainingTokens <= 0) {
                    throw new EmonLensException(ErrorCode.CONFLICT,
                            "token 预算已耗尽，无法" + action + "；请显式新建 run 并设置新预算");
                }
                Map<String, Object> tokenBudgetMap = new LinkedHashMap<>();
                Map<?, ?> currentBudget = asMap(config.get("budget"));
                if (currentBudget != null) {
                    for (Map.Entry<?, ?> entry : currentBudget.entrySet()) {
                        tokenBudgetMap.put(String.valueOf(entry.getKey()), entry.getValue());
                    }
                }
                Long original = optionalLongOrNull(tokenBudgetMap.get("originalMaxTokens"));
                long priorConsumed = Math.max(0, optionalLong(tokenBudgetMap.get("resumeConsumedTokens")));
                tokenBudgetMap.put("originalMaxTokens", original == null ? sourceMaxTokens : original);
                tokenBudgetMap.put("resumeConsumedTokens", priorConsumed + sourceSpent + sourceReserved);
                tokenBudgetMap.put("maxTokens", remainingTokens);
                config.put("budget", tokenBudgetMap);
                attemptMaxTokens = remainingTokens;
            }
            SysSettingService.EvalLlm credentials = llmClient.effectiveConfig();

            DatasetRunEntity run = new DatasetRunEntity();
            run.setWorkspaceId(workspaceId);
            run.setDatasetId(datasetId);
            run.setDatasetRevisionId(configRevisionId);
            run.setDomainPackId(source.getDomainPackId());
            run.setResumedFromRunId(source.getId());
            run.setAttempt((source.getAttempt() == null ? 1 : source.getAttempt()) + 1);
            run.setSuiteId(source.getSuiteId());
            run.setCandidateLabel(source.getCandidateLabel());
            run.setCandidateFingerprint(configFingerprint);
            run.setEvaluatorId(source.getEvaluatorId());
            run.setEvaluatorKey(source.getEvaluatorKey());
            run.setRunMode(source.getRunMode());
            run.setModel(source.getModel());
            run.setStatus(DatasetRunEntity.STATUS_PENDING);
            run.setItemCount(sourceItems.size());
            run.setExternalCallsReserved(0);
            run.setMaxTokens(attemptMaxTokens);
            run.setTokensReserved(0L);
            run.setTokensSpent(0L);
            run.setPassCount(0);
            run.setFailCount(0);
            run.setRequestKey(key);
            run.setRequestHash(requestHash);
            run.setConfigSnapshotJson(toJson(config));
            run.setConfigSnapshot(config);
            run.setStartedAt(LocalDateTime.now());
            run.setLegacyNoSnapshot(false);

            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    datasetRunMapper.insert(run);
                    acquireAdmissionSlot(run.getId());
                    credentialStore.put(run.getId(), credentials);
                    List<DatasetRunItemEntity> insertedItems = new ArrayList<>();
                    Set<String> resetRunItemIds = new LinkedHashSet<>();
                    for (DatasetRunItemEntity sourceItem : sourceItems) {
                        DatasetRunItemEntity item = new DatasetRunItemEntity();
                        item.setRunId(run.getId());
                        item.setItemId(sourceItem.getItemId());
                        item.setTraceId(sourceItem.getTraceId());
                        item.setSourceTraceId(sourceItem.getSourceTraceId());
                        item.setAgentKey(sourceItem.getAgentKey());
                        item.setAgentVersion(sourceItem.getAgentVersion());
                        item.setSampleSnapshotJson(sourceItem.getSampleSnapshotJson());
                        boolean retryItem = mode == AttemptMode.RETRY_FAILED
                                ? EvaluatorEntity.LABEL_ERROR.equals(sourceItem.getLabel())
                                : sourceItem.getLabel() == null;
                        if (retryItem) {
                            item.setResultDetailsJson("{\"status\":\"PENDING\"}");
                        } else {
                            item.setLabel(sourceItem.getLabel());
                            item.setScore(sourceItem.getScore());
                            item.setReason(sourceItem.getReason());
                            item.setReplayOutput(sourceItem.getReplayOutput());
                            item.setResultDetailsJson(sourceItem.getResultDetailsJson());
                            item.setEvaluatorResultsJson(sourceItem.getEvaluatorResultsJson());
                        }
                        datasetRunItemMapper.insert(item);
                        insertedItems.add(item);
                        if (retryItem) {
                            resetRunItemIds.add(item.getId());
                        }
                    }
                    evalTaskService.copyForAttempt(source.getId(), run, insertedItems,
                            evaluatorSnapshots(config), resetRunItemIds);
                });
            } catch (DuplicateKeyException e) {
                credentialStore.remove(run.getId());
                DatasetRunEntity raced = datasetRunMapper.selectOne(Wrappers.<DatasetRunEntity>lambdaQuery()
                        .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                        .eq(DatasetRunEntity::getRequestKey, key));
                if (raced != null && requestHash.equals(raced.getRequestHash())
                        && runId.equals(raced.getResumedFromRunId())) {
                    enrich(raced, itemsOf(raced.getId()), false, true);
                    return raced;
                }
                Long racedAttempt = datasetRunMapper.selectCount(Wrappers.<DatasetRunEntity>lambdaQuery()
                        .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                        .eq(DatasetRunEntity::getResumedFromRunId, runId)
                        .eq(DatasetRunEntity::getAttempt, run.getAttempt()));
                if (racedAttempt != null && racedAttempt > 0) {
                    throw new EmonLensException(ErrorCode.CONFLICT,
                            "该源 run 已创建后续 attempt；请从最新 attempt 继续，避免证据链分叉");
                }
                throw new EmonLensException(ErrorCode.CONFLICT,
                        "同一 Idempotency-Key 已用于不同的续跑/重试请求");
            } catch (RuntimeException e) {
                credentialStore.remove(run.getId());
                throw e;
            }
            enrich(run, itemsOf(run.getId()), false, true);
            return run;
        }
    }

    private int estimatedCallsPerItem(DatasetRunEntity run, Map<String, Object> config) {
        int calls = DatasetRunSnapshotService.MODE_REPLAY.equalsIgnoreCase(run.getRunMode()) ? 1 : 0;
        Object raw = config.get("evaluators");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item instanceof Map
                        && EvaluatorEntity.TYPE_LLM_JUDGE.equals(
                                String.valueOf(((Map<?, ?>) item).get("type")))) {
                    calls++;
                }
            }
        }
        return Math.max(1, calls);
    }

    public enum ExternalCallReservation {
        RESERVED, BUDGET_EXCEEDED, LEASE_LOST
    }

    /**
     * 在执行前原子预留最坏调用数；只有持有当前未过期租约才能记账。
     * 预留不回退，崩溃时宁可少用预算也不能超卖；多实例下由条件更新保证上限。
     */
    public ExternalCallReservation reserveExternalCalls(String runId, String leaseToken,
                                                        int calls, Integer limit) {
        if (calls <= 0 || limit == null) {
            return ExternalCallReservation.RESERVED;
        }
        int updated = datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .setSql("external_calls_reserved = COALESCE(external_calls_reserved, 0) + " + calls)
                .eq(DatasetRunEntity::getId, runId)
                .eq(DatasetRunEntity::getLeaseToken, leaseToken)
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .gt(DatasetRunEntity::getLeaseExpiresAt, LocalDateTime.now())
                .apply("COALESCE(external_calls_reserved, 0) + {0} <= {1}", calls, limit));
        if (updated > 0) {
            return ExternalCallReservation.RESERVED;
        }
        DatasetRunEntity current = datasetRunMapper.selectById(runId);
        boolean leaseValid = current != null
                && DatasetRunEntity.STATUS_RUNNING.equals(current.getStatus())
                && leaseToken != null && leaseToken.equals(current.getLeaseToken())
                && current.getLeaseExpiresAt() != null
                && current.getLeaseExpiresAt().isAfter(LocalDateTime.now());
        return leaseValid ? ExternalCallReservation.BUDGET_EXCEEDED : ExternalCallReservation.LEASE_LOST;
    }

    private static final int MAX_ATTEMPTS = 100;

    /**
     * 一次运行及其续跑 attempt 链；只返回汇总，不读取/返回样本正文。
     * 以 requested 的 parent 指针构造祖先路径，再从 requested 向后追加，保证 current 一定在链中；
     * 不把 parent 的兄弟分支混入。最多 100 层，超限显式拒绝。
     */
    public Map<String, Object> attemptChain(String workspaceId, String datasetId, String runId) {
        DatasetRunEntity requested = requireRun(workspaceId, datasetId, runId);
        List<DatasetRunEntity> path = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        DatasetRunEntity cursor = requested;
        while (cursor != null) {
            if (path.size() >= MAX_ATTEMPTS) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "attempt 链超过 " + MAX_ATTEMPTS + " 层，拒绝返回不完整链");
            }
            if (!seen.add(cursor.getId())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "attempt 链存在环");
            }
            path.add(cursor);
            if (cursor.getResumedFromRunId() == null) {
                break;
            }
            DatasetRunEntity parent = datasetRunMapper.selectById(cursor.getResumedFromRunId());
            if (parent == null || !workspaceId.equals(parent.getWorkspaceId())
                    || !datasetId.equals(parent.getDatasetId())) {
                throw new EmonLensException(ErrorCode.NOT_FOUND, "attempt parent run not found");
            }
            cursor = parent;
        }
        Collections.reverse(path);

        DatasetRunEntity next = datasetRunMapper.selectOne(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                .eq(DatasetRunEntity::getDatasetId, datasetId)
                .eq(DatasetRunEntity::getResumedFromRunId, requested.getId())
                .orderByDesc(DatasetRunEntity::getAttempt)
                .orderByDesc(DatasetRunEntity::getId)
                .last("LIMIT 1"));
        while (next != null) {
            if (path.size() >= MAX_ATTEMPTS) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "attempt 链超过 " + MAX_ATTEMPTS + " 层，拒绝返回不完整链");
            }
            if (!seen.add(next.getId())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "attempt 链存在环");
            }
            path.add(next);
            next = datasetRunMapper.selectOne(Wrappers.<DatasetRunEntity>lambdaQuery()
                    .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                    .eq(DatasetRunEntity::getDatasetId, datasetId)
                    .eq(DatasetRunEntity::getResumedFromRunId, next.getId())
                    .orderByDesc(DatasetRunEntity::getAttempt)
                    .orderByDesc(DatasetRunEntity::getId)
                    .last("LIMIT 1"));
        }

        List<String> runIds = new ArrayList<>();
        for (DatasetRunEntity run : path) {
            runIds.add(run.getId());
        }
        Map<String, int[]> counts = new HashMap<>();
        if (!runIds.isEmpty()) {
            for (Map<String, Object> row : datasetRunItemMapper.selectMaps(
                    Wrappers.<DatasetRunItemEntity>query().select("run_id", "label").in("run_id", runIds))) {
                String itemRunId = mapString(row, "run_id");
                if (itemRunId == null) {
                    continue;
                }
                int[] c = counts.computeIfAbsent(itemRunId, key -> new int[6]);
                c[0]++;
                String label = mapString(row, "label");
                if (EvaluatorEntity.LABEL_PASS.equals(label)) {
                    c[1]++;
                } else if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
                    c[2]++;
                } else if (EvaluatorEntity.LABEL_UNKNOWN.equals(label)
                        || EvaluatorEntity.LABEL_NOT_APPLICABLE.equals(label)
                        || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)) {
                    c[3]++;
                } else if (EvaluatorEntity.LABEL_ERROR.equals(label)) {
                    c[4]++;
                } else {
                    c[5]++;
                }
            }
        }
        List<Map<String, Object>> attempts = new ArrayList<>();
        for (DatasetRunEntity run : path) {
            attempts.add(attemptSummary(run, counts.get(run.getId())));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("rootRunId", path.get(0).getId());
        result.put("currentRunId", requested.getId());
        result.put("attemptCount", attempts.size());
        result.put("attempts", attempts);
        return result;
    }

    private Map<String, Object> attemptSummary(DatasetRunEntity run, int[] counts) {
        int total = counts == null ? 0 : counts[0];
        int pass = counts == null ? 0 : counts[1];
        int fail = counts == null ? 0 : counts[2];
        int unknown = counts == null ? 0 : counts[3];
        int error = counts == null ? 0 : counts[4];
        int incomplete = counts == null ? 0 : counts[5];
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", run.getId());
        row.put("attempt", run.getAttempt() == null ? 1 : run.getAttempt());
        row.put("status", run.getStatus());
        row.put("resumedFromRunId", run.getResumedFromRunId());
        row.put("startedAt", run.getStartedAt());
        row.put("finishedAt", run.getFinishedAt());
        row.put("itemCount", total);
        row.put("passCount", pass);
        row.put("failCount", fail);
        row.put("unknownCount", unknown);
        row.put("errorCount", error);
        row.put("incompleteCount", incomplete);
        row.put("coverage", total == 0 ? 0.0 : roundPercent(pass + fail, total));
        row.put("externalCallsReserved", run.getExternalCallsReserved());
        Map<String, Object> config = parseMap(run.getConfigSnapshotJson());
        Map<?, ?> budget = config == null ? null : asMap(config.get("budget"));
        if (budget != null) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("maxExternalCalls", budget.get("maxExternalCalls"));
            b.put("originalMaxExternalCalls", budget.get("originalMaxExternalCalls"));
            b.put("resumeConsumedCalls", budget.get("resumeConsumedCalls"));
            row.put("budget", b);
        }
        return row;
    }

    public DatasetRunEntity requireRun(String workspaceId, String datasetId, String runId) {
        DatasetRunEntity run = requireRun(workspaceId, runId);
        if (!run.getDatasetId().equals(datasetId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "run not found");
        }
        return run;
    }

    public DatasetRunEntity requireRun(String workspaceId, String runId) {
        DatasetRunEntity run = datasetRunMapper.selectById(runId);
        if (run == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "run not found");
        }
        String runWorkspace = run.getWorkspaceId();
        if (runWorkspace == null) {
            DatasetEntity dataset = datasetMapper.selectById(run.getDatasetId());
            runWorkspace = dataset == null ? null : dataset.getWorkspaceId();
        }
        if (!workspaceId.equals(runWorkspace)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "run not found");
        }
        return run;
    }

    public List<DatasetRunItemEntity> itemsOf(String runId) {
        return datasetRunItemMapper.selectList(Wrappers.<DatasetRunItemEntity>lambdaQuery()
                .eq(DatasetRunItemEntity::getRunId, runId));
    }

    public DatasetRunItemEntity itemOf(String runId, String runItemId) {
        return datasetRunItemMapper.selectOne(Wrappers.<DatasetRunItemEntity>lambdaQuery()
                .eq(DatasetRunItemEntity::getRunId, runId)
                .eq(DatasetRunItemEntity::getId, runItemId));
    }

    /** 回写路径的样本更新：run 已终态，无租约竞争，直接写。 */
    public void saveItem(DatasetRunItemEntity item) {
        datasetRunItemMapper.updateById(item);
    }

    /** 宽限清理等后台路径用：run 可能已被清理，缺失返回 null 而不是抛错。 */
    public DatasetRunEntity requireRunQuietly(String runId) {
        return datasetRunMapper.selectById(runId);
    }

    /**
     * 补全运行汇总与解析后的快照。contentAllowed=false 时移除正文与可能含正文的理由。
     */
    public void enrich(DatasetRunEntity run, List<DatasetRunItemEntity> items, boolean contentAllowed,
                       boolean parseItems) {
        int pass = 0, fail = 0, unknown = 0, error = 0, incomplete = 0;
        int generationCalls = 0, judgeCalls = 0;
        Integer promptTokens = null, completionTokens = null;
        boolean anyUsage = false;
        boolean allUsageKnown = true;

        for (DatasetRunItemEntity item : items) {
            String label = item.getLabel();
            if (EvaluatorEntity.LABEL_PASS.equals(label)) {
                pass++;
            } else if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
                fail++;
            } else if (EvaluatorEntity.LABEL_UNKNOWN.equals(label)
                    || EvaluatorEntity.LABEL_NOT_APPLICABLE.equals(label)
                    || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)) {
                unknown++;
            } else if (EvaluatorEntity.LABEL_ERROR.equals(label)) {
                error++;
            } else {
                incomplete++;
            }
            if (parseItems) {
                item.setSampleSnapshot(parseMap(item.getSampleSnapshotJson()));
                item.setResultDetails(parseMap(item.getResultDetailsJson()));
                item.setEvaluatorResults(parseList(item.getEvaluatorResultsJson()));
            }
            Map<String, Object> details = item.getResultDetails();
            if (details != null) {
                Map<?, ?> calls = asMap(details.get("calls"));
                int externalCalls = calls == null ? 0 : asInt(calls.get("external"));
                if (calls != null) {
                    generationCalls += asInt(calls.get("generation"));
                    judgeCalls += asInt(calls.get("judge"));
                }
                Map<?, ?> usage = asMap(details.get("usage"));
                if (usage != null && Boolean.TRUE.equals(usage.get("known"))) {
                    anyUsage = true;
                    promptTokens = plus(promptTokens, asInteger(usage.get("promptTokens")));
                    completionTokens = plus(completionTokens, asInteger(usage.get("completionTokens")));
                } else if (externalCalls > 0) {
                    allUsageKnown = false;
                }
            }
            if (!contentAllowed) {
                redactContent(item);
            }
        }

        int total = items.size();
        // 取消后 worker 不再 refreshProgress，详情必须从已落库 item 派生 PASS/FAIL，避免汇总自相矛盾。
        run.setPassCount(pass);
        run.setFailCount(fail);
        run.setUnknownCount(unknown);
        run.setErrorCount(error);
        run.setIncompleteCount(incomplete);
        run.setPassRate(pass + fail == 0 ? null : roundPercent(pass, pass + fail));
        run.setCoverage(total == 0 ? 0.0 : roundPercent(pass + fail, total));
        run.setLegacyNoSnapshot(run.getRequestKey() == null && run.getConfigSnapshotJson() == null);
        Map<String, Object> config = parseMap(run.getConfigSnapshotJson());
        run.setConfigSnapshot(config);
        List<String> evaluatorKeys = new ArrayList<>();
        Object rawEvaluators = config == null ? null : config.get("evaluators");
        if (rawEvaluators instanceof List) {
            for (Object raw : (List<?>) rawEvaluators) {
                Map<?, ?> evaluator = asMap(raw);
                if (evaluator != null && evaluator.get("key") != null) {
                    evaluatorKeys.add(String.valueOf(evaluator.get("key")));
                }
            }
        } else if (config != null && config.get("evaluator") instanceof Map) {
            Map<?, ?> evaluator = asMap(config.get("evaluator"));
            if (evaluator != null && evaluator.get("key") != null) {
                evaluatorKeys.add(String.valueOf(evaluator.get("key")));
            }
        }
        run.setEvaluatorKeys(evaluatorKeys);
        run.setEvaluatorCount(evaluatorKeys.size());

        Map<String, Object> usageSummary = new LinkedHashMap<>();
        usageSummary.put("generationCalls", generationCalls);
        usageSummary.put("judgeCalls", judgeCalls);
        usageSummary.put("externalCalls", generationCalls + judgeCalls);
        usageSummary.put("promptTokens", anyUsage ? promptTokens : null);
        usageSummary.put("completionTokens", anyUsage ? completionTokens : null);
        usageSummary.put("totalTokens", anyUsage && promptTokens != null && completionTokens != null
                ? promptTokens + completionTokens : null);
        usageSummary.put("usageKnown", anyUsage && allUsageKnown);
        run.setUsageSummary(usageSummary);
    }

    private void redactContent(DatasetRunItemEntity item) {
        Map<String, Object> sample = item.getSampleSnapshot();
        if (sample != null) {
            sample.remove("input");
            sample.remove("traceInput");
            sample.remove("expectedOutput");
            sample.remove("candidateOutput");
            sample.remove("candidateHash");
            sample.remove("traceDigest");
            sample.remove("contents");
            // v2 样本证据同样可能含正文：无内容权限时全部移除，只保留结构/状态
            sample.remove("retrievalContext");
            sample.remove("expectedTools");
            sample.remove("toolsCalled");
            sample.remove("turns");
            sample.remove("expectations");
        }
        Map<String, Object> details = item.getResultDetails();
        if (details != null) {
            details.remove("candidateOutput");
            details.remove("expectedOutput");
            details.remove("reason");
            details.remove("error");
            redactEvaluatorReasons(details.get("evaluators"));
        }
        redactEvaluatorReasons(item.getEvaluatorResults());
        if (item.getReason() != null) {
            item.setReason("（无内容查看权限，已隐藏判定理由）");
        }
        item.setReplayOutput(null);
    }

    @SuppressWarnings("unchecked")
    private void redactEvaluatorReasons(Object results) {
        if (!(results instanceof List)) {
            return;
        }
        for (Object raw : (List<?>) results) {
            if (raw instanceof Map) {
                Map<String, Object> result = (Map<String, Object>) raw;
                result.remove("reason");
                result.remove("error");
                result.remove("criteria");
                // claim 原文/理由可能包含样本内容；无内容权限时整段移除，仅保留 claimAggregate 计数
                result.remove("claims");
            }
        }
    }

    /**
     * 原子占用数据库级准入槽。单行主键把“检查无 active run”与“登记新 run”合成一次 insert；
     * 多实例并发时只有一个能成功。槽位指向终态/缺失 run 时可安全回收。
     */
    private void enforceActiveRunCapacity(String workspaceId) {
        int maxActive = maxActiveRuns();
        Long active = datasetRunMapper.selectCount(Wrappers.<DatasetRunEntity>lambdaQuery()
                .in(DatasetRunEntity::getStatus,
                        DatasetRunEntity.STATUS_PENDING, DatasetRunEntity.STATUS_RUNNING));
        if (active != null && active >= maxActive) {
            throw new EmonLensException(ErrorCode.RATE_LIMITED,
                    "已达到数据集实验并发上限（" + maxActive + "），请稍后再试");
        }
        int perWorkspace = maxActiveRunsPerWorkspace();
        if (perWorkspace > 0) {
            Long workspaceActive = datasetRunMapper.selectCount(Wrappers.<DatasetRunEntity>lambdaQuery()
                    .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                    .in(DatasetRunEntity::getStatus,
                            DatasetRunEntity.STATUS_PENDING, DatasetRunEntity.STATUS_RUNNING));
            if (workspaceActive != null && workspaceActive >= perWorkspace) {
                throw new EmonLensException(ErrorCode.RATE_LIMITED,
                        "已达到当前 workspace 的数据集实验并发上限（" + perWorkspace + "），请稍后再试");
            }
        }
    }

    private int maxActiveRunsPerWorkspace() {
        return Math.max(0, Math.min(16, properties.getDatasetRun().getMaxActiveRunsPerWorkspace()));
    }

    /**
     * 通过固定槽位池原子占位：slotKey=GLOBAL#1..N。每个槽位主键唯一，多个实例同时受理时
     * 最多只有 N 个 run 能拿到槽位；终态 run 的残留槽位可被安全回收。
     */
    private void acquireAdmissionSlot(String runId) {
        int maxActive = maxActiveRuns();
        for (int i = 1; i <= maxActive; i++) {
            String slotKey = DatasetRunSlotEntity.slotKey(i);
            LocalDateTime now = LocalDateTime.now();
            DatasetRunSlotEntity slot = new DatasetRunSlotEntity();
            slot.setSlotKey(slotKey);
            slot.setRunId(runId);
            slot.setAcquiredAt(now);
            slot.setExpiresAt(now.plusSeconds(Math.max(600, leaseSeconds() * 2)));
            try {
                datasetRunSlotMapper.insert(slot);
                return;
            } catch (DuplicateKeyException held) {
                DatasetRunSlotEntity existing = datasetRunSlotMapper.selectById(slotKey);
                if (existing == null) {
                    continue;
                }
                DatasetRunEntity owner = datasetRunMapper.selectById(existing.getRunId());
                if (owner == null || !isActiveStatus(owner.getStatus())) {
                    int cleaned = datasetRunSlotMapper.delete(Wrappers.<DatasetRunSlotEntity>lambdaQuery()
                            .eq(DatasetRunSlotEntity::getSlotKey, slotKey)
                            .eq(DatasetRunSlotEntity::getRunId, existing.getRunId()));
                    if (cleaned > 0) {
                        try {
                            datasetRunSlotMapper.insert(slot);
                            return;
                        } catch (DuplicateKeyException again) {
                            // 另一个实例在清理与重试之间抢到了槽位，继续尝试下一个。
                        }
                    }
                }
            }
        }
        throw new EmonLensException(ErrorCode.RATE_LIMITED,
                "已达到数据集实验并发上限（" + maxActive + "），请稍后再试");
    }

    private void releaseAdmissionSlot(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        datasetRunSlotMapper.delete(Wrappers.<DatasetRunSlotEntity>lambdaQuery()
                .eq(DatasetRunSlotEntity::getRunId, runId));
    }

    private int maxActiveRuns() {
        return Math.max(1, Math.min(16, properties.getDatasetRun().getMaxActiveRuns()));
    }

    /**
     * 按 model price 估算 run 成本，并按 generation / judge、model、evaluator 分账。
     * 缺价格的模型进入 unpricedModels，不伪造总额；多模型不再直接判为不可计价。
     */
    public Map<String, Object> costReport(String workspaceId, String datasetId, String runId) {
        DatasetRunEntity run = requireRun(workspaceId, datasetId, runId);
        List<DatasetRunItemEntity> items = itemsOf(runId);
        enrich(run, items, false, true);
        Map<String, Object> usage = run.getUsageSummary() == null
                ? Map.of() : run.getUsageSummary();
        long totalPrompt = longValue(usage.get("promptTokens"));
        long totalCompletion = longValue(usage.get("completionTokens"));
        Map<String, Object> config = run.getConfigSnapshot();
        String generationModel = generationModel(config);
        Map<String, String> evaluatorModels = evaluatorModels(config);

        long judgePrompt = 0;
        long judgeCompletion = 0;
        int judgeCalls = 0;
        int cacheHits = 0;
        Map<String, long[]> byEvaluator = new LinkedHashMap<>();
        Map<String, long[]> byModel = new LinkedHashMap<>();
        Map<String, String> evaluatorModelOf = new LinkedHashMap<>();
        for (DatasetRunItemEntity item : items) {
            for (Map<String, Object> result : item.getEvaluatorResults() == null
                    ? List.<Map<String, Object>>of() : item.getEvaluatorResults()) {
                boolean judgeCalled = Boolean.TRUE.equals(result.get("judgeCalled"));
                boolean cacheHit = Boolean.TRUE.equals(result.get("cacheHit"));
                if (judgeCalled) {
                    judgeCalls++;
                }
                if (cacheHit) {
                    cacheHits++;
                }
                long p = longValue(result.get("promptTokens"));
                long c = longValue(result.get("completionTokens"));
                String evaluatorKey = stringValue(result.get("evaluatorKey"), "unknown");
                String model = stringValue(result.get("judgeModel"), evaluatorModels.get(evaluatorKey));
                // 缓存命中也有 evaluator 行（0 token / 0 成本），否则成本页看不到“省了多少”
                if (p > 0 || c > 0 || cacheHit) {
                    evaluatorModelOf.putIfAbsent(evaluatorKey, model);
                    byEvaluator.merge(evaluatorKey, new long[] {p, c}, DatasetRunService::mergeTokens);
                }
                if (p > 0 || c > 0) {
                    byModel.merge(model == null ? "unknown" : model,
                            new long[] {p, c}, DatasetRunService::mergeTokens);
                    judgePrompt += p;
                    judgeCompletion += c;
                }
            }
        }
        long generationPrompt = Math.max(0, totalPrompt - judgePrompt);
        long generationCompletion = Math.max(0, totalCompletion - judgeCompletion);
        if (generationModel != null && generationPrompt + generationCompletion > 0) {
            byModel.merge(generationModel, new long[] {generationPrompt, generationCompletion},
                    DatasetRunService::mergeTokens);
        }

        Map<String, io.emonlens.server.storage.entity.ModelPriceEntity> prices = new LinkedHashMap<>();
        for (String model : byModel.keySet()) {
            io.emonlens.server.storage.entity.ModelPriceEntity price = modelPriceMapper.selectOne(
                    Wrappers.<io.emonlens.server.storage.entity.ModelPriceEntity>lambdaQuery()
                            .eq(io.emonlens.server.storage.entity.ModelPriceEntity::getWorkspaceId, workspaceId)
                            .eq(io.emonlens.server.storage.entity.ModelPriceEntity::getModel, model));
            if (price != null) {
                prices.put(model, price);
            }
        }
        List<String> unpricedModels = new ArrayList<>();
        java.math.BigDecimal totalCost = java.math.BigDecimal.ZERO;
        String currency = null;
        List<Map<String, Object>> byModelRows = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byModel.entrySet()) {
            String model = entry.getKey();
            long p = entry.getValue()[0];
            long c = entry.getValue()[1];
            io.emonlens.server.storage.entity.ModelPriceEntity price = prices.get(model);
            java.math.BigDecimal cost = price == null ? null : tokenCost(price, p, c);
            if (price == null) {
                unpricedModels.add(model);
            } else {
                totalCost = totalCost.add(cost);
                currency = currency == null
                        ? (price.getCurrency() == null ? "USD" : price.getCurrency()) : currency;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("model", model);
            row.put("promptTokens", p);
            row.put("completionTokens", c);
            row.put("cost", cost);
            row.put("priced", price != null);
            byModelRows.add(row);
        }
        List<Map<String, Object>> byEvaluatorRows = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : byEvaluator.entrySet()) {
            String evaluatorKey = entry.getKey();
            long p = entry.getValue()[0];
            long c = entry.getValue()[1];
            String model = evaluatorModelOf.get(evaluatorKey);
            io.emonlens.server.storage.entity.ModelPriceEntity price = prices.get(model);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("evaluatorKey", evaluatorKey);
            row.put("model", model);
            row.put("promptTokens", p);
            row.put("completionTokens", c);
            row.put("cost", price == null ? null : tokenCost(price, p, c));
            row.put("priced", price != null);
            byEvaluatorRows.add(row);
        }

        Map<String, Object> generationPhase = new LinkedHashMap<>();
        generationPhase.put("promptTokens", generationPrompt);
        generationPhase.put("completionTokens", generationCompletion);
        generationPhase.put("cost", generationModel == null ? null
                : costOf(prices.get(generationModel), generationPrompt, generationCompletion));
        Map<String, Object> judgePhase = new LinkedHashMap<>();
        judgePhase.put("promptTokens", judgePrompt);
        judgePhase.put("completionTokens", judgeCompletion);
        judgePhase.put("cost", judgeCost(byEvaluator, evaluatorModelOf, prices));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("datasetId", datasetId);
        result.put("status", run.getStatus());
        result.put("models", new ArrayList<>(byModel.keySet()));
        result.put("promptTokens", usage.get("promptTokens"));
        result.put("completionTokens", usage.get("completionTokens"));
        result.put("totalTokens", usage.get("totalTokens"));
        result.put("usageKnown", usage.get("usageKnown"));
        result.put("byPhase", Map.of("generation", generationPhase, "judge", judgePhase));
        result.put("byModel", byModelRows);
        result.put("byEvaluator", byEvaluatorRows);
        Map<String, Object> cache = new LinkedHashMap<>();
        cache.put("hits", cacheHits);
        cache.put("judgeCalls", judgeCalls);
        int judgeEvaluations = judgeCalls + cacheHits;
        cache.put("judgeEvaluations", judgeEvaluations);
        cache.put("hitRate", judgeEvaluations == 0 ? null
                : Math.round(cacheHits * 1000.0 / judgeEvaluations) / 10.0);
        result.put("cache", cache);
        result.put("unpricedModels", unpricedModels);
        result.put("priced", !byModel.isEmpty() && unpricedModels.size() < byModel.size());
        result.put("completePricing", unpricedModels.isEmpty() && !byModel.isEmpty());
        result.put("total", totalCost);
        result.put("currency", currency == null ? "USD" : currency);
        if (byModel.size() == 1) {
            String model = byModel.keySet().iterator().next();
            io.emonlens.server.storage.entity.ModelPriceEntity price = prices.get(model);
            result.put("model", model);
            if (price != null) {
                result.put("cost", tokenCost(price, byModel.get(model)[0], byModel.get(model)[1]));
                result.put("inputPerMillion", price.getInputPerMillion());
                result.put("outputPerMillion", price.getOutputPerMillion());
            }
        }
        if (byModel.isEmpty()) {
            result.put("reason", "NO_MODEL");
        } else if (unpricedModels.size() == byModel.size()) {
            result.put("reason", "PRICE_MISSING");
        }
        return result;
    }

    private String generationModel(Map<String, Object> config) {
        if (config != null && config.get("generation") instanceof Map) {
            Object model = ((Map<?, ?>) config.get("generation")).get("model");
            if (model != null && !String.valueOf(model).isBlank()) {
                return String.valueOf(model);
            }
        }
        return null;
    }

    private Map<String, String> evaluatorModels(Map<String, Object> config) {
        Map<String, String> result = new LinkedHashMap<>();
        if (config == null || !(config.get("evaluators") instanceof List)) {
            return result;
        }
        for (Object raw : (List<?>) config.get("evaluators")) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<?, ?> evaluator = (Map<?, ?>) raw;
            Object key = evaluator.get("key");
            Object model = evaluator.get("effectiveJudgeModel");
            if (model == null && evaluator.get("spec") instanceof Map) {
                model = ((Map<?, ?>) evaluator.get("spec")).get("judgeModel");
            }
            if (key != null && model != null && !String.valueOf(model).isBlank()) {
                result.put(String.valueOf(key), String.valueOf(model));
            }
        }
        return result;
    }

    private static long[] mergeTokens(long[] a, long[] b) {
        return new long[] {a[0] + b[0], a[1] + b[1]};
    }

    private java.math.BigDecimal tokenCost(io.emonlens.server.storage.entity.ModelPriceEntity price,
                                           long prompt, long completion) {
        return price.getInputPerMillion()
                .multiply(java.math.BigDecimal.valueOf(prompt))
                .add(price.getOutputPerMillion().multiply(java.math.BigDecimal.valueOf(completion)))
                .divide(java.math.BigDecimal.valueOf(1_000_000), 6,
                        java.math.RoundingMode.HALF_UP);
    }

    private java.math.BigDecimal costOf(io.emonlens.server.storage.entity.ModelPriceEntity price,
                                        long prompt, long completion) {
        return price == null ? null : tokenCost(price, prompt, completion);
    }

    private java.math.BigDecimal judgeCost(Map<String, long[]> byEvaluator,
                                           Map<String, String> evaluatorModelOf,
                                           Map<String, io.emonlens.server.storage.entity.ModelPriceEntity> prices) {
        java.math.BigDecimal total = java.math.BigDecimal.ZERO;
        boolean any = false;
        for (Map.Entry<String, long[]> entry : byEvaluator.entrySet()) {
            io.emonlens.server.storage.entity.ModelPriceEntity price =
                    prices.get(evaluatorModelOf.get(entry.getKey()));
            if (price != null) {
                total = total.add(tokenCost(price, entry.getValue()[0], entry.getValue()[1]));
                any = true;
            }
        }
        return any ? total : null;
    }

    private static String stringValue(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private long longValue(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    /** Domain Pack qualityModel 的运行时报告：只读 run 冻结的 evaluator spec 与逐样本结果，不改 Gate。 */
    public Map<String, Object> qualityModelReport(String workspaceId, String datasetId,
                                                  String runId) {
        DatasetRunEntity run = requireRun(workspaceId, datasetId, runId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("datasetId", datasetId);
        result.put("domainPackId", run.getDomainPackId());
        result.put("status", run.getStatus());
        if (run.getDomainPackId() == null || run.getDomainPackId().isBlank()) {
            result.put("bound", false);
            result.put("dimensions", List.of());
            result.put("warnings", List.of("run 未绑定领域包，无法按 qualityModel 聚合"));
            return result;
        }
        Map<String, Object> pack = domainPackService.detail(workspaceId, run.getDomainPackId());
        Map<String, Object> manifest = pack.get("manifest") instanceof Map
                ? (Map<String, Object>) pack.get("manifest") : Map.of();
        Object dimensionsRaw = manifest.get("qualityModel") instanceof Map
                ? ((Map<String, Object>) manifest.get("qualityModel")).get("dimensions") : null;
        List<Map<String, Object>> dimensions = new ArrayList<>();
        if (dimensionsRaw instanceof List) {
            for (Object row : (List<?>) dimensionsRaw) {
                if (row instanceof Map) {
                    Map<String, Object> dimension = new LinkedHashMap<>();
                    Map<?, ?> source = (Map<?, ?>) row;
                    dimension.put("key", source.get("key"));
                    dimension.put("label", source.get("label"));
                    dimension.put("weight", source.get("weight") == null ? 1 : source.get("weight"));
                    dimensions.add(dimension);
                }
            }
        }
        List<DatasetRunItemEntity> runItems = itemsOf(run.getId());
        enrich(run, runItems, false, true);
        Map<String, String> dimensionByEvaluator = new LinkedHashMap<>();
        Object evaluatorsRaw = run.getConfigSnapshot() == null
                ? null : run.getConfigSnapshot().get("evaluators");
        if (evaluatorsRaw instanceof List) {
            for (Object row : (List<?>) evaluatorsRaw) {
                if (!(row instanceof Map)) {
                    continue;
                }
                Map<?, ?> evaluator = (Map<?, ?>) row;
                Object key = evaluator.get("key");
                Object specRaw = evaluator.get("spec");
                if (key != null && specRaw instanceof Map) {
                    Object dimension = ((Map<?, ?>) specRaw).get("qualityDimension");
                    if (dimension != null && !String.valueOf(dimension).isBlank()) {
                        dimensionByEvaluator.put(String.valueOf(key), String.valueOf(dimension));
                    }
                }
            }
        }
        Map<String, int[]> stats = new LinkedHashMap<>();
        for (Map<String, Object> dimension : dimensions) {
            stats.put(String.valueOf(dimension.get("key")), new int[3]);
        }
        Set<String> unmapped = new LinkedHashSet<>();
        for (DatasetRunItemEntity item : runItems) {
            List<Map<String, Object>> results = item.getEvaluatorResults();
            if (results == null) {
                continue;
            }
            for (Map<String, Object> evaluatorResult : results) {
                String evaluatorKey = text(evaluatorResult.get("evaluatorKey"));
                String dimensionKey = dimensionByEvaluator.get(evaluatorKey);
                if (dimensionKey == null || !stats.containsKey(dimensionKey)) {
                    if (evaluatorKey != null) {
                        unmapped.add(evaluatorKey);
                    }
                    continue;
                }
                String label = text(evaluatorResult.get("label"));
                int[] counts = stats.get(dimensionKey);
                if ("PASS".equals(label)) {
                    counts[0]++;
                } else if ("FAIL".equals(label)) {
                    counts[1]++;
                } else {
                    counts[2]++;
                }
            }
        }
        double weightedSum = 0;
        double weightSum = 0;
        int covered = 0;
        List<Map<String, Object>> dimensionResults = new ArrayList<>();
        for (Map<String, Object> dimension : dimensions) {
            String key = String.valueOf(dimension.get("key"));
            int[] counts = stats.get(key);
            int evaluated = counts[0] + counts[1];
            Double passRate = evaluated == 0 ? null : round1(counts[0] * 100.0 / evaluated);
            double weight = dimension.get("weight") instanceof Number
                    ? ((Number) dimension.get("weight")).doubleValue() : 1.0;
            Map<String, Object> row = new LinkedHashMap<>(dimension);
            row.put("pass", counts[0]);
            row.put("fail", counts[1]);
            row.put("unknown", counts[2]);
            row.put("passRate", passRate);
            row.put("evaluated", evaluated);
            dimensionResults.add(row);
            if (evaluated > 0) {
                covered++;
                weightedSum += passRate == null ? 0 : passRate * weight;
                weightSum += weight;
            }
        }
        result.put("bound", true);
        result.put("packKey", pack.get("packKey"));
        result.put("packVersion", pack.get("version"));
        result.put("dimensions", dimensionResults);
        result.put("coverage", dimensions.isEmpty() ? null
                : round1(covered * 100.0 / dimensions.size()));
        result.put("weightedScore", weightSum == 0 ? null : round1(weightedSum / weightSum));
        result.put("unmappedEvaluatorKeys", new ArrayList<>(unmapped));
        result.put("warnings", unmapped.isEmpty() ? List.of()
                : List.of("部分 evaluator 未映射到 qualityDimension：" + String.join(",", unmapped)));
        return result;
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    public DatasetRunEntity claimNextPending() {
        DatasetRunEntity pending = datasetRunMapper.selectOne(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_PENDING)
                .orderByAsc(DatasetRunEntity::getStartedAt)
                .last("LIMIT 1"));
        if (pending == null) {
            return null;
        }
        LocalDateTime now = LocalDateTime.now();
        String token = UUID.randomUUID().toString();
        int updated = datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, pending.getId())
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_PENDING)
                .set(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .set(DatasetRunEntity::getStartedAt, now)
                .set(DatasetRunEntity::getLeaseToken, token)
                .set(DatasetRunEntity::getLeaseExpiresAt, now.plusSeconds(leaseSeconds())));
        return updated > 0 ? datasetRunMapper.selectById(pending.getId()) : null;
    }

    /** 续租：token/状态/未过期同时满足才成功；旧 worker 不能靠迟到的续租复活。 */
    public boolean renewLease(String runId, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int updated = datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, runId)
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .eq(DatasetRunEntity::getLeaseToken, token)
                .gt(DatasetRunEntity::getLeaseExpiresAt, now)
                .set(DatasetRunEntity::getLeaseExpiresAt, now.plusSeconds(leaseSeconds())));
        return updated > 0;
    }

    private long leaseSeconds() {
        return Math.max(30, properties.getDatasetRun().getLeaseSeconds());
    }

    /**
     * 进程启动时只中断“没有有效租约”的 RUNNING；有未过期 lease 的 run 属于其他存活实例，
     * 不能被本实例重启误杀。lease 过期的 run 由下一次轮询的 recoverExpiredLeases 收口。
     */
    public void recoverInterrupted() {
        LocalDateTime now = LocalDateTime.now();
        List<DatasetRunEntity> running = datasetRunMapper.selectList(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .isNotNull(DatasetRunEntity::getRequestKey)
                .and(w -> w.isNull(DatasetRunEntity::getLeaseToken)
                        .or().isNull(DatasetRunEntity::getLeaseExpiresAt)
                        .or().lt(DatasetRunEntity::getLeaseExpiresAt, now)));
        for (DatasetRunEntity run : running) {
            markUnexecutedItems(run.getId(), "INTERRUPTED",
                    "服务中断，样本未执行；不会自动重发外部调用");
            interrupt(run.getId());
            log.warn("dataset run marked INTERRUPTED on startup, run={}", run.getId());
        }
    }

    /** 运行中租约已过期（worker 崩溃/长阻塞）：中断并释放 token；下一次轮询不会永久卡 RUNNING。 */
    public void recoverExpiredLeases() {
        List<DatasetRunEntity> expired = datasetRunMapper.selectList(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .lt(DatasetRunEntity::getLeaseExpiresAt, LocalDateTime.now())
                .isNotNull(DatasetRunEntity::getRequestKey));
        for (DatasetRunEntity run : expired) {
            markUnexecutedItems(run.getId(), "LEASE_EXPIRED",
                    "worker 租约过期，样本未执行；不会自动重发外部调用");
            interrupt(run.getId());
            log.warn("dataset run lease expired, marked INTERRUPTED, run={}", run.getId());
        }
        // 终端 run 上仍悬挂的任务（典型：取消瞬间 worker 被杀）不能在 UI 永远显示“执行中”。
        // 留 5 分钟宽限期，避免把仍在回写迟到结果的 worker 误收敛。
        for (String runId : evalTaskService.staleOpenRunIds(LocalDateTime.now().minusMinutes(5), 100)) {
            DatasetRunEntity run = datasetRunMapper.selectById(runId);
            if (run == null || isActiveStatus(run.getStatus())) {
                continue;
            }
            evalTaskService.settleTerminalRunTasks(runId, run.getStatus());
            log.warn("terminal run had stale open eval tasks, settled, run={} status={}",
                    runId, run.getStatus());
        }
    }

    private void markUnexecutedItems(String runId, String reasonCode, String reason) {
        evalTaskService.interruptOpenTasks(runId, reasonCode, reason);
        for (DatasetRunItemEntity item : itemsOf(runId)) {
            if (item.getLabel() == null) {
                item.setResultDetailsJson("{\"status\":\"INCOMPLETE\",\"reasonCode\":\"" + reasonCode
                        + "\",\"reason\":\"" + reason + "\"}");
                datasetRunItemMapper.updateById(item);
            }
        }
    }

    private void interrupt(String runId) {
        datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, runId)
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .set(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_INTERRUPTED)
                .set(DatasetRunEntity::getFinishedAt, LocalDateTime.now())
                .set(DatasetRunEntity::getLeaseToken, null)
                .set(DatasetRunEntity::getLeaseExpiresAt, null));
        releaseAdmissionSlot(runId);
    }

    /**
     * 取消活动 run。条件更新保证只有 PENDING/RUNNING 能进入 CANCELLED；
     * 终态重复取消幂等返回当前状态。已执行 item 不回滚，未执行 item 标 CANCELLED。
     */
    @Transactional
    public DatasetRunEntity cancel(String workspaceId, String datasetId, String runId) {
        DatasetRunEntity run = requireRun(workspaceId, datasetId, runId);
        if (!isActiveStatus(run.getStatus())) {
            // 终态 run 的残留槽位在这里顺手清理；不改变 run 的任何结论。
            releaseAdmissionSlot(runId);
            credentialStore.remove(runId);
            return run;
        }
        int updated = datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, runId)
                .in(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_PENDING,
                        DatasetRunEntity.STATUS_RUNNING)
                .set(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_CANCELLED)
                .set(DatasetRunEntity::getFinishedAt, LocalDateTime.now())
                .set(DatasetRunEntity::getLeaseToken, null)
                .set(DatasetRunEntity::getLeaseExpiresAt, null));
        if (updated > 0) {
            for (DatasetRunItemEntity item : itemsOf(runId)) {
                if (item.getLabel() == null) {
                    item.setResultDetailsJson("{\"status\":\"INCOMPLETE\",\"reasonCode\":\"CANCELLED\","
                            + "\"reason\":\"用户取消运行，样本未执行\"}");
                    datasetRunItemMapper.updateById(item);
                }
            }
            evalTaskService.cancelOpenTasks(runId, "CANCELLED", "用户取消运行，任务未执行");
            releaseAdmissionSlot(runId);
            // PENDING run 尚未被 worker 领取：不清理会让内存中的 LLM 凭据永久驻留
            credentialStore.remove(runId);
            log.info("dataset run cancelled, run={}", runId);
        }
        return datasetRunMapper.selectById(runId);
    }

    public boolean isActive(String runId) {
        DatasetRunEntity run = datasetRunMapper.selectById(runId);
        return run != null && isActiveStatus(run.getStatus());
    }

    private boolean isActiveStatus(String status) {
        return DatasetRunEntity.STATUS_PENDING.equals(status)
                || DatasetRunEntity.STATUS_RUNNING.equals(status);
    }

    public void refreshProgress(DatasetRunEntity run) {
        String leaseToken = run.getLeaseToken();
        if (leaseToken == null || leaseToken.isBlank()) {
            log.warn("dataset run progress skipped (no lease token), run={}", run.getId());
            return;
        }
        List<DatasetRunItemEntity> items = itemsOf(run.getId());
        int pass = 0, fail = 0;
        for (DatasetRunItemEntity item : items) {
            if (EvaluatorEntity.LABEL_PASS.equals(item.getLabel())) {
                pass++;
            } else if (EvaluatorEntity.LABEL_FAIL.equals(item.getLabel())) {
                fail++;
            }
        }
        run.setPassCount(pass);
        run.setFailCount(fail);
        // 只更新计数，且只在 RUNNING/持有未过期 token 时更新；不能把已取消的 run 写回 RUNNING。
        LocalDateTime now = LocalDateTime.now();
        datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, run.getId())
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .eq(DatasetRunEntity::getLeaseToken, leaseToken)
                .gt(DatasetRunEntity::getLeaseExpiresAt, now)
                .set(DatasetRunEntity::getPassCount, pass)
                .set(DatasetRunEntity::getFailCount, fail));
    }

    /** 完成一次运行：状态只表达执行；业务 FAIL 不影响 COMPLETED。token 是必需的所有权凭证。 */
    public void finish(DatasetRunEntity run, String leaseToken) {
        finish(run, leaseToken, false);
    }

    /**
     * @param stoppedByBudget 预算耗尽导致仍有样本未执行：run 收口为 INTERRUPTED，
     *                        保留“可续跑”语义，避免 null label 被算成 FAILED 后无法 resume/retry
     */
    public void finish(DatasetRunEntity run, String leaseToken, boolean stoppedByBudget) {
        if (leaseToken == null || leaseToken.isBlank()) {
            log.warn("dataset run finish skipped (no lease token), run={}", run.getId());
            return;
        }
        List<DatasetRunItemEntity> items = itemsOf(run.getId());
        int pass = 0, fail = 0, unknown = 0, error = 0, incomplete = 0;
        for (DatasetRunItemEntity item : items) {
            String label = item.getLabel();
            if (EvaluatorEntity.LABEL_PASS.equals(label)) {
                pass++;
            } else if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
                fail++;
            } else if (EvaluatorEntity.LABEL_UNKNOWN.equals(label)
                    || EvaluatorEntity.LABEL_NOT_APPLICABLE.equals(label)
                    || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)) {
                unknown++;
            } else if (EvaluatorEntity.LABEL_ERROR.equals(label)) {
                error++;
            } else {
                incomplete++;
            }
        }
        String status;
        if (stoppedByBudget || incomplete > 0) {
            // 未执行完（预算耗尽 / 中途中断）：保留可续跑语义，不计入 FAILED
            status = DatasetRunEntity.STATUS_INTERRUPTED;
        } else if (error == 0) {
            status = DatasetRunEntity.STATUS_COMPLETED;
        } else if (pass + fail + unknown == 0) {
            status = DatasetRunEntity.STATUS_FAILED;
        } else {
            status = DatasetRunEntity.STATUS_PARTIAL_FAILED;
        }
        run.setPassCount(pass);
        run.setFailCount(fail);
        run.setStatus(status);
        run.setFinishedAt(LocalDateTime.now());
        LocalDateTime now = LocalDateTime.now();
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<DatasetRunEntity> update =
                Wrappers.<DatasetRunEntity>lambdaUpdate()
                        .eq(DatasetRunEntity::getId, run.getId())
                        .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                        .set(DatasetRunEntity::getPassCount, pass)
                        .set(DatasetRunEntity::getFailCount, fail)
                        .set(DatasetRunEntity::getStatus, status)
                        .set(DatasetRunEntity::getFinishedAt, now);
        update.eq(DatasetRunEntity::getLeaseToken, leaseToken)
                .gt(DatasetRunEntity::getLeaseExpiresAt, now);
        if (stoppedByBudget) {
            update.set(DatasetRunEntity::getLeaseToken, null)
                    .set(DatasetRunEntity::getLeaseExpiresAt, null);
        }
        int updated = datasetRunMapper.update(null, update);
        if (updated == 0) {
            log.info("dataset run finish skipped (not RUNNING or lease lost), run={}", run.getId());
            return;
        }
        evalTaskService.finalizeOpenTasks(run.getId());
        releaseAdmissionSlot(run.getId());
        log.info("dataset run finished, run={}, status={}, pass={}, fail={}, unknown={}, error={}, incomplete={}",
                run.getId(), status, pass, fail, unknown, error, incomplete);
    }

    /**
     * 终端 run 上重算 pass/fail 计数：SDK 结果回写改变样本判定后刷新 run 汇总。
     * 与 finish 的租约条件更新不同，这里只作用于终端 run，允许重复执行。
     */
    public void refreshTerminalCounters(String runId) {
        DatasetRunEntity run = datasetRunMapper.selectById(runId);
        if (run == null || isActiveStatus(run.getStatus())) {
            return;
        }
        int pass = 0, fail = 0;
        for (DatasetRunItemEntity item : itemsOf(runId)) {
            if (EvaluatorEntity.LABEL_PASS.equals(item.getLabel())) {
                pass++;
            } else if (EvaluatorEntity.LABEL_FAIL.equals(item.getLabel())) {
                fail++;
            }
        }
        datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, runId)
                .set(DatasetRunEntity::getPassCount, pass)
                .set(DatasetRunEntity::getFailCount, fail));
    }

    /** 预算只接受正整数；留空表示不限制，保持旧调用兼容。 */
    private long optionalLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private Long optionalLongOrNull(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String normalizeReviewMode(String reviewMode) {
        if (reviewMode == null || reviewMode.isBlank() || "SINGLE".equalsIgnoreCase(reviewMode)) {
            return "SINGLE";
        }
        if ("DOUBLE_BLIND".equalsIgnoreCase(reviewMode)) {
            return "DOUBLE_BLIND";
        }
        throw new EmonLensException(ErrorCode.BAD_REQUEST,
                "reviewMode 只支持 SINGLE 或 DOUBLE_BLIND");
    }

    private Long normalizeMaxTokens(Long maxTokens) {
        if (maxTokens == null) {
            return null;
        }
        if (maxTokens <= 0) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "maxTokens 必须为正整数");
        }
        if (maxTokens > 1_000_000_000L) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "maxTokens 不能超过 1000000000");
        }
        return maxTokens;
    }

    private Integer normalizeMaxExternalCalls(Integer maxExternalCalls) {
        if (maxExternalCalls == null) {
            return null;
        }
        int limit = properties.getDatasetRun().getMaxExternalCallsPerRun();
        if (maxExternalCalls <= 0) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "maxExternalCalls 必须为正整数");
        }
        if (maxExternalCalls > limit) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "maxExternalCalls 不能超过 " + limit);
        }
        return maxExternalCalls;
    }

    private List<String> normalizeEvaluatorIds(List<String> evaluatorIds) {
        if (evaluatorIds == null || evaluatorIds.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluatorId or evaluatorIds is required");
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String id : evaluatorIds) {
            if (id != null && !id.isBlank()) {
                unique.add(id.trim());
            }
        }
        if (unique.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluatorId or evaluatorIds is required");
        }
        int max = properties.getDatasetRun().getMaxEvaluators();
        if (unique.size() > max) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "单次 run 最多 " + max + " 个评估器，当前 " + unique.size() + " 个");
        }
        return new ArrayList<>(unique);
    }

    private List<EvaluatorEntity> loadEvaluators(String workspaceId, List<String> ids) {
        List<EvaluatorEntity> found = evaluatorMapper.selectList(Wrappers.<EvaluatorEntity>lambdaQuery()
                .eq(EvaluatorEntity::getWorkspaceId, workspaceId)
                .in(EvaluatorEntity::getId, ids));
        Map<String, EvaluatorEntity> byId = new LinkedHashMap<>();
        for (EvaluatorEntity evaluator : found) {
            byId.put(evaluator.getId(), evaluator);
        }
        List<EvaluatorEntity> ordered = new ArrayList<>();
        for (String id : ids) {
            EvaluatorEntity evaluator = byId.get(id);
            if (evaluator == null) {
                throw new EmonLensException(ErrorCode.NOT_FOUND, "evaluator not found: " + id);
            }
            if (evaluator.getEnabled() == null || evaluator.getEnabled() != 1) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluator is disabled: " + evaluator.getEvaluatorKey());
            }
            ordered.add(evaluator);
        }
        return ordered;
    }

    /**
     * 给 run 打 CI 基线标签（R1-4）：同一数据集内同标签只指向一个 run，其余自动摘除，
     * 这样“基线是哪一条”永远没有二义性。置空即摘除本 run 的标签。
     */
    public Map<String, Object> labelRun(String workspaceId, String datasetId, String runId, String runLabel) {
        DatasetRunEntity run = requireRun(workspaceId, datasetId, runId);
        String label = runLabel == null || runLabel.isBlank() ? null : runLabel.trim();
        if (label != null && label.length() > 64) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runLabel 不能超过 64 字符");
        }
        List<String> cleared = new ArrayList<>();
        if (label != null) {
            List<DatasetRunEntity> holders = datasetRunMapper.selectList(
                    Wrappers.<DatasetRunEntity>lambdaQuery()
                            .eq(DatasetRunEntity::getDatasetId, datasetId)
                            .eq(DatasetRunEntity::getRunLabel, label));
            for (DatasetRunEntity holder : holders) {
                if (!holder.getId().equals(run.getId())) {
                    datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                            .eq(DatasetRunEntity::getId, holder.getId())
                            .set(DatasetRunEntity::getRunLabel, null));
                    cleared.add(holder.getId());
                }
            }
        }
        datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, run.getId())
                .set(DatasetRunEntity::getRunLabel, label));
        run.setRunLabel(label);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", run.getId());
        result.put("datasetId", datasetId);
        result.put("runLabel", label);
        result.put("clearedRunIds", cleared);
        return result;
    }

    /** 按标签取该数据集最近一条 run（CI 基线解析）；没有返回 null。 */
    public DatasetRunEntity latestByLabel(String workspaceId, String datasetId, String runLabel) {
        if (runLabel == null || runLabel.isBlank()) {
            return null;
        }
        return datasetRunMapper.selectList(Wrappers.<DatasetRunEntity>lambdaQuery()
                        .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                        .eq(DatasetRunEntity::getDatasetId, datasetId)
                        .eq(DatasetRunEntity::getRunLabel, runLabel.trim())
                        .orderByDesc(DatasetRunEntity::getStartedAt)
                        .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
    }

    private DatasetEntity requireDataset(String workspaceId, String datasetId) {
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !workspaceId.equals(dataset.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found");
        }
        return dataset;
    }

    private String requestHash(String datasetId, List<String> evaluatorIds, String revisionId,
                               String mode, String systemPrompt, String model,
                               String candidateLabel, String candidateFingerprint,
                               Integer maxExternalCalls, Long maxTokens, String reviewMode,
                               String suiteId,
                               io.emonlens.server.engine.prompt.PromptService.PromptRef promptRef,
                               String runLabel, String commitSha) {
        Map<String, Object> canonical = new TreeMap<>();
        canonical.put("datasetId", datasetId);
        canonical.put("evaluatorIds", evaluatorIds);
        canonical.put("revisionId", revisionId == null ? "" : revisionId);
        canonical.put("mode", mode);
        canonical.put("systemPrompt", systemPrompt == null ? "" : systemPrompt);
        canonical.put("model", model == null ? "" : model);
        canonical.put("candidateLabel", candidateLabel == null ? "" : candidateLabel);
        canonical.put("candidateFingerprint", candidateFingerprint == null ? "" : candidateFingerprint);
        // 只有显式声明预算才进入 hash，保持历史无预算请求的幂等键可继续命中。
        if (maxExternalCalls != null) {
            canonical.put("maxExternalCalls", maxExternalCalls);
        }
        if (maxTokens != null) {
            canonical.put("maxTokens", maxTokens);
        }
        if (reviewMode != null && !"SINGLE".equals(reviewMode)) {
            canonical.put("reviewMode", reviewMode);
        }
        if (suiteId != null && !suiteId.isEmpty()) {
            canonical.put("suiteId", suiteId);
        }
        // 只有显式声明才进 hash：保持历史（无 prompt / 无标签 / 无提交号）幂等键继续命中。
        if (promptRef != null && promptRef.getName() != null) {
            canonical.put("promptName", promptRef.getName());
            canonical.put("promptVersion", promptRef.getVersion());
        }
        if (runLabel != null && !runLabel.isEmpty()) {
            canonical.put("runLabel", runLabel);
        }
        if (commitSha != null && !commitSha.isEmpty()) {
            canonical.put("commitSha", commitSha);
        }
        return Sha256Utils.hash(toJson(canonical));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> evaluatorSnapshots(Map<String, Object> config) {
        if (config == null) {
            return List.of();
        }
        Object raw = config.get("evaluators");
        if (!(raw instanceof List)) {
            return List.of();
        }
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (Object item : (List<?>) raw) {
            if (item instanceof Map) {
                snapshots.add((Map<String, Object>) item);
            }
        }
        return snapshots;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "dataset run snapshot serialization failed");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("dataset run evaluator results parse failed: {}", e.getMessage());
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

    private String mapString(Map<String, Object> row, String key) {
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key) && entry.getValue() != null) {
                return String.valueOf(entry.getValue());
            }
        }
        return null;
    }

    private Map<?, ?> asMap(Object value) {
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private int asInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private Integer asInteger(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    /** 预算/账本字段必须是非负整数；Double 2.9 这类静默截断不可接受。 */
    private Integer strictInteger(Object value) {
        if (!(value instanceof Number)) {
            return null;
        }
        Number number = (Number) value;
        double asDouble = number.doubleValue();
        if (!Double.isFinite(asDouble) || asDouble != Math.rint(asDouble)
                || asDouble < Integer.MIN_VALUE || asDouble > Integer.MAX_VALUE) {
            return null;
        }
        return number.intValue();
    }

    private int optionalInt(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
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

    private double roundPercent(int numerator, int denominator) {
        return Math.round(numerator * 1000.0 / denominator) / 10.0;
    }
}
