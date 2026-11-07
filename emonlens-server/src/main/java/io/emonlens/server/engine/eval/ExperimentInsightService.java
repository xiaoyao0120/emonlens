package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 实验对比增强（R2-4）：散点、repetitions 方差、数据集 leaderboard。
 *
 * <p>排序口径必须可解释：leaderboard 用 <b>加权通过率</b> =
 * 组内平均通过率 × (组内平均覆盖率 / 100)，覆盖不足的候选显式标 `degraded=true`，
 * 并把它排在同等通过率但覆盖完整的候选之后。这样"只跑 1 条样本拿到 100%"不会排第一。</p>
 *
 * <p>repetitions 用候选指纹分组：同一配置重复跑出来的 run 指纹相同，因此
 * 均值 / 样本方差 / 标准差都按组计算；成对比较要求两组重复次数一致（见 PairwiseEvaluationService）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExperimentInsightService {

    private static final int MAX_RUNS = 50;

    private final DatasetMapper datasetMapper;
    private final DatasetRunMapper runMapper;
    private final DatasetRunService runService;
    private final ObjectMapper objectMapper;

    // ==================== 散点 ====================

    /** 分数 vs 成本 / 延迟散点：一行一个 run，可先按模型 / 候选标签 / 覆盖率过滤。 */
    public Map<String, Object> scatter(String workspaceId, String datasetId, int windowDays,
                                       String model, String candidateLabel, Double minCoverage) {
        DatasetEntity dataset = requireDataset(workspaceId, datasetId);
        List<DatasetRunEntity> runs = runsOf(workspaceId, dataset, windowDays);
        List<Map<String, Object>> points = new ArrayList<>();
        // 批量取回所有 run 的 items 与模型价格缓存：逐 run 单查会让查询数随历史 run 数线性增长（实测 24 个 run 84 条语句）。
        Map<String, List<DatasetRunItemEntity>> itemsByRun = runService
                .itemsOfRuns(runs.stream().map(DatasetRunEntity::getId).collect(java.util.stream.Collectors.toList()));
        Map<String, io.emonlens.server.storage.entity.ModelPriceEntity> priceCache = new LinkedHashMap<>();
        for (DatasetRunEntity run : runs) {
            List<DatasetRunItemEntity> items = itemsByRun.getOrDefault(run.getId(), List.of());
            // 先把点位需要的派生值取出来：后面的成本分账会调用 enrich，而 enrich 会按 items 重算并
            // 覆盖 run 上的计数（历史 run 可能没有 item 行，计数会被清零）。结论列以库里落库的为准。
            boolean keep = filterMatch(run, model, candidateLabel, minCoverage);
            Double passRate = passRateOf(run);
            Double coverage = coverageOf(run);
            int passCount = run.getPassCount() == null ? 0 : run.getPassCount();
            int failCount = run.getFailCount() == null ? 0 : run.getFailCount();
            int unknownCount = run.getUnknownCount() == null ? 0 : run.getUnknownCount();
            String repetitionGroup = groupKey(run);
            Long avgLatency = averageLatency(items);
            Map<String, Object> cost = safeCostFor(workspaceId, run, items, priceCache);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("runId", run.getId());
            point.put("candidateLabel", run.getCandidateLabel());
            point.put("model", run.getModel());
            point.put("promptVersion", run.getPromptVersion());
            point.put("status", run.getStatus());
            point.put("passed", keep ? "KEEP" : "FILTERED");
            point.put("passRate", passRate);
            point.put("coverage", coverage);
            // 计数列可能为空（历史 run / 未 enrich）：输出 0 而不是 null，
            // 否则前端图表拿到 undefined 会把半径算成 NaN
            point.put("passCount", passCount);
            point.put("failCount", failCount);
            point.put("unknownCount", unknownCount);
            point.put("cost", cost.get("cost"));
            point.put("priced", cost.get("priced"));
            point.put("avgLatencyMs", avgLatency);
            // 通过率的 95% 置信区间（Wilson）：小样本的 100% 和 30 样本的 100% 不是一回事，
            // 散点若只画点会让人过度相信 n=3 的结果。judged = pass + fail（UNKNOWN 不计入分母，
            // 与 passRateOf 的口径一致）。
            int judged = passCount + failCount;
            point.put("judgedCount", judged);
            Double[] ci = wilsonInterval(passCount, judged);
            point.put("passRateLow", ci == null ? null : ci[0]);
            point.put("passRateHigh", ci == null ? null : ci[1]);
            point.put("repetitionGroup", repetitionGroup);
            point.put("startedAt", run.getStartedAt());
            if (keep) {
                points.add(point);
            }
        }
        int filtered = runs.size() - points.size();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetId", datasetId);
        payload.put("windowDays", windowDays);
        payload.put("points", points);
        payload.put("filteredOut", filtered);
        payload.put("axes", Map.of("x", "cost（USD，缺价目为 null）", "y", "passRate（%）",
                "size", "itemCount", "extra", "avgLatencyMs"));
        return payload;
    }

    // ==================== Leaderboard ====================

    /**
     * 数据集维度 leaderboard：按候选（同指纹）聚合，给出重复次数、均值方差、覆盖率与加权排名。
     */
    public Map<String, Object> leaderboard(String workspaceId, String datasetId, int windowDays,
                                           double minCoverage) {
        DatasetEntity dataset = requireDataset(workspaceId, datasetId);
        List<DatasetRunEntity> runs = runsOf(workspaceId, dataset, windowDays);
        Map<String, List<DatasetRunEntity>> groups = new LinkedHashMap<>();
        for (DatasetRunEntity run : runs) {
            groups.computeIfAbsent(groupKey(run), k -> new ArrayList<>()).add(run);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        // 与散点同源的问题：每个候选分组各查一次 items、再走一次成本分账（内部又查 run + items +
        // 每个模型一次价目）。矩阵实验每列一个候选，分组数可以很大，所以这里也批量取 items 并共享价目缓存。
        Map<String, List<DatasetRunItemEntity>> itemsByRun = runService
                .itemsOfRuns(runs.stream().map(DatasetRunEntity::getId).collect(java.util.stream.Collectors.toList()));
        Map<String, io.emonlens.server.storage.entity.ModelPriceEntity> priceCache = new LinkedHashMap<>();
        for (Map.Entry<String, List<DatasetRunEntity>> entry : groups.entrySet()) {
            List<DatasetRunEntity> group = entry.getValue();
            DoubleSpec passRate = stats(group.stream().map(this::passRateOf)
                    .collect(java.util.stream.Collectors.toList()));
            DoubleSpec coverage = stats(group.stream().map(this::coverageOf)
                    .collect(java.util.stream.Collectors.toList()));
            DatasetRunEntity sample = group.get(0);
            List<DatasetRunItemEntity> items = itemsByRun.getOrDefault(sample.getId(), List.of());
            // 成本分账会 enrich 并把计数写回 run，所以派生值必须先生成（见 costReportFor 的说明）
            Long avgLatency = averageLatency(items);
            Map<String, Object> cost = safeCostFor(workspaceId, sample, items, priceCache);
            double coverageValue = coverage.mean == null ? 0 : coverage.mean;
            double passRateValue = passRate.mean == null ? 0 : passRate.mean;
            // 覆盖率折扣：覆盖 100% 不打折，覆盖 50% 折半；缺覆盖率按 0 处理（不能拿"没判过"当满分）
            double adjusted = round(passRateValue * coverageValue / 100.0, 1);
            boolean degraded = coverageValue < minCoverage;

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("candidate", sample.getCandidateLabel() == null
                    ? (sample.getModel() == null ? entry.getKey() : sample.getModel())
                    : sample.getCandidateLabel());
            row.put("candidateFingerprint", sample.getCandidateFingerprint());
            row.put("model", sample.getModel());
            row.put("promptName", sample.getPromptName());
            row.put("promptVersion", sample.getPromptVersion());
            row.put("runs", group.size());
            row.put("passRateMean", passRate.mean);
            row.put("passRateStdDev", passRate.stdDev);
            row.put("passRateVariance", passRate.variance);
            row.put("coverageMean", coverage.mean);
            row.put("adjustedPassRate", adjusted);
            row.put("cost", cost.get("cost"));
            row.put("priced", cost.get("priced"));
            row.put("avgLatencyMs", avgLatency);
            row.put("degraded", degraded);
            row.put("degradeReason", degraded
                    ? "平均覆盖率 " + coverageValue + "% 低于阈值 " + minCoverage + "%：按覆盖率线性降权"
                    : null);
            rows.add(row);
        }
        rows.sort(Comparator
                .comparingDouble((Map<String, Object> row) -> -asDouble(row.get("adjustedPassRate")))
                .thenComparingDouble(row -> -asDouble(row.get("coverageMean")))
                .thenComparing(row -> String.valueOf(row.get("candidate"))));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetId", datasetId);
        payload.put("windowDays", windowDays);
        payload.put("minCoverage", minCoverage);
        payload.put("rankingRule",
                "adjustedPassRate = passRateMean × coverageMean/100；按 adjustedPassRate 降序，"
                        + "同分按覆盖率降序，再按候选名排序；coverageMean < minCoverage 的候选标 degraded 并参与降权");
        payload.put("rows", rows);
        payload.put("degradedCount", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("degraded"))).count());
        return payload;
    }

    // ==================== 工具 ====================

    private List<DatasetRunEntity> runsOf(String workspaceId, DatasetEntity dataset, int windowDays) {
        LocalDateTime since = LocalDateTime.now().minusDays(Math.max(1, windowDays));
        List<DatasetRunEntity> runs = runMapper.selectList(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getWorkspaceId, workspaceId)
                .eq(DatasetRunEntity::getDatasetId, dataset.getId())
                .in(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_COMPLETED,
                        DatasetRunEntity.STATUS_PARTIAL_FAILED, DatasetRunEntity.STATUS_FAILED)
                .ge(DatasetRunEntity::getStartedAt, since)
                .orderByDesc(DatasetRunEntity::getStartedAt)
                .last("LIMIT " + MAX_RUNS));
        List<DatasetRunEntity> sorted = new ArrayList<>(runs);
        sorted.sort(Comparator.comparing(DatasetRunEntity::getStartedAt,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return sorted;
    }

    /** 通过率：PASS / (PASS + FAIL)；全未判定时返回 null（不拿 0 冒充）。 */
    private Double passRateOf(DatasetRunEntity run) {
        int pass = run.getPassCount() == null ? 0 : run.getPassCount();
        int fail = run.getFailCount() == null ? 0 : run.getFailCount();
        int judged = pass + fail;
        return judged == 0 ? null : round(pass * 100.0 / judged, 1);
    }

    /** 覆盖率：(PASS + FAIL) / itemCount，与 run 报告口径一致。 */
    private Double coverageOf(DatasetRunEntity run) {
        int pass = run.getPassCount() == null ? 0 : run.getPassCount();
        int fail = run.getFailCount() == null ? 0 : run.getFailCount();
        int total = run.getItemCount() == null ? 0 : run.getItemCount();
        return total == 0 ? null : round((pass + fail) * 100.0 / total, 1);
    }

    /** 同指纹即同配置：这就是 repetitions 组的定义。没有指纹时退化为 runId（每组 1 条）。 */
    private String groupKey(DatasetRunEntity run) {
        String fingerprint = run.getCandidateFingerprint();
        return fingerprint == null || fingerprint.isBlank() ? run.getId() : fingerprint;
    }

    private boolean filterMatch(DatasetRunEntity run, String model, String candidateLabel,
                                Double minCoverage) {
        if (model != null && !model.isBlank() && !model.equals(run.getModel())) {
            return false;
        }
        if (candidateLabel != null && !candidateLabel.isBlank()
                && !candidateLabel.equals(run.getCandidateLabel())) {
            return false;
        }
        Double runCoverage = coverageOf(run);
        if (minCoverage != null && (runCoverage == null || runCoverage < minCoverage)) {
            return false;
        }
        return true;
    }

    /** 已加载 items 的成本分账；不重复查库，模型价格走请求级缓存。 */
    private Map<String, Object> safeCostFor(String workspaceId, DatasetRunEntity run,
                                            List<DatasetRunItemEntity> items,
                                            Map<String, io.emonlens.server.storage.entity.ModelPriceEntity> priceCache) {
        try {
            return runService.costReportFor(workspaceId, run, items, priceCache);
        } catch (Exception e) {
            log.debug("cost report failed, run={}", run.getId(), e);
            return Map.of("cost", null, "priced", false);
        }
    }

    /** 平均延迟：取任务账本里记录的 latencyMs（没有记录时返回 null，不猜）。 */
    private Long averageLatency(List<DatasetRunItemEntity> items) {
        long sum = 0;
        int count = 0;
        for (DatasetRunItemEntity item : items) {
            Map<String, Object> details = parseMap(item.getResultDetailsJson());
            Object latency = details == null ? null : details.get("latencyMs");
            if (latency instanceof Number) {
                sum += ((Number) latency).longValue();
                count++;
            }
        }
        return count == 0 ? null : Math.round((double) sum / count);
    }

    private DatasetEntity requireDataset(String workspaceId, String datasetId) {
        DatasetEntity dataset = datasetId == null ? null : datasetMapper.selectById(datasetId);
        if (dataset == null || !Objects.equals(workspaceId, dataset.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found: " + datasetId);
        }
        return dataset;
    }

    private static class DoubleSpec {
        private Double mean;
        private Double variance;
        private Double stdDev;
    }

    private DoubleSpec stats(List<Double> values) {
        List<Double> clean = new ArrayList<>();
        for (Double value : values) {
            if (value != null) {
                clean.add(value);
            }
        }
        DoubleSpec spec = new DoubleSpec();
        if (clean.isEmpty()) {
            return spec;
        }
        double mean = clean.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        spec.mean = round(mean, 1);
        if (clean.size() > 1) {
            double sumSq = 0;
            for (double value : clean) {
                sumSq += (value - mean) * (value - mean);
            }
            double variance = sumSq / (clean.size() - 1); // 样本方差：repetitions 是抽样
            spec.variance = round(variance, 2);
            spec.stdDev = round(Math.sqrt(variance), 2);
        } else {
            spec.variance = 0.0;
            spec.stdDev = 0.0;
        }
        return spec;
    }

    /**
     * Wilson score interval (95%) in percent, or null when nothing was judged.
     * Chosen over the normal approximation because it stays inside [0, 100] and behaves sanely at n=1..5,
     * which is exactly where the plain ratio lies.
     */
    private static Double[] wilsonInterval(int successes, int total) {
        if (total <= 0) {
            return null;
        }
        double z = 1.96;
        double n = total;
        double p = successes / n;
        double denom = 1 + z * z / n;
        double center = (p + z * z / (2 * n)) / denom;
        double margin = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / denom;
        double low = Math.max(0, center - margin) * 100;
        double high = Math.min(1, center + margin) * 100;
        return new Double[] { round(low, 1), round(high, 1) };
    }

    private static double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static double asDouble(Object value) {
        return value instanceof Number ? ((Number) value).doubleValue() : 0;
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return null;
        }
    }
}
