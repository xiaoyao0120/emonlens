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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.JsonNode;

import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.QualityGateEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.QualityGateMapper;

/**
 * 查询次数预算：凡是"逐项出一点/一行"的视图，都不得随规模线性增加查询次数。
 *
 * <p>这类视图最自然的写法是逐项取 items、逐项做成本分账，查询数就跟着条目数走。已经出现过三次：
 * 散点（24 个 run 84 条）、leaderboard（24 个候选 81 条）、Gate 历史（每条 Gate 重新取一次 items，
 * 上限 50 条）。本类用 MyBatis 拦截器数真实语句数，把"不得随规模增长"钉成断言。</p>
 *
 * <p>断言方式是「小规模 vs 大规模的增长量」，不是绝对条数：绝对基线会被鉴权、数据集校验、连接池、
 * 语句解析等固定开销污染（同一份代码实测 12/16/7/10），卡它等于卡错对象。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:emonlens-insight-budget;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "emonlens.eval.auto-run-enabled=false",
        "emonlens.dataset-run.poll-interval-ms=100",
        "emonlens.security.login-ip-limit-per-minute=0"})
@AutoConfigureMockMvc
@Import(QueryBudgetTest.CountConfig.class)
class QueryBudgetTest extends AbstractIntegrationTest {

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
        /** 记录语句 id：断言失败时要能看清"多出来的那几条"到底是什么，而不是盲调阈值。 */
        final java.util.List<String> ids = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        @Override
        public Object plugin(Object target) {
            return target instanceof Executor ? Plugin.wrap(target, this) : target;
        }

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            String name = invocation.getMethod().getName();
            if ("query".equals(name) || "update".equals(name)) {
                statements.incrementAndGet();
                Object first = invocation.getArgs().length > 0 ? invocation.getArgs()[0] : null;
                if (first instanceof org.apache.ibatis.mapping.MappedStatement) {
                    ids.add(((org.apache.ibatis.mapping.MappedStatement) first).getId());
                }
            }
            return invocation.proceed();
        }

        void reset() {
            statements.set(0);
            ids.clear();
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
    private static final int LARGE = 24;
    /** 小规模样本：与 LARGE 的差值就是"是否随规模增长"的证据。 */
    private static final int SMALL = 4;

    @Resource
    private ApplicationContext context;
    @Resource
    private DatasetRunMapper datasetRunMapper;
    @Resource
    private DatasetRunItemMapper datasetRunItemMapper;
    @Resource
    private QualityGateMapper gateMapper;

    @Test
    void scatterQueryCountDoesNotGrowWithRunCount() throws Exception {
        String datasetId = createDataset("scatter-budget");
        warmUp(datasetId, "runs/scatter?windowDays=7");
        int small = measureScatter(datasetId, 0, SMALL);
        int large = measureScatter(datasetId, SMALL, LARGE - SMALL);
        assertGrowth("散点", small, SMALL, large, LARGE);
    }

    /**
     * leaderboard 按候选分组：每个分组都要取 items 并做一次成本分账。
     * 分组数并不小——矩阵实验每一列就是一个候选。这里让每个 run 各自成为一个候选，取最坏情况。
     */
    @Test
    void leaderboardQueryCountDoesNotGrowWithCandidateCount() throws Exception {
        String datasetId = createDataset("leaderboard-budget");
        warmUp(datasetId, "leaderboard");
        int small = measureLeaderboard(datasetId, 0, SMALL);
        int large = measureLeaderboard(datasetId, SMALL, LARGE - SMALL);
        assertGrowth("leaderboard", small, SMALL, large, LARGE);
    }

    /**
     * Gate 历史：同一 run 最多返回 50 条 Gate，每条都带 verification，而 verification 需要 items。
     * 逐条取 items 会让这个审计端点变成 50 次查询；items 与 Gate 条数无关，取一次即可。
     */
    @Test
    void gateHistoryQueryCountDoesNotGrowWithGateCount() throws Exception {
        String datasetId = createDataset("gate-history-budget");
        insertCompletedRun(datasetId, "gh-fp-0", "gh-cand-0");
        String runId = datasetRunMapper.selectList(Wrappers.<DatasetRunEntity>lambdaQuery()
                        .eq(DatasetRunEntity::getDatasetId, datasetId))
                .stream().map(DatasetRunEntity::getId).findFirst().orElseThrow();
        insertGates(datasetId, runId, 0, SMALL);
        readTree(get("/api/v1/quality-gates/history?runId=" + runId)); // 预热
        int small = measureHistory(runId, SMALL);
        insertGates(datasetId, runId, SMALL, LARGE - SMALL);
        int large = measureHistory(runId, LARGE);
        assertGrowth("Gate 历史", small, SMALL, large, LARGE);
    }

    private void insertGates(String datasetId, String runId, int from, int count) {
        for (int i = from; i < from + count; i++) {
            QualityGateEntity gate = new QualityGateEntity();
            gate.setId(java.util.UUID.randomUUID().toString());
            gate.setWorkspaceId(WS);
            gate.setRunId(runId);
            gate.setDatasetId(datasetId);
            gate.setPolicyKey("budget-policy");
            gate.setPolicyVersion("1");
            gate.setDecision(QualityGateEntity.DECISION_PASS);
            gate.setCandidateFingerprint("gh-fp-0");
            gate.setRunStatus(DatasetRunEntity.STATUS_COMPLETED);
            gate.setChecksJson("[]");
            gate.setCreatedBy("budget-test");
            gate.setCreatedAt(LocalDateTime.now().minusSeconds(1000 - i));
            gateMapper.insert(gate);
        }
    }

    private int measureHistory(String runId, int expected) throws Exception {
        CountingInterceptor counter = context.getBean(CountingInterceptor.class);
        counter.reset();
        JsonNode body = readTree(get("/api/v1/quality-gates/history?runId=" + runId));
        int statements = counter.statements.get();
        assertThat(body.path("code").asInt()).as("gate history: %s", body).isZero();
        assertThat(body.path("data")).hasSize(expected);
        return statements;
    }

    /**
     * 断言"增长量"，而不是一个绝对条数：绝对基线受鉴权、数据集校验、连接池等固定开销影响，会随版本漂移。
     * 实测（批量取数之后）：4 项 → 24 项只多 3~4 条；N+1 时每多一项固定多几条（散点/leaderboard 约 3 条、Gate 历史 1 条）。
     * 容差取「新增项数的一半」——远低于线性增长，同时容得下几条固定噪声。
     */
    private void assertGrowth(String what, int small, int smallSize, int large, int largeSize) {
        String ids = String.join(" , ", context.getBean(CountingInterceptor.class).ids);
        assertThat(large)
                .as("%s 查询数应随规模持平：%d 项时 %d 条，%d 项时 %d 条；逐项查库时每多一项就多固定几条。最后一批语句：%s",
                        what, smallSize, small, largeSize, large, ids)
                .isLessThanOrEqualTo(small + Math.max(4, (largeSize - smallSize) / 2));
    }

    /**
     * 预热一次：首个请求会顺带付出连接池建立、语句解析等一次性成本；把它算进基线会让增长量出现几条抖动。
     */
    private void warmUp(String datasetId, String path) throws Exception {
        readTree(get("/api/v1/datasets/" + datasetId + "/" + path));
    }

    private int measureScatter(String datasetId, int from, int count) throws Exception {
        for (int i = from; i < from + count; i++) {
            insertCompletedRun(datasetId, "fp-" + i, "cand-" + i);
        }
        CountingInterceptor counter = context.getBean(CountingInterceptor.class);
        counter.reset();
        JsonNode body = readTree(get("/api/v1/datasets/" + datasetId + "/runs/scatter?windowDays=7"));
        int statements = counter.statements.get();
        assertThat(body.path("code").asInt()).as("scatter: %s", body).isZero();
        assertThat(body.path("data").path("points")).hasSize(from + count);
        return statements;
    }

    private int measureLeaderboard(String datasetId, int from, int count) throws Exception {
        for (int i = from; i < from + count; i++) {
            insertCompletedRun(datasetId, "lb-fp-" + i, "lb-cand-" + i);
        }
        CountingInterceptor counter = context.getBean(CountingInterceptor.class);
        counter.reset();
        JsonNode body = readTree(get("/api/v1/datasets/" + datasetId + "/leaderboard"));
        int statements = counter.statements.get();
        assertThat(body.path("code").asInt()).as("leaderboard: %s", body).isZero();
        assertThat(body.path("data").path("rows")).hasSize(from + count);
        return statements;
    }

    private String createDataset(String key) throws Exception {
        JsonNode created = readTree(postApi("/api/v1/datasets", Map.of("datasetKey", key)));
        assertThat(created.path("code").asInt()).as("dataset: %s", created).isZero();
        return created.path("data").path("id").asText();
    }

    private void insertCompletedRun(String datasetId, String fingerprint, String label) {
        DatasetRunEntity run = new DatasetRunEntity();
        // ID 列是 36 字符，不能把 datasetId 拼进来（会超长）；用 UUID 正好占满且保证唯一
        run.setId(java.util.UUID.randomUUID().toString());
        run.setWorkspaceId(WS);
        run.setDatasetId(datasetId);
        run.setRunMode("TRACE");
        run.setCandidateLabel(label);
        run.setCandidateFingerprint(fingerprint);
        run.setEvaluatorId("budget-evaluator");
        run.setEvaluatorKey("budget-expr");
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
        run.setRequestKey("budget-" + datasetId + "-" + fingerprint);
        datasetRunMapper.insert(run);

        // 每个 run 放一条 item，让取 items 有真实工作量（否则会显得"没有成本"）
        DatasetRunItemEntity item = new DatasetRunItemEntity();
        item.setId(java.util.UUID.randomUUID().toString());
        item.setRunId(run.getId());
        item.setItemId("budget-src-" + fingerprint);
        item.setLabel("PASS");
        item.setResultDetailsJson("{\"status\":\"SUCCEEDED\",\"latencyMs\":120}");
        datasetRunItemMapper.insert(item);
    }
}
