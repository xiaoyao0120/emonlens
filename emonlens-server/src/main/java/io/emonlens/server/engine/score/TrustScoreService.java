package io.emonlens.server.engine.score;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.entity.TrustScoreEntity;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.EvaluatorMapper;
import io.emonlens.server.storage.mapper.FindingMapper;
import io.emonlens.server.storage.mapper.MonitorMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.mapper.TrustScoreMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Trust Score V2：五维加权几何平均 + Critical Gate + 可解释快照。
 * 任一维度没有合格证据时该维度 score 为 null，整体 score 为 null 并标记 INSUFFICIENT_EVIDENCE；
 * 只有五维全部 COMPLETE 才计算总分。严重级别 CRITICAL 的 finding 仍优先触发 Gate。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrustScoreService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final TraceSummaryMapper traceSummaryMapper;
    private final FindingMapper findingMapper;
    private final EvalResultMapper evalResultMapper;
    private final MonitorMapper monitorMapper;
    private final EvaluatorMapper evaluatorMapper;
    private final TrustScoreMapper trustScoreMapper;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;
    private final DatasetRunEvidenceService datasetRunEvidenceService;
    private final io.emonlens.server.engine.security.SecurityEvidenceService securityEvidenceService;

    /** 重算指定 agent 的窗口评分并写快照；返回快照 */
    public TrustScoreEntity recompute(String workspaceId, String agentKey, int windowHours) {
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        List<TraceSummaryEntity> traces = traceSummaryMapper.selectList(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                        .eq(TraceSummaryEntity::getAgentKey, agentKey)
                        .ge(TraceSummaryEntity::getStartTime, since)
                        .last("LIMIT 50000"));
        List<FindingEntity> findings = findingMapper.selectList(Wrappers.<FindingEntity>lambdaQuery()
                .eq(FindingEntity::getWorkspaceId, workspaceId)
                .eq(FindingEntity::getAgentKey, agentKey)
                .ge(FindingEntity::getCreatedAt, since)
                .last("LIMIT 50000"));
        List<EvalResultEntity> legacyEvals = evalResultMapper.selectList(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getWorkspaceId, workspaceId)
                .eq(EvalResultEntity::getAgentKey, agentKey)
                .ge(EvalResultEntity::getCreatedAt, since)
                .last("LIMIT 50000"));
        List<EvalResultEntity> datasetEvals;
        try {
            datasetEvals = datasetRunEvidenceService.latestEvaluations(workspaceId, agentKey, since);
        } catch (Exception e) {
            // 评分不能因为证据适配器异常而中断；缺失证据继续按 NO_DATA 处理。
            log.warn("dataset evidence unavailable, agent={}: {}", agentKey, e.getMessage());
            datasetEvals = List.of();
        }
        List<EvalResultEntity> evals = dedupeEvidence(legacyEvals, datasetEvals);

        List<MonitorEntity> monitors = monitorMapper.selectList(Wrappers.<MonitorEntity>lambdaQuery()
                .eq(MonitorEntity::getWorkspaceId, workspaceId)
                .eq(MonitorEntity::getAgentKey, agentKey)
                .eq(MonitorEntity::getEnabled, 1));
        Long enabledEvaluators = evaluatorMapper.selectCount(Wrappers.<EvaluatorEntity>lambdaQuery()
                .eq(EvaluatorEntity::getWorkspaceId, workspaceId)
                .eq(EvaluatorEntity::getEnabled, 1));

        boolean hasTraces = !traces.isEmpty();
        boolean securityConfigured = monitors.stream().anyMatch(m -> "SECURITY".equals(m.getDimension()));
        boolean contextConfigured = monitors.stream().anyMatch(m -> "CONTEXT".equals(m.getDimension()));
        boolean outputConfigured = enabledEvaluators != null && enabledEvaluators > 0;
        List<EvalResultEntity> judgedEvals = judgedEvals(evals);

        // R3-2 收尾：安全包 run 证据作为 security 维度的专属证据源。
        // VIOLATION 直接把 security 分打到 0（红线不是"扣几分"）；
        // SECURE / INSUFFICIENT 只补充证据来源说明，不凭一次通过就抬高分数。
        Map<String, Object> securityRun = null;
        try {
            securityRun = securityEvidenceService.latestForAgent(workspaceId, agentKey, since);
        } catch (Exception e) {
            log.warn("security run evidence unavailable, agent={}: {}", agentKey, e.getMessage());
        }
        String securityRunVerdict = securityRun == null
                ? null : String.valueOf(securityRun.get("verdict"));
        Double securityScore = hasTraces && securityConfigured ? penaltyScore(findings, "SECURITY") : null;
        String securityEvidenceStatus;
        if (hasTraces && securityConfigured) {
            securityEvidenceStatus = TrustScoreEntity.EVIDENCE_COMPLETE;
        } else {
            securityEvidenceStatus = securityConfigured
                    ? TrustScoreEntity.EVIDENCE_NO_DATA : TrustScoreEntity.EVIDENCE_NOT_CONFIGURED;
        }
        String securityBasis = securityConfigured ? "security_monitors" : "no_security_monitor";
        if ("VIOLATION".equals(securityRunVerdict)) {
            securityScore = 0.0;
            securityEvidenceStatus = TrustScoreEntity.EVIDENCE_COMPLETE;
            securityBasis = (securityConfigured ? "security_monitors+" : "") + "security_run:VIOLATION";
        } else if (securityRun != null) {
            securityBasis = (securityConfigured ? "security_monitors+" : "")
                    + "security_run:" + securityRunVerdict;
            if ("SECURE".equals(securityRunVerdict)) {
                securityEvidenceStatus = TrustScoreEntity.EVIDENCE_COMPLETE;
            }
        }

        Map<String, Object> performance = dimension(
                hasTraces ? successRateScore(traces) : null,
                hasTraces ? TrustScoreEntity.EVIDENCE_COMPLETE : TrustScoreEntity.EVIDENCE_NO_DATA,
                hasTraces ? "success_rate" : "no_trace",
                traces.size(), traces.size());

        // 行为检测是内置检测链路：有 trace 即已执行；无 trace 则没有证据。
        Map<String, Object> behavior = dimension(
                hasTraces ? penaltyScore(findings, "BEHAVIOR") : null,
                hasTraces ? TrustScoreEntity.EVIDENCE_COMPLETE : TrustScoreEntity.EVIDENCE_NO_DATA,
                hasTraces ? "behavior_detectors" : "no_trace",
                traces.size(), traces.size());

        Map<String, Object> output = dimension(
                judgedEvals.isEmpty() ? null : evalScore(judgedEvals),
                judgedEvals.isEmpty()
                        ? (outputConfigured ? TrustScoreEntity.EVIDENCE_NO_DATA
                                : TrustScoreEntity.EVIDENCE_NOT_CONFIGURED)
                        : TrustScoreEntity.EVIDENCE_COMPLETE,
                "eval_pass_rate", judgedEvals.size(), evals.size());

        Map<String, Object> security = dimension(
                securityScore,
                securityEvidenceStatus,
                securityBasis,
                hasTraces ? traces.size() : 0, traces.size());

        Map<String, Object> context = dimension(
                hasTraces && contextConfigured ? penaltyScore(findings, "CONTEXT") : null,
                hasTraces && contextConfigured ? TrustScoreEntity.EVIDENCE_COMPLETE
                        : (contextConfigured ? TrustScoreEntity.EVIDENCE_NO_DATA
                                : TrustScoreEntity.EVIDENCE_NOT_CONFIGURED),
                contextConfigured ? "context_monitors" : "no_context_monitor",
                hasTraces ? traces.size() : 0, traces.size());

        List<Map<String, Object>> dimensions = List.of(performance, behavior, output, security, context);
        long complete = dimensions.stream()
                .filter(d -> TrustScoreEntity.EVIDENCE_COMPLETE.equals(d.get("evidenceStatus")))
                .count();
        int coverage = (int) Math.round(complete * 100.0 / dimensions.size());
        String evidenceStatus = complete == dimensions.size()
                ? TrustScoreEntity.EVIDENCE_COMPLETE
                : complete == 0 ? TrustScoreEntity.EVIDENCE_NONE : TrustScoreEntity.EVIDENCE_PARTIAL;

        EmonLensProperties.Score weights = properties.getScore();
        Double overall = complete == dimensions.size() ? overall(performance, behavior, output, security, context, weights) : null;

        List<String> gates = criticalGates(findings);
        String status = !gates.isEmpty() ? TrustScoreEntity.STATUS_CRITICAL
                : overall == null ? TrustScoreEntity.STATUS_INSUFFICIENT_EVIDENCE
                : overall >= 85 ? TrustScoreEntity.STATUS_TRUSTED : TrustScoreEntity.STATUS_AT_RISK;

        TrustScoreEntity snapshot = new TrustScoreEntity();
        snapshot.setWorkspaceId(workspaceId);
        snapshot.setScopeType(TrustScoreEntity.SCOPE_AGENT);
        snapshot.setScopeId(agentKey);
        snapshot.setTimeWindow(windowHours + "h");
        snapshot.setScore(overall == null ? null
                : BigDecimal.valueOf(overall).setScale(2, RoundingMode.HALF_UP));
        snapshot.setStatus(status);
        snapshot.setScoreVersion(TrustScoreEntity.SCORE_VERSION_EVIDENCE);
        snapshot.setEvidenceStatus(evidenceStatus);
        snapshot.setCoverage(BigDecimal.valueOf(coverage));
        Map<String, Object> dimensionMap = new java.util.LinkedHashMap<>();
        dimensionMap.put("performance", performance);
        dimensionMap.put("behavior", behavior);
        dimensionMap.put("output", output);
        dimensionMap.put("security", security);
        dimensionMap.put("context", context);
        snapshot.setDimensions(toJson(dimensionMap));
        snapshot.setCriticalGates(toJson(gates));
        snapshot.setComputedAt(LocalDateTime.now());
        try {
            trustScoreMapper.insert(snapshot);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            // 同一毫秒并发重算：uk_score 兜底，错开 1ms 再写一次
            snapshot.setComputedAt(snapshot.getComputedAt().plusNanos(1_000_000));
            trustScoreMapper.insert(snapshot);
        }
        log.info("trust score computed, agent={}, score={}, status={}, gates={}",
                agentKey, snapshot.getScore(), status, gates.size());
        return snapshot;
    }

    /** 兜底重算：对窗口内有 trace 的 agent 逐个重算（score-runner 周期调用） */
    public int recomputeRecent(int windowHours) {
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        List<TraceSummaryEntity> recent = traceSummaryMapper.selectList(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .ge(TraceSummaryEntity::getStartTime, since)
                        .orderByDesc(TraceSummaryEntity::getStartTime)
                        .last("LIMIT 500"));
        Map<String, TraceSummaryEntity> byAgent = new HashMap<>();
        for (TraceSummaryEntity trace : recent) {
            // 多租户下不同工作空间可以有同名 agentKey，不能只按 agentKey 去重
            byAgent.putIfAbsent(trace.getWorkspaceId() + "|" + trace.getAgentKey(), trace);
        }
        int recomputed = 0;
        for (TraceSummaryEntity trace : byAgent.values()) {
            try {
                recompute(trace.getWorkspaceId(), trace.getAgentKey(), windowHours);
                recomputed++;
            } catch (Exception e) {
                log.error("score recompute failed, workspace={}, agent={}",
                        trace.getWorkspaceId(), trace.getAgentKey(), e);
            }
        }
        return recomputed;
    }

    public TrustScoreEntity latest(String workspaceId, String agentKey, String window) {
        return trustScoreMapper.selectOne(Wrappers.<TrustScoreEntity>lambdaQuery()
                .eq(TrustScoreEntity::getWorkspaceId, workspaceId)
                .eq(TrustScoreEntity::getScopeType, TrustScoreEntity.SCOPE_AGENT)
                .eq(TrustScoreEntity::getScopeId, agentKey)
                .eq(TrustScoreEntity::getTimeWindow, window)
                .eq(TrustScoreEntity::getScoreVersion, TrustScoreEntity.SCORE_VERSION_EVIDENCE)
                .orderByDesc(TrustScoreEntity::getComputedAt)
                .last("LIMIT 1"));
    }

    /** 各 agent 最新快照（overview 用） */
    public List<TrustScoreEntity> latestPerAgent(String workspaceId) {
        List<TrustScoreEntity> latest = trustScoreMapper.selectList(Wrappers.<TrustScoreEntity>lambdaQuery()
                .eq(TrustScoreEntity::getWorkspaceId, workspaceId)
                .eq(TrustScoreEntity::getScopeType, TrustScoreEntity.SCOPE_AGENT)
                .eq(TrustScoreEntity::getScoreVersion, TrustScoreEntity.SCORE_VERSION_EVIDENCE)
                .orderByDesc(TrustScoreEntity::getComputedAt)
                .last("LIMIT 500"));
        Map<String, TrustScoreEntity> byAgent = new HashMap<>();
        for (TrustScoreEntity score : latest) {
            byAgent.putIfAbsent(score.getScopeId(), score);
        }
        return List.copyOf(byAgent.values());
    }

    /**
     * 同一 evaluator+trace 只保留最新一条判定，避免 legacy 结果与 dataset 结果重复计数。
     * dataset 证据追加在 legacy 之后；createdAt 相同时 dataset 胜出。
     */
    private List<EvalResultEntity> dedupeEvidence(List<EvalResultEntity> legacy,
                                                   List<EvalResultEntity> dataset) {
        Map<String, EvalResultEntity> latest = new LinkedHashMap<>();
        List<EvalResultEntity> all = new ArrayList<>();
        if (legacy != null) {
            all.addAll(legacy);
        }
        if (dataset != null) {
            all.addAll(dataset);
        }
        for (EvalResultEntity candidate : all) {
            String key = evidenceKey(candidate);
            EvalResultEntity current = latest.get(key);
            if (current == null || isNewer(candidate, current)) {
                latest.put(key, candidate);
            }
        }
        return new ArrayList<>(latest.values());
    }

    private String evidenceKey(EvalResultEntity result) {
        // evaluatorId 是 legacy 与 dataset 证据共有的稳定主键；旧数据缺 id 时才退回 key。
        String evaluator = result.getEvaluatorId() != null && !result.getEvaluatorId().isBlank()
                ? result.getEvaluatorId() : String.valueOf(result.getEvaluatorKey());
        if (result.getTraceId() == null || result.getTraceId().isBlank()) {
            // 没有 trace 的历史结果无法与 dataset 结果配对，保留独立键，避免被误合并。
            return evaluator + "|id:" + result.getId();
        }
        return evaluator + "|" + result.getTraceId();
    }

    private boolean isNewer(EvalResultEntity candidate, EvalResultEntity current) {
        if (candidate.getCreatedAt() == null) {
            return false;
        }
        if (current.getCreatedAt() == null) {
            return true;
        }
        return !candidate.getCreatedAt().isBefore(current.getCreatedAt());
    }

    private double successRateScore(List<TraceSummaryEntity> traces) {
        long ok = traces.stream().filter(t -> t.getErrorCount() == null || t.getErrorCount() == 0).count();
        return HUNDRED.doubleValue() * ok / traces.size();
    }

    private List<EvalResultEntity> judgedEvals(List<EvalResultEntity> evals) {
        // pass 率分母不含 EVAL_UNAVAILABLE / N/A / UNKNOWN / ERROR（红线：降级不得当作合格）
        return evals.stream()
                .filter(e -> EvaluatorEntity.LABEL_PASS.equals(e.getLabel())
                        || EvaluatorEntity.LABEL_FAIL.equals(e.getLabel()))
                .collect(Collectors.toList());
    }

    private double evalScore(List<EvalResultEntity> judged) {
        long pass = judged.stream()
                .filter(e -> EvaluatorEntity.LABEL_PASS.equals(e.getLabel())).count();
        return HUNDRED.doubleValue() * pass / judged.size();
    }

    private double penaltyScore(List<FindingEntity> findings, String dimension) {
        double penalty = 0;
        for (FindingEntity finding : findings) {
            if (!dimension.equals(finding.getDimension())) {
                continue;
            }
            if ("CRITICAL".equals(finding.getSeverity())) {
                penalty += 40;
            } else if ("HIGH".equals(finding.getSeverity())) {
                penalty += 15;
            } else if ("WARNING".equals(finding.getSeverity())) {
                penalty += 5;
            }
        }
        if (penalty <= 0) {
            return 100;
        }
        // 软饱和而非线性归零：分数随罚分平滑逼近 0 但保持可分度（几何加权下维度 0 会让总分直接归零），
        // 同时保留对问题量的区分：1 条 HIGH≈84、7 条≈43、50 条≈9.6
        return 100.0 / (1.0 + penalty / 80.0);
    }

    private Map<String, Object> dimension(Double score, String evidenceStatus, String basis,
                                          int observed, int total) {
        Map<String, Object> dimension = new java.util.LinkedHashMap<>();
        dimension.put("score", score);
        dimension.put("evidenceStatus", evidenceStatus);
        dimension.put("basis", basis);
        dimension.put("observed", observed);
        dimension.put("total", total);
        return dimension;
    }

    private Double overall(Map<String, Object> performance, Map<String, Object> behavior,
                           Map<String, Object> output, Map<String, Object> security,
                           Map<String, Object> context, EmonLensProperties.Score weights) {
        double overall = Math.pow(dimensionScore(performance), weights.getWeightPerformance())
                * Math.pow(dimensionScore(behavior), weights.getWeightBehavior())
                * Math.pow(dimensionScore(output), weights.getWeightOutput())
                * Math.pow(dimensionScore(security), weights.getWeightSecurity())
                * Math.pow(dimensionScore(context), weights.getWeightContext());
        return Math.min(100, overall);
    }

    private List<String> criticalGates(List<FindingEntity> findings) {
        return findings.stream()
                .filter(f -> "CRITICAL".equals(f.getSeverity()))
                .map(f -> f.getType() + ":" + f.getDetectorId())
                .distinct()
                .collect(Collectors.toList());
    }

    private static double dimensionScore(Map<String, Object> dimension) {
        Object score = dimension.get("score");
        if (!(score instanceof Number)) {
            throw new IllegalStateException("dimension score missing for complete evidence");
        }
        return ((Number) score).doubleValue();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.copy()
                    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.ALWAYS)
                    .writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

}
