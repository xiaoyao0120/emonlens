package io.emonlens.server;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.common.EmonLensException;
import io.emonlens.server.engine.eval.DatasetRunService;
import io.emonlens.server.engine.eval.JudgeCalibrationService;
import io.emonlens.server.engine.eval.RunTokenBudgetStore;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.CurrentUser;
import java.util.Set;
import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvalAttemptEntity;
import io.emonlens.server.storage.entity.EvalTaskEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.QualityGateEntity;
import io.emonlens.server.storage.entity.UserEntity;
import io.emonlens.server.storage.entity.UserWorkspaceEntity;
import io.emonlens.server.storage.entity.WorkspaceEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.EvalAttemptMapper;
import io.emonlens.server.storage.mapper.EvalTaskMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.DatasetRunSlotMapper;
import io.emonlens.server.storage.mapper.DatasetRevisionMapper;
import io.emonlens.server.storage.entity.DatasetRunSlotEntity;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.JudgeReviewMapper;
import io.emonlens.server.storage.mapper.QualityGateMapper;
import io.emonlens.server.storage.mapper.UserMapper;
import io.emonlens.server.storage.mapper.UserWorkspaceMapper;
import io.emonlens.server.storage.mapper.WorkspaceMapper;

/**
 * 用户试点最小重构（M1-M4）端到端验收：
 * 参考匹配/缺失、参考不泄漏、幂等/并发拒绝、运行后删除样本不影响旧报告、
 * 中断恢复、9 PASS + 1 ERROR 统计、正文权限裁剪与跨空间隔离。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-pilot;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.pilot-mode=true",
        "emonlens.dataset-run.poll-interval-ms=200",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class DatasetPilotFlowTest extends AbstractIntegrationTest {

    private static final String EXACT_MATCH_SPEC =
            "reference_match.exact(candidate_output, expected_output)";

    @Resource
    private FakeLlm fakeLlm;
    @Resource
    private DatasetRunService datasetRunService;
    @Resource
    private DatasetRunMapper datasetRunMapper;
    @Resource
    private DatasetRunSlotMapper datasetRunSlotMapper;
    @Resource
    private DatasetRevisionMapper datasetRevisionMapper;
    @Resource
    private RunTokenBudgetStore runTokenBudgetStore;
    @Resource
    private JudgeCalibrationService judgeCalibrationService;
    @Resource
    private io.emonlens.server.engine.eval.DatasetHealthService datasetHealthService;
    @Resource
    private DatasetItemMapper datasetItemMapper;
    @Resource
    private DatasetRunItemMapper datasetRunItemMapper;
    @Resource
    private QualityGateMapper qualityGateMapper;
    @Resource
    private EvalResultMapper evalResultMapper;
    @Resource
    private WorkspaceMapper workspaceMapper;
    @Resource
    private UserMapper userMapper;
    @Resource
    private UserWorkspaceMapper userWorkspaceMapper;
    @Resource
    private JudgeReviewMapper judgeReviewMapper;
    @Resource
    private EvalTaskMapper evalTaskMapper;
    @Resource
    private EvalAttemptMapper evalAttemptMapper;
    @Resource
    private io.emonlens.server.storage.mapper.AccessTokenMapper accessTokenMapper;

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    static class FakeLlm implements LlmClient {
        final Map<String, String> outputs = new ConcurrentHashMap<>();
        final List<String[]> calls = new CopyOnWriteArrayList<>();
        final CountDownLatch block = new CountDownLatch(1);
        volatile boolean blocking = false;
        volatile boolean failGeneration = false;
        final java.util.Set<String> failInputs = java.util.concurrent.ConcurrentHashMap.newKeySet();
        volatile String judgeOutput = "{\"score\":8,\"confidence\":0.9,\"reason\":\"ok\"}";
        final java.util.Queue<String> pairwiseOutputs = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final Map<String, String> auditOutputs = new ConcurrentHashMap<>();
        final java.util.Queue<String> autoRubricOutputs = new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.Queue<String> semanticDedupOutputs = new java.util.concurrent.ConcurrentLinkedQueue<>();
        volatile String pairwiseOutput = "{\"winner\":\"TIE\",\"confidence\":0.5,\"reason\":\"same\"}";

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model, double temperature) {
            calls.add(new String[] { systemPrompt, userPrompt });
            if (blocking) {
                try {
                    block.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (systemPrompt.contains("semantic rubric comparator")) {
                String queued = semanticDedupOutputs.poll();
                return new ChatReply(queued == null
                        ? "{\"duplicate\":false,\"reason\":\"different\"}" : queued, 2, 3);
            }
            if (systemPrompt.contains("auto-rubric generator")) {
                String queued = autoRubricOutputs.poll();
                return new ChatReply(queued == null ? "[]" : queued, 5, 9);
            }
            if (systemPrompt.contains("audit judge")) {
                for (Map.Entry<String, String> entry : auditOutputs.entrySet()) {
                    if (userPrompt.contains(entry.getKey())) {
                        return new ChatReply(entry.getValue(), 3, 4);
                    }
                }
                return new ChatReply("{\"violation\":false,\"reason\":\"ok\"}", 3, 4);
            }
            if (systemPrompt.contains("pairwise evaluator")) {
                String queued = pairwiseOutputs.poll();
                return new ChatReply(queued == null ? pairwiseOutput : queued, 9, 7);
            }
            if (systemPrompt.contains("You are an evaluator")) {
                return new ChatReply(judgeOutput, 11, 22);
            }
            if (failGeneration || failInputs.contains(userPrompt)) {
                throw new RuntimeException("fake generation failure");
            }
            String output = outputs.getOrDefault(userPrompt, "ECHO:" + userPrompt);
            return new ChatReply(output, 7, 13);
        }
    }

    @Test
    void referenceExactMatchMissingReferenceAndNoLeak() throws Exception {
        fakeLlm.calls.clear();
        String datasetId = createDataset("pilot-ref");
        fakeLlm.outputs.put("q-ok", "正确答案");
        fakeLlm.outputs.put("q-wrong", "错误答案");
        fakeLlm.outputs.put("q-noref", "任意输出");
        addItem(datasetId, "q-ok", "正确答案", null);
        addItem(datasetId, "q-wrong", "正确答案", null);
        addItem(datasetId, "q-noref", null, null);

        String evaluatorId = createEvaluator("pilot-ref-exact", "EXPRESSION", exactMatchSpec());

        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "key-ref-1", 202);
        String runId = accepted.path("data").path("runId").asText();
        assertThat(runId).isNotEmpty();

        JsonNode detail = awaitRun(datasetId, runId);
        JsonNode run = detail.path("data").path("run");
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("passCount").asInt()).isEqualTo(1);
        assertThat(run.path("failCount").asInt()).isEqualTo(1);
        assertThat(run.path("unknownCount").asInt()).isEqualTo(1);
        assertThat(run.path("errorCount").asInt()).isZero();
        assertThat(run.path("passRate").asDouble()).isEqualTo(50.0);
        assertThat(run.path("coverage").asDouble()).isEqualTo(66.7);

        Map<String, JsonNode> byInput = itemsByInput(detail);
        for (JsonNode item : detail.path("data").path("items")) {
            // REPLAY 的合成 agent 归属必须随快照落库，否则 Trust Score 无法归因。
            assertThat(item.path("sampleSnapshot").path("agentKey").asText()).isEqualTo("dataset-replay");
        }
        assertThat(byInput.get("q-ok").path("label").asText()).isEqualTo("PASS");
        assertThat(byInput.get("q-wrong").path("label").asText()).isEqualTo("FAIL");
        assertThat(byInput.get("q-noref").path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(byInput.get("q-noref").path("resultDetails").path("reasonCode").asText())
                .isEqualTo("REFERENCE_MISSING");

        // 参考答案只进入评估器，不进入 REPLAY 生成请求
        for (String[] call : fakeLlm.calls) {
            if (!call[0].contains("You are an evaluator")) {
                assertThat(call[1]).doesNotContain("正确答案");
            }
        }
        // 本轮数据集判定不写 et_eval_result
        assertThat(evalResultMapper.selectCount(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getEvaluatorKey, "pilot-ref-exact"))).isZero();
        // 配置快照不含凭据
        assertThat(run.path("configSnapshot").path("generation").toString()).doesNotContain("apiKey");
        assertThat(run.path("configSnapshot").path("sampleSetHash").asText()).isNotEmpty();
        assertThat(run.path("configSnapshot").path("evaluator").path("fingerprint").asText()).isNotEmpty();
    }

    @Test
    void expressionEvaluatorRejectsReflectiveMethodChains() throws Exception {
        // 表达式是可配置文本：方法调用只放行白名单（字符串/集合只读操作 + reference_match.exact），
        // getClass()/classLoader 这类反射链必须被拒绝成 EXPRESSION_ERROR，而不是被求值
        String datasetId = createDataset("pilot-spel-safe");
        fakeLlm.outputs.put("q-spel", "ok");
        addItem(datasetId, "q-spel", "ok", null);
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "EXPRESSION");
        spec.put("passWhen", "candidate_output.getClass().getName().length() > 0");
        String evaluatorId = createEvaluator("pilot-spel-reflect", "EXPRESSION", spec);

        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "spel-safe-1", 202);
        String runId = accepted.path("data").path("runId").asText();
        JsonNode detail = awaitRun(datasetId, runId);
        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("ERROR");
        assertThat(item.path("resultDetails").path("reasonCode").asText()).isEqualTo("EXPRESSION_ERROR");

        // 白名单内的方法（length/contains）仍正常求值
        Map<String, Object> safeSpec = new HashMap<>();
        safeSpec.put("type", "EXPRESSION");
        safeSpec.put("passWhen", "candidate_output.length() > 0 && candidate_output.contains('o')");
        String safeEvaluatorId = createEvaluator("pilot-spel-safe-methods", "EXPRESSION", safeSpec);
        JsonNode safeRun = awaitRun(datasetId,
                runAsync(datasetId, safeEvaluatorId, "REPLAY", "spel-safe-2", 202)
                        .path("data").path("runId").asText());
        assertThat(safeRun.path("data").path("items").get(0).path("label").asText()).isEqualTo("PASS");
    }

    @Test
    void idempotencyBusyRejectionAndConflictingRequest() throws Exception {
        String datasetId = createDataset("pilot-idem");
        fakeLlm.blocking = true;
        fakeLlm.outputs.put("q-block", "blocked-output");
        addItem(datasetId, "q-block", "blocked-output", null);
        String evaluatorId = createEvaluator("pilot-idem-exact", "EXPRESSION", exactMatchSpec());

        JsonNode first = runAsync(datasetId, evaluatorId, "REPLAY", "idem-1", 202);
        String runId = first.path("data").path("runId").asText();
        assertThat(first.path("data").path("created").asBoolean()).isTrue();

        JsonNode duplicate = runAsync(datasetId, evaluatorId, "REPLAY", "idem-1", 200);
        assertThat(duplicate.path("data").path("runId").asText()).isEqualTo(runId);
        assertThat(duplicate.path("data").path("created").asBoolean()).isFalse();

        // 同键不同请求 → 40901（项目约定 HTTP 200 + 业务码）
        JsonNode conflict = runAsync(datasetId, evaluatorId, "TRACE", "idem-1", 200);
        assertThat(conflict.path("code").asInt()).isEqualTo(40901);

        // 第二个不同 key 的活动运行 → 429
        runAsync(datasetId, evaluatorId, "REPLAY", "idem-2", 429);

        fakeLlm.blocking = false;
        fakeLlm.block.countDown();
        JsonNode detail = awaitRun(datasetId, runId);
        assertThat(detail.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void oldRunKeepsSnapshotAfterItemDeletion() throws Exception {
        String datasetId = createDataset("pilot-freeze");
        fakeLlm.outputs.put("q-freeze", "A");
        addItem(datasetId, "q-freeze", "A", null);
        String evaluatorId = createEvaluator("pilot-freeze-exact", "EXPRESSION", exactMatchSpec());

        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "freeze-1", 202);
        String runId = accepted.path("data").path("runId").asText();
        JsonNode detail = awaitRun(datasetId, runId);
        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("PASS");
        String itemId = item.path("itemId").asText();

        deleteApi("/api/v1/datasets/" + datasetId + "/items/" + itemId);

        JsonNode after = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
        JsonNode frozen = after.path("data").path("items").get(0);
        assertThat(frozen.path("label").asText()).isEqualTo("PASS");
        assertThat(frozen.path("sampleSnapshot").path("input").asText()).isEqualTo("q-freeze");
        assertThat(frozen.path("sampleSnapshot").path("expectedOutput").asText()).isEqualTo("A");
        assertThat(frozen.path("resultDetails").path("candidateOutput").asText()).isEqualTo("A");

        JsonNode datasetAfter = readTree(get("/api/v1/datasets/" + datasetId));
        assertThat(datasetAfter.path("data").path("items").size()).isZero();
    }

    @Test
    void cancelRunningRunIsIdempotentAndWorkerCannotOverwrite() throws Exception {
        String datasetId = createDataset("pilot-cancel");
        fakeLlm.blocking = true;
        fakeLlm.outputs.put("q-cancel-1", "A");
        fakeLlm.outputs.put("q-cancel-2", "B");
        addItem(datasetId, "q-cancel-1", "A", null);
        addItem(datasetId, "q-cancel-2", "B", null);
        fakeLlm.calls.clear();
        String evaluatorId = createEvaluator("pilot-cancel-exact", "EXPRESSION", exactMatchSpec());

        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "cancel-1", 202);
        String runId = accepted.path("data").path("runId").asText();
        await(() -> {
                    try {
                        return readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> "RUNNING".equals(json.path("data").path("run").path("status").asText()), 10000);
        // 确保取消发生在“当前样本已开始生成”之后：任务已被 beginItem 认领，迟到结果应保留。
        await(() -> fakeLlm.calls.size(), size -> size >= 1, 10000);

        JsonNode cancelled = readTree(postApi("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/cancel",
                Map.of()));
        assertThat(cancelled.path("data").path("run").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(cancelled.path("data").path("cancelled").asBoolean()).isTrue();

        JsonNode repeated = readTree(postApi("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/cancel",
                Map.of()));
        assertThat(repeated.path("data").path("run").path("status").asText()).isEqualTo("CANCELLED");

        fakeLlm.blocking = false;
        fakeLlm.block.countDown();
        await(() -> {
                    try {
                        return readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> json.path("data").path("run").path("passCount").asInt() == 1, 10000);
        JsonNode detail = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
        assertThat(detail.path("data").path("run").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(detail.path("data").path("run").path("incompleteCount").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(detail.path("data").path("run").path("passCount").asInt()).isEqualTo(1);
        assertThat(detail.path("data").path("run").path("passRate").asDouble()).isEqualTo(100.0);
        assertThat(detail.path("data").path("run").path("coverage").asDouble()).isEqualTo(50.0);
        JsonNode tasks = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/tasks"))
                .path("data");
        assertThat(tasks.path("summary").path("cancelled").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(tasks.path("records").findValuesAsText("status")).contains("CANCELLED");
        JsonNode gate = createGate(runId);
        assertThat(gate.path("data").path("decision").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void runComparisonReportsPairedTransitionsAndIncompatibility() throws Exception {
        String datasetId = createDataset("pilot-compare");
        addItem(datasetId, "q-cmp-a", "A", null);
        addItem(datasetId, "q-cmp-b", "B", null);
        fakeLlm.outputs.put("q-cmp-a", "A");
        fakeLlm.outputs.put("q-cmp-b", "B");
        String evaluatorId = createEvaluator("pilot-compare-exact", "EXPRESSION", exactMatchSpec());

        String baselineId = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "compare-base", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();

        fakeLlm.outputs.put("q-cmp-a", "X");
        String candidateId = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "compare-cand", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();

        JsonNode compared = readTree(get("/api/v1/datasets/" + datasetId + "/runs/comparison"
                + "?baselineRunId=" + baselineId + "&candidateRunId=" + candidateId));
        assertThat(compared.path("code").asInt()).isZero();
        JsonNode data = compared.path("data");
        assertThat(data.path("compatible").asBoolean()).isTrue();
        assertThat(data.path("reasons").size()).isZero();
        assertThat(data.path("summary").path("passToPass").asInt()).isEqualTo(1);
        assertThat(data.path("summary").path("passToFail").asInt()).isEqualTo(1);
        assertThat(data.path("regressions").size()).isEqualTo(1);
        assertThat(data.path("regressions").get(0).path("input").asText()).isEqualTo("q-cmp-a");

        // 换一个 evaluator key：样本/模式相同也不能把差异当模型回归
        String otherEvaluatorId = createEvaluator("pilot-compare-other", "EXPRESSION", exactMatchSpec());
        String otherRunId = awaitRun(datasetId,
                runAsync(datasetId, otherEvaluatorId, "REPLAY", "compare-other", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        JsonNode incompatible = readTree(get("/api/v1/datasets/" + datasetId + "/runs/comparison"
                + "?baselineRunId=" + baselineId + "&candidateRunId=" + otherRunId));
        assertThat(incompatible.path("data").path("compatible").asBoolean()).isFalse();
        assertThat(incompatible.path("data").path("reasons").toString()).contains("EVALUATOR_MISMATCH");
    }

    @Test
    void comparisonRejectsLegacyRunsWithoutSnapshotEvidence() throws Exception {
        String datasetId = createDataset("pilot-compare-legacy");
        DatasetRunEntity baseline = new DatasetRunEntity();
        baseline.setWorkspaceId("ws-default");
        baseline.setDatasetId(datasetId);
        baseline.setEvaluatorId("legacy-evaluator");
        baseline.setEvaluatorKey("legacy-evaluator");
        baseline.setRunMode("TRACE");
        baseline.setStatus(DatasetRunEntity.STATUS_COMPLETED);
        baseline.setItemCount(1);
        baseline.setStartedAt(LocalDateTime.now());
        baseline.setFinishedAt(LocalDateTime.now());
        datasetRunMapper.insert(baseline);
        DatasetRunEntity candidate = new DatasetRunEntity();
        candidate.setWorkspaceId("ws-default");
        candidate.setDatasetId(datasetId);
        candidate.setEvaluatorId("legacy-evaluator");
        candidate.setEvaluatorKey("legacy-evaluator");
        candidate.setRunMode("TRACE");
        candidate.setStatus(DatasetRunEntity.STATUS_COMPLETED);
        candidate.setItemCount(1);
        candidate.setStartedAt(LocalDateTime.now());
        candidate.setFinishedAt(LocalDateTime.now());
        datasetRunMapper.insert(candidate);
        for (DatasetRunEntity run : List.of(baseline, candidate)) {
            DatasetRunItemEntity item = new DatasetRunItemEntity();
            item.setRunId(run.getId());
            item.setItemId("legacy-item-1");
            item.setLabel(EvaluatorEntity.LABEL_PASS);
            item.setScore(java.math.BigDecimal.valueOf(100));
            datasetRunItemMapper.insert(item);
        }

        JsonNode compared = readTree(get("/api/v1/datasets/" + datasetId + "/runs/comparison"
                + "?baselineRunId=" + baseline.getId() + "&candidateRunId=" + candidate.getId()));
        JsonNode data = compared.path("data");
        assertThat(data.path("compatible").asBoolean()).isFalse();
        assertThat(data.path("diffAvailable").asBoolean()).isFalse();
        assertThat(data.path("items").size()).isZero();
        assertThat(data.path("regressions").size()).isZero();
        assertThat(data.path("reasons").toString())
                .contains("BASELINE_SNAPSHOT_MISSING")
                .contains("CANDIDATE_SNAPSHOT_MISSING");
    }

    @Test
    void criterionResultsGateJudgeLabelAndMissingCriteriaIsUnknown() throws Exception {
        String datasetId = createDataset("pilot-criterion");
        fakeLlm.outputs.put("q-criterion", "A");
        addItem(datasetId, "q-criterion", "A", null);

        Map<String, Object> criterionSpec = new HashMap<>();
        criterionSpec.put("type", "LLM_JUDGE");
        criterionSpec.put("rubric", "回答必须准确且语气自然");
        criterionSpec.put("minScore", 6);
        criterionSpec.put("referenceRequired", false);
        criterionSpec.put("criteria", List.of(
                Map.of("id", "accuracy", "label", "准确", "description", "事实正确", "required", true),
                Map.of("id", "tone", "label", "语气", "description", "自然礼貌", "required", true)));

        fakeLlm.judgeOutput = "{\"score\":9,\"confidence\":0.9,\"reason\":\"部分通过\","
                + "\"criteria\":[{\"id\":\"accuracy\",\"passed\":false,\"reason\":\"事实错误\"},"
                + "{\"id\":\"tone\",\"passed\":true,\"reason\":\"语气自然\"}]}";
        String evaluatorId = createEvaluator("pilot-criterion-required", "LLM_JUDGE", criterionSpec);
        JsonNode run = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "criterion-required", 202)
                        .path("data").path("runId").asText());
        JsonNode item = run.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("FAIL");
        assertThat(item.path("resultDetails").path("reasonCode").asText()).isEqualTo("JUDGE_CRITERION_FAIL");
        JsonNode criteria = item.path("resultDetails").path("evaluators").get(0).path("criteria");
        assertThat(criteria.size()).isEqualTo(2);
        assertThat(criteria.get(0).path("id").asText()).isEqualTo("accuracy");
        assertThat(criteria.get(0).path("passed").asBoolean()).isFalse();
        assertThat(criteria.get(1).path("passed").asBoolean()).isTrue();

        // optional criterion 不参与整体门禁：同样 false 但 required=false 时，score 达标即 PASS
        Map<String, Object> optionalSpec = new HashMap<>(criterionSpec);
        optionalSpec.put("criteria", List.of(
                Map.of("id", "accuracy", "label", "准确", "required", false),
                Map.of("id", "tone", "label", "语气", "required", false)));
        String optionalId = createEvaluator("pilot-criterion-optional", "LLM_JUDGE", optionalSpec);
        JsonNode optionalRun = awaitRun(datasetId,
                runAsync(datasetId, optionalId, "REPLAY", "criterion-optional", 202)
                        .path("data").path("runId").asText());
        assertThat(optionalRun.path("data").path("items").get(0).path("label").asText()).isEqualTo("PASS");

        // criteria 配置存在但模型没返回 criteria：不得按 score 放行，必须 UNKNOWN
        fakeLlm.judgeOutput = "{\"score\":9,\"confidence\":0.9,\"reason\":\"ok\"}";
        String missingId = createEvaluator("pilot-criterion-missing", "LLM_JUDGE", criterionSpec);
        JsonNode missingRun = awaitRun(datasetId,
                runAsync(datasetId, missingId, "REPLAY", "criterion-missing", 202)
                        .path("data").path("runId").asText());
        JsonNode missingItem = missingRun.path("data").path("items").get(0);
        assertThat(missingItem.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(missingItem.path("resultDetails").path("reasonCode").asText())
                .isEqualTo("INVALID_JUDGE_OUTPUT");
        JsonNode missingTasks = readTree(get("/api/v1/datasets/" + datasetId + "/runs/"
                + missingRun.path("data").path("run").path("id").asText() + "/tasks")).path("data");
        assertThat(missingTasks.path("summary").path("unknown").asInt()).isEqualTo(1);
        assertThat(missingTasks.path("summary").path("failed").asInt()).isZero();
        JsonNode missingTask = missingTasks.path("records").get(0);
        assertThat(missingTask.path("status").asText()).isEqualTo(EvalTaskEntity.STATUS_UNKNOWN);
        assertThat(missingTask.path("verdict").asText()).isEqualTo(EvaluatorEntity.LABEL_UNKNOWN);
        assertThat(missingTask.path("reasonCode").asText()).isEqualTo("INVALID_JUDGE_OUTPUT");

        // 只返回部分 id 或 passed 非布尔：同样 fail-closed，不得按 score 放行
        fakeLlm.judgeOutput = "{\"score\":9,\"confidence\":0.9,\"reason\":\"ok\","
                + "\"criteria\":[{\"id\":\"accuracy\",\"passed\":\"true\"}]}";
        String malformedId = createEvaluator("pilot-criterion-malformed", "LLM_JUDGE", criterionSpec);
        JsonNode malformedRun = awaitRun(datasetId,
                runAsync(datasetId, malformedId, "REPLAY", "criterion-malformed", 202)
                        .path("data").path("runId").asText());
        JsonNode malformedItem = malformedRun.path("data").path("items").get(0);
        assertThat(malformedItem.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(malformedItem.path("resultDetails").path("reasonCode").asText())
                .isEqualTo("INVALID_JUDGE_OUTPUT");
    }

    @Test
    void suiteRunUsesFrozenEvaluatorAndCreatesNewVersion() throws Exception {
        String datasetId = createDataset("pilot-suite");
        fakeLlm.outputs.put("q-suite", "A");
        addItem(datasetId, "q-suite", "A", null);

        Map<String, Object> passSpec = new HashMap<>();
        passSpec.put("type", "EXPRESSION");
        passSpec.put("passWhen", "true");
        passSpec.put("referenceRequired", false);
        String evaluatorId = createEvaluator("pilot-suite-version", "EXPRESSION", passSpec);

        JsonNode suite = createSuite("pilot-suite", List.of(evaluatorId));
        String suiteId = suite.path("data").path("id").asText();
        assertThat(suite.path("data").path("version").asInt()).isEqualTo(1);
        assertThat(suite.path("data").path("evaluatorCount").asInt()).isEqualTo(1);
        JsonNode listed = readTree(get("/api/v1/eval-suites"));
        assertThat(listed.path("code").asInt()).isZero();
        assertThat(listed.path("data").toString()).contains(suiteId);
        // 同内容重复创建 → 同一个不可变版本
        assertThat(createSuite("pilot-suite", List.of(evaluatorId)).path("data").path("id").asText())
                .isEqualTo(suiteId);

        Map<String, Object> mixed = new HashMap<>();
        mixed.put("suiteId", suiteId);
        mixed.put("evaluatorIds", List.of(evaluatorId));
        mixed.put("mode", "REPLAY");
        String mixedResponse = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "suite-mixed")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(mixed)))
                .andReturn().getResponse().getContentAsString();
        assertThat(readTree(mixedResponse).path("code").asInt()).isEqualTo(40000);

        JsonNode first = awaitRun(datasetId,
                runAsyncWithSuite(datasetId, suiteId, "suite-1", 202).path("data").path("runId").asText());
        assertThat(first.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(first.path("data").path("run").path("suiteId").asText()).isEqualTo(suiteId);
        assertThat(first.path("data").path("run").path("evaluatorCount").asInt()).isEqualTo(1);
        assertThat(first.path("data").path("run").path("configSnapshot").path("suite").path("id").asText())
                .isEqualTo(suiteId);
        assertThat(first.path("data").path("run").path("configSnapshot").path("evaluators").get(0)
                .path("version").asInt()).isEqualTo(1);

        // evaluator 当前定义改为 false；直接运行使用新版本应 FAIL，suite 仍使用冻结 v1 应 PASS
        Map<String, Object> failSpec = new HashMap<>(passSpec);
        failSpec.put("passWhen", "false");
        putApi("/api/v1/evaluators/" + evaluatorId, Map.of("spec", failSpec));

        JsonNode direct = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "suite-direct", 202)
                        .path("data").path("runId").asText());
        assertThat(direct.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(direct.path("data").path("items").get(0).path("label").asText()).isEqualTo("FAIL");

        JsonNode second = awaitRun(datasetId,
                runAsyncWithSuite(datasetId, suiteId, "suite-2", 202).path("data").path("runId").asText());
        assertThat(second.path("data").path("items").get(0).path("label").asText()).isEqualTo("PASS");
        assertThat(second.path("data").path("run").path("configSnapshot").path("evaluators").get(0)
                .path("version").asInt()).isEqualTo(1);

        JsonNode suiteV2 = createSuite("pilot-suite", List.of(evaluatorId));
        assertThat(suiteV2.path("data").path("version").asInt()).isEqualTo(2);
        assertThat(suiteV2.path("data").path("id").asText()).isNotEqualTo(suiteId);
    }

    @Test
    void budgetStopsBeforeNextItemAndMarksIncomplete() throws Exception {
        String datasetId = createDataset("pilot-budget");
        fakeLlm.outputs.put("q-budget-1", "A");
        fakeLlm.outputs.put("q-budget-2", "B");
        addItem(datasetId, "q-budget-1", "A", null);
        addItem(datasetId, "q-budget-2", "B", null);
        String evaluatorId = createEvaluator("pilot-budget-exact", "EXPRESSION", exactMatchSpec());

        JsonNode accepted = runAsyncWithBudget(datasetId, evaluatorId, 1, "budget-1", 202);
        String runId = accepted.path("data").path("runId").asText();
        JsonNode detail = awaitRun(datasetId, runId);
        JsonNode run = detail.path("data").path("run");

        // 预算耗尽语义是“未执行完”而非“执行失败”：收口 INTERRUPTED，保留续跑入口
        assertThat(run.path("status").asText()).isEqualTo("INTERRUPTED");
        assertThat(run.path("passCount").asInt()).isEqualTo(1);
        assertThat(run.path("incompleteCount").asInt()).isEqualTo(1);
        assertThat(run.path("coverage").asDouble()).isEqualTo(50.0);
        assertThat(run.path("usageSummary").path("externalCalls").asInt()).isEqualTo(1);
        assertThat(run.path("configSnapshot").path("budget").path("maxExternalCalls").asInt()).isEqualTo(1);

        boolean budgetItem = false;
        boolean passItem = false;
        for (JsonNode item : detail.path("data").path("items")) {
            if ("BUDGET_EXCEEDED".equals(item.path("resultDetails").path("reasonCode").asText())) {
                budgetItem = true;
                assertThat(item.path("label").isMissingNode() || item.path("label").isNull()).isTrue();
                assertThat(item.path("resultDetails").path("status").asText()).isEqualTo("INCOMPLETE");
            }
            if ("PASS".equals(item.path("label").asText())) {
                passItem = true;
            }
        }
        assertThat(budgetItem).isTrue();
        assertThat(passItem).isTrue();
    }

    @Test
    void invalidBudgetAndBudgetChangeWithSameKeyAreRejected() throws Exception {
        String datasetId = createDataset("pilot-budget-invalid");
        fakeLlm.outputs.put("q-budget-invalid", "A");
        addItem(datasetId, "q-budget-invalid", "A", null);
        String evaluatorId = createEvaluator("pilot-budget-invalid-exact", "EXPRESSION", exactMatchSpec());

        assertThat(runAsyncWithBudget(datasetId, evaluatorId, 0, "budget-invalid-1", 200)
                .path("code").asInt()).isEqualTo(40000);
        JsonNode created = runAsyncWithBudget(datasetId, evaluatorId, 1, "budget-invalid-2", 202);
        String runId = created.path("data").path("runId").asText();
        assertThat(runAsyncWithBudget(datasetId, evaluatorId, 2, "budget-invalid-2", 200)
                .path("code").asInt()).isEqualTo(40901);
        // 释放活动名额，避免影响后续用例（预算用例共用同一个 worker/H2 上下文）。
        postApi("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/cancel", Map.of());
        awaitRun(datasetId, runId);
    }

    @Test
    void cancelCompletedRunIsNoOp() throws Exception {
        String datasetId = createDataset("pilot-cancel-done");
        fakeLlm.outputs.put("q-cancel-done", "A");
        addItem(datasetId, "q-cancel-done", "A", null);
        String evaluatorId = createEvaluator("pilot-cancel-done-exact", "EXPRESSION", exactMatchSpec());
        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "cancel-done-1", 202);
        String runId = accepted.path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/cancel",
                Map.of()));
        assertThat(response.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(response.path("data").path("cancelled").asBoolean()).isFalse();
    }

    @Test
    void resumeCarriesRemainingTokenBudgetWithoutResettingIt() throws Exception {
        String datasetId = createDataset("token-budget-resume");
        fakeLlm.outputs.put("q-token-resume", "ok");
        addItem(datasetId, "q-token-resume", "ok", null);
        String evaluatorId = createEvaluator("token-budget-resume", "EXPRESSION", exactMatchSpec());
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorIds", List.of(evaluatorId));
        body.put("mode", "REPLAY");
        body.put("maxTokens", 5000);
        JsonNode accepted = readTree(mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "token-budget-resume-source")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String sourceId = accepted.path("data").path("runId").asText();
        awaitRun(datasetId, sourceId);

        DatasetRunEntity source = datasetRunMapper.selectById(sourceId);
        source.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        source.setTokensSpent(1200L);
        source.setTokensReserved(100L);
        source.setFinishedAt(LocalDateTime.now());
        datasetRunMapper.updateById(source);
        DatasetRunItemEntity item = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery()
                        .eq(DatasetRunItemEntity::getRunId, sourceId)).get(0);
        datasetRunItemMapper.update(null, Wrappers.<DatasetRunItemEntity>lambdaUpdate()
                .eq(DatasetRunItemEntity::getId, item.getId())
                .set(DatasetRunItemEntity::getLabel, null)
                .set(DatasetRunItemEntity::getScore, null)
                .set(DatasetRunItemEntity::getReason, null)
                .set(DatasetRunItemEntity::getResultDetailsJson,
                        "{\"status\":\"INCOMPLETE\",\"reasonCode\":\"INTERRUPTED\"}"));

        JsonNode resumed = resume(datasetId, sourceId, "token-budget-resume-attempt", 202)
                .path("data");
        assertThat(resumed.path("maxTokens").asLong()).isEqualTo(3700L);
        assertThat(resumed.path("configSnapshot").path("budget").path("maxTokens").asLong())
                .isEqualTo(3700L);
        assertThat(resumed.path("configSnapshot").path("budget").path("originalMaxTokens").asLong())
                .isEqualTo(5000L);
        assertThat(resumed.path("configSnapshot").path("budget").path("resumeConsumedTokens").asLong())
                .isEqualTo(1300L);
        DatasetRunEntity unchanged = datasetRunMapper.selectById(sourceId);
        assertThat(unchanged.getMaxTokens()).isEqualTo(5000L);
        assertThat(unchanged.getTokensSpent()).isEqualTo(1200L);
        awaitRun(datasetId, resumed.path("id").asText());
    }

    @Test
    void resumeInterruptedRunCreatesNewAttemptAndOnlyExecutesMissingItems() throws Exception {
        String datasetId = createDataset("pilot-resume");
        addItem(datasetId, "q-resume-a", "A", null);
        addItem(datasetId, "q-resume-b", "B", null);
        fakeLlm.outputs.put("q-resume-a", "A");
        fakeLlm.outputs.put("q-resume-b", "B");
        String evaluatorId = createEvaluator("pilot-resume-exact", "EXPRESSION", exactMatchSpec());
        String sourceId = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "resume-source", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();

        // 模拟崩溃：一条已执行、一条未执行，源 run 为 INTERRUPTED
        List<DatasetRunItemEntity> items = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery().eq(DatasetRunItemEntity::getRunId, sourceId));
        assertThat(items).hasSize(2);
        DatasetRunItemEntity missing = items.stream()
                .filter(i -> "q-resume-a".equals(itemInput(i))).findFirst().orElseThrow();
        DatasetRunItemEntity executed = items.stream()
                .filter(i -> !i.getId().equals(missing.getId())).findFirst().orElseThrow();
        assertThat(executed.getLabel()).isEqualTo("PASS");
        String executedLabel = executed.getLabel();
        java.math.BigDecimal executedScore = executed.getScore();
        String executedDetails = executed.getResultDetailsJson();
        String executedEvaluatorResults = executed.getEvaluatorResultsJson();
        String executedReplayOutput = executed.getReplayOutput();
        datasetRunItemMapper.update(null, Wrappers.<DatasetRunItemEntity>lambdaUpdate()
                .eq(DatasetRunItemEntity::getId, missing.getId())
                .set(DatasetRunItemEntity::getLabel, null)
                .set(DatasetRunItemEntity::getScore, null)
                .set(DatasetRunItemEntity::getReason, null)
                .set(DatasetRunItemEntity::getResultDetailsJson,
                        "{\"status\":\"INCOMPLETE\",\"reasonCode\":\"INTERRUPTED\"}"));
        DatasetRunEntity source = datasetRunMapper.selectById(sourceId);
        source.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        source.setFinishedAt(LocalDateTime.now());
        datasetRunMapper.updateById(source);

        fakeLlm.calls.clear();
        JsonNode resumed = resume(datasetId, sourceId, "resume-1", 202);
        JsonNode data = resumed.path("data");
        assertThat(data.path("status").asText()).isEqualTo("PENDING");
        assertThat(data.path("resumedFromRunId").asText()).isEqualTo(sourceId);
        assertThat(data.path("attempt").asInt()).isEqualTo(2);
        assertThat(data.path("itemCount").asInt()).isEqualTo(2);
        String newId = data.path("id").asText();
        JsonNode completed = awaitRun(datasetId, newId);
        assertThat(completed.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(completed.path("data").path("run").path("passCount").asInt()).isEqualTo(2);
        assertThat(datasetRunMapper.selectById(sourceId).getStatus())
                .isEqualTo(DatasetRunEntity.STATUS_INTERRUPTED);
        assertThat(fakeLlm.calls).as("only missing item should call LLM").hasSize(1);

        // 已执行 item 的证据必须逐字段原样复制，未执行 item 才重新执行
        List<DatasetRunItemEntity> newItems = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery().eq(DatasetRunItemEntity::getRunId, newId));
        DatasetRunItemEntity copied = newItems.stream()
                .filter(i -> i.getItemId().equals(executed.getItemId())).findFirst().orElseThrow();
        assertThat(copied.getLabel()).isEqualTo(executedLabel);
        assertThat(copied.getScore()).isEqualByComparingTo(executedScore);
        assertThat(copied.getResultDetailsJson()).isEqualTo(executedDetails);
        assertThat(copied.getEvaluatorResultsJson()).isEqualTo(executedEvaluatorResults);
        assertThat(copied.getReplayOutput()).isEqualTo(executedReplayOutput);
        DatasetRunItemEntity rerun = newItems.stream()
                .filter(i -> i.getItemId().equals(missing.getItemId())).findFirst().orElseThrow();
        assertThat(rerun.getLabel()).isEqualTo("PASS");

        // 同 Idempotency-Key 重复调用返回同一新 run
        JsonNode replay = resume(datasetId, sourceId, "resume-1", 202);
        assertThat(replay.path("data").path("id").asText()).isEqualTo(newId);

        // 同源不同 key 不能分支成第二个 attempt=2
        assertThat(resume(datasetId, sourceId, "resume-branch", 200)
                .path("code").asInt()).isEqualTo(40901);

        // 同 key 不同源：409，而不是创建或返回错误 run
        String otherDataset = createDataset("pilot-resume-other");
        assertThat(resume(otherDataset, sourceId, "resume-1", 200)
                .path("code").asInt()).isEqualTo(40901);

        // 链式续跑 attempt=3
        DatasetRunEntity attempt2 = datasetRunMapper.selectById(newId);
        attempt2.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        datasetRunMapper.updateById(attempt2);
        DatasetRunItemEntity attempt2Item = newItems.get(0);
        datasetRunItemMapper.update(null, Wrappers.<DatasetRunItemEntity>lambdaUpdate()
                .eq(DatasetRunItemEntity::getId, attempt2Item.getId())
                .set(DatasetRunItemEntity::getLabel, null)
                .set(DatasetRunItemEntity::getResultDetailsJson,
                        "{\"status\":\"INCOMPLETE\",\"reasonCode\":\"INTERRUPTED\"}"));
        fakeLlm.calls.clear();
        JsonNode third = resume(datasetId, newId, "resume-2", 202);
        assertThat(third.path("data").path("attempt").asInt()).isEqualTo(3);
        JsonNode thirdRun = awaitRun(datasetId, third.path("data").path("id").asText());
        assertThat(thirdRun.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(fakeLlm.calls).as("third attempt only missing item").hasSize(1);

        JsonNode chain = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/" + third.path("data").path("id").asText() + "/attempts"));
        assertThat(chain.path("data").path("rootRunId").asText()).isEqualTo(sourceId);
        assertThat(chain.path("data").path("currentRunId").asText())
                .isEqualTo(third.path("data").path("id").asText());
        assertThat(chain.path("data").path("attemptCount").asInt()).isEqualTo(3);
        assertThat(chain.path("data").path("attempts").get(0).path("attempt").asInt()).isEqualTo(1);
        assertThat(chain.path("data").path("attempts").get(1).path("attempt").asInt()).isEqualTo(2);
        assertThat(chain.path("data").path("attempts").get(2).path("attempt").asInt()).isEqualTo(3);
        assertThat(chain.path("data").path("attempts").get(2).path("status").asText())
                .isEqualTo("COMPLETED");
        assertThat(chain.path("data").path("attempts").get(0).path("passCount").asInt()).isEqualTo(1);
        assertThat(chain.path("data").path("attempts").get(0).path("incompleteCount").asInt()).isEqualTo(1);
        assertThat(chain.path("data").path("attempts").get(0).path("coverage").asDouble()).isEqualTo(50.0);
        assertThat(chain.path("data").path("attempts").get(2).path("passCount").asInt()).isEqualTo(2);
        assertThat(chain.path("data").path("attempts").get(2).path("coverage").asDouble()).isEqualTo(100.0);
        assertThat(chain.path("data").path("attempts").get(0).has("items")).isFalse();
        JsonNode midChain = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/" + newId + "/attempts"));
        assertThat(midChain.path("data").path("currentRunId").asText()).isEqualTo(newId);
        assertThat(midChain.path("data").path("attemptCount").asInt()).isEqualTo(3);
        JsonNode wrongDatasetChain = readTree(get("/api/v1/datasets/" + otherDataset
                + "/runs/" + third.path("data").path("id").asText() + "/attempts"));
        assertThat(wrongDatasetChain.path("code").asInt()).isEqualTo(40401);
    }

    @Test
    void resumeRejectsNonInterruptedOrBudgetedRun() throws Exception {
        String datasetId = createDataset("pilot-resume-reject");
        fakeLlm.outputs.put("q-resume-reject", "A");
        addItem(datasetId, "q-resume-reject", "A", null);
        String evaluatorId = createEvaluator("pilot-resume-reject-exact", "EXPRESSION", exactMatchSpec());
        String completedId = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "resume-reject-1", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        assertThat(resume(datasetId, completedId, "resume-reject-key", 200)
                .path("code").asInt()).isEqualTo(40000);

        // 已是 INTERRUPTED 但没有未执行样本：409
        DatasetRunEntity completedSource = datasetRunMapper.selectById(completedId);
        completedSource.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        datasetRunMapper.updateById(completedSource);
        assertThat(resume(datasetId, completedId, "resume-empty", 200)
                .path("code").asInt()).isEqualTo(40901);

        // 冻结 config 与列不一致：400（防止伪造指纹/revision 续跑）
        Map<String, Object> tampered = objectMapper.readValue(
                completedSource.getConfigSnapshotJson(), Map.class);
        tampered.put("candidateFingerprint", "0".repeat(64));
        completedSource.setConfigSnapshotJson(objectMapper.writeValueAsString(tampered));
        datasetRunMapper.updateById(completedSource);
        assertThat(resume(datasetId, completedId, "resume-tampered", 200)
                .path("code").asInt()).isEqualTo(40000);

        // 有活动 run：50301
        DatasetRunEntity active = new DatasetRunEntity();
        active.setWorkspaceId("ws-default");
        active.setDatasetId(datasetId);
        active.setEvaluatorId(evaluatorId);
        active.setEvaluatorKey("pilot-resume-reject-exact");
        active.setStatus(DatasetRunEntity.STATUS_RUNNING);
        active.setItemCount(0);
        active.setStartedAt(LocalDateTime.now());
        active.setRequestKey("resume-active-blocker");
        datasetRunMapper.insert(active);
        JsonNode activeRejected = resume(datasetId, completedId, "resume-active", 429);
        assertThat(activeRejected.path("code").asInt()).isEqualTo(50301);
        datasetRunMapper.deleteById(active.getId());

        // 预算 run 按持久化调用账本续跑：即使中断 item 未落结果，已预留的调用仍会扣减
        String budgetDataset = createDataset("pilot-resume-budget");
        fakeLlm.outputs.put("q-resume-budget-a", "A");
        fakeLlm.outputs.put("q-resume-budget-b", "B");
        addItem(budgetDataset, "q-resume-budget-a", "A", null);
        addItem(budgetDataset, "q-resume-budget-b", "B", null);
        String budgetEvaluator = createEvaluator("pilot-resume-budget-exact", "EXPRESSION", exactMatchSpec());
        String budgetRunId = awaitRun(budgetDataset,
                runAsyncWithBudget(budgetDataset, budgetEvaluator, 3, "resume-budget-1", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        DatasetRunEntity budgetSource = datasetRunMapper.selectById(budgetRunId);
        assertThat(budgetSource.getExternalCallsReserved()).isEqualTo(2);
        budgetSource.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        datasetRunMapper.updateById(budgetSource);
        DatasetRunItemEntity budgetMissing = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery().eq(DatasetRunItemEntity::getRunId, budgetRunId)).get(0);
        datasetRunItemMapper.update(null, Wrappers.<DatasetRunItemEntity>lambdaUpdate()
                .eq(DatasetRunItemEntity::getId, budgetMissing.getId())
                .set(DatasetRunItemEntity::getLabel, null)
                .set(DatasetRunItemEntity::getResultDetailsJson,
                        "{\"status\":\"INCOMPLETE\",\"reasonCode\":\"INTERRUPTED\"}"));
        fakeLlm.calls.clear();
        JsonNode resumedBudget = resume(budgetDataset, budgetRunId, "resume-budget-key", 202);
        assertThat(resumedBudget.path("data").path("configSnapshot").path("budget")
                .path("maxExternalCalls").asInt()).isEqualTo(1);
        assertThat(resumedBudget.path("data").path("configSnapshot").path("budget")
                .path("resumeConsumedCalls").asInt()).isEqualTo(2);
        assertThat(resumedBudget.path("data").path("externalCallsReserved").asInt()).isZero();
        JsonNode resumedBudgetRun = awaitRun(budgetDataset, resumedBudget.path("data").path("id").asText());
        assertThat(resumedBudgetRun.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(fakeLlm.calls).as("ledger deducts reserved calls even when item result missing").hasSize(1);
        JsonNode budgetChain = readTree(get("/api/v1/datasets/" + budgetDataset
                + "/runs/" + resumedBudget.path("data").path("id").asText() + "/attempts"));
        JsonNode resumedRow = budgetChain.path("data").path("attempts").get(1);
        assertThat(resumedRow.path("budget").path("maxExternalCalls").asInt()).isEqualTo(1);
        assertThat(resumedRow.path("budget").path("originalMaxExternalCalls").asInt()).isEqualTo(3);
        assertThat(resumedRow.path("budget").path("resumeConsumedCalls").asInt()).isEqualTo(2);
        assertThat(resumedRow.path("externalCallsReserved").asInt()).isEqualTo(1);

        // 预算已耗尽：按账本拒绝，而不是按未落库的 label 重新发放完整预算
        String spentDataset = createDataset("pilot-resume-budget-spent");
        fakeLlm.outputs.put("q-resume-spent", "A");
        addItem(spentDataset, "q-resume-spent", "A", null);
        String spentEvaluator = createEvaluator("pilot-resume-spent-exact", "EXPRESSION", exactMatchSpec());
        String spentRunId = awaitRun(spentDataset,
                runAsyncWithBudget(spentDataset, spentEvaluator, 2, "resume-spent-1", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        DatasetRunEntity spent = datasetRunMapper.selectById(spentRunId);
        spent.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        datasetRunMapper.updateById(spent);
        DatasetRunItemEntity spentItem = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery().eq(DatasetRunItemEntity::getRunId, spentRunId)).get(0);
        datasetRunItemMapper.update(null, Wrappers.<DatasetRunItemEntity>lambdaUpdate()
                .eq(DatasetRunItemEntity::getId, spentItem.getId())
                .set(DatasetRunItemEntity::getLabel, null)
                .set(DatasetRunItemEntity::getResultDetailsJson,
                        "{\"status\":\"INCOMPLETE\",\"reasonCode\":\"INTERRUPTED\"}"));
        datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, spentRunId)
                .set(DatasetRunEntity::getExternalCallsReserved, 2));
        assertThat(resume(spentDataset, spentRunId, "resume-spent-key", 200)
                .path("code").asInt()).isEqualTo(40901);

        // 历史预算 run 没有账本：fail-closed
        String legacyDataset = createDataset("pilot-resume-budget-legacy");
        fakeLlm.outputs.put("q-resume-legacy", "A");
        addItem(legacyDataset, "q-resume-legacy", "A", null);
        String legacyEvaluator = createEvaluator("pilot-resume-legacy-exact", "EXPRESSION", exactMatchSpec());
        String legacyRunId = awaitRun(legacyDataset,
                runAsyncWithBudget(legacyDataset, legacyEvaluator, 2, "resume-legacy-1", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        DatasetRunEntity legacyBudget = datasetRunMapper.selectById(legacyRunId);
        legacyBudget.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        datasetRunMapper.updateById(legacyBudget);
        DatasetRunItemEntity legacyItem = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery().eq(DatasetRunItemEntity::getRunId, legacyRunId)).get(0);
        datasetRunItemMapper.update(null, Wrappers.<DatasetRunItemEntity>lambdaUpdate()
                .eq(DatasetRunItemEntity::getId, legacyItem.getId())
                .set(DatasetRunItemEntity::getLabel, null)
                .set(DatasetRunItemEntity::getResultDetailsJson,
                        "{\"status\":\"INCOMPLETE\",\"reasonCode\":\"INTERRUPTED\"}"));
        datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, legacyRunId)
                .set(DatasetRunEntity::getExternalCallsReserved, null));
        assertThat(resume(legacyDataset, legacyRunId, "resume-legacy-key", 200)
                .path("code").asInt()).isEqualTo(40000);

        datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, legacyRunId)
                .set(DatasetRunEntity::getExternalCallsReserved, -1));
        assertThat(resume(legacyDataset, legacyRunId, "resume-negative-key", 200)
                .path("code").asInt()).isEqualTo(40000);
    }

    @Test
    void attemptChainAggregatesLabelsAndStaysOnRequestedPath() {
        String rootId = java.util.UUID.randomUUID().toString();
        String childId = java.util.UUID.randomUUID().toString();
        String siblingId = java.util.UUID.randomUUID().toString();
        DatasetRunEntity root = attemptRun(rootId, null, 1, "chain-agg-root");
        DatasetRunEntity child = attemptRun(childId, rootId, 2, "chain-agg-child");
        DatasetRunEntity sibling = attemptRun(siblingId, rootId, 9, "chain-agg-sibling");
        datasetRunMapper.insert(root);
        datasetRunMapper.insert(child);
        datasetRunMapper.insert(sibling);
        insertAttemptItem(rootId, "PASS", 1);
        insertAttemptItem(rootId, "UNKNOWN", 2);
        insertAttemptItem(rootId, "ERROR", 3);
        insertAttemptItem(rootId, null, 4);
        insertAttemptItem(childId, "PASS", 1);
        insertAttemptItem(siblingId, "FAIL", 1);

        Map<String, Object> chain = datasetRunService.attemptChain("ws-default", "ds-chain-agg", childId);
        assertThat(chain.get("rootRunId")).isEqualTo(rootId);
        assertThat(chain.get("currentRunId")).isEqualTo(childId);
        assertThat(chain.get("attemptCount")).isEqualTo(2);
        List<?> attempts = (List<?>) chain.get("attempts");
        Map<?, ?> rootRow = (Map<?, ?>) attempts.get(0);
        assertThat(rootRow.get("passCount")).isEqualTo(1);
        assertThat(rootRow.get("unknownCount")).isEqualTo(1);
        assertThat(rootRow.get("errorCount")).isEqualTo(1);
        assertThat(rootRow.get("incompleteCount")).isEqualTo(1);
        assertThat(rootRow.get("coverage")).isEqualTo(25.0);

        datasetRunItemMapper.delete(Wrappers.<DatasetRunItemEntity>lambdaQuery()
                .in(DatasetRunItemEntity::getRunId, List.of(rootId, childId, siblingId)));
        datasetRunMapper.deleteBatchIds(List.of(rootId, childId, siblingId));
    }

    private DatasetRunEntity attemptRun(String id, String resumedFrom, int attempt, String requestKey) {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setId(id);
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-chain-agg");
        run.setEvaluatorId("ev-chain-agg");
        run.setEvaluatorKey("ev-chain-agg");
        run.setStatus(DatasetRunEntity.STATUS_COMPLETED);
        run.setItemCount(1);
        run.setStartedAt(LocalDateTime.now());
        run.setResumedFromRunId(resumedFrom);
        run.setAttempt(attempt);
        run.setRequestKey(requestKey);
        run.setExternalCallsReserved(0);
        return run;
    }

    private void insertAttemptItem(String runId, String label, int order) {
        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setId(java.util.UUID.randomUUID().toString());
        item.setRunId(runId);
        item.setItemId("it-" + java.util.UUID.randomUUID().toString().substring(0, 12) + "-" + order);
        item.setSampleSnapshotJson("{}");
        item.setResultDetailsJson("{}");
        item.setLabel(label);
        datasetRunItemMapper.insert(item);
    }

    @Test
    void retryFailedRunOnlyRetriesErrorItemsAndPreservesSource() throws Exception {
        fakeLlm.outputs.put("q-retry-ok", "ok");
        fakeLlm.outputs.put("q-retry-bad", "ok");
        String datasetIdForRetry = createDataset("retry-failed");
        addItem(datasetIdForRetry, "q-retry-ok", "ok", null);
        addItem(datasetIdForRetry, "q-retry-bad", "ok", null);
        String evaluatorIdForRetry = createEvaluator("retry-failed-exact", "EXPRESSION", exactMatchSpec());
        fakeLlm.failInputs.add("q-retry-bad");
        try {
            String sourceId = awaitRun(datasetIdForRetry,
                    runAsync(datasetIdForRetry, evaluatorIdForRetry, "REPLAY", "retry-source-1", 202)
                            .path("data").path("runId").asText())
                    .path("data").path("run").path("id").asText();
            JsonNode sourceDetail = awaitRun(datasetIdForRetry, sourceId);
            assertThat(sourceDetail.path("data").path("run").path("status").asText())
                    .isEqualTo("PARTIAL_FAILED");
            assertThat(sourceDetail.path("data").path("run").path("errorCount").asInt()).isEqualTo(1);
            assertThat(sourceDetail.path("data").path("run").path("passCount").asInt()).isEqualTo(1);
            Map<String, JsonNode> sourceItems = itemsByInput(sourceDetail);
            assertThat(sourceItems.get("q-retry-ok").path("label").asText()).isEqualTo("PASS");
            assertThat(sourceItems.get("q-retry-bad").path("label").asText()).isEqualTo("ERROR");

            fakeLlm.failInputs.clear();
            JsonNode retried = retry(datasetIdForRetry, sourceId, "retry-1", 202);
            assertThat(retried.path("data").path("attempt").asInt()).isEqualTo(2);
            assertThat(retried.path("data").path("resumedFromRunId").asText()).isEqualTo(sourceId);
            JsonNode retriedDetail = awaitRun(datasetIdForRetry,
                    retried.path("data").path("id").asText());
            assertThat(retriedDetail.path("data").path("run").path("status").asText())
                    .isEqualTo("COMPLETED");
            assertThat(retriedDetail.path("data").path("run").path("passCount").asInt()).isEqualTo(2);
            Map<String, JsonNode> retriedItems = itemsByInput(retriedDetail);
            assertThat(retriedItems.get("q-retry-ok").path("label").asText()).isEqualTo("PASS");
            assertThat(retriedItems.get("q-retry-bad").path("label").asText()).isEqualTo("PASS");
            JsonNode chain = readTree(get("/api/v1/datasets/" + datasetIdForRetry
                    + "/runs/" + retried.path("data").path("id").asText() + "/attempts"));
            assertThat(chain.path("data").path("attempts").size()).isEqualTo(2);

            DatasetRunEntity sourceAfter = datasetRunMapper.selectById(sourceId);
            assertThat(sourceAfter.getStatus()).isEqualTo(DatasetRunEntity.STATUS_PARTIAL_FAILED);

            JsonNode replay = retry(datasetIdForRetry, sourceId, "retry-1", 202);
            assertThat(replay.path("data").path("id").asText())
                    .isEqualTo(retried.path("data").path("id").asText());
            JsonNode completedRetry = retry(datasetIdForRetry,
                    retried.path("data").path("id").asText(), "retry-completed", 200);
            assertThat(completedRetry.path("code").asInt()).isEqualTo(40000);
        } finally {
            fakeLlm.failInputs.clear();
            fakeLlm.failGeneration = false;
        }
    }

    @Test
    void reserveExternalCallsFencesLeaseAndBudget() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-budget-ledger");
        run.setEvaluatorId("ev-budget-ledger");
        run.setEvaluatorKey("ev-budget-ledger");
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        run.setItemCount(0);
        run.setStartedAt(LocalDateTime.now());
        run.setLeaseToken("lease-ledger");
        run.setLeaseExpiresAt(LocalDateTime.now().plusHours(1));
        run.setExternalCallsReserved(0);
        run.setRequestKey("reserve-ledger-test");
        datasetRunMapper.insert(run);

        assertThat(datasetRunService.reserveExternalCalls(run.getId(), "wrong-lease", 1, 5))
                .isEqualTo(DatasetRunService.ExternalCallReservation.LEASE_LOST);
        assertThat(datasetRunService.reserveExternalCalls(run.getId(), "lease-ledger", 3, 5))
                .isEqualTo(DatasetRunService.ExternalCallReservation.RESERVED);
        assertThat(datasetRunService.reserveExternalCalls(run.getId(), "lease-ledger", 3, 5))
                .isEqualTo(DatasetRunService.ExternalCallReservation.BUDGET_EXCEEDED);
        assertThat(datasetRunMapper.selectById(run.getId()).getExternalCallsReserved()).isEqualTo(3);

        datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, run.getId())
                .set(DatasetRunEntity::getLeaseExpiresAt, LocalDateTime.now().minusSeconds(1)));
        assertThat(datasetRunService.reserveExternalCalls(run.getId(), "lease-ledger", 1, 5))
                .isEqualTo(DatasetRunService.ExternalCallReservation.LEASE_LOST);
        assertThat(datasetRunMapper.selectById(run.getId()).getExternalCallsReserved()).isEqualTo(3);
        datasetRunMapper.deleteById(run.getId());
    }

    @Test
    @Transactional
    void expiredLeaseIsInterruptedAndReleasesToken() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-lease-expired");
        run.setEvaluatorId("ev-lease-expired");
        run.setEvaluatorKey("ev-lease-expired");
        run.setRunMode("REPLAY");
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        run.setItemCount(1);
        run.setPassCount(0);
        run.setFailCount(0);
        run.setRequestKey("lease-expired-request");
        run.setLeaseToken("expired-token");
        run.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        run.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);
        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setRunId(run.getId());
        item.setItemId("lease-expired-item");
        datasetRunItemMapper.insert(item);

        datasetRunService.recoverExpiredLeases();

        DatasetRunEntity recovered = datasetRunMapper.selectById(run.getId());
        assertThat(recovered.getStatus()).isEqualTo(DatasetRunEntity.STATUS_INTERRUPTED);
        assertThat(recovered.getLeaseToken()).isNull();
        assertThat(recovered.getLeaseExpiresAt()).isNull();
        assertThat(datasetRunItemMapper.selectById(item.getId()).getResultDetailsJson())
                .contains("LEASE_EXPIRED").contains("INCOMPLETE");
    }

    @Test
    @Transactional
    void expiredLeaseTokenCannotRefreshOrFinish() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-lease-expired-token");
        run.setEvaluatorId("ev-lease-expired-token");
        run.setEvaluatorKey("ev-lease-expired-token");
        run.setRunMode("REPLAY");
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        run.setItemCount(1);
        run.setPassCount(0);
        run.setFailCount(0);
        run.setLeaseToken("expired-token");
        run.setLeaseExpiresAt(LocalDateTime.now().minusMinutes(1));
        run.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);
        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setRunId(run.getId());
        item.setItemId("lease-expired-token-item");
        item.setLabel(EvaluatorEntity.LABEL_PASS);
        item.setScore(java.math.BigDecimal.valueOf(100));
        datasetRunItemMapper.insert(item);

        datasetRunService.refreshProgress(run);
        datasetRunService.finish(run, "expired-token");

        DatasetRunEntity unchanged = datasetRunMapper.selectById(run.getId());
        assertThat(unchanged.getStatus()).isEqualTo(DatasetRunEntity.STATUS_RUNNING);
        assertThat(unchanged.getPassCount()).isZero();
    }

    @Test
    void startupRecoveryDoesNotInterruptAnotherInstanceLiveLease() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-live-lease");
        run.setEvaluatorId("ev-live-lease");
        run.setEvaluatorKey("ev-live-lease");
        run.setRunMode("REPLAY");
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        run.setItemCount(1);
        run.setPassCount(0);
        run.setFailCount(0);
        run.setRequestKey("live-lease-" + System.nanoTime());
        run.setRequestHash("hash");
        run.setConfigSnapshotJson("{}");
        run.setLeaseToken("another-instance-token");
        run.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        run.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);

        datasetRunService.recoverInterrupted();

        DatasetRunEntity alive = datasetRunMapper.selectById(run.getId());
        assertThat(alive.getStatus()).isEqualTo(DatasetRunEntity.STATUS_RUNNING);
        assertThat(alive.getLeaseToken()).isEqualTo("another-instance-token");
        datasetRunMapper.deleteById(run.getId());
    }

    @Test
    void admissionSlotIsAtomicReleasedAndCleansTerminalOwner() throws Exception {
        String datasetA = createDataset("slot-a");
        fakeLlm.outputs.put("q-slot-a", "ok");
        addItem(datasetA, "q-slot-a", "ok", null);
        String evaluatorA = createEvaluator("slot-a", "EXPRESSION", exactMatchSpec());
        String firstRun = awaitRun(datasetA,
                runAsync(datasetA, evaluatorA, "REPLAY", "slot-a-run", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        assertThat(datasetRunSlotMapper.selectById(DatasetRunSlotEntity.slotKey(1))).isNull();

        // 残留槽位指向终态 run：下一次受理应回收并成功占用。
        DatasetRunSlotEntity stale = new DatasetRunSlotEntity();
        stale.setSlotKey(DatasetRunSlotEntity.slotKey(1));
        stale.setRunId(firstRun);
        stale.setAcquiredAt(LocalDateTime.now());
        stale.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        datasetRunSlotMapper.insert(stale);

        String datasetB = createDataset("slot-b");
        fakeLlm.outputs.put("q-slot-b", "ok");
        addItem(datasetB, "q-slot-b", "ok", null);
        String evaluatorB = createEvaluator("slot-b", "EXPRESSION", exactMatchSpec());
        String secondRun = runAsync(datasetB, evaluatorB, "REPLAY", "slot-b-run", 202)
                .path("data").path("runId").asText();
        DatasetRunSlotEntity held = datasetRunSlotMapper.selectById(DatasetRunSlotEntity.slotKey(1));
        assertThat(held.getRunId()).isEqualTo(secondRun);
        awaitRun(datasetB, secondRun);
        assertThat(datasetRunSlotMapper.selectById(DatasetRunSlotEntity.slotKey(1))).isNull();

        // 已有 active run 的槽位不能被抢占。
        DatasetRunEntity active = new DatasetRunEntity();
        active.setWorkspaceId("ws-default");
        active.setDatasetId(datasetB);
        active.setEvaluatorId(evaluatorB);
        active.setEvaluatorKey("slot-b");
        active.setRunMode("REPLAY");
        active.setStatus(DatasetRunEntity.STATUS_RUNNING);
        active.setLeaseToken("slot-test-token");
        active.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        active.setItemCount(0);
        active.setPassCount(0);
        active.setFailCount(0);
        active.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(active);
        DatasetRunSlotEntity occupied = new DatasetRunSlotEntity();
        occupied.setSlotKey(DatasetRunSlotEntity.slotKey(1));
        occupied.setRunId(active.getId());
        occupied.setAcquiredAt(LocalDateTime.now());
        occupied.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        datasetRunSlotMapper.insert(occupied);
        try {
            String denied = mockMvc.perform(MockMvcRequestBuilders.post(
                            "/api/v1/datasets/" + datasetB + "/runs/async")
                            .header("Authorization", adminAuth())
                            .header("Idempotency-Key", "slot-occupied")
                            .contentType("application/json")
                            .content(objectMapper.writeValueAsString(Map.of(
                                    "evaluatorIds", List.of(evaluatorB), "mode", "REPLAY"))))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(readTree(denied).path("code").asInt()).isEqualTo(50301);
        } finally {
            datasetRunSlotMapper.deleteById(DatasetRunSlotEntity.slotKey(1));
            datasetRunMapper.deleteById(active.getId());
        }
    }

    @Test
    void runTokenBudgetIsValidatedAndFrozenInSnapshot() throws Exception {
        String datasetId = createDataset("token-budget-api");
        fakeLlm.outputs.put("q-token-budget", "ok");
        addItem(datasetId, "q-token-budget", "ok", null);
        String evaluatorId = createEvaluator("token-budget-api", "EXPRESSION", exactMatchSpec());

        Map<String, Object> invalid = new HashMap<>();
        invalid.put("evaluatorIds", List.of(evaluatorId));
        invalid.put("mode", "REPLAY");
        invalid.put("maxTokens", 0);
        String bad = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "token-budget-invalid")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(invalid)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(readTree(bad).path("code").asInt()).isEqualTo(40000);

        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorIds", List.of(evaluatorId));
        body.put("mode", "REPLAY");
        body.put("maxTokens", 5000);
        JsonNode accepted = readTree(mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "token-budget-valid")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String runId = accepted.path("data").path("runId").asText();
        JsonNode detail = awaitRun(datasetId, runId);
        assertThat(detail.path("data").path("run").path("maxTokens").asLong()).isEqualTo(5000L);
        assertThat(detail.path("data").path("run").path("configSnapshot").path("budget")
                .path("maxTokens").asLong()).isEqualTo(5000L);
    }

    @Test
    @Transactional
    void runTokenBudgetReservesSettlesAndNeverOversells() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-token-budget");
        run.setEvaluatorId("ev-token-budget");
        run.setEvaluatorKey("ev-token-budget");
        run.setRunMode("REPLAY");
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        run.setItemCount(1);
        run.setPassCount(0);
        run.setFailCount(0);
        run.setLeaseToken("token-budget-lease");
        run.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        run.setMaxTokens(7000L);
        run.setTokensReserved(0L);
        run.setTokensSpent(0L);
        run.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);

        runTokenBudgetStore.bind(run.getId(), "token-budget-lease", 7000L);
        try {
            var first = runTokenBudgetStore.reserve("", "");
            var second = runTokenBudgetStore.reserve("", "");
            var third = runTokenBudgetStore.reserve("", "");
            var fourth = runTokenBudgetStore.reserve("", "");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> runTokenBudgetStore.reserve("", ""))
                    .isInstanceOf(RunTokenBudgetStore.BudgetExceededException.class);
            runTokenBudgetStore.settle(first, 100, 200);
            var after = datasetRunMapper.selectById(run.getId());
            assertThat(after.getTokensSpent()).isEqualTo(300L);
            assertThat(after.getTokensReserved()).isEqualTo(4608L);
            runTokenBudgetStore.release(second);
            runTokenBudgetStore.release(third);
            runTokenBudgetStore.release(fourth);
        } finally {
            runTokenBudgetStore.clear();
        }
        DatasetRunEntity settled = datasetRunMapper.selectById(run.getId());
        assertThat(settled.getTokensSpent()).isEqualTo(300L);
        assertThat(settled.getTokensReserved()).isZero();
    }

    @Test
    @Transactional
    void staleLeaseCannotRefreshOrFinishRun() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-lease");
        run.setEvaluatorId("ev-lease");
        run.setEvaluatorKey("ev-lease");
        run.setRunMode("REPLAY");
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        run.setItemCount(1);
        run.setPassCount(0);
        run.setFailCount(0);
        run.setLeaseToken("stale-token");
        run.setLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        run.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);
        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setRunId(run.getId());
        item.setItemId("lease-item-1");
        item.setLabel(EvaluatorEntity.LABEL_PASS);
        item.setScore(java.math.BigDecimal.valueOf(100));
        datasetRunItemMapper.insert(item);

        // 新 worker 已抢占租约：旧 token 不能刷新计数，也不能完成
        run.setLeaseToken("new-token");
        datasetRunService.refreshProgress(run);
        assertThat(datasetRunMapper.selectById(run.getId()).getPassCount()).isZero();
        datasetRunService.finish(run, run.getLeaseToken());
        assertThat(datasetRunMapper.selectById(run.getId()).getStatus())
                .isEqualTo(DatasetRunEntity.STATUS_RUNNING);

        // 原持有者仍有未过期租约时可以刷新/完成
        run.setLeaseToken("stale-token");
        datasetRunService.refreshProgress(run);
        assertThat(datasetRunMapper.selectById(run.getId()).getPassCount()).isEqualTo(1);
        datasetRunService.finish(run, run.getLeaseToken());
        assertThat(datasetRunMapper.selectById(run.getId()).getStatus())
                .isEqualTo(DatasetRunEntity.STATUS_COMPLETED);
    }

    @Test
    void finishCannotOverwriteCancelledRun() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-cancel-finish");
        run.setEvaluatorId("ev-cancel-finish");
        run.setEvaluatorKey("ev-cancel-finish");
        run.setRunMode("REPLAY");
        run.setStatus(DatasetRunEntity.STATUS_CANCELLED);
        run.setItemCount(1);
        run.setPassCount(0);
        run.setFailCount(0);
        run.setStartedAt(LocalDateTime.now());
        run.setFinishedAt(LocalDateTime.now());
        run.setLeaseToken("cancelled-token");
        datasetRunMapper.insert(run);

        datasetRunService.finish(run, "cancelled-token");

        assertThat(datasetRunMapper.selectById(run.getId()).getStatus())
                .isEqualTo(DatasetRunEntity.STATUS_CANCELLED);
    }

    @Test
    @Transactional
    void cancelPendingRunMarksUnexecutedItems() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-cancel-pending");
        run.setEvaluatorId("ev-cancel-pending");
        run.setEvaluatorKey("ev-cancel-pending");
        run.setRunMode("REPLAY");
        run.setStatus(DatasetRunEntity.STATUS_PENDING);
        run.setItemCount(1);
        run.setPassCount(0);
        run.setFailCount(0);
        run.setRequestKey("cancel-pending-" + System.nanoTime());
        run.setRequestHash("hash");
        run.setConfigSnapshotJson("{}");
        run.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);

        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setRunId(run.getId());
        item.setItemId("cancel-pending-item");
        item.setSampleSnapshotJson("{}");
        item.setResultDetailsJson("{\"status\":\"PENDING\"}");
        datasetRunItemMapper.insert(item);

        DatasetRunEntity cancelled = datasetRunService.cancel("ws-default", "ds-cancel-pending", run.getId());

        assertThat(cancelled.getStatus()).isEqualTo(DatasetRunEntity.STATUS_CANCELLED);
        assertThat(cancelled.getFinishedAt()).isNotNull();
        assertThat(datasetRunItemMapper.selectById(item.getId()).getResultDetailsJson())
                .contains("INCOMPLETE").contains("CANCELLED");
    }

    @Test
    void startupRecoveryMarksRunningAsInterruptedWithoutRetry() {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId("ws-default");
        run.setDatasetId("ds-recovery");
        run.setEvaluatorId("ev-recovery");
        run.setEvaluatorKey("ev-recovery");
        run.setRunMode("REPLAY");
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        run.setItemCount(1);
        run.setPassCount(0);
        run.setFailCount(0);
        run.setRequestKey("recover-key-" + System.nanoTime());
        run.setRequestHash("hash");
        run.setConfigSnapshotJson("{}");
        run.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);

        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setRunId(run.getId());
        item.setItemId("recover-item");
        item.setSampleSnapshotJson("{}");
        item.setResultDetailsJson("{\"status\":\"PENDING\"}");
        datasetRunItemMapper.insert(item);

        datasetRunService.recoverInterrupted();

        DatasetRunEntity recovered = datasetRunMapper.selectById(run.getId());
        assertThat(recovered.getStatus()).isEqualTo(DatasetRunEntity.STATUS_INTERRUPTED);
        assertThat(recovered.getFinishedAt()).isNotNull();
        DatasetRunItemEntity recoveredItem = datasetRunItemMapper.selectById(item.getId());
        assertThat(recoveredItem.getLabel()).isNull();
        assertThat(recoveredItem.getResultDetailsJson()).contains("INCOMPLETE").contains("INTERRUPTED");
    }

    @Test
    void ninePassOneErrorProducesPassRate100AndCoverage90() throws Exception {
        String datasetId = createDataset("pilot-stats");
        for (int i = 0; i < 9; i++) {
            String input = "q-pass-" + i;
            fakeLlm.outputs.put(input, "OK");
            addItem(datasetId, input, "OK", null);
        }
        // REPLAY 下没有 input 的样本无法生成 → ERROR（执行失败，不是业务 FAIL）
        addItem(datasetId, null, "OK", "trace-without-input");
        String evaluatorId = createEvaluator("pilot-stats-exact", "EXPRESSION", exactMatchSpec());

        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "stats-1", 202);
        JsonNode detail = awaitRun(datasetId, accepted.path("data").path("runId").asText());
        JsonNode run = detail.path("data").path("run");
        assertThat(run.path("passCount").asInt()).isEqualTo(9);
        assertThat(run.path("failCount").asInt()).isZero();
        assertThat(run.path("errorCount").asInt()).isEqualTo(1);
        assertThat(run.path("unknownCount").asInt()).isZero();
        assertThat(run.path("passRate").asDouble()).isEqualTo(100.0);
        assertThat(run.path("coverage").asDouble()).isEqualTo(90.0);
        assertThat(run.path("status").asText()).isEqualTo("PARTIAL_FAILED");

        JsonNode errorItem = null;
        for (JsonNode it : detail.path("data").path("items")) {
            if ("ERROR".equals(it.path("label").asText())) {
                errorItem = it;
            }
        }
        assertThat(errorItem).isNotNull();
        assertThat(errorItem.path("resultDetails").path("reasonCode").asText()).isEqualTo("INPUT_MISSING");
    }

    @Test
    void contentPermissionRedactsBodyAndCrossWorkspaceIsNotFound() throws Exception {
        String datasetId = createDataset("pilot-perm");
        fakeLlm.outputs.put("q-perm", "答案");
        Map<String, Object> itemBody = new HashMap<>();
        itemBody.put("input", "q-perm");
        itemBody.put("expectedOutput", "答案");
        itemBody.put("schemaVersion", 2);
        itemBody.put("retrievalContext", List.of(Map.of("content", "敏感检索内容")));
        itemBody.put("expectedTools", List.of(Map.of("name", "query_order",
                "inputParameters", Map.of("orderId", "敏感参数"))));
        itemBody.put("turns", List.of(Map.of("role", "user", "content", "敏感多轮内容")));
        itemBody.put("expectations", Map.of("must", List.of("敏感期望")));
        JsonNode added = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", itemBody));
        assertThat(added.path("code").asInt()).isZero();
        String evaluatorId = createEvaluator("pilot-perm-exact", "EXPRESSION", exactMatchSpec());
        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "perm-1", 202);
        String runId = accepted.path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        // 自定义角色：有 dataset:query / evaluation:query，但没有 trace:content
        JsonNode role = readTree(postApi("/api/v1/sys/roles", Map.of("name", "pilot contentless")));
        String roleId = role.path("data").path("id").asText();
        putApi("/api/v1/sys/roles/" + roleId + "/permissions", Map.of("permissions", List.of(
                "workspace:trust:dataset:query", "workspace:trust:dataset:manage",
                "workspace:trust:evaluation:query")));
        postApi("/api/v1/sys/users", Map.of(
                "username", "pilot-viewer", "password", "viewer-123456",
                "name", "试点只读", "roleId", roleId));
        String login = postApi("/api/v1/auth/login",
                Map.of("username", "pilot-viewer", "password", "viewer-123456"));
        String viewerToken = "Bearer " + readTree(login).path("data").path("token").asText();

        String restricted = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId)
                        .header("Authorization", viewerToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode data = readTree(restricted).path("data");
        assertThat(data.path("contentRestricted").asBoolean()).isTrue();
        JsonNode item = data.path("items").get(0);
        assertThat(item.path("sampleSnapshot").has("input")).isFalse();
        assertThat(item.path("sampleSnapshot").has("expectedOutput")).isFalse();
        assertThat(item.path("sampleSnapshot").has("retrievalContext")).isFalse();
        assertThat(item.path("sampleSnapshot").has("expectedTools")).isFalse();
        assertThat(item.path("sampleSnapshot").has("turns")).isFalse();
        assertThat(item.path("sampleSnapshot").has("expectations")).isFalse();
        assertThat(item.path("sampleSnapshot").has("toolsCalled")).isFalse();
        assertThat(item.path("resultDetails").has("candidateOutput")).isFalse();
        assertThat(item.path("evaluatorResults").get(0).has("reason")).isFalse();
        assertThat(item.path("evaluatorResults").get(0).has("criteria")).isFalse();
        assertThat(item.path("resultDetails").path("evaluators").get(0).has("reason")).isFalse();
        assertThat(item.path("replayOutput").isMissingNode() || item.path("replayOutput").isNull()).isTrue();
        assertThat(item.path("reason").asText()).contains("无内容查看权限");

        // 只有 manage、没有 trace:content 的账号不能盲编辑正文（否则会把正文覆盖为空）
        MockHttpServletResponse updateAttempt = mockMvc.perform(MockMvcRequestBuilders.put(
                        "/api/v1/datasets/" + datasetId + "/items/" + item.path("itemId").asText())
                        .header("Authorization", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "input", "attempted overwrite", "expectedOutput", "x"))))
                .andReturn().getResponse();
        assertThat(readTree(updateAttempt.getContentAsString(StandardCharsets.UTF_8))
                .path("code").asInt()).isEqualTo(40301);

        // 添加、导入、创建合成任务同样不能绕过内容权限
        MockHttpServletResponse addAttempt = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/items")
                        .header("Authorization", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "input", "attempted add", "expectedOutput", "x"))))
                .andReturn().getResponse();
        assertThat(readTree(addAttempt.getContentAsString(StandardCharsets.UTF_8))
                .path("code").asInt()).isEqualTo(40301);

        MockHttpServletResponse importAttempt = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/items/import")
                        .header("Authorization", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "format", "jsonl", "content", "{\"input\":\"x\"}\n"))))
                .andReturn().getResponse();
        assertThat(readTree(importAttempt.getContentAsString(StandardCharsets.UTF_8))
                .path("code").asInt()).isEqualTo(40301);

        MockHttpServletResponse synthesisAttempt = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/synthesis-jobs")
                        .header("Authorization", viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceType", "SCRATCH", "source", Map.of(), "config", Map.of()))))
                .andReturn().getResponse();
        assertThat(readTree(synthesisAttempt.getContentAsString(StandardCharsets.UTF_8))
                .path("code").asInt()).isEqualTo(40301);

        // 导出仍可用，但不能带出正文
        String restrictedExport = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetId + "/items/export?format=jsonl")
                        .header("Authorization", viewerToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(restrictedExport).doesNotContain("q-perm");
        assertThat(restrictedExport).doesNotContain("敏感检索内容");

        JsonNode restrictedTasks = readTree(mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId + "/tasks")
                        .header("Authorization", viewerToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
        assertThat(restrictedTasks.path("contentRestricted").asBoolean()).isTrue();
        JsonNode restrictedTask = restrictedTasks.path("records").get(0);
        assertThat(restrictedTask.get("reason") == null
                || restrictedTask.get("reason").isNull()).isTrue();
        assertThat(restrictedTask.has("criteria")).isFalse();
        assertThat(restrictedTask.path("reasonCode").asText()).isNotBlank();

        // 另一工作空间成员按 id 读取 → 40401
        WorkspaceEntity other = new WorkspaceEntity();
        other.setId("ws-pilot-other");
        other.setCode("pilot-other");
        other.setName("试点隔离空间");
        other.setIsDefault(0);
        other.setCreatedAt(LocalDateTime.now());
        workspaceMapper.insert(other);
        JsonNode cancelRole = readTree(postApi("/api/v1/sys/roles", Map.of("name", "pilot cancel operator")));
        String cancelRoleId = cancelRole.path("data").path("id").asText();
        putApi("/api/v1/sys/roles/" + cancelRoleId + "/permissions", Map.of("permissions", List.of(
                "workspace:trust:dataset:query", "workspace:trust:evaluation:run",
                "workspace:trust:evaluation:query")));
        postApi("/api/v1/sys/users", Map.of(
                "username", "pilot-other-user", "password", "other-123456",
                "name", "试点隔离", "roleId", cancelRoleId));
        UserEntity otherUser = userMapper.selectOne(Wrappers.<UserEntity>lambdaQuery()
                .eq(UserEntity::getUsername, "pilot-other-user"));
        UserWorkspaceEntity membership = new UserWorkspaceEntity();
        membership.setUserId(otherUser.getId());
        membership.setWorkspaceId("ws-pilot-other");
        membership.setRoleId(cancelRoleId);
        membership.setCreatedAt(LocalDateTime.now());
        userWorkspaceMapper.insert(membership);
        String otherLogin = postApi("/api/v1/auth/login",
                Map.of("username", "pilot-other-user", "password", "other-123456"));
        String otherToken = "Bearer " + readTree(otherLogin).path("data").path("token").asText();

        String denied = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId)
                        .header("Authorization", otherToken)
                        .header("X-Workspace-Id", "ws-pilot-other"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(readTree(denied).path("code").asInt()).isEqualTo(40401);

        // 另一工作空间不能读取该 run 的 Gate 历史（有 EVALUATION_QUERY 也返回 40401）
        createGate(runId);
        String crossHistory = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/quality-gates/history?runId=" + runId)
                        .header("Authorization", otherToken)
                        .header("X-Workspace-Id", "ws-pilot-other"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(readTree(crossHistory).path("code").asInt()).isEqualTo(40401);

        // 无 EVALUATION_RUN 权限不能取消 → 40301
        String noPermission = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId + "/cancel")
                        .header("Authorization", viewerToken))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(readTree(noPermission).path("code").asInt()).isEqualTo(40301);

        // 有运行权限但属于另一工作空间 → 40401，不泄露存在性
        String crossWorkspace = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId + "/cancel")
                        .header("Authorization", otherToken)
                        .header("X-Workspace-Id", "ws-pilot-other"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(readTree(crossWorkspace).path("code").asInt()).isEqualTo(40401);
    }

    @Test
    void gatePassFailAndLegacyBlocked() throws Exception {
        fakeLlm.outputs.put("q-gate-pass", "ok");
        String passDataset = createDataset("gate-pass");
        addItem(passDataset, "q-gate-pass", "ok", null);
        String passEvaluator = createEvaluator("gate-pass-exact", "EXPRESSION", exactMatchSpec());
        String passRun = awaitRun(passDataset, runAsync(passDataset, passEvaluator, "REPLAY", "gate-pass-1", 202)
                .path("data").path("runId").asText()).path("data").path("run").path("id").asText();
        JsonNode passGate = createGate(passRun);
        assertThat(passGate.path("data").path("decision").asText()).isEqualTo("PASS");
        assertThat(passGate.path("data").path("checks").size()).isGreaterThan(5);
        for (JsonNode check : passGate.path("data").path("checks")) {
            assertThat(check.path("status").asText()).as(check.path("code").asText()).isEqualTo("PASS");
        }
        String passGateId = passGate.path("data").path("id").asText();
        JsonNode verification = readTree(get("/api/v1/quality-gates/" + passGateId + "/verification"));
        assertThat(verification.path("data").path("usable").asBoolean()).isTrue();
        assertThat(verification.path("data").path("expired").asBoolean()).isFalse();

        QualityGateEntity expiring = qualityGateMapper.selectById(passGateId);
        expiring.setValidUntil(LocalDateTime.now().minusHours(1));
        qualityGateMapper.updateById(expiring);
        JsonNode expired = readTree(get("/api/v1/quality-gates/" + passGateId + "/verification"));
        assertThat(expired.path("data").path("usable").asBoolean()).isFalse();
        assertThat(expired.path("data").path("expired").asBoolean()).isTrue();
        assertThat(expired.path("data").path("reasons").toString()).contains("EXPIRED");

        // 同一 run 产生新 Gate 后，旧 Gate 明确标 SUPERSEDED；微秒级先后即可区分
        JsonNode newerGate = createGate(passRun);
        QualityGateEntity tied = qualityGateMapper.selectById(newerGate.path("data").path("id").asText());
        tied.setCreatedAt(expiring.getCreatedAt().plusNanos(1000));
        qualityGateMapper.updateById(tied);
        JsonNode superseded = readTree(get("/api/v1/quality-gates/" + passGateId + "/verification"));
        assertThat(superseded.path("data").path("superseded").asBoolean()).isTrue();
        assertThat(superseded.path("data").path("reasons").toString()).contains("SUPERSEDED");
        JsonNode weakPolicy = readTree(postApi("/api/v1/quality-gates",
                Map.of("runId", passRun, "minCoverage", 0)));
        assertThat(weakPolicy.path("code").asInt()).as("cannot weaken gate policy").isNotZero();

        // 历史 Gate 指纹为空：不能默认视为一致
        qualityGateMapper.update(null, Wrappers.<QualityGateEntity>lambdaUpdate()
                .eq(QualityGateEntity::getId, passGateId)
                .set(QualityGateEntity::getCandidateFingerprint, null));
        JsonNode missingFingerprint = readTree(get("/api/v1/quality-gates/" + passGateId + "/verification"));
        assertThat(missingFingerprint.path("data").path("usable").asBoolean()).isFalse();
        assertThat(missingFingerprint.path("data").path("reasons").toString())
                .contains("CANDIDATE_FINGERPRINT_MISSING");

        // run 被删除后，历史 Gate 不能继续 usable，也不能让 CI 只拿到 404 就误判
        datasetRunMapper.deleteById(passRun);
        JsonNode missingRun = readTree(get("/api/v1/quality-gates/" + passGateId + "/verification"));
        assertThat(missingRun.path("data").path("usable").asBoolean()).isFalse();
        assertThat(missingRun.path("data").path("reasons").toString()).contains("RUN_MISSING");
        assertThat(qualityGateMapper.selectCount(Wrappers.<QualityGateEntity>lambdaQuery()
                .eq(QualityGateEntity::getWorkspaceId, "ws-default")
                .eq(QualityGateEntity::getRunId, passRun))).isGreaterThanOrEqualTo(2);
        JsonNode historyAfterRunDelete = readTree(get("/api/v1/quality-gates/history?runId=" + passRun));
        assertThat(historyAfterRunDelete.path("code").asInt()).isZero();
        assertThat(historyAfterRunDelete.path("data").size()).isGreaterThanOrEqualTo(2);
        for (JsonNode row : historyAfterRunDelete.path("data")) {
            assertThat(row.path("verification").path("usable").asBoolean()).isFalse();
            assertThat(row.path("verification").path("reasons").toString()).contains("RUN_MISSING");
        }

        fakeLlm.outputs.put("q-gate-fail", "bad");
        String failDataset = createDataset("gate-fail");
        addItem(failDataset, "q-gate-fail", "ok", null);
        String failEvaluator = createEvaluator("gate-fail-exact", "EXPRESSION", exactMatchSpec());
        String failRun = awaitRun(failDataset, runAsync(failDataset, failEvaluator, "REPLAY", "gate-fail-1", 202)
                .path("data").path("runId").asText()).path("data").path("run").path("id").asText();
        JsonNode failGate = createGate(failRun);
        assertThat(failGate.path("data").path("decision").asText()).isEqualTo("FAIL");
        assertThat(failGate.path("data").path("checks").toString()).contains("FAIL_THRESHOLD");
        JsonNode failVerification = readTree(get("/api/v1/quality-gates/"
                + failGate.path("data").path("id").asText() + "/verification"));
        assertThat(failVerification.path("data").path("usable").asBoolean()).isFalse();
        assertThat(failVerification.path("data").path("reasons").toString()).contains("DECISION_FAIL");

        // 历史 run 没有 revision/version 证据：必须 BLOCKED，不能默认 PASS
        DatasetRunEntity legacy = new DatasetRunEntity();
        legacy.setWorkspaceId("ws-default");
        legacy.setDatasetId(passDataset);
        legacy.setEvaluatorId(passEvaluator);
        legacy.setEvaluatorKey("gate-pass-exact");
        legacy.setStatus(DatasetRunEntity.STATUS_COMPLETED);
        legacy.setItemCount(1);
        legacy.setPassCount(1);
        legacy.setFailCount(0);
        legacy.setStartedAt(LocalDateTime.now());
        legacy.setFinishedAt(LocalDateTime.now());
        datasetRunMapper.insert(legacy);
        JsonNode legacyGate = createGate(legacy.getId());
        assertThat(legacyGate.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(legacyGate.path("data").path("checks").toString()).contains("REVISION_BOUND");
    }

    @Test
    void qualityCaseClosesOnlyWithRegressionGatePass() throws Exception {
        String datasetId = createDataset("quality-case");
        fakeLlm.outputs.put("q-case", "ok");
        addItem(datasetId, "q-case", "ok", null);
        String evaluatorId = createEvaluator("quality-case-exact", "EXPRESSION", exactMatchSpec());
        String runId = runAsync(datasetId, evaluatorId, "REPLAY", "case-run", 202)
                .path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        JsonNode created = readTree(postApi("/api/v1/quality-cases", Map.of(
                "sourceRunId", runId,
                "title", "答案引用错误",
                "phenomenon", "候选引用了不存在的订单",
                "rootCauseCategory", "RETRIEVAL",
                "owner", "team-a")));
        assertThat(created.path("code").asInt()).isZero();
        String caseId = created.path("data").path("id").asText();
        assertThat(created.path("data").path("status").asText()).isEqualTo("OPEN");

        assertThat(readTree(patchApi("/api/v1/quality-cases/" + caseId,
                Map.of("status", "FIXED"))).path("code").asInt()).isNotZero();
        assertThat(readTree(patchApi("/api/v1/quality-cases/" + caseId,
                Map.of("status", "INVESTIGATING"))).path("code").asInt()).isZero();
        JsonNode fixed = readTree(patchApi("/api/v1/quality-cases/" + caseId,
                Map.of("status", "FIXED", "fixFingerprint", "prompt-v2",
                        "fixNote", "补充检索引用校验")));
        assertThat(fixed.path("data").path("status").asText()).isEqualTo("FIXED");

        assertThat(readTree(postApi("/api/v1/quality-cases/" + caseId + "/close", Map.of()))
                .path("code").asInt()).isNotZero();

        createGate(runId);
        JsonNode linked = readTree(postApi("/api/v1/quality-cases/" + caseId + "/regression",
                Map.of("runId", runId)));
        assertThat(linked.path("data").path("regressionRunId").asText()).isEqualTo(runId);
        JsonNode closed = readTree(postApi("/api/v1/quality-cases/" + caseId + "/close", Map.of()));
        assertThat(closed.path("data").path("status").asText()).isEqualTo("CLOSED");
        assertThat(closed.path("data").path("verifiedBy").asText()).isNotEmpty();

        JsonNode again = readTree(postApi("/api/v1/quality-cases/" + caseId + "/close", Map.of()));
        assertThat(again.path("data").path("status").asText()).isEqualTo("CLOSED");
        JsonNode list = readTree(get("/api/v1/quality-cases?runId=" + runId));
        assertThat(list.path("data").size()).isEqualTo(1);
        assertThat(list.path("data").get(0).path("status").asText()).isEqualTo("CLOSED");
    }

    @Test
    void pairwiseComparesTwoCompletedRunsBidirectionally() throws Exception {
        String datasetId = createDataset("pairwise-runs");
        fakeLlm.outputs.put("q-pw", "base");
        addItem(datasetId, "q-pw", "ok", null);
        String evaluatorId = createEvaluator("pairwise-exact", "EXPRESSION", exactMatchSpec());

        fakeLlm.outputs.put("q-pw", "alpha");
        JsonNode runA = runAsync(datasetId, evaluatorId, "REPLAY", "pw-a", 202);
        String runAId = runA.path("data").path("runId").asText();
        awaitRun(datasetId, runAId);

        fakeLlm.outputs.put("q-pw", "beta");
        JsonNode runB = runAsyncVariant(datasetId, evaluatorId, "pw-b",
                "You are a different assistant.");
        String runBId = runB.path("data").path("runId").asText();
        awaitRun(datasetId, runBId);

        // AB 方向选 A，BA 方向选 B；映射回原始候选后一致 -> A 胜
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"A better\"}");
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"B\",\"confidence\":0.8,\"reason\":\"A better\"}");
        JsonNode eval = readTree(postApi("/api/v1/datasets/" + datasetId + "/pairwise-evaluations",
                Map.of("runAId", runAId, "runBId", runBId, "samplesPerDirection", 1)));
        assertThat(eval.path("code").asInt()).isZero();
        assertThat(eval.path("data").path("aWins").asInt()).isEqualTo(1);
        assertThat(eval.path("data").path("bWins").asInt()).isZero();
        assertThat(eval.path("data").path("unknownCount").asInt()).isZero();
        assertThat(eval.path("data").path("preferenceRate").asDouble()).isEqualTo(100.0);
        assertThat(eval.path("data").path("items").get(0).path("preference").asText()).isEqualTo("A");

        // 两个方向都选 A：映射后 AB=A、BA=B，方向冲突 -> UNKNOWN
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"x\"}");
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"x\"}");
        JsonNode conflict = readTree(postApi("/api/v1/datasets/" + datasetId + "/pairwise-evaluations",
                Map.of("runAId", runAId, "runBId", runBId)));
        assertThat(conflict.path("data").path("unknownCount").asInt()).isEqualTo(1);
        assertThat(conflict.path("data").path("items").get(0).path("reason").asText())
                .isEqualTo("DIRECTIONS_DISAGREE");

        JsonNode list = readTree(get("/api/v1/datasets/" + datasetId
                + "/pairwise-evaluations?runAId=" + runAId + "&runBId=" + runBId));
        assertThat(list.path("data").size()).isEqualTo(2);

        // 两个评审模型各自双向一致，多数投票 -> A；每个方向、每个模型都独立留痕
        for (String model : List.of("judge-a", "judge-b")) {
            fakeLlm.pairwiseOutputs.add("{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"x\"}");
            fakeLlm.pairwiseOutputs.add("{\"winner\":\"B\",\"confidence\":0.9,\"reason\":\"x\"}");
        }
        JsonNode multi = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/pairwise-evaluations", Map.of(
                "runAId", runAId, "runBId", runBId,
                "judgeModels", List.of("judge-a", "judge-b"))));
        assertThat(multi.path("code").asInt()).isZero();
        assertThat(multi.path("data").path("judgeCount").asInt()).isEqualTo(2);
        assertThat(multi.path("data").path("judgeModels").size()).isEqualTo(2);
        assertThat(multi.path("data").path("protocolVersion").asText())
                .isEqualTo("pairwise/v2");
        assertThat(multi.path("data").path("aWins").asInt()).isEqualTo(1);
        assertThat(multi.path("data").path("unknownCount").asInt()).isZero();

        // 两个评审给出相反结论且无多数 -> UNKNOWN，不把 1:1 当偏好
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"x\"}");
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"B\",\"confidence\":0.9,\"reason\":\"x\"}");
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"B\",\"confidence\":0.9,\"reason\":\"x\"}");
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"x\"}");
        JsonNode split = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/pairwise-evaluations", Map.of(
                "runAId", runAId, "runBId", runBId,
                "judgeModels", List.of("judge-a", "judge-b"))));
        assertThat(split.path("data").path("unknownCount").asInt()).isEqualTo(1);
        assertThat(split.path("data").path("items").get(0).path("reason").asText())
                .isEqualTo("MODEL_DISAGREEMENT");

        // 位置随机化：同一 seed 可复现，且每次调用都记录实际呈现顺序
        for (int i = 0; i < 4; i++) {
            fakeLlm.pairwiseOutputs.add(i % 2 == 0
                    ? "{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"x\"}"
                    : "{\"winner\":\"B\",\"confidence\":0.9,\"reason\":\"x\"}");
        }
        JsonNode randomized = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/pairwise-evaluations", Map.of(
                "runAId", runAId, "runBId", runBId,
                "judgeModels", List.of("judge-a"),
                "positionRandomized", true, "positionSeed", 42)));
        assertThat(randomized.path("data").path("protocolVersion").asText())
                .isEqualTo("pairwise/v3");
        assertThat(randomized.path("data").path("positionSeed").asLong()).isEqualTo(42L);
        assertThat(randomized.path("data").path("positionAFirstCount").asInt()
                + randomized.path("data").path("positionBFirstCount").asInt()).isEqualTo(2);
        JsonNode randomizedAgain = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/pairwise-evaluations", Map.of(
                "runAId", runAId, "runBId", runBId,
                "judgeModels", List.of("judge-a"),
                "positionRandomized", true, "positionSeed", 42)));
        assertThat(randomizedAgain.path("data").path("positionAFirstCount").asInt())
                .isEqualTo(randomized.path("data").path("positionAFirstCount").asInt());
        assertThat(randomizedAgain.path("data").path("positionBFirstCount").asInt())
                .isEqualTo(randomized.path("data").path("positionBFirstCount").asInt());

        // 人工偏好与模型偏好的一致性校准
        String evaluationId = multi.path("data").path("id").asText();
        String itemId = multi.path("data").path("items").get(0).path("itemId").asText();
        JsonNode review = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/pairwise-evaluations/" + evaluationId + "/reviews",
                Map.of("itemId", itemId, "preference", "A", "note", "人工确认 A 更好")));
        assertThat(review.path("code").asInt()).isZero();
        assertThat(review.path("data").path("reviewer").asText()).isEqualTo("user-admin");
        JsonNode calibration = readTree(get("/api/v1/datasets/" + datasetId
                + "/pairwise-evaluations/" + evaluationId + "/calibration"));
        assertThat(calibration.path("data").path("humanReviewedItems").asInt()).isEqualTo(1);
        assertThat(calibration.path("data").path("agreementCount").asInt()).isEqualTo(1);
        assertThat(calibration.path("data").path("humanAgreementRate").asDouble())
                .isEqualTo(100.0);
        JsonNode invalidReview = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/pairwise-evaluations/" + evaluationId + "/reviews",
                Map.of("itemId", itemId, "preference", "LEFT")));
        assertThat(invalidReview.path("code").asInt()).isEqualTo(40000);
    }

    @Test
    void staleItemPolicyPreviewsAndAppliesQuarantine() throws Exception {
        String datasetId = createDataset("stale-items");
        fakeLlm.outputs.put("stale-old", "ok");
        fakeLlm.outputs.put("stale-new", "ok");
        addItem(datasetId, "stale-old", "ok", null);
        addItem(datasetId, "stale-new", "ok", null);
        DatasetItemEntity old = datasetItemMapper.selectOne(
                Wrappers.<DatasetItemEntity>lambdaQuery()
                        .eq(DatasetItemEntity::getDatasetId, datasetId)
                        .eq(DatasetItemEntity::getInput, "stale-old"));
        old.setCreatedAt(LocalDateTime.now().minusDays(100));
        datasetItemMapper.updateById(old);

        JsonNode saved = readTree(putApi("/api/v1/datasets/" + datasetId + "/blueprint",
                Map.of("maxItemAgeDays", 30)));
        assertThat(saved.path("code").asInt()).isZero();
        JsonNode health = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(health.path("data").path("maxItemAgeDays").asInt()).isEqualTo(30);
        assertThat(health.path("data").path("staleItemCount").asInt()).isEqualTo(1);
        assertThat(health.path("data").path("staleItems").get(0).path("input").asText())
                .isEqualTo("stale-old");

        JsonNode preview = readTree(postApi(
                "/api/v1/datasets/" + datasetId + "/items/quarantine-stale",
                Map.of("apply", false)));
        assertThat(preview.path("data").path("staleCount").asInt()).isEqualTo(1);
        assertThat(preview.path("data").path("appliedCount").asInt()).isZero();

        JsonNode applied = readTree(postApi(
                "/api/v1/datasets/" + datasetId + "/items/quarantine-stale",
                Map.of("apply", true)));
        assertThat(applied.path("data").path("appliedCount").asInt()).isEqualTo(1);
        JsonNode detail = readTree(get("/api/v1/datasets/" + datasetId));
        JsonNode oldItem = null;
        for (JsonNode item : detail.path("data").path("items")) {
            if ("stale-old".equals(item.path("input").asText())) {
                oldItem = item;
            }
        }
        assertThat(oldItem.path("status").asText()).isEqualTo("QUARANTINED");
        assertThat(oldItem.path("invalidReason").asText()).isEqualTo("STALE_AGE_30D");

        String evaluatorId = createEvaluator("stale-exact", "EXPRESSION", exactMatchSpec());
        JsonNode run = runAsync(datasetId, evaluatorId, "REPLAY", "stale-run", 202);
        JsonNode runDetail = awaitRun(datasetId, run.path("data").path("runId").asText());
        assertThat(runDetail.path("data").path("run").path("itemCount").asInt()).isEqualTo(1);
    }

    @Test
    void autoQuarantineStaleOnlyRunsWhenBlueprintOptsIn() throws Exception {
        String optedIn = createDataset("stale-auto-on");
        String off = createDataset("stale-auto-off");
        addItem(optedIn, "auto-old", "ok", null);
        addItem(off, "manual-old", "ok", null);
        for (String datasetId : List.of(optedIn, off)) {
            DatasetItemEntity item = datasetItemMapper.selectOne(
                    Wrappers.<DatasetItemEntity>lambdaQuery()
                            .eq(DatasetItemEntity::getDatasetId, datasetId));
            item.setCreatedAt(LocalDateTime.now().minusDays(100));
            datasetItemMapper.updateById(item);
        }
        readTree(putApi("/api/v1/datasets/" + optedIn + "/blueprint",
                Map.of("maxItemAgeDays", 30, "autoQuarantineStale", true)));
        readTree(putApi("/api/v1/datasets/" + off + "/blueprint",
                Map.of("maxItemAgeDays", 30, "autoQuarantineStale", false)));

        int applied = datasetHealthService.autoQuarantineStaleAll();
        assertThat(applied).isGreaterThanOrEqualTo(1);
        DatasetItemEntity auto = datasetItemMapper.selectOne(
                Wrappers.<DatasetItemEntity>lambdaQuery()
                        .eq(DatasetItemEntity::getDatasetId, optedIn));
        DatasetItemEntity manual = datasetItemMapper.selectOne(
                Wrappers.<DatasetItemEntity>lambdaQuery()
                        .eq(DatasetItemEntity::getDatasetId, off));
        assertThat(auto.getStatus()).isEqualTo("QUARANTINED");
        assertThat(auto.getInvalidReason()).isEqualTo("STALE_AGE_30D");
        assertThat(manual.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void quarantinedDatasetItemsAreExcludedFromNewRunsButRemainRestorable() throws Exception {
        String datasetId = createDataset("quarantine-items");
        fakeLlm.outputs.put("q-keep", "ok");
        fakeLlm.outputs.put("q-drop", "ok");
        addItem(datasetId, "q-keep", "ok", null);
        addItem(datasetId, "q-drop", "ok", null);
        JsonNode detail = readTree(get("/api/v1/datasets/" + datasetId));
        JsonNode items = detail.path("data").path("items");
        assertThat(items.size()).isEqualTo(2);
        String dropId = null;
        for (JsonNode item : items) {
            if ("q-drop".equals(item.path("input").asText())) {
                dropId = item.path("id").asText();
            }
        }
        assertThat(dropId).isNotBlank();

        JsonNode invalidated = readTree(postApi(
                "/api/v1/datasets/" + datasetId + "/items/" + dropId + "/invalidate",
                Map.of("reason", "来源已过期")));
        assertThat(invalidated.path("data").path("status").asText()).isEqualTo("QUARANTINED");
        JsonNode health = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(health.path("data").path("invalidItemCount").asInt()).isEqualTo(1);
        assertThat(health.path("data").path("itemCount").asInt()).isEqualTo(1);
        assertThat(health.path("data").path("totalItemCount").asInt()).isEqualTo(2);

        String evaluatorId = createEvaluator("quarantine-exact", "EXPRESSION", exactMatchSpec());
        JsonNode run = runAsync(datasetId, evaluatorId, "REPLAY", "quarantine-run-1", 202);
        String runId = run.path("data").path("runId").asText();
        JsonNode runDetail = awaitRun(datasetId, runId);
        assertThat(runDetail.path("data").path("run").path("itemCount").asInt()).isEqualTo(1);

        JsonNode restored = readTree(postApi(
                "/api/v1/datasets/" + datasetId + "/items/" + dropId + "/restore", Map.of()));
        assertThat(restored.path("data").path("status").asText()).isEqualTo("ACTIVE");
        JsonNode healthAgain = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(healthAgain.path("data").path("invalidItemCount").asInt()).isZero();
        JsonNode runAgain = runAsync(datasetId, evaluatorId, "REPLAY", "quarantine-run-2", 202);
        JsonNode runAgainDetail = awaitRun(datasetId,
                runAgain.path("data").path("runId").asText());
        assertThat(runAgainDetail.path("data").path("run").path("itemCount").asInt()).isEqualTo(2);
    }

    @Test
    void autoRubricAuditsCandidatesAgainstUntouchedHoldout() throws Exception {
        String datasetId = createDataset("auto-rubric");
        fakeLlm.outputs.put("ar-fail", "wrong");
        fakeLlm.outputs.put("ar-pass", "ok");
        fakeLlm.outputs.put("ar-pass-1", "ok");
        fakeLlm.outputs.put("ar-pass-2", "ok");
        addItemFull(datasetId, "ar-dev-fail", "ok", "LOW", "DEV", "happy");
        addItemFull(datasetId, "ar-fail-1", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "ar-fail-2", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "ar-fail-3", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "ar-pass-1", "ok", "LOW", "HOLDOUT", "happy");
        addItemFull(datasetId, "ar-pass-2", "ok", "LOW", "HOLDOUT", "happy");
        String evaluatorId = createEvaluator("auto-rubric-exact", "EXPRESSION", exactMatchSpec());
        JsonNode run = runAsync(datasetId, evaluatorId, "REPLAY", "ar-run", 202);
        String runId = run.path("data").path("runId").asText();
        JsonNode detail = awaitRun(datasetId, runId);
        assertThat(detail.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("data").path("run").path("failCount").asInt()).isEqualTo(4);

        for (String input : List.of("ar-fail-1", "ar-fail-2", "ar-fail-3")) {
            fakeLlm.auditOutputs.put(input,
                    "{\"violation\":true,\"reason\":\"missing citation\"}");
        }
        for (String input : List.of("ar-pass-1", "ar-pass-2")) {
            fakeLlm.auditOutputs.put(input,
                    "{\"violation\":false,\"reason\":\"ok\"}");
        }
        JsonNode created = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/auto-rubric-runs", Map.of(
                "sourceRunId", runId,
                "candidates", List.of(
                        Map.of("key", "citation", "text", "回答必须给出引用",
                                "minPrecision", 0.8, "minSupport", 3),
                        Map.of("key", "too-strict", "text", "回答必须逐字复制参考",
                                "minPrecision", 0.9, "minSupport", 4)),
                "cvFolds", 3)));
        assertThat(created.path("code").asInt()).isZero();
        JsonNode data = created.path("data");
        assertThat(data.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(data.path("auditSplit").asText()).isEqualTo("HOLDOUT");
        assertThat(data.path("auditCount").asInt()).isEqualTo(5);
        assertThat(data.path("proposedCount").asInt()).isEqualTo(1);
        assertThat(data.path("judgeCalls").asInt()).isEqualTo(10);
        JsonNode candidates = data.path("candidates");
        assertThat(candidates.size()).isEqualTo(2);
        assertThat(candidates.get(0).path("ruleKey").asText()).isEqualTo("citation");
        assertThat(candidates.get(0).path("decision").asText()).isEqualTo("PROPOSED");
        assertThat(candidates.get(0).path("precision").asDouble()).isEqualTo(100.0);
        assertThat(candidates.get(0).path("recall").asDouble()).isEqualTo(100.0);
        assertThat(candidates.get(0).path("support").asInt()).isEqualTo(3);
        assertThat(candidates.get(0).path("stable").asBoolean()).isTrue();
        assertThat(candidates.get(0).path("selectionFrequency").asDouble())
                .isGreaterThanOrEqualTo(60.0);
        assertThat(data.path("bootstrapRounds").asInt()).isEqualTo(200);
        assertThat(candidates.get(1).path("decision").asText()).isEqualTo("REJECTED");
        JsonNode selection = data.path("selection");
        assertThat(selection.path("folds").asInt()).isEqualTo(3);
        assertThat(selection.path("selectedKeys").toString()).contains("citation");
        assertThat(selection.path("cv").path("foldResults").size()).isEqualTo(3);
        assertThat(selection.path("fullAudit").path("f1").asDouble()).isGreaterThan(0.0);

        String candidateId = candidates.get(0).path("id").asText();
        JsonNode approved = readTree(postApi("/api/v1/auto-rubric-runs/"
                + data.path("id").asText() + "/candidates/" + candidateId + "/decision",
                Map.of("decision", "APPROVED", "note", "专家确认可进入候选池")));
        assertThat(approved.path("data").path("decision").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("data").path("reviewedBy").asText()).isEqualTo("user-admin");
    }

    @Test
    void autoRubricGeneratesCandidatesFromNonHoldoutFailures() throws Exception {
        String datasetId = createDataset("auto-rubric-gen");
        fakeLlm.outputs.put("arg-pass-1", "ok");
        fakeLlm.outputs.put("arg-pass-2", "ok");
        addItemFull(datasetId, "arg-fail", "ok", "LOW", "DEV", "boundary");
        addItemFull(datasetId, "arg-pass-1", "ok", "LOW", "HOLDOUT", "happy");
        addItemFull(datasetId, "arg-pass-2", "ok", "LOW", "HOLDOUT", "happy");
        addItemFull(datasetId, "arg-fail-2", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "arg-fail-3", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "arg-fail-4", "ok", "LOW", "HOLDOUT", "boundary");
        String evaluatorId = createEvaluator("auto-rubric-gen", "EXPRESSION", exactMatchSpec());
        JsonNode run = runAsync(datasetId, evaluatorId, "REPLAY", "arg-run", 202);
        String runId = run.path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        fakeLlm.autoRubricOutputs.add("[{\"key\":\"generated-citation\","
                + "\"text\":\"回答必须给出来源引用\"}]");
        for (String input : List.of("arg-fail-2", "arg-fail-3", "arg-fail-4")) {
            fakeLlm.auditOutputs.put(input,
                    "{\"violation\":true,\"reason\":\"missing citation\"}");
        }
        fakeLlm.auditOutputs.put("arg-pass-1",
                "{\"violation\":false,\"reason\":\"ok\"}");
        fakeLlm.auditOutputs.put("arg-pass-2",
                "{\"violation\":false,\"reason\":\"ok\"}");

        JsonNode created = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/auto-rubric-runs", Map.of(
                "sourceRunId", runId, "generate", true, "maxCandidates", 2)));
        assertThat(created.path("code").asInt()).isZero();
        JsonNode data = created.path("data");
        assertThat(data.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(data.path("generationCalls").asInt()).isEqualTo(1);
        assertThat(data.path("candidateCount").asInt()).isEqualTo(1);
        JsonNode candidate = data.path("candidates").get(0);
        assertThat(candidate.path("ruleKey").asText()).isEqualTo("generated-citation");
        assertThat(candidate.path("origin").asText()).isEqualTo("GENERATED");
        assertThat(candidate.path("decision").asText()).isEqualTo("PROPOSED");
        assertThat(candidate.path("support").asInt()).isEqualTo(3);
    }

    @Test
    void autoRubricSkipsGeneratedNearDuplicateOfManualCandidate() throws Exception {
        String datasetId = createDataset("auto-rubric-near-dup");
        fakeLlm.outputs.put("and-pass-1", "ok");
        fakeLlm.outputs.put("and-pass-2", "ok");
        addItemFull(datasetId, "and-dev-fail", "ok", "LOW", "DEV", "boundary");
        addItemFull(datasetId, "and-pass-1", "ok", "LOW", "HOLDOUT", "happy");
        addItemFull(datasetId, "and-pass-2", "ok", "LOW", "HOLDOUT", "happy");
        addItemFull(datasetId, "and-fail-1", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "and-fail-2", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "and-fail-3", "ok", "LOW", "HOLDOUT", "boundary");
        String evaluatorId = createEvaluator("auto-rubric-near-dup", "EXPRESSION", exactMatchSpec());
        String runId = runAsync(datasetId, evaluatorId, "REPLAY", "and-run", 202)
                .path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        for (String input : List.of("and-fail-1", "and-fail-2", "and-fail-3")) {
            fakeLlm.auditOutputs.put(input, "{\"violation\":true,\"reason\":\"missing citation\"}");
        }
        for (String input : List.of("and-pass-1", "and-pass-2")) {
            fakeLlm.auditOutputs.put(input, "{\"violation\":false,\"reason\":\"ok\"}");
        }
        fakeLlm.autoRubricOutputs.add("[{\"key\":\"generated-citation\","
                + "\"text\":\"回答必须给出可核验来源引用\"}]");
        JsonNode created = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/auto-rubric-runs", Map.of(
                "sourceRunId", runId, "generate", true, "maxCandidates", 1,
                "candidates", List.of(Map.of("key", "citation",
                        "text", "回答必须给出可核验的来源引用",
                        "minPrecision", 0.8, "minSupport", 3)))));
        JsonNode data = created.path("data");
        assertThat(data.path("candidateCount").asInt()).isEqualTo(1);
        assertThat(data.path("candidates").get(0).path("origin").asText()).isEqualTo("MANUAL");
        assertThat(data.path("candidates").get(0).path("ruleKey").asText()).isEqualTo("citation");
    }

    @Test
    void autoRubricSemanticDedupCanSkipLexicallyDifferentDuplicate() throws Exception {
        String datasetId = createDataset("auto-rubric-semantic-dup");
        fakeLlm.outputs.put("asd-pass-1", "ok");
        fakeLlm.outputs.put("asd-pass-2", "ok");
        addItemFull(datasetId, "asd-dev-fail", "ok", "LOW", "DEV", "boundary");
        addItemFull(datasetId, "asd-pass-1", "ok", "LOW", "HOLDOUT", "happy");
        addItemFull(datasetId, "asd-pass-2", "ok", "LOW", "HOLDOUT", "happy");
        addItemFull(datasetId, "asd-fail-1", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "asd-fail-2", "ok", "LOW", "HOLDOUT", "boundary");
        addItemFull(datasetId, "asd-fail-3", "ok", "LOW", "HOLDOUT", "boundary");
        String evaluatorId = createEvaluator("auto-rubric-semantic-dup", "EXPRESSION", exactMatchSpec());
        String runId = runAsync(datasetId, evaluatorId, "REPLAY", "asd-run", 202)
                .path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        for (String input : List.of("asd-fail-1", "asd-fail-2", "asd-fail-3")) {
            fakeLlm.auditOutputs.put(input, "{\"violation\":true,\"reason\":\"missing citation\"}");
        }
        for (String input : List.of("asd-pass-1", "asd-pass-2")) {
            fakeLlm.auditOutputs.put(input, "{\"violation\":false,\"reason\":\"ok\"}");
        }
        fakeLlm.autoRubricOutputs.add("[{\"key\":\"generated-citation\","
                + "\"text\":\"回答需给出处\"}]");
        fakeLlm.semanticDedupOutputs.add("{\"duplicate\":true,\"reason\":\"same requirement\"}");
        JsonNode created = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/auto-rubric-runs", Map.of(
                "sourceRunId", runId, "generate", true, "maxCandidates", 1,
                "semanticDedup", true,
                "candidates", List.of(Map.of("key", "citation",
                        "text", "回答必须标注来源",
                        "minPrecision", 0.8, "minSupport", 3)))));
        JsonNode data = created.path("data");
        assertThat(data.path("candidateCount").asInt()).isEqualTo(1);
        assertThat(data.path("candidates").get(0).path("ruleKey").asText()).isEqualTo("citation");
        assertThat(data.path("candidates").get(0).path("origin").asText()).isEqualTo("MANUAL");
    }

    @Test
    void autoRubricRefusesWithoutEnoughHoldoutLabels() throws Exception {
        String datasetId = createDataset("auto-rubric-empty");
        fakeLlm.outputs.put("ar-empty", "ok");
        addItem(datasetId, "ar-empty", "ok", null);
        String evaluatorId = createEvaluator("auto-rubric-empty", "EXPRESSION", exactMatchSpec());
        JsonNode run = runAsync(datasetId, evaluatorId, "REPLAY", "ar-empty-run", 202);
        String runId = run.path("data").path("runId").asText();
        awaitRun(datasetId, runId);
        JsonNode created = readTree(postApi("/api/v1/datasets/" + datasetId
                + "/auto-rubric-runs", Map.of(
                "sourceRunId", runId,
                "candidates", List.of(Map.of("key", "citation", "text", "必须给出引用")))));
        assertThat(created.path("code").asInt()).isZero();
        assertThat(created.path("data").path("status").asText()).isEqualTo("NO_AUDIT_SET");
        assertThat(created.path("data").path("candidates").size()).isZero();
    }

    @Test
    void businessEvidenceIsVersionedAndQualityGated() throws Exception {
        Map<String, Object> experiment = new HashMap<>();
        experiment.put("source", "clickhouse");
        experiment.put("externalExperimentId", "exp-2026-10-001");
        experiment.put("designType", "RANDOMIZED");
        experiment.put("primaryMetric", "conversion");
        experiment.put("minSampleSize", 100);
        experiment.put("design", Map.of("unit", "user", "allocation", "50/50"));
        JsonNode created = readTree(postApi("/api/v1/business-experiments", experiment));
        assertThat(created.path("code").asInt()).isZero();
        String experimentId = created.path("data").path("id").asText();

        Map<String, Object> v1 = new HashMap<>();
        v1.put("reportVersion", "v1");
        v1.put("metrics", Map.of("sampleSize", 80, "pValue", 0.01, "relativeLift", 0.05));
        JsonNode first = readTree(postApi("/api/v1/business-experiments/" + experimentId + "/evidence", v1));
        assertThat(first.path("data").path("latestEvidence").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(first.path("data").path("latestEvidence").path("qualityChecks").path("sampleOk").asBoolean())
                .isFalse();

        Map<String, Object> v2 = new HashMap<>();
        v2.put("reportVersion", "v2");
        v2.put("metrics", Map.of("sampleSize", 200, "pValue", 0.01, "relativeLift", 0.05,
                "srmPValue", 0.5, "ciLower", 0.01, "ciUpper", 0.09));
        JsonNode positive = readTree(postApi("/api/v1/business-experiments/" + experimentId + "/evidence", v2));
        assertThat(positive.path("data").path("latestEvidence").path("decision").asText()).isEqualTo("POSITIVE");
        assertThat(positive.path("data").path("evidenceCount").asInt()).isEqualTo(2);

        JsonNode idempotent = readTree(postApi("/api/v1/business-experiments/" + experimentId + "/evidence", v2));
        assertThat(idempotent.path("data").path("evidenceCount").asInt()).isEqualTo(2);

        Map<String, Object> tampered = new HashMap<>(v2);
        tampered.put("metrics", Map.of("sampleSize", 200, "pValue", 0.20, "relativeLift", 0.02));
        String conflict = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/business-experiments/" + experimentId + "/evidence")
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tampered)))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(readTree(conflict).path("code").asInt()).isNotZero();
        assertThat(conflict).contains("reportVersion");

        Map<String, Object> v3 = new HashMap<>();
        v3.put("reportVersion", "v3");
        v3.put("metrics", Map.of("sampleSize", 220, "pValue", 0.02, "relativeLift", -0.08,
                "srmPValue", 0.4, "ciLower", -0.12, "ciUpper", -0.04));
        JsonNode negative = readTree(postApi("/api/v1/business-experiments/" + experimentId + "/evidence", v3));
        assertThat(negative.path("data").path("latestEvidence").path("decision").asText()).isEqualTo("NEGATIVE");
        assertThat(negative.path("data").path("evidence").size()).isEqualTo(3);

        Map<String, Object> observational = new HashMap<>();
        observational.put("source", "clickhouse");
        observational.put("externalExperimentId", "obs-2026-10-001");
        observational.put("designType", "OBSERVATIONAL");
        observational.put("primaryMetric", "conversion");
        String obsId = readTree(postApi("/api/v1/business-experiments", observational))
                .path("data").path("id").asText();
        Map<String, Object> obsEvidence = new HashMap<>();
        obsEvidence.put("reportVersion", "v1");
        obsEvidence.put("metrics", Map.of("sampleSize", 500, "pValue", 0.01, "relativeLift", 0.2));
        JsonNode obs = readTree(postApi("/api/v1/business-experiments/" + obsId + "/evidence", obsEvidence));
        assertThat(obs.path("data").path("latestEvidence").path("decision").asText())
                .isEqualTo("INCONCLUSIVE");
    }

    @Test
    void datasetBlueprintReportsCoverageDriftAndStaleness() throws Exception {
        String datasetId = createDataset("blueprint-health");
        addItemFull(datasetId, "q-bp-1", "ok", "HIGH", "HOLDOUT", "safety");
        addItemFull(datasetId, "q-bp-2", "ok", "HIGH", "DEV", "safety");
        addItemFull(datasetId, "q-bp-3", "ok", "LOW", "DEV", "regression");
        addItemFull(datasetId, "q-bp-4", "ok", "LOW", "VALIDATION", "regression");

        Map<String, Object> blueprint = new HashMap<>();
        blueprint.put("minItems", 6);
        blueprint.put("riskMin", Map.of("HIGH", 2));
        blueprint.put("splitMin", Map.of("HOLDOUT", 1));
        blueprint.put("purposeMin", Map.of("safety", 2));
        blueprint.put("maxStaleDays", 30);
        JsonNode saved = readTree(putApi("/api/v1/datasets/" + datasetId + "/blueprint", blueprint));
        assertThat(saved.path("code").asInt()).isZero();
        JsonNode health = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(health.path("data").path("blueprintConfigured").asBoolean()).isTrue();
        assertThat(health.path("data").path("status").asText()).isEqualTo("AT_RISK");
        assertThat(health.path("data").path("requirementCoverage").asDouble()).isEqualTo(75.0);
        assertThat(health.path("data").path("drift").path("status").asText()).isEqualTo("NO_BASELINE");
        assertThat(health.path("data").path("warnings").toString()).contains("未达标");

        postApi("/api/v1/datasets/" + datasetId + "/revisions", Map.of("label", "baseline"));
        JsonNode synced = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(synced.path("data").path("drift").path("status").asText()).isEqualTo("IN_SYNC");
        assertThat(synced.path("data").path("stale").asBoolean()).isFalse();

        addItemFull(datasetId, "q-bp-5", "ok", "HIGH", "HOLDOUT", "safety");
        JsonNode drifted = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(drifted.path("data").path("drift").path("status").asText()).isEqualTo("DRIFTED");
        assertThat(drifted.path("data").path("drift").path("added").asInt()).isEqualTo(1);
        assertThat(drifted.path("data").path("status").asText()).isEqualTo("AT_RISK");

        io.emonlens.server.storage.entity.DatasetRevisionEntity latest =
                datasetRevisionMapper.selectOne(Wrappers.<io.emonlens.server.storage.entity.DatasetRevisionEntity>lambdaQuery()
                        .eq(io.emonlens.server.storage.entity.DatasetRevisionEntity::getDatasetId, datasetId)
                        .orderByDesc(io.emonlens.server.storage.entity.DatasetRevisionEntity::getRevision)
                        .last("LIMIT 1"));
        latest.setCreatedAt(LocalDateTime.now().minusDays(100));
        datasetRevisionMapper.updateById(latest);
        JsonNode stale = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(stale.path("data").path("stale").asBoolean()).isTrue();
        assertThat(stale.path("data").path("warnings").toString()).contains("未刷新");

        assertThat(datasetHealthService.scanAll()).isGreaterThanOrEqualTo(1);
        JsonNode history = readTree(get("/api/v1/datasets/" + datasetId + "/health/history?limit=5"));
        assertThat(history.path("data").size()).isEqualTo(1);
        JsonNode snapshot = history.path("data").get(0);
        assertThat(snapshot.path("status").asText()).isEqualTo("AT_RISK");
        assertThat(snapshot.path("itemCount").asInt()).isEqualTo(5);
        assertThat(snapshot.path("stale").asBoolean()).isTrue();
        assertThat(snapshot.path("latestRevision").asInt()).isEqualTo(1);
        assertThat(snapshot.path("warnings").toString()).contains("未刷新");

        JsonNode cleared = readTree(putApi("/api/v1/datasets/" + datasetId + "/blueprint", Map.of()));
        assertThat(cleared.path("data").path("status").asText()).isEqualTo("NO_BLUEPRINT");
        assertThat(cleared.path("data").path("blueprintConfigured").asBoolean()).isFalse();
    }

    private void addItemFull(String datasetId, String input, String expectedOutput,
                             String riskLevel, String split, String purposeTags) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", input, "expectedOutput", expectedOutput,
                        "riskLevel", riskLevel, "split", split, "purposeTags", purposeTags)));
        assertThat(response.path("code").asInt()).isZero();
    }

    @Test
    void doubleBlindCalibrationRequiresTwoReviewersAndThirdPartyAdjudication() throws Exception {
        fakeLlm.outputs.put("q-double-a", "ok");
        fakeLlm.outputs.put("q-double-b", "ok");
        fakeLlm.judgeOutput = "{\"score\":9,\"confidence\":0.9,\"reason\":\"ok\"}";
        String datasetId = createDataset("judge-double-blind");
        addItem(datasetId, "q-double-a", "ok", null);
        addItem(datasetId, "q-double-b", "ok", null);
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须准确");
        spec.put("minScore", 6);
        spec.put("referenceRequired", false);
        String evaluatorId = createEvaluator("judge-double-blind", "LLM_JUDGE", spec);
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorIds", List.of(evaluatorId));
        body.put("mode", "REPLAY");
        body.put("reviewMode", "DOUBLE_BLIND");
        JsonNode accepted = readTree(mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "judge-double-blind-run")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String runId = accepted.path("data").path("runId").asText();
        JsonNode detail = awaitRun(datasetId, runId);
        assertThat(detail.path("data").path("run").path("configSnapshot").path("reviewMode").asText())
                .isEqualTo("DOUBLE_BLIND");
        Map<String, JsonNode> items = itemsByInput(detail);
        String itemA = items.get("q-double-a").path("itemId").asText();
        String itemB = items.get("q-double-b").path("itemId").asText();
        String evaluatorKey = items.get("q-double-a").path("evaluatorResults").get(0)
                .path("evaluatorKey").asText();

        try {
            setReviewer("reviewer-a");
            for (String itemId : List.of(itemA, itemB)) {
                judgeCalibrationService.review("ws-default", datasetId, runId, itemId,
                        evaluatorKey, "PASS", "A");
            }
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    judgeCalibrationService.review("ws-default", datasetId, runId, itemA,
                            evaluatorKey, "FAIL", "A again"))
                    .isInstanceOf(EmonLensException.class)
                    .hasMessageContaining("你已提交");

            Map<String, Object> afterA = judgeCalibrationService.summary(
                    "ws-default", datasetId, runId, evaluatorKey, false);
            Map<?, ?> summaryA = (Map<?, ?>) afterA.get("summary");
            assertThat(summaryA.get("reviewMode")).isEqualTo("DOUBLE_BLIND");
            assertThat(summaryA.get("reviewed")).isEqualTo(0);
            assertThat(summaryA.get("revealed")).isEqualTo(false);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rowsA = (List<Map<String, Object>>) afterA.get("items");
            assertThat(rowsA.get(0).get("myReviewed")).isEqualTo(true);
            assertThat(rowsA.get(0).get("myLabel")).isEqualTo("PASS");
            assertThat(rowsA.get(0).get("humanLabel")).isNull();

            setReviewer("reviewer-b");
            judgeCalibrationService.review("ws-default", datasetId, runId, itemA,
                    evaluatorKey, "PASS", "B");
            judgeCalibrationService.review("ws-default", datasetId, runId, itemB,
                    evaluatorKey, "FAIL", "B");

            setReviewer("reviewer-c");
            Map<String, Object> pending = judgeCalibrationService.summary(
                    "ws-default", datasetId, runId, evaluatorKey, false);
            Map<?, ?> pendingSummary = (Map<?, ?>) pending.get("summary");
            assertThat(pendingSummary.get("pendingAdjudication")).isEqualTo(1);
            assertThat(pendingSummary.get("reviewed")).isEqualTo(1);
            assertThat(pendingSummary.get("revealed")).isEqualTo(false);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> pendingRows = (List<Map<String, Object>>) pending.get("items");
            Map<String, Object> rowB = pendingRows.stream()
                    .filter(r -> itemB.equals(r.get("itemId"))).findFirst().orElseThrow();
            assertThat(rowB.get("needsAdjudication")).isEqualTo(true);
            assertThat(rowB.get("canAdjudicate")).isEqualTo(true);

            setReviewer("reviewer-a");
            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    judgeCalibrationService.adjudicate("ws-default", datasetId, runId, itemB,
                            evaluatorKey, "PASS", "A cannot adjudicate"))
                    .isInstanceOf(EmonLensException.class)
                    .hasMessageContaining("仲裁人不能是原评审人");

            setReviewer("reviewer-c");
            judgeCalibrationService.adjudicate("ws-default", datasetId, runId, itemB,
                    evaluatorKey, "PASS", "C tie-break");
            Map<String, Object> done = judgeCalibrationService.summary(
                    "ws-default", datasetId, runId, evaluatorKey, false);
            Map<?, ?> doneSummary = (Map<?, ?>) done.get("summary");
            assertThat(doneSummary.get("reviewed")).isEqualTo(2);
            assertThat(doneSummary.get("revealed")).isEqualTo(true);
            assertThat(doneSummary.get("agreementRate")).isEqualTo(100.0);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> doneRows = (List<Map<String, Object>>) done.get("items");
            Map<String, Object> doneB = doneRows.stream()
                    .filter(r -> itemB.equals(r.get("itemId"))).findFirst().orElseThrow();
            assertThat(doneB.get("primaryLabel")).isEqualTo("PASS");
            assertThat(doneB.get("secondLabel")).isEqualTo("FAIL");
            assertThat(doneB.get("adjudicatedLabel")).isEqualTo("PASS");
            assertThat(doneB.get("humanLabel")).isEqualTo("PASS");
            assertThat(doneB.get("judgeLabel")).isEqualTo("PASS");

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    judgeCalibrationService.adjudicate("ws-default", datasetId, runId, itemB,
                            evaluatorKey, "FAIL", "again"))
                    .isInstanceOf(EmonLensException.class)
                    .hasMessageContaining("已仲裁");
        } finally {
            CurrentContext.clear();
        }
    }

    private void setReviewer(String userId) {
        CurrentContext.set(new CurrentUser(userId, userId, true, "ws-default", Set.of()));
    }

    @Test
    void doubleBlindGateBlocksUnadjudicatedDisagreementUntilThirdPartyResolves() throws Exception {
        String datasetId = createDataset("judge-double-gate");
        for (int i = 0; i < 10; i++) {
            fakeLlm.outputs.put("q-dgate-" + i, "ok");
            addItem(datasetId, "q-dgate-" + i, "ok", null);
        }
        fakeLlm.judgeOutput = "{\"score\":9,\"confidence\":0.9,\"reason\":\"ok\"}";
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须准确");
        spec.put("minScore", 6);
        spec.put("referenceRequired", false);
        String evaluatorId = createEvaluator("judge-double-gate", "LLM_JUDGE", spec);
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorIds", List.of(evaluatorId));
        body.put("mode", "REPLAY");
        body.put("reviewMode", "DOUBLE_BLIND");
        JsonNode accepted = readTree(mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "judge-double-gate-run")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String runId = accepted.path("data").path("runId").asText();
        JsonNode detail = awaitRun(datasetId, runId);
        String evaluatorKey = detail.path("data").path("items").get(0)
                .path("evaluatorResults").get(0).path("evaluatorKey").asText();
        List<String> itemIds = new ArrayList<>();
        for (JsonNode item : detail.path("data").path("items")) {
            itemIds.add(item.path("itemId").asText());
        }
        assertThat(itemIds).hasSize(10);

        try {
            setReviewer("gate-reviewer-a");
            for (String itemId : itemIds) {
                judgeCalibrationService.review("ws-default", datasetId, runId, itemId,
                        evaluatorKey, "PASS", "A");
            }
            setReviewer("gate-reviewer-b");
            for (int i = 0; i < itemIds.size(); i++) {
                judgeCalibrationService.review("ws-default", datasetId, runId, itemIds.get(i),
                        evaluatorKey, i == itemIds.size() - 1 ? "FAIL" : "PASS", "B");
            }
        } finally {
            CurrentContext.clear();
        }

        JsonNode blocked = createGate(runId);
        assertThat(blocked.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.path("data").path("checks").toString())
                .contains("JUDGE_CALIBRATION")
                .contains("reviewed=9/10");

        try {
            setReviewer("gate-reviewer-c");
            judgeCalibrationService.adjudicate("ws-default", datasetId, runId,
                    itemIds.get(itemIds.size() - 1), evaluatorKey, "PASS", "C");
        } finally {
            CurrentContext.clear();
        }
        JsonNode passed = createGate(runId);
        assertThat(passed.path("data").path("decision").asText()).isEqualTo("PASS");
        assertThat(passed.path("data").path("checks").toString())
                .contains("JUDGE_CALIBRATION")
                .contains("reviewed=10/10")
                .contains("agreement=100.0%");
    }

    @Test
    void businessEvidenceAppliesHolmCorrectionToMultipleComparisons() throws Exception {
        JsonNode experiment = readTree(postApi("/api/v1/business-experiments", Map.of(
                "source", "stats-source", "externalExperimentId", "holm-exp-1",
                "primaryMetric", "conversion", "designType", "RANDOMIZED",
                "minSampleSize", 100)));
        String experimentId = experiment.path("data").path("id").asText();

        Map<String, Object> comparisons = Map.of("comparisons", List.of(
                Map.of("metric", "conversion", "pValue", 0.01, "relativeLift", 0.05),
                Map.of("metric", "latency", "pValue", 0.04, "relativeLift", 0.02),
                Map.of("metric", "error_rate", "pValue", 0.5, "relativeLift", 0.0,
                        "guardrail", true)));
        Map<String, Object> metrics = new HashMap<>();
        metrics.put("sampleSize", 1000);
        metrics.put("comparisons", comparisons.get("comparisons"));
        Map<String, Object> body = Map.of(
                "reportVersion", "v1", "metrics", metrics,
                "qualityChecks", Map.of("mature", true, "srmOk", true, "guardrailsOk", true));
        JsonNode imported = readTree(postApi(
                "/api/v1/business-experiments/" + experimentId + "/evidence", body));
        assertThat(imported.path("code").asInt()).isZero();
        JsonNode latest = imported.path("data").path("latestEvidence");
        assertThat(latest.path("decision").asText()).isEqualTo("POSITIVE");
        assertThat(latest.path("adjustmentMethod").asText()).isEqualTo("HOLM");
        assertThat(latest.path("comparisons").size()).isEqualTo(3);
        assertThat(latest.path("comparisons").get(0).path("metric").asText()).isEqualTo("conversion");
        assertThat(latest.path("comparisons").get(0).path("adjustedPValue").asDouble())
                .isEqualTo(0.03);
        assertThat(latest.path("comparisons").get(0).path("significant").asBoolean()).isTrue();
        assertThat(latest.path("comparisons").get(1).path("significant").asBoolean()).isFalse();

        Map<String, Object> badMetrics = new HashMap<>();
        badMetrics.put("sampleSize", 1000);
        badMetrics.put("comparisons", List.of(
                Map.of("metric", "conversion", "pValue", 0.01, "relativeLift", 0.05),
                Map.of("metric", "error_rate", "pValue", 0.5, "relativeLift", -0.2,
                        "guardrail", true)));
        JsonNode blocked = readTree(postApi(
                "/api/v1/business-experiments/" + experimentId + "/evidence",
                Map.of("reportVersion", "v2", "metrics", badMetrics,
                        "qualityChecks", Map.of("mature", true, "srmOk", true,
                                "guardrailsOk", true))));
        assertThat(blocked.path("data").path("latestEvidence").path("decision").asText())
                .isEqualTo("BLOCKED");
        assertThat(blocked.path("data").path("latestEvidence").path("qualityChecks")
                .path("guardrailViolation").asBoolean()).isTrue();
    }

    @Test
    void businessEvidenceSupportsRsaSignedImports() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();
        String publicKey = java.util.Base64.getEncoder()
                .encodeToString(keyPair.getPublic().getEncoded());

        JsonNode credential = readTree(postApi("/api/v1/business-experiments/credentials", Map.of(
                "keyId", "partner-key-1", "source", "partner-a",
                "name", "合作方 A", "publicKey", publicKey)));
        assertThat(credential.path("code").asInt()).isZero();
        assertThat(credential.path("data").path("status").asText()).isEqualTo("ACTIVE");

        JsonNode experiment = readTree(postApi("/api/v1/business-experiments", Map.of(
                "source", "partner-a", "externalExperimentId", "signed-exp-1",
                "primaryMetric", "conversion", "designType", "RANDOMIZED",
                "minSampleSize", 100, "requireSignature", true)));
        String experimentId = experiment.path("data").path("id").asText();
        assertThat(experimentId).isNotBlank();

        Map<String, Object> metrics = Map.of(
                "sampleSize", 1000, "pValue", 0.01, "relativeLift", 0.05);
        Map<String, Object> checks = Map.of(
                "mature", true, "srmOk", true, "guardrailsOk", true);
        String reportVersion = "v1";
        String signedAt = java.time.OffsetDateTime.now().withNano(0).toString();
        String nonce = "nonce-20261020-1";
        Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("design", Map.of());
        canonical.put("metrics", metrics);
        canonical.put("qualityChecks", checks);
        canonical.put("reportedAt", null);
        String canonicalJson = objectMapper.writer()
                .with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .writeValueAsString(canonical);
        String payloadHash = io.emonlens.common.Sha256Utils.hash(canonicalJson);
        String signingString = experimentId + "\n" + "partner-key-1" + "\n" + reportVersion
                + "\n" + signedAt + "\n" + nonce + "\n" + payloadHash;
        java.security.Signature signer = java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(signingString.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String signature = java.util.Base64.getEncoder().encodeToString(signer.sign());

        JsonNode unsigned = readTree(postApi(
                "/api/v1/business-experiments/" + experimentId + "/evidence",
                Map.of("reportVersion", "v0", "metrics", metrics, "qualityChecks", checks)));
        assertThat(unsigned.path("code").asInt()).isEqualTo(40000);
        assertThat(unsigned.path("message").asText()).contains("签名");

        Map<String, Object> body = new HashMap<>();
        body.put("reportVersion", reportVersion);
        body.put("metrics", metrics);
        body.put("qualityChecks", checks);
        body.put("sourceKeyId", "partner-key-1");
        body.put("signature", signature);
        body.put("signedAt", signedAt);
        body.put("nonce", nonce);
        JsonNode imported = readTree(postApi(
                "/api/v1/business-experiments/" + experimentId + "/evidence", body));
        assertThat(imported.path("code").asInt()).isZero();
        assertThat(imported.path("data").path("latestEvidence").path("signatureVerified")
                .asBoolean()).isTrue();
        assertThat(imported.path("data").path("latestEvidence").path("sourceKeyId").asText())
                .isEqualTo("partner-key-1");

        Map<String, Object> tampered = new HashMap<>(body);
        tampered.put("reportVersion", "v2");
        tampered.put("nonce", "nonce-20261020-2");
        tampered.put("signature", java.util.Base64.getEncoder().encodeToString(new byte[256]));
        assertThat(readTree(postApi("/api/v1/business-experiments/" + experimentId + "/evidence",
                tampered)).path("code").asInt()).isEqualTo(40000);

        JsonNode credentials = readTree(get("/api/v1/business-experiments/credentials"));
        assertThat(credentials.path("data").size()).isEqualTo(1);
        assertThat(credentials.path("data").toString()).doesNotContain("publicKey");
    }

    @Test
    void domainPackVersionsAreImmutableAndValidated() throws Exception {
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("schemaVersion", "domain-pack/v1");
        manifest.put("qualityModel", Map.of("dimensions", List.of(
                Map.of("key", "accuracy", "label", "准确性", "weight", 2),
                Map.of("key", "safety", "label", "安全性", "weight", 1,
                        "dependsOn", List.of("accuracy")))));
        manifest.put("riskTaxonomy", List.of(
                Map.of("key", "high", "label", "高风险"),
                Map.of("key", "low", "label", "低风险")));
        manifest.put("datasetBlueprint", Map.of("minItems", 20,
                "riskMin", Map.of("high", 5), "splitMin", Map.of("holdout", 1),
                "maxStaleDays", 30, "maxItemAgeDays", 30));
        manifest.put("gateTemplates", List.of(Map.of("key", "default", "name", "默认准入")));

        Map<String, Object> body = new HashMap<>();
        body.put("packKey", "customer-support");
        body.put("version", "1.0.0");
        body.put("name", "客服 Agent 领域包");
        body.put("manifest", manifest);
        JsonNode created = readTree(postApi("/api/v1/domain-packs", body));
        assertThat(created.path("code").asInt()).isZero();
        String packId = created.path("data").path("id").asText();
        assertThat(created.path("data").path("digest").asText()).hasSize(64);
        assertThat(created.path("data").path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(created.path("data").path("manifest").path("qualityModel")
                .path("dimensions").size()).isEqualTo(2);

        JsonNode again = readTree(postApi("/api/v1/domain-packs", body));
        assertThat(again.path("data").path("id").asText()).isEqualTo(packId);

        Map<String, Object> changed = new HashMap<>(body);
        changed.put("name", "客服 Agent 领域包 v2 内容");
        assertThat(readTree(postApi("/api/v1/domain-packs", changed)).path("code").asInt())
                .isEqualTo(40901);

        Map<String, Object> unknown = new HashMap<>(body);
        Map<String, Object> badManifest = new HashMap<>(manifest);
        badManifest.put("unknownField", true);
        unknown.put("version", "1.0.1");
        unknown.put("manifest", badManifest);
        assertThat(readTree(postApi("/api/v1/domain-packs", unknown)).path("code").asInt())
                .isEqualTo(40000);

        Map<String, Object> cyclic = new HashMap<>(body);
        cyclic.put("version", "1.0.2");
        Map<String, Object> cyclicManifest = new HashMap<>(manifest);
        cyclicManifest.put("qualityModel", Map.of("dimensions", List.of(
                Map.of("key", "accuracy", "label", "准确性", "dependsOn", List.of("safety")),
                Map.of("key", "safety", "label", "安全性", "dependsOn", List.of("accuracy")))));
        cyclic.put("manifest", cyclicManifest);
        assertThat(readTree(postApi("/api/v1/domain-packs", cyclic)).path("code").asInt())
                .isEqualTo(40000);

        JsonNode versions = readTree(get("/api/v1/domain-packs/customer-support/versions"));
        assertThat(versions.path("data").size()).isEqualTo(1);

        String datasetId = createDataset("domain-pack-bound");
        addItemFull(datasetId, "q-pack-1", "ok", "HIGH", "HOLDOUT", "regression");
        JsonNode bound = readTree(putApi("/api/v1/datasets/" + datasetId + "/domain-pack",
                Map.of("domainPackId", packId)));
        assertThat(bound.path("code").asInt()).isZero();
        assertThat(bound.path("data").path("blueprintSource").asText()).isEqualTo("DOMAIN_PACK");
        assertThat(bound.path("data").path("domainPackVersion").asText()).isEqualTo("1.0.0");
        assertThat(bound.path("data").path("blueprintConfigured").asBoolean()).isTrue();
        assertThat(bound.path("data").path("requirements").toString()).contains("RISK:high");
        assertThat(bound.path("data").path("requirements").toString()).contains("SPLIT:holdout");
        assertThat(bound.path("data").path("maxItemAgeDays").asInt()).isEqualTo(30);

        fakeLlm.outputs.put("q-pack-1", "ok");
        String evaluatorId = createEvaluator("domain-pack-exact", "EXPRESSION", exactMatchSpec());
        JsonNode runDetail = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "domain-pack-run", 202)
                        .path("data").path("runId").asText());
        assertThat(runDetail.path("data").path("run").path("domainPackId").asText()).isEqualTo(packId);
        assertThat(runDetail.path("data").path("run").path("configSnapshot")
                .path("domainPackId").asText()).isEqualTo(packId);

        JsonNode deprecated = readTree(postApi("/api/v1/domain-packs/" + packId + "/deprecate", Map.of()));
        assertThat(deprecated.path("data").path("status").asText()).isEqualTo("DEPRECATED");
        JsonNode rejectedBind = readTree(putApi("/api/v1/datasets/" + datasetId + "/domain-pack",
                Map.of("domainPackId", packId)));
        assertThat(rejectedBind.path("code").asInt()).isEqualTo(40000);
        JsonNode afterDeprecate = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(afterDeprecate.path("data").path("warnings").toString()).contains("已弃用");
        JsonNode unbound = readTree(putApi("/api/v1/datasets/" + datasetId + "/domain-pack", Map.of()));
        assertThat(unbound.path("data").path("blueprintSource").asText()).isEqualTo("NONE");
        assertThat(unbound.path("data").path("blueprintConfigured").asBoolean()).isFalse();

        JsonNode list = readTree(get("/api/v1/domain-packs?packKey=customer-support"));
        assertThat(list.path("data").size()).isEqualTo(1);
        assertThat(list.path("data").get(0).path("status").asText()).isEqualTo("DEPRECATED");
    }

    @Test
    void domainPackUpgradeRequiresSameKeyAndNewerPublishedVersion() throws Exception {
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("schemaVersion", "domain-pack/v1");
        manifest.put("qualityModel", Map.of("dimensions", List.of(
                Map.of("key", "accuracy", "label", "准确性", "weight", 1))));
        manifest.put("riskTaxonomy", List.of(Map.of("key", "high", "label", "高风险")));
        manifest.put("gateTemplates", List.of(Map.of("key", "default", "name", "默认准入")));

        String packV1 = readTree(postApi("/api/v1/domain-packs", Map.of(
                "packKey", "upgrade-pack", "version", "1.0.0",
                "name", "升级领域包", "manifest", manifest)))
                .path("data").path("id").asText();
        String packV2 = readTree(postApi("/api/v1/domain-packs", Map.of(
                "packKey", "upgrade-pack", "version", "2.0.0",
                "name", "升级领域包", "manifest", manifest)))
                .path("data").path("id").asText();
        String otherPack = readTree(postApi("/api/v1/domain-packs", Map.of(
                "packKey", "other-pack", "version", "1.0.0",
                "name", "其他领域包", "manifest", manifest)))
                .path("data").path("id").asText();

        String datasetId = createDataset("domain-pack-upgrade");
        addItemFull(datasetId, "q-upgrade-1", "ok", "high", "DEV", "regression");
        JsonNode bound = readTree(putApi("/api/v1/datasets/" + datasetId + "/domain-pack",
                Map.of("domainPackId", packV1)));
        assertThat(bound.path("data").path("domainPackVersion").asText()).isEqualTo("1.0.0");

        JsonNode upgraded = readTree(postApi(
                "/api/v1/datasets/" + datasetId + "/domain-pack/upgrade",
                Map.of("domainPackId", packV2)));
        assertThat(upgraded.path("code").asInt()).isZero();
        assertThat(upgraded.path("data").path("domainPackVersion").asText()).isEqualTo("2.0.0");

        JsonNode downgrade = readTree(postApi(
                "/api/v1/datasets/" + datasetId + "/domain-pack/upgrade",
                Map.of("domainPackId", packV1)));
        assertThat(downgrade.path("code").asInt()).isEqualTo(40000);
        JsonNode otherKey = readTree(postApi(
                "/api/v1/datasets/" + datasetId + "/domain-pack/upgrade",
                Map.of("domainPackId", otherPack)));
        assertThat(otherKey.path("code").asInt()).isEqualTo(40000);

        readTree(postApi("/api/v1/domain-packs/" + packV2 + "/deprecate", Map.of()));
        JsonNode deprecatedTarget = readTree(postApi(
                "/api/v1/datasets/" + datasetId + "/domain-pack/upgrade",
                Map.of("domainPackId", packV2)));
        assertThat(deprecatedTarget.path("code").asInt()).isEqualTo(40000);
    }

    @Test
    void domainPackRiskTaxonomyRejectsUnknownLevelsAfterBinding() throws Exception {
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("schemaVersion", "domain-pack/v1");
        manifest.put("qualityModel", Map.of("dimensions", List.of(
                Map.of("key", "accuracy", "label", "准确性", "weight", 1))));
        manifest.put("riskTaxonomy", List.of(
                Map.of("key", "high", "label", "高风险"),
                Map.of("key", "low", "label", "低风险")));
        manifest.put("gateTemplates", List.of());
        String packId = readTree(postApi("/api/v1/domain-packs", Map.of(
                "packKey", "risk-taxonomy-pack", "version", "1.0.0",
                "name", "风险分类领域包", "manifest", manifest)))
                .path("data").path("id").asText();

        String datasetId = createDataset("risk-taxonomy" + System.nanoTime());
        addItemFull(datasetId, "risk-before-bind", "ok", "critical", "DEV", "regression");
        readTree(putApi("/api/v1/datasets/" + datasetId + "/domain-pack",
                Map.of("domainPackId", packId)));
        JsonNode health = readTree(get("/api/v1/datasets/" + datasetId + "/health"));
        assertThat(health.path("data").path("unknownRiskLevels").toString()).contains("critical");

        JsonNode rejected = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", "risk-after-bind", "expectedOutput", "ok",
                        "riskLevel", "critical", "split", "DEV")));
        assertThat(rejected.path("code").asInt()).isEqualTo(40000);
        assertThat(rejected.path("message").asText()).contains("riskTaxonomy");
        addItemFull(datasetId, "risk-valid", "ok", "high", "DEV", "regression");
    }

    @Test
    void runCostReportUsesModelPriceAndUsage() throws Exception {
        readTree(postApi("/api/v1/model-prices", Map.of(
                "model", "gpt-4o-mini", "inputPerMillion", 1.0,
                "outputPerMillion", 2.0, "currency", "USD")));
        String datasetId = createDataset("cost-report-" + System.nanoTime());
        fakeLlm.outputs.put("cost-q", "ok");
        addItem(datasetId, "cost-q", "ok", null);
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须正确");
        spec.put("minScore", 6);
        spec.put("referenceRequired", false);
        String evaluatorId = createEvaluator("cost-judge", "LLM_JUDGE", spec);
        String runId = runAsync(datasetId, evaluatorId, "REPLAY", "cost-run", 202)
                .path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        JsonNode cost = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/" + runId + "/cost")).path("data");
        assertThat(cost.path("priced").asBoolean()).isTrue();
        assertThat(cost.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(cost.path("promptTokens").asLong()).isEqualTo(18);
        assertThat(cost.path("completionTokens").asLong()).isEqualTo(35);
        assertThat(cost.path("cost").asDouble()).isEqualTo(0.000088);

        MockHttpServletResponse cappedResponse = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "cost-run-capped")
                        .contentType("application/json")
                        .content("{\"evaluatorId\":\"" + evaluatorId
                                + "\",\"mode\":\"REPLAY\",\"maxCost\":0.000001}"))
                .andReturn().getResponse();
        assertThat(cappedResponse.getStatus()).isEqualTo(202);
        String cappedRunId = readTree(cappedResponse.getContentAsString(
                StandardCharsets.UTF_8)).path("data").path("runId").asText();
        awaitRun(datasetId, cappedRunId);
        JsonNode cappedGate = createGate(cappedRunId);
        assertThat(cappedGate.path("data").path("decision").asText()).isEqualTo("FAIL");
        assertThat(cappedGate.path("data").path("checks").toString()).contains("COST_BUDGET");
    }

    @Test
    void domainPackQualityModelReportsWeightedDimensionResults() throws Exception {
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("schemaVersion", "domain-pack/v1");
        manifest.put("qualityModel", Map.of("dimensions", List.of(
                Map.of("key", "accuracy", "label", "准确性", "weight", 2),
                Map.of("key", "safety", "label", "安全性", "weight", 1))));
        manifest.put("riskTaxonomy", List.of(Map.of("key", "high", "label", "高风险")));
        manifest.put("gateTemplates", List.of());
        String packId = readTree(postApi("/api/v1/domain-packs", Map.of(
                "packKey", "quality-model-pack", "version", "1.0.0",
                "name", "质量模型领域包", "manifest", manifest)))
                .path("data").path("id").asText();

        String datasetId = createDataset("quality-model" + System.nanoTime());
        readTree(putApi("/api/v1/datasets/" + datasetId + "/domain-pack",
                Map.of("domainPackId", packId)));
        fakeLlm.outputs.put("qm-pass", "ok");
        addItemFull(datasetId, "qm-pass", "ok", "high", "DEV", "regression");
        Map<String, Object> spec = new HashMap<>(exactMatchSpec());
        spec.put("qualityDimension", "accuracy");
        String evaluatorId = createEvaluator("quality-model-exact", "EXPRESSION", spec);
        String runId = runAsync(datasetId, evaluatorId, "REPLAY", "quality-model-run", 202)
                .path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        JsonNode report = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/" + runId + "/quality-model"));
        JsonNode data = report.path("data");
        assertThat(data.path("bound").asBoolean()).isTrue();
        assertThat(data.path("packKey").asText()).isEqualTo("quality-model-pack");
        JsonNode dimensions = data.path("dimensions");
        assertThat(dimensions.size()).isEqualTo(2);
        assertThat(dimensions.get(0).path("key").asText()).isEqualTo("accuracy");
        assertThat(dimensions.get(0).path("passRate").asDouble()).isEqualTo(100.0);
        assertThat(dimensions.get(0).path("evaluated").asInt()).isEqualTo(1);
        assertThat(dimensions.get(1).has("passRate")).isFalse();
        assertThat(data.path("coverage").asDouble()).isEqualTo(50.0);
        assertThat(data.path("weightedScore").asDouble()).isEqualTo(100.0);
        assertThat(data.path("unmappedEvaluatorKeys").size()).isZero();
    }

    @Test
    void gateTemplatePolicyIsReadFromRunDomainPack() throws Exception {
        Map<String, Object> manifest = new HashMap<>();
        manifest.put("schemaVersion", "domain-pack/v1");
        manifest.put("qualityModel", Map.of("dimensions", List.of(
                Map.of("key", "accuracy", "label", "准确性", "weight", 1))));
        manifest.put("riskTaxonomy", List.of(Map.of("key", "high", "label", "高风险")));
        manifest.put("gateTemplates", List.of(Map.of(
                "key", "strict", "name", "严格准入",
                "policy", Map.of("minCoverage", 95, "maxFail", 0,
                        "maxUnknown", 0, "validHours", 12))));
        String packId = readTree(postApi("/api/v1/domain-packs", Map.of(
                "packKey", "gate-template-pack", "version", "1.0.0",
                "name", "Gate 模板领域包", "manifest", manifest)))
                .path("data").path("id").asText();

        String datasetId = createDataset("gate-template" + System.nanoTime());
        for (int i = 0; i < 10; i++) {
            fakeLlm.outputs.put("gt-" + i, "ok");
            addItemFull(datasetId, "gt-" + i, "ok", "high", "DEV", "regression");
        }
        readTree(putApi("/api/v1/datasets/" + datasetId + "/domain-pack",
                Map.of("domainPackId", packId)));
        String evaluatorId = createEvaluator("gate-template-exact", "EXPRESSION", exactMatchSpec());
        String runId = runAsync(datasetId, evaluatorId, "REPLAY", "gate-template-run", 202)
                .path("data").path("runId").asText();
        awaitRun(datasetId, runId);

        JsonNode gate = readTree(postApi("/api/v1/quality-gates",
                Map.of("runId", runId, "gateTemplateKey", "strict")));
        assertThat(gate.path("code").asInt()).isZero();
        assertThat(gate.path("data").path("policyKey").asText())
                .isEqualTo("gate-template-pack@1.0.0#strict");
        assertThat(gate.path("data").path("checks").toString()).contains("95");
        assertThat(gate.path("data").path("decision").asText()).isEqualTo("PASS");

        JsonNode conflict = readTree(postApi("/api/v1/quality-gates",
                Map.of("runId", runId, "gateTemplateKey", "strict", "maxFail", 0)));
        assertThat(conflict.path("code").asInt()).isEqualTo(40000);
        JsonNode unknown = readTree(postApi("/api/v1/quality-gates",
                Map.of("runId", runId, "gateTemplateKey", "missing")));
        assertThat(unknown.path("code").asInt()).isEqualTo(40000);

        Map<String, Object> weakManifest = new HashMap<>(manifest);
        weakManifest.put("gateTemplates", List.of(Map.of(
                "key", "weak", "name", "弱政策",
                "policy", Map.of("minCoverage", 50))));
        JsonNode weak = readTree(postApi("/api/v1/domain-packs", Map.of(
                "packKey", "weak-gate-pack", "version", "1.0.0",
                "name", "弱 Gate 领域包", "manifest", weakManifest)));
        assertThat(weak.path("code").asInt()).isEqualTo(40000);
    }

    @Test
    void gateBlocksMixedJudgeCalibrationProtocols() throws Exception {
        String datasetId = createDataset("judge-protocol-mixed");
        for (int i = 0; i < 10; i++) {
            fakeLlm.outputs.put("q-proto-" + i, "ok");
            addItem(datasetId, "q-proto-" + i, "ok", null);
        }
        fakeLlm.judgeOutput = "{\"score\":9,\"confidence\":0.9,\"reason\":\"ok\"}";
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须准确");
        spec.put("minScore", 6);
        spec.put("referenceRequired", false);
        String evaluatorId = createEvaluator("judge-protocol-mixed", "LLM_JUDGE", spec);
        JsonNode detail = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "judge-protocol-mixed-run", 202)
                        .path("data").path("runId").asText());
        String runId = detail.path("data").path("run").path("id").asText();
        String evaluatorKey = detail.path("data").path("items").get(0)
                .path("evaluatorResults").get(0).path("evaluatorKey").asText();
        try {
            setReviewer("proto-reviewer");
            for (JsonNode item : detail.path("data").path("items")) {
                judgeCalibrationService.review("ws-default", datasetId, runId,
                        item.path("itemId").asText(), evaluatorKey, "PASS", "ok");
            }
        } finally {
            CurrentContext.clear();
        }
        String firstItem = detail.path("data").path("items").get(0).path("itemId").asText();
        judgeReviewMapper.update(null,
                Wrappers.<io.emonlens.server.storage.entity.JudgeReviewEntity>lambdaUpdate()
                        .eq(io.emonlens.server.storage.entity.JudgeReviewEntity::getRunId, runId)
                        .eq(io.emonlens.server.storage.entity.JudgeReviewEntity::getItemId, firstItem)
                        .set(io.emonlens.server.storage.entity.JudgeReviewEntity::getProtocolVersion,
                                "judge-calibration/1"));
        JsonNode gate = createGate(runId);
        assertThat(gate.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(gate.path("data").path("checks").toString())
                .contains("JUDGE_CALIBRATION")
                .contains("协议一致")
                .contains("judge-calibration/1");
    }

    @Test
    void gateBlocksJudgeRunUntilCalibrationPasses() throws Exception {
        String datasetId = createDataset("gate-judge-calibration");
        for (int i = 0; i < 10; i++) {
            fakeLlm.outputs.put("q-gate-cal-" + i, "ok");
            addItem(datasetId, "q-gate-cal-" + i, "ok", null);
        }
        fakeLlm.judgeOutput = "{\"score\":9,\"confidence\":0.9,\"reason\":\"ok\"}";
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须准确");
        spec.put("minScore", 6);
        spec.put("referenceRequired", false);
        String evaluatorId = createEvaluator("gate-judge-calibration", "LLM_JUDGE", spec);
        JsonNode detail = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "gate-judge-cal-run", 202)
                        .path("data").path("runId").asText());
        String runId = detail.path("data").path("run").path("id").asText();
        String evaluatorKey = detail.path("data").path("items").get(0)
                .path("evaluatorResults").get(0).path("evaluatorKey").asText();

        JsonNode blocked = createGate(runId);
        assertThat(blocked.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.path("data").path("checks").toString())
                .contains("JUDGE_CALIBRATION")
                .contains("reviewed=0/10");

        String itemId = detail.path("data").path("items").get(0).path("itemId").asText();
        for (int i = 0; i < 9; i++) {
            String id = detail.path("data").path("items").get(i).path("itemId").asText();
            putApi("/api/v1/datasets/" + datasetId + "/runs/" + runId
                    + "/judge-calibration/" + id,
                    Map.of("evaluatorKey", evaluatorKey, "humanLabel", "PASS"));
        }
        JsonNode incomplete = createGate(runId);
        assertThat(incomplete.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(incomplete.path("data").path("checks").toString()).contains("reviewed=9/10");
        String earlyExport = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId
                                + "/judge-calibration/export?evaluatorKey=" + evaluatorKey)
                        .header("Authorization", adminAuth()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(readTree(earlyExport).path("code").asInt()).isEqualTo(40901);

        String lastId = detail.path("data").path("items").get(9).path("itemId").asText();
        putApi("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/judge-calibration/" + lastId,
                Map.of("evaluatorKey", evaluatorKey, "humanLabel", "PASS"));
        JsonNode passed = createGate(runId);
        assertThat(passed.path("data").path("decision").asText()).isEqualTo("PASS");
        assertThat(passed.path("data").path("checks").toString())
                .contains("JUDGE_CALIBRATION")
                .contains("reviewed=10/10")
                .contains("agreement=100.0%");
        MockHttpServletResponse exported = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId
                                + "/judge-calibration/export?evaluatorKey=" + evaluatorKey)
                        .header("Authorization", adminAuth()))
                .andReturn().getResponse();
        assertThat(exported.getStatus()).isEqualTo(200);
        assertThat(exported.getContentType()).startsWith("text/csv");
        String csv = exported.getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("evaluatorKey,itemId,judgeLabel,humanLabel,agreement");
        assertThat(csv.split("\n")).hasSize(11);
        assertThat(csv).contains(",YES,");

        String passedGateId = passed.path("data").path("id").asText();
        JsonNode passVerification = readTree(get("/api/v1/quality-gates/" + passedGateId + "/verification"));
        assertThat(passVerification.path("data").path("usable").asBoolean()).isTrue();
        assertThat(passVerification.path("data").path("judgeCalibration").path("status").asText())
                .isEqualTo("PASS");

        // Gate 创建后若校准证据被删（绕过 API），历史验证必须重新校验并失效
        judgeReviewMapper.delete(Wrappers.<io.emonlens.server.storage.entity.JudgeReviewEntity>lambdaQuery()
                .eq(io.emonlens.server.storage.entity.JudgeReviewEntity::getRunId, runId));
        JsonNode tampered = readTree(get("/api/v1/quality-gates/" + passedGateId + "/verification"));
        assertThat(tampered.path("data").path("usable").asBoolean()).isFalse();
        assertThat(tampered.path("data").path("reasons").toString())
                .contains("JUDGE_CALIBRATION")
                .contains("reviewed=0/10");
        assertThat(tampered.path("data").path("judgeCalibration").path("status").asText())
                .isEqualTo("BLOCKED");

        // item 顺序不保证，但 id 来自同一详情，至少确保变量被使用且不是空白
        assertThat(itemId).isNotBlank();

        // 低一致率 -> Gate FAIL，而不是 PASS
        String lowDataset = createDataset("gate-judge-low-agreement");
        for (int i = 0; i < 10; i++) {
            fakeLlm.outputs.put("q-low-cal-" + i, "ok");
            addItem(lowDataset, "q-low-cal-" + i, "ok", null);
        }
        String lowEvaluator = createEvaluator("gate-judge-low", "LLM_JUDGE", spec);
        JsonNode lowDetail = awaitRun(lowDataset,
                runAsync(lowDataset, lowEvaluator, "REPLAY", "gate-judge-low-run", 202)
                        .path("data").path("runId").asText());
        String lowRunId = lowDetail.path("data").path("run").path("id").asText();
        String lowKey = lowDetail.path("data").path("items").get(0)
                .path("evaluatorResults").get(0).path("evaluatorKey").asText();
        for (int i = 0; i < 10; i++) {
            String id = lowDetail.path("data").path("items").get(i).path("itemId").asText();
            putApi("/api/v1/datasets/" + lowDataset + "/runs/" + lowRunId
                    + "/judge-calibration/" + id,
                    Map.of("evaluatorKey", lowKey, "humanLabel", i < 6 ? "PASS" : "FAIL"));
        }
        JsonNode lowGate = createGate(lowRunId);
        assertThat(lowGate.path("data").path("decision").asText()).isEqualTo("FAIL");
        assertThat(lowGate.path("data").path("checks").toString())
                .contains("JUDGE_CALIBRATION")
                .contains("agreement=60.0%");
    }

    @Test
    void gateHistoryKeepsSupersededVerification() throws Exception {
        fakeLlm.outputs.put("q-gate-history", "ok");
        String historyDataset = createDataset("gate-history");
        addItem(historyDataset, "q-gate-history", "ok", null);
        String historyEvaluator = createEvaluator("gate-history-exact", "EXPRESSION", exactMatchSpec());
        String historyRun = awaitRun(historyDataset,
                runAsync(historyDataset, historyEvaluator, "REPLAY", "gate-history-1", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        String oldGateId = createGate(historyRun).path("data").path("id").asText();
        qualityGateMapper.update(null, Wrappers.<QualityGateEntity>lambdaUpdate()
                .eq(QualityGateEntity::getId, oldGateId)
                .set(QualityGateEntity::getCreatedAt, LocalDateTime.now().minusMinutes(10)));
        String newGateId = createGate(historyRun).path("data").path("id").asText();

        JsonNode history = readTree(get("/api/v1/quality-gates/history?runId=" + historyRun));
        assertThat(history.path("code").asInt()).isZero();
        assertThat(history.path("data").size()).isEqualTo(2);
        JsonNode newest = history.path("data").get(0);
        JsonNode older = history.path("data").get(1);
        assertThat(newest.path("gate").path("id").asText()).isEqualTo(newGateId);
        assertThat(newest.path("verification").path("usable").asBoolean()).isTrue();
        assertThat(older.path("gate").path("id").asText()).isEqualTo(oldGateId);
        assertThat(older.path("verification").path("usable").asBoolean()).isFalse();
        assertThat(older.path("verification").path("superseded").asBoolean()).isTrue();
        assertThat(older.path("verification").path("reasons").toString()).contains("SUPERSEDED");
    }

    @Test
    void openApiSubmitsAndReadsDatasetEvalRun() throws Exception {
        String datasetId = createDataset("open-eval-run");
        fakeLlm.outputs.put("open-q", "ok");
        addItem(datasetId, "open-q", "ok", null);
        String evaluatorId = createEvaluator("open-eval-exact", "EXPRESSION", exactMatchSpec());
        String token = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "sdk-eval", "scopes", "eval:run,eval:read")))
                .path("data").path("token").asText();

        JsonNode created = openPost("/api/v1/open/eval-runs", token, "open-eval-1",
                Map.of("datasetId", datasetId, "evaluatorId", evaluatorId, "mode", "REPLAY"));
        assertThat(created.path("code").asInt()).isZero();
        String runId = created.path("data").path("runId").asText();
        assertThat(runId).isNotBlank();

        JsonNode pending = readTree(openGet("/api/v1/open/eval-runs/" + runId, token));
        assertThat(pending.path("code").asInt()).isZero();
        assertThat(pending.path("data").path("run").path("id").asText()).isEqualTo(runId);

        awaitRun(datasetId, runId);
        JsonNode completed = readTree(openGet("/api/v1/open/eval-runs/" + runId, token));
        assertThat(completed.path("data").path("run").path("status").asText())
                .isEqualTo("COMPLETED");
        assertThat(completed.path("data").path("run").path("passCount").asInt()).isEqualTo(1);
        // CI 消费 unknown/error/coverage；开放 API 必须回传补全后的计数，否则 SDK 退出码失效
        assertThat(completed.path("data").path("run").path("coverage").asDouble()).isEqualTo(100.0);
        assertThat(completed.path("data").path("run").path("unknownCount").asInt()).isZero();
        assertThat(completed.path("data").path("run").path("configSnapshot").isMissingNode()
                || completed.path("data").path("run").path("configSnapshot").isNull()).isTrue();

        String wrongScope = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "wrong-eval-scope", "scopes", "score:read")))
                .path("data").path("token").asText();
        JsonNode forbidden = openPost("/api/v1/open/eval-runs", wrongScope, "open-eval-2",
                Map.of("datasetId", datasetId, "evaluatorId", evaluatorId, "mode", "REPLAY"));
        assertThat(forbidden.path("code").asInt()).isEqualTo(40301);
    }

    @Test
    void openTokenLegacyScopeWithSpacesStillAuthorizes() throws Exception {
        // 历史 token 的 scope 串可能带空格（如 "score:read, monitors:write"）；鉴权需 trim，否则误 403
        String plain = "eak-legacy-space-token-test-0001";
        // 直插历史 token（scope 带空格）；HTTP 之外无登录上下文，先用超管上下文拿 workspaceId
        CurrentContext.set(new CurrentUser("admin", "admin", true, "ws-default", Set.of()));
        io.emonlens.server.storage.entity.AccessTokenEntity legacy =
                new io.emonlens.server.storage.entity.AccessTokenEntity();
        legacy.setTokenHash(io.emonlens.common.Sha256Utils.hash(plain));
        legacy.setTokenMasked("eak-****-0001");
        legacy.setName("legacy-space");
        legacy.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        legacy.setScopes("score:read, monitors:write");
        legacy.setStatus(io.emonlens.server.storage.entity.AccessTokenEntity.STATUS_ACTIVE);
        legacy.setCreatedAt(java.time.LocalDateTime.now());
        try {
            accessTokenMapper.insert(legacy);
        } finally {
            CurrentContext.clear();
        }

        // score:read（第一段）：无快照 → code 40401（若 scope 校验失败则是 40301）
        MockHttpServletResponse scoreResp = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/open/score/agents/no-such-agent")
                        .header("X-Api-Token", plain))
                .andReturn().getResponse();
        JsonNode first = readTree(scoreResp.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(first.path("code").asInt()).isEqualTo(40401);
        // monitors:write（带空格的第二段，修复前会 403）
        JsonNode second = openPost("/api/v1/open/monitors", plain, "legacy-mon-1",
                Map.of("agentKey", "a-legacy", "monitorKey", "m-legacy", "kind", "METRIC",
                        "spec", Map.of("metric", "latency", "thresholdValue", 100)));
        assertThat(second.path("code").asInt()).isZero();
        assertThat(second.path("data").path("monitorKey").asText()).isEqualTo("m-legacy");
    }

    @Test
    void openTokenIssuanceRejectsUnknownOrBlankScopesAndNormalizes() throws Exception {
        // 未知 scope：防止预埋未来 scope 或任意字符串绕过后续鉴权扩展
        JsonNode unknown = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "bad-scope", "scopes", "score:read,admin:all")));
        assertThat(unknown.path("code").asInt()).isEqualTo(40000);
        assertThat(unknown.path("message").asText()).contains("admin:all");

        JsonNode blank = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "blank-scope", "scopes", " , ")));
        assertThat(blank.path("code").asInt()).isEqualTo(40000);

        JsonNode empty = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "empty-scope", "scopes", "")));
        assertThat(empty.path("code").asInt()).isEqualTo(40000);

        // 合法 scope：去重并规范化后落库
        JsonNode ok = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "norm-scope", "scopes", "monitors:write, score:read ,monitors:write")));
        assertThat(ok.path("code").asInt()).isZero();
        assertThat(ok.path("data").path("tokenMasked").asText()).contains("eak-****-");
        JsonNode saved = readTree(get("/api/v1/open-tokens"));
        String normalized = null;
        for (JsonNode t : saved.path("data")) {
            if ("norm-scope".equals(t.path("name").asText())) {
                normalized = t.path("scopes").asText();
            }
        }
        assertThat(normalized).isEqualTo("monitors:write,score:read");

        // 默认不传 scopes 仍回落 score:read
        JsonNode fallback = readTree(postApi("/api/v1/open-tokens", Map.of("name", "default-scope")));
        assertThat(fallback.path("code").asInt()).isZero();
        for (JsonNode t : readTree(get("/api/v1/open-tokens")).path("data")) {
            if ("default-scope".equals(t.path("name").asText())) {
                assertThat(t.path("scopes").asText()).isEqualTo("score:read");
            }
        }
    }

    @Test
    void openApiVerifiesHistoricalGateWithReadScope() throws Exception {
        fakeLlm.outputs.put("q-open-gate", "ok");
        String datasetIdForGate = createDataset("open-gate");
        addItem(datasetIdForGate, "q-open-gate", "ok", null);
        String evaluatorIdForGate = createEvaluator("open-gate-exact", "EXPRESSION", exactMatchSpec());
        String runIdForGate = awaitRun(datasetIdForGate,
                runAsync(datasetIdForGate, evaluatorIdForGate, "REPLAY", "open-gate-1", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        String gateId = createGate(runIdForGate).path("data").path("id").asText();
        String token = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "ci-gate", "scopes", "score:read")))
                .path("data").path("token").asText();
        assertThat(token).startsWith("eak-");

        JsonNode openVerification = readTree(openGet(
                "/api/v1/open/quality-gates/" + gateId + "/verification", token));
        assertThat(openVerification.path("code").asInt()).isZero();
        assertThat(openVerification.path("data").path("usable").asBoolean()).isTrue();
        assertThat(openVerification.path("data").path("decision").asText()).isEqualTo("PASS");
        assertThat(openVerification.path("data").path("runStatus").asText()).isEqualTo("COMPLETED");
        assertThat(openVerification.path("data").path("gateId").asText()).isEqualTo(gateId);

        String wrongScope = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "ci-wrong-scope", "scopes", "monitors:write")))
                .path("data").path("token").asText();
        JsonNode forbidden = readTree(openGet(
                "/api/v1/open/quality-gates/" + gateId + "/verification", wrongScope));
        assertThat(forbidden.path("code").asInt()).isEqualTo(40301);

        QualityGateEntity expiring = qualityGateMapper.selectById(gateId);
        expiring.setValidUntil(LocalDateTime.now().minusHours(1));
        qualityGateMapper.updateById(expiring);
        JsonNode expired = readTree(openGet(
                "/api/v1/open/quality-gates/" + gateId + "/verification", token));
        assertThat(expired.path("data").path("usable").asBoolean()).isFalse();
        assertThat(expired.path("data").path("reasons").toString()).contains("EXPIRED");
    }

    @Test
    void gateBlocksInterruptedIncompleteAndUnknownRuns() throws Exception {
        String dataset = createDataset("gate-interrupted");
        for (int i = 0; i < 10; i++) {
            String input = "q-gate-interrupted-" + i;
            fakeLlm.outputs.put(input, "ok");
            addItem(dataset, input, "ok", null);
        }
        String evaluator = createEvaluator("gate-interrupted-exact", "EXPRESSION", exactMatchSpec());
        String runId = awaitRun(dataset, runAsync(dataset, evaluator, "REPLAY", "gate-interrupted-1", 202)
                .path("data").path("runId").asText()).path("data").path("run").path("id").asText();

        List<DatasetRunItemEntity> items = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery().eq(DatasetRunItemEntity::getRunId, runId));
        assertThat(items).hasSize(10);
        DatasetRunEntity run = datasetRunMapper.selectById(runId);
        DatasetRunItemEntity last = items.get(9);
        datasetRunItemMapper.update(null, Wrappers.<DatasetRunItemEntity>lambdaUpdate()
                .eq(DatasetRunItemEntity::getId, last.getId())
                .set(DatasetRunItemEntity::getLabel, null)
                .set(DatasetRunItemEntity::getReason, null));
        run.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        datasetRunMapper.updateById(run);

        JsonNode interrupted = createGate(runId);
        assertThat(interrupted.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(interrupted.path("data").path("checks").toString())
                .contains("INCOMPLETE_COUNT").contains("RUN_EXECUTION");

        DatasetRunItemEntity first = items.get(0);
        first.setLabel(EvaluatorEntity.LABEL_ERROR);
        datasetRunItemMapper.updateById(first);
        run.setStatus(DatasetRunEntity.STATUS_PARTIAL_FAILED);
        datasetRunMapper.updateById(run);
        JsonNode partial = createGate(runId);
        assertThat(partial.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(partial.path("data").path("checks").toString()).contains("EXECUTION_ERRORS");

        first.setLabel(EvaluatorEntity.LABEL_UNKNOWN);
        datasetRunItemMapper.updateById(first);
        run.setStatus(DatasetRunEntity.STATUS_COMPLETED);
        datasetRunMapper.updateById(run);
        JsonNode unknown = createGate(runId);
        assertThat(unknown.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(unknown.path("data").path("checks").toString()).contains("UNKNOWN_BUDGET");
    }

    @Test
    void multiEvaluatorRunKeepsIndependentResultsAndRevisionBinding() throws Exception {
        fakeLlm.outputs.put("q-multi", "A");
        String datasetId = createDataset("pilot-multi");
        addItem(datasetId, "q-multi", null, null);

        Map<String, Object> passSpec = new HashMap<>();
        passSpec.put("type", "REGEX");
        passSpec.put("field", "completion");
        passSpec.put("pattern", "A");
        passSpec.put("requireMatch", true);
        String passId = createEvaluator("multi-pass", "REGEX", passSpec);

        Map<String, Object> failSpec = new HashMap<>();
        failSpec.put("type", "REGEX");
        failSpec.put("field", "completion");
        failSpec.put("pattern", "^B$");
        failSpec.put("requireMatch", true);
        String failId = createEvaluator("multi-fail", "REGEX", failSpec);

        JsonNode accepted = runAsyncMulti(datasetId, List.of(passId, failId), "multi-1", 202);
        JsonNode detail = awaitRun(datasetId, accepted.path("data").path("runId").asText());
        JsonNode run = detail.path("data").path("run");
        JsonNode item = detail.path("data").path("items").get(0);

        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("datasetRevisionId").asText()).isNotEmpty();
        assertThat(run.path("evaluatorCount").asInt()).isEqualTo(2);
        assertThat(run.path("evaluatorKeys").size()).isEqualTo(2);
        assertThat(run.path("configSnapshot").path("evaluators").size()).isEqualTo(2);
        assertThat(run.path("configSnapshot").path("datasetRevision").path("manifestHash").asText()).isNotEmpty();
        assertThat(run.path("candidateFingerprint").asText()).hasSize(64);

        // 聚合：任一 FAIL 即 FAIL；逐评估器结论仍独立保存
        assertThat(item.path("label").asText()).isEqualTo("FAIL");
        assertThat(item.path("evaluatorResults").size()).isEqualTo(2);
        Map<String, String> labels = new HashMap<>();
        for (JsonNode result : item.path("evaluatorResults")) {
            labels.put(result.path("evaluatorKey").asText(), result.path("label").asText());
        }
        assertThat(labels).containsEntry("multi-pass", "PASS").containsEntry("multi-fail", "FAIL");

        JsonNode revisions = readTree(get("/api/v1/datasets/" + datasetId + "/revisions"));
        assertThat(revisions.path("data").size()).isEqualTo(1);
        assertThat(revisions.path("data").get(0).path("itemCount").asInt()).isEqualTo(1);
    }

    @Test
    void traceModeRunsAreIndependentAndEvaluatorEditDoesNotRewriteOldRun() throws Exception {
        ingestTraceWithCompletion("pilot-trace-1", "问题", "A");
        String datasetId = createDataset("pilot-trace");
        addItem(datasetId, "问题", "A", "pilot-trace-1");

        String evaluatorId = createEvaluator("pilot-trace-exact", "EXPRESSION", exactMatchSpec());
        JsonNode first = runAsync(datasetId, evaluatorId, "TRACE", "trace-1", 202);
        JsonNode firstDetail = awaitRun(datasetId, first.path("data").path("runId").asText());
        JsonNode firstRun = firstDetail.path("data").path("run");
        assertThat(firstRun.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(firstRun.path("passCount").asInt()).isEqualTo(1);
        String oldFingerprint = firstRun.path("configSnapshot").path("evaluator").path("fingerprint").asText();
        JsonNode firstItem = firstDetail.path("data").path("items").get(0);
        assertThat(firstItem.path("sampleSnapshot").path("candidateOutput").asText()).isEqualTo("A");
        assertThat(firstItem.path("label").asText()).isEqualTo("PASS");

        // 运行后修改评估器：旧 run 的快照与判定不变
        Map<String, Object> changed = new HashMap<>();
        changed.put("type", "EXPRESSION");
        changed.put("passWhen", "candidate_output == 'B'");
        changed.put("referenceRequired", false);
        putApi("/api/v1/evaluators/" + evaluatorId, Map.of("spec", changed));
        JsonNode versions = readTree(get("/api/v1/evaluators/" + evaluatorId + "/versions"));
        assertThat(versions.path("data").size()).isEqualTo(2);
        assertThat(versions.path("data").get(0).path("version").asInt()).isEqualTo(2);
        assertThat(versions.path("data").get(1).path("version").asInt()).isEqualTo(1);
        assertThat(versions.path("data").get(1).path("spec").path("passWhen").asText())
                .isEqualTo(EXACT_MATCH_SPEC);
        assertThat(versions.path("data").get(0).path("fingerprint").asText())
                .isNotEqualTo(versions.path("data").get(1).path("fingerprint").asText());

        JsonNode oldAfterEdit = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/" + firstRun.path("id").asText()));
        assertThat(oldAfterEdit.path("data").path("run").path("configSnapshot")
                .path("evaluator").path("fingerprint").asText()).isEqualTo(oldFingerprint);
        assertThat(oldAfterEdit.path("data").path("items").get(0).path("label").asText()).isEqualTo("PASS");

        // 同一 Trace 用相反条件再跑：分别保存相反结果
        JsonNode second = runAsync(datasetId, evaluatorId, "TRACE", "trace-2", 202);
        JsonNode secondDetail = awaitRun(datasetId, second.path("data").path("runId").asText());
        JsonNode secondRun = secondDetail.path("data").path("run");
        assertThat(secondRun.path("failCount").asInt()).isEqualTo(1);
        assertThat(secondRun.path("configSnapshot").path("evaluator").path("fingerprint").asText())
                .isNotEqualTo(oldFingerprint);
        assertThat(secondDetail.path("data").path("items").get(0).path("label").asText()).isEqualTo("FAIL");

        JsonNode datasetDetail = readTree(get("/api/v1/datasets/" + datasetId));
        assertThat(datasetDetail.path("data").path("runs").size()).isEqualTo(2);
    }

    @Test
    void traceModeFallsBackToAgentSpanOutputWhenContentNotCaptured() throws Exception {
        ingestTraceWithAgentOutput("pilot-root-output", "问题", "ROOT-ANSWER");
        String datasetId = createDataset("pilot-root-output");
        addItem(datasetId, null, null, "pilot-root-output");
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "EXPRESSION");
        spec.put("passWhen", "candidate_output != null && candidate_output.contains('ROOT-ANSWER')");
        spec.put("referenceRequired", false);
        String evaluatorId = createEvaluator("pilot-root-output-check", "EXPRESSION", spec);

        JsonNode accepted = runAsync(datasetId, evaluatorId, "TRACE", "trace-root-output", 202);
        JsonNode detail = awaitRun(datasetId, accepted.path("data").path("runId").asText());
        JsonNode run = detail.path("data").path("run");
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("passCount").asInt()).isEqualTo(1);
        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("PASS");
        assertThat(item.path("sampleSnapshot").path("candidateOutput").asText()).isEqualTo("ROOT-ANSWER");
        assertThat(item.path("sampleSnapshot").path("candidateSource").asText()).isEqualTo("AGENT_SPAN");
    }

    @Test
    void llmJudgeReceivesReferenceAndRecordsUsage() throws Exception {
        fakeLlm.calls.clear();
        String datasetId = createDataset("pilot-judge");
        fakeLlm.outputs.put("q-judge", "候选输出");
        addItem(datasetId, "q-judge", "参考答案", null);
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须正确");
        spec.put("minScore", 6);
        spec.put("referenceRequired", true);
        String evaluatorId = createEvaluator("pilot-judge-ref", "LLM_JUDGE", spec);

        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "judge-1", 202);
        JsonNode detail = awaitRun(datasetId, accepted.path("data").path("runId").asText());
        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("PASS");
        assertThat(item.path("resultDetails").path("judgeAdvice").asBoolean()).isTrue();
        assertThat(item.path("resultDetails").path("calls").path("generation").asInt()).isEqualTo(1);
        assertThat(item.path("resultDetails").path("calls").path("judge").asInt()).isEqualTo(1);
        assertThat(item.path("resultDetails").path("usage").path("known").asBoolean()).isTrue();
        assertThat(item.path("resultDetails").path("usage").path("totalTokens").asInt()).isEqualTo(53);

        JsonNode usage = detail.path("data").path("run").path("usageSummary");
        assertThat(usage.path("externalCalls").asInt()).isEqualTo(2);
        assertThat(usage.path("totalTokens").asInt()).isEqualTo(53);

        boolean generationChecked = false;
        boolean judgeChecked = false;
        for (String[] call : fakeLlm.calls) {
            if (call[0].contains("You are an evaluator")) {
                assertThat(call[1]).contains("Reference answer").contains("参考答案")
                        .contains("Candidate output").contains("候选输出");
                judgeChecked = true;
            } else {
                assertThat(call[1]).doesNotContain("参考答案");
                generationChecked = true;
            }
        }
        assertThat(generationChecked).isTrue();
        assertThat(judgeChecked).isTrue();
    }

    @Test
    void llmJudgeReferenceRequiredMissingIsUnknownWithoutJudgeCall() throws Exception {
        String datasetId = createDataset("pilot-judge-missing");
        fakeLlm.outputs.put("q-judge-missing", "候选输出");
        addItem(datasetId, "q-judge-missing", null, null);
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须正确");
        spec.put("referenceRequired", true);
        String evaluatorId = createEvaluator("pilot-judge-required", "LLM_JUDGE", spec);

        JsonNode accepted = runAsync(datasetId, evaluatorId, "REPLAY", "judge-missing-1", 202);
        JsonNode detail = awaitRun(datasetId, accepted.path("data").path("runId").asText());
        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(item.path("resultDetails").path("reasonCode").asText()).isEqualTo("REFERENCE_MISSING");
        assertThat(item.path("resultDetails").path("calls").path("judge").asInt()).isZero();
        assertThat(item.path("resultDetails").path("calls").path("generation").asInt()).isEqualTo(1);
        assertThat(detail.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
        assertThat(detail.path("data").path("run").path("coverage").asDouble()).isZero();
        assertThat(detail.path("data").path("run").path("passRate").isMissingNode()
                || detail.path("data").path("run").path("passRate").isNull()).isTrue();
    }

    @Test
    void runningTraceIsUnknownAndDoesNotUseIntermediateCompletion() throws Exception {
        String traceId = "pilot-running-trace";
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> root = new HashMap<>();
        root.put("traceId", traceId);
        root.put("spanId", "root");
        root.put("agentKey", "pilot-agent");
        root.put("agentVersion", "v1");
        root.put("spanType", "agent");
        root.put("spanName", "invoke_agent");
        root.put("startTime", "2026-10-19T10:00:01");
        root.put("status", "OK");
        Map<String, Object> llm = new HashMap<>();
        llm.put("traceId", traceId);
        llm.put("spanId", "llm");
        llm.put("parentSpanId", "root");
        llm.put("agentKey", "pilot-agent");
        llm.put("agentVersion", "v1");
        llm.put("spanType", "llm");
        llm.put("spanName", "call_model");
        llm.put("startTime", "2026-10-19T10:00:02");
        llm.put("endTime", "2026-10-19T10:00:04");
        llm.put("status", "OK");
        llm.put("content", Map.of("prompt", "问题", "completion", "中间输出"));
        spans.add(root);
        spans.add(llm);
        post("/api/v1/ingest/events", spans);
        await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    try {
                        return "RUNNING".equals(readTree(json).path("data")
                                .path("summary").path("status").asText());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                ASYNC_TIMEOUT_MS);

        String datasetId = createDataset("pilot-running");
        addItem(datasetId, "问题", "中间输出", traceId);
        String evaluatorId = createEvaluator("pilot-running-exact", "EXPRESSION", exactMatchSpec());
        JsonNode accepted = runAsync(datasetId, evaluatorId, "TRACE", "running-1", 202);
        JsonNode detail = awaitRun(datasetId, accepted.path("data").path("runId").asText());
        JsonNode item = detail.path("data").path("items").get(0);
        assertThat(item.path("label").asText()).isEqualTo("UNKNOWN");
        assertThat(item.path("resultDetails").path("reasonCode").asText()).isEqualTo("TRACE_NOT_COMPLETED");
        assertThat(detail.path("data").path("run").path("status").asText()).isEqualTo("COMPLETED");
    }

    @Test
    void judgeCalibrationRequiresBlindHumanLabelsAndComputesAgreement() throws Exception {
        fakeLlm.outputs.put("q-cal-a", "ok");
        fakeLlm.outputs.put("q-cal-b", "ok");
        fakeLlm.judgeOutput = "{\"score\":9,\"confidence\":0.9,\"reason\":\"looks good\"}";
        String datasetId = createDataset("judge-calibration");
        addItem(datasetId, "q-cal-a", "ok", null);
        addItem(datasetId, "q-cal-b", "ok", null);
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("rubric", "回答必须准确");
        spec.put("minScore", 6);
        spec.put("referenceRequired", false);
        String evaluatorId = createEvaluator("judge-calibration", "LLM_JUDGE", spec);
        JsonNode detail = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "judge-calibration-run", 202)
                        .path("data").path("runId").asText());
        String runId = detail.path("data").path("run").path("id").asText();
        Map<String, JsonNode> calibrationItems = itemsByInput(detail);
        JsonNode firstResult = calibrationItems.get("q-cal-a").path("evaluatorResults").get(0);
        String evaluatorKey = firstResult.path("evaluatorKey").asText();
        assertThat(evaluatorKey).isEqualTo("judge-calibration");
        String itemA = calibrationItems.get("q-cal-a").path("itemId").asText();
        String itemB = calibrationItems.get("q-cal-b").path("itemId").asText();

        JsonNode initial = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/judge-calibration?evaluatorKey=" + evaluatorKey));
        assertThat(initial.path("code").asInt()).isZero();
        assertThat(initial.path("data").path("summary").path("reviewable").asInt()).isEqualTo(2);
        assertThat(initial.path("data").path("summary").path("status").asText()).isEqualTo("NOT_REVIEWED");
        assertThat(initial.path("data").path("items").size()).isEqualTo(2);
        assertThat(initial.path("data").path("items").toString()).contains("q-cal-a").contains("q-cal-b");
        for (JsonNode row : initial.path("data").path("items")) {
            // 盲审：提交前不能看到 judge 标签和理由
            assertThat(row.hasNonNull("judgeLabel")).isFalse();
            assertThat(row.hasNonNull("judgeReason")).isFalse();
            assertThat(row.path("reviewed").asBoolean()).isFalse();
        }

        JsonNode first = readTree(putApi("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/judge-calibration/" + itemA,
                Map.of("evaluatorKey", evaluatorKey, "humanLabel", "PASS", "note", "人工确认")));
        assertThat(first.path("data").path("humanLabel").asText()).isEqualTo("PASS");
        assertThat(first.path("data").has("judgeLabel")).isFalse();
        JsonNode one = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/judge-calibration?evaluatorKey=" + evaluatorKey));
        assertThat(one.path("data").path("summary").path("reviewed").asInt()).isEqualTo(1);
        assertThat(one.path("data").path("summary").path("status").asText())
                .isEqualTo("IN_PROGRESS");
        assertThat(one.path("data").path("items").toString()).doesNotContain("judgeLabel\":\"PASS");

        putApi("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/judge-calibration/" + itemB,
                Map.of("evaluatorKey", evaluatorKey, "humanLabel", "FAIL"));
        JsonNode disagreement = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/judge-calibration?evaluatorKey=" + evaluatorKey));
        assertThat(disagreement.path("data").path("summary").path("reviewed").asInt()).isEqualTo(2);
        assertThat(disagreement.path("data").path("summary").path("agreements").asInt()).isEqualTo(1);
        assertThat(disagreement.path("data").path("summary").path("agreementRate").asDouble())
                .isEqualTo(50.0);
        assertThat(disagreement.path("data").path("summary").path("kappa").asDouble()).isEqualTo(0.0);
        assertThat(disagreement.path("data").path("summary").path("protocolVersion").asText())
                .isEqualTo("judge-calibration/2");
        assertThat(disagreement.path("data").path("summary").path("protocolMixed").asBoolean()).isFalse();
        JsonNode matrix = disagreement.path("data").path("summary").path("confusionMatrix");
        assertThat(matrix.path("tp").asInt()).isEqualTo(1);
        assertThat(matrix.path("fp").asInt()).isEqualTo(1);
        assertThat(matrix.path("tn").asInt()).isZero();
        assertThat(matrix.path("fn").asInt()).isZero();
        assertThat(matrix.path("accuracy").asDouble()).isEqualTo(50.0);
        assertThat(matrix.path("precision").asDouble()).isEqualTo(50.0);
        assertThat(matrix.path("recall").asDouble()).isEqualTo(100.0);
        assertThat(matrix.path("f1").asDouble()).isEqualTo(66.7);

        JsonNode overwrite = readTree(putApi("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/judge-calibration/" + itemB,
                Map.of("evaluatorKey", evaluatorKey, "humanLabel", "PASS")));
        assertThat(overwrite.path("code").asInt()).isEqualTo(40901);
        JsonNode agreed = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/judge-calibration?evaluatorKey=" + evaluatorKey));
        assertThat(agreed.path("data").path("summary").path("agreementRate").asDouble())
                .isEqualTo(50.0);
        assertThat(agreed.path("data").path("summary").path("revealed").asBoolean()).isTrue();
        assertThat(agreed.path("data").path("summary").path("agreements").asInt()).isEqualTo(1);

        JsonNode badLabel = readTree(putApi("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/judge-calibration/" + itemA,
                Map.of("evaluatorKey", evaluatorKey, "humanLabel", "MAYBE")));
        assertThat(badLabel.path("code").asInt()).isEqualTo(40000);

        String expressionEvaluator = createEvaluator("calibration-expression", "EXPRESSION", exactMatchSpec());
        JsonNode expressionRun = awaitRun(datasetId,
                runAsync(datasetId, expressionEvaluator, "REPLAY", "calibration-expression-run", 202)
                        .path("data").path("runId").asText());
        JsonNode notJudge = readTree(get("/api/v1/datasets/" + datasetId + "/runs/"
                + expressionRun.path("data").path("run").path("id").asText() + "/judge-calibration"));
        assertThat(notJudge.path("code").asInt()).isEqualTo(40000);
    }

    @Test
    void legacySyncEntryIsRejectedInPilotMode() throws Exception {
        String datasetId = createDataset("pilot-legacy");
        addItem(datasetId, "q-legacy", "A", null);
        String evaluatorId = createEvaluator("pilot-legacy-exact", "EXPRESSION", exactMatchSpec());

        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/runs",
                Map.of("evaluatorId", evaluatorId, "mode", "REPLAY")));
        assertThat(response.path("code").asInt()).isEqualTo(40000);
        assertThat(response.path("message").asText()).contains("runs/async");
    }

    // ---------- helpers ----------

    private void ingestTraceWithAgentOutput(String traceId, String input, String output) throws Exception {
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> root = span("pilot-agent", traceId, "root", null,
                "agent", "invoke_agent", null, null, 1, 5);
        root.put("input", input);
        root.put("output", output);
        Map<String, Object> llm = span("pilot-agent", traceId, "llm", "root",
                "llm", "call_model", null, "pilot-model", 2, 4);
        spans.add(root);
        spans.add(llm);
        post("/api/v1/ingest/events", spans);
        await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    try {
                        return "COMPLETED".equals(readTree(json).path("data")
                                .path("summary").path("status").asText());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                ASYNC_TIMEOUT_MS);
    }

    private void ingestTraceWithCompletion(String traceId, String input, String completion) throws Exception {
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> root = span("pilot-agent", traceId, "root", null,
                "agent", "invoke_agent", null, null, 1, 5);
        Map<String, Object> llm = span("pilot-agent", traceId, "llm", "root",
                "llm", "call_model", null, "pilot-model", 2, 4);
        llm.put("content", Map.of("prompt", input, "completion", completion));
        spans.add(root);
        spans.add(llm);
        post("/api/v1/ingest/events", spans);
        await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    try {
                        return "COMPLETED".equals(readTree(json).path("data")
                                .path("summary").path("status").asText());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                ASYNC_TIMEOUT_MS);
    }

    private Map<String, Object> exactMatchSpec() {
        Map<String, Object> spec = new HashMap<>();
        spec.put("type", "EXPRESSION");
        spec.put("passWhen", EXACT_MATCH_SPEC);
        spec.put("referenceRequired", true);
        spec.put("preset", "reference-exact-match");
        return spec;
    }


    @Test
    void runMaterializesTaskPerItemAndEvaluatorWithAttemptEvidence() throws Exception {
        String datasetId = createDataset("pilot-task-ledger");
        addItem(datasetId, "q-task-a", "A", null);
        addItem(datasetId, "q-task-b", "B", null);
        fakeLlm.outputs.put("q-task-a", "A");
        fakeLlm.outputs.put("q-task-b", "B");
        String evaluatorId = createEvaluator("pilot-task-exact", "EXPRESSION", exactMatchSpec());
        String runId = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "task-ledger-source", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();

        JsonNode tasks = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/tasks"));
        assertThat(tasks.path("code").asInt()).isZero();
        JsonNode data = tasks.path("data");
        assertThat(data.path("legacy").asBoolean()).isFalse();
        assertThat(data.path("summary").path("total").asInt()).isEqualTo(2);
        assertThat(data.path("summary").path("succeeded").asInt()).isEqualTo(2);
        assertThat(data.path("summary").path("pass").asInt()).isEqualTo(2);
        assertThat(data.path("summary").path("open").asInt()).isZero();
        assertThat(data.path("records")).hasSize(2);
        for (JsonNode record : data.path("records")) {
            assertThat(record.path("status").asText()).isEqualTo(EvalTaskEntity.STATUS_SUCCEEDED);
            assertThat(record.path("verdict").asText()).isEqualTo(EvaluatorEntity.LABEL_PASS);
            assertThat(record.path("attemptCount").asInt()).isEqualTo(1);
            assertThat(record.path("evaluatorKey").asText()).isEqualTo("pilot-task-exact");
            assertThat(record.path("evidenceHash").asText()).isNotBlank();
            assertThat(record.path("attempts")).hasSize(1);
            assertThat(record.path("attempts").get(0).path("callSeq").asInt()).isEqualTo(1);
            assertThat(record.path("attempts").get(0).path("outcome").asText()).isEqualTo("SUCCEEDED");
            Long attempts = evalAttemptMapper.selectCount(Wrappers.<EvalAttemptEntity>lambdaQuery()
                    .eq(EvalAttemptEntity::getTaskId, record.path("id").asText()));
            assertThat(attempts).isEqualTo(1L);
        }
        // 负向过滤 + 分页不改变汇总
        JsonNode filtered = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/tasks?status=FAILED&pageSize=1"));
        assertThat(filtered.path("data").path("records")).isEmpty();
        assertThat(filtered.path("data").path("summary").path("total").asInt()).isEqualTo(2);
    }


    @Test
    void multiEvaluatorRunMaterializesCartesianTaskLedger() throws Exception {
        String datasetId = createDataset("pilot-task-cartesian");
        addItem(datasetId, "q-task-cart-a", "A", null);
        addItem(datasetId, "q-task-cart-b", "B", null);
        fakeLlm.outputs.put("q-task-cart-a", "A");
        fakeLlm.outputs.put("q-task-cart-b", "B");
        String first = createEvaluator("pilot-task-cart-1", "EXPRESSION", exactMatchSpec());
        String second = createEvaluator("pilot-task-cart-2", "EXPRESSION", exactMatchSpec());
        String runId = awaitRun(datasetId,
                runAsyncMulti(datasetId, List.of(first, second), "task-cartesian-source", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();

        JsonNode data = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/tasks?pageSize=200")).path("data");
        assertThat(data.path("summary").path("total").asInt()).isEqualTo(4);
        assertThat(data.path("summary").path("succeeded").asInt()).isEqualTo(4);
        assertThat(data.path("summary").path("pass").asInt()).isEqualTo(4);
        assertThat(data.path("evaluatorKeys")).hasSize(2);
        assertThat(data.path("records")).hasSize(4);
        Map<String, java.util.Set<Integer>> ordersByEvaluator = new HashMap<>();
        for (JsonNode record : data.path("records")) {
            ordersByEvaluator.computeIfAbsent(record.path("evaluatorKey").asText(),
                    key -> new java.util.HashSet<>()).add(record.path("itemOrder").asInt());
        }
        assertThat(ordersByEvaluator.get("pilot-task-cart-1")).containsExactlyInAnyOrder(0, 1);
        assertThat(ordersByEvaluator.get("pilot-task-cart-2")).containsExactlyInAnyOrder(0, 1);
        JsonNode filtered = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId
                + "/tasks?evaluatorKey=pilot-task-cart-2&pageSize=200")).path("data");
        assertThat(filtered.path("total").asInt()).isEqualTo(2);
        assertThat(filtered.path("records")).hasSize(2);
        assertThat(filtered.path("summary").path("total").asInt()).isEqualTo(4);
    }

    @Test
    void legacyRunWithoutTaskRowsIsReportedAsLegacy() throws Exception {
        String datasetId = createDataset("pilot-task-legacy");
        addItem(datasetId, "q-task-legacy", "A", null);
        fakeLlm.outputs.put("q-task-legacy", "A");
        String evaluatorId = createEvaluator("pilot-task-legacy-exact", "EXPRESSION", exactMatchSpec());
        String runId = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "task-legacy-source", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        evalTaskMapper.delete(Wrappers.<EvalTaskEntity>lambdaQuery()
                .eq(EvalTaskEntity::getRunId, runId));

        JsonNode data = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/tasks"))
                .path("data");
        assertThat(data.path("legacy").asBoolean()).isTrue();
        assertThat(data.path("summary").path("total").asInt()).isZero();
        assertThat(data.path("records")).isEmpty();
    }

    @Test
    void resumeCopiesExecutedTaskEvidenceAndResetsUnexecutedTasks() throws Exception {
        String datasetId = createDataset("pilot-task-resume");
        addItem(datasetId, "q-task-resume-a", "A", null);
        addItem(datasetId, "q-task-resume-b", "B", null);
        fakeLlm.outputs.put("q-task-resume-a", "A");
        fakeLlm.outputs.put("q-task-resume-b", "B");
        String evaluatorId = createEvaluator("pilot-task-resume-exact", "EXPRESSION", exactMatchSpec());
        String sourceId = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "task-resume-source", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();

        List<DatasetRunItemEntity> items = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery().eq(DatasetRunItemEntity::getRunId, sourceId));
        DatasetRunItemEntity missing = items.stream()
                .filter(i -> "q-task-resume-a".equals(itemInput(i))).findFirst().orElseThrow();
        DatasetRunItemEntity executed = items.stream()
                .filter(i -> !i.getId().equals(missing.getId())).findFirst().orElseThrow();
        EvalTaskEntity executedTask = evalTaskMapper.selectOne(Wrappers.<EvalTaskEntity>lambdaQuery()
                .eq(EvalTaskEntity::getRunId, sourceId)
                .eq(EvalTaskEntity::getRunItemId, executed.getId()));
        assertThat(executedTask).isNotNull();

        datasetRunItemMapper.update(null, Wrappers.<DatasetRunItemEntity>lambdaUpdate()
                .eq(DatasetRunItemEntity::getId, missing.getId())
                .set(DatasetRunItemEntity::getLabel, null)
                .set(DatasetRunItemEntity::getScore, null)
                .set(DatasetRunItemEntity::getReason, null)
                .set(DatasetRunItemEntity::getResultDetailsJson,
                        "{\"status\":\"INCOMPLETE\",\"reasonCode\":\"INTERRUPTED\"}"));
        DatasetRunEntity source = datasetRunMapper.selectById(sourceId);
        source.setStatus(DatasetRunEntity.STATUS_INTERRUPTED);
        source.setFinishedAt(LocalDateTime.now());
        datasetRunMapper.updateById(source);

        String newId = resume(datasetId, sourceId, "task-resume-attempt", 202)
                .path("data").path("id").asText();
        awaitRun(datasetId, newId);

        List<EvalTaskEntity> newTasks = evalTaskMapper.selectList(
                Wrappers.<EvalTaskEntity>lambdaQuery().eq(EvalTaskEntity::getRunId, newId)
                        .orderByAsc(EvalTaskEntity::getItemOrder));
        assertThat(newTasks).hasSize(2);
        EvalTaskEntity copied = newTasks.stream()
                .filter(t -> t.getItemId().equals(executed.getItemId())).findFirst().orElseThrow();
        assertThat(copied.getId()).isNotEqualTo(executedTask.getId());
        assertThat(copied.getExecutionStatus()).isEqualTo(EvalTaskEntity.STATUS_SUCCEEDED);
        assertThat(copied.getVerdict()).isEqualTo(executedTask.getVerdict());
        assertThat(copied.getAttemptCount()).isEqualTo(1);
        Long copiedAttempts = evalAttemptMapper.selectCount(Wrappers.<EvalAttemptEntity>lambdaQuery()
                .eq(EvalAttemptEntity::getTaskId, copied.getId()));
        assertThat(copiedAttempts).isEqualTo(1L);
        EvalTaskEntity rerun = newTasks.stream()
                .filter(t -> t.getItemId().equals(missing.getItemId())).findFirst().orElseThrow();
        assertThat(rerun.getExecutionStatus()).isEqualTo(EvalTaskEntity.STATUS_SUCCEEDED);
        assertThat(rerun.getVerdict()).isEqualTo(EvaluatorEntity.LABEL_PASS);
        // 源 run 的任务账本不被续跑改写
        assertThat(evalTaskMapper.selectById(executedTask.getId()).getExecutionStatus())
                .isEqualTo(EvalTaskEntity.STATUS_SUCCEEDED);
    }

    @Test
    void terminalRunWithStaleOpenTaskIsReconciled() throws Exception {
        String datasetId = createDataset("pilot-task-orphan");
        addItem(datasetId, "q-task-orphan", "A", null);
        fakeLlm.outputs.put("q-task-orphan", "A");
        String evaluatorId = createEvaluator("pilot-task-orphan-exact", "EXPRESSION", exactMatchSpec());
        String runId = awaitRun(datasetId,
                runAsync(datasetId, evaluatorId, "REPLAY", "task-orphan-source", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();

        EvalTaskEntity task = evalTaskMapper.selectOne(Wrappers.<EvalTaskEntity>lambdaQuery()
                .eq(EvalTaskEntity::getRunId, runId));
        assertThat(task).isNotNull();
        LocalDateTime stale = LocalDateTime.now().minusMinutes(10);
        task.setExecutionStatus(EvalTaskEntity.STATUS_RUNNING);
        task.setStartedAt(stale);
        task.setCreatedAt(stale);
        evalTaskMapper.updateById(task);
        DatasetRunEntity run = datasetRunMapper.selectById(runId);
        run.setStatus(DatasetRunEntity.STATUS_CANCELLED);
        run.setFinishedAt(LocalDateTime.now());
        datasetRunMapper.updateById(run);

        datasetRunService.recoverExpiredLeases();

        EvalTaskEntity settled = evalTaskMapper.selectById(task.getId());
        assertThat(settled.getExecutionStatus()).isEqualTo(EvalTaskEntity.STATUS_SKIPPED);
        assertThat(settled.getReasonCode()).isEqualTo("RUN_CANCELLED_TASK_ORPHANED");
        JsonNode summary = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/tasks"))
                .path("data").path("summary");
        assertThat(summary.path("open").asInt()).isZero();
        assertThat(summary.path("skipped").asInt()).isEqualTo(1);
    }

    @Test
    void tasksEndpointRejectsUnknownRun() throws Exception {
        String datasetId = createDataset("pilot-task-missing-run");
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetId + "/runs/not-a-run/tasks")
                        .header("Authorization", adminAuth()))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(readTree(response.getContentAsString()).path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.NOT_FOUND.getCode());

        // 跨数据集同样不可见：run 存在，但不属于请求路径中的 dataset
        String datasetA = createDataset("pilot-task-scope-a");
        String datasetB = createDataset("pilot-task-scope-b");
        addItem(datasetA, "q-task-scope", "A", null);
        fakeLlm.outputs.put("q-task-scope", "A");
        String evaluatorId = createEvaluator("pilot-task-scope-exact", "EXPRESSION", exactMatchSpec());
        String runId = awaitRun(datasetA,
                runAsync(datasetA, evaluatorId, "REPLAY", "task-scope-source", 202)
                        .path("data").path("runId").asText())
                .path("data").path("run").path("id").asText();
        MockHttpServletResponse crossDataset = mockMvc.perform(MockMvcRequestBuilders.get(
                        "/api/v1/datasets/" + datasetB + "/runs/" + runId + "/tasks")
                        .header("Authorization", adminAuth()))
                .andReturn().getResponse();
        assertThat(crossDataset.getStatus()).isEqualTo(200);
        assertThat(readTree(crossDataset.getContentAsString()).path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.NOT_FOUND.getCode());
    }

    @Test
    void duplicateDatasetKeyIsConflictNotInternalError() throws Exception {
        String key = "dup-key-" + System.nanoTime();
        createDataset(key);
        // 重复 key 必须是可读冲突（40901），不能是 50000 internal error——用户双击「创建」就会触发
        JsonNode second = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(second.path("code").asInt())
                .as("duplicate datasetKey: %s", second).isEqualTo(40901);
        assertThat(second.path("message").asText()).contains("已存在");
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private JsonNode createSuite(String key, List<String> evaluatorIds) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/eval-suites",
                Map.of("suiteKey", key, "name", key, "evaluatorIds", evaluatorIds)));
        assertThat(response.path("code").asInt()).isZero();
        return response;
    }

    private JsonNode runAsyncWithSuite(String datasetId, String suiteId,
                                       String idempotencyKey, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("suiteId", suiteId);
        body.put("mode", "REPLAY");
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("async suite run status").isEqualTo(expectedStatus);
        JsonNode json = readTree(response.getContentAsString());
        assertThat(json.path("code").asInt()).as("async suite run body: " + json).isZero();
        return json;
    }

    private String createEvaluator(String key, String type, Map<String, Object> spec) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators",
                Map.of("evaluatorKey", key, "type", type, "spec", spec)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private void addItem(String datasetId, String input, String expectedOutput, String traceId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (input != null) body.put("input", input);
        if (expectedOutput != null) body.put("expectedOutput", expectedOutput);
        if (traceId != null) body.put("traceId", traceId);
        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", body));
        assertThat(response.path("code").asInt()).isZero();
    }

    private JsonNode runAsyncWithBudget(String datasetId, String evaluatorId, int maxExternalCalls,
                                        String idempotencyKey, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorId", evaluatorId);
        body.put("mode", "REPLAY");
        body.put("maxExternalCalls", maxExternalCalls);
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("async budget run status").isEqualTo(expectedStatus);
        return readTree(response.getContentAsString());
    }

    private JsonNode runAsync(String datasetId, String evaluatorId, String mode,
                              String idempotencyKey, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorId", evaluatorId);
        body.put("mode", mode);
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("async run status").isEqualTo(expectedStatus);
        return readTree(response.getContentAsString());
    }

    private JsonNode openPost(String url, String token, String idempotencyKey, Object body)
            throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(url)
                        .header("X-Api-Token", token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        return readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String openGet(String url, String token) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get(url)
                        .header("X-Api-Token", token))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("open GET " + url + ": "
                + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
        return response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private JsonNode retry(String datasetId, String runId, String key, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId + "/retry")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", key))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("retry status: "
                + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(expectedStatus);
        return readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private JsonNode resume(String datasetId, String runId, String key, int expectedStatus) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/" + runId + "/resume")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", key))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("resume status: "
                + response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(expectedStatus);
        return readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String itemInput(DatasetRunItemEntity item) {
        try {
            return objectMapper.readTree(item.getSampleSnapshotJson()).path("input").asText();
        } catch (Exception e) {
            return "";
        }
    }

    private JsonNode createGate(String runId) throws Exception {
        return readTree(postApi("/api/v1/quality-gates", Map.of("runId", runId)));
    }

    private JsonNode runAsyncMulti(String datasetId, List<String> evaluatorIds,
                                    String idempotencyKey, int expectedStatus) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorIds", evaluatorIds);
        body.put("mode", "REPLAY");
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("async multi run status").isEqualTo(expectedStatus);
        return readTree(response.getContentAsString());
    }

    private JsonNode runAsyncVariant(String datasetId, String evaluatorId, String idempotencyKey,
                                     String systemPrompt) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("evaluatorIds", List.of(evaluatorId));
        body.put("mode", "REPLAY");
        body.put("systemPrompt", systemPrompt);
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(
                        "/api/v1/datasets/" + datasetId + "/runs/async")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("async variant run status").isEqualTo(202);
        return readTree(response.getContentAsString());
    }

    private JsonNode awaitRun(String datasetId, String runId) throws Exception {
        return await(
                () -> {
                    try {
                        return readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    String status = json.path("data").path("run").path("status").asText();
                    return !DatasetRunEntity.STATUS_PENDING.equals(status)
                            && !DatasetRunEntity.STATUS_RUNNING.equals(status);
                },
                60000);
    }

    private Map<String, JsonNode> itemsByInput(JsonNode detail) {
        Map<String, JsonNode> byInput = new HashMap<>();
        for (JsonNode item : detail.path("data").path("items")) {
            byInput.put(item.path("sampleSnapshot").path("input").asText(), item);
        }
        return byInput;
    }
}
