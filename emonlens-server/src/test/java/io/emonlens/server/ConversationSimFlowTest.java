package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Resource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;

import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

/**
 * R3-1 多轮会话仿真（REPLAY_SIM）验收：
 * 1. 一段仿真按 persona 跑满轮次，每轮都进 trace 体系（同 sessionId，可回放）；
 * 2. 对话轮次进入样本契约 v2 的 turns，评估器照常打分；
 * 3. 成本分 simulation / generation / judge 三相；
 * 4. 仿真配置进候选指纹（换 persona 即换候选）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-sim;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.sim.allow-private-targets=true",
        "emonlens.sim.max-turns=6",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
class ConversationSimFlowTest extends AbstractIntegrationTest {

    private static HttpServer agentServer;
    private static String agentUrl;

    @Resource
    private DatasetRunMapper datasetRunMapper;

    @TestConfiguration
    static class FakeSimLlmConfig {
        @Bean
        @Primary
        SimFakeLlm simFakeLlm() {
            return new SimFakeLlm();
        }
    }

    /** 模拟用户：按轮次返回固定发言，第 3 轮起宣告目标达成。 */
    static class SimFakeLlm implements LlmClient {
        final Map<String, Integer> calls = new ConcurrentHashMap<>();

        @Override
        public String chat(String systemPrompt, String userPrompt, String model, double temperature) {
            return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
        }

        @Override
        public ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model,
                                       double temperature) {
            int turn = calls.merge("turns", 1, Integer::sum);
            String user = turn >= 2 ? "好的，那就这样，谢谢" : "我还是没收到退款，帮我查一下";
            String json = "{\"user\":\"" + user + "\",\"satisfied\":" + (turn >= 2) + ",\"reason\":\"sim\"}";
            return new ChatReply(json, 120, 24);
        }
    }

    @BeforeAll
    static void startAgent() throws Exception {
        agentServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agentServer.createContext("/agent", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String reply = body.contains("谢谢")
                    ? "{\"output\":\"不客气，退款已到账。\",\"usage\":{\"promptTokens\":40,\"completionTokens\":12}}"
                    : "{\"output\":\"我再帮您确认一次退款进度。\",\"usage\":{\"promptTokens\":40,\"completionTokens\":12}}";
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        agentServer.start();
        agentUrl = "http://127.0.0.1:" + agentServer.getAddress().getPort() + "/agent";
    }

    @AfterAll
    static void stopAgent() {
        if (agentServer != null) {
            agentServer.stop(0);
        }
    }

    private String createDataset(String key) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(response.path("code").asInt()).as("dataset: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    private String createEvaluator(String suffix) throws Exception {
        JsonNode response = readTree(postApi("/api/v1/evaluators", Map.of(
                "evaluatorKey", "sim-" + suffix, "type", "EXPRESSION",
                "spec", Map.of("type", "EXPRESSION", "passWhen", "span_count >= 0"))));
        assertThat(response.path("code").asInt()).as("evaluator: %s", response).isZero();
        return response.path("data").path("id").asText();
    }

    private JsonNode startSimRun(String datasetId, String evaluatorId, String key, String persona)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("evaluatorIds", List.of(evaluatorId));
        body.put("mode", "REPLAY_SIM");
        body.put("simulation", Map.of(
                "persona", persona,
                "goal", "把退款要回来",
                "maxTurns", 4,
                "endpointUrl", agentUrl));
        return readTree(mockMvc.perform(
                        MockMvcRequestBuilders.post("/api/v1/datasets/" + datasetId + "/runs/async")
                                .header("Authorization", adminAuth())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void simulatedConversationLandsInTracesAndReportsThreePhaseCost() throws Exception {
        String datasetId = createDataset("sim-dataset");
        JsonNode item = readTree(postApi("/api/v1/datasets/" + datasetId + "/items",
                Map.of("input", "我的退款一直没到账，帮我处理一下")));
        assertThat(item.path("code").asInt()).isZero();
        String evaluatorId = createEvaluator("refund");

        JsonNode created = startSimRun(datasetId, evaluatorId, "sim-run-1", "着急的客户");
        assertThat(created.path("code").asInt()).as("run: %s", created).isZero();
        String runId = created.path("data").path("runId").asText();
        await(() -> datasetRunMapper.selectById(runId),
                run -> run != null && DatasetRunEntity.STATUS_COMPLETED.equals(run.getStatus()),
                ASYNC_TIMEOUT_MS);

        DatasetRunEntity run = datasetRunMapper.selectById(runId);
        assertThat(run.getRunMode()).isEqualTo("REPLAY_SIM");
        assertThat(run.getPassCount()).isEqualTo(1);

        JsonNode report = readTree(get("/api/v1/datasets/" + datasetId + "/runs/" + runId + "/simulation"));
        assertThat(report.path("code").asInt()).as("report: %s", report).isZero();
        JsonNode data = report.path("data");
        assertThat(data.path("sessions").asInt()).isEqualTo(1);
        // 命中 satisfied 的那条用户消息不会再发给 Agent：3 次模拟发言里只有 2 次产生轮次
        assertThat(data.path("turns").asInt()).isEqualTo(2);
        assertThat(data.path("stopReasons").toString()).contains("SATISFIED");
        JsonNode phases = data.path("costPhases");
        assertThat(phases.path("simulation").path("calls").asInt()).isEqualTo(2);
        assertThat(phases.path("simulation").path("promptTokens").asInt()).isEqualTo(240);
        assertThat(phases.path("generation").path("calls").asInt()).isEqualTo(2);
        assertThat(phases.path("generation").path("promptTokens").asInt()).isEqualTo(80);
        assertThat(phases.path("judge").path("calls").asInt()).isZero();

        // 每一轮都进 trace 体系，sessionId 相同 → 会话页可以整段回放
        JsonNode traceIds = data.path("items").get(0).path("traceIds");
        assertThat(traceIds).hasSize(2);
        String sessionId = data.path("items").get(0).path("sessionId").asText();
        assertThat(sessionId).startsWith("sim-");
        for (JsonNode traceId : traceIds) {
            JsonNode trace = readTree(get("/api/v1/traces/" + traceId.asText()));
            assertThat(trace.path("code").asInt()).as("trace: %s", trace).isZero();
            assertThat(trace.path("data").path("summary").path("status").asText()).isEqualTo("COMPLETED");
        }

        // 同配置重跑：候选指纹一致（persona 相同）
        JsonNode again = startSimRun(datasetId, evaluatorId, "sim-run-2", "着急的客户");
        assertThat(again.path("code").asInt()).isZero();
        DatasetRunEntity second = datasetRunMapper.selectById(again.path("data").path("runId").asText());
        assertThat(second.getCandidateFingerprint()).isEqualTo(run.getCandidateFingerprint());

        // 换 persona = 换候选
        await(() -> datasetRunMapper.selectById(second.getId()),
                candidate -> candidate != null
                        && !DatasetRunEntity.STATUS_PENDING.equals(candidate.getStatus())
                        && !"RUNNING".equals(candidate.getStatus()), ASYNC_TIMEOUT_MS);
        JsonNode different = startSimRun(datasetId, evaluatorId, "sim-run-3", "不耐烦的老客户");
        assertThat(different.path("code").asInt()).isZero();
        DatasetRunEntity third = datasetRunMapper.selectById(different.path("data").path("runId").asText());
        assertThat(third.getCandidateFingerprint()).isNotEqualTo(run.getCandidateFingerprint());
    }
}
