package io.emonlens.server.engine.playground;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.eval.DatasetRunService;
import io.emonlens.server.engine.eval.DatasetRevisionService;
import io.emonlens.server.engine.eval.EvaluationRunService;
import io.emonlens.server.engine.eval.EvaluationSample;
import io.emonlens.server.engine.eval.LlmClient;
import io.emonlens.server.engine.eval.RunItemAggregator;
import io.emonlens.server.engine.prompt.PromptService;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.ModelPriceEntity;
import io.emonlens.server.storage.entity.PlaygroundCellEntity;
import io.emonlens.server.storage.entity.PlaygroundDiffEntity;
import io.emonlens.server.storage.entity.PlaygroundDiffItemEntity;
import io.emonlens.server.storage.entity.PlaygroundMatrixEntity;
import io.emonlens.server.storage.entity.PromptEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.EvaluatorMapper;
import io.emonlens.server.storage.mapper.ModelPriceMapper;
import io.emonlens.server.storage.mapper.PlaygroundCellMapper;
import io.emonlens.server.storage.mapper.PlaygroundDiffItemMapper;
import io.emonlens.server.storage.mapper.PlaygroundDiffMapper;
import io.emonlens.server.storage.mapper.PlaygroundMatrixMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Playground 矩阵实验（R1-2）：数据集样本 × 列（prompt 版本 × 模型 × 参数）网格试跑。
 *
 * <p>口径边界：矩阵不是准入证据——没有候选指纹、不进 Gate、不进信任分。要让一次试跑成为
 * 证据必须显式升格为 dataset run（{@link #promote}），升格后才走冻结与指纹口径。</p>
 *
 * <p>成本护栏：启动前必须给出预估并要求调用方显式确认（confirmEstimate），单元格、外部调用
 * 数都有硬上限；单格失败只写 ERROR，不中断整批。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaygroundMatrixService {

    private static final Pattern VAR_PATTERN = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");
    /** 单元格输出入库上限：试跑产物不需要全文留档，超限截断并标记 */
    private static final int MAX_OUTPUT_CHARS = 20_000;
    private static final int DEFAULT_MAX_TOKENS = 512;
    private static final int ESTIMATE_CHARS_PER_TOKEN = 4;

    private final PlaygroundMatrixMapper matrixMapper;
    private final PlaygroundCellMapper cellMapper;
    private final PlaygroundDiffMapper diffMapper;
    private final PlaygroundDiffItemMapper diffItemMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final DatasetRevisionService revisionService;
    private final EvaluatorMapper evaluatorMapper;
    private final ModelPriceMapper modelPriceMapper;
    private final EvaluationRunService evaluationRunService;
    private final LlmClient llmClient;
    private final PromptService promptService;
    private final DatasetRunService datasetRunService;
    private final io.emonlens.server.engine.eval.PairwiseJudge pairwiseJudge;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;
    private final PlatformTransactionManager transactionManager;

    private final Object acceptLock = new Object();

    // ==================== 请求体 ====================

    @Data
    public static class ColumnRequest {
        /** 列标识；缺省按序号生成 c1/c2；同一矩阵内唯一 */
        private String key;
        private String label;
        /** 从 prompt 库取版本：优先 promptVersion，只给 name 时按 promptLabel（缺省 production） */
        private String promptName;
        private Integer promptVersion;
        private String promptLabel;
        /** 或直接给模板正文（不写 prompt 库时使用） */
        private String prompt;
        private String systemPrompt;
        private String model;
        private Double temperature;
        private Integer maxTokens;
        /** 变量固定值 */
        private Map<String, String> variables;
        /** 变量来源：input / expected / fixed；缺省第一个变量取 input，其余取 fixed */
        private Map<String, String> variableSource;
    }

    @Data
    public static class CreateRequest {
        private String name;
        private String datasetId;
        private String revisionId;
        /** 可选：挂 1..5 个评估器给每个单元格打分（试跑口径，不进 Gate） */
        private List<String> evaluatorIds;
        private Integer sampleLimit;
        private List<ColumnRequest> columns;
        /** 成本预估硬确认：未确认为 false 时拒绝受理 */
        private Boolean confirmEstimate;
    }

    @Data
    public static class DiffRequest {
        private String baseColumn;
        private String targetColumn;
        private List<String> judgeModels;
        private Integer samplesPerDirection;
        private Boolean positionRandomized;
        private Long positionSeed;
    }

    @Data
    public static class PromoteRequest {
        /** 可选：升格后的 run 打这个标签（如 baseline） */
        private String runLabel;
        private String commitSha;
        private String candidateLabel;
    }

    // ==================== 预估 ====================

    /** 只算不落库：前端先看成本，确认后再 create。 */
    public Map<String, Object> estimate(String workspaceId, CreateRequest request) {
        DatasetEntity dataset = requireDataset(workspaceId, request == null ? null : request.getDatasetId());
        List<ColumnRequest> columns = normalizeColumns(request.getColumns());
        List<EvaluatorEntity> evaluators = loadEvaluators(workspaceId, request.getEvaluatorIds());
        List<DatasetItemEntity> items = itemsFor(workspaceId, dataset, request.getRevisionId(),
                sampleLimit(request));
        List<Map<String, Object>> frozen = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            frozen.add(freezeColumn(workspaceId, columns.get(i), i));
        }
        return estimateOf(workspaceId, frozen, items, evaluators);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> estimateOf(String workspaceId, List<Map<String, Object>> columns,
                                           List<DatasetItemEntity> items,
                                           List<EvaluatorEntity> evaluators) {
        int judgeEvaluators = (int) evaluators.stream()
                .filter(e -> EvaluatorEntity.TYPE_LLM_JUDGE.equals(e.getType())).count();
        List<Map<String, Object>> byColumn = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        String currency = null;
        Set<String> unpriced = new LinkedHashSet<>();
        long totalPrompt = 0;
        long totalCompletion = 0;
        for (Map<String, Object> column : columns) {
            String model = String.valueOf(column.get("model"));
            int maxTokens = intOf(column.get("maxTokens"), DEFAULT_MAX_TOKENS);
            long promptTokens = 0;
            for (DatasetItemEntity item : items) {
                String rendered = render(String.valueOf(column.get("prompt")),
                        variables(column, item));
                promptTokens += estimateTokens(rendered)
                        + estimateTokens((String) column.get("systemPrompt"));
            }
            long completionTokens = (long) items.size() * maxTokens;
            // judge 的 token 预估：候选输出 + 参考 + rubric 提示，按每评估器固定 400 输入 / 120 输出
            long judgePrompt = (long) items.size() * judgeEvaluators * 400;
            long judgeCompletion = (long) items.size() * judgeEvaluators * 120;
            ModelPriceEntity price = priceOf(workspaceId, String.valueOf(column.get("model")));
            BigDecimal generationCost = tokenCost(price, promptTokens, completionTokens);
            BigDecimal judgeCost = judgeEvaluators == 0 ? BigDecimal.ZERO
                    : tokenCost(price, judgePrompt, judgeCompletion);
            BigDecimal cost = null;
            if (generationCost != null && judgeCost != null) {
                cost = generationCost.add(judgeCost);
            } else if (generationCost == null) {
                unpriced.add(model);
            }
            if (cost != null) {
                total = total.add(cost);
                if (currency == null) {
                    currency = price == null || price.getCurrency() == null ? "USD" : price.getCurrency();
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", column.get("key"));
            row.put("label", column.get("label"));
            row.put("model", model);
            row.put("cells", items.size());
            row.put("promptTokens", promptTokens);
            row.put("completionTokens", completionTokens);
            row.put("judgePromptTokens", judgePrompt);
            row.put("judgeCompletionTokens", judgeCompletion);
            row.put("cost", cost);
            row.put("priced", cost != null);
            byColumn.add(row);
            totalPrompt += promptTokens + judgePrompt;
            totalCompletion += completionTokens + judgeCompletion;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("estimated", true);
        result.put("sampleCount", items.size());
        result.put("columnCount", columns.size());
        result.put("cellCount", (long) items.size() * columns.size());
        result.put("estimatedCalls", (long) items.size() * columns.size()
                * (1L + evaluators.size()));
        result.put("evaluators", evaluators.stream().map(EvaluatorEntity::getEvaluatorKey)
                .collect(java.util.stream.Collectors.toList()));
        result.put("promptTokens", totalPrompt);
        result.put("completionTokens", totalCompletion);
        result.put("byColumn", byColumn);
        result.put("total", total);
        result.put("currency", currency == null ? "USD" : currency);
        result.put("unpricedModels", new ArrayList<>(unpriced));
        result.put("priced", unpriced.isEmpty());
        return result;
    }

    // ==================== 受理 ====================

    public Map<String, Object> create(String workspaceId, String requestKey, CreateRequest request,
                                      String createdBy) {
        String key = requestKey == null ? "" : requestKey.trim();
        if (key.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        if (key.length() > 128) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能超过 128 字符");
        }
        if (!Boolean.TRUE.equals(request.getConfirmEstimate())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "矩阵会实际调用 LLM；请先调用 /matrices/estimate 确认成本后带 confirmEstimate=true 提交");
        }
        DatasetEntity dataset = requireDataset(workspaceId, request.getDatasetId());
        List<ColumnRequest> columns = normalizeColumns(request.getColumns());
        List<EvaluatorEntity> evaluators = loadEvaluators(workspaceId, request.getEvaluatorIds());
        int limit = sampleLimit(request);
        List<DatasetItemEntity> items = itemsFor(workspaceId, dataset, request.getRevisionId(), limit);
        if (items.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "数据集没有可用样本，无法跑矩阵");
        }
        List<Map<String, Object>> frozenColumns = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            frozenColumns.add(freezeColumn(workspaceId, columns.get(i), i));
        }
        long cells = (long) items.size() * frozenColumns.size();
        long calls = cells * (1L + evaluators.size());
        EmonLensProperties.Matrix limits = properties.getPlayground().getMatrix();
        if (cells > limits.getMaxCells()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "网格 " + cells + " 格超过上限 " + limits.getMaxCells() + "；请减少样本或列数");
        }
        if (calls > limits.getMaxCalls()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "外部调用 " + calls + " 次超过上限 " + limits.getMaxCalls() + "；请减少样本、列数或评估器");
        }
        Map<String, Object> estimate = estimateOf(workspaceId, frozenColumns, items, evaluators);
        String requestHash = Sha256Utils.hash(toJson(canonicalRequest(dataset.getId(),
                request.getRevisionId(), frozenColumns, evaluators)));

        synchronized (acceptLock) {
            PlaygroundMatrixEntity existing = matrixMapper.selectOne(
                    Wrappers.<PlaygroundMatrixEntity>lambdaQuery()
                            .eq(PlaygroundMatrixEntity::getWorkspaceId, workspaceId)
                            .eq(PlaygroundMatrixEntity::getRequestKey, key));
            if (existing != null) {
                if (!requestHash.equals(existing.getRequestHash())) {
                    throw new EmonLensException(ErrorCode.CONFLICT,
                            "同一 Idempotency-Key 已用于不同矩阵请求；请使用新的 key");
                }
                return detail(workspaceId, existing.getId());
            }
            PlaygroundMatrixEntity matrix = new PlaygroundMatrixEntity();
            matrix.setWorkspaceId(workspaceId);
            matrix.setRequestKey(key);
            matrix.setRequestHash(requestHash);
            matrix.setDatasetId(dataset.getId());
            matrix.setDatasetRevisionId(request.getRevisionId());
            matrix.setName(request.getName());
            matrix.setStatus(PlaygroundMatrixEntity.STATUS_PENDING);
            matrix.setColumnsJson(toJson(frozenColumns));
            matrix.setEvaluatorIdsJson(toJson(evaluators.stream().map(EvaluatorEntity::getId)
                    .collect(java.util.stream.Collectors.toList())));
            matrix.setEstimateJson(toJson(estimate));
            matrix.setItemCount(items.size());
            matrix.setCellCount((int) cells);
            matrix.setCompletedCells(0);
            matrix.setFailedCells(0);
            matrix.setPromptTokens(0L);
            matrix.setCompletionTokens(0L);
            matrix.setCreatedBy(createdBy);
            matrix.setCreatedAt(LocalDateTime.now());
            try {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    matrixMapper.insert(matrix);
                    int order = 0;
                    for (DatasetItemEntity item : items) {
                        for (Map<String, Object> column : frozenColumns) {
                            PlaygroundCellEntity cell = new PlaygroundCellEntity();
                            cell.setWorkspaceId(workspaceId);
                            cell.setMatrixId(matrix.getId());
                            cell.setColumnKey(String.valueOf(column.get("key")));
                            cell.setItemId(item.getId());
                            cell.setItemOrder(order);
                            cell.setStatus(PlaygroundCellEntity.STATUS_PENDING);
                            cell.setInputText(item.getInput());
                            cell.setExpectedText(item.getExpectedOutput());
                            cell.setRenderedPrompt(render(String.valueOf(column.get("prompt")),
                                    variables(column, item)));
                            cell.setCreatedAt(LocalDateTime.now());
                            cellMapper.insert(cell);
                        }
                        order++;
                    }
                });
            } catch (DuplicateKeyException e) {
                PlaygroundMatrixEntity raced = matrixMapper.selectOne(
                        Wrappers.<PlaygroundMatrixEntity>lambdaQuery()
                                .eq(PlaygroundMatrixEntity::getWorkspaceId, workspaceId)
                                .eq(PlaygroundMatrixEntity::getRequestKey, key));
                if (raced != null && requestHash.equals(raced.getRequestHash())) {
                    return detail(workspaceId, raced.getId());
                }
                throw new EmonLensException(ErrorCode.CONFLICT,
                        "同一 Idempotency-Key 已用于不同矩阵请求；请使用新的 key");
            }
            return detail(workspaceId, matrix.getId());
        }
    }

    private Map<String, Object> canonicalRequest(String datasetId, String revisionId,
                                                 List<Map<String, Object>> columns,
                                                 List<EvaluatorEntity> evaluators) {
        Map<String, Object> canonical = new java.util.TreeMap<>();
        canonical.put("datasetId", datasetId);
        canonical.put("revisionId", revisionId == null ? "" : revisionId);
        canonical.put("columns", columns);
        canonical.put("evaluatorIds", evaluators.stream().map(EvaluatorEntity::getId)
                .collect(java.util.stream.Collectors.toList()));
        return canonical;
    }

    // ==================== 查询 ====================

    public Map<String, Object> detail(String workspaceId, String matrixId) {
        PlaygroundMatrixEntity matrix = requireOwned(workspaceId, matrixId);
        List<Map<String, Object>> columns = parseListOfMaps(matrix.getColumnsJson());
        List<PlaygroundCellEntity> cells = cellsOf(matrix.getId());
        List<Map<String, Object>> diffs = diffSummaries(matrix.getId());
        Map<String, Object> map = matrixMap(matrix, columns);
        List<Map<String, Object>> cellViews = new ArrayList<>();
        for (PlaygroundCellEntity cell : cells) {
            cellViews.add(cellMap(cell));
        }
        map.put("cells", cellViews);
        map.put("diffs", diffs);
        return map;
    }

    public List<Map<String, Object>> list(String workspaceId, int limit) {
        List<PlaygroundMatrixEntity> rows = matrixMapper.selectList(
                Wrappers.<PlaygroundMatrixEntity>lambdaQuery()
                        .eq(PlaygroundMatrixEntity::getWorkspaceId, workspaceId)
                        .orderByDesc(PlaygroundMatrixEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(1, Math.min(limit, 100))));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlaygroundMatrixEntity row : rows) {
            hydrate(row);
            result.add(matrixMap(row, row.getColumns()));
        }
        return result;
    }

    /** 网格 CSV：一行一个样本，列按列定义展开（输出/状态/延迟/token/成本/评估结论）。 */
    public String exportCsv(String workspaceId, String matrixId) {
        PlaygroundMatrixEntity matrix = requireOwned(workspaceId, matrixId);
        List<Map<String, Object>> columns = parseListOfMaps(matrix.getColumnsJson());
        List<PlaygroundCellEntity> cells = cellsOf(matrix.getId());
        Map<String, Map<String, PlaygroundCellEntity>> byColumn = new LinkedHashMap<>();
        List<String> itemOrder = new ArrayList<>();
        Map<String, PlaygroundCellEntity> byItemAny = new LinkedHashMap<>();
        for (PlaygroundCellEntity cell : cells) {
            byColumn.computeIfAbsent(cell.getColumnKey(), k -> new LinkedHashMap<>())
                    .put(cell.getItemId(), cell);
            if (!byItemAny.containsKey(cell.getItemId())) {
                byItemAny.put(cell.getItemId(), cell);
                itemOrder.add(cell.getItemId());
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append(csv("item_id")).append(',').append(csv("输入")).append(',')
                .append(csv("期望输出"));
        for (Map<String, Object> column : columns) {
            String prefix = String.valueOf(column.get("label") == null
                    ? column.get("key") : column.get("label"));
            for (String field : List.of("输出", "状态", "延迟ms", "输入token", "输出token", "成本",
                    "评估结论", "得分", "错误")) {
                sb.append(',').append(csv(prefix + " / " + field));
            }
        }
        sb.append('\n');
        for (String itemId : itemOrder) {
            PlaygroundCellEntity head = byItemAny.get(itemId);
            sb.append(csv(itemId)).append(',').append(csv(head.getInputText())).append(',')
                    .append(csv(head.getExpectedText()));
            for (Map<String, Object> column : columns) {
                PlaygroundCellEntity cell = byColumn
                        .getOrDefault(String.valueOf(column.get("key")), Map.of()).get(itemId);
                sb.append(',');
                if (cell == null) {
                    sb.append(csv("")).append(',').append(csv("")).append(',')
                            .append(csv("")).append(',').append(csv("")).append(',')
                            .append(csv("")).append(',').append(csv("")).append(',')
                            .append(csv("")).append(',').append(csv("")).append(',')
                            .append(csv(""));
                    continue;
                }
                sb.append(csv(cell.getOutput())).append(',')
                        .append(csv(cell.getStatus())).append(',')
                        .append(cell.getLatencyMs() == null ? "" : cell.getLatencyMs()).append(',')
                        .append(cell.getPromptTokens() == null ? "" : cell.getPromptTokens()).append(',')
                        .append(cell.getCompletionTokens() == null ? "" : cell.getCompletionTokens()).append(',')
                        .append(cell.getCost() == null ? "" : cell.getCost().toPlainString()).append(',')
                        .append(csv(cell.getAggregateLabel())).append(',')
                        .append(cell.getScore() == null ? "" : cell.getScore().toPlainString()).append(',')
                        .append(csv(cell.getError()));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ==================== 执行（worker 调用） ====================

    public PlaygroundMatrixEntity claimNextPending() {
        PlaygroundMatrixEntity candidate = matrixMapper.selectOne(
                Wrappers.<PlaygroundMatrixEntity>lambdaQuery()
                        .eq(PlaygroundMatrixEntity::getStatus, PlaygroundMatrixEntity.STATUS_PENDING)
                        .orderByAsc(PlaygroundMatrixEntity::getCreatedAt)
                        .last("LIMIT 1"));
        if (candidate == null) {
            return null;
        }
        int updated = matrixMapper.update(null, Wrappers.<PlaygroundMatrixEntity>lambdaUpdate()
                .eq(PlaygroundMatrixEntity::getId, candidate.getId())
                .eq(PlaygroundMatrixEntity::getStatus, PlaygroundMatrixEntity.STATUS_PENDING)
                .set(PlaygroundMatrixEntity::getStatus, PlaygroundMatrixEntity.STATUS_RUNNING)
                .set(PlaygroundMatrixEntity::getStartedAt, LocalDateTime.now()));
        if (updated == 0) {
            return null;
        }
        return hydrate(matrixMapper.selectById(candidate.getId()));
    }

    public void execute(PlaygroundMatrixEntity matrix) {
        List<Map<String, Object>> columns = parseListOfMaps(matrix.getColumnsJson());
        List<EvaluatorEntity> evaluators = evaluatorsOf(matrix);
        List<PlaygroundCellEntity> cells = cellsOf(matrix.getId()).stream()
                .filter(c -> PlaygroundCellEntity.STATUS_PENDING.equals(c.getStatus()))
                .sorted(Comparator.comparingInt(c -> c.getItemOrder() == null ? 0 : c.getItemOrder()))
                .collect(java.util.stream.Collectors.toList());
        Map<String, Map<String, Object>> columnByKey = new LinkedHashMap<>();
        for (Map<String, Object> column : columns) {
            columnByKey.put(String.valueOf(column.get("key")), column);
        }
        int concurrency = Math.max(1, properties.getPlayground().getMatrix().getConcurrency());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(concurrency, Math.max(1, cells.size())),
                r -> {
                    Thread t = new Thread(r, "playground-matrix-" + matrix.getId());
                    t.setDaemon(true);
                    return t;
                });
        java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (PlaygroundCellEntity cell : cells) {
            futures.add(pool.submit(() -> {
                boolean ok = executeCell(matrix, cell, columnByKey.get(cell.getColumnKey()), evaluators);
                done.incrementAndGet();
                if (!ok) {
                    failed.incrementAndGet();
                }
                touchProgress(matrix.getId(), done.get(), failed.get());
            }));
        }
        pool.shutdown();
        try {
            pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        finish(matrix, failed.get() > 0);
    }

    private boolean executeCell(PlaygroundMatrixEntity matrix, PlaygroundCellEntity cell,
                                Map<String, Object> column, List<EvaluatorEntity> evaluators) {
        long start = System.currentTimeMillis();
        if (column == null) {
            markCellError(cell, "COLUMN_MISSING", "列定义不存在（矩阵可能被并发修改）");
            return false;
        }
        try {
            String system = column.get("systemPrompt") == null ? null
                    : String.valueOf(column.get("systemPrompt"));
            String model = String.valueOf(column.get("model"));
            double temperature = doubleOf(column.get("temperature"), 0.2);
            String prompt = cell.getRenderedPrompt() == null
                    ? render(String.valueOf(column.get("prompt")), Map.of())
                    : cell.getRenderedPrompt();
            LlmClient.ChatReply reply = llmClient.chatWithUsage(system == null || system.isBlank()
                    ? "You are a helpful assistant." : system, prompt, model, temperature);
            String output = reply.content();
            int latency = (int) (System.currentTimeMillis() - start);
            cell.setStatus(PlaygroundCellEntity.STATUS_OK);
            cell.setOutput(truncate(output, MAX_OUTPUT_CHARS));
            cell.setOutputHash(output == null ? null : Sha256Utils.hash(output));
            cell.setPromptTokens(reply.promptTokens());
            cell.setCompletionTokens(reply.completionTokens());
            cell.setLatencyMs(latency);
            cell.setError(null);
            BigDecimal generationCost = tokenCost(priceOf(matrix.getWorkspaceId(), model),
                    reply.promptTokens() == null ? 0 : reply.promptTokens(),
                    reply.completionTokens() == null ? 0 : reply.completionTokens());
            BigDecimal judgeCost = BigDecimal.ZERO;
            if (!evaluators.isEmpty()) {
                ScoreOutcome scores = score(matrix, column, cell, output, evaluators);
                cell.setScoresJson(toJson(scores.rows));
                cell.setAggregateLabel(scores.aggregateLabel);
                cell.setScore(scores.score);
                judgeCost = judgeCost.add(scores.cost);
            }
            cell.setCost(generationCost == null ? null : generationCost.add(judgeCost));
            cell.setFinishedAt(LocalDateTime.now());
            cellMapper.updateById(cell);
            return true;
        } catch (Exception e) {
            markCellError(cell, "GENERATION_FAILED", e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
            return false;
        }
    }

    private static class ScoreOutcome {
        private List<Map<String, Object>> rows = new ArrayList<>();
        private String aggregateLabel;
        private BigDecimal score;
        private BigDecimal cost = BigDecimal.ZERO;
    }

    private ScoreOutcome score(PlaygroundMatrixEntity matrix, Map<String, Object> column,
                               PlaygroundCellEntity cell, String output,
                               List<EvaluatorEntity> evaluators) {
        ScoreOutcome outcome = new ScoreOutcome();
        EvaluationSample sample = new EvaluationSample();
        sample.setItemId(cell.getItemId());
        sample.setInput(cell.getInputText());
        sample.setCandidateOutput(output);
        sample.setExpectedOutput(cell.getExpectedText());
        sample.setCandidateComplete(true);
        sample.setContentStatus("COMPLETE");
        sample.getContents().put("prompt", cell.getRenderedPrompt());
        if (output != null) {
            sample.getContents().put("completion", output);
            sample.getContentCaptureModes().put("completion", "FULL");
            sample.getContentTruncated().put("completion", false);
        }
        for (EvaluatorEntity evaluator : evaluators) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("evaluatorKey", evaluator.getEvaluatorKey());
            row.put("type", evaluator.getType());
            try {
                io.emonlens.server.storage.entity.EvalResultEntity result =
                        evaluationRunService.evaluateSample(evaluator, sample);
                String label = result.getLabel();
                row.put("label", label);
                row.put("score", result.getScore());
                row.put("reasonCode", result.getReasonCode());
                row.put("reason", truncate(result.getReason(), 2000));
                row.put("judgeModel", result.getJudgeModel());
                row.put("cacheHit", Boolean.TRUE.equals(result.getCacheHit()));
                BigDecimal judgeCost = tokenCost(priceOf(matrix.getWorkspaceId(),
                                result.getJudgeModel() == null
                                        ? String.valueOf(column.get("model")) : result.getJudgeModel()),
                        result.getPromptTokens() == null ? 0 : result.getPromptTokens(),
                        result.getCompletionTokens() == null ? 0 : result.getCompletionTokens());
                if (judgeCost != null) {
                    outcome.cost = outcome.cost.add(judgeCost);
                }
            } catch (Exception e) {
                row.put("label", EvaluatorEntity.LABEL_ERROR);
                row.put("reasonCode", "EVALUATOR_EXECUTION_FAILED");
                row.put("reason", truncate(e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage(), 2000));
            }
            outcome.rows.add(row);
        }
        outcome.aggregateLabel = RunItemAggregator.aggregateLabel(outcome.rows);
        BigDecimal avg = RunItemAggregator.averageScore(outcome.rows);
        outcome.score = avg;
        return outcome;
    }

    private void markCellError(PlaygroundCellEntity cell, String code, String message) {
        cell.setStatus(PlaygroundCellEntity.STATUS_ERROR);
        cell.setError(truncate(code + ": " + message, 1000));
        cell.setFinishedAt(LocalDateTime.now());
        cellMapper.updateById(cell);
    }

    private void touchProgress(String matrixId, int completed, int failed) {
        try {
            matrixMapper.update(null, Wrappers.<PlaygroundMatrixEntity>lambdaUpdate()
                    .eq(PlaygroundMatrixEntity::getId, matrixId)
                    .set(PlaygroundMatrixEntity::getCompletedCells, completed)
                    .set(PlaygroundMatrixEntity::getFailedCells, failed));
        } catch (Exception e) {
            log.warn("matrix progress update failed, matrix={}", matrixId, e);
        }
    }

    private void finish(PlaygroundMatrixEntity matrix, boolean partial) {
        List<PlaygroundCellEntity> cells = cellsOf(matrix.getId());
        long promptTokens = 0;
        long completionTokens = 0;
        BigDecimal cost = BigDecimal.ZERO;
        boolean anyCost = false;
        Set<String> unpriced = new LinkedHashSet<>();
        for (PlaygroundCellEntity cell : cells) {
            promptTokens += cell.getPromptTokens() == null ? 0 : cell.getPromptTokens();
            completionTokens += cell.getCompletionTokens() == null ? 0 : cell.getCompletionTokens();
            if (cell.getCost() != null) {
                cost = cost.add(cell.getCost());
                anyCost = true;
            } else if (PlaygroundCellEntity.STATUS_OK.equals(cell.getStatus())) {
                unpriced.add("unknown");
            }
        }
        String currency = anyCost ? currencyOf(matrix) : null;
        matrixMapper.update(null, Wrappers.<PlaygroundMatrixEntity>lambdaUpdate()
                .eq(PlaygroundMatrixEntity::getId, matrix.getId())
                .set(PlaygroundMatrixEntity::getStatus, partial
                        ? PlaygroundMatrixEntity.STATUS_PARTIAL : PlaygroundMatrixEntity.STATUS_COMPLETED)
                .set(PlaygroundMatrixEntity::getPromptTokens, promptTokens)
                .set(PlaygroundMatrixEntity::getCompletionTokens, completionTokens)
                .set(PlaygroundMatrixEntity::getCost, anyCost ? cost : null)
                .set(PlaygroundMatrixEntity::getCurrency, currency)
                .set(PlaygroundMatrixEntity::getUnpricedModels,
                        unpriced.isEmpty() ? null : String.join(",", unpriced))
                .set(PlaygroundMatrixEntity::getFinishedAt, LocalDateTime.now()));
    }

    /** 币种取自分列模型的价目：同一矩阵混用不同币种时按第一条价目展示，并在 estimate 里保留分列明细。 */
    private String currencyOf(PlaygroundMatrixEntity matrix) {
        for (Map<String, Object> column : matrix.getColumns() == null
                ? List.<Map<String, Object>>of() : matrix.getColumns()) {
            ModelPriceEntity price = priceOf(matrix.getWorkspaceId(),
                    String.valueOf(column.get("model")));
            if (price != null && price.getCurrency() != null) {
                return price.getCurrency();
            }
        }
        return "USD";
    }

    // ==================== Diff ====================

    public Map<String, Object> diff(String workspaceId, String matrixId, DiffRequest request,
                                    String createdBy) {
        PlaygroundMatrixEntity matrix = requireOwned(workspaceId, matrixId);
        if (!PlaygroundMatrixEntity.STATUS_COMPLETED.equals(matrix.getStatus())
                && !PlaygroundMatrixEntity.STATUS_PARTIAL.equals(matrix.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "矩阵尚未跑完，无法 Diff");
        }
        List<Map<String, Object>> columns = parseListOfMaps(matrix.getColumnsJson());
        String base = request.getBaseColumn();
        String target = request.getTargetColumn();
        if (base == null || target == null || base.equals(target)) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "baseColumn/targetColumn 必须不同");
        }
        for (String key : List.of(base, target)) {
            if (columns.stream().noneMatch(c -> key.equals(String.valueOf(c.get("key"))))) {
                throw new EmonLensException(ErrorCode.NOT_FOUND, "列不存在: " + key);
            }
        }
        List<PlaygroundCellEntity> cells = cellsOf(matrix.getId());
        Map<String, PlaygroundCellEntity> baseCells = new LinkedHashMap<>();
        Map<String, PlaygroundCellEntity> targetCells = new LinkedHashMap<>();
        List<String> itemOrder = new ArrayList<>();
        for (PlaygroundCellEntity cell : cells) {
            if (base.equals(cell.getColumnKey())) {
                baseCells.put(cell.getItemId(), cell);
                itemOrder.add(cell.getItemId());
            } else if (target.equals(cell.getColumnKey())) {
                targetCells.put(cell.getItemId(), cell);
            }
        }
        if (baseCells.isEmpty() || targetCells.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "两列都没有可比较的单元格");
        }
        int samples = request.getSamplesPerDirection() == null ? 1 : request.getSamplesPerDirection();
        if (samples < 1 || samples > 3) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "samplesPerDirection 只允许 1..3");
        }
        List<String> models = judgeModels(request);
        boolean randomize = Boolean.TRUE.equals(request.getPositionRandomized());
        long seed = request.getPositionSeed() == null
                ? java.util.concurrent.ThreadLocalRandom.current().nextLong()
                : request.getPositionSeed();
        java.util.Random random = randomize ? new java.util.Random(seed) : null;

        PlaygroundDiffEntity diff = new PlaygroundDiffEntity();
        diff.setWorkspaceId(workspaceId);
        diff.setMatrixId(matrix.getId());
        diff.setBaseColumn(base);
        diff.setTargetColumn(target);
        diff.setProtocolVersion(randomize ? "playground-diff/v2" : "playground-diff/v1");
        diff.setJudgeModelsJson(toJson(models));
        diff.setPositionRandomized(randomize);
        diff.setPositionSeed(randomize ? seed : null);
        diff.setStatus(PlaygroundDiffEntity.STATUS_COMPLETED);
        diff.setCreatedBy(createdBy);
        diff.setCreatedAt(LocalDateTime.now());

        List<PlaygroundDiffItemEntity> rows = new ArrayList<>();
        int same = 0;
        int changed = 0;
        int improved = 0;
        int regressed = 0;
        int tradeoff = 0;
        int tie = 0;
        int unknown = 0;
        int[] positionCounts = new int[2];
        for (String itemId : itemOrder) {
            PlaygroundCellEntity a = baseCells.get(itemId);
            PlaygroundCellEntity b = targetCells.get(itemId);
            PlaygroundDiffItemEntity row = new PlaygroundDiffItemEntity();
            row.setWorkspaceId(workspaceId);
            row.setItemId(itemId);
            if (a == null || b == null || a.getOutput() == null || b.getOutput() == null) {
                row.setDeterministic(PlaygroundDiffItemEntity.DET_MISSING);
                row.setPreference(PlaygroundDiffItemEntity.PREF_UNKNOWN);
                row.setReasonCode("OUTPUT_MISSING");
                row.setReason("有一列没有输出（单元格失败或未执行）");
                unknown++;
                rows.add(row);
                continue;
            }
            Map<String, Object> delta = scoreDelta(a, b);
            row.setScoreDeltaJson(toJson(delta));
            if (Objects.equals(a.getOutputHash(), b.getOutputHash())
                    || normalize(a.getOutput()).equals(normalize(b.getOutput()))) {
                row.setDeterministic(PlaygroundDiffItemEntity.DET_SAME);
                row.setPreference(PlaygroundDiffItemEntity.PREF_TIE);
                row.setReasonCode("DETERMINISTIC_SAME");
                row.setReason("两列输出一致");
                same++;
                tie++;
                rows.add(row);
                continue;
            }
            row.setDeterministic(PlaygroundDiffItemEntity.DET_CHANGED);
            changed++;
            // 目标列作为候选 B：B 胜 = 改进，A 胜 = 退步
            io.emonlens.server.engine.eval.PairwiseJudge.Verdict verdict = pairwiseJudge.judge(
                    a.getInputText(), a.getExpectedText(), a.getOutput(), b.getOutput(),
                    models, samples, randomize, random, positionCounts);
            String preference;
            String reasonCode = verdict.getReasonCode();
            switch (verdict.getPreference()) {
                case "B":
                    preference = PlaygroundDiffItemEntity.PREF_IMPROVEMENT;
                    break;
                case "A":
                    preference = PlaygroundDiffItemEntity.PREF_REGRESSION;
                    break;
                case "TIE":
                    preference = PlaygroundDiffItemEntity.PREF_TIE;
                    break;
                default:
                    preference = PlaygroundDiffItemEntity.PREF_UNKNOWN;
                    break;
            }
            // 语义偏好与评估结论冲突时给 TRADEOFF：宁可标“有得有失”，也不掩盖冲突。
            if (Boolean.TRUE.equals(delta.get("scoreRegressed"))
                    && PlaygroundDiffItemEntity.PREF_IMPROVEMENT.equals(preference)) {
                preference = PlaygroundDiffItemEntity.PREF_TRADEOFF;
                reasonCode = "PREFERENCE_UP_SCORE_DOWN";
            } else if (Boolean.TRUE.equals(delta.get("scoreImproved"))
                    && PlaygroundDiffItemEntity.PREF_REGRESSION.equals(preference)) {
                preference = PlaygroundDiffItemEntity.PREF_TRADEOFF;
                reasonCode = "PREFERENCE_DOWN_SCORE_UP";
            }
            row.setPreference(preference);
            row.setReasonCode(reasonCode);
            row.setReason(truncate(verdict.getReason(), 512));
            row.setCallsJson(toJson(verdict.getCalls()));
            switch (preference) {
                case PlaygroundDiffItemEntity.PREF_IMPROVEMENT:
                    improved++;
                    break;
                case PlaygroundDiffItemEntity.PREF_REGRESSION:
                    regressed++;
                    break;
                case PlaygroundDiffItemEntity.PREF_TRADEOFF:
                    tradeoff++;
                    break;
                case PlaygroundDiffItemEntity.PREF_TIE:
                    tie++;
                    break;
                default:
                    unknown++;
                    break;
            }
            rows.add(row);
        }
        diff.setItemCount(itemOrder.size());
        diff.setSameCount(same);
        diff.setChangedCount(changed);
        diff.setImprovedCount(improved);
        diff.setRegressedCount(regressed);
        diff.setTradeoffCount(tradeoff);
        diff.setTieCount(tie);
        diff.setUnknownCount(unknown);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            diffMapper.insert(diff);
            for (PlaygroundDiffItemEntity row : rows) {
                row.setDiffId(diff.getId());
                diffItemMapper.insert(row);
            }
        });
        return diffDetail(workspaceId, diff.getId());
    }

    public Map<String, Object> diffDetail(String workspaceId, String diffId) {
        return diffDetail(workspaceId, null, diffId);
    }

    /** 矩阵维度校验：URL 上的 matrixId 必须与该 diff 的归属一致，避免跨矩阵探测。 */
    public Map<String, Object> diffDetail(String workspaceId, String matrixId, String diffId) {
        PlaygroundDiffEntity diff = diffMapper.selectById(diffId);
        if (diff == null || !workspaceId.equals(diff.getWorkspaceId())
                || (matrixId != null && !matrixId.equals(diff.getMatrixId()))) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "diff not found");
        }
        List<PlaygroundDiffItemEntity> rows = diffItemMapper.selectList(
                Wrappers.<PlaygroundDiffItemEntity>lambdaQuery()
                        .eq(PlaygroundDiffItemEntity::getDiffId, diffId));
        Map<String, Object> map = diffMap(diff);
        List<Map<String, Object>> items = new ArrayList<>();
        for (PlaygroundDiffItemEntity row : rows) {
            items.add(diffItemMap(row));
        }
        map.put("items", items);
        return map;
    }

    private Map<String, Object> scoreDelta(PlaygroundCellEntity a, PlaygroundCellEntity b) {
        Map<String, Object> delta = new LinkedHashMap<>();
        if (a.getAggregateLabel() == null && b.getAggregateLabel() == null) {
            return delta;
        }
        delta.put("baseLabel", a.getAggregateLabel());
        delta.put("targetLabel", b.getAggregateLabel());
        delta.put("baseScore", a.getScore());
        delta.put("targetScore", b.getScore());
        boolean basePass = EvaluatorEntity.LABEL_PASS.equals(a.getAggregateLabel());
        boolean targetPass = EvaluatorEntity.LABEL_PASS.equals(b.getAggregateLabel());
        boolean baseFail = EvaluatorEntity.LABEL_FAIL.equals(a.getAggregateLabel());
        boolean targetFail = EvaluatorEntity.LABEL_FAIL.equals(b.getAggregateLabel());
        delta.put("scoreImproved", !basePass && targetPass);
        delta.put("scoreRegressed", basePass && targetFail);
        return delta;
    }

    private List<Map<String, Object>> diffSummaries(String matrixId) {
        List<PlaygroundDiffEntity> rows = diffMapper.selectList(
                Wrappers.<PlaygroundDiffEntity>lambdaQuery()
                        .eq(PlaygroundDiffEntity::getMatrixId, matrixId)
                        .orderByDesc(PlaygroundDiffEntity::getCreatedAt));
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlaygroundDiffEntity row : rows) {
            result.add(diffMap(row));
        }
        return result;
    }

    // ==================== 升格（R1-3） ====================

    /** 把矩阵列一键升格为正式 dataset run：沿用冻结、指纹与 Gate 口径，prompt 版本随之绑定。 */
    public Map<String, Object> promote(String workspaceId, String matrixId, String columnKey,
                                       PromoteRequest request, String createdBy) {
        PlaygroundMatrixEntity matrix = requireOwned(workspaceId, matrixId);
        Map<String, Object> column = parseListOfMaps(matrix.getColumnsJson()).stream()
                .filter(c -> columnKey != null && columnKey.equals(String.valueOf(c.get("key"))))
                .findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.NOT_FOUND, "列不存在: " + columnKey));
        List<String> evaluatorIds = matrix.getEvaluatorIds() == null
                ? List.of() : matrix.getEvaluatorIds();
        if (evaluatorIds.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "矩阵没有挂评估器，无法升格为正式实验；请重建矩阵并在 evaluatorIds 里指定");
        }
        PromptService.PromptRef promptRef = null;
        if (column.get("promptName") != null) {
            promptRef = new PromptService.PromptRef(String.valueOf(column.get("promptName")),
                    column.get("promptVersion") instanceof Number
                            ? ((Number) column.get("promptVersion")).intValue() : null,
                    column.get("promptLabel") == null ? null : String.valueOf(column.get("promptLabel")),
                    null);
        }
        // 幂等键由 matrix + column 推导：重复点击“转正式实验”返回同一条 run，不会重复计费。
        String requestKey = "matrix:" + matrix.getId() + ":column:" + columnKey;
        DatasetRunService.AcceptResult result = datasetRunService.accept(workspaceId,
                matrix.getDatasetId(), evaluatorIds, matrix.getDatasetRevisionId(),
                "REPLAY", column.get("systemPrompt") == null ? null
                        : String.valueOf(column.get("systemPrompt")),
                column.get("model") == null ? null : String.valueOf(column.get("model")),
                request.getCandidateLabel() == null
                        ? "matrix:" + columnKey + " / " + column.get("label") : request.getCandidateLabel(),
                null, null, null, null, null, requestKey, null, promptRef,
                request.getRunLabel(), request.getCommitSha());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", result.getRun().getId());
        payload.put("created", result.isCreated());
        payload.put("matrixId", matrix.getId());
        payload.put("columnKey", columnKey);
        payload.put("status", result.getRun().getStatus());
        payload.put("promptName", result.getRun().getPromptName());
        payload.put("promptVersion", result.getRun().getPromptVersion());
        payload.put("datasetId", matrix.getDatasetId());
        payload.put("createdBy", createdBy);
        return payload;
    }

    // ==================== 内部工具 ====================

    private PlaygroundMatrixEntity requireOwned(String workspaceId, String matrixId) {
        PlaygroundMatrixEntity matrix = matrixId == null ? null : matrixMapper.selectById(matrixId);
        if (matrix == null || !Objects.equals(matrix.getWorkspaceId(), workspaceId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "playground matrix not found");
        }
        return hydrate(matrix);
    }

    private PlaygroundMatrixEntity hydrate(PlaygroundMatrixEntity matrix) {
        matrix.setColumns(parseListOfMaps(matrix.getColumnsJson()));
        matrix.setEvaluatorIds(parseStringList(matrix.getEvaluatorIdsJson()));
        matrix.setEstimate(parseMap(matrix.getEstimateJson()));
        return matrix;
    }

    private DatasetEntity requireDataset(String workspaceId, String datasetId) {
        if (datasetId == null || datasetId.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "datasetId is required");
        }
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !workspaceId.equals(dataset.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found");
        }
        return dataset;
    }

    private List<ColumnRequest> normalizeColumns(List<ColumnRequest> raw) {
        if (raw == null || raw.size() < 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "至少需要 1 列（建议 2 列以上才好 Diff）");
        }
        int max = properties.getPlayground().getMatrix().getMaxColumns();
        if (raw.size() > max) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "列数最多 " + max);
        }
        List<ColumnRequest> columns = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        int index = 0;
        for (ColumnRequest column : raw) {
            index++;
            if (column == null) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "列定义不能为空");
            }
            String key = column.getKey() == null || column.getKey().isBlank()
                    ? "c" + index : column.getKey().trim();
            if (!key.matches("[A-Za-z0-9_-]{1,64}")) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "列 key 只允许字母数字与 -_，最长 64: " + key);
            }
            if (!keys.add(key)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "列 key 重复: " + key);
            }
            column.setKey(key);
            if (column.getTemperature() != null
                    && (column.getTemperature() < 0 || column.getTemperature() > 2)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "temperature 只允许 0..2");
            }
            columns.add(column);
        }
        return columns;
    }

    private List<EvaluatorEntity> loadEvaluators(String workspaceId, List<String> evaluatorIds) {
        if (evaluatorIds == null || evaluatorIds.isEmpty()) {
            return List.of();
        }
        int max = properties.getPlayground().getMatrix().getMaxEvaluators();
        if (evaluatorIds.size() > max) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "评估器最多 " + max + " 个");
        }
        List<EvaluatorEntity> evaluators = new ArrayList<>();
        for (String id : new LinkedHashSet<>(evaluatorIds)) {
            EvaluatorEntity evaluator = evaluatorMapper.selectById(id);
            if (evaluator == null || !workspaceId.equals(evaluator.getWorkspaceId())) {
                throw new EmonLensException(ErrorCode.NOT_FOUND, "evaluator not found: " + id);
            }
            if (EvaluatorEntity.TYPE_SDK.equals(evaluator.getType())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "矩阵不支持 SDK 评估器（需要外部回写）；请在升格为 run 后使用");
            }
            evaluators.add(evaluator);
        }
        return evaluators;
    }

    private List<EvaluatorEntity> evaluatorsOf(PlaygroundMatrixEntity matrix) {
        if (matrix.getEvaluatorIds() == null || matrix.getEvaluatorIds().isEmpty()) {
            return List.of();
        }
        List<EvaluatorEntity> evaluators = new ArrayList<>();
        for (String id : matrix.getEvaluatorIds()) {
            EvaluatorEntity evaluator = evaluatorMapper.selectById(id);
            if (evaluator != null) {
                evaluators.add(evaluator);
            }
        }
        return evaluators;
    }

    private int sampleLimit(CreateRequest request) {
        int max = properties.getPlayground().getMatrix().getMaxSamples();
        int limit = request.getSampleLimit() == null ? max : request.getSampleLimit();
        if (limit < 1) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "sampleLimit 至少为 1");
        }
        return Math.min(limit, max);
    }

    private List<DatasetItemEntity> itemsFor(String workspaceId, DatasetEntity dataset, String revisionId,
                                             int limit) {
        List<DatasetItemEntity> items;
        if (revisionId != null && !revisionId.isBlank()) {
            items = revisionService.toItems(revisionService.requireOwned(workspaceId,
                    dataset.getId(), revisionId));
        } else {
            items = datasetItemMapper.selectList(Wrappers.<DatasetItemEntity>lambdaQuery()
                    .eq(DatasetItemEntity::getDatasetId, dataset.getId())
                    .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                            .or().isNull(DatasetItemEntity::getStatus)));
        }
        List<DatasetItemEntity> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparing(DatasetItemEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DatasetItemEntity::getId));
        return ordered.size() > limit ? new ArrayList<>(ordered.subList(0, limit)) : ordered;
    }

    /** 冻结一列：prompt 正文在此刻定版，之后改 prompt 库不影响已受理矩阵。 */
    private Map<String, Object> freezeColumn(String workspaceId, ColumnRequest request, int index) {
        String prompt;
        String promptName = null;
        Integer promptVersion = null;
        String promptLabel = null;
        String promptHash = null;
        if (request.getPromptName() != null && !request.getPromptName().isBlank()) {
            PromptEntity entity = promptService.resolve(workspaceId, request.getPromptName().trim(),
                    request.getPromptVersion(), request.getPromptLabel());
            prompt = entity.getPrompt();
            promptName = entity.getName();
            promptVersion = entity.getVersion();
            promptLabel = request.getPromptLabel() == null || request.getPromptLabel().isBlank()
                    ? (request.getPromptVersion() == null
                        ? PromptService.LABEL_PRODUCTION : PromptService.LABEL_LATEST)
                    : request.getPromptLabel().trim();
            promptHash = promptService.contentHash(entity);
            promptService.recordResolution(entity, promptLabel, PromptService.SOURCE_MATRIX, null);
        } else {
            prompt = request.getPrompt();
        }
        if (prompt == null || prompt.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "第 " + index + " 列缺少 prompt（给 promptName 或 prompt 正文）");
        }
        Map<String, Object> column = new LinkedHashMap<>();
        column.put("key", request.getKey());
        column.put("label", request.getLabel() == null || request.getLabel().isBlank()
                ? request.getKey() : request.getLabel().trim());
        column.put("promptName", promptName);
        column.put("promptVersion", promptVersion);
        column.put("promptLabel", promptLabel);
        column.put("promptHash", promptHash);
        column.put("prompt", prompt);
        column.put("systemPrompt", request.getSystemPrompt());
        column.put("model", effectiveModel(request.getModel()));
        column.put("temperature", request.getTemperature() == null ? 0.2 : request.getTemperature());
        column.put("maxTokens", request.getMaxTokens() == null
                ? DEFAULT_MAX_TOKENS : request.getMaxTokens());
        column.put("variables", request.getVariables() == null ? Map.of() : request.getVariables());
        column.put("variableSource", request.getVariableSource() == null
                ? Map.of() : request.getVariableSource());
        return column;
    }

    private String effectiveModel(String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        io.emonlens.server.config.SysSettingService.EvalLlm cfg = llmClient.effectiveConfig();
        if (cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()) {
            return cfg.getModel();
        }
        return "gpt-4o-mini";
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> variables(Map<String, Object> column, DatasetItemEntity item) {
        Map<String, String> fixed = column.get("variables") instanceof Map
                ? (Map<String, String>) column.get("variables") : Map.of();
        Map<String, String> sources = column.get("variableSource") instanceof Map
                ? (Map<String, String>) column.get("variableSource") : Map.of();
        List<String> names = new ArrayList<>();
        Matcher matcher = VAR_PATTERN.matcher(String.valueOf(column.get("prompt")));
        while (matcher.find()) {
            if (!names.contains(matcher.group(1))) {
                names.add(matcher.group(1));
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            String source = sources.get(name);
            if (source == null) {
                // 默认映射：第一个变量取样本输入，其余取列上的固定值
                source = i == 0 ? "input" : "fixed";
            }
            switch (source) {
                case "input":
                    values.put(name, item.getInput() == null ? "" : item.getInput());
                    break;
                case "expected":
                    values.put(name, item.getExpectedOutput() == null ? "" : item.getExpectedOutput());
                    break;
                default:
                    values.put(name, fixed.getOrDefault(name, ""));
                    break;
            }
        }
        return values;
    }

    private String render(String template, Map<String, String> variables) {
        if (template == null) {
            return "";
        }
        Matcher matcher = VAR_PATTERN.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                    value == null ? matcher.group(0) : value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private List<String> judgeModels(DiffRequest request) {
        List<String> models = new ArrayList<>();
        if (request.getJudgeModels() != null) {
            for (String model : request.getJudgeModels()) {
                if (model == null || model.isBlank()) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "judgeModels 不能包含空值");
                }
                String trimmed = model.trim();
                if (!models.contains(trimmed)) {
                    models.add(trimmed);
                }
            }
        }
        if (models.isEmpty()) {
            io.emonlens.server.config.SysSettingService.EvalLlm cfg = llmClient.effectiveConfig();
            models.add(cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()
                    ? cfg.getModel() : "gpt-4o-mini");
        }
        if (models.size() > 3) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "judgeModels 最多 3 个");
        }
        return models;
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
        if (price == null) {
            return null;
        }
        return price.getInputPerMillion().multiply(BigDecimal.valueOf(prompt))
                .add(price.getOutputPerMillion().multiply(BigDecimal.valueOf(completion)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (text.length() + ESTIMATE_CHARS_PER_TOKEN - 1L) / ESTIMATE_CHARS_PER_TOKEN;
    }

    private List<PlaygroundCellEntity> cellsOf(String matrixId) {
        return cellMapper.selectList(Wrappers.<PlaygroundCellEntity>lambdaQuery()
                .eq(PlaygroundCellEntity::getMatrixId, matrixId)
                .orderByAsc(PlaygroundCellEntity::getItemOrder));
    }

    private Map<String, Object> matrixMap(PlaygroundMatrixEntity matrix,
                                          List<Map<String, Object>> columns) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", matrix.getId());
        map.put("datasetId", matrix.getDatasetId());
        map.put("datasetRevisionId", matrix.getDatasetRevisionId());
        map.put("name", matrix.getName());
        map.put("status", matrix.getStatus());
        map.put("columns", columns);
        map.put("evaluatorIds", matrix.getEvaluatorIds());
        map.put("estimate", parseMap(matrix.getEstimateJson()));
        map.put("itemCount", matrix.getItemCount());
        map.put("cellCount", matrix.getCellCount());
        map.put("completedCells", matrix.getCompletedCells());
        map.put("failedCells", matrix.getFailedCells());
        map.put("promptTokens", matrix.getPromptTokens());
        map.put("completionTokens", matrix.getCompletionTokens());
        map.put("cost", matrix.getCost());
        map.put("currency", matrix.getCurrency());
        map.put("unpricedModels", matrix.getUnpricedModels());
        map.put("error", matrix.getError());
        map.put("createdBy", matrix.getCreatedBy());
        map.put("createdAt", matrix.getCreatedAt());
        map.put("startedAt", matrix.getStartedAt());
        map.put("finishedAt", matrix.getFinishedAt());
        return map;
    }

    private Map<String, Object> cellMap(PlaygroundCellEntity cell) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", cell.getId());
        map.put("columnKey", cell.getColumnKey());
        map.put("itemId", cell.getItemId());
        map.put("itemOrder", cell.getItemOrder());
        map.put("status", cell.getStatus());
        map.put("output", cell.getOutput());
        map.put("outputHash", cell.getOutputHash());
        map.put("renderedPrompt", cell.getRenderedPrompt());
        map.put("input", cell.getInputText());
        map.put("expectedOutput", cell.getExpectedText());
        map.put("error", cell.getError());
        map.put("promptTokens", cell.getPromptTokens());
        map.put("completionTokens", cell.getCompletionTokens());
        map.put("latencyMs", cell.getLatencyMs());
        map.put("cost", cell.getCost());
        map.put("scores", parseListOfMaps(cell.getScoresJson()));
        map.put("aggregateLabel", cell.getAggregateLabel());
        map.put("score", cell.getScore());
        return map;
    }

    private Map<String, Object> diffMap(PlaygroundDiffEntity diff) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", diff.getId());
        map.put("matrixId", diff.getMatrixId());
        map.put("baseColumn", diff.getBaseColumn());
        map.put("targetColumn", diff.getTargetColumn());
        map.put("protocolVersion", diff.getProtocolVersion());
        map.put("judgeModels", parseStringList(diff.getJudgeModelsJson()));
        map.put("positionRandomized", diff.getPositionRandomized());
        map.put("positionSeed", diff.getPositionSeed());
        map.put("itemCount", diff.getItemCount());
        map.put("sameCount", diff.getSameCount());
        map.put("changedCount", diff.getChangedCount());
        map.put("improvedCount", diff.getImprovedCount());
        map.put("regressedCount", diff.getRegressedCount());
        map.put("tradeoffCount", diff.getTradeoffCount());
        map.put("tieCount", diff.getTieCount());
        map.put("unknownCount", diff.getUnknownCount());
        map.put("status", diff.getStatus());
        map.put("createdAt", diff.getCreatedAt());
        return map;
    }

    private Map<String, Object> diffItemMap(PlaygroundDiffItemEntity row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("itemId", row.getItemId());
        map.put("deterministic", row.getDeterministic());
        map.put("preference", row.getPreference());
        map.put("reasonCode", row.getReasonCode());
        map.put("reason", row.getReason());
        map.put("scoreDelta", parseMap(row.getScoreDeltaJson()));
        return map;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().replace("\r\n", "\n").replaceAll("[ \t]+", " ");
    }

    private String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\r\n", " ").replace("\n", " ").replace("\r", " ");
        if (escaped.contains(",") || escaped.contains("\"")) {
            return "\"" + escaped.replace("\"", "\"\"") + "\"";
        }
        return escaped;
    }

    private String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private static int intOf(Object value, int fallback) {
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }

    private static double doubleOf(Object value, double fallback) {
        return value instanceof Number ? ((Number) value).doubleValue() : fallback;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "json 序列化失败");
        }
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

    private List<Map<String, Object>> parseListOfMaps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
