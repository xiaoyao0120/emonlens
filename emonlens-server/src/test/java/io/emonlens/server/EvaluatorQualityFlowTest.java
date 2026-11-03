package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.EvalCacheEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.JudgeReviewEntity;
import io.emonlens.server.storage.entity.ModelPriceEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.EvalCacheMapper;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.FindingMapper;
import io.emonlens.server.storage.mapper.JudgeReviewMapper;
import io.emonlens.server.storage.mapper.ModelPriceMapper;

/**
 * R2-3 评估器质量看板验收：
 * 1. 报告能直接回答「哪个评估器最不可信」（leastTrusted + LOW_TRUST 判定）；
 * 2. 一致率、UNKNOWN/未执行率、成本（缺价目时 priced=false）口径正确；
 * 3. 「哪个数据集在腐烂」：陈旧样本占比 + revision 年龄 → ROTTING；
 * 4. 告警接入 Alerts：扫描写入 Finding，且 24h 内同一对象不重复；
 * 5. 缓存汇总按工作空间统计并说明原因。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-quality;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class EvaluatorQualityFlowTest extends AbstractIntegrationTest {

    private static final String WS = "ws-default";

    @Resource
    private EvalResultMapper evalResultMapper;
    @Resource
    private JudgeReviewMapper judgeReviewMapper;
    @Resource
    private DatasetMapper datasetMapper;
    @Resource
    private DatasetItemMapper datasetItemMapper;
    @Resource
    private ModelPriceMapper modelPriceMapper;
    @Resource
    private EvalCacheMapper evalCacheMapper;
    @Resource
    private FindingMapper findingMapper;

    private String createEvaluator(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", key, "type", "EXPRESSION",
                "spec", Map.of("type", "EXPRESSION", "passWhen", "error_count >= 0"))));
        assertThat(response.path("code").asInt()).as("evaluator: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    private void insertResult(String evaluatorId, String evaluatorKey, String label,
                              String judgeModel, Integer promptTokens, Integer completionTokens,
                              String traceId, int attempt) {
        EvalResultEntity result = new EvalResultEntity();
        result.setEvaluatorId(evaluatorId);
        result.setEvaluatorKey(evaluatorKey);
        result.setEvaluatorVersion(1);
        result.setWorkspaceId(WS);
        result.setAgentKey("quality-agent");
        result.setAgentVersion("v1");
        result.setTraceId(traceId);
        result.setAttempt(attempt);
        result.setLabel(label);
        result.setScore(BigDecimal.valueOf("PASS".equals(label) ? 9 : 3));
        result.setJudgeModel(judgeModel);
        result.setPromptTokens(promptTokens);
        result.setCompletionTokens(completionTokens);
        result.setReason("seeded for quality board");
        result.setCreatedAt(LocalDateTime.now());
        evalResultMapper.insert(result);
    }

    private void insertReview(String evaluatorKey, int index, String judgeLabel, String humanLabel) {
        JudgeReviewEntity review = new JudgeReviewEntity();
        review.setWorkspaceId(WS);
        review.setRunId("quality-run");
        review.setItemId("quality-item-" + evaluatorKey + "-" + index);
        review.setEvaluatorKey(evaluatorKey);
        review.setEvaluatorVersion(1);
        review.setJudgeLabel(judgeLabel);
        review.setHumanLabel(humanLabel);
        review.setReviewerSlot("PRIMARY");
        review.setProtocolVersion("judge-calibration/1");
        review.setReviewedBy("qa-user");
        review.setCreatedAt(LocalDateTime.now());
        review.setUpdatedAt(LocalDateTime.now());
        judgeReviewMapper.insert(review);
    }

    @Test
    void reportAnswersLeastTrustedEvaluatorAndRottingDataset() throws Exception {
        String goodId = createEvaluator("quality-good");
        String badId = createEvaluator("quality-bad");
        modelPriceMapper.insert(price("q-judge", "1.0", "2.0"));

        // 好评估器：8 PASS / 2 FAIL；坏评估器：4 UNKNOWN / 2 ERROR / 2 PASS / 2 FAIL（20% 未执行）
        for (int i = 0; i < 10; i++) {
            insertResult(goodId, "quality-good", i < 8 ? "PASS" : "FAIL",
                    "q-judge", 1000, 200, "quality-good-t" + i, 1);
        }
        String[] badLabels = {"PASS", "PASS", "FAIL", "FAIL", "UNKNOWN", "UNKNOWN",
                "UNKNOWN", "UNKNOWN", "ERROR", "EVAL_UNAVAILABLE"};
        for (int i = 0; i < badLabels.length; i++) {
            insertResult(badId, "quality-bad", badLabels[i], "q-judge", 1000, 200,
                    "quality-bad-t" + i, 1);
        }
        // 盲审：好评估器 9/10 一致，坏评估器 5/10 一致
        for (int i = 0; i < 10; i++) {
            insertReview("quality-good", i, "PASS", i == 0 ? "FAIL" : "PASS");
            insertReview("quality-bad", i, "PASS", i < 5 ? "PASS" : "FAIL");
        }
        // 数据集：一个新鲜的 + 一个腐烂的（样本 200 天前、没有 revision）
        String freshId = readTree(postApi("/api/v1/datasets",
                Map.of("datasetKey", "quality-fresh"))).path("data").path("id").asText();
        readTree(postApi("/api/v1/datasets/" + freshId + "/items",
                Map.of("input", "新鲜样本", "expectedOutput", "答案")));
        DatasetEntity rotten = new DatasetEntity();
        rotten.setWorkspaceId(WS);
        rotten.setDatasetKey("quality-rotten");
        rotten.setCreatedAt(LocalDateTime.now().minusDays(200));
        rotten.setUpdatedAt(LocalDateTime.now().minusDays(200));
        datasetMapper.insert(rotten);
        for (int i = 0; i < 4; i++) {
            DatasetItemEntity item = new DatasetItemEntity();
            item.setDatasetId(rotten.getId());
            item.setInput("旧样本 " + i);
            item.setStatus("ACTIVE");
            item.setCreatedAt(LocalDateTime.now().minusDays(200));
            datasetItemMapper.insert(item);
        }
        // 缓存：窗口内两条
        for (int i = 0; i < 2; i++) {
            EvalCacheEntity cache = new EvalCacheEntity();
            cache.setWorkspaceId(WS);
            cache.setCacheKey("quality-cache-" + i);
            cache.setSampleHash("sample-" + i);
            cache.setEvaluatorFingerprint("fp-" + i);
            cache.setJudgeModel("q-judge");
            cache.setLabel("PASS");
            cache.setPromptTokens(500);
            cache.setCompletionTokens(100);
            cache.setCreatedAt(LocalDateTime.now());
            cache.setExpiresAt(LocalDateTime.now().plusDays(1));
            evalCacheMapper.insert(cache);
        }

        JsonNode report = readTree(get("/api/v1/evaluator-quality?windowHours=24&staleDays=90"));
        assertThat(report.path("code").asInt()).as("report: %s", report).isZero();
        JsonNode data = report.path("data");

        // 最不可信评估器：本类两个用例共用 ws-default，因此断言「判定为 LOW_TRUST」
        // 与「quality-bad 这一行的具体口径」，而不是断言某个固定 key 排第一。
        assertThat(data.path("leastTrusted").path("verdict").asText()).isEqualTo("LOW_TRUST");

        JsonNode bad = null;
        for (JsonNode row : data.path("evaluators")) {
            if ("quality-bad".equals(row.path("evaluatorKey").asText())) {
                bad = row;
            }
        }
        assertThat(bad).isNotNull();
        assertThat(bad.path("verdict").asText()).isEqualTo("LOW_TRUST");
        assertThat(bad.path("agreementRate").asDouble()).isEqualTo(50.0);
        assertThat(bad.path("unknownCount").asInt()).isEqualTo(4);
        assertThat(bad.path("notExecutedCount").asInt()).isEqualTo(2);
        assertThat(bad.path("cost").asDouble()).isGreaterThan(0.0);
        assertThat(bad.path("advice").toString()).contains("一致率");
        assertThat(data.path("leastTrusted").path("agreementRate").asDouble()).isLessThanOrEqualTo(50.0);

        JsonNode good = null;
        for (JsonNode row : data.path("evaluators")) {
            if ("quality-good".equals(row.path("evaluatorKey").asText())) {
                good = row;
            }
        }
        assertThat(good).isNotNull();
        assertThat(good.path("verdict").asText()).isEqualTo("OK");
        assertThat(good.path("agreementRate").asDouble()).isEqualTo(90.0);
        assertThat(good.path("passRate").asDouble()).isEqualTo(80.0);

        // 数据集腐烂判定
        JsonNode rottenRow = null;
        for (JsonNode row : data.path("datasets")) {
            if ("quality-rotten".equals(row.path("datasetKey").asText())) {
                rottenRow = row;
            }
        }
        assertThat(rottenRow).isNotNull();
        assertThat(rottenRow.path("verdict").asText()).isEqualTo("ROTTING");
        assertThat(rottenRow.path("staleShare").asDouble()).isEqualTo(100.0);
        assertThat(rottenRow.path("advice").toString()).contains("补新样本");

        assertThat(data.path("cache").path("entries").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(data.path("cache").path("note").asText()).contains("工作空间");

        // 告警：低可信评估器 + 腐烂数据集至少各一条
        String alerts = data.path("alerts").toString();
        assertThat(alerts).contains("EVALUATOR_QUALITY").contains("DATASET_ROT");
    }

    @Test
    void scanWritesFindingsOncePerDay() throws Exception {
        String badId = createEvaluator("quality-scan-bad");
        for (int i = 0; i < 10; i++) {
            insertResult(badId, "quality-scan-bad", i < 2 ? "PASS" : "UNKNOWN",
                    "q-judge", 10, 10, "quality-scan-t" + i, 1);
        }
        for (int i = 0; i < 10; i++) {
            insertReview("quality-scan-bad", i, "PASS", i < 3 ? "PASS" : "FAIL");
        }
        JsonNode first = readTree(postApi("/api/v1/evaluator-quality/scan", Map.of()));
        assertThat(first.path("code").asInt()).isZero();
        assertThat(first.path("data").path("findingsCreated").asInt()).isGreaterThanOrEqualTo(1);

        List<FindingEntity> findings = findingMapper.selectList(Wrappers.<FindingEntity>lambdaQuery()
                .eq(FindingEntity::getWorkspaceId, WS)
                .eq(FindingEntity::getType, "EVALUATOR_QUALITY"));
        assertThat(findings).isNotEmpty();
        assertThat(findings.get(0).getDetectorId()).contains("quality-scan-bad");

        JsonNode second = readTree(postApi("/api/v1/evaluator-quality/scan", Map.of()));
        assertThat(second.path("data").path("findingsCreated").asInt()).isZero();
    }

    private ModelPriceEntity price(String model, String input, String output) {
        ModelPriceEntity price = new ModelPriceEntity();
        price.setWorkspaceId(WS);
        price.setModel(model);
        price.setInputPerMillion(new BigDecimal(input));
        price.setOutputPerMillion(new BigDecimal(output));
        price.setCurrency("USD");
        price.setCreatedAt(LocalDateTime.now());
        price.setUpdatedAt(LocalDateTime.now());
        return price;
    }
}
