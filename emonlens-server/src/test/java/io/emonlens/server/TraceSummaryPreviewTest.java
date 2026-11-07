package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Trace 列表摘要（输入/输出摘要、模型名）：
 * 1) LLM span 正常填充三个字段；
 * 2) 纯 agent/tool trace（CLI 桥接无 LLM span）回退到根 span 输入输出，模型名取任一 span。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-preview;MODE=MySQL;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class TraceSummaryPreviewTest extends AbstractIntegrationTest {

    @Test
    void llmSpanFillsPreviewsAndModel() throws Exception {
        String traceId = "preview-trace-llm";
        List<Map<String, Object>> spans = new ArrayList<>();
        spans.add(span("preview-agent", traceId, "root", null, "agent", "invoke", null, null, 1, 20));
        Map<String, Object> llm = span("preview-agent", traceId, "llm-1", "root", "llm",
                "assistant_turn", null, "gpt-4o-mini", 2, 15);
        llm.put("input", "工单 #1024 的处理进度是多少？");
        llm.put("output", "工单 #1024 已进入二线处理");
        spans.add(llm);
        post("/api/v1/ingest/events", spans);

        JsonNode summary = awaitPreview(traceId);
        assertThat(summary.path("inputPreview").asText())
                .as("summary: %s", summary)
                .isEqualTo("工单 #1024 的处理进度是多少？");
        assertThat(summary.path("outputPreview").asText()).isEqualTo("工单 #1024 已进入二线处理");
        assertThat(summary.path("modelName").asText()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void agentOnlyTraceFallsBackToRootIo() throws Exception {
        String traceId = "preview-trace-agent";
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> root = span("preview-agent", traceId, "root", null, "agent",
                "codex_session", null, null, 1, 20);
        root.put("input", "修复登录页样式");
        root.put("output", "已完成修复");
        spans.add(root);
        Map<String, Object> tool = span("preview-agent", traceId, "tool-1", "root", "tool",
                "Bash", "Bash", null, 5, 10);
        tool.put("input", "npm run build");
        spans.add(tool);
        post("/api/v1/ingest/events", spans);

        JsonNode summary = awaitPreview(traceId);
        // 输入优先根 span（用户提示词），输出取最后一个带输出的 span，模型无则不填
        assertThat(summary.path("inputPreview").asText()).isEqualTo("修复登录页样式");
        assertThat(summary.path("outputPreview").asText()).isEqualTo("已完成修复");
        assertThat(summary.path("modelName").asText()).isEmpty();
    }

    @Test
    void lateLlmSpanOverridesFallbackPreview() throws Exception {
        // 复刻 CLI 桥接真实顺序：工具批先到（懒创建 RUNNING summary，触发 fallback），
        // stop 批（llm span + root）后到，llm 数据必须覆盖 fallback 值
        String traceId = "preview-trace-late-llm";
        List<Map<String, Object>> toolBatch = new ArrayList<>();
        Map<String, Object> tool = span("preview-agent", traceId, "tool-1", "root", "tool",
                "Bash", "Bash", null, 5, 10);
        tool.put("input", "ls /tmp");
        toolBatch.add(tool);
        post("/api/v1/ingest/events", toolBatch);

        List<Map<String, Object>> stopBatch = new ArrayList<>();
        Map<String, Object> llm = span("preview-agent", traceId, "llm-1", "root", "llm",
                "assistant_turn", null, "gpt-4o-mini", 8, 18);
        llm.put("input", "E2E:帮我核对工单进度");
        llm.put("output", "工单已升级至二线团队");
        stopBatch.add(llm);
        Map<String, Object> root = span("preview-agent", traceId, "root", null, "agent",
                "codex_session", null, null, 1, 20);
        root.put("input", "E2E:帮我核对工单进度");
        root.put("output", "工单已升级至二线团队");
        stopBatch.add(root);
        post("/api/v1/ingest/events", stopBatch);

        JsonNode summary = awaitPreview(traceId);
        assertThat(summary.path("inputPreview").asText()).isEqualTo("E2E:帮我核对工单进度");
        assertThat(summary.path("outputPreview").asText()).isEqualTo("工单已升级至二线团队");
        assertThat(summary.path("modelName").asText()).isEqualTo("gpt-4o-mini");
    }

    @Test
    void ingestWithUserIdAggregatesIntoUsersInsight() throws Exception {
        // 上报时带 userId → trace_summary.user_id → /api/v1/users 聚合可见
        String traceId = "preview-trace-user";
        List<Map<String, Object>> spans = new ArrayList<>();
        Map<String, Object> root = span("preview-agent", traceId, "root", null, "agent",
                "invoke", null, null, 1, 10);
        root.put("userId", "user-e2e-1");
        root.put("sessionId", "sess-e2e-1");
        root.put("output", "完成");
        spans.add(root);
        post("/api/v1/ingest/events", spans);

        awaitSummary(traceId);

        String usersJson = get("/api/v1/users?pageNumber=1&pageSize=20");
        JsonNode records = readTree(usersJson).path("data").path("records");
        JsonNode hit = null;
        for (JsonNode r : records) {
            if ("user-e2e-1".equals(r.path("userId").asText())) {
                hit = r;
                break;
            }
        }
        assertThat(hit).as("users list: %s", usersJson).isNotNull();
        assertThat(hit.path("traceCount").asInt()).isEqualTo(1);
        assertThat(hit.path("sessionCount").asInt()).isEqualTo(1);

        JsonNode detail = readTree(get("/api/v1/users/user-e2e-1/detail"));
        assertThat(detail.path("data").path("userId").asText()).isEqualTo("user-e2e-1");
        assertThat(detail.path("data").path("traceCount").asInt()).isEqualTo(1);
    }

    /**
     * 等 preview 真正落库再断言。
     *
     * <p>两个 span 可能分两批装配：含 root 的那批先把 status 置为 COMPLETED，llm span 的数据到下一批才补上 preview。
     * 这是正常的最终一致窗口，所以等待条件必须等于被断言的那个状态——只等 COMPLETED 会在高负载下偶发失败
     * （实测全量跑时 inputPreview 取到空串，单跑必过）。</p>
     */
    private JsonNode awaitPreview(String traceId) throws InterruptedException {
        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    try {
                        JsonNode s = readTree(json).path("data").path("summary");
                        return "COMPLETED".equals(s.path("status").asText())
                                && !s.path("inputPreview").asText().isEmpty()
                                && !s.path("outputPreview").asText().isEmpty();
                    } catch (Exception e) {
                        return false;
                    }
                },
                ASYNC_TIMEOUT_MS);
        try {
            return readTree(detail).path("data").path("summary");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode awaitSummary(String traceId) throws InterruptedException {
        String detail = await(
                () -> {
                    try {
                        return get("/api/v1/traces/" + traceId);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                },
                json -> {
                    try {
                        return readTree(json).path("data").path("summary").path("status").asText()
                                .equals("COMPLETED");
                    } catch (Exception e) {
                        return false;
                    }
                },
                ASYNC_TIMEOUT_MS);
        try {
            return readTree(detail).path("data").path("summary");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
