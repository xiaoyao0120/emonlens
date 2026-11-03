package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.finding.FindingService;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetRevisionEntity;
import io.emonlens.server.storage.entity.EvalCacheEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.JudgeReviewEntity;
import io.emonlens.server.storage.entity.ModelPriceEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRevisionMapper;
import io.emonlens.server.storage.mapper.EvalCacheMapper;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.EvaluatorMapper;
import io.emonlens.server.storage.mapper.FindingMapper;
import io.emonlens.server.storage.mapper.JudgeReviewMapper;
import io.emonlens.server.storage.mapper.ModelPriceMapper;
import io.emonlens.server.storage.mapper.WorkspaceMapper;
import io.emonlens.server.storage.entity.WorkspaceEntity;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 评估器质量看板（R2-3）：回答两个问题——「哪个评估器最不可信」「哪个数据集在腐烂」。
 *
 * <p>口径：
 * <ul>
 *   <li><b>盲审一致率</b>：窗口内 et_judge_review 的 judgeLabel == humanLabel 占比（DOUBLE_BLIND 取仲裁值已由写入侧决定）。</li>
 *   <li><b>UNKNOWN / ERROR 率</b>：et_eval_result 的标签分布；ERROR 与 EVAL_UNAVAILABLE 单独计为「未执行」，
 *       不混进 UNKNOWN（证据不足 ≠ 执行失败）。</li>
 *   <li><b>成本</b>：按 judgeModel 的 model price 折算；缺价目时显式标 priced=false，不伪造金额。</li>
 *   <li><b>缓存</b>：et_eval_cache 在窗口内的行数与 token；工作空间级（缓存 key 不绑定 evaluator key）。</li>
 *   <li><b>数据集腐烂</b>：陈旧样本占比（超过 staleDays 未更新）与最新 revision 年龄；
 *       蓝图未配置单独标注，因为它决定了「有没有可对照的覆盖要求」。</li>
 * </ul></p>
 *
 * <p>降级建议是规则化的可解释结论，不做自动改配置：低一致率建议改 CLAIMS / 换模板版本，
 * 高 UNKNOWN 建议补证据或放宽 referenceRequired，高成本建议开缓存或降级到确定性评估器。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluatorQualityService {

    public static final String VERDICT_OK = "OK";
    public static final String VERDICT_LOW_TRUST = "LOW_TRUST";
    public static final String VERDICT_NOISY = "NOISY";
    public static final String VERDICT_COSTLY = "COSTLY";

    public static final String DATASET_HEALTHY = "HEALTHY";
    public static final String DATASET_WARNING = "WARNING";
    public static final String DATASET_ROTTING = "ROTTING";

    private final EvalResultMapper evalResultMapper;
    private final EvalCacheMapper evalCacheMapper;
    private final JudgeReviewMapper judgeReviewMapper;
    private final EvaluatorMapper evaluatorMapper;
    private final ModelPriceMapper modelPriceMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final DatasetRevisionMapper datasetRevisionMapper;
    private final WorkspaceMapper workspaceMapper;
    private final FindingMapper findingMapper;
    private final FindingService findingService;
    private final EmonLensProperties properties;
    private final ObjectMapper objectMapper;

    // ==================== 报告 ====================

    public Map<String, Object> report(String workspaceId, int windowHours, int staleDays) {
        int hours = Math.max(1, Math.min(windowHours, 24 * 90));
        int stale = Math.max(1, Math.min(staleDays, 3650));
        LocalDateTime since = LocalDateTime.now().minusHours(hours);

        List<EvaluatorEntity> evaluators = evaluatorMapper.selectList(
                Wrappers.<EvaluatorEntity>lambdaQuery()
                        .eq(EvaluatorEntity::getWorkspaceId, workspaceId)
                        .orderByAsc(EvaluatorEntity::getEvaluatorKey));
        List<EvalResultEntity> results = evalResultMapper.selectList(
                Wrappers.<EvalResultEntity>lambdaQuery()
                        .eq(EvalResultEntity::getWorkspaceId, workspaceId)
                        .ge(EvalResultEntity::getCreatedAt, since));
        List<JudgeReviewEntity> reviews = judgeReviewMapper.selectList(
                Wrappers.<JudgeReviewEntity>lambdaQuery()
                        .eq(JudgeReviewEntity::getWorkspaceId, workspaceId)
                        .ge(JudgeReviewEntity::getCreatedAt, since));

        Map<String, List<EvalResultEntity>> byEvaluator = new LinkedHashMap<>();
        for (EvalResultEntity result : results) {
            byEvaluator.computeIfAbsent(key(result.getEvaluatorKey(), result.getEvaluatorVersion()),
                    k -> new ArrayList<>()).add(result);
        }
        Map<String, List<JudgeReviewEntity>> reviewsByEvaluator = new LinkedHashMap<>();
        for (JudgeReviewEntity review : reviews) {
            reviewsByEvaluator.computeIfAbsent(key(review.getEvaluatorKey(), review.getEvaluatorVersion()),
                    k -> new ArrayList<>()).add(review);
        }

        List<Map<String, Object>> evaluatorRows = new ArrayList<>();
        for (EvaluatorEntity evaluator : evaluators) {
            String key = key(evaluator.getEvaluatorKey(), evaluator.getVersion());
            evaluatorRows.add(evaluatorRow(workspaceId, evaluator, key,
                    byEvaluator.getOrDefault(key, List.of()),
                    reviewsByEvaluator.getOrDefault(key, List.of())));
        }
        // 只按「最不可信」排序：低一致率优先，其次高 UNKNOWN、再有成本的排后面
        evaluatorRows.sort(Comparator
                .comparingInt((Map<String, Object> row) -> severity((String) row.get("verdict")))
                .thenComparingDouble(row -> asDouble(row.get("agreementRate"), 100))
                .thenComparingDouble(row -> -asDouble(row.get("unknownRate"), 0)));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("windowHours", hours);
        payload.put("staleDays", stale);
        payload.put("generatedAt", LocalDateTime.now());
        payload.put("evaluators", evaluatorRows);
        payload.put("leastTrusted", evaluatorRows.isEmpty() ? null : evaluatorRows.get(0));
        List<Map<String, Object>> datasets = datasetRows(workspaceId, stale);
        payload.put("datasets", datasets);
        payload.put("cache", cacheRow(workspaceId, since));
        payload.put("alerts", alerts(evaluatorRows, datasets));
        return payload;
    }

    private Map<String, Object> evaluatorRow(String workspaceId, EvaluatorEntity evaluator, String key,
                                             List<EvalResultEntity> results,
                                             List<JudgeReviewEntity> reviews) {
        int pass = 0;
        int fail = 0;
        int unknown = 0;
        int notExecuted = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        boolean priced = true;
        BigDecimal cost = BigDecimal.ZERO;
        java.util.Set<String> unpricedModels = new java.util.LinkedHashSet<>();
        for (EvalResultEntity result : results) {
            String label = result.getLabel();
            if (EvaluatorEntity.LABEL_PASS.equals(label)) {
                pass++;
            } else if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
                fail++;
            } else if (EvaluatorEntity.LABEL_ERROR.equals(label)
                    || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)) {
                notExecuted++;
            } else {
                unknown++;
            }
            long p = result.getPromptTokens() == null ? 0 : result.getPromptTokens();
            long c = result.getCompletionTokens() == null ? 0 : result.getCompletionTokens();
            promptTokens += p;
            completionTokens += c;
            if (p + c > 0) {
                ModelPriceEntity price = priceOf(workspaceId, result.getJudgeModel());
                if (price == null) {
                    priced = false;
                    unpricedModels.add(result.getJudgeModel() == null ? "unknown" : result.getJudgeModel());
                } else {
                    cost = cost.add(tokenCost(price, p, c));
                }
            }
        }
        int total = results.size();
        int judged = pass + fail;

        int reviewed = 0;
        int agreed = 0;
        for (JudgeReviewEntity review : reviews) {
            reviewed++;
            if (Objects.equals(review.getJudgeLabel(), review.getHumanLabel())) {
                agreed++;
            }
        }
        Double agreementRate = reviewed == 0 ? null
                : round(agreed * 100.0 / reviewed, 1);
        double unknownRate = total == 0 ? 0 : (double) unknown / total;
        double notExecutedRate = total == 0 ? 0 : (double) notExecuted / total;

        String verdict = VERDICT_OK;
        List<String> advice = new ArrayList<>();
        if (agreementRate != null && agreementRate < properties.getEvaluatorQuality().getMinAgreement()) {
            verdict = VERDICT_LOW_TRUST;
            advice.add("一致率低于 " + properties.getEvaluatorQuality().getMinAgreement()
                    + "%：先用盲审样本复核 rubric，考虑改 CLAIMS 逐条判分或换 Judge 模板版本");
        } else if (unknownRate + notExecutedRate >= properties.getEvaluatorQuality().getUnknownAlertRate()) {
            verdict = VERDICT_NOISY;
            advice.add("UNKNOWN/未执行占比 " + percent(unknownRate + notExecutedRate)
                    + "：先补证据（检索上下文 / 工具轨迹 / 参考答案），或放宽 referenceRequired");
        } else if (cost.compareTo(BigDecimal.valueOf(
                properties.getEvaluatorQuality().getCostlyPerWindow())) > 0 && !priced) {
            advice.add("窗口成本超过阈值且存在未计价模型：先补「模型价格」再评估性价比");
            verdict = VERDICT_COSTLY;
        } else if (cost.compareTo(BigDecimal.valueOf(
                properties.getEvaluatorQuality().getCostlyPerWindow())) > 0) {
            verdict = VERDICT_COSTLY;
            advice.add("窗口成本较高：开启评测缓存或改用确定性评估器（表达式 / 正则 / 轨迹）");
        }
        if (reviewed > 0 && reviewed < properties.getEvaluatorQuality().getMinReviewedForTrust()) {
            advice.add("盲审样本仅 " + reviewed + " 条，一致率还不具备统计意义；建议补到 "
                    + properties.getEvaluatorQuality().getMinReviewedForTrust() + " 条以上");
        }
        if (total == 0) {
            advice.add("窗口内没有评估结果：确认自动评估规则是否覆盖该评估器");
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("evaluatorKey", evaluator.getEvaluatorKey());
        row.put("evaluatorVersion", evaluator.getVersion());
        row.put("type", evaluator.getType());
        row.put("enabled", evaluator.getEnabled());
        row.put("evalCount", total);
        row.put("passCount", pass);
        row.put("failCount", fail);
        row.put("unknownCount", unknown);
        row.put("notExecutedCount", notExecuted);
        row.put("passRate", judged == 0 ? null : round(pass * 100.0 / judged, 1));
        row.put("unknownRate", round(unknownRate * 100, 1));
        row.put("notExecutedRate", round(notExecutedRate * 100, 1));
        row.put("reviewedCount", reviewed);
        row.put("agreementCount", agreed);
        row.put("agreementRate", agreementRate);
        row.put("promptTokens", promptTokens);
        row.put("completionTokens", completionTokens);
        row.put("cost", priced ? cost.setScale(4, RoundingMode.HALF_UP) : null);
        row.put("priced", priced);
        row.put("unpricedModels", new ArrayList<>(unpricedModels));
        row.put("verdict", verdict);
        row.put("advice", advice);
        return row;
    }

    private List<Map<String, Object>> datasetRows(String workspaceId, int staleDays) {
        List<DatasetEntity> datasets = datasetMapper.selectList(Wrappers.<DatasetEntity>lambdaQuery()
                .eq(DatasetEntity::getWorkspaceId, workspaceId)
                .orderByAsc(DatasetEntity::getDatasetKey));
        List<Map<String, Object>> rows = new ArrayList<>();
        LocalDateTime staleBefore = LocalDateTime.now().minusDays(staleDays);
        for (DatasetEntity dataset : datasets) {
            List<DatasetItemEntity> items = datasetItemMapper.selectList(
                    Wrappers.<DatasetItemEntity>lambdaQuery()
                            .eq(DatasetItemEntity::getDatasetId, dataset.getId()));
            long active = items.stream().filter(i -> i.getStatus() == null || "ACTIVE".equals(i.getStatus()))
                    .count();
            long staleItems = items.stream()
                    .filter(i -> i.getStatus() == null || "ACTIVE".equals(i.getStatus()))
                    .filter(i -> i.getCreatedAt() != null && i.getCreatedAt().isBefore(staleBefore))
                    .count();
            DatasetRevisionEntity latest = datasetRevisionMapper.selectOne(
                    Wrappers.<DatasetRevisionEntity>lambdaQuery()
                            .eq(DatasetRevisionEntity::getDatasetId, dataset.getId())
                            .orderByDesc(DatasetRevisionEntity::getRevision)
                            .last("LIMIT 1"));
            Long revisionAgeDays = latest == null || latest.getCreatedAt() == null ? null
                    : Duration.between(latest.getCreatedAt(), LocalDateTime.now()).toDays();
            double staleShare = active == 0 ? 0 : (double) staleItems / active;
            boolean blueprintConfigured = dataset.getBlueprintJson() != null
                    && !dataset.getBlueprintJson().isBlank() && !"{}".equals(dataset.getBlueprintJson().trim());

            String verdict = DATASET_HEALTHY;
            List<String> advice = new ArrayList<>();
            if (active == 0) {
                verdict = DATASET_WARNING;
                advice.add("数据集没有 ACTIVE 样本：先补样本再谈覆盖");
            } else if (staleShare >= 0.5 || (revisionAgeDays != null
                    && revisionAgeDays > properties.getEvaluatorQuality().getRotRevisionDays())) {
                verdict = DATASET_ROTTING;
                advice.add("陈旧样本占比 " + percent(staleShare) + "，最新 revision "
                        + (revisionAgeDays == null ? "不存在" : revisionAgeDays + " 天前") + "：建议补新样本并冻结新 revision");
            } else if (staleShare >= 0.25 || (revisionAgeDays != null
                    && revisionAgeDays > properties.getEvaluatorQuality().getWarnRevisionDays())) {
                verdict = DATASET_WARNING;
                advice.add("陈旧样本占比 " + percent(staleShare) + "：建议按蓝图补齐缺失类别");
            }
            if (!blueprintConfigured) {
                advice.add("未配置数据集蓝图：无法判断覆盖是否达标（保鲜度按固定阈值估算）");
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("datasetId", dataset.getId());
            row.put("datasetKey", dataset.getDatasetKey());
            row.put("activeItems", active);
            row.put("staleItems", staleItems);
            row.put("staleShare", round(staleShare * 100, 1));
            row.put("latestRevision", latest == null ? null : latest.getRevision());
            row.put("revisionAgeDays", revisionAgeDays);
            row.put("blueprintConfigured", blueprintConfigured);
            row.put("verdict", verdict);
            row.put("advice", advice);
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt((Map<String, Object> row) -> {
            String verdict = (String) row.get("verdict");
            return DATASET_ROTTING.equals(verdict) ? 0 : DATASET_WARNING.equals(verdict) ? 1 : 2;
        }));
        return rows;
    }

    private Map<String, Object> cacheRow(String workspaceId, LocalDateTime since) {
        List<EvalCacheEntity> cached = evalCacheMapper.selectList(Wrappers.<EvalCacheEntity>lambdaQuery()
                .eq(EvalCacheEntity::getWorkspaceId, workspaceId)
                .ge(EvalCacheEntity::getCreatedAt, since));
        long promptTokens = 0;
        long completionTokens = 0;
        for (EvalCacheEntity row : cached) {
            promptTokens += row.getPromptTokens() == null ? 0 : row.getPromptTokens();
            completionTokens += row.getCompletionTokens() == null ? 0 : row.getCompletionTokens();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("entries", cached.size());
        map.put("promptTokensSaved", promptTokens);
        map.put("completionTokensSaved", completionTokens);
        map.put("note", "缓存 key 绑定 sample 指纹与模板 hash，不绑定 evaluator key，因此按工作空间汇总");
        return map;
    }

    // ==================== 告警（接入 Alerts） ====================

    /** 把低可信评估器与腐烂数据集写成 Finding，交给既有 Incident / 通知链路。 */
    public int scanAndRecord(String workspaceId) {
        Map<String, Object> payload = report(workspaceId,
                properties.getEvaluatorQuality().getWindowHours(),
                properties.getEvaluatorQuality().getStaleDays());
        int created = 0;
        Object evaluators = payload.get("evaluators");
        if (evaluators instanceof List) {
            for (Object raw : (List<?>) evaluators) {
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<?, ?> row = (Map<?, ?>) raw;
                String verdict = String.valueOf(row.get("verdict"));
                if (VERDICT_OK.equals(verdict)) {
                    continue;
                }
                created += record(workspaceId, "EVALUATOR_QUALITY",
                        "evaluator:" + row.get("evaluatorKey") + "@" + row.get("evaluatorVersion"),
                        "evaluation", verdict.equals(VERDICT_LOW_TRUST) ? "HIGH" : "MEDIUM",
                        "评估器 " + row.get("evaluatorKey") + " v" + row.get("evaluatorVersion")
                                + " 需要复核（" + verdict + "）：" + firstAdvice(row),
                        row);
            }
        }
        Object datasets = payload.get("datasets");
        if (datasets instanceof List) {
            for (Object raw : (List<?>) datasets) {
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<?, ?> row = (Map<?, ?>) raw;
                if (!DATASET_ROTTING.equals(String.valueOf(row.get("verdict")))) {
                    continue;
                }
                created += record(workspaceId, "DATASET_ROT",
                        "dataset:" + row.get("datasetKey"),
                        "context", "MEDIUM",
                        "数据集 " + row.get("datasetKey") + " 正在腐烂：" + firstAdvice(row),
                        row);
            }
        }
        return created;
    }

    /** 同一对象 24 小时内只报一次，避免每天刷同样的 Finding。 */
    private int record(String workspaceId, String type, String detectorId, String dimension,
                       String severity, String message, Map<?, ?> evidence) {
        Long recent = findingMapper.selectCount(Wrappers.<FindingEntity>lambdaQuery()
                .eq(FindingEntity::getWorkspaceId, workspaceId)
                .eq(FindingEntity::getType, type)
                .eq(FindingEntity::getDetectorId, detectorId)
                .ge(FindingEntity::getCreatedAt, LocalDateTime.now().minusHours(24)));
        if (recent != null && recent > 0) {
            return 0;
        }
        FindingEntity finding = new FindingEntity();
        finding.setWorkspaceId(workspaceId);
        // 质量告警是工作空间/数据集级的，没有具体 agent：用 "*" 作为平台级哨兵值，
        // 既满足 et_finding.agent_key NOT NULL，也便于 Alerts 页区分「平台级」与「某 agent」。
        finding.setAgentKey("*");
        finding.setDimension(dimension);
        finding.setType(type);
        finding.setSeverity(severity);
        finding.setDetectorId(detectorId);
        finding.setMessage(message.length() > 500 ? message.substring(0, 500) : message);
        try {
            finding.setEvidence(objectMapper.writeValueAsString(evidence));
        } catch (Exception e) {
            finding.setEvidence(null);
        }
        try {
            findingService.record(finding);
            return 1;
        } catch (Exception e) {
            log.error("quality finding failed, type={}, detector={}", type, detectorId, e);
            return 0;
        }
    }

    /** 供调度器遍历：当前有评估器或数据集的工作空间。 */
    public List<String> workspaces() {
        List<String> ids = new ArrayList<>();
        for (WorkspaceEntity workspace : workspaceMapper.selectList(Wrappers.<WorkspaceEntity>lambdaQuery())) {
            ids.add(workspace.getId());
        }
        return ids;
    }

    private List<Map<String, Object>> alerts(List<Map<String, Object>> evaluatorRows,
                                             List<Map<String, Object>> datasetRows) {
        List<Map<String, Object>> alerts = new ArrayList<>();
        for (Map<String, Object> row : evaluatorRows) {
            String verdict = String.valueOf(row.get("verdict"));
            if (VERDICT_OK.equals(verdict)) {
                continue;
            }
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("kind", "EVALUATOR_QUALITY");
            alert.put("target", row.get("evaluatorKey") + " v" + row.get("evaluatorVersion"));
            alert.put("verdict", verdict);
            alert.put("detail", firstAdvice(row));
            alerts.add(alert);
        }
        for (Map<String, Object> row : datasetRows) {
            if (!DATASET_ROTTING.equals(String.valueOf(row.get("verdict")))) {
                continue;
            }
            Map<String, Object> alert = new LinkedHashMap<>();
            alert.put("kind", "DATASET_ROT");
            alert.put("target", row.get("datasetKey"));
            alert.put("verdict", row.get("verdict"));
            alert.put("detail", firstAdvice(row));
            alerts.add(alert);
        }
        return alerts;
    }

    // ==================== 工具 ====================

    private static String key(String evaluatorKey, Integer version) {
        return evaluatorKey + "@" + (version == null ? 0 : version);
    }

    private static int severity(String verdict) {
        if (VERDICT_LOW_TRUST.equals(verdict)) {
            return 0;
        }
        return VERDICT_NOISY.equals(verdict) ? 1 : VERDICT_COSTLY.equals(verdict) ? 2 : 3;
    }

    private String firstAdvice(Map<?, ?> row) {
        Object advice = row.get("advice");
        if (advice instanceof List && !((List<?>) advice).isEmpty()) {
            return String.valueOf(((List<?>) advice).get(0));
        }
        return String.valueOf(row.get("verdict"));
    }

    private ModelPriceEntity priceOf(String workspaceId, String model) {
        if (model == null || model.isBlank()) {
            return null;
        }
        return modelPriceMapper.selectOne(Wrappers.<ModelPriceEntity>lambdaQuery()
                .eq(ModelPriceEntity::getWorkspaceId, workspaceId)
                .eq(ModelPriceEntity::getModel, model)
                .last("LIMIT 1"));
    }

    private BigDecimal tokenCost(ModelPriceEntity price, long prompt, long completion) {
        return price.getInputPerMillion().multiply(BigDecimal.valueOf(prompt))
                .add(price.getOutputPerMillion().multiply(BigDecimal.valueOf(completion)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private static Double round(double value, int scale) {
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue();
    }

    private static double asDouble(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private static String percent(double ratio) {
        return round(ratio * 100, 1) + "%";
    }
}
