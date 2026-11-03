package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.EvaluationRewindService;
import io.emonlens.server.engine.eval.EvaluationRuleService;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvalRewindJobEntity;
import io.emonlens.server.storage.entity.EvalRuleHitEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.EvalRewindJobMapper;
import io.emonlens.server.storage.mapper.EvalRuleHitMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;

/**
 * R2-1 评估规则 v2 验收：
 * 1. 规则过滤（agentKeys）不匹配时不评估；
 * 2. 错误 trace 由 errorForced 无视采样率强制评估（sampleRate=0）；
 * 3. 同一 trace 重复完成 → 命中账本幂等，不重复产生结果；
 * 4. 多条规则命中同一批评估器 → 归并去重（只跑一次，第二条记 DEDUPED）；
 * 5. 小时预算用尽 → 记 SKIPPED_BUDGET 且不评估；
 * 6. SESSION scope：多轮会话聚合评估一次，结果带 scope=SESSION，并有独立汇总；
 * 7. Rewind：按 filter 补评历史 trace，重跑不再产生新结果（幂等），可暂停/恢复。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-eval-rule;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.eval.ignore-session-idle=true",
        // 调度器拉长到 1h：测试要自己控制 Rewind/会话补评的推进节奏，避免后台线程抢作业。
        // 注意不能关 emonlens.worker.enabled——ingest 微批线程共用该开关，关掉 trace 永远不会完成。
        "emonlens.eval.rewind-poll-ms=3600000",
        "emonlens.eval.session-sweep-ms=3600000",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class EvaluationRuleFlowTest extends AbstractIntegrationTest {

    private static final String WS = "ws-default";

    @Resource
    private EvaluationRuleService ruleService;
    @Resource
    private EvaluationRewindService rewindService;
    @Resource
    private EvalResultMapper evalResultMapper;
    @Resource
    private EvalRuleHitMapper hitMapper;
    @Resource
    private TraceSummaryMapper traceSummaryMapper;
    @Resource
    private EvalRewindJobMapper jobMapper;

    // ---------- 辅助 ----------

    private String evaluator(String key, String passWhen) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", key, "type", "EXPRESSION",
                "spec", Map.of("type", "EXPRESSION", "passWhen", passWhen))));
        assertThat(response.path("code").asInt()).as("evaluator: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    private JsonNode upsertRule(String key, Map<String, Object> body) throws Exception {
        Map<String, Object> payload = new HashMap<>(body);
        payload.put("ruleKey", key);
        return readTree(postApi("/api/v1/eval-rules", payload));
    }

    private String ingestTrace(String traceId, String agentKey, boolean withError, String sessionId)
            throws Exception {
        Map<String, Object> root = span(agentKey, traceId, "root", null, "agent", "invoke_agent",
                null, null, 1, 5);
        if (sessionId != null) {
            root.put("sessionId", sessionId);
        }
        Map<String, Object> llm = span(agentKey, traceId, "llm-1", "root", "llm", "call_model",
                null, "gpt-test", 2, 4);
        if (withError) {
            llm.put("status", "ERROR");
            llm.put("errorType", "Timeout");
        }
        if (sessionId != null) {
            llm.put("sessionId", sessionId);
        }
        post("/api/v1/ingest/events", List.of(root, llm));
        await(() -> {
            try {
                return readTree(get("/api/v1/traces/" + traceId)).path("data").path("summary");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, node -> "COMPLETED".equals(node.path("status").asText()), ASYNC_TIMEOUT_MS);
        return traceId;
    }

    private TraceSummaryEntity summaryOf(String traceId) {
        return traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, WS)
                .eq(TraceSummaryEntity::getTraceId, traceId));
    }

    private List<EvalResultEntity> resultsOf(String traceId) {
        return evalResultMapper.selectList(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getWorkspaceId, WS)
                .eq(EvalResultEntity::getTraceId, traceId));
    }

    private EvalRuleHitEntity hitOf(String ruleKey, String subject) {
        return hitMapper.selectOne(Wrappers.<EvalRuleHitEntity>lambdaQuery()
                .eq(EvalRuleHitEntity::getRuleKey, ruleKey)
                .eq(EvalRuleHitEntity::getSubjectId, subject));
    }

    // ---------- 1. 过滤 / 强制采样 / 预算 ----------

    @Test
    void ruleFiltersAndErrorForcedSampling() throws Exception {
        String exactId = evaluator("rule-filter-exact", "error_count >= 0");
        JsonNode created = upsertRule("filter-agent-a", Map.of(
                "scope", "TRACE",
                "filters", Map.of("agentKeys", List.of("rule-agent-a")),
                "evaluatorIds", List.of(exactId),
                "sampleRate", 0.0,
                "errorForced", true));
        assertThat(created.path("code").asInt()).as("create rule: %s", created).isZero();
        assertThat(created.path("data").path("filters").path("agentKeys").toString())
                .contains("rule-agent-a");

        // agent 不匹配：不评估，也不落命中
        String other = ingestTrace("rule-other-agent", "rule-agent-b", true, null);
        assertThat(ruleService.runForTrace(WS, summaryOf(other), false)).isZero();
        assertThat(resultsOf(other)).isEmpty();
        assertThat(hitOf("filter-agent-a", other)).isNull();

        // 匹配 + 错误 trace + sampleRate=0：errorForced 生效，产生结论
        String matched = ingestTrace("rule-error-trace", "rule-agent-a", true, null);
        assertThat(ruleService.runForTrace(WS, summaryOf(matched), false)).isEqualTo(1);
        List<EvalResultEntity> results = resultsOf(matched);
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getRuleKey()).isEqualTo("filter-agent-a");
        assertThat(results.get(0).getScope()).isEqualTo("TRACE");
        EvalRuleHitEntity hit = hitOf("filter-agent-a", matched);
        assertThat(hit).isNotNull();
        assertThat(hit.getDecision()).isEqualTo(EvalRuleHitEntity.DECISION_FORCED);

        // 幂等：同一 trace 再来一次不产生新结果，命中账本仍只有一条
        assertThat(ruleService.runForTrace(WS, summaryOf(matched), false)).isZero();
        assertThat(resultsOf(matched)).hasSize(1);
        assertThat(hitMapper.selectCount(Wrappers.<EvalRuleHitEntity>lambdaQuery()
                .eq(EvalRuleHitEntity::getRuleKey, "filter-agent-a")
                .eq(EvalRuleHitEntity::getSubjectId, matched))).isEqualTo(1);
    }

    @Test
    void hourlyBudgetBlocksFurtherEvaluation() throws Exception {
        String exactId = evaluator("rule-budget-exact", "error_count >= 0");
        upsertRule("budget-one", Map.of(
                "scope", "TRACE",
                "filters", Map.of("agentKeys", List.of("budget-agent")),
                "evaluatorIds", List.of(exactId),
                "sampleRate", 1.0,
                "maxPerHour", 1));

        String first = ingestTrace("rule-budget-1", "budget-agent", false, null);
        assertThat(ruleService.runForTrace(WS, summaryOf(first), false)).isEqualTo(1);

        String second = ingestTrace("rule-budget-2", "budget-agent", false, null);
        assertThat(ruleService.runForTrace(WS, summaryOf(second), false)).isZero();
        assertThat(resultsOf(second)).isEmpty();
        assertThat(hitOf("budget-one", second).getDecision())
                .isEqualTo(EvalRuleHitEntity.DECISION_SKIPPED_BUDGET);
    }

    // ---------- 2. 多规则归并去重 ----------

    @Test
    void multipleRulesSharingEvaluatorMergeIntoOneRun() throws Exception {
        String exactId = evaluator("rule-merge-exact", "error_count >= 0");
        upsertRule("merge-first", Map.of("scope", "TRACE",
                "filters", Map.of("agentKeys", List.of("merge-agent")),
                "evaluatorIds", List.of(exactId), "sampleRate", 1.0, "priority", 10));
        upsertRule("merge-second", Map.of("scope", "TRACE",
                "filters", Map.of("agentKeys", List.of("merge-agent")),
                "evaluatorIds", List.of(exactId), "sampleRate", 1.0, "priority", 20));

        String traceId = ingestTrace("rule-merge-trace", "merge-agent", false, null);
        int produced = ruleService.runForTrace(WS, summaryOf(traceId), false);
        assertThat(produced).as("同一评估器只应评估一次").isEqualTo(1);
        assertThat(resultsOf(traceId)).hasSize(1);
        assertThat(resultsOf(traceId).get(0).getRuleKey()).isEqualTo("merge-first");
        assertThat(hitOf("merge-second", traceId).getDecision())
                .isEqualTo(EvalRuleHitEntity.DECISION_DEDUPED);
    }

    // ---------- 3. SESSION scope ----------

    @Test
    void sessionScopeEvaluatesAggregateOnceAndReportsSeparately() throws Exception {
        String exactId = evaluator("rule-session-exact", "candidate_output != null");
        JsonNode created = upsertRule("session-qa", Map.of(
                "scope", "SESSION",
                "filters", Map.of("agentKeys", List.of("session-agent")),
                "evaluatorIds", List.of(exactId),
                "sampleRate", 1.0,
                "sessionIdleMinutes", 1));
        assertThat(created.path("data").path("scope").asText()).isEqualTo("SESSION");

        String sessionId = "sess-rule-1";
        ingestTrace("rule-session-t1", "session-agent", false, sessionId);
        ingestTrace("rule-session-t2", "session-agent", false, sessionId);

        int produced = ruleService.runForSession(WS, sessionId);
        assertThat(produced).as("会话聚合评估一次").isEqualTo(1);
        List<EvalResultEntity> sessionResults = evalResultMapper.selectList(
                Wrappers.<EvalResultEntity>lambdaQuery()
                        .eq(EvalResultEntity::getWorkspaceId, WS)
                        .eq(EvalResultEntity::getScope, "SESSION"));
        assertThat(sessionResults).hasSize(1);
        assertThat(sessionResults.get(0).getSessionId()).isEqualTo(sessionId);
        assertThat(sessionResults.get(0).getRuleKey()).isEqualTo("session-qa");
        assertThat(sessionResults.get(0).getTraceId()).isEqualTo("session:" + sessionId);

        // 幂等：再跑一次不产生第二条
        assertThat(ruleService.runForSession(WS, sessionId)).isZero();
        assertThat(evalResultMapper.selectCount(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getWorkspaceId, WS)
                .eq(EvalResultEntity::getScope, "SESSION"))).isEqualTo(1);

        // 独立汇总：SESSION 与 TRACE 分区统计
        JsonNode summary = readTree(get("/api/v1/eval-rules/summary?windowHours=24"));
        assertThat(summary.path("code").asInt()).isZero();
        assertThat(summary.path("data").path("byScope").path("SESSION").path("total").asInt())
                .isGreaterThanOrEqualTo(1);
        assertThat(summary.path("data").path("hits").has("SAMPLED")).isTrue();
    }

    // ---------- 4. Rewind ----------

    @Test
    void rewindEvaluatesHistoryOnceAndIsPausable() throws Exception {
        String exactId = evaluator("rule-rewind-exact", "error_count >= 0");
        upsertRule("rewind-backfill", Map.of("scope", "TRACE",
                "filters", Map.of("agentKeys", List.of("rewind-agent")),
                "evaluatorIds", List.of(exactId), "sampleRate", 1.0));

        ingestTrace("rule-rewind-1", "rewind-agent", false, null);
        ingestTrace("rule-rewind-2", "rewind-agent", false, null);

        JsonNode created = readTree(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/eval-rules/rewind-backfill/rewinds")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "rewind-job-1")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "filters", Map.of("agentKeys", List.of("rewind-agent")),
                                        "ratePerMinute", 60))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(created.path("code").asInt()).as("rewind: %s", created).isZero();
        String jobId = created.path("data").path("id").asText();
        assertThat(created.path("data").path("total").asInt()).isGreaterThanOrEqualTo(2);

        // 同 Idempotency-Key 返回同一作业
        JsonNode again = readTree(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/eval-rules/rewind-backfill/rewinds")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "rewind-job-1")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of())))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(again.path("data").path("id").asText()).isEqualTo(jobId);

        // 暂停：作业不再推进
        postApi("/api/v1/eval-rules/rewinds/" + jobId + "/pause", Map.of());
        assertThat(rewindService.runOnce()).isZero();
        EvalRewindJobEntity paused = jobMapper.selectById(jobId);
        assertThat(paused.getStatus()).isEqualTo(EvalRewindJobEntity.STATUS_PAUSED);
        assertThat(paused.getProcessed()).isZero();

        // 恢复：推进到完成，且不重复评估已评估过的 trace
        postApi("/api/v1/eval-rules/rewinds/" + jobId + "/resume", Map.of());
        for (int i = 0; i < 20; i++) {
            rewindService.runOnce();
            EvalRewindJobEntity job = jobMapper.selectById(jobId);
            if (EvalRewindJobEntity.STATUS_COMPLETED.equals(job.getStatus())) {
                break;
            }
        }
        EvalRewindJobEntity done = jobMapper.selectById(jobId);
        assertThat(done.getStatus()).isEqualTo(EvalRewindJobEntity.STATUS_COMPLETED);
        assertThat(done.getEvaluated()).isGreaterThanOrEqualTo(2);
        assertThat(resultsOf("rule-rewind-1")).hasSize(1);
        assertThat(resultsOf("rule-rewind-2")).hasSize(1);

        // 跑完的 trace 不重复评：再建一个作业，全部落 deduped
        JsonNode second = readTree(mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/v1/eval-rules/rewind-backfill/rewinds")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "rewind-job-2")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "filters", Map.of("agentKeys", List.of("rewind-agent")),
                                        "ratePerMinute", 600))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String secondId = second.path("data").path("id").asText();
        for (int i = 0; i < 20; i++) {
            rewindService.runOnce();
            EvalRewindJobEntity job = jobMapper.selectById(secondId);
            if (EvalRewindJobEntity.STATUS_COMPLETED.equals(job.getStatus())) {
                break;
            }
        }
        EvalRewindJobEntity secondDone = jobMapper.selectById(secondId);
        assertThat(secondDone.getEvaluated()).isZero();
        assertThat(secondDone.getDeduped()).isGreaterThanOrEqualTo(2);
        assertThat(resultsOf("rule-rewind-1")).hasSize(1);
    }

    // ---------- 5. 规则接口约束 ----------

    @Test
    void ruleApiRejectsInvalidInput() throws Exception {
        JsonNode reserved = upsertRule("summary", Map.of("evaluatorIds", List.of("x")));
        assertThat(reserved.path("code").asInt()).isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());

        String exactId = evaluator("rule-invalid-exact", "error_count >= 0");
        JsonNode badRate = upsertRule("bad-rate", Map.of(
                "evaluatorIds", List.of(exactId), "sampleRate", 5));
        assertThat(badRate.path("code").asInt()).isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());

        JsonNode noEvaluator = upsertRule("no-evaluator", Map.of("evaluatorIds", List.of()));
        assertThat(noEvaluator.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());

        JsonNode unknownRule = readTree(get("/api/v1/eval-rules/does-not-exist"));
        assertThat(unknownRule.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.NOT_FOUND.getCode());

        // 禁用后不再参与匹配
        upsertRule("toggle-rule", Map.of("evaluatorIds", List.of(exactId),
                "filters", Map.of("agentKeys", List.of("toggle-agent")), "sampleRate", 1.0));
        JsonNode disabled = readTree(postApi("/api/v1/eval-rules/toggle-rule/disable", Map.of()));
        assertThat(disabled.path("data").path("enabled").asBoolean()).isFalse();
        JsonNode enabled = readTree(postApi("/api/v1/eval-rules/toggle-rule/enable", Map.of()));
        assertThat(enabled.path("data").path("enabled").asBoolean()).isTrue();
    }
}
