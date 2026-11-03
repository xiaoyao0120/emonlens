package io.emonlens.server.engine.sim;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.ingest.IngestService;
import io.emonlens.server.ingest.TrustContext;
import io.emonlens.server.ingest.dto.IngestResponse;
import io.emonlens.server.ingest.dto.SpanEventDto;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 多轮会话仿真（R3-1，REPLAY_SIM）：按 persona 与场景驱动一段多轮对话。
 *
 * <p>每一轮：模拟用户先发言（platform 侧 LLM）→ 调用被测 Agent（HTTP 端点，promptfoo provider 风格）→
 * 把这一轮写成一条真实 trace（同一 sessionId），因此整段仿真既能在会话页回放，也能被既有评估器打分。</p>
 *
 * <p>停止条件：模拟用户宣告目标达成（satisfied）／达到 maxTurns／Agent 调用失败。
 * 成本三相分开记账：simulation（模拟用户）、generation（Agent 调用，端点回报 usage 时）、judge（评估器，由 worker 统计）。</p>
 *
 * <p>SSRF 防护：默认拒绝回环与私网地址，只有显式开启 emonlens.sim.allow-private-targets 才允许（测试/内网部署用）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationSimulator {

    public static final String MODE_REPLAY_SIM = "REPLAY_SIM";
    public static final String AGENT_KEY = "dataset-sim";
    public static final String STOP_SATISFIED = "SATISFIED";
    public static final String STOP_MAX_TURNS = "MAX_TURNS";
    public static final String STOP_AGENT_ERROR = "AGENT_ERROR";
    public static final String STOP_SIMULATOR_ERROR = "SIMULATOR_ERROR";

    private static final String SIMULATOR_SYSTEM = "You simulate a real end user talking to an AI assistant. "
            + "Stay in character, keep every message short (one or two sentences), and never help the assistant. "
            + "Return strict JSON only: {\"user\":\"...\",\"satisfied\":true|false,\"reason\":\"...\"}. "
            + "Set satisfied=true only when the assistant has actually resolved the user's problem.";

    private final LlmClient llmClient;
    private final IngestService ingestService;
    private final TraceSummaryMapper traceSummaryMapper;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;

    /** 一次仿真的结果：轮次、会话 id、trace 列表与三相用量。 */
    public static class SimResult {
        private String sessionId;
        private final List<Map<String, Object>> turns = new ArrayList<>();
        private final List<String> traceIds = new ArrayList<>();
        private String transcript;
        private String stopReason = STOP_MAX_TURNS;
        private int agentCalls;
        private int simulationCalls;
        private int simulationPromptTokens;
        private int simulationCompletionTokens;
        private Integer agentPromptTokens;
        private Integer agentCompletionTokens;
        private String error;

        public String getSessionId() {
            return sessionId;
        }

        public List<Map<String, Object>> getTurns() {
            return turns;
        }

        public List<String> getTraceIds() {
            return traceIds;
        }

        public String getTranscript() {
            return transcript;
        }

        public String getStopReason() {
            return stopReason;
        }

        public int getAgentCalls() {
            return agentCalls;
        }

        public int getSimulationCalls() {
            return simulationCalls;
        }

        public int getSimulationPromptTokens() {
            return simulationPromptTokens;
        }

        public int getSimulationCompletionTokens() {
            return simulationCompletionTokens;
        }

        public Integer getAgentPromptTokens() {
            return agentPromptTokens;
        }

        public Integer getAgentCompletionTokens() {
            return agentCompletionTokens;
        }

        public String getError() {
            return error;
        }
    }

    public SimResult simulate(String workspaceId, String runId, String itemId, String scenario,
                              Map<String, Object> simulation, String model) {
        String persona = string(simulation.get("persona"), "一位有具体诉求的普通用户");
        String goal = string(simulation.get("goal"), scenario == null ? "" : scenario);
        String endpointUrl = string(simulation.get("endpointUrl"), null);
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "REPLAY_SIM 需要 simulation.endpointUrl（被测 Agent 的 HTTP 端点）");
        }
        int maxTurns = intOf(simulation.get("maxTurns"), properties.getSim().getMaxTurns());
        maxTurns = Math.max(1, Math.min(maxTurns, properties.getSim().getMaxTurns()));
        String simModel = string(simulation.get("model"), model);
        double temperature = simulation.get("temperature") instanceof Number
                ? ((Number) simulation.get("temperature")).doubleValue() : 0.7;
        assertTargetAllowed(endpointUrl);

        SimResult result = new SimResult();
        result.sessionId = "sim-" + suffix(runId) + "-" + suffix(itemId) + "-" + System.nanoTime() % 100000;
        StringBuilder transcript = new StringBuilder();
        String lastUser = goal.isBlank() ? scenario : goal;
        for (int turn = 1; turn <= maxTurns; turn++) {
            String userMessage;
            boolean satisfied = false;
            if (turn == 1 && lastUser != null && !lastUser.isBlank()) {
                // 第一轮直接用样本输入/目标作为开场，避免"模拟用户"把场景重述一遍
                userMessage = lastUser;
            } else {
                try {
                    LlmClient.ChatReply reply = llmClient.chatWithUsage(SIMULATOR_SYSTEM,
                            simulatorPrompt(persona, goal, transcript.toString(), turn, maxTurns),
                            simModel, temperature);
                    result.simulationCalls++;
                    result.simulationPromptTokens += reply.promptTokens() == null ? 0 : reply.promptTokens();
                    result.simulationCompletionTokens += reply.completionTokens() == null ? 0 : reply.completionTokens();
                    Map<String, Object> parsed = parseJson(reply.content());
                    userMessage = string(parsed.get("user"), null);
                    satisfied = Boolean.TRUE.equals(parsed.get("satisfied"));
                    if (userMessage == null || userMessage.isBlank()) {
                        result.stopReason = STOP_SIMULATOR_ERROR;
                        result.error = "模拟用户没有给出有效发言";
                        break;
                    }
                } catch (Exception e) {
                    result.stopReason = STOP_SIMULATOR_ERROR;
                    result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    break;
                }
            }
            if (satisfied && turn > 1) {
                result.stopReason = STOP_SATISFIED;
                break;
            }

            String agentReply;
            try {
                AgentReply reply = callAgent(endpointUrl, userMessage, result.sessionId, turn);
                agentReply = reply.output;
                result.agentCalls++;
                if (reply.promptTokens != null) {
                    result.agentPromptTokens = plus(result.agentPromptTokens, reply.promptTokens);
                }
                if (reply.completionTokens != null) {
                    result.agentCompletionTokens = plus(result.agentCompletionTokens, reply.completionTokens);
                }
            } catch (Exception e) {
                result.stopReason = STOP_AGENT_ERROR;
                result.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                break;
            }

            Map<String, Object> turnRow = new LinkedHashMap<>();
            turnRow.put("turn", turn);
            turnRow.put("user", userMessage);
            turnRow.put("assistant", agentReply);
            result.turns.add(turnRow);
            transcript.append("第").append(turn).append("轮用户：").append(userMessage).append('\n')
                    .append("第").append(turn).append("轮助手：").append(agentReply).append('\n');

            String traceId = writeTurnTrace(workspaceId, result.sessionId, turn, userMessage, agentReply);
            result.traceIds.add(traceId);

            if (satisfied) {
                result.stopReason = STOP_SATISFIED;
                break;
            }
            lastUser = null;
        }
        result.transcript = transcript.toString();
        if (result.turns.isEmpty() && result.error == null) {
            result.error = "没有产生任何有效轮次";
        }
        return result;
    }

    // ==================== Agent 调用 ====================

    private static class AgentReply {
        private String output;
        private Integer promptTokens;
        private Integer completionTokens;
    }

    private AgentReply callAgent(String endpointUrl, String userMessage, String sessionId, int turn)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", userMessage);
        body.put("sessionId", sessionId);
        body.put("turn", turn);
        byte[] payload = objectMapper.writeValueAsBytes(body);
        HttpURLConnection connection = (HttpURLConnection) new URL(endpointUrl).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(properties.getSim().getConnectTimeoutMs());
        connection.setReadTimeout(properties.getSim().getAgentTimeoutMs());
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = connection.getOutputStream()) {
            out.write(payload);
        }
        int status = connection.getResponseCode();
        String text;
        try (InputStream in = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
            text = readAll(in);
        }
        if (status >= 400) {
            throw new EmonLensException(ErrorCode.DOWNSTREAM_UNAVAILABLE,
                    "Agent 端点返回 HTTP " + status + ": " + abbreviate(text));
        }
        Map<String, Object> parsed = parseJson(text);
        String output = string(parsed.get("output"), null);
        if (output == null) {
            // 宽松兼容：不强制 JSON，纯文本也当作回复（promptfoo provider 常见返回）
            output = text == null || text.isBlank() ? null : text.trim();
        }
        if (output == null) {
            throw new EmonLensException(ErrorCode.DOWNSTREAM_UNAVAILABLE, "Agent 端点没有返回 output");
        }
        AgentReply reply = new AgentReply();
        reply.output = output;
        if (parsed.get("usage") instanceof Map) {
            Map<?, ?> usage = (Map<?, ?>) parsed.get("usage");
            reply.promptTokens = intOrNull(usage.get("promptTokens"));
            reply.completionTokens = intOrNull(usage.get("completionTokens"));
        }
        return reply;
    }

    // ==================== trace 写入 ====================

    private String writeTurnTrace(String workspaceId, String sessionId, int turn,
                                  String userMessage, String agentReply) {
        String traceId = sessionId + "-t" + turn;
        SpanEventDto root = new SpanEventDto();
        root.setTraceId(traceId);
        root.setSpanId("root");
        root.setAgentKey(AGENT_KEY);
        root.setAgentVersion("sim");
        root.setSpanType("agent");
        root.setSpanName("sim_turn_" + turn);
        root.setStatus(SpanEventDto.STATUS_OK);
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        root.setStartTime(now.minusSeconds(1).toString());
        root.setEndTime(now.toString());
        root.setInput(userMessage);
        root.setOutput(agentReply);
        root.setSessionId(sessionId);
        Map<String, String> contents = new LinkedHashMap<>();
        contents.put("prompt", userMessage);
        contents.put("completion", agentReply);
        root.setContent(contents);
        IngestResponse response = ingestService.accept(new TrustContext(AGENT_KEY, workspaceId),
                List.of(root));
        if (response.getAcceptedCount() != 1) {
            log.warn("simulation trace rejected, trace={}, rejected={}", traceId, response.getRejected());
        }
        awaitAssembled(traceId);
        return traceId;
    }

    private void awaitAssembled(String traceId) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            TraceSummaryEntity summary = traceSummaryMapper.selectOne(
                    Wrappers.<TraceSummaryEntity>lambdaQuery().eq(TraceSummaryEntity::getTraceId, traceId));
            if (summary != null) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.warn("simulation trace assembly timeout, trace={}", traceId);
    }

    // ==================== 工具 ====================

    /** 默认只允许公网 http(s)：仿真端点由用户配置，服务端主动发请求前必须挡掉 SSRF。 */
    private void assertTargetAllowed(String endpointUrl) {
        URI uri;
        try {
            uri = URI.create(endpointUrl);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "endpointUrl 不是合法 URL");
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "endpointUrl 只支持 http/https");
        }
        if (properties.getSim().isAllowPrivateTargets()) {
            return;
        }
        try {
            InetAddress address = InetAddress.getByName(uri.getHost());
            if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "endpointUrl 指向内网/回环地址；如为自建服务请开启 emonlens.sim.allow-private-targets");
            }
        } catch (EmonLensException e) {
            throw e;
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "endpointUrl 域名无法解析");
        }
    }

    private String simulatorPrompt(String persona, String goal, String transcript, int turn, int maxTurns) {
        return "Persona: " + persona + "\n"
                + "User goal: " + (goal == null ? "" : goal) + "\n"
                + "Turn " + turn + " of at most " + maxTurns + "\n"
                + "Conversation so far:\n" + (transcript.isBlank() ? "(none)" : transcript)
                + "\nWrite the user's next message. If the assistant already solved the problem, set satisfied=true.";
    }

    private String readAll(InputStream in) throws Exception {
        if (in == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private Map<String, Object> parseJson(String text) {
        if (text == null) {
            return new LinkedHashMap<>();
        }
        String trimmed = text.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(trimmed.substring(start, end + 1),
                    new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Integer intOrNull(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : null;
    }

    private static Integer plus(Integer current, Integer delta) {
        if (delta == null) {
            return current;
        }
        return current == null ? delta : current + delta;
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static String string(Object value, String fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        return String.valueOf(value);
    }

    private static String suffix(String value) {
        if (value == null) {
            return "x";
        }
        String clean = value.replace("-", "");
        return clean.length() <= 8 ? clean : clean.substring(0, 8);
    }

    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 200 ? text.substring(0, 200) : text;
    }
}
