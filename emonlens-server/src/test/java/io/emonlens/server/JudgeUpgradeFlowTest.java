package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.JudgeReviewEntity;
import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.JudgeReviewMapper;

/**
 * R3-3 Judge 升级验收：
 * 1. CoT：模型先写证据再给 JSON，解析仍然成功（容忍"先说明后 JSON"）；
 * 2. few-shot：盲审一致的校准结论注入 prompt（可开关）；
 * 3. 多 judge：MAJORITY 平票 → UNKNOWN（不硬判）；CONSERVATIVE 全一致才给结论；
 *    judge 原文留痕进任务账本，judge 调用数按模型数计。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-judge-upgrade;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.eval.judge-few-shot-enabled=true",
        "emonlens.eval.judge-few-shot-max=3",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class JudgeUpgradeFlowTest extends AbstractIntegrationTest {

    private static final String WS = "ws-default";

    @Resource
    private DatasetRunMapper datasetRunMapper;
    @Resource
    private JudgeReviewMapper judgeReviewMapper;
    @Resource
    private io.emonlens.server.storage.mapper.DatasetRunItemMapper datasetRunItemMapper;
    @Resource
    private io.emonlens.server.storage.mapper.EvalResultMapper evalResultMapper;
    @Resource
    private org.springframework.context.ApplicationContext applicationContext;

    @TestConfiguration
    static class FakeJudgeConfig {
        @Bean
        @Primary
        JudgeFakeLlm judgeFakeLlm() {
            return new JudgeFakeLlm();
        }
    }

    /** 按模型名返回不同结论，并保留收到的 prompt 供 few-shot 断言。 */
    static class JudgeFakeLlm implements LlmClient {
        final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model,
                                       double temperature) {
            prompts.add(userPrompt);
            double score = "judge-strict".equals(model) ? 3.0 : 9.0;
            String content = "证据要点：\n- 候选回复确认了退款进度\n- 未承诺具体到账时间\n\n"
                    + "{\"score\":" + score + ",\"confidence\":0.8,\"reason\":\"evidence based\"}";
            return new ChatReply(content, 100, 20);
        }
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).as("dataset: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    private void ingestTrace(String traceId, String output) throws Exception {
        Map<String, Object> root = span("judge-agent", traceId, "root", null, "agent", "invoke_agent",
                null, null, 1, 5);
        root.put("input", "退款进度");
        root.put("output", output);
        post("/api/v1/ingest/events", List.of(root));
        await(() -> {
            try {
                return readTree(get("/api/v1/traces/" + traceId)).path("data").path("summary");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, node -> "COMPLETED".equals(node.path("status").asText()), ASYNC_TIMEOUT_MS);
    }

    private String createJudgeEvaluator(String key, Map<String, Object> extraSpec) throws Exception {
        Map<String, Object> spec = new java.util.LinkedHashMap<>();
        spec.put("type", "LLM_JUDGE");
        spec.put("judgeMode", "HOLISTIC");
        spec.put("rubric", "回复需要说明退款进度，并避免承诺具体到账时间");
        spec.put("criteria", List.of());
        spec.put("minScore", 6);
        spec.putAll(extraSpec);
        JsonNode response = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", key, "type", "LLM_JUDGE", "spec", spec)));
        assertThat(response.path("code").asInt()).as("evaluator: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    private String runTrace(String datasetId, String evaluatorId, String key) throws Exception {
        JsonNode created = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/datasets/" + datasetId + "/runs/async")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "evaluatorIds", List.of(evaluatorId), "mode", "TRACE"))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(created.path("code").asInt()).as("run: %s", created).isZero();
        String runId = created.path("data").path("runId").asText();
        await(() -> datasetRunMapper.selectById(runId),
                run -> run != null && DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus()),
                ASYNC_TIMEOUT_MS);
        return runId;
    }

    private JsonNode itemOf(String datasetId, String runId) throws Exception {
        JsonNode detail = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId));
        return detail.path("data").path("run");
    }

    @Test
    void cotPreambleIsParsedAndMajorityTieYieldsUnknown() throws Exception {
        String datasetId = createDataset("judge-cot");
        ingestTrace("judge-cot-t1", "退款已受理，预计 3 个工作日内到账，请留意短信。");
        readTree(postApi("/api/v1/datasets/" + datasetId + "/items", Map.of("traceId", "judge-cot-t1")));

        // 1) CoT：模型先写证据要点再给 JSON → 仍能解析出分数
        String single = createJudgeEvaluator("judge-cot-single", Map.of("judgeModel", "judge-lenient"));
        String run1 = runTrace(datasetId, single, "judge-run-1");
        JsonNode run = itemOf(datasetId, run1);
        assertThat(run.path("passCount").asInt()).isEqualTo(1);
        assertThat(run.path("failCount").asInt()).isZero();

        // 2) 多 judge MAJORITY：9 分 vs 3 分 = 平票 → UNKNOWN（不硬判成 FAIL）
        String multi = createJudgeEvaluator("judge-cot-multi", Map.of(
                "judgeModels", List.of("judge-lenient", "judge-strict"),
                "judgeAggregation", "MAJORITY"));
        String run2 = runTrace(datasetId, multi, "judge-run-2");
        JsonNode multiRun = itemOf(datasetId, run2);
        assertThat(multiRun.path("unknownCount").asInt())
                .as("平票必须 UNKNOWN: %s", multiRun).isEqualTo(1);
        assertThat(multiRun.path("passCount").asInt()).isZero();
        assertThat(multiRun.path("failCount").asInt()).isZero();

        String details = datasetRunItemMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<io.emonlens.server.storage.entity.DatasetRunItemEntity>lambdaQuery()
                                .eq(io.emonlens.server.storage.entity.DatasetRunItemEntity::getRunId, run2))
                .get(0).getResultDetailsJson();
        assertThat(details).contains("JUDGE_DISAGREEMENT");
        assertThat(details).contains("judge-strict").contains("judge-lenient");
        assertThat(details).contains("by MAJORITY");
    }

    @Test
    void fewShotCalibrationIsInjectedAndConservativeRequiresUnanimity() throws Exception {
        String datasetId = createDataset("judge-fewshot");
        ingestTrace("judge-fs-t1", "退款已受理，预计 3 个工作日内到账。");
        readTree(postApi("/api/v1/datasets/" + datasetId + "/items", Map.of("traceId", "judge-fs-t1")));
        String evaluatorKey = "judge-fs";
        String evaluatorId = createJudgeEvaluator(evaluatorKey, Map.of("judgeModel", "judge-lenient"));

        // 盲审一致样本：2 PASS + 1 FAIL
        String[] labels = {"PASS", "PASS", "FAIL"};
        for (int i = 0; i < labels.length; i++) {
            JudgeReviewEntity review = new JudgeReviewEntity();
            review.setWorkspaceId(WS);
            review.setRunId("judge-fs-run");
            review.setItemId("judge-fs-item-" + i);
            review.setEvaluatorKey(evaluatorKey);
            review.setEvaluatorVersion(1);
            review.setJudgeLabel(labels[i]);
            review.setHumanLabel(labels[i]);
            review.setReviewerSlot("PRIMARY");
            review.setProtocolVersion("judge-calibration/1");
            review.setNote("盲审一致");
            review.setReviewedBy("qa");
            review.setCreatedAt(LocalDateTime.now().minusDays(i));
            review.setUpdatedAt(LocalDateTime.now());
            judgeReviewMapper.insert(review);
        }

        JudgeFakeLlm fake = (JudgeFakeLlm) applicationContext.getBean(LlmClient.class);
        fake.prompts.clear();
        String runId = runTrace(datasetId, evaluatorId, "judge-fs-run-1");
        assertThat(fake.prompts).isNotEmpty();
        assertThat(fake.prompts.get(0))
                .contains("Calibration from blind review")
                .contains("2 PASS / 1 FAIL")
                .contains("agreed label PASS");

        // CONSERVATIVE：两个 judge 结论不一致 → UNKNOWN
        String conservative = createJudgeEvaluator("judge-conservative", Map.of(
                "judgeModels", List.of("judge-lenient", "judge-strict"),
                "judgeAggregation", "CONSERVATIVE"));
        String run2 = runTrace(datasetId, conservative, "judge-fs-run-2");
        JsonNode conservativeRun = itemOf(datasetId, run2);
        assertThat(conservativeRun.path("unknownCount").asInt()).isEqualTo(1);
        assertThat(itemOf(datasetId, runId).path("passCount").asInt()).isEqualTo(1);
    }

    /**
     * MEAN = 均分 + 同向保护：
     *   ① 没有严格多数同向 → UNKNOWN（1 PASS + 1 FAIL 不硬判）
     *   ② 多数同向且均分达到 minScore → 给结论（2 PASS + 1 FAIL，均分 7.0 >= 6 → PASS）
     *   ③ 多数同向但均分未达 minScore → 仍 UNKNOWN，reasonCode 说明是均值不过线
     * 这三种情形把 MEAN 与 MAJORITY（只看票数）和"纯均分硬切"区分开了。
     */
    @Test
    void meanAggregationNeedsBothMajorityAndMeanAboveMinScore() throws Exception {
        String datasetId = createDataset("judge-mean");
        ingestTrace("judge-mean-t1", "refund accepted, arrives in 3 business days");
        readTree(postApi("/api/v1/datasets/" + datasetId + "/items", Map.of("traceId", "judge-mean-t1")));

        // ① 1 PASS(9) + 1 FAIL(3)：没有严格多数 → UNKNOWN
        String split = createJudgeEvaluator("judge-mean-split", Map.of(
                "judgeModels", List.of("judge-lenient", "judge-strict"),
                "judgeAggregation", "MEAN"));
        JsonNode splitRun = itemOf(datasetId, runTrace(datasetId, split, "judge-mean-1"));
        assertThat(splitRun.path("unknownCount").asInt())
                .as("split verdict must stay UNKNOWN: %s", splitRun).isEqualTo(1);
        assertThat(splitRun.path("passCount").asInt()).isZero();
        assertThat(splitRun.path("failCount").asInt()).isZero();

        // ② 2 PASS(9,9) + 1 FAIL(3)：多数同向，均分 7.0 >= 6 → PASS
        String majority = createJudgeEvaluator("judge-mean-majority", Map.of(
                "judgeModels", List.of("judge-lenient", "judge-lenient-b", "judge-strict"),
                "judgeAggregation", "MEAN"));
        JsonNode majorityRun = itemOf(datasetId, runTrace(datasetId, majority, "judge-mean-2"));
        assertThat(majorityRun.path("passCount").asInt())
                .as("majority + mean>=minScore must PASS: %s", majorityRun).isEqualTo(1);
        assertThat(majorityRun.path("unknownCount").asInt()).isZero();

        // ③ 同样的票型，但 minScore 提到 8：均分 7.0 < 8 → UNKNOWN（不做分数硬切）
        String belowMean = createJudgeEvaluator("judge-mean-below", Map.of(
                "judgeModels", List.of("judge-lenient", "judge-lenient-b", "judge-strict"),
                "judgeAggregation", "MEAN",
                "minScore", 8));
        String belowRunId = runTrace(datasetId, belowMean, "judge-mean-3");
        JsonNode belowRun = itemOf(datasetId, belowRunId);
        assertThat(belowRun.path("unknownCount").asInt())
                .as("majority but mean<minScore must be UNKNOWN: %s", belowRun).isEqualTo(1);
        assertThat(belowRun.path("passCount").asInt()).isZero();
        String details = datasetRunItemMapper.selectList(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<io.emonlens.server.storage.entity.DatasetRunItemEntity>lambdaQuery()
                                .eq(io.emonlens.server.storage.entity.DatasetRunItemEntity::getRunId, belowRunId))
                .get(0).getResultDetailsJson();
        assertThat(details).contains("JUDGE_MEAN_BELOW_MIN_SCORE");
        // reason 必须自带人话解释：界面只展示 reason（reasonCode 不落库），
        // 没有这句用户只看到 UNKNOWN 完全不知道为什么。
        assertThat(details).contains("未达阈值");

        // 原因码还要能落库并读回来：它曾经是 @TableField(exist = false)（只在内存传递），
        // 读回来就丢，事后无法按"为什么判成这样"筛选复盘。
        // 注意：数据集跑批只写 run item 明细，不写 et_eval_result，所以这里直接验列与映射本身。
        io.emonlens.server.storage.entity.EvalResultEntity probe =
                new io.emonlens.server.storage.entity.EvalResultEntity();
        probe.setEvaluatorId("judge-mean-probe");
        probe.setEvaluatorKey("judge-mean-probe");
        probe.setEvaluatorVersion(1);
        probe.setWorkspaceId(WS);
        probe.setAgentKey("judge-agent");
        probe.setTraceId("judge-mean-probe-trace");
        probe.setAttempt(1);
        probe.setLabel(io.emonlens.server.storage.entity.EvaluatorEntity.LABEL_UNKNOWN);
        probe.setReasonCode("JUDGE_MEAN_BELOW_MIN_SCORE");
        probe.setCreatedAt(LocalDateTime.now());
        evalResultMapper.insert(probe);
        assertThat(evalResultMapper.selectById(probe.getId()).getReasonCode())
                .as("reasonCode 必须真的落库").isEqualTo("JUDGE_MEAN_BELOW_MIN_SCORE");
    }
}