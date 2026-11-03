package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.EndUserFeedbackEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.EndUserFeedbackMapper;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.FindingMapper;

/**
 * R2-2 端用户反馈验收：
 * 1. 预签 token 免登录提交，四类 score 归一后落 et_eval_result（既有监控可用）；
 * 2. token 配额 / 过期 / 吊销都按服务端判定拒绝；
 * 3. token 读不到正文：只能看剩余次数，访问 trace 接口仍需登录；
 * 4. correction 收录数据集走样本契约校验，非 correction 反馈拒绝收录；
 * 5. 负反馈达到阈值产生 Finding（只跨阈值那一次触发）；
 * 6. 同一 token 突发提交被限流。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-feedback;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.feedback.finding-threshold=2",
        "emonlens.feedback.rate-limit-per-minute=20",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class EndUserFeedbackFlowTest extends AbstractIntegrationTest {

    @Resource
    private EndUserFeedbackMapper feedbackMapper;
    @Resource
    private EvalResultMapper evalResultMapper;
    @Resource
    private FindingMapper findingMapper;
    @Resource
    private DatasetItemMapper datasetItemMapper;

    // ---------- 辅助 ----------

    private String traceWithInput(String traceId, String agentKey, String input) throws Exception {
        Map<String, Object> root = span(agentKey, traceId, "root", null, "agent", "invoke_agent",
                null, null, 1, 5);
        root.put("input", input);
        root.put("output", "这是助手回答");
        post("/api/v1/ingest/events", List.of(root));
        await(() -> {
            try {
                return readTree(get("/api/v1/traces/" + traceId)).path("data").path("summary");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, node -> "COMPLETED".equals(node.path("status").asText()), ASYNC_TIMEOUT_MS);
        return traceId;
    }

    private JsonNode issueToken(String traceId, Integer maxUses, Integer expiresInMinutes)
            throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        if (maxUses != null) {
            body.put("maxUses", maxUses);
        }
        if (expiresInMinutes != null) {
            body.put("expiresInMinutes", expiresInMinutes);
        }
        return readTree(postApi("/api/v1/traces/" + traceId + "/feedback-tokens", body));
    }

    /** 公开入口：不带任何 Authorization（证明免登录）。 */
    private JsonNode publicSubmit(String token, Map<String, Object> body) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/feedback/" + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse();
        return readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private List<EvalResultEntity> feedbackResults(String traceId) {
        return evalResultMapper.selectList(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getTraceId, traceId)
                .eq(EvalResultEntity::getEvaluatorKey, "end-user-feedback"));
    }

    // ---------- 1. 四类 score 与落库 ----------

    @Test
    void submitTypedFeedbackLandsInEvalResult() throws Exception {
        String traceId = traceWithInput("fb-numeric", "fb-agent", "我的订单到哪了");
        String token = issueToken(traceId, 5, 60).path("data").path("token").asText();
        assertThat(token).startsWith("fb_");

        JsonNode numeric = publicSubmit(token, Map.of("kind", "NUMERIC", "valueNum", 9.0));
        assertThat(numeric.path("code").asInt()).as("numeric: %s", numeric).isZero();
        assertThat(numeric.path("data").path("label").asText()).isEqualTo("PASS");
        assertThat(numeric.path("data").path("remainingUses").asInt()).isEqualTo(4);

        JsonNode bool = publicSubmit(token, Map.of("kind", "BOOLEAN", "valueText", "down"));
        assertThat(bool.path("data").path("label").asText()).isEqualTo("FAIL");

        JsonNode categorical = publicSubmit(token, Map.of("kind", "CATEGORICAL", "valueText", "差"));
        assertThat(categorical.path("data").path("label").asText()).isEqualTo("FAIL");

        JsonNode correction = publicSubmit(token, Map.of("kind", "CORRECTION",
                "correction", "您的订单预计明天 10 点前送达"));
        assertThat(correction.path("data").path("label").asText()).isEqualTo("FAIL");

        List<EvalResultEntity> results = feedbackResults(traceId);
        assertThat(results).hasSize(4);
        assertThat(results).extracting(EvalResultEntity::getLabel)
                .containsExactlyInAnyOrder("PASS", "FAIL", "FAIL", "FAIL");
        assertThat(results).allSatisfy(r -> {
            assertThat(r.getReason()).contains("end-user feedback");
            // 纠正正文不能进 reason（正文受内容权限约束）
            assertThat(r.getReason()).doesNotContain("明天 10 点");
        });

        JsonNode list = readTree(get("/api/v1/traces/" + traceId + "/feedback"));
        assertThat(list.path("data")).hasSize(4);
        assertThat(list.path("data").get(0).path("hasCorrection").asBoolean()).isTrue();
    }

    // ---------- 2. 配额 / 过期 / 吊销 ----------

    @Test
    void quotaExpiryAndRevocationAreEnforced() throws Exception {
        String traceId = traceWithInput("fb-quota", "fb-agent", "问题");
        JsonNode issued = issueToken(traceId, 1, 60);
        String token = issued.path("data").path("token").asText();

        assertThat(publicSubmit(token, Map.of("kind", "NUMERIC", "valueNum", 8.0))
                .path("code").asInt()).isZero();
        JsonNode exhausted = publicSubmit(token, Map.of("kind", "NUMERIC", "valueNum", 8.0));
        assertThat(exhausted.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.CONFLICT.getCode());

        // 过期：expiresInMinutes=0 立即失效
        String expired = issueToken(traceId, 5, 0).path("data").path("token").asText();
        assertThat(publicSubmit(expired, Map.of("kind", "NUMERIC", "valueNum", 8.0))
                .path("code").asInt()).isEqualTo(io.emonlens.common.ErrorCode.FORBIDDEN.getCode());

        // 吊销：吊销后该 token 立刻不可用，未吊销的同 trace token 不受影响
        JsonNode revocable = issueToken(traceId, 5, 60);
        String revocableToken = revocable.path("data").path("token").asText();
        String revocableId = revocable.path("data").path("tokenId").asText();
        JsonNode revoke = readTree(postApi("/api/v1/traces/" + traceId
                + "/feedback-tokens/" + revocableId + "/revoke", Map.of()));
        assertThat(revoke.path("data").path("valid").asBoolean()).isFalse();
        assertThat(publicSubmit(revocableToken, Map.of("kind", "NUMERIC", "valueNum", 8.0))
                .path("code").asInt()).isEqualTo(io.emonlens.common.ErrorCode.FORBIDDEN.getCode());

        String usable = issueToken(traceId, 5, 60).path("data").path("token").asText();
        assertThat(publicSubmit(usable, Map.of("kind", "NUMERIC", "valueNum", 8.0))
                .path("code").asInt()).isZero();
    }

    // ---------- 3. token 读不到正文 ----------

    @Test
    void tokenCannotReadTraceContent() throws Exception {
        String traceId = traceWithInput("fb-isolation", "fb-agent", "机密问题");
        String token = issueToken(traceId, 5, 60).path("data").path("token").asText();

        JsonNode status = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/feedback/" + token))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(status.path("data").path("valid").asBoolean()).isTrue();
        assertThat(status.path("data").has("traceId")).isFalse();
        assertThat(status.toString()).doesNotContain("机密问题");

        // 用 token 当登录凭据访问 trace：控制面契约是 HTTP 200 + 业务码 40101（未授权），
        // 也就是「token 换不来任何控制面读取能力」
        MockHttpServletResponse denied = mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/traces/" + traceId)
                                .header("Authorization", "Bearer " + token))
                .andReturn().getResponse();
        JsonNode deniedBody = readTree(denied.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(deniedBody.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.UNAUTHORIZED.getCode());
        assertThat(deniedBody.path("data").isMissingNode()).isTrue();

        // 公开路径不接受未知 token
        JsonNode unknown = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.get("/api/v1/feedback/fb_deadbeef"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(unknown.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.NOT_FOUND.getCode());
    }

    // ---------- 4. correction 收录数据集 ----------

    @Test
    void correctionPromotesToDatasetWithContractValidation() throws Exception {
        String traceId = traceWithInput("fb-promote", "fb-agent", "发票怎么开");
        String token = issueToken(traceId, 5, 60).path("data").path("token").asText();
        JsonNode numeric = publicSubmit(token, Map.of("kind", "NUMERIC", "valueNum", 3.0));
        JsonNode correction = publicSubmit(token, Map.of("kind", "CORRECTION",
                "correction", "在「财务中心 → 发票管理」提交开票申请"));
        String numericId = numeric.path("data").path("feedbackId").asText();
        String correctionId = correction.path("data").path("feedbackId").asText();

        String datasetId = readTree(postApi("/api/v1/datasets",
                Map.of("datasetKey", "fb-dataset"))).path("data").path("id").asText();

        // 非 correction 反馈不能收录
        JsonNode rejected = readTree(postApi("/api/v1/traces/" + traceId + "/feedback/"
                + numericId + "/dataset", Map.of("datasetId", datasetId)));
        assertThat(rejected.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.BAD_REQUEST.getCode());

        // correction 正常收录，期望输出=用户纠正内容
        JsonNode promoted = readTree(postApi("/api/v1/traces/" + traceId + "/feedback/"
                + correctionId + "/dataset", Map.of("datasetId", datasetId)));
        assertThat(promoted.path("code").asInt()).as("promote: %s", promoted).isZero();
        String itemId = promoted.path("data").path("itemId").asText();
        DatasetItemEntity item = datasetItemMapper.selectById(itemId);
        assertThat(item).isNotNull();
        assertThat(item.getExpectedOutput()).isEqualTo("在「财务中心 → 发票管理」提交开票申请");
        assertThat(item.getInput()).isEqualTo("发票怎么开");
        assertThat(item.getSourceType()).isEqualTo("USER_FEEDBACK");
        assertThat(item.getSourceRef()).startsWith("feedback:");
        assertThat(item.getSchemaVersion()).isEqualTo(1);

        // 超长纠正内容被截断到 2000 字符后仍走契约校验（校验的是实际入库内容）
        assertThat(item.getExpectedOutput().length()).isLessThanOrEqualTo(2000);
    }

    // ---------- 5. 负反馈阈值 → Finding ----------

    @Test
    void negativeFeedbackThresholdRaisesFinding() throws Exception {
        String traceId = traceWithInput("fb-finding", "fb-finding-agent", "退款要多久");
        String token = issueToken(traceId, 5, 60).path("data").path("token").asText();

        publicSubmit(token, Map.of("kind", "BOOLEAN", "valueText", "down"));
        assertThat(findingMapper.selectCount(Wrappers.<FindingEntity>lambdaQuery()
                .eq(FindingEntity::getTraceId, traceId)
                .eq(FindingEntity::getType, "END_USER_FEEDBACK"))).isZero();

        publicSubmit(token, Map.of("kind", "BOOLEAN", "valueText", "down"));
        List<FindingEntity> findings = findingMapper.selectList(Wrappers.<FindingEntity>lambdaQuery()
                .eq(FindingEntity::getTraceId, traceId)
                .eq(FindingEntity::getType, "END_USER_FEEDBACK"));
        assertThat(findings).as("阈值 2 条负面反馈应产生 Finding").hasSize(1);
        assertThat(findings.get(0).getMessage()).contains("负面端用户反馈");

        // 已跨过阈值后继续差评不再重复开 Finding
        publicSubmit(token, Map.of("kind", "BOOLEAN", "valueText", "down"));
        assertThat(findingMapper.selectCount(Wrappers.<FindingEntity>lambdaQuery()
                .eq(FindingEntity::getTraceId, traceId)
                .eq(FindingEntity::getType, "END_USER_FEEDBACK"))).isEqualTo(1);
    }

    // ---------- 6. 限流 ----------

    @Test
    void burstSubmissionsAreRateLimited() throws Exception {
        String traceId = traceWithInput("fb-rate", "fb-agent", "限流");
        String token = issueToken(traceId, 40, 60).path("data").path("token").asText();
        for (int i = 0; i < 20; i++) {
            assertThat(publicSubmit(token, Map.of("kind", "NUMERIC", "valueNum", 7.0))
                    .path("code").asInt()).as("第 %d 次提交应通过", i + 1).isZero();
        }
        JsonNode limited = publicSubmit(token, Map.of("kind", "NUMERIC", "valueNum", 7.0));
        assertThat(limited.path("code").asInt())
                .isEqualTo(io.emonlens.common.ErrorCode.RATE_LIMITED.getCode());
        assertThat(feedbackMapper.selectCount(Wrappers.<EndUserFeedbackEntity>lambdaQuery()
                .eq(EndUserFeedbackEntity::getTraceId, traceId))).isEqualTo(20);
    }
}
