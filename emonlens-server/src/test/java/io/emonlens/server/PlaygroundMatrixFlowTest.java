package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.engine.eval.LlmUnavailableException;

/**
 * R1-2 Playground 矩阵 + R1-3 prompt 版本绑定的端到端验收：
 * 1. 成本预估 → 硬确认 → 网格执行 → 网格 CSV；
 * 2. 单格失败不中断整批（矩阵 PARTIAL，其余格照常有结论）；
 * 3. Diff 给出确定性变化与语义偏好，偏好与评估结论冲突时标 TRADEOFF；
 * 4. 矩阵列升格为 dataset run 后绑定 prompt 版本，production 指针移动使旧 Gate 失效；
 * 5. CI 基线标签在同一数据集内唯一。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-playground-matrix;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.playground.matrix.poll-interval-ms=100",
        "emonlens.playground.matrix.concurrency=2",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class PlaygroundMatrixFlowTest extends AbstractIntegrationTest {

    @Resource
    private FakeLlm fakeLlm;

    @TestConfiguration
    static class FakeLlmConfig {
        @Bean
        @Primary
        FakeLlm fakeLlm() {
            return new FakeLlm();
        }
    }

    /** 生成侧按模型区分输出（便于构造 PASS/FAIL 与 diff 场景），judge 侧按脚本出结论。 */
    static class FakeLlm implements LlmClient {
        static final String MODEL_GOOD = "model-good";
        static final String MODEL_BAD = "model-bad";

        final ConcurrentLinkedQueue<String> pairwiseOutputs = new ConcurrentLinkedQueue<>();
        final java.util.Set<String> failingModels = ConcurrentHashMap.newKeySet();
        volatile int pairwiseCalls = 0;

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model,
                                       double temperature) {
            if (failingModels.contains(model)) {
                throw new LlmUnavailableException("model down: " + model);
            }
            if (systemPrompt != null && systemPrompt.contains("pairwise evaluator")) {
                pairwiseCalls++;
                String scripted = pairwiseOutputs.poll();
                return new ChatReply(scripted == null
                        ? "{\"winner\":\"TIE\",\"confidence\":0.5,\"reason\":\"same\"}" : scripted, 9, 7);
            }
            return new ChatReply(MODEL_GOOD.equals(model) ? "OK" : "BETTER", 10, 5);
        }
    }

    // ---------- 测试辅助 ----------

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).isZero();
        return response.path("data").path("id").asText();
    }

    private void addItem(String datasetId, String input, String expectedOutput) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", input, "expectedOutput", expectedOutput)));
        assertThat(response.path("code").asInt()).isZero();
    }

    private String exactMatchEvaluator(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", key, "type", "EXPRESSION",
                "spec", Map.of("type", "EXPRESSION",
                        "passWhen", "reference_match.exact(candidate_output, expected_output)"))));
        assertThat(response.path("code").asInt()).as("evaluator: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    private JsonNode createMatrix(String datasetId, List<Map<String, Object>> columns,
                                  List<String> evaluatorIds, String key) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("name", "matrix " + key);
        body.put("datasetId", datasetId);
        body.put("columns", columns);
        body.put("evaluatorIds", evaluatorIds);
        body.put("confirmEstimate", true);
        return readTree(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/playground/matrices")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", key)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private Map<String, Object> column(String key, String model, String promptName,
                                       String promptLabel) {
        Map<String, Object> column = new java.util.HashMap<>();
        column.put("key", key);
        column.put("label", key + "-" + model);
        column.put("model", model);
        column.put("prompt", "{{input}}");
        if (promptName != null) {
            column.put("promptName", promptName);
            column.put("promptLabel", promptLabel);
        }
        return column;
    }

    private JsonNode awaitMatrix(String matrixId) throws Exception {
        return await(() -> {
            try {
                return readTree(get("/api/v1/playground/matrices/" + matrixId)).path("data");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, node -> {
            String status = node.path("status").asText();
            return "COMPLETED".equals(status) || "PARTIAL".equals(status) || "FAILED".equals(status);
        }, ASYNC_TIMEOUT_MS);
    }

    private JsonNode awaitRun(String datasetId, String runId) throws Exception {
        return await(() -> {
            try {
                return readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId))
                        .path("data").path("run");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, node -> {
            String status = node.path("status").asText();
            return "COMPLETED".equals(status) || "PARTIAL_FAILED".equals(status)
                    || "FAILED".equals(status) || "INTERRUPTED".equals(status)
                    || "CANCELLED".equals(status);
        }, ASYNC_TIMEOUT_MS);
    }

    // ---------- 1. 预估 → 受理 → 网格 → CSV ----------

    @Test
    void matrixEstimatesRequiresConfirmationRunsGridAndExportsCsv() throws Exception {
        String datasetId = createDataset("matrix-grid");
        addItem(datasetId, "q-one", "OK");
        addItem(datasetId, "q-two", "OK");
        postApi("/api/v1/model-prices", Map.of("model", FakeLlm.MODEL_GOOD,
                "inputPerMillion", 1.0, "outputPerMillion", 2.0));
        String evaluatorId = exactMatchEvaluator("matrix-exact");

        Map<String, Object> estimateBody = Map.of("datasetId", datasetId,
                "columns", List.of(column("c1", FakeLlm.MODEL_GOOD, null, null),
                        column("c2", FakeLlm.MODEL_BAD, null, null)),
                "evaluatorIds", List.of(evaluatorId));
        JsonNode estimate = readTree(postApi("/api/v1/playground/matrices/estimate", estimateBody));
        assertThat(estimate.path("code").asInt()).isZero();
        assertThat(estimate.path("data").path("cellCount").asInt()).isEqualTo(4);
        assertThat(estimate.path("data").path("estimatedCalls").asInt()).isEqualTo(8);
        // 预估成本可计价（已配置 model-good 价目；model-bad 无价目 → priced=false）
        assertThat(estimate.path("data").path("byColumn").get(0).path("priced").asBoolean()).isTrue();
        assertThat(estimate.path("data").path("byColumn").get(0).path("cost").asDouble())
                .isGreaterThan(0.0);
        assertThat(estimate.path("data").path("priced").asBoolean()).isFalse();
        assertThat(estimate.path("data").path("unpricedModels").toString())
                .contains(FakeLlm.MODEL_BAD);

        // 未确认成本 → 拒绝受理（避免误点就跑出账单）
        Map<String, Object> unconfirmed = new java.util.HashMap<>(estimateBody);
        unconfirmed.put("confirmEstimate", false);
        JsonNode rejected = readTree(postApi("/api/v1/playground/matrices", unconfirmed));
        assertThat(rejected.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());

        JsonNode created = createMatrix(datasetId,
                List.of(column("c1", FakeLlm.MODEL_GOOD, null, null),
                        column("c2", FakeLlm.MODEL_BAD, null, null)),
                List.of(evaluatorId), "matrix-grid-1");
        assertThat(created.path("code").asInt()).as("create: %s", created).isZero();
        String matrixId = created.path("data").path("id").asText();

        JsonNode matrix = awaitMatrix(matrixId);
        assertThat(matrix.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(matrix.path("completedCells").asInt()).isEqualTo(4);
        assertThat(matrix.path("failedCells").asInt()).isZero();
        assertThat(matrix.path("cells")).hasSize(4);
        JsonNode firstCell = matrix.path("cells").get(0);
        assertThat(firstCell.path("output").asText()).isNotBlank();
        assertThat(firstCell.path("aggregateLabel").asText()).isNotBlank();
        // model-good 输出 OK 命中期望；model-bad 输出 BETTER 不命中
        long passes = 0;
        for (JsonNode cell : matrix.path("cells")) {
            if ("PASS".equals(cell.path("aggregateLabel").asText())) {
                passes++;
            }
        }
        assertThat(passes).isEqualTo(2);

        String csv = get("/api/v1/playground/matrices/" + matrixId + "/export.csv");
        assertThat(csv).contains("item_id").contains("c1-model-good / 输出")
                .contains("c2-model-bad / 评估结论");
        assertThat(csv.split("\n")).hasSize(3); // header + 2 samples

        // 幂等：同 key 同请求返回同一矩阵
        JsonNode again = createMatrix(datasetId,
                List.of(column("c1", FakeLlm.MODEL_GOOD, null, null),
                        column("c2", FakeLlm.MODEL_BAD, null, null)),
                List.of(evaluatorId), "matrix-grid-1");
        assertThat(again.path("data").path("id").asText()).isEqualTo(matrixId);
    }

    @Test
    void singleCellFailureKeepsTheRestOfTheGrid() throws Exception {
        String datasetId = createDataset("matrix-partial");
        addItem(datasetId, "q-one", "OK");
        String evaluatorId = exactMatchEvaluator("matrix-partial-exact");
        fakeLlm.failingModels.add("model-broken");
        try {
            JsonNode created = createMatrix(datasetId,
                    List.of(column("c1", FakeLlm.MODEL_GOOD, null, null),
                            column("c2", "model-broken", null, null)),
                    List.of(evaluatorId), "matrix-partial-1");
            String matrixId = created.path("data").path("id").asText();
            JsonNode matrix = awaitMatrix(matrixId);
            assertThat(matrix.path("status").asText()).isEqualTo("PARTIAL");
            assertThat(matrix.path("completedCells").asInt()).isEqualTo(2);
            assertThat(matrix.path("failedCells").asInt()).isEqualTo(1);
            boolean healthyCellOk = false;
            boolean brokenCellError = false;
            for (JsonNode cell : matrix.path("cells")) {
                if ("c1".equals(cell.path("columnKey").asText())) {
                    healthyCellOk = "OK".equals(cell.path("status").asText());
                } else {
                    brokenCellError = "ERROR".equals(cell.path("status").asText())
                            && cell.path("error").asText().contains("model down");
                }
            }
            assertThat(healthyCellOk).as("正常列不受失败列影响").isTrue();
            assertThat(brokenCellError).as("失败列留下可读错误").isTrue();
        } finally {
            fakeLlm.failingModels.remove("model-broken");
        }
    }

    // ---------- 2. Diff ----------

    @Test
    void diffMarksIdenticalOutputsAsTieWithoutCallingJudge() throws Exception {
        String datasetId = createDataset("matrix-diff-same");
        addItem(datasetId, "q-one", "OK");
        String evaluatorId = exactMatchEvaluator("matrix-same-exact");
        JsonNode created = createMatrix(datasetId,
                List.of(column("c1", FakeLlm.MODEL_GOOD, null, null),
                        column("c2", FakeLlm.MODEL_GOOD, null, null)),
                List.of(evaluatorId), "matrix-same-1");
        String matrixId = created.path("data").path("id").asText();
        awaitMatrix(matrixId);
        int callsBefore = fakeLlm.pairwiseCalls;

        JsonNode diff = readTree(postApi("/api/v1/playground/matrices/" + matrixId + "/diff",
                Map.of("baseColumn", "c1", "targetColumn", "c2")));
        assertThat(diff.path("code").asInt()).as("diff: %s", diff).isZero();
        assertThat(diff.path("data").path("sameCount").asInt()).isEqualTo(1);
        assertThat(diff.path("data").path("changedCount").asInt()).isZero();
        assertThat(diff.path("data").path("items").get(0).path("deterministic").asText())
                .isEqualTo("SAME");
        assertThat(diff.path("data").path("items").get(0).path("preference").asText())
                .isEqualTo("TIE");
        // 输出完全一致时不该花 judge 的钱
        assertThat(fakeLlm.pairwiseCalls).isEqualTo(callsBefore);
    }

    @Test
    void diffUsesPairwisePreferenceAndFlagsTradeoffOnScoreConflict() throws Exception {
        String datasetId = createDataset("matrix-diff-pref");
        addItem(datasetId, "q-one", "OK");
        String evaluatorId = exactMatchEvaluator("matrix-pref-exact");
        JsonNode created = createMatrix(datasetId,
                List.of(column("c1", FakeLlm.MODEL_BAD, null, null),
                        column("c2", FakeLlm.MODEL_GOOD, null, null)),
                List.of(evaluatorId), "matrix-pref-1");
        String matrixId = created.path("data").path("id").asText();
        awaitMatrix(matrixId);

        // 两个方向都选 B（目标列）→ IMPROVEMENT，且评估结论同时变好
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"B\",\"confidence\":0.9,\"reason\":\"x\"}");
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"x\"}");
        JsonNode improved = readTree(postApi("/api/v1/playground/matrices/" + matrixId + "/diff",
                Map.of("baseColumn", "c1", "targetColumn", "c2")));
        assertThat(improved.path("data").path("changedCount").asInt()).isEqualTo(1);
        assertThat(improved.path("data").path("improvedCount").asInt()).isEqualTo(1);
        JsonNode item = improved.path("data").path("items").get(0);
        assertThat(item.path("preference").asText()).isEqualTo("IMPROVEMENT");
        assertThat(item.path("scoreDelta").path("scoreImproved").asBoolean()).isTrue();
        assertThat(item.path("reasonCode").asText()).isNotBlank();
        // 逐样本重新可查（结论可追溯）
        String diffId = improved.path("data").path("id").asText();
        JsonNode detail = readTree(get("/api/v1/playground/matrices/" + matrixId
                + "/diffs/" + diffId));
        assertThat(detail.path("data").path("items")).hasSize(1);

        // 反向比较：语义偏好判目标列更好，但评估结论从 PASS 变 FAIL → TRADEOFF
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"B\",\"confidence\":0.9,\"reason\":\"x\"}");
        fakeLlm.pairwiseOutputs.add("{\"winner\":\"A\",\"confidence\":0.9,\"reason\":\"x\"}");
        JsonNode tradeoff = readTree(postApi("/api/v1/playground/matrices/" + matrixId + "/diff",
                Map.of("baseColumn", "c2", "targetColumn", "c1")));
        JsonNode tradeoffItem = tradeoff.path("data").path("items").get(0);
        assertThat(tradeoffItem.path("preference").asText()).isEqualTo("TRADEOFF");
        assertThat(tradeoffItem.path("reasonCode").asText()).isEqualTo("PREFERENCE_UP_SCORE_DOWN");
        assertThat(tradeoff.path("data").path("tradeoffCount").asInt()).isEqualTo(1);
    }

    @Test
    void diffRejectsUnknownColumnAndUnfinishedMatrix() throws Exception {
        String datasetId = createDataset("matrix-diff-guard");
        addItem(datasetId, "q-one", "OK");
        String evaluatorId = exactMatchEvaluator("matrix-guard-exact");
        String matrixId = createMatrix(datasetId,
                List.of(column("c1", FakeLlm.MODEL_GOOD, null, null),
                        column("c2", FakeLlm.MODEL_BAD, null, null)),
                List.of(evaluatorId), "matrix-guard-1").path("data").path("id").asText();
        awaitMatrix(matrixId);
        JsonNode unknown = readTree(postApi("/api/v1/playground/matrices/" + matrixId + "/diff",
                Map.of("baseColumn", "c1", "targetColumn", "nope")));
        assertThat(unknown.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.NOT_FOUND.getCode());
        JsonNode same = readTree(postApi("/api/v1/playground/matrices/" + matrixId + "/diff",
                Map.of("baseColumn", "c1", "targetColumn", "c1")));
        assertThat(same.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());
    }

    // ---------- 3. 升格 + prompt 绑定 + Gate 失效 ----------

    @Test
    void promoteBindsPromptVersionAndProductionMoveInvalidatesTheGate() throws Exception {
        String datasetId = createDataset("matrix-promote");
        addItem(datasetId, "q-one", "OK");
        String evaluatorId = exactMatchEvaluator("matrix-promote-exact");

        JsonNode v1 = readTree(postApi("/api/v1/prompts", Map.of("name", "promote-prompt",
                "prompt", "{{input}}", "setProduction", true)));
        assertThat(v1.path("data").path("version").asInt()).isEqualTo(1);
        JsonNode v2 = readTree(postApi("/api/v1/prompts", Map.of("name", "promote-prompt",
                "prompt", "{{input}}", "setProduction", true)));
        assertThat(v2.path("data").path("version").asInt()).isEqualTo(2);
        // label 语义收紧：production 同名唯一，新版本声明后旧版本自动摘除
        JsonNode versions = readTree(get("/api/v1/prompts/promote-prompt/versions"));
        assertThat(versions.path("data").get(0).path("labels").toString()).contains("production");
        assertThat(versions.path("data").get(1).path("labels").toString()).doesNotContain("production");

        String matrixId = createMatrix(datasetId,
                List.of(column("c1", FakeLlm.MODEL_GOOD, "promote-prompt", "production")),
                List.of(evaluatorId), "matrix-promote-1").path("data").path("id").asText();
        awaitMatrix(matrixId);

        JsonNode promoted = readTree(postApi("/api/v1/playground/matrices/" + matrixId
                + "/columns/c1/promote", Map.of("runLabel", "baseline")));
        assertThat(promoted.path("code").asInt()).as("promote: %s", promoted).isZero();
        String runId = promoted.path("data").path("runId").asText();
        assertThat(promoted.path("data").path("promptVersion").asInt()).isEqualTo(2);

        JsonNode run = awaitRun(datasetId, runId);
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("promptName").asText()).isEqualTo("promote-prompt");
        assertThat(run.path("promptVersion").asInt()).isEqualTo(2);
        assertThat(run.path("promptLabel").asText()).isEqualTo("production");
        assertThat(run.path("promptHash").asText()).isNotBlank();
        assertThat(run.path("runLabel").asText()).isEqualTo("baseline");

        JsonNode gate = readTree(postApi("/api/v1/quality-gates", Map.of("runId", runId)));
        assertThat(gate.path("data").path("decision").asText())
                .as("gate: %s", gate.path("data").path("checks")).isEqualTo("PASS");
        String gateId = gate.path("data").path("id").asText();
        JsonNode usable = readTree(get("/api/v1/quality-gates/" + gateId + "/verification"));
        assertThat(usable.path("data").path("usable").asBoolean()).isTrue();

        // 移动 production 指针 → 旧 Gate 自动失效，新 Gate 直接 BLOCKED
        JsonNode v3 = readTree(postApi("/api/v1/prompts", Map.of("name", "promote-prompt",
                "prompt", "{{input}} 已改版", "setProduction", true)));
        assertThat(v3.path("data").path("version").asInt()).isEqualTo(3);
        JsonNode expired = readTree(get("/api/v1/quality-gates/" + gateId + "/verification"));
        assertThat(expired.path("data").path("usable").asBoolean()).isFalse();
        assertThat(expired.path("data").path("reasons").toString())
                .contains("PROMPT_PRODUCTION_MOVED");
        JsonNode reEvaluate = readTree(postApi("/api/v1/quality-gates", Map.of("runId", runId)));
        assertThat(reEvaluate.path("data").path("decision").asText()).isEqualTo("BLOCKED");
        assertThat(reEvaluate.path("data").path("checks").toString()).contains("PROMPT_BINDING");

        // 从 prompt 版本能回答“被哪些 run 评过、Gate 结论是什么”
        JsonNode promptRuns = readTree(get("/api/v1/prompts/promote-prompt/runs"));
        assertThat(promptRuns.path("data")).hasSize(1);
        assertThat(promptRuns.path("data").get(0).path("runId").asText()).isEqualTo(runId);
        assertThat(promptRuns.path("data").get(0).path("promptVersion").asInt()).isEqualTo(2);
        assertThat(promptRuns.path("data").get(0).path("gateDecision").asText()).isEqualTo("BLOCKED");
    }

    @Test
    void runLabelStaysUniquePerDataset() throws Exception {
        String datasetId = createDataset("matrix-run-label");
        addItem(datasetId, "q-one", "OK");
        String evaluatorId = exactMatchEvaluator("matrix-label-exact");
        String firstMatrix = createMatrix(datasetId,
                List.of(column("c1", FakeLlm.MODEL_GOOD, null, null)),
                List.of(evaluatorId), "matrix-label-1").path("data").path("id").asText();
        String secondMatrix = createMatrix(datasetId,
                List.of(column("c1", FakeLlm.MODEL_BAD, null, null)),
                List.of(evaluatorId), "matrix-label-2").path("data").path("id").asText();
        awaitMatrix(firstMatrix);
        awaitMatrix(secondMatrix);

        String runA = readTree(postApi("/api/v1/playground/matrices/" + firstMatrix
                + "/columns/c1/promote", Map.of("runLabel", "baseline"))).path("data").path("runId").asText();
        // 同一工作空间同时只允许一条活动 run：等第一条收口再升格第二条
        awaitRun(datasetId, runA);
        String runB = readTree(postApi("/api/v1/playground/matrices/" + secondMatrix
                + "/columns/c1/promote", Map.of("runLabel", "baseline"))).path("data").path("runId").asText();
        assertThat(runA).isNotEqualTo(runB);
        awaitRun(datasetId, runB);

        JsonNode labelled = readTree(get("/api/v1/datasets/" + datasetId + "/runs?label=baseline"));
        assertThat(labelled.path("data")).hasSize(1);
        assertThat(labelled.path("data").get(0).path("id").asText()).isEqualTo(runB);

        // 手动摘除标签后不再命中
        JsonNode cleared = readTree(postApi("/api/v1/datasets/" + datasetId + "/runs/" + runB + "/label",
                Map.of("runLabel", "")));
        assertThat(cleared.path("data").path("runLabel").isNull()
                || cleared.path("data").path("runLabel").asText().isEmpty()).isTrue();
        JsonNode empty = readTree(get("/api/v1/datasets/" + datasetId + "/runs?label=baseline"));
        assertThat(empty.path("data")).isEmpty();
    }

    @Test
    void promoteRequiresEvaluators() throws Exception {
        String datasetId = createDataset("matrix-promote-noeval");
        addItem(datasetId, "q-one", "OK");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("datasetId", datasetId);
        body.put("columns", List.of(column("c1", FakeLlm.MODEL_GOOD, null, null)));
        body.put("confirmEstimate", true);
        JsonNode created = readTree(mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/v1/playground/matrices")
                        .header("Authorization", adminAuth())
                        .header("Idempotency-Key", "matrix-noeval-1")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        String matrixId = created.path("data").path("id").asText();
        awaitMatrix(matrixId);
        JsonNode promoted = readTree(postApi("/api/v1/playground/matrices/" + matrixId
                + "/columns/c1/promote", Map.of()));
        assertThat(promoted.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());
        assertThat(promoted.path("message").asText()).contains("评估器");
    }
}
