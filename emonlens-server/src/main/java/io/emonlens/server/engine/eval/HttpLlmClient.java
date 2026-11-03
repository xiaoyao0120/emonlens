package io.emonlens.server.engine.eval;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 通用 HTTP LLM 客户端，支持两种 wire protocol：
 *
 * <ul>
 *   <li>{@code openai}（默认）：OpenAI 兼容 {@code /chat/completions}，国产模型网关多为此协议；</li>
 *   <li>{@code anthropic}：Anthropic Messages {@code /messages}（Claude 官方及只暴露 Claude 原生协议的网关）。</li>
 * </ul>
 *
 * 配置优先级：DB 运行时设置（设置页）> application.yml；测试连通性可传 override 临时覆盖。
 * base-url 可直接写 API 根（如 {@code https://api.openai.com}，自动补 {@code /v1}），
 * 也可写到 {@code /v1} 甚至完整 endpoint（{@code /chat/completions}、{@code /messages}）——
 * 只有缺失的部分才会被追加，避免出现 {@code /v1/chat/completions/chat/completions}。
 *
 * <p>OpenCode Zen / Go 要求客户端带稳定会话标识（{@code x-opencode-session}）且 User-Agent
 * 不能是通用 HTTP 库名，否则返回 400 MissingSessionID；这两个头对所有端点都会发送（无害），
 * 因此 OpenCode 的 openai / anthropic 两种端点都能直接使用。</p>
 *
 * base-url 未配置或调用失败一律抛 {@link LlmUnavailableException}（评估层转 EVAL_UNAVAILABLE），
 * 错误信息会带上 provider 返回的 body 摘要，方便定位 401/404/模型不存在等问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpLlmClient implements LlmClient {

    public static final String PROTOCOL_OPENAI = "openai";
    public static final String PROTOCOL_ANTHROPIC = "anthropic";

    /** Anthropic Messages API 版本头；Claude 兼容网关均要求。 */
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    /** 非预算模式下 Anthropic 的默认输出上限（该协议 max_tokens 必填）。 */
    private static final int ANTHROPIC_DEFAULT_MAX_TOKENS = 4096;
    /** provider 错误体保留长度：足够看清 401/404/模型不存在等原因，又不至于刷屏。 */
    private static final int MAX_ERROR_BODY_CHARS = 600;

    private final EmonLensProperties properties;
    private final SysSettingService sysSettingService;
    private final ObjectMapper objectMapper;
    private final DatasetRunCredentialStore credentialStore;
    private final RunTokenBudgetStore tokenBudgetStore;

    /** 进程级稳定会话 ID：OpenCode 用它做路由/提示词缓存，随机化避免与其他客户端串会话。 */
    private final String sessionId = "emonlens-" + UUID.randomUUID();

    @Override
    public String chat(String systemPrompt, String userPrompt, String model, double temperature)
            throws LlmUnavailableException {
        return chatWithUsage(systemPrompt, userPrompt, model, temperature).content();
    }

    /** 带 usage 的评审调用：按协议解析对应响应体的 token 字段。 */
    @Override
    public ChatReply chatWithUsage(String systemPrompt, String userPrompt, String model, double temperature)
            throws LlmUnavailableException {
        return doChat(null, systemPrompt, userPrompt, null, model, temperature);
    }

    /**
     * R3-4：带图片外链的评审调用。vision 端点把图片作为 content parts 收；
     * 这一步必须真的覆写接口默认方法，否则默认实现会退化成纯文本（真机曾因此丢图）。
     */
    @Override
    public ChatReply chatWithImages(String systemPrompt, String userPrompt,
                                    java.util.List<String> imageUrls, String model, double temperature)
            throws LlmUnavailableException {
        return doChat(null, systemPrompt, userPrompt,
                imageUrls == null || imageUrls.isEmpty() ? null : imageUrls, model, temperature);
    }

    /** override 非空且配置了 base-url 时完全按 override 执行（连通性测试未保存的表单值）。 */
    public ChatReply chat(SysSettingService.EvalLlm override, String systemPrompt, String userPrompt,
                          String model, double temperature) throws LlmUnavailableException {
        return doChat(override, systemPrompt, userPrompt, null, model, temperature);
    }

    private ChatReply doChat(SysSettingService.EvalLlm override, String systemPrompt, String userPrompt,
                             java.util.List<String> imageUrls, String model, double temperature)
            throws LlmUnavailableException {
        SysSettingService.EvalLlm cfg = override != null && override.configured()
                ? override : effectiveConfig();
        String baseUrl = cfg.getBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            throw new LlmUnavailableException(
                    "llm provider not configured：请在 系统设置 → LLM 端点 配置 base-url（或 application.yml emonlens.eval.llm.base-url）");
        }
        String protocol = normalizeProtocol(cfg.getProtocol());
        String effectiveModel = firstNonBlank(model, cfg.getModel(), properties.getEval().getLlm().getModel());
        int timeoutMs = cfg.getTimeoutMs() != null ? cfg.getTimeoutMs().intValue()
                : properties.getEval().getLlm().getTimeoutMs();
        String endpoint = endpointFor(baseUrl, protocol);
        String apiKey = cfg.getApiKey();
        int maxAttempts = Math.max(1, Math.min(5, properties.getEval().getLlm().getMaxAttempts()));
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            RunTokenBudgetStore.Reservation reservation = tokenBudgetStore.reserve(systemPrompt, userPrompt);
            try {
                Map<String, Object> body = PROTOCOL_ANTHROPIC.equals(protocol)
                        ? anthropicBody(systemPrompt, userPrompt, imageUrls, effectiveModel, temperature)
                        : openAiBody(systemPrompt, userPrompt, imageUrls, effectiveModel, temperature);
                HttpResult result = post(endpoint, timeoutMs, protocol, apiKey,
                        objectMapper.writeValueAsBytes(body));
                // 部分 Claude（如 Haiku 5.5）及推理模型拒绝 temperature 参数（400 temperature is deprecated）。
                // 评审只需要确定性输出，去掉这个可选参数重试一次即可。
                if (result.status == 400 && body.containsKey("temperature") && mentionsTemperature(result.body)) {
                    body.remove("temperature");
                    result = post(endpoint, timeoutMs, protocol, apiKey,
                            objectMapper.writeValueAsBytes(body));
                }
                if (result.status < 200 || result.status >= 300) {
                    if (attempt < maxAttempts && retryableStatus(result)) {
                        tokenBudgetStore.release(reservation);
                        log.warn("llm transient http failure, attempt={}/{}, status={}, retryInMs={}",
                                attempt, maxAttempts, result.status, result.retryAfterMs);
                        sleepBeforeRetry(attempt, result.retryAfterMs);
                        continue;
                    }
                    throw new LlmUnavailableException(httpError(result.status, result.body));
                }
                JsonNode root = objectMapper.readTree(result.body);
                ChatReply reply = PROTOCOL_ANTHROPIC.equals(protocol)
                        ? parseAnthropic(root) : parseOpenAi(root);
                tokenBudgetStore.settle(reservation, reply.promptTokens(), reply.completionTokens());
                return reply;
            } catch (LlmUnavailableException | IOException e) {
                tokenBudgetStore.release(reservation);
                if (e instanceof IOException && attempt < maxAttempts) {
                    log.warn("llm transient io failure, attempt={}/{}, error={}",
                            attempt, maxAttempts, e.getMessage());
                    sleepBeforeRetry(attempt, 0);
                    continue;
                }
                if (e instanceof LlmUnavailableException) {
                    throw (LlmUnavailableException) e;
                }
                throw new LlmUnavailableException("llm call failed: " + e.getMessage(), e);
            } catch (RuntimeException e) {
                tokenBudgetStore.release(reservation);
                throw e;
            }
        }
        throw new LlmUnavailableException("llm call failed after " + maxAttempts + " attempts");
    }

    /** 429/5xx 可重试；配额/计费/上下文超限是永久错误，重试只会放大成本。 */
    private boolean retryableStatus(HttpResult result) {
        if (result.status != 429 && result.status != 500 && result.status != 502
                && result.status != 503 && result.status != 504) {
            return false;
        }
        String body = result.body == null ? "" : result.body.toLowerCase(Locale.ROOT);
        return !body.contains("insufficient_quota")
                && !body.contains("billing")
                && !body.contains("context_length_exceeded")
                && !body.contains("context length");
    }

    private void sleepBeforeRetry(int attempt, long retryAfterMs) {
        EmonLensProperties.Eval.Llm cfg = properties.getEval().getLlm();
        long initial = Math.max(1, cfg.getInitialBackoffMs());
        long max = Math.max(initial, cfg.getMaxBackoffMs());
        long base = Math.min(max, initial * (1L << Math.min(20, attempt - 1)));
        double jitter = Math.max(0.0, Math.min(1.0, cfg.getJitter()));
        long jittered = (long) (base * (1.0 + (java.util.concurrent.ThreadLocalRandom.current()
                .nextDouble() * 2.0 - 1.0) * jitter));
        long delay = Math.max(retryAfterMs, jittered);
        delay = Math.max(0, Math.min(delay, Math.max(max, 30_000)));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("interrupted during retry backoff", e);
        }
    }

    /** 发一次 POST，返回状态码与（成功时较完整的）响应体。 */
    private HttpResult post(String endpoint, int timeoutMs, String protocol, String apiKey, byte[] payload)
            throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        // OpenCode Zen/Go：通用 SDK/HTTP 库 UA 会被降级，必须声明自己的客户端；会话头用于路由与缓存。
        conn.setRequestProperty("User-Agent", "EmonLens/0.1");
        conn.setRequestProperty("x-opencode-session", sessionId);
        if (PROTOCOL_ANTHROPIC.equals(protocol)) {
            conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("x-api-key", apiKey);
            }
        } else if (apiKey != null && !apiKey.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        }
        conn.setDoOutput(true);
        try (java.io.OutputStream out = conn.getOutputStream()) {
            out.write(payload);
        }
        int status = conn.getResponseCode();
        boolean ok = status >= 200 && status < 300;
        String bodyText = readBody(ok ? conn.getInputStream() : conn.getErrorStream(),
                ok ? 2 * 1024 * 1024 : 8 * 1024);
        return new HttpResult(status, bodyText, parseRetryAfter(conn.getHeaderField("Retry-After")));
    }

    /** Retry-After 只解析秒数；HTTP-date 形式不阻塞评估，按普通退避处理。 */
    private long parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(value.trim()) * 1000L);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private boolean mentionsTemperature(String body) {
        String lower = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return lower.contains("temperature")
                && (lower.contains("deprecated") || lower.contains("unsupported")
                || lower.contains("not supported") || lower.contains("invalid"));
    }

    private static final class HttpResult {
        final int status;
        final String body;
        final long retryAfterMs;

        HttpResult(int status, String body, long retryAfterMs) {
            this.status = status;
            this.body = body;
            this.retryAfterMs = retryAfterMs;
        }
    }

    private Map<String, Object> openAiBody(String systemPrompt, String userPrompt,
                                           java.util.List<String> imageUrls, String model, double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("temperature", temperature);
        Object userContent = userPrompt;
        if (imageUrls != null && !imageUrls.isEmpty()) {
            List<Map<String, Object>> parts = new java.util.ArrayList<>();
            parts.add(Map.of("type", "text", "text", userPrompt));
            for (String url : imageUrls) {
                parts.add(Map.of("type", "image_url", "image_url", Map.of("url", url)));
            }
            userContent = parts;
        }
        body.put("messages", List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", userContent)));
        if (tokenBudgetStore.active()) {
            body.put("max_tokens", RunTokenBudgetStore.MAX_OUTPUT_TOKENS);
        }
        return body;
    }

    private Map<String, Object> anthropicBody(String systemPrompt, String userPrompt,
                                              java.util.List<String> imageUrls, String model, double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("max_tokens", tokenBudgetStore.active()
                ? RunTokenBudgetStore.MAX_OUTPUT_TOKENS : ANTHROPIC_DEFAULT_MAX_TOKENS);
        body.put("temperature", temperature);
        // Messages API 的 system 是顶层字段，不是 messages 里的一条。
        body.put("system", systemPrompt);
        Object userContent = userPrompt;
        if (imageUrls != null && !imageUrls.isEmpty()) {
            List<Map<String, Object>> parts = new java.util.ArrayList<>();
            parts.add(Map.of("type", "text", "text", userPrompt));
            for (String url : imageUrls) {
                parts.add(Map.of("type", "image", "source", Map.of("type", "url", "url", url)));
            }
            userContent = parts;
        }
        body.put("messages", List.of(Map.of("role", "user", "content", userContent)));
        return body;
    }

    private ChatReply parseOpenAi(JsonNode root) {
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        String text;
        if (content.isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode part : content) {
                sb.append(part.path("text").asText(""));
            }
            text = sb.toString();
        } else {
            text = content.asText("");
        }
        JsonNode usage = root.path("usage");
        Integer pt = usage.path("prompt_tokens").isNumber() ? usage.path("prompt_tokens").asInt() : null;
        Integer ct = usage.path("completion_tokens").isNumber() ? usage.path("completion_tokens").asInt() : null;
        return new ChatReply(text, pt, ct);
    }

    private ChatReply parseAnthropic(JsonNode root) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                sb.append(block.path("text").asText(""));
            }
        }
        JsonNode usage = root.path("usage");
        Integer pt = usage.path("input_tokens").isNumber() ? usage.path("input_tokens").asInt() : null;
        Integer ct = usage.path("output_tokens").isNumber() ? usage.path("output_tokens").asInt() : null;
        return new ChatReply(sb.toString(), pt, ct);
    }

    /** HTTP 非 2xx：状态码 + provider 错误摘要（401 鉴权 / 404 路径 / 400 模型或协议不匹配都能直接看出来）。 */
    private String httpError(int status, String body) {
        String detail = providerMessage(body);
        return "llm http status " + status + (detail.isEmpty() ? "" : "：" + detail);
    }

    /** 兼容三种错误体：{"error":{"message":..}}、{"type":"error","error":{"type":..,"message":..}}、{"error":".."}。 */
    private String providerMessage(String body) {
        if (body == null || body.trim().isEmpty()) {
            return "";
        }
        String raw = body.trim();
        try {
            JsonNode root = objectMapper.readTree(raw);
            JsonNode err = root.path("error");
            if (err.isObject()) {
                String type = err.path("type").asText("");
                String message = err.path("message").asText("");
                String combined = (type.isEmpty() ? "" : type + ": ") + message;
                if (!combined.isEmpty()) {
                    return truncate(combined);
                }
            } else if (err.isTextual() && !err.asText().isEmpty()) {
                return truncate(err.asText());
            }
            if (root.path("message").isTextual()) {
                return truncate(root.path("message").asText());
            }
        } catch (IOException e) {
            // 非 JSON 错误页（nginx/网关 HTML）直接截断原文。
        }
        return truncate(raw);
    }

    private String readBody(InputStream stream, int limit) throws IOException {
        if (stream == null) {
            return "";
        }
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1 && out.size() < limit) {
                out.write(buffer, 0, read);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    private String truncate(String s) {
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= MAX_ERROR_BODY_CHARS
                ? oneLine : oneLine.substring(0, MAX_ERROR_BODY_CHARS) + "…";
    }

    public static String normalizeProtocol(String protocol) {
        return protocol != null && PROTOCOL_ANTHROPIC.equalsIgnoreCase(protocol.trim())
                ? PROTOCOL_ANTHROPIC : PROTOCOL_OPENAI;
    }

    /**
     * base-url → 完整 endpoint。已含 endpoint 的原样使用；只写到域名（无 path）时补 /v1。
     * 例：https://api.openai.com → https://api.openai.com/v1/chat/completions
     *     https://opencode.ai/inference/go/anthropic/v1 → .../v1/messages
     */
    public static String endpointFor(String baseUrl, String protocol) {
        String base = baseUrl.trim().replaceAll("/+$", "");
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.endsWith("/chat/completions") || lower.endsWith("/messages")
                || lower.endsWith("/responses")) {
            return base;
        }
        if (hasEmptyPath(base)) {
            base = base + "/v1";
        }
        return base + (PROTOCOL_ANTHROPIC.equals(normalizeProtocol(protocol))
                ? "/messages" : "/chat/completions");
    }

    private static boolean hasEmptyPath(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null || path.isEmpty() || "/".equals(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** DB 运行时设置逐字段优先（非空才覆盖），yml 兜底。 */
    public SysSettingService.EvalLlm effectiveConfig() {
        // 数据集运行受理时冻结的有效配置优先：同一 run 执行期间不重新读取默认模型/凭据。
        // 该快照仅存在于当前进程；重启后不恢复，PENDING run 使用新进程当时的配置。
        SysSettingService.EvalLlm frozen = credentialStore.current();
        if (frozen != null) {
            return frozen;
        }
        EmonLensProperties.Eval.Llm yml = properties.getEval().getLlm();
        SysSettingService.EvalLlm db = sysSettingService.getEvalLlm();
        SysSettingService.EvalLlm cfg = new SysSettingService.EvalLlm();
        // 协议与 base-url 视为同一组端点配置：DB 未配置端点时整体回退 yml，避免"yml 的 anthropic 端点 + DB 的 openai 协议"错配。
        cfg.setProtocol(db != null && db.configured() && db.getProtocol() != null && !db.getProtocol().isEmpty()
                ? normalizeProtocol(db.getProtocol()) : normalizeProtocol(yml.getProtocol()));
        cfg.setBaseUrl(db != null && db.getBaseUrl() != null && !db.getBaseUrl().isEmpty()
                ? db.getBaseUrl() : yml.getBaseUrl());
        cfg.setApiKey(db != null && db.getApiKey() != null && !db.getApiKey().isEmpty()
                ? db.getApiKey() : yml.getApiKey());
        cfg.setModel(db != null && db.getModel() != null && !db.getModel().isEmpty()
                ? db.getModel() : yml.getModel());
        cfg.setTimeoutMs(db != null && db.getTimeoutMs() != null ? db.getTimeoutMs() : (long) yml.getTimeoutMs());
        return cfg;
    }

    private String firstNonBlank(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isEmpty()) {
                return c;
            }
        }
        return "";
    }
}
