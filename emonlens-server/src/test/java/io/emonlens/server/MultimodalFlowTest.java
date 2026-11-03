package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

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

import io.emonlens.server.engine.eval.AttachmentPolicy;
import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

/**
 * R3-4 多模态验收：
 * 1. 附件校验：只收 IMAGE、mime 白名单、url 必须 http(s)、base64 ≤ 256KB；
 * 2. 附件随样本快照冻结，judge prompt 里出现附件证据，外链图片交给 vision 端点；
 * 3. 导出/无权限读取时 base64 脱敏成 sha256 占位（与正文 TRACE_CONTENT 口径一致）；
 * 4. TRAJECTORY 评估器不受影响（不消费附件）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-multimodal;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class MultimodalFlowTest extends AbstractIntegrationTest {

    private static final String IMAGE_URL = "https://cdn.example.com/receipt.png";

    @Resource
    private DatasetRunMapper datasetRunMapper;
    @Resource
    private DatasetRunItemMapper datasetRunItemMapper;
    @Resource
    private AttachmentPolicy attachmentPolicy;
    @Resource
    private org.springframework.context.ApplicationContext applicationContext;

    @TestConfiguration
    static class FakeVisionConfig {
        @Bean
        @Primary
        VisionFakeLlm visionFakeLlm() {
            return new VisionFakeLlm();
        }
    }

    static class VisionFakeLlm implements LlmClient {
        final List<List<String>> imageCalls = new CopyOnWriteArrayList<>();
        final List<String> prompts = new CopyOnWriteArrayList<>();

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model,
                                       double temperature) {
            prompts.add(userPrompt);
            return new ChatReply("{\"score\":8,\"confidence\":0.9,\"reason\":\"ok\"}", 90, 15);
        }

        @Override
        public ChatReply chatWithImages(String systemPrompt, String userPrompt, List<String> imageUrls,
                                        String model, double temperature) {
            imageCalls.add(imageUrls == null ? List.of() : imageUrls);
            prompts.add(userPrompt);
            return new ChatReply("{\"score\":8,\"confidence\":0.9,\"reason\":\"image ok\"}", 120, 18);
        }
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).as("dataset: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    private void ingestTrace(String traceId, String output) throws Exception {
        Map<String, Object> root = span("vision-agent", traceId, "root", null, "agent", "invoke_agent",
                null, null, 1, 5);
        root.put("input", "看看这张小票");
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

    @Test
    void attachmentValidationRejectsBadInput() throws Exception {
        String datasetId = createDataset("mm-validate");
        // 非图片 mime
        JsonNode badMime = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", Map.of(
                "input", "文本样本",
                "attachments", List.of(Map.of("kind", "IMAGE", "mime", "application/pdf",
                        "url", IMAGE_URL)))));
        assertThat(badMime.path("code").asInt()).as("non-image mime must be rejected").isNotZero();
        // url 不是 http(s)
        JsonNode badScheme = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", Map.of(
                "input", "文本样本",
                "attachments", List.of(Map.of("kind", "IMAGE", "mime", "image/png",
                        "url", "file:///etc/passwd")))));
        assertThat(badScheme.path("code").asInt()).as("file:// must be rejected").isNotZero();
        // base64 超限
        String huge = "A".repeat(AttachmentPolicy.MAX_BASE64_BYTES + 1);
        JsonNode tooBig = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", Map.of(
                "input", "文本样本",
                "attachments", List.of(Map.of("kind", "IMAGE", "mime", "image/png",
                        "base64", huge)))));
        assertThat(tooBig.path("code").asInt()).as("oversized base64 must be rejected").isNotZero();
        // 合法附件
        JsonNode ok = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", Map.of(
                "input", "看看这张小票",
                "attachments", List.of(Map.of("kind", "IMAGE", "mime", "image/png",
                        "url", IMAGE_URL, "alt", "小票照片")))));
        assertThat(ok.path("code").asInt()).as("item: %s", ok).isZero();
    }

    @Test
    void judgeSeesAttachmentAndVisionModelReceivesImageUrl() throws Exception {
        String datasetId = createDataset("mm-judge");
        ingestTrace("mm-trace-1", "小票金额 128 元，已核对。");
        JsonNode item = readTree(postApi("/api/v1/datasets/" + datasetId + "/items", Map.of(
                "traceId", "mm-trace-1",
                "attachments", List.of(Map.of("kind", "IMAGE", "mime", "image/png",
                        "url", IMAGE_URL, "alt", "小票照片")))));
        assertThat(item.path("code").asInt()).isZero();

        JsonNode evaluator = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", "mm-judge", "type", "LLM_JUDGE",
                "spec", Map.of("type", "LLM_JUDGE", "judgeMode", "HOLISTIC",
                        "rubric", "核对小票金额与回复是否一致", "judgeModel", "vision-model",
                        "minScore", 6))));
        assertThat(evaluator.path("code").asInt()).isZero();

        JsonNode created = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/datasets/" + datasetId + "/runs/async")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "mm-run-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "evaluatorIds", List.of(evaluator.path("data").path("id").asText()),
                                        "mode", "TRACE"))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(created.path("code").asInt()).as("run: %s", created).isZero();
        String runId = created.path("data").path("runId").asText();
        await(() -> datasetRunMapper.selectById(runId),
                run -> run != null && DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus()),
                ASYNC_TIMEOUT_MS);

        DatasetRunEntity run = datasetRunMapper.selectById(runId);
        assertThat(run.getPassCount()).isEqualTo(1);

        // 快照里冻结了附件；判定时把图片交给 vision 端点，并在 prompt 里说明证据
        DatasetRunItemEntity runItem = datasetRunItemMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<DatasetRunItemEntity>lambdaQuery()
                        .eq(DatasetRunItemEntity::getRunId, runId)).get(0);
        assertThat(runItem.getSampleSnapshotJson()).contains("attachments").contains(IMAGE_URL);

        VisionFakeLlm fake = (VisionFakeLlm) applicationContext.getBean(LlmClient.class);
        assertThat(fake.imageCalls).isNotEmpty();
        assertThat(fake.imageCalls.get(0)).containsExactly(IMAGE_URL);
        assertThat(fake.prompts.get(0))
                .contains("Attachments (images; consider them as evidence)")
                .contains("alt=\"小票照片\"");
    }

    @Test
    void exportRedactsBase64Payload() {
        String base64 = "iVBORw0KGgoAAAANSUhEUg==";
        String json = "[{\"kind\":\"IMAGE\",\"mime\":\"image/png\",\"base64\":\"" + base64 + "\"}]";
        assertThat(attachmentPolicy.promptBlock(json)).contains("inline=base64");
        List<Map<String, Object>> redacted = attachmentPolicy.redacted(json);
        assertThat(redacted).hasSize(1);
        assertThat(redacted.get(0)).doesNotContainKey("base64");
        assertThat(redacted.get(0).get("base64_sha256")).isNotNull();
        // 有权限时导出原文
        assertThat(attachmentPolicy.parse(json).get(0)).containsEntry("base64", base64);
        assertThat(attachmentPolicy.imageUrls(json)).isEmpty();
    }
}
