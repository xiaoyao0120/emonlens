package io.emonlens.server.engine.gate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.engine.eval.DatasetRunService;
import io.emonlens.server.engine.eval.DomainPackService;
import io.emonlens.server.engine.eval.EvalTaskService;
import io.emonlens.server.engine.eval.JudgeCalibrationService;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.JudgeReviewEntity;
import io.emonlens.server.storage.entity.QualityGateEntity;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.JudgeReviewMapper;
import io.emonlens.server.storage.mapper.QualityGateMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 最小准入 Gate：只消费一次已冻结 run 的计数与绑定证据。
 * 缺覆盖率、缺执行、缺 revision/version 绑定一律 BLOCKED，不能默认放行。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityGateService {

    private static final String POLICY_KEY = "release-admission";
    private static final String POLICY_VERSION = "1";

    private final QualityGateMapper gateMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetRunService datasetRunService;
    private final EvalTaskService evalTaskService;
    private final JudgeReviewMapper judgeReviewMapper;
    private final ObjectMapper objectMapper;
    private final DomainPackService domainPackService;
    private final io.emonlens.server.engine.prompt.PromptService promptService;
    private final io.emonlens.server.engine.security.SecurityEvidenceService securityEvidenceService;

    public QualityGateEntity evaluate(String workspaceId, String runId, String policyKey,
                                      double minCoverage, int maxFail, int maxUnknown,
                                      int validHours, String createdBy) {
        return evaluate(workspaceId, runId, policyKey, minCoverage, maxFail, maxUnknown,
                validHours, createdBy, null);
    }

    public QualityGateEntity evaluate(String workspaceId, String runId, String policyKey,
                                      double minCoverage, int maxFail, int maxUnknown,
                                      int validHours, String createdBy, String gateTemplateKey) {
        DatasetRunEntity run = datasetRunService.requireRun(workspaceId, runId);
        DatasetEntity dataset = datasetMapper.selectById(run.getDatasetId());
        if (dataset == null || !workspaceId.equals(dataset.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset run not found");
        }
        if (gateTemplateKey != null && !gateTemplateKey.isBlank()) {
            TemplatePolicy template = resolveTemplatePolicy(workspaceId, run, gateTemplateKey.trim());
            policyKey = template.policyKey;
            minCoverage = template.minCoverage;
            maxFail = template.maxFail;
            maxUnknown = template.maxUnknown;
            validHours = template.validHours;
        }
        // MVP 准入政策不允许调用方放宽：只能更严格，不能低于覆盖率 90 / FAIL 0 / UNKNOWN 0。
        if (minCoverage < 90 || minCoverage > 100 || maxFail != 0 || maxUnknown != 0 || validHours < 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "gate policy cannot be weaker than minCoverage=90, maxFail=0, maxUnknown=0");
        }
        List<DatasetRunItemEntity> items = datasetRunService.itemsOf(run.getId());
        datasetRunService.enrich(run, items, false, true);
        Map<String, Object> config = run.getConfigSnapshot();

        List<Map<String, Object>> checks = new ArrayList<>();
        boolean terminal = run.getStatus() != null
                && !DatasetRunEntity.STATUS_PENDING.equals(run.getStatus())
                && !DatasetRunEntity.STATUS_RUNNING.equals(run.getStatus());
        check(checks, "RUN_TERMINAL", terminal, "运行已结束",
                terminal ? "PASS" : "BLOCKED", "run.status=" + run.getStatus());
        check(checks, "RUN_EXECUTION", isExecutable(run.getStatus()),
                "运行执行状态可用于判断", "BLOCKED", "run.status=" + run.getStatus());
        check(checks, "INCOMPLETE_COUNT", run.getIncompleteCount() != null && run.getIncompleteCount() == 0,
                "没有未执行样本", "BLOCKED", "incompleteCount=" + run.getIncompleteCount());
        check(checks, "ITEM_COUNT", run.getItemCount() != null && run.getItemCount() == items.size(),
                "样本数与受理时冻结数量一致", "BLOCKED",
                "run.itemCount=" + run.getItemCount() + ", actual=" + items.size());
        double coverage = run.getCoverage() == null ? 0 : run.getCoverage();
        check(checks, "COVERAGE", coverage >= minCoverage,
                "判断覆盖率不低于 " + minCoverage + "%", "BLOCKED",
                "coverage=" + coverage + "%");
        check(checks, "FAIL_THRESHOLD", run.getFailCount() == null || run.getFailCount() <= maxFail,
                "FAIL 数不超过 " + maxFail, "FAIL", "failCount=" + run.getFailCount());
        check(checks, "EXECUTION_ERRORS", run.getErrorCount() == null || run.getErrorCount() == 0,
                "没有执行错误", "BLOCKED", "errorCount=" + run.getErrorCount());
        check(checks, "UNKNOWN_BUDGET", run.getUnknownCount() == null || run.getUnknownCount() <= maxUnknown,
                "UNKNOWN 数不超过 " + maxUnknown, "BLOCKED", "unknownCount=" + run.getUnknownCount());
        check(checks, "REVISION_BOUND", revisionBound(run, config),
                "已绑定不可变数据集修订", "BLOCKED",
                "datasetRevisionId=" + run.getDatasetRevisionId());
        check(checks, "EVALUATOR_VERSIONS", evaluatorVersionsBound(config),
                "每个评估器都有版本与配置指纹", "BLOCKED",
                evaluatorSummary(config));
        check(checks, "JUDGE_TEMPLATES", judgeTemplatesBound(config),
                "LLM Judge 绑定可追溯模板", "BLOCKED",
                judgeTemplateSummary(config));
        addSdkResultsCheck(checks, run, config);
        addSecurityRedlineCheck(checks, run);
        addJudgeCalibrationCheck(checks, run, items, config);
        addCostBudgetCheck(checks, workspaceId, run.getDatasetId(), run, config);
        check(checks, "SAMPLE_SET_HASH", config != null && config.get("sampleSetHash") != null,
                "样本集合有冻结 hash", "BLOCKED", "sampleSetHash="
                        + (config == null ? null : config.get("sampleSetHash")));
        check(checks, "CANDIDATE_FINGERPRINT", run.getCandidateFingerprint() != null
                        && !run.getCandidateFingerprint().isBlank(),
                "候选有可追溯指纹", "BLOCKED", "candidateFingerprint=" + run.getCandidateFingerprint());
        addPromptBindingCheck(checks, run);

        boolean failed = checks.stream().anyMatch(c -> QualityGateEntity.CHECK_FAIL.equals(c.get("status")));
        boolean blocked = checks.stream().anyMatch(c -> QualityGateEntity.CHECK_BLOCKED.equals(c.get("status")));
        // 安全红线优先于普通 FAIL 聚合：违例说明这次候选不该被放行，不是"分数不够"，
        // 决策必须是 BLOCKED，不能被 failCount 的 FAIL 覆盖。
        boolean securityBlocked = checks.stream().anyMatch(c ->
                "SECURITY_REDLINE".equals(c.get("code"))
                        && QualityGateEntity.CHECK_BLOCKED.equals(c.get("status")));
        String decision = securityBlocked ? QualityGateEntity.DECISION_BLOCKED
                : failed ? QualityGateEntity.DECISION_FAIL
                : blocked ? QualityGateEntity.DECISION_BLOCKED : QualityGateEntity.DECISION_PASS;

        QualityGateEntity gate = new QualityGateEntity();
        gate.setWorkspaceId(workspaceId);
        gate.setRunId(run.getId());
        gate.setDatasetId(run.getDatasetId());
        gate.setPolicyKey(policyKey == null || policyKey.isBlank() ? POLICY_KEY : policyKey.trim());
        gate.setPolicyVersion(POLICY_VERSION);
        gate.setDecision(decision);
        gate.setSummary(summary(decision, checks, coverage, run));
        gate.setCandidateLabel(run.getCandidateLabel());
        gate.setCandidateFingerprint(run.getCandidateFingerprint());
        gate.setRunStatus(run.getStatus());
        gate.setCoverage(BigDecimal.valueOf(coverage));
        gate.setPassCount(run.getPassCount() == null ? 0 : run.getPassCount());
        gate.setFailCount(run.getFailCount() == null ? 0 : run.getFailCount());
        gate.setUnknownCount(run.getUnknownCount() == null ? 0 : run.getUnknownCount());
        gate.setErrorCount(run.getErrorCount() == null ? 0 : run.getErrorCount());
        gate.setChecksJson(toJson(checks));
        gate.setChecks(checks);
        gate.setValidUntil(LocalDateTime.now().plusHours(Math.max(1, validHours)));
        gate.setCreatedBy(createdBy);
        gate.setCreatedAt(LocalDateTime.now());
        gateMapper.insert(gate);
        log.info("quality gate {} run={} decision={} coverage={}", gate.getId(), run.getId(), decision, coverage);
        return gate;
    }

    public QualityGateEntity latest(String workspaceId, String runId) {
        datasetRunService.requireRun(workspaceId, runId);
        QualityGateEntity gate = gateMapper.selectOne(Wrappers.<QualityGateEntity>lambdaQuery()
                .eq(QualityGateEntity::getWorkspaceId, workspaceId)
                .eq(QualityGateEntity::getRunId, runId)
                .orderByDesc(QualityGateEntity::getCreatedAt)
                .orderByDesc(QualityGateEntity::getId)
                .last("LIMIT 1"));
        return hydrate(gate);
    }

    /** 同一 run 的 Gate 历史；每条都带 verification，旧结论被取代后仍可审计。 */
    public java.util.List<Map<String, Object>> history(String workspaceId, String runId) {
        java.util.List<QualityGateEntity> gates = gateMapper.selectList(Wrappers.<QualityGateEntity>lambdaQuery()
                .eq(QualityGateEntity::getWorkspaceId, workspaceId)
                .eq(QualityGateEntity::getRunId, runId)
                .orderByDesc(QualityGateEntity::getCreatedAt)
                .orderByDesc(QualityGateEntity::getId)
                .last("LIMIT 50"));
        if (gates.isEmpty()) {
            // 没有 Gate 时仍要求 run 存在，避免拿任意 runId 探测；有 Gate 时允许 run 已删除，
            // 逐条返回 RUN_MISSING，保证审计记录不会因 run 删除而不可达。
            datasetRunService.requireRun(workspaceId, runId);
            return new ArrayList<>();
        }
        DatasetRunEntity run = runOrNull(workspaceId, runId);
        LocalDateTime now = LocalDateTime.now();
        java.util.List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < gates.size(); i++) {
            QualityGateEntity gate = gates.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("gate", gate);
            row.put("verification", verification(gate, run, i > 0, now));
            rows.add(row);
        }
        return rows;
    }

    /** 供 CI/前端校验历史 Gate 是否仍可用：PASS + 未过期 + run 仍完成 + 指纹未变 + 未被更新 Gate 取代。 */
    public Map<String, Object> verify(String workspaceId, String id) {
        QualityGateEntity gate = get(workspaceId, id);
        // 与 latest()/history() 的 (created_at desc, id desc) 完全一致：只要存在严格更新的
        // 时间，或同一时间但 id 更大，就视为被取代；用 count 而不是拉全量 Gate。
        Long newer = gateMapper.selectCount(Wrappers.<QualityGateEntity>lambdaQuery()
                .eq(QualityGateEntity::getWorkspaceId, workspaceId)
                .eq(QualityGateEntity::getRunId, gate.getRunId())
                .and(w -> w.gt(QualityGateEntity::getCreatedAt, gate.getCreatedAt())
                        .or(o -> o.eq(QualityGateEntity::getCreatedAt, gate.getCreatedAt())
                                .gt(QualityGateEntity::getId, gate.getId()))));
        boolean superseded = newer != null && newer > 0;
        return verification(gate, runOrNull(workspaceId, gate.getRunId()), superseded, LocalDateTime.now());
    }

    private DatasetRunEntity runOrNull(String workspaceId, String runId) {
        try {
            return datasetRunService.requireRun(workspaceId, runId);
        } catch (EmonLensException e) {
            if (e.getErrorCode() == ErrorCode.NOT_FOUND) {
                return null;
            }
            throw e;
        }
    }

    private Map<String, Object> verification(QualityGateEntity gate, DatasetRunEntity run,
                                             boolean superseded, LocalDateTime now) {
        List<String> reasons = new ArrayList<>();
        Map<String, Object> judgeCalibration = null;
        boolean expired = gate.getValidUntil() == null || !gate.getValidUntil().isAfter(now);
        if (!QualityGateEntity.DECISION_PASS.equals(gate.getDecision())) {
            reasons.add("DECISION_" + gate.getDecision());
        }
        if (expired) {
            reasons.add("EXPIRED");
        }
        if (superseded) {
            reasons.add("SUPERSEDED");
        }
        if (run == null) {
            reasons.add("RUN_MISSING");
        } else {
            if (!DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus())) {
                reasons.add("RUN_NOT_COMPLETED:" + run.getStatus());
            }
            String gateFingerprint = gate.getCandidateFingerprint();
            if (gateFingerprint == null || gateFingerprint.isBlank()) {
                reasons.add("CANDIDATE_FINGERPRINT_MISSING");
            } else if (run.getCandidateFingerprint() == null
                    || !gateFingerprint.equals(run.getCandidateFingerprint())) {
                reasons.add("CANDIDATE_FINGERPRINT_CHANGED");
            }
            // prompt 改版（production 指针移动）后旧结论自动失效：无需重新评估即可判定过期。
            String promptExpiry = promptExpiryReason(run);
            if (promptExpiry != null) {
                reasons.add(promptExpiry);
            }
            if (DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus())) {
                List<DatasetRunItemEntity> items = datasetRunService.itemsOf(run.getId());
                datasetRunService.enrich(run, items, false, true);
                List<Map<String, Object>> checks = new ArrayList<>();
                addJudgeCalibrationCheck(checks, run, items, run.getConfigSnapshot());
                for (Map<String, Object> check : checks) {
                    if ("JUDGE_CALIBRATION".equals(check.get("code"))) {
                        judgeCalibration = new LinkedHashMap<>();
                        judgeCalibration.put("status", check.get("status"));
                        judgeCalibration.put("actual", check.get("actual"));
                        if (!QualityGateEntity.CHECK_PASS.equals(check.get("status"))) {
                            reasons.add("JUDGE_CALIBRATION:" + check.get("actual"));
                        }
                    }
                }
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("gateId", gate.getId());
        result.put("decision", gate.getDecision());
        result.put("usable", reasons.isEmpty());
        result.put("expired", expired);
        result.put("superseded", superseded);
        result.put("reasons", reasons);
        result.put("validUntil", gate.getValidUntil());
        result.put("runStatus", run == null ? null : run.getStatus());
        result.put("candidateFingerprint", gate.getCandidateFingerprint());
        result.put("verifiedAt", now);
        if (judgeCalibration != null) {
            result.put("judgeCalibration", judgeCalibration);
        }
        return result;
    }

    /** production 指针移走后返回过期原因；未绑定 prompt 或非 production 绑定返回 null。 */
    private String promptExpiryReason(DatasetRunEntity run) {
        if (run.getPromptName() == null || run.getPromptName().isBlank()
                || run.getPromptVersion() == null
                || !io.emonlens.server.engine.prompt.PromptService.LABEL_PRODUCTION.equals(
                        run.getPromptLabel())) {
            return null;
        }
        io.emonlens.server.storage.entity.PromptEntity current =
                promptService.production(run.getWorkspaceId(), run.getPromptName());
        if (current == null) {
            return "PROMPT_PRODUCTION_MISSING:" + run.getPromptName();
        }
        if (!current.getVersion().equals(run.getPromptVersion())
                || !promptService.contentHash(current).equals(run.getPromptHash())) {
            return "PROMPT_PRODUCTION_MOVED:" + run.getPromptName() + ":v"
                    + run.getPromptVersion() + "->v" + current.getVersion();
        }
        return null;
    }

    public QualityGateEntity get(String workspaceId, String id) {
        QualityGateEntity gate = gateMapper.selectById(id);
        if (gate == null || !workspaceId.equals(gate.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "quality gate not found");
        }
        return hydrate(gate);
    }

    private QualityGateEntity hydrate(QualityGateEntity gate) {
        if (gate == null) {
            return null;
        }
        gate.setChecks(parseChecks(gate.getChecksJson()));
        return gate;
    }

    private boolean isExecutable(String status) {
        return DatasetRunEntity.STATUS_COMPLETED.equals(status);
    }

    private boolean revisionBound(DatasetRunEntity run, Map<String, Object> config) {
        if (run.getDatasetRevisionId() == null || run.getDatasetRevisionId().isBlank()) {
            return false;
        }
        Object raw = config == null ? null : config.get("datasetRevision");
        if (!(raw instanceof Map)) {
            return false;
        }
        Object hash = ((Map<?, ?>) raw).get("manifestHash");
        return hash != null && !String.valueOf(hash).isBlank();
    }

    /**
     * LLM Judge 结论必须经人工盲审：全部 judge PASS/FAIL 样本审完、至少 10 条、一致率 >= 80%。
     * 无 Judge 的 run 不受影响；有 Judge 但结果不可校准直接 BLOCKED。
     */
    private void addCostBudgetCheck(List<Map<String, Object>> checks, String workspaceId,
                                    String datasetId, DatasetRunEntity run,
                                    Map<String, Object> config) {
        Object raw = config == null ? null : config.get("maxCost");
        if (raw == null || String.valueOf(raw).isBlank()) {
            return;
        }
        BigDecimal maxCost;
        try {
            maxCost = new BigDecimal(String.valueOf(raw));
        } catch (NumberFormatException e) {
            check(checks, "COST_BUDGET", false, "成本上限可解析", "BLOCKED",
                    "maxCost=" + raw);
            return;
        }
        Map<String, Object> cost = datasetRunService.costReport(
                workspaceId, datasetId, run.getId());
        if (!Boolean.TRUE.equals(cost.get("priced"))) {
            check(checks, "COST_BUDGET", false,
                    "成本不超过 " + maxCost + "（需可计价）", "BLOCKED",
                    "priced=false, reason=" + cost.get("reason"));
            return;
        }
        BigDecimal actual = new BigDecimal(String.valueOf(cost.get("cost")));
        check(checks, "COST_BUDGET", actual.compareTo(maxCost) <= 0,
                "成本不超过 " + maxCost, "FAIL",
                "cost=" + actual + " " + cost.get("currency"));
    }

    /**
     * R1-3：绑定 prompt 的 run 必须能回答“评的是哪个 prompt 版本”，且 production 指针不能已经
     * 移走——指针移走意味着现在上线的不是被评的那一版，旧结论不再能作为放行依据。
     * 未绑定 prompt 的历史 run 不受影响（不新增检查项，保持向后兼容）。
     */
    private void addPromptBindingCheck(List<Map<String, Object>> checks, DatasetRunEntity run) {
        if (run.getPromptName() == null || run.getPromptName().isBlank()) {
            return;
        }
        boolean bound = run.getPromptVersion() != null && run.getPromptHash() != null
                && !run.getPromptHash().isBlank();
        if (!bound) {
            check(checks, "PROMPT_BINDING", false, "已绑定 prompt 版本与内容指纹", "BLOCKED",
                    "prompt=" + run.getPromptName() + ", version=" + run.getPromptVersion());
            return;
        }
        if (!io.emonlens.server.engine.prompt.PromptService.LABEL_PRODUCTION.equals(run.getPromptLabel())) {
            check(checks, "PROMPT_BINDING", true, "已绑定 prompt 版本与内容指纹", "BLOCKED",
                    "prompt=" + run.getPromptName() + " v" + run.getPromptVersion()
                            + " @" + run.getPromptLabel());
            return;
        }
        io.emonlens.server.storage.entity.PromptEntity current =
                promptService.production(run.getWorkspaceId(), run.getPromptName());
        if (current == null) {
            check(checks, "PROMPT_BINDING", false, "production prompt 仍指向本次评估的版本", "BLOCKED",
                    "prompt=" + run.getPromptName() + " 没有 production 版本");
            return;
        }
        boolean same = current.getVersion().equals(run.getPromptVersion())
                && promptService.contentHash(current).equals(run.getPromptHash());
        check(checks, "PROMPT_BINDING", same, "production prompt 仍指向本次评估的版本", "BLOCKED",
                same ? "prompt=" + run.getPromptName() + " v" + run.getPromptVersion()
                        : "已评估 v" + run.getPromptVersion() + "，当前 production=v" + current.getVersion());
    }

    /**
     * R3-2 安全红线：run 里带 security 样本时，任何策略违例都直接 BLOCKED——
     * 不吃通用的 maxFail 预算，也不被"功能项通过率 100%"掩盖。安全样本证据不足（UNKNOWN/ERROR）同样 BLOCKED：
     * 无法认定安全就不算安全。
     */
    private void addSecurityRedlineCheck(List<Map<String, Object>> checks, DatasetRunEntity run) {
        Map<String, Object> evidence;
        try {
            evidence = securityEvidenceService.evidence(run);
        } catch (Exception e) {
            log.warn("security evidence failed, run={}", run.getId(), e);
            return;
        }
        Object total = evidence.get("securityTotal");
        if (!(total instanceof Number) || ((Number) total).intValue() == 0) {
            return; // 没有安全样本：不新增检查项，保持既有 run 的口径不变
        }
        int violations = evidence.get("violations") instanceof Number
                ? ((Number) evidence.get("violations")).intValue() : 0;
        int unknown = (evidence.get("unknown") instanceof Number
                ? ((Number) evidence.get("unknown")).intValue() : 0)
                + (evidence.get("error") instanceof Number
                ? ((Number) evidence.get("error")).intValue() : 0);
        String functional = "securitySamples=" + total
                + ", violations=" + violations
                + ", unknownOrError=" + unknown
                + ", functionalPassRate=" + evidence.get("functionalPassRate");
        if (violations > 0) {
            check(checks, "SECURITY_REDLINE", false, "安全红线：违例 0 条", "BLOCKED", functional);
            return;
        }
        if (unknown > 0) {
            check(checks, "SECURITY_REDLINE", false, "安全证据充分（无 UNKNOWN/ERROR）", "BLOCKED", functional);
            return;
        }
        check(checks, "SECURITY_REDLINE", true, "安全红线：违例 0 条", "BLOCKED", functional);
    }

    /** 声明了 SDK 评测器时，结果必须全部回写齐才能放行；缺一个都 BLOCKED。 */
    private void addSdkResultsCheck(List<Map<String, Object>> checks, DatasetRunEntity run,
                                    Map<String, Object> config) {
        boolean sdkPresent = evaluatorTypes(config).contains(EvaluatorEntity.TYPE_SDK);
        int open = sdkPresent ? evalTaskService.countOpenSdkTasks(run.getId()) : 0;
        check(checks, "SDK_EVALUATORS", !sdkPresent || open == 0,
                "SDK 评测结果已收齐", "BLOCKED", "openSdkTasks=" + open);
    }

    private void addJudgeCalibrationCheck(List<Map<String, Object>> checks, DatasetRunEntity run,
                                          List<DatasetRunItemEntity> items, Map<String, Object> config) {
        boolean judgePresent = evaluatorTypes(config).contains(EvaluatorEntity.TYPE_LLM_JUDGE);
        if (!judgePresent) {
            return;
        }
        Map<String, java.util.List<JudgeReviewEntity>> reviews = new LinkedHashMap<>();
        for (JudgeReviewEntity review : judgeReviewMapper.selectList(
                Wrappers.<JudgeReviewEntity>lambdaQuery().eq(JudgeReviewEntity::getRunId, run.getId()))) {
            reviews.computeIfAbsent(reviewKey(review.getItemId(), review.getEvaluatorKey(),
                    review.getEvaluatorVersion()), k -> new ArrayList<>()).add(review);
        }
        boolean doubleBlind = "DOUBLE_BLIND".equals(config == null ? null : string(config.get("reviewMode")));
        int reviewable = 0;
        int reviewed = 0;
        int agreements = 0;
        int pendingAdjudication = 0;
        java.util.Set<String> protocols = new java.util.LinkedHashSet<>();
        for (DatasetRunItemEntity item : items) {
            for (Map<String, Object> result : parseEvaluatorResults(item.getEvaluatorResultsJson())) {
                if (!EvaluatorEntity.TYPE_LLM_JUDGE.equals(string(result.get("type")))) {
                    continue;
                }
                String label = string(result.get("label"));
                if (!EvaluatorEntity.LABEL_PASS.equals(label) && !EvaluatorEntity.LABEL_FAIL.equals(label)) {
                    continue;
                }
                reviewable++;
                java.util.List<JudgeReviewEntity> itemReviews = reviews.getOrDefault(
                        reviewKey(item.getItemId(), string(result.get("evaluatorKey")),
                                intValue(result.get("evaluatorVersion"))), java.util.List.of());
                itemReviews.stream().map(JudgeReviewEntity::getProtocolVersion)
                        .filter(java.util.Objects::nonNull).forEach(protocols::add);
                JudgeReviewEntity primary = reviewSlot(itemReviews, JudgeCalibrationService.SLOT_PRIMARY);
                JudgeReviewEntity second = reviewSlot(itemReviews, JudgeCalibrationService.SLOT_SECOND);
                JudgeReviewEntity adjudicator = reviewSlot(itemReviews, JudgeCalibrationService.SLOT_ADJUDICATOR);
                boolean resolved = doubleBlind
                        ? primary != null && second != null
                            && (java.util.Objects.equals(primary.getHumanLabel(), second.getHumanLabel())
                                || adjudicator != null)
                        : primary != null;
                if (doubleBlind && primary != null && second != null
                        && !java.util.Objects.equals(primary.getHumanLabel(), second.getHumanLabel())
                        && adjudicator == null) {
                    pendingAdjudication++;
                }
                if (resolved) {
                    reviewed++;
                    String human = adjudicator != null ? adjudicator.getHumanLabel()
                            : primary.getHumanLabel();
                    if (label.equals(human)) {
                        agreements++;
                    }
                }
            }
        }
        if (reviewable == 0) {
            check(checks, "JUDGE_CALIBRATION", false,
                    "Judge 判定可人工校准", "BLOCKED", "没有 PASS/FAIL 的 Judge 结果");
            return;
        }
        double agreement = reviewed == 0 ? 0 : Math.round(agreements * 1000.0 / reviewed) / 10.0;
        String actual = "reviewed=" + reviewed + "/" + reviewable + ", agreement=" + agreement + "%"
                + (pendingAdjudication > 0 ? ", pendingAdjudication=" + pendingAdjudication : "");
        if (reviewed < reviewable) {
            check(checks, "JUDGE_CALIBRATION", false,
                    "全部 Judge 样本已人工盲审", "BLOCKED", actual);
            return;
        }
        if (protocols.size() > 1) {
            check(checks, "JUDGE_CALIBRATION", false,
                    "Judge 校准协议一致", "BLOCKED",
                    actual + ", protocols=" + protocols);
            return;
        }
        if (reviewed < JudgeCalibrationService.MIN_REVIEWED) {
            check(checks, "JUDGE_CALIBRATION", false,
                    "至少 " + JudgeCalibrationService.MIN_REVIEWED + " 条人工盲审", "BLOCKED", actual);
            return;
        }
        if (agreement < JudgeCalibrationService.MIN_AGREEMENT) {
            check(checks, "JUDGE_CALIBRATION", false,
                    "Judge 一致率不低于 " + JudgeCalibrationService.MIN_AGREEMENT + "%", "FAIL", actual);
            return;
        }
        check(checks, "JUDGE_CALIBRATION", true,
                "Judge 一致率不低于 " + JudgeCalibrationService.MIN_AGREEMENT + "%", "FAIL", actual);
    }

    private JudgeReviewEntity reviewSlot(java.util.List<JudgeReviewEntity> reviews, String slot) {
        return reviews.stream().filter(r -> slot.equals(r.getReviewerSlot())).findFirst().orElse(null);
    }

    private java.util.Set<String> evaluatorTypes(Map<String, Object> config) {
        java.util.Set<String> types = new java.util.LinkedHashSet<>();
        if (config == null) return types;
        Object raw = config.get("evaluators");
        List<?> values = raw instanceof List ? (List<?>) raw : List.of();
        if (values.isEmpty() && config.get("evaluator") instanceof Map) {
            values = List.of(config.get("evaluator"));
        }
        for (Object value : values) {
            if (value instanceof Map) {
                types.add(string(((Map<?, ?>) value).get("type")));
            }
        }
        return types;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseEvaluatorResults(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }

    private String reviewKey(String itemId, String evaluatorKey, Integer version) {
        return itemId + "|" + evaluatorKey + "|" + version;
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Integer intValue(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private boolean evaluatorVersionsBound(Map<String, Object> config) {
        if (config == null) {
            return false;
        }
        Object raw = config.get("evaluators");
        List<?> values = raw instanceof List ? (List<?>) raw : List.of();
        if (values.isEmpty()) {
            Object single = config.get("evaluator");
            values = single instanceof Map ? List.of(single) : List.of();
        }
        if (values.isEmpty()) {
            return false;
        }
        for (Object value : values) {
            if (!(value instanceof Map)) {
                return false;
            }
            Map<?, ?> evaluator = (Map<?, ?>) value;
            if (evaluator.get("version") == null || evaluator.get("fingerprint") == null
                    || String.valueOf(evaluator.get("fingerprint")).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String evaluatorSummary(Map<String, Object> config) {
        if (config == null) {
            return "no config snapshot";
        }
        Object raw = config.get("evaluators");
        if (raw instanceof List) {
            return "evaluators=" + ((List<?>) raw).size();
        }
        return "evaluator=" + (config.get("evaluator") == null ? null : "present");
    }

    /**
     * 新 run 的 LLM Judge 必须带模板 key/version/hash；历史 run 没有 judgeTemplate
     * 字段时保持兼容（不因这次升级把旧 Gate 全部打成 BLOCKED）。
     */
    private boolean judgeTemplatesBound(Map<String, Object> config) {
        for (Map<?, ?> evaluator : evaluatorValues(config)) {
            if (!"LLM_JUDGE".equals(String.valueOf(evaluator.get("type")))) {
                continue;
            }
            Object raw = evaluator.get("judgeTemplate");
            if (raw == null) {
                continue;
            }
            if (!(raw instanceof Map)) {
                return false;
            }
            Object hash = ((Map<?, ?>) raw).get("hash");
            if (hash == null || String.valueOf(hash).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String judgeTemplateSummary(Map<String, Object> config) {
        List<String> parts = new ArrayList<>();
        for (Map<?, ?> evaluator : evaluatorValues(config)) {
            if (!"LLM_JUDGE".equals(String.valueOf(evaluator.get("type")))) {
                continue;
            }
            Object raw = evaluator.get("judgeTemplate");
            if (raw instanceof Map) {
                Map<?, ?> template = (Map<?, ?>) raw;
                parts.add(String.valueOf(template.get("key")) + "@"
                        + template.get("version") + "#"
                        + String.valueOf(template.get("hash")).substring(0,
                        Math.min(8, String.valueOf(template.get("hash")).length())));
            } else {
                parts.add("legacy");
            }
        }
        return parts.isEmpty() ? "no judge evaluator" : String.join(",", parts);
    }

    private List<Map<?, ?>> evaluatorValues(Map<String, Object> config) {
        if (config == null) {
            return List.of();
        }
        Object raw = config.get("evaluators");
        List<?> values = raw instanceof List ? (List<?>) raw : List.of();
        if (values.isEmpty()) {
            Object single = config.get("evaluator");
            values = single instanceof Map ? List.of(single) : List.of();
        }
        List<Map<?, ?>> result = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map) {
                result.add((Map<?, ?>) value);
            }
        }
        return result;
    }

    private TemplatePolicy resolveTemplatePolicy(String workspaceId, DatasetRunEntity run,
                                                  String templateKey) {
        String packId = run.getDomainPackId();
        if (packId == null || packId.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "run 未绑定领域包，不能使用 gateTemplateKey");
        }
        Map<String, Object> pack = domainPackService.detail(workspaceId, packId);
        Object manifestRaw = pack.get("manifest");
        Map<String, Object> manifest = manifestRaw instanceof Map
                ? (Map<String, Object>) manifestRaw : Map.of();
        Object templatesRaw = manifest.get("gateTemplates");
        if (!(templatesRaw instanceof List)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "领域包没有 gateTemplates");
        }
        Map<String, Object> template = null;
        for (Object row : (List<?>) templatesRaw) {
            if (row instanceof Map && templateKey.equals(String.valueOf(((Map<?, ?>) row).get("key")))) {
                template = (Map<String, Object>) row;
                break;
            }
        }
        if (template == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "领域包不存在 gateTemplate: " + templateKey);
        }
        Map<String, Object> policy = template.get("policy") instanceof Map
                ? (Map<String, Object>) template.get("policy") : Map.of();
        int minCoverage = intValue(policy.get("minCoverage"), 90);
        int maxFail = intValue(policy.get("maxFail"), 0);
        int maxUnknown = intValue(policy.get("maxUnknown"), 0);
        int validHours = intValue(policy.get("validHours"), 24);
        if (minCoverage < 90 || minCoverage > 100 || maxFail != 0 || maxUnknown != 0 || validHours < 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "gateTemplate.policy 不能弱于 minCoverage=90/maxFail=0/maxUnknown=0");
        }
        String packKey = String.valueOf(pack.get("packKey"));
        String version = String.valueOf(pack.get("version"));
        return new TemplatePolicy(packKey + "@" + version + "#" + templateKey,
                minCoverage, maxFail, maxUnknown, validHours);
    }

    private int intValue(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String && !((String) value).isBlank()) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static class TemplatePolicy {
        private final String policyKey;
        private final int minCoverage;
        private final int maxFail;
        private final int maxUnknown;
        private final int validHours;

        private TemplatePolicy(String policyKey, int minCoverage, int maxFail,
                               int maxUnknown, int validHours) {
            this.policyKey = policyKey;
            this.minCoverage = minCoverage;
            this.maxFail = maxFail;
            this.maxUnknown = maxUnknown;
            this.validHours = validHours;
        }
    }

    private void check(List<Map<String, Object>> checks, String code, boolean ok,
                       String expected, String failureStatus, String actual) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("code", code);
        check.put("status", ok ? QualityGateEntity.CHECK_PASS : failureStatus);
        check.put("expected", expected);
        check.put("actual", actual);
        checks.add(check);
    }

    private String summary(String decision, List<Map<String, Object>> checks, double coverage,
                           DatasetRunEntity run) {
        if (QualityGateEntity.DECISION_PASS.equals(decision)) {
            return "PASS：覆盖率 " + coverage + "%；FAIL " + run.getFailCount()
                    + "；UNKNOWN " + run.getUnknownCount() + "；revision/version 已绑定";
        }
        List<String> reasons = new ArrayList<>();
        for (Map<String, Object> check : checks) {
            if (!QualityGateEntity.CHECK_PASS.equals(check.get("status"))) {
                reasons.add(check.get("code") + ": " + check.get("actual"));
            }
        }
        String prefix = QualityGateEntity.DECISION_FAIL.equals(decision) ? "FAIL：存在业务失败；" : "BLOCKED：证据不足；";
        String value = prefix + String.join("；", reasons);
        return value.length() <= 500 ? value : value.substring(0, 500);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "quality gate checks serialization failed");
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseChecks(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            return List.of();
        }
    }
}
