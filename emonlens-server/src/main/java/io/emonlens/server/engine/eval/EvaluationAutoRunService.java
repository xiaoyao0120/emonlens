package io.emonlens.server.engine.eval;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.engine.event.EventService;
import io.emonlens.server.engine.event.EventType;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * trace 完成后的自动评估编排：采样决策 -> 逐个启用 evaluator 执行 -> 落库 -> 事件（EVAL_RESULT_READY + SCORE_CHANGED）。
 * 自动评估可经 emonlens.eval.auto-run-enabled 关闭；手动评估走 /evaluators/{id}/test 与 /evaluations/run。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationAutoRunService {

    private final EmonLensProperties properties;
    private final SysSettingService sysSettingService;
    private final SamplingService samplingService;
    private final EvaluationRuleService ruleService;
    private final EvaluatorService evaluatorService;
    private final EvaluationRunService evaluationRunService;
    private final TraceSummaryMapper traceSummaryMapper;
    private final EvalResultMapper evalResultMapper;
    private final EventService eventService;
    private final ObjectMapper objectMapper;

    public void runForTrace(String workspaceId, String traceId) {
        // 试点环境强制关闭自动评估：即使 DB 运行时设置仍为 true，也不能与手动试跑同时产生费用
        if (properties.getDatasetRun().isPilotMode()) {
            return;
        }
        SysSettingService.EvalSampling db = sysSettingService.getEvalSampling();
        boolean enabled = db != null && db.getAutoRunEnabled() != null ? db.getAutoRunEnabled() : properties.getEval().isAutoRunEnabled();
        if (!enabled) {
            return;
        }
        TraceSummaryEntity summary = traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .eq(TraceSummaryEntity::getTraceId, traceId));
        if (summary == null || !TraceSummaryEntity.STATUS_COMPLETED.equals(summary.getStatus())) {
            return;
        }
        // R2-1：工作空间配置了启用规则时，走「规则 scope + filter + 采样/预算 + 幂等」路径；
        // 没有规则则维持原来的全局采样率，升级不改变既有行为。
        if (ruleService.anyEnabled(workspaceId)) {
            int produced;
            try {
                produced = ruleService.runForTrace(workspaceId, summary, false);
            } catch (Exception e) {
                log.error("rule based auto eval failed, trace={}", traceId, e);
                produced = 0;
            }
            if (summary.getSessionId() != null && !summary.getSessionId().isBlank()) {
                try {
                    // 会话仍活跃时这里不会评估，等空闲扫描补评（SESSION scope）
                    ruleService.runForSession(workspaceId, summary.getSessionId());
                } catch (Exception e) {
                    log.error("session rule eval failed, session={}", summary.getSessionId(), e);
                }
            }
            if (produced > 0) {
                enqueueEvalEvents(workspaceId, traceId, summary.getAgentKey());
            }
            return;
        }
        if (!samplingService.shouldAutoEvaluate(summary)) {
            return;
        }
        List<EvaluatorEntity> evaluators = evaluatorService.listEnabled(workspaceId);
        boolean anyResult = false;
        for (EvaluatorEntity evaluator : evaluators) {
            try {
                int attempt = nextAttempt(evaluator.getId(), traceId);
                EvalResultEntity result = evaluationRunService.evaluate(evaluator, traceId, attempt, true);
                anyResult = true;
                log.info("auto eval done, evaluator={}, trace={}, label={}",
                        evaluator.getEvaluatorKey(), traceId, result.getLabel());
            } catch (Exception e) {
                log.error("auto eval failed, evaluator={}, trace={}", evaluator.getId(), traceId, e);
            }
        }
        if (anyResult) {
            enqueueEvalEvents(workspaceId, traceId, summary.getAgentKey());
        }
    }

    /** EVAL_RESULT_READY + SCORE_CHANGED：只带 traceId/agentKey，不带正文。 */
    private void enqueueEvalEvents(String workspaceId, String traceId, String agentKey) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("traceId", traceId);
        fields.put("agentKey", agentKey);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(fields);
        } catch (Exception e) {
            payload = "{}";
        }
        eventService.enqueue(EventType.EVAL_RESULT_READY, workspaceId, agentKey, payload);
        eventService.enqueue(EventType.SCORE_CHANGED, workspaceId, agentKey, payload);
    }

    /** 同 evaluator+trace 的下一次尝试序号（受 maxAttempt 封顶） */
    private int nextAttempt(String evaluatorId, String traceId) {
        EvalResultEntity last = evalResultMapper.selectOne(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getEvaluatorId, evaluatorId)
                .eq(EvalResultEntity::getTraceId, traceId)
                .orderByDesc(EvalResultEntity::getAttempt)
                .last("LIMIT 1"));
        return last == null ? 1 : Math.min(last.getAttempt() + 1, properties.getEval().getMaxAttempt());
    }
}
