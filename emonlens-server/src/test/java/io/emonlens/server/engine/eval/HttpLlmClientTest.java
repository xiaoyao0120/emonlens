package io.emonlens.server.engine.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;

/** 协议选择 / endpoint 拼接 / 错误体透出 的单元测试（本地 HttpServer，无外网依赖）。 */
class HttpLlmClientTest {

    private HttpServer server;
    private int port;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<Captured> captured = new AtomicReference<>();
    private HttpLlmClient client;
    private EmonLensProperties properties;

    private static class Captured {
        String path;
        Map<String, String> headers = new LinkedHashMap<>();
        String body = "";
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();

        properties = new EmonLensProperties();
        properties.getEval().getLlm().setTimeoutMs(5000);
        properties.getEval().getLlm().setMaxAttempts(3);
        properties.getEval().getLlm().setInitialBackoffMs(1);
        properties.getEval().getLlm().setMaxBackoffMs(5);
        properties.getEval().getLlm().setJitter(0);
        SysSettingService settings = mock(SysSettingService.class);
        DatasetRunCredentialStore credentials = mock(DatasetRunCredentialStore.class);
        RunTokenBudgetStore budget = mock(RunTokenBudgetStore.class);
        when(credentials.current()).thenReturn(null);
        client = new HttpLlmClient(properties, settings, mapper, credentials, budget);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void openAiProtocolUsesChatCompletionsWithBearerAuth() throws Exception {
        respond(200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}");

        LlmClient.ChatReply reply = client.chat(
                override("http://127.0.0.1:" + port + "/v1", "openai", "sk-test"),
                "sys", "hi", "gpt-test", 0.0);

        assertThat(reply.content()).isEqualTo("pong");
        assertThat(reply.promptTokens()).isEqualTo(3);
        assertThat(reply.completionTokens()).isEqualTo(1);

        Captured c = captured.get();
        assertThat(c.path).isEqualTo("/v1/chat/completions");
        assertThat(header(c, "Authorization")).isEqualTo("Bearer sk-test");
        assertThat(header(c, "x-opencode-session")).startsWith("emonlens-");
        assertThat(header(c, "User-Agent")).isEqualTo("EmonLens/0.1");

        JsonNode body = mapper.readTree(c.body);
        assertThat(body.path("model").asText()).isEqualTo("gpt-test");
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
    }

    @Test
    void anthropicProtocolUsesMessagesWithSystemTopLevelAndApiKeyHeader() throws Exception {
        respond(200, "{\"content\":[{\"type\":\"text\",\"text\":\"po\"},{\"type\":\"text\",\"text\":\"ng\"}],"
                + "\"usage\":{\"input_tokens\":5,\"output_tokens\":2}}");

        LlmClient.ChatReply reply = client.chat(
                override("http://127.0.0.1:" + port + "/inference/go/anthropic/v1", "anthropic", "sk-ant"),
                "sys prompt", "hi", "claude-haiku-5-5", 0.0);

        assertThat(reply.content()).isEqualTo("pong");
        assertThat(reply.promptTokens()).isEqualTo(5);
        assertThat(reply.completionTokens()).isEqualTo(2);

        Captured c = captured.get();
        assertThat(c.path).isEqualTo("/inference/go/anthropic/v1/messages");
        assertThat(header(c, "x-api-key")).isEqualTo("sk-ant");
        assertThat(header(c, "anthropic-version")).isEqualTo("2023-06-01");
        assertThat(header(c, "Authorization")).isNull();

        JsonNode body = mapper.readTree(c.body);
        assertThat(body.path("system").asText()).isEqualTo("sys prompt");
        assertThat(body.path("max_tokens").asInt()).isGreaterThan(0);
        assertThat(body.path("messages").size()).isEqualTo(1);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("user");
    }

    @Test
    void endpointResolutionNeverDoublesPaths() {
        assertThat(HttpLlmClient.endpointFor("https://api.openai.com", "openai"))
                .isEqualTo("https://api.openai.com/v1/chat/completions");
        assertThat(HttpLlmClient.endpointFor("https://api.anthropic.com", "anthropic"))
                .isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(HttpLlmClient.endpointFor("https://opencode.ai/zen/go/v1/", "openai"))
                .isEqualTo("https://opencode.ai/zen/go/v1/chat/completions");
        assertThat(HttpLlmClient.endpointFor("https://opencode.ai/inference/go/anthropic/v1", "anthropic"))
                .isEqualTo("https://opencode.ai/inference/go/anthropic/v1/messages");
        // 直接粘贴完整 endpoint 时原样使用，切换协议也不会重复拼接
        assertThat(HttpLlmClient.endpointFor("https://x.dev/v1/chat/completions", "openai"))
                .isEqualTo("https://x.dev/v1/chat/completions");
        assertThat(HttpLlmClient.endpointFor("https://x.dev/v1/messages", "anthropic"))
                .isEqualTo("https://x.dev/v1/messages");
    }

    @Test
    void retriesWithoutTemperatureWhenProviderRejectsIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> retriedBody = new AtomicReference<>();
        server.createContext("/", exchange -> {
            String requestBody = readAll(exchange.getRequestBody());
            if (calls.incrementAndGet() == 1) {
                writeJson(exchange, 400, "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\","
                        + "\"message\":\"`temperature` is deprecated for this model.\"}}");
            } else {
                retriedBody.set(requestBody);
                writeJson(exchange, 200, "{\"content\":[{\"type\":\"text\",\"text\":\"pong\"}],"
                        + "\"usage\":{\"input_tokens\":4,\"output_tokens\":1}}");
            }
        });
        server.start();

        LlmClient.ChatReply reply = client.chat(
                override("http://127.0.0.1:" + port + "/v1", "anthropic", "sk-ant"),
                "sys", "hi", "claude-haiku-5-5", 0.2);

        assertThat(calls.get()).isEqualTo(2);
        assertThat(reply.content()).isEqualTo("pong");
        assertThat(retriedBody.get()).doesNotContain("temperature");
    }

    @Test
    void retriesOn429ThenSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            readAll(exchange.getRequestBody());
            if (calls.incrementAndGet() == 1) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                writeJson(exchange, 429, "{\"error\":{\"message\":\"rate limited\"}}");
            } else {
                writeJson(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"pong\"}}],"
                        + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}");
            }
        });
        server.start();

        LlmClient.ChatReply reply = client.chat(
                override("http://127.0.0.1:" + port + "/v1", "openai", "sk-test"),
                "sys", "hi", "gpt-test", 0.0);

        assertThat(reply.content()).isEqualTo("pong");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void doesNotRetryAuthError() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            readAll(exchange.getRequestBody());
            calls.incrementAndGet();
            writeJson(exchange, 401, "{\"error\":{\"message\":\"invalid api key\"}}");
        });
        server.start();

        assertThatThrownBy(() -> client.chat(
                override("http://127.0.0.1:" + port + "/v1", "openai", "sk-bad"),
                "sys", "hi", "gpt-test", 0.0))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("401");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void doesNotRetryQuotaExhausted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        server.createContext("/", exchange -> {
            readAll(exchange.getRequestBody());
            calls.incrementAndGet();
            writeJson(exchange, 429, "{\"error\":{\"code\":\"insufficient_quota\","
                    + "\"message\":\"quota exhausted\"}}");
        });
        server.start();

        assertThatThrownBy(() -> client.chat(
                override("http://127.0.0.1:" + port + "/v1", "openai", "sk-test"),
                "sys", "hi", "gpt-test", 0.0))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("429");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void providerErrorBodyIsSurfacedInExceptionMessage() throws Exception {
        respond(400, "{\"type\":\"error\",\"error\":{\"type\":\"MissingSessionID\","
                + "\"message\":\"Request is missing x-opencode-session and cannot be routed efficiently.\"}}");

        assertThatThrownBy(() -> client.chat(
                override("http://127.0.0.1:" + port + "/zen/go/v1", "openai", "sk-test"),
                "sys", "hi", "deepseek-v4.1-flash", 0.0))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("llm http status 400")
                .hasMessageContaining("MissingSessionID")
                .hasMessageContaining("x-opencode-session");
    }

    @Test
    void effectiveConfigFallsBackToYmlProtocol() {
        EmonLensProperties properties = new EmonLensProperties();
        properties.getEval().getLlm().setProtocol("anthropic");
        properties.getEval().getLlm().setBaseUrl("https://api.anthropic.com");
        SysSettingService settings = mock(SysSettingService.class);
        when(settings.getEvalLlm()).thenReturn(null);
        DatasetRunCredentialStore credentials = mock(DatasetRunCredentialStore.class);
        when(credentials.current()).thenReturn(null);

        HttpLlmClient ymlOnly = new HttpLlmClient(properties, settings, mapper, credentials,
                mock(RunTokenBudgetStore.class));

        assertThat(ymlOnly.effectiveConfig().getProtocol()).isEqualTo("anthropic");
    }

    /** JDK HttpServer 会规范化 header 名大小写，这里统一按大小写无关查找。 */
    private String header(Captured c, String name) {
        for (Map.Entry<String, String> e : c.headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    private SysSettingService.EvalLlm override(String baseUrl, String protocol, String apiKey) {
        SysSettingService.EvalLlm cfg = new SysSettingService.EvalLlm();
        cfg.setBaseUrl(baseUrl);
        cfg.setProtocol(protocol);
        cfg.setApiKey(apiKey);
        cfg.setTimeoutMs(5000L);
        return cfg;
    }

    private void respond(int status, String json) {
        server.createContext("/", exchange -> {
            Captured c = new Captured();
            c.path = exchange.getRequestURI().getPath();
            for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
                c.headers.put(e.getKey(), e.getValue().isEmpty() ? "" : e.getValue().get(0));
            }
            c.body = readAll(exchange.getRequestBody());
            captured.set(c);
            writeJson(exchange, status, json);
        });
        server.start();
    }

    private void writeJson(HttpExchange exchange, int status, String json) throws IOException {        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        } finally {
            exchange.close();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
