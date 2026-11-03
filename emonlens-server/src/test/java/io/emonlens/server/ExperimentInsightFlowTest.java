package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import javax.annotation.Resource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

/**
 * R2-4 实验对比增强验收：
 * 1. 散点：一行一个 run，含 passRate / coverage / cost / avgLatency，可按覆盖率过滤；
 * 2. repetitions：同候选指纹的多次 run 聚成一组，给出均值 / 样本方差 / 标准差；
 * 3. leaderboard：加权通过率 = 均值通过率 × 覆盖率，覆盖不足显式 degraded 并降权，
 *    排序口径在响应里写明；
 * 4. 复跑接口：按冻结配置再跑一次（同指纹归组），同 Idempotency-Key 幂等。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-insight;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class ExperimentInsightFlowTest extends AbstractIntegrationTest {

    private static final String WS = "ws-default";

    @Resource
    private DatasetMapper datasetMapper;
    @Resource
    private DatasetRunMapper datasetRunMapper;

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).as("dataset: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    /** 直接插入 run 行：leaderboard 只看结论列，不需要真跑一次实验。 */
    private void insertRun(String datasetId, String fingerprint, String label, String model,
                           int itemCount, int pass, int fail, int unknown) {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId(WS);
        run.setDatasetId(datasetId);
        run.setRunMode("TRACE");
        run.setModel(model);
        run.setCandidateLabel(label);
        run.setCandidateFingerprint(fingerprint);
        run.setEvaluatorId("insight-evaluator");
        run.setEvaluatorKey("insight-expr");
        run.setStatus(DatasetRunEntity.STATUS_COMPLETED);
        run.setItemCount(itemCount);
        run.setPassCount(pass);
        run.setFailCount(fail);
        run.setUnknownCount(unknown);
        run.setErrorCount(0);
        run.setIncompleteCount(0);
        run.setAttempt(1);
        run.setStartedAt(LocalDateTime.now().minusMinutes(5));
        run.setFinishedAt(LocalDateTime.now().minusMinutes(4));
        run.setRequestKey("insight-" + fingerprint + "-" + System.nanoTime());
        datasetRunMapper.insert(run);
    }

    @Test
    void leaderboardWeightsByCoverageAndFlagsDegradedCandidates() throws Exception {
        String datasetId = createDataset("insight-leaderboard");
        // A：通过率 100、覆盖 100（两条重复）
        insertRun(datasetId, "fp-a", "candidate-a", "model-a", 10, 10, 0, 0);
        insertRun(datasetId, "fp-a", "candidate-a", "model-a", 10, 10, 0, 0);
        // B：通过率 100，但只判了 6/10（覆盖 60）——应被降权
        insertRun(datasetId, "fp-b", "candidate-b", "model-b", 10, 6, 0, 4);
        insertRun(datasetId, "fp-b", "candidate-b", "model-b", 10, 6, 0, 4);
        // C：通过率 50、覆盖 100
        insertRun(datasetId, "fp-c", "candidate-c", "model-c", 10, 5, 5, 0);

        JsonNode leaderboard = readTree(get("/api/v1/datasets/" + datasetId
                + "/leaderboard?windowDays=7&minCoverage=90"));
        assertThat(leaderboard.path("code").asInt()).as("leaderboard: %s", leaderboard).isZero();
        JsonNode data = leaderboard.path("data");
        assertThat(data.path("rankingRule").asText()).contains("adjustedPassRate");

        JsonNode rows = data.path("rows");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).path("candidate").asText()).isEqualTo("candidate-a");
        assertThat(rows.get(0).path("adjustedPassRate").asDouble()).isEqualTo(100.0);
        assertThat(rows.get(0).path("runs").asInt()).isEqualTo(2);

        // B 的通过率是 100，但覆盖率 60 → 加权 60，排在 A 之后、C 之前，并标 degraded
        JsonNode b = null;
        for (JsonNode row : rows) {
            if ("candidate-b".equals(row.path("candidate").asText())) {
                b = row;
            }
        }
        assertThat(b).isNotNull();
        assertThat(b.path("passRateMean").asDouble()).isEqualTo(100.0);
        assertThat(b.path("coverageMean").asDouble()).isEqualTo(60.0);
        assertThat(b.path("adjustedPassRate").asDouble()).isEqualTo(60.0);
        assertThat(b.path("degraded").asBoolean()).isTrue();
        assertThat(b.path("degradeReason").asText()).contains("降权");
        assertThat(data.path("degradedCount").asInt()).isEqualTo(1);

        assertThat(rows.get(1).path("candidate").asText()).isEqualTo("candidate-b");
        assertThat(rows.get(2).path("candidate").asText()).isEqualTo("candidate-c");
        assertThat(rows.get(2).path("adjustedPassRate").asDouble()).isEqualTo(50.0);
    }

    @Test
    void repetitionsReportMeanAndSampleVariance() throws Exception {
        String datasetId = createDataset("insight-repetitions");
        // 同一候选指纹的三次重复：通过率 100 / 90 / 80
        insertRun(datasetId, "fp-rep", "candidate-rep", "model-r", 10, 10, 0, 0);
        insertRun(datasetId, "fp-rep", "candidate-rep", "model-r", 10, 9, 1, 0);
        insertRun(datasetId, "fp-rep", "candidate-rep", "model-r", 10, 8, 2, 0);

        JsonNode leaderboard = readTree(get("/api/v1/datasets/" + datasetId
                + "/leaderboard?windowDays=7&minCoverage=90"));
        JsonNode row = leaderboard.path("data").path("rows").get(0);
        assertThat(row.path("candidate").asText()).isEqualTo("candidate-rep");
        assertThat(row.path("runs").asInt()).isEqualTo(3);
        assertThat(row.path("passRateMean").asDouble()).isEqualTo(90.0);
        // 样本方差 ((100-90)^2 + 0 + (80-90)^2) / 2 = 100
        assertThat(row.path("passRateVariance").asDouble()).isEqualTo(100.0);
        assertThat(row.path("passRateStdDev").asDouble()).isEqualTo(10.0);
    }

    @Test
    void scatterExposesCostAndLatencyAndFiltersByCoverage() throws Exception {
        String datasetId = createDataset("insight-scatter");
        insertRun(datasetId, "fp-s1", "scatter-1", "model-s1", 4, 4, 0, 0);
        insertRun(datasetId, "fp-s2", "scatter-2", "model-s2", 4, 1, 0, 3);

        JsonNode scatter = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/scatter?windowDays=7"));
        assertThat(scatter.path("code").asInt()).isZero();
        JsonNode points = scatter.path("data").path("points");
        assertThat(points).hasSize(2);
        JsonNode first = points.get(0);
        assertThat(first.has("passRate")).isTrue();
        assertThat(first.has("coverage")).isTrue();
        assertThat(first.has("priced")).isTrue();
        assertThat(first.path("repetitionGroup").asText()).isNotBlank();
        assertThat(scatter.path("data").path("axes").path("x").asText()).contains("cost");

        // 覆盖率过滤：只保留 coverage >= 50 的点
        JsonNode filtered = readTree(get("/api/v1/datasets/" + datasetId
                + "/runs/scatter?windowDays=7&minCoverage=50"));
        assertThat(filtered.path("data").path("points")).hasSize(1);
        assertThat(filtered.path("data").path("filteredOut").asInt()).isEqualTo(1);
    }

    @Test
    void repeatEndpointClonesFrozenConfigAndIsIdempotent() throws Exception {
        String datasetId = createDataset("insight-repeat");
        String traceId = "insight-repeat-trace";
        post("/api/v1/ingest/events", List.of(span("insight-agent", traceId, "root", null,
                "agent", "invoke_agent", null, null, 1, 5)));
        await(() -> {
            try {
                return readTree(get("/api/v1/traces/" + traceId)).path("data").path("summary");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, node -> "COMPLETED".equals(node.path("status").asText()), ASYNC_TIMEOUT_MS);
        readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("traceId", traceId)));
        String evaluatorId = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", "insight-expr", "type", "EXPRESSION",
                "spec", Map.of("type", "EXPRESSION", "passWhen", "error_count >= 0"))))
                .path("data").path("id").asText();
        JsonNode created = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/datasets/" + datasetId + "/runs/async")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "insight-run-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "evaluatorIds", List.of(evaluatorId), "mode", "TRACE"))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(created.path("code").asInt()).as("run: %s", created).isZero();
        String runId = created.path("data").path("runId").asText();
        await(() -> datasetRunMapper.selectById(runId),
                run -> run != null && DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus()),
                ASYNC_TIMEOUT_MS);

        JsonNode repeated = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/datasets/" + datasetId
                                        + "/runs/" + runId + "/repetition")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "insight-repeat-1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(repeated.path("code").asInt()).as("repeat: %s", repeated).isZero();
        assertThat(repeated.path("data").path("createdCount").asInt()).isEqualTo(1);
        assertThat(repeated.path("data").path("refused").asInt()).isZero();
        String repeatId = repeated.path("data").path("created").get(0).path("runId").asText();
        assertThat(repeatId).isNotEqualTo(runId);
        assertThat(datasetRunMapper.selectById(repeatId).getCandidateFingerprint())
                .isEqualTo(datasetRunMapper.selectById(runId).getCandidateFingerprint());

        // 同 Idempotency-Key 幂等：返回同一条 run，不新建
        JsonNode again = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/datasets/" + datasetId
                                        + "/runs/" + runId + "/repetition")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "insight-repeat-1"))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(again.path("data").path("createdCount").asInt()).isEqualTo(1);
        assertThat(again.path("data").path("created").get(0).path("runId").asText()).isEqualTo(repeatId);

        // 同指纹归组：复跑与源 run 属于同一 repetitions 组
        await(() -> datasetRunMapper.selectById(repeatId),
                run -> run != null && DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus()),
                ASYNC_TIMEOUT_MS);
        JsonNode leaderboard = readTree(get("/api/v1/datasets/" + datasetId
                + "/leaderboard?windowDays=7&minCoverage=90"));
        assertThat(leaderboard.path("data").path("rows").get(0).path("runs").asInt())
                .isEqualTo(2);
    }

    @Test
    void repeatManyReportsCapacityRefusalsInsteadOfFailing() throws Exception {
        String datasetId = createDataset("insight-repeat-many");
        String traceId = "insight-repeat-many-trace";
        post("/api/v1/ingest/events", List.of(span("insight-agent", traceId, "root", null,
                "agent", "invoke_agent", null, null, 1, 5)));
        await(() -> {
            try {
                return readTree(get("/api/v1/traces/" + traceId)).path("data").path("summary");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, node -> "COMPLETED".equals(node.path("status").asText()), ASYNC_TIMEOUT_MS);
        readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("traceId", traceId)));
        String evaluatorId = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", "insight-expr-many", "type", "EXPRESSION",
                "spec", Map.of("type", "EXPRESSION", "passWhen", "error_count >= 0"))))
                .path("data").path("id").asText();
        JsonNode created = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/datasets/" + datasetId + "/runs/async")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "many-run-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of(
                                        "evaluatorIds", List.of(evaluatorId), "mode", "TRACE"))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(created.path("code").asInt()).as("run: %s", created).isZero();
        String runId = created.path("data").path("runId").asText();
        await(() -> datasetRunMapper.selectById(runId),
                run -> run != null && DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus()),
                ASYNC_TIMEOUT_MS);

        // 默认 maxActiveRuns=1：第一条复跑占坑后，其余被 429 拒绝并如实上报
        JsonNode batch = readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/datasets/" + datasetId
                                        + "/runs/" + runId + "/repetition")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", "many-repeat-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(Map.of("count", 3))))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(batch.path("code").asInt()).as("batch: %s", batch).isZero();
        JsonNode data = batch.path("data");
        assertThat(data.path("requested").asInt()).isEqualTo(3);
        int createdCount = data.path("createdCount").asInt();
        assertThat(createdCount).isGreaterThanOrEqualTo(1);
        assertThat(data.path("refused").asInt()).isEqualTo(3 - createdCount);
        assertThat(data.path("capacityLimited").asBoolean()).isTrue();

        for (JsonNode row : data.path("created")) {
            String createdRunId = row.path("runId").asText();
            assertThat(datasetRunMapper.selectById(createdRunId).getCandidateFingerprint())
                    .isEqualTo(datasetRunMapper.selectById(runId).getCandidateFingerprint());
            await(() -> datasetRunMapper.selectById(createdRunId),
                    run -> run != null && DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus()),
                    ASYNC_TIMEOUT_MS);
        }
        JsonNode leaderboard = readTree(get("/api/v1/datasets/" + datasetId
                + "/leaderboard?windowDays=7&minCoverage=90"));
        assertThat(leaderboard.path("data").path("rows").get(0).path("runs").asInt())
                .isEqualTo(1 + createdCount);
    }
}
