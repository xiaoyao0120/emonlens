package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.engine.eval.LlmUnavailableException;

/**
 * 手工走查中验证过、但尚无自动化契约的业务不变量：
 * 1. cachedInputTokens 在 trace 详情与 observations 两个接口都透出（用户关心的缓存命中口径）；
 * 2. ingest key 的 name 能存取往返（签发→列表），用于识别该吊销哪把；
 * 3. Playground 在 LLM 不可用时返回结构化降级（LLM_UNAVAILABLE + 请求模型 + 渲染后 Prompt），
 *    而不是笼统 500——失败态也必须可诊断。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-contract;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class ContractCoverageTest extends AbstractIntegrationTest {

    private static final String AGENT = "contract-agent";

    @org.springframework.boot.test.context.TestConfiguration
    static class UnavailableLlmConfig {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        LlmClient unavailableLlmClient() {
            return (systemPrompt, userPrompt, model, temperature) -> {
                throw new LlmUnavailableException("Connection refused");
            };
        }
    }

    @Test
    void evaluatorDeleteRemovesDefinitionAndItsEvalResults() throws Exception {
        // 此前评估器只能启用/停用，没有删除入口，试错阶段的定义会永久留在列表里。
        // 契约：DELETE 成功定义与历史评估结果一起消失（同注解队列/数据集的级联策略）。
        Map<String, Object> ev = new HashMap<>();
        ev.put("evaluatorKey", "contract-del-eval");
        ev.put("type", "EXPRESSION");
        ev.put("spec", Map.of("passWhen", "error_count == 0"));
        assertThat(readTree(postApi("/api/v1/evaluators", ev)).path("code").asInt()).isZero();

        String tid = "contract-evaldel-trace";
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, tid, "root", null, "agent", "invoke_agent", null, null, 1, 5));
        post("/api/v1/ingest/events", spans);
        await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + tid);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("summary").path("status").asText()
                        .equals("COMPLETED"),
                ASYNC_TIMEOUT_MS);

        String evaluatorId = null;
        for (JsonNode r : readSafely(get("/api/v1/evaluators?pageNumber=1&pageSize=50"))
                .path("data").path("records")) {
            if ("contract-del-eval".equals(r.path("evaluatorKey").asText())) {
                evaluatorId = r.path("id").asText();
            }
        }
        assertThat(evaluatorId).isNotNull();
        // 先跑一次真实评估落库，验证删除时结果一并清除
        assertThat(readTree(postApi("/api/v1/evaluators/" + evaluatorId + "/test",
                Map.of("traceId", tid))).path("code").asInt()).isZero();
        assertThat(readTree(postApi("/api/v1/evaluations/run",
                Map.of("evaluatorId", evaluatorId, "traceIds", List.of(tid)))).path("code").asInt())
                .isZero();

        assertThat(readTree(deleteApi("/api/v1/evaluators/" + evaluatorId)).path("code").asInt())
                .isZero();

        String after = get("/api/v1/evaluators?pageNumber=1&pageSize=50");
        assertThat(readSafely(after).path("data").path("records").toString())
                .doesNotContain("contract-del-eval");
        // 评估结果已级联清理
        String results = get("/api/v1/eval-results?pageNumber=1&pageSize=50&evaluatorId=" + evaluatorId);
        assertThat(readSafely(results).path("data").path("total").asInt()).isZero();
    }

    @Test
    void expressionEvaluatorReportsUnknownVariableInsteadOfInternalError() throws Exception {
        // 手工走查时踩到：写错 SpEL 变量名（如 output.length() > 0）只回 50000 internal error，
        // 配置阶段完全无从自查。契约：必须回 40000 + 可用变量清单。
        Map<String, Object> ev = new HashMap<>();
        ev.put("evaluatorKey", "contract-bad-expr");
        ev.put("type", "EXPRESSION");
        ev.put("spec", Map.of("passWhen", "output.length() > 0"));
        assertThat(readTree(postApi("/api/v1/evaluators", ev)).path("code").asInt()).isZero();

        String tid = "contract-badexpr-trace";
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, tid, "root", null, "agent", "invoke_agent", null, null, 1, 5));
        post("/api/v1/ingest/events", spans);
        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + tid);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("summary").path("status").asText()
                        .equals("COMPLETED"),
                ASYNC_TIMEOUT_MS);

        String evaluators = get("/api/v1/evaluators?pageNumber=1&pageSize=50");
        String evaluatorId = null;
        for (JsonNode r : readSafely(evaluators).path("data").path("records")) {
            if ("contract-bad-expr".equals(r.path("evaluatorKey").asText())) {
                evaluatorId = r.path("id").asText();
            }
        }
        assertThat(evaluatorId).isNotNull();

        String resp = postApi("/api/v1/evaluators/" + evaluatorId + "/test", Map.of("traceId", tid));
        JsonNode j = readSafely(resp);
        assertThat(j.path("code").asInt()).isEqualTo(40000);   // ErrorCode.BAD_REQUEST
        assertThat(j.path("message").asText()).contains("error_count");
        assertThat(j.path("message").asText()).contains("可用变量");
    }

    @Test
    void cachedTokensAreExposedByBothTraceAndObservationApis() throws Exception {
        String traceId = "contract-trace-1";
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, traceId, "root", null, "agent", "invoke_agent", null, null, 1, 5));
        Map<String, Object> llm = span(AGENT, traceId, "llm-1", "root", "llm", "call_model", null,
                "gpt-test", 2, 4);
        llm.put("inputTokens", 1000);
        llm.put("outputTokens", 200);
        llm.put("cachedInputTokens", 640);
        spans.add(llm);
        post("/api/v1/ingest/events", spans);

        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                // 同一批 ingest 的两个 span 可能被微批切成两次落库：等两条都可见再断言，
                // 否则会在“trace 已 COMPLETED 但 llm span 还没装配”时假失败（高负载下更常见）。
                json -> readSafely(json).path("data").path("summary").path("status").asText()
                        .equals("COMPLETED")
                        && readSafely(json).path("data").path("spans").toString().contains("\"llm-1\""),
                ASYNC_TIMEOUT_MS);
        JsonNode llmSpan = readSafely(detail).path("data").path("spans");
        assertThat(llmSpan.toString()).contains("\"cachedInputTokens\":640");

        String obs = await(
                () -> {
                    try {
                        return get("/api/v1/observations?traceId=" + traceId + "&spanType=llm");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode rec = readSafely(obs).path("data").path("records").get(0);
        assertThat(rec.path("cachedInputTokens").asLong()).isEqualTo(640L);
        assertThat(rec.path("inputTokens").asLong()).isEqualTo(1000L);
    }

    @Test
    void ingestKeyNameRoundTripsThroughIssueAndList() throws Exception {
        String created = postApi("/api/v1/ingest-keys", Map.of("name", "ci-key"));
        assertThat(readTree(created).path("code").asInt()).isZero();
        String plain = readTree(created).path("data").path("key").asText();
        assertThat(plain).startsWith("tk-");
        assertThat(readTree(created).path("data").path("name").asText()).isEqualTo("ci-key");

        String list = get("/api/v1/ingest-keys");
        JsonNode keys = readTree(list).path("data");
        assertThat(keys.toString()).contains("ci-key");
        // 明文只在签发响应里，列表必须只给掩码
        assertThat(keys.toString()).doesNotContain(plain);
        // 按 name 精确定位（而不是取列表位置）：name 字段的意义就是可识别
        JsonNode mine = null;
        for (JsonNode k : keys) {
            if ("ci-key".equals(k.path("name").asText())) {
                mine = k;
            }
        }
        assertThat(mine).as("name 应能在列表中定位到该 key").isNotNull();
        assertThat(mine.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(readTree(deleteApi("/api/v1/ingest-keys/" + mine.path("id").asText()))
                .path("code").asInt()).isZero();
        // 吊销后列表里该 key 状态翻转
        String after = get("/api/v1/ingest-keys");
        JsonNode revoked = null;
        for (JsonNode k : readTree(after).path("data")) {
            if ("ci-key".equals(k.path("name").asText())) {
                revoked = k;
            }
        }
        assertThat(revoked).isNotNull();
        assertThat(revoked.path("status").asText()).isEqualTo("REVOKED");
    }

    @Test
    void playgroundDegradesStructuredWhenLlmUnavailable() throws Exception {
        Map<String, Object> req = new HashMap<>();
        req.put("prompt", "你好 {{name}}");
        req.put("variables", Map.of("name", "王小明"));
        req.put("model", "gpt-4o-mini");
        req.put("temperature", 0.2);

        JsonNode result = readTree(postApi("/api/v1/playground/run", req)).path("data");
        // 降级必须是结构化结果：状态 + 原因 + 请求的模型 + 渲染后的 Prompt（可诊断），而不是 500
        assertThat(result.path("status").asText()).isEqualTo("LLM_UNAVAILABLE");
        assertThat(result.path("message").asText()).contains("Connection refused");
        assertThat(result.path("model").asText()).isEqualTo("gpt-4o-mini");
        assertThat(result.path("renderedPrompt").asText()).isEqualTo("你好 王小明");
    }

    @Test
    void traceSearchFiltersByModelForUsageDrilldown() throws Exception {
        // 用量成本页点模型行会跳 /traces?modelNames=xxx：筛选契约必须成立
        for (int i = 0; i < 2; i++) {
            String tid = "contract-model-" + i;
            Map<String, Object> llm = span(AGENT, tid, "llm-1", "root", "llm", "call_model", null,
                    i == 0 ? "gpt-drill-a" : "gpt-drill-b", 2, 4);
            List<Map<String, Object>> spans = new ArrayList<>();
            spans.add(span(AGENT, tid, "root", null, "agent", "invoke_agent", null, null, 1, 5));
            spans.add(llm);
            post("/api/v1/ingest/events", spans);
        }
        String resp = await(
                () -> {
                    try {
                        return postApi("/api/v1/traces/search", Map.of(
                                "agentKeys", List.of(AGENT), "modelNames", List.of("gpt-drill-a"),
                                "pageNumber", 1, "pageSize", 20));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("total").asLong() >= 1,
                ASYNC_TIMEOUT_MS);
        JsonNode recs = readSafely(resp).path("data").path("records");
        assertThat(recs.size()).isEqualTo(1);
        assertThat(recs.get(0).path("traceId").asText()).isEqualTo("contract-model-0");
    }

    @Test
    void ingestRejectsNegativeTokensButAcceptsCleanSpecials() throws Exception {
        // 脏 token 会拉低用量统计：整条拒绝；特殊字符（引号/换行/null/emoji）必须原样可存
        String tid = "contract-boundary";
        List<Map<String, Object>> bad = new ArrayList<>();
        bad.add(span(AGENT, tid, "root", null, "agent", "invoke_agent", null, null, 1, 5));
        Map<String, Object> neg = span(AGENT, tid, "llm-1", "root", "llm", "call_model", null, "gpt-test", 2, 4);
        neg.put("inputTokens", -500);
        neg.put("outputTokens", 200);
        bad.add(neg);
        String resp = post("/api/v1/ingest/events", bad);
        JsonNode d = readTree(resp).path("data");
        assertThat(d.path("acceptedCount").asInt()).isEqualTo(1);   // 好的收，坏的拒
        assertThat(d.path("rejected").toString()).contains("non-negative");

        // 特殊字符不碰脏数据：原样落库（JSON 转义由存储层负责）
        List<Map<String, Object>> good = new ArrayList<>();
        good.add(span(AGENT, "contract-chars", "root", null, "agent", "invoke_agent", null, null, 1, 5));
        good.get(0).put("input", "a\"b'c\\d\ne\u0000f😀");
        assertThat(readTree(post("/api/v1/ingest/events", good)).path("code").asInt()).isZero();
        // 异步装配：必须等 span 真落库再取 detail，否则 spans 为空会 NPE
        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/contract-chars");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("spans").size() >= 1,
                ASYNC_TIMEOUT_MS);
        assertThat(readTree(detail).path("data").path("spans").get(0).path("inputText").asText())
                .contains("😀");
    }

    @Test
    void spaForwardBacksDeepLinksWithoutHijackingStaticAssets() throws Exception {
        // 深链接（刷新/书签/手输）必须落到 SPA 入口交给 vue-router：
        // 前端 router 自带 :pathMatch(.*)* 兜底重定向到 /dashboard，服务端此前只认白名单，
        // 拼错或过期的一级链接返回 Spring Whitelabel 404，前端兜底形同虚设。
        assertThat(forwardedTo("/users")).isEqualTo("/index.html");
        assertThat(forwardedTo("/sessions/some-session-id")).isEqualTo("/index.html");
        assertThat(forwardedTo("/users-insight")).isEqualTo("/index.html");
        // 带扩展名的是静态资源：兜底不能把它们劫走，否则 .js/.css 全变 404 → 前端白屏
        assertThat(forwardedTo("/favicon.png")).isNull();
        assertThat(forwardedTo("/assets/nonexistent-chunk.js")).isNull();
    }

    /** MockMvc 下 forward 的静态资源不会渲染正文，只能看 forward 目标；未 forward 时为 null。 */
    private String forwardedTo(String path) throws Exception {
        MockHttpServletResponse response = mockMvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path))
                .andReturn().getResponse();
        return response.getForwardedUrl();
    }

    @Test
    void costEstimationDiscountsCachedInputTokens() throws Exception {
        // 用户反馈：缓存命中被按全价计费，成本虚高数倍。
        // in=1000（cached=900）/ out=20；输入 5/M、输出 2/M、缓存未配置→输入价 10% = 0.5/M
        // 期望：(1000-900)*5 + 900*0.5 + 20*2 = 990 / 1M = 0.00099（全价会是 0.00504）
        // 价格必须先于 span 入库：estimateCost 只在首次 ingest 时估算
        Map<String, Object> price = new HashMap<>();
        price.put("model", "gpt-cost-test");
        price.put("inputPerMillion", 5.0);
        price.put("outputPerMillion", 2.0);
        assertThat(readTree(postApi("/api/v1/model-prices", price)).path("code").asInt()).isZero();

        String tid = "contract-cost-1";
        Map<String, Object> llm = span(AGENT, tid, "llm-1", "root", "llm", "call_model", null,
                "gpt-cost-test", 2, 4);
        llm.put("inputTokens", 1000);
        llm.put("outputTokens", 20);
        llm.put("cachedInputTokens", 900);
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, tid, "root", null, "agent", "invoke_agent", null, null, 1, 5));
        spans.add(llm);
        post("/api/v1/ingest/events", spans);

        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + tid);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    for (JsonNode x : readSafely(json).path("data").path("spans")) {
                        if ("llm-1".equals(x.path("spanId").asText())) {
                            return x.path("costUsd").asDouble(0) > 0;
                        }
                    }
                    return false;
                },
                ASYNC_TIMEOUT_MS);
        BigDecimal cost = null;
        for (JsonNode x : readSafely(detail).path("data").path("spans")) {
            if ("llm-1".equals(x.path("spanId").asText())) {
                cost = x.has("costUsd") ? new BigDecimal(x.path("costUsd").asText()) : null;
            }
        }
        assertThat(cost)
                .as("缓存命中部分必须按缓存价（默认输入价 10%）计费，而非全价")
                .isBetween(new BigDecimal("0.00098"), new BigDecimal("0.001"));
    }

    @Test
    void priceCreatedAfterIngestBackfillsHistoricalCost() throws Exception {
        // 旧行为：价格后配 → 历史 span 成本恒 0（估算只在首次 ingest 发生）。
        // 新行为：创建/改价即重算该模型全部已有 llm span + trace 汇总。
        String tid = "contract-late-price";
        Map<String, Object> llm = span(AGENT, tid, "llm-1", "root", "llm", "call_model", null,
                "gpt-late-price", 2, 4);
        llm.put("inputTokens", 1000);
        llm.put("outputTokens", 20);
        llm.put("cachedInputTokens", 900);
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span(AGENT, tid, "root", null, "agent", "invoke_agent", null, null, 1, 5));
        spans.add(llm);
        post("/api/v1/ingest/events", spans);

        // 入库时无价格 → 成本为 0
        String before = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + tid);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("summary").path("status").asText()
                        .equals("COMPLETED"),
                ASYNC_TIMEOUT_MS);
        assertThat(readSafely(before).path("data").path("summary").path("totalCostUsd").asDouble())
                .isZero();

        // 后配价格：5/2/缓存默认 0.5 → (100-900已扣)*5 + 900*0.5 + 20*2 = 990/1M
        Map<String, Object> price = new HashMap<>();
        price.put("model", "gpt-late-price");
        price.put("inputPerMillion", 5.0);
        price.put("outputPerMillion", 2.0);
        assertThat(readTree(postApi("/api/v1/model-prices", price)).path("code").asInt()).isZero();

        String after = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + tid);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> readSafely(json).path("data").path("summary").path("totalCostUsd").asDouble() > 0,
                ASYNC_TIMEOUT_MS);
        assertThat(readSafely(after).path("data").path("summary").path("totalCostUsd").asDouble())
                .as("价格后配必须回溯重算历史成本").isBetween(0.00098, 0.001);
    }

    private JsonNode readSafely(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
