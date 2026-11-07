package io.emonlens.server;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Resource;

import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 集成测试基类：MockMvc + ingest key 常量 + 轮询等待工具。
 * 测试统一使用内存 H2：driver 与 schema 初始化在此统一覆盖（子类仅覆盖各自库名 url），
 * 保证测试自洽，不依赖 application.yml 当前指向 MySQL 还是 H2。
 * application.yml 默认 MySQL；H2 只在 demo profile / 测试覆盖下使用。
 */
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "spring.sql.init.platform=h2",
        "spring.sql.init.schema-locations=classpath:schema-h2.sql",
        "emonlens.notify.allow-private-webhook-targets=true"})
public abstract class AbstractIntegrationTest {

    static final String INGEST_KEY = "dev-trust-key-001";

    /**
     * 等待异步条件（装配/投递/推送）的统一预算。
     * 这些条件正常都在一秒内完成，超时值只在真的出问题时才起作用——而机器高负载
     * （并行跑 Docker/mvn/dev server）时后台线程可能被饿死：实测 40s 不够，60s 也被打穿过一次
     * （JudgeUpgradeFlowTest 在全量并发下等 ingest 收口超时，单独跑 3/3 全绿）。
     * 取 120s：不影响 happy path 耗时（条件满足就立刻返回），把假阴性彻底消掉。
     * <b>若 120s 仍超时，说明是真积压，应当去查 ingest 队列而不是再调这个数。</b>
     */
    protected static final long ASYNC_TIMEOUT_MS = 120000;

    @Resource
    protected MockMvc mockMvc;

    @Resource
    protected ObjectMapper objectMapper;

    private String cachedAdminToken;

    /** 控制面请求统一携带 admin JWT（首用真实登录，按上下文缓存） */
    protected String adminAuth() {
        if (cachedAdminToken == null) {
            try {
                String body = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"admin\",\"password\":\"123456\"}"))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
                if (node.path("code").asInt() != 0) {
                    throw new IllegalStateException("admin login failed: " + body);
                }
                cachedAdminToken = node.path("data").path("token").asText();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        return "Bearer " + cachedAdminToken;
    }

    protected String post(String url, Object body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url)
                        .header("Authorization", adminAuth())
                        .header("X-Trust-Key", INGEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected String get(String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.get(url)
                        .header("Authorization", adminAuth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected String postApi(String url, Object body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url)
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 删除类接口（DELETE /{id}，无请求体） */
    protected String deleteApi(String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.delete(url)
                        .header("Authorization", adminAuth()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected String putApi(String url, Object body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put(url)
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    protected String patchApi(String url, Object body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.patch(url)
                        .header("Authorization", adminAuth())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** 轮询等待异步链路完成，避免固定 sleep 竞态 */
    protected <T> T await(Supplier<T> probe, java.util.function.Predicate<T> done, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        T value = probe.get();
        while (!done.test(value)) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("condition not met within " + timeoutMs + "ms, last value: " + value);
            }
            Thread.sleep(200);
            value = probe.get();
        }
        return value;
    }

    /** 构造一条带结束时间的 span */
    protected Map<String, Object> span(String agentKey, String traceId, String spanId, String parentSpanId,
                                       String spanType, String name, String toolName, String modelName,
                                       int startSecond, int endSecond) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("traceId", traceId);
        map.put("spanId", spanId);
        if (parentSpanId != null) {
            map.put("parentSpanId", parentSpanId);
        }
        map.put("agentKey", agentKey);
        map.put("agentVersion", "v1");
        map.put("spanType", spanType);
        map.put("spanName", name);
        if (toolName != null) {
            map.put("toolName", toolName);
        }
        if (modelName != null) {
            map.put("modelName", modelName);
        }
        map.put("startTime", "2026-08-30T10:00:" + String.format("%02d", startSecond));
        map.put("endTime", "2026-08-30T10:00:" + String.format("%02d", endSecond));
        map.put("inputTokens", 100);
        map.put("outputTokens", 200);
        return map;
    }

    protected JsonNode readTree(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    protected List<JsonNode> dataRecords(JsonNode result) {
        List<JsonNode> records = new ArrayList<>();
        result.path("data").path("records").forEach(records::add);
        return records;
    }
}
