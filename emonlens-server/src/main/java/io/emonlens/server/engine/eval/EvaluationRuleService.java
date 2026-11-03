package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvalRewindJobEntity;
import io.emonlens.server.storage.entity.EvalRuleEntity;
import io.emonlens.server.storage.entity.EvalRuleHitEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.EvalRuleHitMapper;
import io.emonlens.server.storage.mapper.EvalRuleMapper;
import io.emonlens.server.storage.mapper.EvaluatorMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 评估规则 v2（R2-1）：用规则对象取代「全局采样率」。
 *
 * <p>一条规则 = scope（TRACE / SESSION）+ 过滤条件 + 评估器 + 采样与预算。语义要点：</p>
 * <ul>
 *   <li><b>向后兼容</b>：工作空间没有启用规则时，调用方仍走旧的全局采样，升级不改变既有行为。</li>
 *   <li><b>幂等</b>：命中账本唯一键 (rule_key, subject_id)——同一条规则对同一条 trace / 同一个会话
 *       只评估一次；Rewind 重跑、重复完成事件、多规则同时命中都不会重复花钱。</li>
 *   <li><b>归并去重</b>：一次 trace 完成里多条规则命中时，按 priority 顺序消费评估器，
 *       同一评估器只跑一次，后命中的规则记 DEDUPED。</li>
 *   <li><b>错误强制</b>：errorForced 的规则对错误 trace 无视采样率，但仍受小时预算约束。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationRuleService {

    public static final int MAX_EVALUATORS = 5;
    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9_.:-]{1,100}");

    private final EvalRuleMapper ruleMapper;
    private final EvalRuleHitMapper hitMapper;
    private final EvalResultMapper evalResultMapper;
    private final EvaluatorMapper evaluatorMapper;
    private final TraceSummaryMapper traceSummaryMapper;
    private final EvaluationRunService evaluationRunService;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;

    // ==================== 请求体 ====================

    @Data
    public static class RuleRequest {
        private String ruleKey;
        private String name;
        private Boolean enabled;
        /** TRACE / SESSION（SPAN 预留：当前按 TRACE 口径处理 span 级内容） */
        private String scope;
        private Map<String, Object> filters;
        private List<String> evaluatorIds;
        private Double sampleRate;
        private Boolean errorForced;
        private Integer maxPerHour;
        private Integer sessionIdleMinutes;
        private Integer priority;
    }

    @Data
    public static class RewindRequest {
        private String ruleKey;
        private Map<String, Object> filters;
        private Integer ratePerMinute;
        private Integer limit;
    }

    // ==================== 规则维护 ====================

    public Map<String, Object> upsert(String workspaceId, RuleRequest request, String createdBy) {
        if (request == null || request.getRuleKey() == null || request.getRuleKey().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "ruleKey is required");
        }
        String key = request.getRuleKey().trim();
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "ruleKey 只允许字母数字与 _.:-，最长 100 字符");
        }
        if (List.of("rewinds", "summary").contains(key)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "ruleKey 不能使用保留字: " + key);
        }
        String scope = normalizeScope(request.getScope());
        List<String> evaluatorIds = normalizeEvaluators(workspaceId, request.getEvaluatorIds());
        double sampleRate = request.getSampleRate() == null ? 1.0 : request.getSampleRate();
        if (sampleRate < 0 || sampleRate > 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "sampleRate 只允许 0..1");
        }
        int maxPerHour = request.getMaxPerHour() == null ? 0 : Math.max(0, request.getMaxPerHour());
        int idle = request.getSessionIdleMinutes() == null ? 5 : Math.max(1, request.getSessionIdleMinutes());
        if (EvalRuleEntity.SCOPE_SESSION.equals(scope) && idle > 24 * 60) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "sessionIdleMinutes 不能超过 1440");
        }
        Map<String, Object> filters = request.getFilters() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getFilters());
        validateFilters(filters);

        EvalRuleEntity existing = findRule(workspaceId, key);
        EvalRuleEntity entity = existing == null ? new EvalRuleEntity() : existing;
        entity.setWorkspaceId(workspaceId);
        entity.setRuleKey(key);
        entity.setName(request.getName() == null || request.getName().isBlank()
                ? key : request.getName().trim());
        entity.setEnabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled());
        entity.setScope(scope);
        entity.setFiltersJson(toJson(filters));
        entity.setEvaluatorIdsJson(toJson(evaluatorIds));
        entity.setSampleRate(sampleRate);
        entity.setErrorForced(request.getErrorForced() == null ? Boolean.TRUE : request.getErrorForced());
        entity.setMaxPerHour(maxPerHour);
        entity.setSessionIdleMinutes(idle);
        entity.setPriority(request.getPriority() == null ? 100 : request.getPriority());
        entity.setUpdatedAt(LocalDateTime.now());
        if (existing == null) {
            entity.setCreatedBy(createdBy);
            entity.setCreatedAt(LocalDateTime.now());
            ruleMapper.insert(entity);
        } else {
            ruleMapper.updateById(entity);
        }
        return toMap(hydrate(entity));
    }

    public List<Map<String, Object>> list(String workspaceId) {
        List<EvalRuleEntity> rules = ruleMapper.selectList(Wrappers.<EvalRuleEntity>lambdaQuery()
                .eq(EvalRuleEntity::getWorkspaceId, workspaceId)
                .orderByAsc(EvalRuleEntity::getPriority)
                .orderByAsc(EvalRuleEntity::getRuleKey));
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvalRuleEntity rule : rules) {
            out.add(toMap(hydrate(rule)));
        }
        return out;
    }

    public Map<String, Object> get(String workspaceId, String ruleKey) {
        return toMap(requireRule(workspaceId, ruleKey));
    }

    /** 关闭规则即停止实时评估；历史命中与结果保留（可审计）。 */
    public Map<String, Object> setEnabled(String workspaceId, String ruleKey, boolean enabled) {
        EvalRuleEntity rule = requireRule(workspaceId, ruleKey);
        rule.setEnabled(enabled);
        rule.setUpdatedAt(LocalDateTime.now());
        ruleMapper.updateById(rule);
        return toMap(hydrate(rule));
    }

    public void delete(String workspaceId, String ruleKey) {
        EvalRuleEntity rule = requireRule(workspaceId, ruleKey);
        ruleMapper.deleteById(rule.getId());
    }

    // ==================== 实时链路 ====================

    /** 工作空间是否有启用规则：没有则调用方走旧的全局采样（向后兼容）。 */
    public boolean anyEnabled(String workspaceId) {
        Long count = ruleMapper.selectCount(Wrappers.<EvalRuleEntity>lambdaQuery()
                .eq(EvalRuleEntity::getWorkspaceId, workspaceId)
                .eq(EvalRuleEntity::getEnabled, true));
        return count != null && count > 0;
    }

    /** 有启用 SESSION 规则的工作空间（调度器按工作空间扫空闲会话）。 */
    public List<String> workspacesWithSessionRules() {
        List<EvalRuleEntity> rules = ruleMapper.selectList(Wrappers.<EvalRuleEntity>lambdaQuery()
                .eq(EvalRuleEntity::getEnabled, true)
                .eq(EvalRuleEntity::getScope, EvalRuleEntity.SCOPE_SESSION));
        Set<String> workspaces = new LinkedHashSet<>();
        for (EvalRuleEntity rule : rules) {
            workspaces.add(rule.getWorkspaceId());
        }
        return new ArrayList<>(workspaces);
    }

    /**
     * trace 完成后的规则评估。
     *
     * @param forced 强制评估（Rewind / 手动补评）：跳过采样率，仍受小时预算与幂等约束
     * @return 实际产生的评估结果条数
     */
    public int runForTrace(String workspaceId, TraceSummaryEntity summary, boolean forced) {
        List<EvalRuleEntity> rules = matchRules(workspaceId, summary, EvalRuleEntity.SCOPE_TRACE);
        if (rules.isEmpty()) {
            return 0;
        }
        Set<String> evaluated = new LinkedHashSet<>();
        int produced = 0;
        for (EvalRuleEntity rule : rules) {
            produced += evaluateRuleOnTrace(rule, summary, evaluated, forced);
        }
        return produced;
    }

    private int evaluateRuleOnTrace(EvalRuleEntity rule, TraceSummaryEntity summary,
                                    Set<String> evaluatedEvaluatorIds, boolean forced) {
        String subject = summary.getTraceId();
        if (alreadyEvaluated(rule.getRuleKey(), subject)) {
            return 0;
        }
        List<EvaluatorEntity> pending = new ArrayList<>();
        for (EvaluatorEntity evaluator : evaluatorsOf(rule)) {
            if (!evaluatedEvaluatorIds.contains(evaluator.getId())) {
                pending.add(evaluator);
            }
        }
        if (pending.isEmpty()) {
            recordHit(rule, subject, EvalRuleHitEntity.DECISION_DEDUPED, 0, "同一批次的其它规则已评估这些评估器");
            return 0;
        }
        boolean errorForced = Boolean.TRUE.equals(rule.getErrorForced())
                && summary.getErrorCount() != null && summary.getErrorCount() > 0;
        String decision = EvalRuleHitEntity.DECISION_SAMPLED;
        if (!forced && !errorForced) {
            double rate = rule.getSampleRate() == null ? 1.0 : rule.getSampleRate();
            if (rate <= 0 || Math.random() >= rate) {
                return 0;
            }
        }
        if (!reserveBudget(rule)) {
            recordHit(rule, subject, EvalRuleHitEntity.DECISION_SKIPPED_BUDGET, 0,
                    "小时预算已用尽（maxPerHour=" + rule.getMaxPerHour() + "）");
            return 0;
        }
        if (errorForced) {
            decision = EvalRuleHitEntity.DECISION_FORCED;
        }
        int count = 0;
        for (EvaluatorEntity evaluator : pending) {
            try {
                int attempt = nextAttempt(evaluator.getId(), subject);
                EvalResultEntity result = evaluationRunService.evaluate(evaluator, subject, attempt, true);
                result.setScope(EvalRuleEntity.SCOPE_TRACE);
                result.setRuleKey(rule.getRuleKey());
                evalResultMapper.updateById(result);
                evaluatedEvaluatorIds.add(evaluator.getId());
                count++;
                log.info("rule eval done, rule={}, evaluator={}, trace={}, label={}",
                        rule.getRuleKey(), evaluator.getEvaluatorKey(), subject, result.getLabel());
            } catch (Exception e) {
                log.error("rule eval failed, rule={}, evaluator={}, trace={}",
                        rule.getRuleKey(), evaluator.getId(), subject, e);
            }
        }
        recordHit(rule, subject, decision, count, count == 0 ? "评估执行失败" : null);
        return count;
    }

    /** SESSION scope：会话空闲超过阈值后，对聚合视图（多轮摘录）评估一次。 */
    public int runForSession(String workspaceId, String sessionId) {
        List<TraceSummaryEntity> members = traceSummaryMapper.selectList(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                        .eq(TraceSummaryEntity::getSessionId, sessionId)
                        .orderByAsc(TraceSummaryEntity::getStartTime));
        if (members.isEmpty()) {
            return 0;
        }
        LocalDateTime lastSpan = members.stream().map(TraceSummaryEntity::getLastSpanAt)
                .filter(Objects::nonNull).max(LocalDateTime::compareTo)
                .orElse(members.get(members.size() - 1).getEndTime());
        int produced = 0;
        for (EvalRuleEntity rule : matchSessionRules(workspaceId, members.get(members.size() - 1))) {
            if (alreadyEvaluated(rule.getRuleKey(), sessionId)) {
                continue;
            }
            long idleMinutes = lastSpan == null ? Long.MAX_VALUE
                    : java.time.Duration.between(lastSpan, LocalDateTime.now()).toMinutes();
            int threshold = rule.getSessionIdleMinutes() == null ? 5 : rule.getSessionIdleMinutes();
            if (!forceIdle() && idleMinutes < threshold) {
                // 会话仍活跃：不落命中，等空闲扫描再来
                continue;
            }
            if (!reserveBudget(rule)) {
                recordHit(rule, sessionId, EvalRuleHitEntity.DECISION_SKIPPED_BUDGET, 0,
                        "小时预算已用尽（maxPerHour=" + rule.getMaxPerHour() + "）");
                continue;
            }
            produced += evaluateRuleOnSession(rule, sessionId, members);
        }
        return produced;
    }

    /** 空闲扫描：让 SESSION 规则在会话真正空闲后补评（由调度器周期调用）。 */
    public int sweepIdleSessions(String workspaceId) {
        List<EvalRuleEntity> sessionRules = ruleMapper.selectList(Wrappers.<EvalRuleEntity>lambdaQuery()
                .eq(EvalRuleEntity::getWorkspaceId, workspaceId)
                .eq(EvalRuleEntity::getEnabled, true)
                .eq(EvalRuleEntity::getScope, EvalRuleEntity.SCOPE_SESSION));
        if (sessionRules.isEmpty()) {
            return 0;
        }
        int minIdle = sessionRules.stream().map(r -> r.getSessionIdleMinutes() == null ? 5 : r.getSessionIdleMinutes())
                .min(Integer::compareTo).orElse(5);
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(Math.max(1, minIdle));
        List<TraceSummaryEntity> candidates = traceSummaryMapper.selectList(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                        .isNotNull(TraceSummaryEntity::getSessionId)
                        .lt(TraceSummaryEntity::getLastSpanAt, threshold)
                        .orderByDesc(TraceSummaryEntity::getLastSpanAt)
                        .last("LIMIT 200"));
        Set<String> sessions = new LinkedHashSet<>();
        for (TraceSummaryEntity row : candidates) {
            sessions.add(row.getSessionId());
        }
        int produced = 0;
        for (String sessionId : sessions) {
            try {
                produced += runForSession(workspaceId, sessionId);
            } catch (Exception e) {
                log.error("session eval failed, session={}", sessionId, e);
            }
        }
        return produced;
    }

    private int evaluateRuleOnSession(EvalRuleEntity rule, String sessionId,
                                      List<TraceSummaryEntity> members) {
        List<EvaluatorEntity> evaluators = evaluatorsOf(rule);
        if (evaluators.isEmpty()) {
            return 0;
        }
        EvaluationSample sample = sessionSample(sessionId, members);
        // 会话级结果用 session:<id> 作为主体：不与任何真实 trace 的 (evaluator, trace, attempt) 冲突
        String subject = "session:" + sessionId;
        int count = 0;
        for (EvaluatorEntity evaluator : evaluators) {
            try {
                EvalResultEntity result = evaluationRunService.evaluateSample(evaluator, sample);
                result.setWorkspaceId(sampleWorkspace(members));
                result.setEvaluatorId(evaluator.getId());
                result.setEvaluatorKey(evaluator.getEvaluatorKey());
                result.setEvaluatorVersion(evaluator.getVersion());
                result.setAttempt(1);
                result.setTraceId(truncate(subject, 64));
                result.setScope(EvalRuleEntity.SCOPE_SESSION);
                result.setSessionId(sessionId);
                result.setRuleKey(rule.getRuleKey());
                result.setAgentKey(sampleAgentKey(members));
                result.setCreatedAt(LocalDateTime.now());
                evalResultMapper.insert(result);
                count++;
            } catch (Exception e) {
                log.error("session eval failed, rule={}, session={}, evaluator={}",
                        rule.getRuleKey(), sessionId, evaluator.getId(), e);
            }
        }
        recordHit(rule, sessionId, EvalRuleHitEntity.DECISION_SAMPLED, count,
                count == 0 ? "评估执行失败" : null);
        return count;
    }

    /** 会话聚合视图：按时间顺序把每轮的用户输入与助手输出拼成多轮摘录（不含正文权限外的字段）。 */
    private EvaluationSample sessionSample(String sessionId, List<TraceSummaryEntity> members) {
        StringBuilder transcript = new StringBuilder();
        int turn = 0;
        for (TraceSummaryEntity member : members) {
            if (!TraceSummaryEntity.STATUS_COMPLETED.equals(member.getStatus())) {
                continue;
            }
            turn++;
            transcript.append("第").append(turn).append("轮用户：")
                    .append(nullSafe(member.getInputPreview())).append('\n')
                    .append("第").append(turn).append("轮助手：")
                    .append(nullSafe(member.getOutputPreview())).append('\n');
        }
        EvaluationSample sample = new EvaluationSample();
        sample.setItemId("session:" + sessionId);
        sample.setInput("会话 " + sessionId + " 共 " + turn + " 轮");
        sample.setCandidateOutput(transcript.toString());
        sample.setCandidateComplete(true);
        sample.setContentStatus("COMPLETE");
        sample.getContents().put("completion", transcript.toString());
        sample.getContentCaptureModes().put("completion", "FULL");
        sample.getContentTruncated().put("completion", false);
        sample.setTraceDigest(truncate(transcript.toString(), 4000));
        return sample;
    }

    // ==================== 匹配与预算 ====================

    private List<EvalRuleEntity> matchRules(String workspaceId, TraceSummaryEntity summary, String scope) {
        List<EvalRuleEntity> rules = ruleMapper.selectList(Wrappers.<EvalRuleEntity>lambdaQuery()
                .eq(EvalRuleEntity::getWorkspaceId, workspaceId)
                .eq(EvalRuleEntity::getEnabled, true)
                .eq(EvalRuleEntity::getScope, scope)
                .orderByAsc(EvalRuleEntity::getPriority)
                .orderByAsc(EvalRuleEntity::getRuleKey));
        List<EvalRuleEntity> matched = new ArrayList<>();
        for (EvalRuleEntity rule : rules) {
            if (matches(hydrate(rule).getFilters(), summary)) {
                matched.add(hydrate(rule));
            }
        }
        return matched;
    }

    private List<EvalRuleEntity> matchSessionRules(String workspaceId, TraceSummaryEntity any) {
        return matchRules(workspaceId, any, EvalRuleEntity.SCOPE_SESSION);
    }

    /** 过滤条件全部满足才命中；未声明的条件不限制（省略即全量）。 */
    boolean matches(Map<String, Object> filters, TraceSummaryEntity summary) {
        if (filters == null || filters.isEmpty()) {
            return true;
        }
        if (!inList(filters.get("agentKeys"), summary.getAgentKey())) {
            return false;
        }
        if (!inList(filters.get("agentVersions"), summary.getAgentVersion())) {
            return false;
        }
        if (!inList(filters.get("environments"), summary.getEnvironment())) {
            return false;
        }
        if (!inList(filters.get("promptNames"), summary.getPromptName())) {
            return false;
        }
        Long minDuration = asLong(filters.get("minDurationMs"));
        if (minDuration != null && (summary.getDurationMs() == null || summary.getDurationMs() < minDuration)) {
            return false;
        }
        Long maxDuration = asLong(filters.get("maxDurationMs"));
        if (maxDuration != null && (summary.getDurationMs() == null || summary.getDurationMs() > maxDuration)) {
            return false;
        }
        if (Boolean.TRUE.equals(filters.get("onlyErrors"))
                && (summary.getErrorCount() == null || summary.getErrorCount() == 0)) {
            return false;
        }
        return true;
    }

    private boolean inList(Object raw, String value) {
        if (!(raw instanceof List) || ((List<?>) raw).isEmpty()) {
            return true;
        }
        if (value == null) {
            return false;
        }
        for (Object item : (List<?>) raw) {
            if (value.equals(String.valueOf(item))) {
                return true;
            }
        }
        return false;
    }

    private void validateFilters(Map<String, Object> filters) {
        for (String key : List.of("agentKeys", "agentVersions", "environments", "promptNames")) {
            Object raw = filters.get(key);
            if (raw != null && !(raw instanceof List)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "filters." + key + " 必须是数组");
            }
        }
        for (String key : List.of("minDurationMs", "maxDurationMs")) {
            Object raw = filters.get(key);
            if (raw != null && asLong(raw) == null) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "filters." + key + " 必须是整数");
            }
        }
        Long min = asLong(filters.get("minDurationMs"));
        Long max = asLong(filters.get("maxDurationMs"));
        if (min != null && max != null && min > max) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "minDurationMs 不能大于 maxDurationMs");
        }
    }

    /** 小时预算：按命中账本计数，进程重启后仍然有效（不做内存计数）。 */
    private boolean reserveBudget(EvalRuleEntity rule) {
        int limit = rule.getMaxPerHour() == null ? 0 : rule.getMaxPerHour();
        if (limit <= 0) {
            return true;
        }
        Long used = hitMapper.selectCount(Wrappers.<EvalRuleHitEntity>lambdaQuery()
                .eq(EvalRuleHitEntity::getRuleKey, rule.getRuleKey())
                .ge(EvalRuleHitEntity::getCreatedAt, LocalDateTime.now().minusHours(1))
                .in(EvalRuleHitEntity::getDecision,
                        EvalRuleHitEntity.DECISION_SAMPLED, EvalRuleHitEntity.DECISION_FORCED));
        return used == null || used < limit;
    }

    private boolean alreadyEvaluated(String ruleKey, String subject) {
        Long hit = hitMapper.selectCount(Wrappers.<EvalRuleHitEntity>lambdaQuery()
                .eq(EvalRuleHitEntity::getRuleKey, ruleKey)
                .eq(EvalRuleHitEntity::getSubjectId, subject));
        return hit != null && hit > 0;
    }

    private void recordHit(EvalRuleEntity rule, String subject, String decision, int evalCount,
                           String detail) {
        EvalRuleHitEntity hit = new EvalRuleHitEntity();
        hit.setWorkspaceId(rule.getWorkspaceId());
        hit.setRuleKey(rule.getRuleKey());
        hit.setScope(rule.getScope());
        hit.setSubjectId(subject);
        hit.setDecision(decision);
        hit.setEvalCount(evalCount);
        hit.setDetail(truncate(detail, 500));
        hit.setCreatedAt(LocalDateTime.now());
        try {
            hitMapper.insert(hit);
        } catch (DuplicateKeyException e) {
            // 并发重复命中：账本已存在，等价于已评估，不需要额外处理
            log.debug("rule hit already recorded, rule={}, subject={}", rule.getRuleKey(), subject);
        }
    }

    // ==================== 汇总 ====================

    /** 按 scope 与规则汇总最近窗口的评估结论（SESSION 有独立口径，便于报告分区展示）。 */
    public Map<String, Object> summary(String workspaceId, String ruleKey, int windowHours) {
        LocalDateTime since = LocalDateTime.now().minusHours(Math.max(1, windowHours));
        List<EvalResultEntity> results = evalResultMapper.selectList(
                Wrappers.<EvalResultEntity>lambdaQuery()
                        .eq(EvalResultEntity::getWorkspaceId, workspaceId)
                        .eq(ruleKey != null && !ruleKey.isBlank(), EvalResultEntity::getRuleKey, ruleKey)
                        .ge(EvalResultEntity::getCreatedAt, since));
        Map<String, Object> byScope = new LinkedHashMap<>();
        for (String scope : List.of(EvalRuleEntity.SCOPE_TRACE, EvalRuleEntity.SCOPE_SESSION)) {
            byScope.put(scope, aggregate(results.stream()
                    .filter(r -> scope.equals(scopeOf(r))).collect(java.util.stream.Collectors.toList())));
        }
        Map<String, Object> byRule = new LinkedHashMap<>();
        Set<String> rules = new LinkedHashSet<>();
        results.forEach(r -> {
            if (r.getRuleKey() != null) {
                rules.add(r.getRuleKey());
            }
        });
        for (String key : rules) {
            byRule.put(key, aggregate(results.stream()
                    .filter(r -> key.equals(r.getRuleKey()))
                    .collect(java.util.stream.Collectors.toList())));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("windowHours", windowHours);
        payload.put("ruleKey", ruleKey);
        payload.put("total", aggregate(results));
        payload.put("byScope", byScope);
        payload.put("byRule", byRule);
        List<EvalRuleHitEntity> hits = hitMapper.selectList(Wrappers.<EvalRuleHitEntity>lambdaQuery()
                .eq(EvalRuleHitEntity::getWorkspaceId, workspaceId)
                .ge(EvalRuleHitEntity::getCreatedAt, since));
        Map<String, Integer> decisions = new LinkedHashMap<>();
        for (EvalRuleHitEntity hit : hits) {
            decisions.merge(hit.getDecision(), 1, Integer::sum);
        }
        payload.put("hits", decisions);
        return payload;
    }

    private String scopeOf(EvalResultEntity result) {
        return result.getScope() == null ? EvalRuleEntity.SCOPE_TRACE : result.getScope();
    }

    private Map<String, Object> aggregate(List<EvalResultEntity> results) {
        int pass = 0;
        int fail = 0;
        int unknown = 0;
        int failedToExecute = 0;
        for (EvalResultEntity result : results) {
            String label = result.getLabel();
            if (EvaluatorEntity.LABEL_PASS.equals(label)) {
                pass++;
            } else if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
                fail++;
            } else if (EvaluatorEntity.LABEL_ERROR.equals(label)
                    || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)) {
                failedToExecute++;
            } else {
                unknown++;
            }
        }
        int judged = pass + fail;
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("total", results.size());
        map.put("pass", pass);
        map.put("fail", fail);
        map.put("unknown", unknown);
        map.put("notExecuted", failedToExecute);
        map.put("passRate", judged == 0 ? null : Math.round(pass * 1000.0 / judged) / 10.0);
        return map;
    }

    // ==================== 内部工具 ====================

    private EvalRuleEntity requireRule(String workspaceId, String ruleKey) {
        EvalRuleEntity rule = findRule(workspaceId, ruleKey);
        if (rule == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "eval rule not found: " + ruleKey);
        }
        return hydrate(rule);
    }

    private EvalRuleEntity findRule(String workspaceId, String ruleKey) {
        return ruleMapper.selectOne(Wrappers.<EvalRuleEntity>lambdaQuery()
                .eq(EvalRuleEntity::getWorkspaceId, workspaceId)
                .eq(EvalRuleEntity::getRuleKey, ruleKey));
    }

    private EvalRuleEntity hydrate(EvalRuleEntity rule) {
        if (rule == null) {
            return null;
        }
        rule.setFilters(parseMap(rule.getFiltersJson()));
        rule.setEvaluatorIds(parseList(rule.getEvaluatorIdsJson()));
        return rule;
    }

    private List<EvaluatorEntity> evaluatorsOf(EvalRuleEntity rule) {
        List<EvaluatorEntity> evaluators = new ArrayList<>();
        for (String id : rule.getEvaluatorIds() == null ? List.<String>of() : rule.getEvaluatorIds()) {
            EvaluatorEntity evaluator = evaluatorMapper.selectById(id);
            if (evaluator != null && rule.getWorkspaceId().equals(evaluator.getWorkspaceId())) {
                evaluators.add(evaluator);
            }
        }
        return evaluators;
    }

    private List<String> normalizeEvaluators(String workspaceId, List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluatorIds 至少一个");
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(raw));
        if (ids.size() > MAX_EVALUATORS) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "单条规则最多 " + MAX_EVALUATORS + " 个评估器");
        }
        for (String id : ids) {
            EvaluatorEntity evaluator = evaluatorMapper.selectById(id);
            if (evaluator == null || !workspaceId.equals(evaluator.getWorkspaceId())) {
                throw new EmonLensException(ErrorCode.NOT_FOUND, "evaluator not found: " + id);
            }
        }
        return ids;
    }

    private String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return EvalRuleEntity.SCOPE_TRACE;
        }
        String value = scope.trim().toUpperCase();
        if (!List.of(EvalRuleEntity.SCOPE_TRACE, EvalRuleEntity.SCOPE_SESSION).contains(value)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "scope 只支持 TRACE / SESSION（SPAN 口径复用 TRACE 的正文评估）");
        }
        return value;
    }

    private int nextAttempt(String evaluatorId, String traceId) {
        EvalResultEntity last = evalResultMapper.selectOne(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getEvaluatorId, evaluatorId)
                .eq(EvalResultEntity::getTraceId, traceId)
                .orderByDesc(EvalResultEntity::getAttempt)
                .last("LIMIT 1"));
        return last == null ? 1 : Math.min(last.getAttempt() + 1, properties.getEval().getMaxAttempt());
    }

    private boolean forceIdle() {
        return properties.getEval().isIgnoreSessionIdle();
    }

    private String sampleWorkspace(List<TraceSummaryEntity> members) {
        return members.get(0).getWorkspaceId();
    }

    private String sampleAgentKey(List<TraceSummaryEntity> members) {
        return members.get(members.size() - 1).getAgentKey();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private Map<String, Object> toMap(EvalRuleEntity rule) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", rule.getId());
        map.put("ruleKey", rule.getRuleKey());
        map.put("name", rule.getName());
        map.put("enabled", rule.getEnabled());
        map.put("scope", rule.getScope());
        map.put("filters", rule.getFilters());
        map.put("evaluatorIds", rule.getEvaluatorIds());
        map.put("sampleRate", rule.getSampleRate());
        map.put("errorForced", rule.getErrorForced());
        map.put("maxPerHour", rule.getMaxPerHour());
        map.put("sessionIdleMinutes", rule.getSessionIdleMinutes());
        map.put("priority", rule.getPriority());
        map.put("createdBy", rule.getCreatedBy());
        map.put("createdAt", rule.getCreatedAt());
        map.put("updatedAt", rule.getUpdatedAt());
        return map;
    }

    private static Long asLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof String) {
            try {
                return Long.parseLong(((String) value).trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "rule json 序列化失败");
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private List<String> parseList(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }
}
