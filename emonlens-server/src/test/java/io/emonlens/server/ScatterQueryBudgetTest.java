package io.emonlens.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.Resource;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

/**
 * 散点（实验排行）× 查询次数预算。
 *
 * <p>散点为窗口内每个 run 构建一个点；实现里每个 run 都要重新取 items，再走一次 costReport
 * （costReport 内部又会 requireRun + itemsOf），于是查询数随历史 run 数线性增长——活跃 run 有限流，
 * 但历史 run 无限累积。这里用 MyBatis 拦截器把真实语句数数出来，不靠"看起来没问题"。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-scatter-budget;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
@Import(ScatterQueryBudgetTest.CountConfig.class)
class ScatterQueryBudgetTest extends AbstractIntegrationTest {

    /** 统计 query/update 次数；只包 Executor，避免把参数/结果集处理也计入语句数。 */
    @Intercepts({@org.apache.ibatis.plugin.Signature(
            type = Executor.class,
            method = "query",
            args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class,
                    org.apache.ibatis.session.RowBounds.class,
                    org.apache.ibatis.session.ResultHandler.class}),
            @org.apache.ibatis.plugin.Signature(
                    type = Executor.class,
                    method = "update",
                    args = {org.apache.ibatis.mapping.MappedStatement.class, Object.class})})
    static class CountingInterceptor implements Interceptor {
        final AtomicInteger statements = new AtomicInteger();

        @Override
        public Object plugin(Object target) {
            return target instanceof Executor ? Plugin.wrap(target, this) : target;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String name = invocation.getMethod().getName();
            if ("query".equals(name) || "update".equals(name)) {
                statements.incrementAndGet();
            }
            return invocation.proceed();
        }
    }

    @TestConfiguration
    static class CountConfig {
        @Bean
        @Primary
        CountingInterceptor countingInterceptor() {
            return new CountingInterceptor();
        }
    }

    private static final String WS = "ws-default";
    private static final int RUNS = 24;

    @Resource
    private ApplicationContext context;
    @Resource
    private DatasetRunMapper datasetRunMapper;
    @Resource
    private DatasetRunItemMapper datasetRunItemMapper;

    @Test
    void scatterQueryCountDoesNotGrowWithRunCount() throws Exception {
        JsonNode created = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", "scatter-budget")));
        assertThat(created.path("code").asInt()).as("dataset: %s", created).isZero();
        String datasetId = created.path("data").path("id").asText();
        for (int i = 0; i < RUNS; i++) {
            insertCompletedRun(datasetId, "fp-" + i, "cand-" + i);
        }

        CountingInterceptor counter = context.getBean(CountingInterceptor.class);
        counter.statements.set(0);
        JsonNode scatter = readTree(get("/api/v1/datasets/" + datasetId + "/runs/scatter?windowDays=7"));
        int statements = counter.statements.get();

        assertThat(scatter.path("code").asInt()).as("scatter: %s", scatter).isZero();
        assertThat(scatter.path("data").path("points")).hasSize(RUNS);
        // 预算：与 run 数无关的固定开销（鉴权、数据集校验、批量取 items 等）。
        // 每个 run 常数条查询的写法会到 70 条以上，卡在 12 以内同时也挡住退回 N+1。
        assertThat(statements)
                .as("散点查询数不应随 run 数增长（%d 个 run 用了 %d 条语句）", RUNS, statements)
                .isLessThanOrEqualTo(12);
    }

    private void insertCompletedRun(String datasetId, String fingerprint, String label) {
        DatasetRunEntity run = new DatasetRunEntity();
        run.setId("scatter-run-" + fingerprint);
        run.setWorkspaceId(WS);
        run.setDatasetId(datasetId);
        run.setRunMode("TRACE");
        run.setCandidateLabel(label);
        run.setCandidateFingerprint(fingerprint);
        run.setEvaluatorId("scatter-evaluator");
        run.setEvaluatorKey("scatter-expr");
        run.setStatus(DatasetRunEntity.STATUS_COMPLETED);
        run.setItemCount(1);
        run.setPassCount(1);
        run.setFailCount(0);
        run.setUnknownCount(0);
        run.setErrorCount(0);
        run.setIncompleteCount(0);
        run.setAttempt(1);
        run.setStartedAt(LocalDateTime.now().minusMinutes(5));
        run.setFinishedAt(LocalDateTime.now().minusMinutes(4));
        run.setRequestKey("scatter-budget-" + fingerprint);
        datasetRunMapper.insert(run);

        // 每个 run 放一条 item，让取 items 有真实工作量（否则会显得"没有成本"）
        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setId("scatter-item-" + fingerprint);
        item.setRunId(run.getId());
        item.setItemId("scatter-src-" + fingerprint);

        item.setLabel("PASS");
        item.setResultDetailsJson("{\"status\":\"SUCCEEDED\",\"latencyMs\":120}");
        datasetRunItemMapper.insert(item);
    }
}
