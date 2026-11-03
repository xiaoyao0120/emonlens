package io.emonlens.server;

import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.EvalTaskService;
import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.engine.eval.SdkEvalResultService;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.EvalTaskMapper;

/**
 * SDK 评测器（TYPE_SDK）端到端验收：
 * 载荷下发与内容权限、结果回写（幂等/冲突/终态机）、回写后样本重聚合与计数刷新、
 * Gate SDK_EVALUATORS 检查项、执行中拒绝回写、宽限清理。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-sdk-eval;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.eval.sdk-result-grace-hours=1",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class SdkEvalFlowTest extends AbstractIntegrationTest {

    @Resource
    private FakeLlm fakeLlm;
    @Resource
    private DatasetRunMapper datasetRunMapper;
    @Resource
    private DatasetRunItemMapper datasetRunItemMapper;
    @Resource
    private EvalTaskMapper evalTaskMapper;
    @Resource
    private EvalTaskService evalTaskService;
    @Resource
    private SdkEvalResultService sdkEvalResultService;

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    static class FakeLlm implements LlmClient {
        final java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        volatile boolean blocking = false;

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model,
                                       double temperature) {
            if (blocking) {
                try {
                    block.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ChatReply("ECHO:" + userPrompt, 7, 13);
        }

        void unblock() {
            block.countDown();
        }
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private String createEvaluator(String key, String type, Map<String, Object> spec) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators",
                Map.of("evaluatorKey", key, "type", type, "spec", spec)));
        assertThat(response.path("code").asInt())
                .as("create evaluator %s: %s", key, response).isZero();
        return response.path("data").path("id").asText();
    }

    private void addItem(String datasetId, String input, String expectedOutput) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("input", input);
        body.put("expectedOutput", expectedOutput);
        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", body));
        assertThat(response.path("code").asInt()).isZero();
    }

    private String openToken(String scopes) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/open-tokens",
                Map.of("name", "sdk-eval-" + System.nanoTime(), "scopes", scopes)));
        assertThat(response.path("code").asInt())
                .as("issue token with scopes=%s: %s", scopes, response).isZero();
        return response.path("data").path("token").asText();
    }

    private JsonNode openPost(String url, String token, String idempotencyKey, Object body)
            throws Exception {
        return openPost(url, token, idempotencyKey, body, 200);
    }

    private JsonNode openPost(String url, String token, String idempotencyKey, Object body,
                              int expectedStatus) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.post(url)
                        .header("X-Api-Token", token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("POST %s -> %s", url,
                response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(expectedStatus);
        return readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private JsonNode openGet(String url, String token) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(MockMvcRequestBuilders.get(url)
                        .header("X-Api-Token", token))
                .andReturn().getResponse();
        assertThat(response.getStatus()).as("GET %s -> %s", url,
                response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(200);
        return readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private JsonNode submitRun(String datasetId, List<String> evaluatorIds, String token, String key)
            throws Exception {
        // 创建返回 202 Accepted（异步受理），重复提交返回 200
        JsonNode created = openPost("/api/v1/open/eval-runs", token, key,
                Map.of("datasetId", datasetId, "evaluatorIds", evaluatorIds, "mode", "REPLAY"), 202);
        assertThat(created.path("code").asInt()).as("submit run: %s", created).isZero();
        return created;
    }

    private DatasetRunEntity awaitTerminal(String runId) throws Exception {
        return await(() -> datasetRunMapper.selectById(runId),
                run -> run != null && run.getStatus() != null
                        && !"PENDING".equals(run.getStatus()) && !"RUNNING".equals(run.getStatus()),
                ASYNC_TIMEOUT_MS);
    }

    private List<DatasetRunItemEntity> itemsOf(String runId) {
        return datasetRunItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<DatasetRunItemEntity>lambdaQuery()
                        .eq(DatasetRunItemEntity::getRunId, runId));
    }

    @Test
    void pushFlowPayloadIdempotencyConflictAndGate() throws Exception {
        String datasetId = createDataset("sdk-push-flow");
        addItem(datasetId, "q-one", "one");
        addItem(datasetId, "q-two", "two");
        String evaluatorId = createEvaluator("sdk-flow-check", "SDK", Map.of(
                "type", "SDK",
                "requiredFields", List.of("input", "candidate_output", "expected_output"),
                "scorerRef", "scorers.py:flow_check"));
        String token = openToken("eval:run,eval:read,eval:content");

        String runId = submitRun(datasetId, List.of(evaluatorId), token, "sdk-flow-1")
                .path("data").path("runId").asText();
        DatasetRunEntity run = awaitTerminal(runId);
        assertThat(run.getStatus()).as("全 SDK run 服务端无执行负担，应正常收口")
                .isEqualTo(DatasetRunEntity.STATUS_COMPLETED);

        // 收口时样本按 UNKNOWN(AWAITING_SDK_RESULTS) 占位，计数守恒
        JsonNode runView = openGet("/api/v1/open/eval-runs/" + runId, token);
        assertThat(runView.path("data").path("run").path("unknownCount").asInt()).isEqualTo(2);

        // 载荷下发：eval:content token 可见正文，requiredFields 回显契约
        JsonNode tasks = openGet("/api/v1/open/eval-runs/" + runId + "/sdk-tasks", token);
        assertThat(tasks.path("data").path("contentRestricted").asBoolean()).isFalse();
        assertThat(tasks.path("data").path("openSdkTasks").asInt()).isEqualTo(2);
        JsonNode firstTask = tasks.path("data").path("records").get(0);
        assertThat(firstTask.path("evaluatorKey").asText()).isEqualTo("sdk-flow-check");
        assertThat(firstTask.path("requiredFields").toString()).contains("candidate_output");
        JsonNode sample = firstTask.path("sample");
        assertThat(sample.path("input").asText()).isNotBlank();
        assertThat(sample.path("candidateOutput").asText()).startsWith("ECHO:");
        assertThat(sample.path("expectedOutput").asText()).isNotBlank();

        // 回写：一条 PASS 一条 FAIL；item 重聚合 + run 计数刷新
        String itemIdOne = tasks.path("data").path("records").get(0).path("itemId").asText();
        String itemIdTwo = tasks.path("data").path("records").get(1).path("itemId").asText();
        JsonNode pushed = openPost("/api/v1/open/eval-runs/" + runId + "/results", token,
                "sdk-push-1", Map.of("results", List.of(
                        Map.of("itemId", itemIdOne, "evaluatorKey", "sdk-flow-check",
                                "verdict", "PASS", "score", 0.95, "reasonCode", "MATCHED"),
                        Map.of("itemId", itemIdTwo, "evaluatorKey", "sdk-flow-check",
                                "verdict", "FAIL", "reasonCode", "MISMATCH"))));
        assertThat(pushed.path("code").asInt()).as("push: %s", pushed).isZero();
        JsonNode outcomes = pushed.path("data").path("results");
        assertThat(outcomes.get(0).path("outcome").asText()).isEqualTo("APPLIED");
        assertThat(outcomes.get(1).path("outcome").asText()).isEqualTo("APPLIED");
        assertThat(pushed.path("data").path("openSdkTasks").asInt()).isZero();
        assertThat(pushed.path("data").path("passCount").asInt()).isEqualTo(1);
        assertThat(pushed.path("data").path("failCount").asInt()).isEqualTo(1);

        // 任务账本：SDK 任务带 origin=SDK 的证据；PASS/FAIL 都算 SUCCEEDED，区分在 verdict
        List<io.emonlens.server.storage.entity.EvalTaskEntity> sdkTasks = evalTaskService
                .loadForRun(runId).values().stream()
                .filter(t -> "SDK".equals(t.getEvaluatorType()))
                .collect(java.util.stream.Collectors.toList());
        assertThat(sdkTasks).hasSize(2);
        assertThat(sdkTasks).extracting(
                io.emonlens.server.storage.entity.EvalTaskEntity::getVerdict)
                .containsExactlyInAnyOrder("PASS", "FAIL");
        assertThat(sdkTasks.get(0).getResultJson()).contains("\"origin\":\"SDK\"");

        // 幂等重复与终态冲突
        JsonNode duplicate = openPost("/api/v1/open/eval-runs/" + runId + "/results", token,
                "sdk-push-2", Map.of("results", List.of(
                        Map.of("itemId", itemIdOne, "evaluatorKey", "sdk-flow-check",
                                "verdict", "PASS", "score", 0.95))));
        assertThat(duplicate.path("data").path("results").get(0).path("outcome").asText())
                .isEqualTo("DUPLICATE");
        JsonNode conflict = openPost("/api/v1/open/eval-runs/" + runId + "/results", token,
                "sdk-push-3", Map.of("results", List.of(
                        Map.of("itemId", itemIdTwo, "evaluatorKey", "sdk-flow-check",
                                "verdict", "PASS"))));
        assertThat(conflict.path("data").path("results").get(0).path("outcome").asText())
                .isEqualTo("CONFLICT");

        // Gate：有一条 FAIL 样本 → 决策 FAIL（而不是 BLOCKED，说明 SDK 结果已收齐）
        JsonNode gate = readTree(postApi("/api/v1/quality-gates", Map.of("runId", runId)));
        assertThat(gate.path("code").asInt()).isZero();
        assertThat(gate.path("data").path("decision").asText())
                .as("SDK 结果收齐后不应再 BLOCKED: %s", gate).isEqualTo("FAIL");
        assertThat(gate.path("data").path("checks").toString()).contains("SDK_EVALUATORS");
    }

    @Test
    void gateBlockedUntilSdkResultsArrive() throws Exception {
        String datasetId = createDataset("sdk-gate-block");
        addItem(datasetId, "gate-q", "gate-a");
        String evaluatorId = createEvaluator("sdk-gate-check", "SDK",
                Map.of("type", "SDK", "requiredFields", List.of("input")));
        String token = openToken("eval:run,eval:read,eval:content");

        String runId = submitRun(datasetId, List.of(evaluatorId), token, "sdk-gate-1")
                .path("data").path("runId").asText();
        awaitTerminal(runId);

        JsonNode blocked = readTree(postApi("/api/v1/quality-gates", Map.of("runId", runId)));
        assertThat(blocked.path("data").path("decision").asText())
                .as("结果未回写时 Gate 必须 BLOCKED").isEqualTo("BLOCKED");
        assertThat(blocked.path("data").path("checks").toString()).contains("SDK_EVALUATORS");

        JsonNode tasks = openGet("/api/v1/open/eval-runs/" + runId + "/sdk-tasks", token);
        String itemId = tasks.path("data").path("records").get(0).path("itemId").asText();
        JsonNode pushed = openPost("/api/v1/open/eval-runs/" + runId + "/results", token,
                "sdk-gate-push-1", Map.of("results", List.of(
                        Map.of("itemId", itemId, "evaluatorKey", "sdk-gate-check",
                                "verdict", "PASS", "score", 1.0))));
        assertThat(pushed.path("data").path("results").get(0).path("outcome").asText())
                .as("回写应成功: %s", pushed).isEqualTo("APPLIED");

        JsonNode passed = readTree(postApi("/api/v1/quality-gates", Map.of("runId", runId)));
        assertThat(passed.path("data").path("decision").asText())
                .as("结果回写后 Gate 应 PASS: %s", passed).isEqualTo("PASS");
    }

    @Test
    void pushRejectedWhileRunActive() throws Exception {
        String datasetId = createDataset("sdk-push-active");
        addItem(datasetId, "active-q", "active-a");
        String evaluatorId = createEvaluator("sdk-active-check", "SDK",
                Map.of("type", "SDK", "requiredFields", List.of("input")));
        String token = openToken("eval:run,eval:read,eval:content");

        fakeLlm.blocking = true;
        try {
            String runId = submitRun(datasetId, List.of(evaluatorId), token, "sdk-active-1")
                    .path("data").path("runId").asText();
            // REPLAY 生成被阻塞，run 仍活动；回写必须被拒绝
            await(() -> datasetRunMapper.selectById(runId),
                    run -> run != null && "RUNNING".equals(run.getStatus()), ASYNC_TIMEOUT_MS);
            JsonNode rejected = openPost("/api/v1/open/eval-runs/" + runId + "/results", token,
                    "sdk-active-push-1", Map.of("results", List.of(
                            Map.of("itemId", "whatever", "evaluatorKey", "sdk-active-check",
                                    "verdict", "PASS"))));
            assertThat(rejected.path("code").asInt())
                    .isEqualTo(io.emonlens.common.ErrorCode.CONFLICT.getCode());
            fakeLlm.unblock();
            awaitTerminal(runId);
        } finally {
            fakeLlm.blocking = false;
            fakeLlm.unblock();
        }
    }

    @Test
    void contentRestrictedTokenGetsMaskedPayloadAndPushNeedsRunScope() throws Exception {
        String datasetId = createDataset("sdk-mask");
        addItem(datasetId, "mask-q", "mask-a");
        String evaluatorId = createEvaluator("sdk-mask-check", "SDK",
                Map.of("type", "SDK", "requiredFields", List.of("input", "candidate_output")));
        String readToken = openToken("eval:read");
        String writeToken = openToken("eval:run,eval:read,eval:content");

        String runId = submitRun(datasetId, List.of(evaluatorId), writeToken, "sdk-mask-1")
                .path("data").path("runId").asText();
        awaitTerminal(runId);

        // 只有 eval:read：载荷脱敏，正文不下发
        JsonNode tasks = openGet("/api/v1/open/eval-runs/" + runId + "/sdk-tasks", readToken);
        assertThat(tasks.path("data").path("contentRestricted").asBoolean()).isTrue();
        JsonNode sample = tasks.path("data").path("records").get(0).path("sample");
        assertThat(sample.has("input")).isFalse();
        assertThat(sample.path("caseHash").asText()).isNotBlank();

        // 只有 eval:read：回写被拒（缺 eval:run scope，业务码 40301）
        String itemId = tasks.path("data").path("records").get(0).path("itemId").asText();
        JsonNode forbidden = openPost("/api/v1/open/eval-runs/" + runId + "/results", readToken,
                "sdk-mask-push-1", Map.of("results", List.of(
                        Map.of("itemId", itemId, "evaluatorKey", "sdk-mask-check",
                                "verdict", "PASS"))));
        assertThat(forbidden.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.FORBIDDEN.getCode());

        // content 权限 token：正文可见
        JsonNode full = openGet("/api/v1/open/eval-runs/" + runId + "/sdk-tasks", writeToken);
        assertThat(full.path("data").path("records").get(0).path("sample").path("input").asText())
                .isEqualTo("mask-q");
    }

    @Test
    void mixedRunWithServerAndSdkEvaluatorsReaggregatesOnPush() throws Exception {
        String datasetId = createDataset("sdk-mixed");
        // FakeLlm 回显输入：expectedOutput 对齐 ECHO 输出，让表达式评估器先 PASS
        addItem(datasetId, "mixed-q", "ECHO:mixed-q");
        String exactId = createEvaluator("mixed-exact", "EXPRESSION", Map.of(
                "type", "EXPRESSION",
                "passWhen", "reference_match.exact(candidate_output, expected_output)"));
        String sdkId = createEvaluator("mixed-sdk", "SDK",
                Map.of("type", "SDK", "requiredFields", List.of("input")));
        String token = openToken("eval:run,eval:read,eval:content");

        String runId = submitRun(datasetId, List.of(exactId, sdkId), token, "sdk-mixed-1")
                .path("data").path("runId").asText();
        awaitTerminal(runId);

        // 表达式 PASS + SDK 未回写 → 样本级 UNKNOWN，计数守恒
        JsonNode runView = openGet("/api/v1/open/eval-runs/" + runId, token);
        assertThat(runView.path("data").path("run").path("unknownCount").asInt()).isEqualTo(1);
        assertThat(runView.path("data").path("run").path("passCount").asInt()).isZero();

        // SDK 回写 FAIL → 重聚合后样本翻成 FAIL（FAIL 优先于 PASS）
        JsonNode tasks = openGet("/api/v1/open/eval-runs/" + runId + "/sdk-tasks", token);
        String itemId = tasks.path("data").path("records").get(0).path("itemId").asText();
        JsonNode pushed = openPost("/api/v1/open/eval-runs/" + runId + "/results", token,
                "sdk-mixed-push-1", Map.of("results", List.of(
                        Map.of("itemId", itemId, "evaluatorKey", "mixed-sdk",
                                "verdict", "FAIL", "reasonCode", "BUSINESS_RULE"))));
        assertThat(pushed.path("data").path("results").get(0).path("outcome").asText())
                .as("回写应成功: %s", pushed).isEqualTo("APPLIED");
        JsonNode refreshed = openGet("/api/v1/open/eval-runs/" + runId, token);
        assertThat(refreshed.path("data").path("run").path("failCount").asInt()).isEqualTo(1);
        assertThat(refreshed.path("data").path("run").path("unknownCount").asInt()).isZero();

        DatasetRunItemEntity item = itemsOf(runId).get(0);
        assertThat(item.getLabel()).isEqualTo("FAIL");
        assertThat(item.getEvaluatorResultsJson()).contains("mixed-sdk").contains("BUSINESS_RULE");
    }

    @Test
    void sdkEvaluatorSpecValidationRejectsMissingOrUnknownFields() throws Exception {
        JsonNode missing = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", "sdk-bad-missing", "type", "SDK",
                "spec", Map.of("type", "SDK"))));
        assertThat(missing.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());
        assertThat(missing.path("message").asText()).contains("requiredFields");

        JsonNode unknownField = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", "sdk-bad-field", "type", "SDK",
                "spec", Map.of("type", "SDK", "requiredFields", List.of("secret_field")))));
        assertThat(unknownField.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());
    }

    @Test
    void graceSweeperSettlesExpiredSdkTasksAndReaggregates() throws Exception {
        String datasetId = createDataset("sdk-grace");
        addItem(datasetId, "grace-q", "grace-a");
        String evaluatorId = createEvaluator("sdk-grace-check", "SDK",
                Map.of("type", "SDK", "requiredFields", List.of("input")));
        String token = openToken("eval:run,eval:read,eval:content");

        String runId = submitRun(datasetId, List.of(evaluatorId), token, "sdk-grace-1")
                .path("data").path("runId").asText();
        awaitTerminal(runId);

        // 把任务 created_at 回拨 2 小时，触发 1 小时宽限清理
        List<io.emonlens.server.storage.entity.EvalTaskEntity> sdkTasks = evalTaskService
                .openSdkTasks(runId);
        assertThat(sdkTasks).hasSize(1);
        io.emonlens.server.storage.entity.EvalTaskEntity task = sdkTasks.get(0);
        task.setCreatedAt(java.time.LocalDateTime.now().minusHours(2));
        evalTaskMapper.updateById(task);

        int settled = sdkEvalResultService.recoverExpiredSdkTasks();
        assertThat(settled).isEqualTo(1);

        io.emonlens.server.storage.entity.EvalTaskEntity after = evalTaskMapper.selectById(task.getId());
        assertThat(after.getExecutionStatus()).isEqualTo("SKIPPED");
        assertThat(after.getReasonCode()).isEqualTo("SDK_RESULT_NOT_RECEIVED");

        // 样本重聚合为 UNKNOWN（SDK 结果未到），Gate 不得放行
        DatasetRunItemEntity item = itemsOf(runId).get(0);
        assertThat(item.getLabel()).isEqualTo("UNKNOWN");
        assertThat(item.getEvaluatorResultsJson()).contains("SDK_RESULT_NOT_RECEIVED");
        JsonNode gate = readTree(postApi("/api/v1/quality-gates", Map.of("runId", runId)));
        assertThat(gate.path("data").path("decision").asText()).isEqualTo("BLOCKED");
    }
}
