package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.eval.DatasetReplayService;
import io.emonlens.server.engine.eval.DatasetRevisionService;
import io.emonlens.server.engine.eval.DatasetHealthService;
import io.emonlens.server.engine.eval.DatasetRunComparisonService;
import io.emonlens.server.engine.eval.DatasetRunService;
import io.emonlens.server.engine.eval.EvaluationRunService;
import io.emonlens.server.engine.eval.EvalTaskService;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetHealthSnapshotEntity;
import io.emonlens.server.storage.entity.DatasetRunSlotEntity;
import io.emonlens.server.storage.entity.AutoRubricCandidateEntity;
import io.emonlens.server.storage.entity.AutoRubricRunEntity;
import io.emonlens.server.storage.entity.PairwiseEvaluationEntity;
import io.emonlens.server.storage.entity.PairwiseHumanReviewEntity;
import io.emonlens.server.storage.entity.PairwiseItemEntity;
import io.emonlens.server.storage.entity.QualityCaseEntity;
import io.emonlens.server.storage.entity.QualityGateEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.DatasetRevisionEntity;
import io.emonlens.server.storage.entity.DatasetRevisionItemEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetHealthSnapshotMapper;
import io.emonlens.server.storage.mapper.DatasetRunSlotMapper;
import io.emonlens.server.storage.mapper.AutoRubricCandidateMapper;
import io.emonlens.server.storage.mapper.AutoRubricRunMapper;
import io.emonlens.server.storage.mapper.PairwiseEvaluationMapper;
import io.emonlens.server.storage.mapper.PairwiseHumanReviewMapper;
import io.emonlens.server.storage.mapper.PairwiseItemMapper;
import io.emonlens.server.storage.mapper.QualityCaseMapper;
import io.emonlens.server.storage.mapper.QualityGateMapper;
import io.emonlens.server.storage.mapper.DatasetRevisionItemMapper;
import io.emonlens.server.storage.mapper.DatasetRevisionMapper;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;
import io.emonlens.server.storage.mapper.DatasetRunMapper;
import io.emonlens.server.storage.mapper.JudgeReviewMapper;
import io.emonlens.server.storage.mapper.EvaluatorMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Datasets & Experiments（对标 Langfuse/LangSmith 数据集与实验）：
 * 数据集 = 带期望输出的样本集合；运行实验 = 用指定评估器对数据集中样本逐条评估并记录结果。
 *
 * 试点链路统一走 POST /{id}/runs/async：受理即返回 runId，后台单线程执行。
 * 旧同步入口保留原响应形状，但 pilot-mode=true 时停止接受新评测。
 */
@RestController
@RequestMapping("/api/v1/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetMapper datasetMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final DatasetRunMapper datasetRunMapper;
    private final DatasetRunItemMapper datasetRunItemMapper;
    private final EvaluatorMapper evaluatorMapper;
    private final EvaluationRunService evaluationRunService;
    private final DatasetReplayService datasetReplayService;
    private final DatasetRunService datasetRunService;
    private final DatasetRunComparisonService datasetRunComparisonService;
    private final DatasetHealthService datasetHealthService;
    private final DatasetRevisionService revisionService;
    private final EvalTaskService evalTaskService;
    private final DatasetRevisionMapper revisionMapper;
    private final DatasetRevisionItemMapper revisionItemMapper;
    private final JudgeReviewMapper judgeReviewMapper;
    private final DatasetHealthSnapshotMapper healthSnapshotMapper;
    private final DatasetRunSlotMapper runSlotMapper;
    private final QualityGateMapper qualityGateMapper;
    private final PairwiseEvaluationMapper pairwiseEvaluationMapper;
    private final PairwiseItemMapper pairwiseItemMapper;
    private final PairwiseHumanReviewMapper pairwiseHumanReviewMapper;
    private final AutoRubricRunMapper autoRubricRunMapper;
    private final AutoRubricCandidateMapper autoRubricCandidateMapper;
    private final QualityCaseMapper qualityCaseMapper;
    private final io.emonlens.server.engine.eval.EvaluationCaseService evaluationCaseService;
    private final io.emonlens.server.engine.eval.DatasetImportService datasetImportService;
    private final io.emonlens.server.engine.eval.DatasetSynthesisService datasetSynthesisService;
    private final EmonLensProperties properties;

    @Data
    public static class CreateDatasetRequest {
        private String datasetKey;
        private String description;
    }

    @Data
    public static class AddItemRequest {
        private String traceId;
        private String input;
        private String expectedOutput;
        private String metadataJson;
        private String purposeTags;
        private String riskLevel;
        private String groupKey;
        private String split;
        private String failureMode;
        /** 1=旧样本；2=含检索/工具/多轮/期望。留空时按是否填写 v2 字段自动判断。 */
        private Integer schemaVersion;
        /** JSON 数组：检索上下文 */
        private Object retrievalContext;
        /** JSON 数组：期望工具 */
        private Object expectedTools;
        /** JSON 数组：多轮会话 */
        private Object turns;
        /** JSON 对象：样本级 must/mustNot */
        private Object expectations;
    }

    @Data
    public static class RunExperimentRequest {
        private String evaluatorId;
        /** 多评估器运行；与 evaluatorId 二选一，优先使用 evaluatorIds */
        private List<String> evaluatorIds;
        /** 可选：指定已冻结的数据集修订；留空时自动冻结当前样本 */
        private String revisionId;
        /** 候选标识（prompt v3 / model gpt-4o），用于 A/B 与准入展示 */
        private String candidateLabel;
        /** 可选：调用方提供的候选指纹；留空时由 revision + 评估器版本 + 生成配置计算 */
        private String candidateFingerprint;
        /** 可选：本次 run 的外部调用次数预算；留空不限制 */
        private Integer maxExternalCalls;
        /** 可选：本次 run 的 token 预算（输入+输出）；留空不限制 */
        private Long maxTokens;
        /** 人工复核模式：SINGLE（默认，单人）或 DOUBLE_BLIND（双人独立 + 分歧仲裁） */
        private String reviewMode;
        /** 可选：绑定不可变 Eval Suite；与 evaluatorIds/evaluatorId 互斥 */
        private String suiteId;
        /** TRACE（默认）=评估样本关联的已有 trace；REPLAY=用 LLM 重放样本输入生成新输出后评估 */
        private String mode;
        /** REPLAY：可选系统提示词 */
        private String systemPrompt;
        /** REPLAY：可选模型名（留空时冻结当时的有效默认模型） */
        private String model;
        /** 可选：Gate 成本上限（按 model price 估算）；超出时 Gate FAIL，无法计价时 BLOCKED */
        private java.math.BigDecimal maxCost;
        /** R1-3：绑定 prompt 版本（version 优先；只给 name 时按 label 解析，缺省 production） */
        private String promptName;
        private Integer promptVersion;
        private String promptLabel;
        /** R1-4：CI 基线标签（如 baseline）与触发提交号 */
        private String runLabel;
        private String commitSha;
    }

    @Data
    public static class RunLabelRequest {
        /** 置空即摘除标签 */
        private String runLabel;
    }

    @Data
    public static class FreezeRevisionRequest {
        private String label;
    }

    @Data
    public static class CreateSynthesisJobRequest {
        /** DATASET_ITEMS / TRACE_CLUSTER / DOCUMENTS / SCRATCH */
        private String sourceType;
        private Map<String, Object> source;
        private Map<String, Object> config;
    }

    @Data
    public static class ImportItemsRequest {
        /** csv | jsonl */
        private String format;
        /** 只校验不写库 */
        private Boolean dryRun;
        /** 任一行失败则整批回滚 */
        private Boolean atomic;
        private String content;
    }

    @GetMapping
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<Map<String, Object>>> list() {
        String ws = CurrentContext.require().getWorkspaceId();
        List<DatasetEntity> datasets = datasetMapper.selectList(Wrappers.<DatasetEntity>lambdaQuery()
                .eq(DatasetEntity::getWorkspaceId, ws)
                .orderByDesc(DatasetEntity::getCreatedAt));
        return ApiResult.ok(datasets.stream().map(d -> {
            Map<String, Object> m = toVO(d);
            m.put("itemCount", datasetItemMapper.selectCount(Wrappers.<DatasetItemEntity>lambdaQuery()
                    .eq(DatasetItemEntity::getDatasetId, d.getId())));
            m.put("activeItemCount", datasetItemMapper.selectCount(Wrappers.<DatasetItemEntity>lambdaQuery()
                    .eq(DatasetItemEntity::getDatasetId, d.getId())
                    .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                            .or().isNull(DatasetItemEntity::getStatus))));
            m.put("runCount", datasetRunMapper.selectCount(Wrappers.<DatasetRunEntity>lambdaQuery()
                    .eq(DatasetRunEntity::getDatasetId, d.getId())));
            return m;
        }).collect(Collectors.toList()));
    }

    /** 轻量实验列表：质量总览等聚合页不需要加载全部样本。可按 runLabel 过滤（如 baseline）。 */
    @GetMapping("/{id}/runs")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<DatasetRunEntity>> runs(@PathVariable String id,
                                                  @RequestParam(required = false) String label) {
        requireOwned(id);
        List<DatasetRunEntity> runs = datasetRunMapper.selectList(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getDatasetId, id)
                .eq(label != null && !label.isBlank(), DatasetRunEntity::getRunLabel, label)
                .orderByDesc(DatasetRunEntity::getStartedAt));
        enrichRuns(runs);
        return ApiResult.ok(runs);
    }

    /** 打 / 摘 CI 基线标签（R1-4）：同一数据集的 baseline 只保留最近一次设置的那条。 */
    @PostMapping("/{id}/runs/{runId}/label")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.run.label", resourceType = "dataset")
    public ApiResult<Map<String, Object>> labelRun(@PathVariable String id, @PathVariable String runId,
                                                   @RequestBody RunLabelRequest request) {
        DatasetEntity dataset = requireOwned(id);
        return ApiResult.ok(datasetRunService.labelRun(dataset.getWorkspaceId(), id, runId,
                request == null ? null : request.getRunLabel()));
    }

    @PostMapping
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.create", resourceType = "dataset")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateDatasetRequest request) {
        if (request.getDatasetKey() == null || request.getDatasetKey().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "datasetKey is required");
        }
        DatasetEntity entity = new DatasetEntity();
        entity.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        entity.setDatasetKey(request.getDatasetKey().trim());
        entity.setDescription(request.getDescription());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        datasetMapper.insert(entity);
        return ApiResult.ok(toVO(entity));
    }

    /** 数据集蓝图 + 保鲜/漂移报告；不改变任何 run 证据。 */
    @GetMapping("/{id}/health")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> health(@PathVariable String id) {
        return ApiResult.ok(datasetHealthService.health(
                CurrentContext.require().getWorkspaceId(), id));
    }

    /** 保鲜快照历史；由每日扫描写入，不改变样本。 */
    @GetMapping("/{id}/health/history")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<Map<String, Object>>> healthHistory(
            @PathVariable String id,
            @RequestParam(required = false) Integer limit) {
        return ApiResult.ok(datasetHealthService.history(
                CurrentContext.require().getWorkspaceId(), id, limit));
    }

    /** 绑定/解绑领域包；空 domainPackId 表示解绑。 */
    @PostMapping("/{id}/domain-pack/upgrade")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.domain_pack.upgrade", resourceType = "dataset")
    public ApiResult<Map<String, Object>> upgradeDomainPack(@PathVariable String id,
                                                            @RequestBody Map<String, Object> body) {
        return ApiResult.ok(datasetHealthService.upgradeDomainPack(
                CurrentContext.require().getWorkspaceId(), id,
                body == null || body.get("domainPackId") == null
                        ? null : String.valueOf(body.get("domainPackId"))));
    }

    @PutMapping("/{id}/domain-pack")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.domain_pack.bind", resourceType = "dataset")
    public ApiResult<Map<String, Object>> bindDomainPack(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        Object value = body == null ? null : body.get("domainPackId");
        return ApiResult.ok(datasetHealthService.bindDomainPack(
                CurrentContext.require().getWorkspaceId(), id,
                value == null ? null : String.valueOf(value)));
    }

    /** 配置数据蓝图；空对象表示清除。 */
    @PutMapping("/{id}/blueprint")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.blueprint.update", resourceType = "dataset")
    public ApiResult<Map<String, Object>> updateBlueprint(
            @PathVariable String id, @RequestBody(required = false) Map<String, Object> body) {
        return ApiResult.ok(datasetHealthService.saveBlueprint(
                CurrentContext.require().getWorkspaceId(), id, body));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        DatasetEntity dataset = requireOwned(id);
        Map<String, Object> m = toVO(dataset);
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        m.put("contentRestricted", !contentAllowed);
        m.put("items", datasetItemMapper.selectList(Wrappers.<DatasetItemEntity>lambdaQuery()
                        .eq(DatasetItemEntity::getDatasetId, id)
                        .orderByAsc(DatasetItemEntity::getCreatedAt))
                .stream().map(item -> toItemVO(item, contentAllowed))
                .collect(Collectors.toList()));
        List<DatasetRunEntity> runs = datasetRunMapper.selectList(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getDatasetId, id)
                .orderByDesc(DatasetRunEntity::getStartedAt));
        enrichRuns(runs);
        m.put("runs", runs);
        m.put("revisions", revisionService.list(CurrentContext.require().getWorkspaceId(), id).stream()
                .map(revisionService::toMap).collect(Collectors.toList()));
        return ApiResult.ok(m);
    }

    /** 冻结当前样本为不可变 revision；run 可显式引用它，保证样本集合可追溯。 */
    @PostMapping("/{id}/revisions")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.revision.freeze", resourceType = "dataset")
    public ApiResult<Map<String, Object>> freezeRevision(@PathVariable String id,
                                                         @RequestBody(required = false) FreezeRevisionRequest request) {
        DatasetEntity dataset = requireOwned(id);
        List<DatasetItemEntity> items = datasetItemMapper.selectList(Wrappers.<DatasetItemEntity>lambdaQuery()
                .eq(DatasetItemEntity::getDatasetId, id)
                .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                        .or().isNull(DatasetItemEntity::getStatus)));
        DatasetRevisionEntity revision = revisionService.freeze(dataset, items,
                request == null ? null : request.getLabel(), CurrentContext.require().getUserId());
        Map<String, Object> m = revisionService.toMap(revision);
        m.put("items", revision.getItems());
        return ApiResult.ok(m);
    }

    @GetMapping("/{id}/revisions")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<Map<String, Object>>> revisions(@PathVariable String id) {
        requireOwned(id);
        return ApiResult.ok(revisionService.list(CurrentContext.require().getWorkspaceId(), id).stream()
                .map(revisionService::toMap).collect(Collectors.toList()));
    }

    @PostMapping("/{id}/items")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.item.add", resourceType = "dataset")
    public ApiResult<Map<String, Object>> addItem(@PathVariable String id, @RequestBody AddItemRequest request) {
        requireOwned(id);
        requireContentWritePermission();
        datasetHealthService.validateRiskLevel(CurrentContext.require().getWorkspaceId(), id,
                request.getRiskLevel());
        if ((request.getTraceId() == null || request.getTraceId().isBlank())
                && (request.getInput() == null || request.getInput().isBlank())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "traceId 或 input 至少填一项");
        }
        DatasetItemEntity item = new DatasetItemEntity();
        item.setDatasetId(id);
        item.setSourceType("MANUAL");
        item.setSourceRef("manual");
        applyCaseFields(item, request);
        item.setStatus("ACTIVE");
        item.setCreatedAt(LocalDateTime.now());
        datasetItemMapper.insert(item);
        return ApiResult.ok(toItemVO(item));
    }

    /** 编辑工作集样本；不改动已冻结 revision。 */
    @PutMapping("/{id}/items/{itemId}")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.item.update", resourceType = "dataset")
    public ApiResult<Map<String, Object>> updateItem(@PathVariable String id,
                                                     @PathVariable String itemId,
                                                     @RequestBody AddItemRequest request) {
        requireOwned(id);
        requireContentWritePermission();
        DatasetItemEntity item = requireItem(id, itemId);
        datasetHealthService.validateRiskLevel(CurrentContext.require().getWorkspaceId(), id,
                request.getRiskLevel());
        if ((request.getTraceId() == null || request.getTraceId().isBlank())
                && (request.getInput() == null || request.getInput().isBlank())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "traceId 或 input 至少填一项");
        }
        applyCaseFields(item, request);
        datasetItemMapper.updateById(item);
        return ApiResult.ok(toItemVO(item));
    }

    private void applyCaseFields(DatasetItemEntity item, AddItemRequest request) {
        String retrievalJson = evaluationCaseService.toJson(request.getRetrievalContext());
        String expectedToolsJson = evaluationCaseService.toJson(request.getExpectedTools());
        String turnsJson = evaluationCaseService.toJson(request.getTurns());
        String expectationsJson = evaluationCaseService.toJson(request.getExpectations());
        boolean hasV2 = io.emonlens.server.engine.eval.EvaluationCaseService.hasV2Fields(
                retrievalJson, expectedToolsJson, turnsJson, expectationsJson);
        int schemaVersion = evaluationCaseService.effectiveSchemaVersion(
                request.getSchemaVersion(), hasV2);
        evaluationCaseService.validate(schemaVersion, request.getInput(), request.getExpectedOutput(),
                request.getMetadataJson(), retrievalJson, expectedToolsJson, turnsJson,
                expectationsJson, properties.getDatasetRun().getMaxSnapshotChars());

        item.setTraceId(request.getTraceId());
        item.setInput(request.getInput());
        item.setExpectedOutput(request.getExpectedOutput());
        item.setMetadataJson(request.getMetadataJson());
        item.setSchemaVersion(schemaVersion);
        item.setRetrievalContextJson(retrievalJson);
        item.setExpectedToolsJson(expectedToolsJson);
        item.setTurnsJson(turnsJson);
        item.setExpectationsJson(expectationsJson);
        item.setPurposeTags(request.getPurposeTags());
        item.setRiskLevel(request.getRiskLevel() == null || request.getRiskLevel().isBlank()
                ? "UNASSIGNED" : request.getRiskLevel());
        item.setGroupKey(request.getGroupKey());
        item.setSplit(request.getSplit() == null || request.getSplit().isBlank()
                ? "UNASSIGNED" : request.getSplit());
        item.setFailureMode(request.getFailureMode());
        item.setContentHash(evaluationCaseService.hash(item));
    }

    private Map<String, Object> toItemVO(DatasetItemEntity item) {
        return toItemVO(item, true);
    }

    /** contentAllowed=false 时不回传样本正文；结构/标签/hash 仍可用于管理。 */
    private Map<String, Object> toItemVO(DatasetItemEntity item, boolean contentAllowed) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", item.getId());
        map.put("datasetId", item.getDatasetId());
        map.put("traceId", contentAllowed ? item.getTraceId() : null);
        map.put("schemaVersion", item.getSchemaVersion() == null ? 1 : item.getSchemaVersion());
        map.put("contentHash", item.getContentHash());
        map.put("sourceType", item.getSourceType());
        map.put("sourceRef", item.getSourceRef());
        map.put("purposeTags", item.getPurposeTags());
        map.put("riskLevel", item.getRiskLevel());
        map.put("groupKey", item.getGroupKey());
        map.put("split", item.getSplit());
        map.put("failureMode", item.getFailureMode());
        map.put("status", item.getStatus());
        map.put("invalidatedAt", item.getInvalidatedAt());
        map.put("createdAt", item.getCreatedAt());
        map.put("contentRestricted", !contentAllowed);
        if (contentAllowed) {
            map.put("input", item.getInput());
            map.put("expectedOutput", item.getExpectedOutput());
            map.put("metadataJson", item.getMetadataJson());
            map.put("retrievalContext", evaluationCaseService.readForResponse(item.getRetrievalContextJson()));
            map.put("expectedTools", evaluationCaseService.readForResponse(item.getExpectedToolsJson()));
            map.put("turns", evaluationCaseService.readForResponse(item.getTurnsJson()));
            map.put("expectations", evaluationCaseService.readForResponse(item.getExpectationsJson()));
            map.put("invalidReason", item.getInvalidReason());
        }
        return map;
    }

    /** CSV/JSONL 批量导入：dry-run / atomic / contentHash 幂等。 */
    @PostMapping("/{id}/items/import")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.item.import", resourceType = "dataset")
    public ApiResult<io.emonlens.server.engine.eval.DatasetImportService.ImportResult> importItems(
            @PathVariable String id, @RequestBody ImportItemsRequest request) {
        requireOwned(id);
        requireContentWritePermission();
        return ApiResult.ok(datasetImportService.importItems(
                CurrentContext.require().getWorkspaceId(), id,
                request.getFormat(), request.getContent(),
                Boolean.TRUE.equals(request.getDryRun()),
                Boolean.TRUE.equals(request.getAtomic())));
    }

    /** CSV/JSONL 导出；无 TRACE_CONTENT 时只导出标签与 hash，不导出正文。流式写出，避免大样本集占满内存。 */
    @GetMapping("/{id}/items/export")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public void exportItems(@PathVariable String id,
                            @RequestParam(defaultValue = "jsonl") String format,
                            javax.servlet.http.HttpServletResponse response) throws java.io.IOException {
        requireOwned(id);
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        boolean csv = "csv".equalsIgnoreCase(format);
        String filename = "dataset-" + id + "." + (csv ? "csv" : "jsonl");
        response.setContentType(csv ? "text/csv;charset=UTF-8" : "application/x-ndjson;charset=UTF-8");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"");
        datasetImportService.writeItems(response.getWriter(), id, format, contentAllowed);
    }

    /** 创建合成 job；候选必须人工批准后才进入工作集。 */
    @PostMapping("/{id}/synthesis-jobs")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.synthesis.create", resourceType = "dataset")
    public ApiResult<Map<String, Object>> createSynthesisJob(
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateSynthesisJobRequest request) {
        DatasetEntity dataset = requireOwned(id);
        requireContentWritePermission();
        io.emonlens.server.engine.eval.DatasetSynthesisService.CreateJobResult result =
                datasetSynthesisService.create(dataset.getWorkspaceId(), id, idempotencyKey,
                        request.getSourceType(), request.getSource(), request.getConfig(),
                        CurrentContext.require().getUserId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("created", result.isCreated());
        payload.put("job", datasetSynthesisService.toMap(result.getJob(),
                CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT)));
        return ApiResult.ok(payload);
    }

    @GetMapping("/{id}/synthesis-jobs")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<Map<String, Object>>> synthesisJobs(@PathVariable String id) {
        DatasetEntity dataset = requireOwned(id);
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (io.emonlens.server.storage.entity.DatasetSynthesisJobEntity job
                : datasetSynthesisService.listJobs(dataset.getWorkspaceId(), id)) {
            rows.add(datasetSynthesisService.toMap(job, contentAllowed));
        }
        return ApiResult.ok(rows);
    }

    @GetMapping("/{id}/synthesis-jobs/{jobId}")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> synthesisJob(@PathVariable String id,
                                                       @PathVariable String jobId) {
        DatasetEntity dataset = requireOwned(id);
        return ApiResult.ok(datasetSynthesisService.toMap(
                datasetSynthesisService.getJob(dataset.getWorkspaceId(), id, jobId),
                CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT)));
    }

    @GetMapping("/{id}/synthesis-jobs/{jobId}/candidates")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<List<Map<String, Object>>> synthesisCandidates(
            @PathVariable String id, @PathVariable String jobId,
            @RequestParam(required = false) String status) {
        DatasetEntity dataset = requireOwned(id);
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (io.emonlens.server.storage.entity.DatasetSynthesisCandidateEntity candidate
                : datasetSynthesisService.listCandidates(dataset.getWorkspaceId(), id, jobId, status)) {
            rows.add(datasetSynthesisService.toMap(candidate, contentAllowed));
        }
        return ApiResult.ok(rows);
    }

    @PostMapping("/{id}/synthesis-jobs/{jobId}/candidates/{candidateId}/approve")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.synthesis.approve", resourceType = "dataset")
    public ApiResult<Map<String, Object>> approveSynthesisCandidate(
            @PathVariable String id, @PathVariable String jobId,
            @PathVariable String candidateId) {
        DatasetEntity dataset = requireOwned(id);
        requireContentWritePermission();
        DatasetItemEntity item = datasetSynthesisService.approve(dataset.getWorkspaceId(), id,
                jobId, candidateId, CurrentContext.require().getUserId());
        if (CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT)) {
            return ApiResult.ok(toItemVO(item));
        }
        // 无内容权限：只确认已入库与溯源，不回传 input/expected
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("id", item.getId());
        summary.put("datasetId", item.getDatasetId());
        summary.put("schemaVersion", item.getSchemaVersion());
        summary.put("sourceType", item.getSourceType());
        summary.put("sourceRef", item.getSourceRef());
        summary.put("status", item.getStatus());
        summary.put("contentHash", item.getContentHash());
        return ApiResult.ok(summary);
    }

    @PostMapping("/{id}/synthesis-jobs/{jobId}/candidates/{candidateId}/reject")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.synthesis.reject", resourceType = "dataset")
    public ApiResult<Map<String, Object>> rejectSynthesisCandidate(
            @PathVariable String id, @PathVariable String jobId,
            @PathVariable String candidateId,
            @RequestBody(required = false) Map<String, Object> body) {
        DatasetEntity dataset = requireOwned(id);
        String note = body == null || body.get("note") == null
                ? null : String.valueOf(body.get("note"));
        io.emonlens.server.storage.entity.DatasetSynthesisCandidateEntity candidate =
                datasetSynthesisService.reject(dataset.getWorkspaceId(), id, jobId,
                        candidateId, CurrentContext.require().getUserId(), note);
        return ApiResult.ok(datasetSynthesisService.toMap(candidate,
                CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT)));
    }

    @PostMapping("/{id}/items/quarantine-stale")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.item.quarantine_stale", resourceType = "dataset")
    public ApiResult<Map<String, Object>> quarantineStale(@PathVariable String id,
                                                          @RequestBody(required = false)
                                                          Map<String, Object> body) {
        requireOwned(id);
        boolean apply = body != null && Boolean.TRUE.equals(body.get("apply"));
        return ApiResult.ok(datasetHealthService.quarantineStaleItems(
                CurrentContext.require().getWorkspaceId(), id, apply));
    }

    @PostMapping("/{id}/items/{itemId}/invalidate")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.item.invalidate", resourceType = "dataset")
    public ApiResult<DatasetItemEntity> invalidateItem(@PathVariable String id,
                                                       @PathVariable String itemId,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        requireOwned(id);
        DatasetItemEntity item = requireItem(id, itemId);
        item.setStatus("QUARANTINED");
        item.setInvalidReason(body == null || body.get("reason") == null
                ? null : String.valueOf(body.get("reason")));
        item.setInvalidatedAt(LocalDateTime.now());
        datasetItemMapper.updateById(item);
        return ApiResult.ok(item);
    }

    @PostMapping("/{id}/items/{itemId}/restore")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.item.restore", resourceType = "dataset")
    public ApiResult<DatasetItemEntity> restoreItem(@PathVariable String id,
                                                    @PathVariable String itemId) {
        requireOwned(id);
        DatasetItemEntity item = requireItem(id, itemId);
        item.setStatus("ACTIVE");
        item.setInvalidReason(null);
        item.setInvalidatedAt(null);
        datasetItemMapper.update(null, Wrappers.<DatasetItemEntity>lambdaUpdate()
                .eq(DatasetItemEntity::getId, item.getId())
                .set(DatasetItemEntity::getStatus, "ACTIVE")
                .set(DatasetItemEntity::getInvalidReason, null)
                .set(DatasetItemEntity::getInvalidatedAt, null));
        return ApiResult.ok(item);
    }

    private DatasetItemEntity requireItem(String datasetId, String itemId) {
        DatasetItemEntity item = datasetItemMapper.selectById(itemId);
        if (item == null || !Objects.equals(item.getDatasetId(), datasetId)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset item not found");
        }
        return item;
    }

    @DeleteMapping("/{id}/items/{itemId}")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.item.delete", resourceType = "dataset")
    public ApiResult<Void> deleteItem(@PathVariable String id, @PathVariable String itemId) {
        requireOwned(id);
        datasetItemMapper.delete(Wrappers.<DatasetItemEntity>lambdaQuery()
                .eq(DatasetItemEntity::getDatasetId, id)
                .eq(DatasetItemEntity::getId, itemId));
        return ApiResult.ok(null);
    }

    /**
     * 异步受理（试点唯一入口）：要求 Idempotency-Key，受理即返回 runId。
     * 同键同请求返回原 run（200）；同键不同请求 409；已有活动运行 429。
     */
    @PostMapping("/{id}/runs/async")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.run.async", resourceType = "dataset")
    public ResponseEntity<ApiResult<Map<String, Object>>> runAsync(
            @PathVariable String id,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody RunExperimentRequest request) {
        DatasetEntity dataset = requireOwned(id);
        requireContentPermission();
        List<String> evaluatorIds = request.getEvaluatorIds();
        String suiteId = request.getSuiteId();
        if (suiteId != null && !suiteId.isBlank()) {
            if ((evaluatorIds != null && !evaluatorIds.isEmpty())
                    || (request.getEvaluatorId() != null && !request.getEvaluatorId().isBlank())) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "suiteId 与 evaluatorId/evaluatorIds 不能同时指定");
            }
            evaluatorIds = List.of();
        } else if (evaluatorIds == null || evaluatorIds.isEmpty()) {
            if (request.getEvaluatorId() == null || request.getEvaluatorId().isBlank()) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluatorId or evaluatorIds is required");
            }
            evaluatorIds = List.of(request.getEvaluatorId());
        }
        DatasetRunService.AcceptResult result = datasetRunService.accept(
                dataset.getWorkspaceId(), id, evaluatorIds, request.getRevisionId(), request.getMode(),
                request.getSystemPrompt(), request.getModel(), request.getCandidateLabel(),
                request.getCandidateFingerprint(), request.getMaxExternalCalls(),
                request.getMaxTokens(), request.getReviewMode(), request.getSuiteId(),
                idempotencyKey, request.getMaxCost(), promptRefOf(request),
                request.getRunLabel(), request.getCommitSha());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", result.getRun().getId());
        payload.put("status", result.getRun().getStatus());
        payload.put("created", result.isCreated());
        payload.put("run", result.getRun());
        return ResponseEntity.status(result.isCreated() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResult.ok(payload));
    }

    /** 同步 / 异步入口共用的 prompt 引用解析：只给 name 时按 label（缺省 production）解析。 */
    private io.emonlens.server.engine.prompt.PromptService.PromptRef promptRefOf(RunExperimentRequest request) {
        if (request.getPromptName() == null || request.getPromptName().isBlank()) {
            return null;
        }
        return new io.emonlens.server.engine.prompt.PromptService.PromptRef(
                request.getPromptName().trim(), request.getPromptVersion(),
                request.getPromptLabel(), null);
    }

    /** 旧同步入口：保留原响应形状；试点模式拒绝新评测并指向异步入口。 */
    @PostMapping("/{id}/runs")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.run", resourceType = "dataset")
    public ApiResult<DatasetRunEntity> run(@PathVariable String id, @RequestBody RunExperimentRequest request) {
        if (properties.getDatasetRun().isPilotMode()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "试点环境已停用同步运行入口，请使用 POST /api/v1/datasets/" + id
                            + "/runs/async 并携带 Idempotency-Key");
        }
        if (request.getMaxExternalCalls() != null || request.getMaxTokens() != null
                || request.getReviewMode() != null || request.getSuiteId() != null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "同步入口不支持 maxExternalCalls/maxTokens/reviewMode/suiteId，请使用异步入口");
        }
        DatasetEntity dataset = requireOwned(id);
        EvaluatorEntity evaluator = evaluatorMapper.selectOne(Wrappers.<EvaluatorEntity>lambdaQuery()
                .eq(EvaluatorEntity::getId, request.getEvaluatorId())
                .eq(EvaluatorEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId()));
        if (evaluator == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "evaluator not found");
        }
        boolean replay = "REPLAY".equals(request.getMode());
        List<DatasetItemEntity> items = datasetItemMapper.selectList(Wrappers.<DatasetItemEntity>lambdaQuery()
                .eq(DatasetItemEntity::getDatasetId, id)
                .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                        .or().isNull(DatasetItemEntity::getStatus)));

        DatasetRunEntity run = new DatasetRunEntity();
        run.setWorkspaceId(dataset.getWorkspaceId());
        run.setDatasetId(id);
        run.setEvaluatorId(evaluator.getId());
        run.setEvaluatorKey(evaluator.getEvaluatorKey());
        run.setRunMode(replay ? "REPLAY" : "TRACE");
        run.setModel(replay ? request.getModel() : null);
        run.setStatus(DatasetRunEntity.STATUS_RUNNING);
        run.setItemCount(items.size());
        run.setStartedAt(LocalDateTime.now());
        datasetRunMapper.insert(run);

        int pass = 0;
        int fail = 0;
        for (DatasetItemEntity item : items) {
            if (!datasetRunService.isActive(run.getId())) {
                break;
            }
            DatasetRunItemEntity ri = new DatasetRunItemEntity();
            ri.setRunId(run.getId());
            ri.setItemId(item.getId());
            String targetTraceId = item.getTraceId();
            try {
                if (replay) {
                    if (item.getInput() == null || item.getInput().isBlank()) {
                        ri.setLabel(EvaluatorEntity.LABEL_ERROR);
                        ri.setReason("样本缺少 input，无法重放");
                        datasetRunItemMapper.insert(ri);
                        continue;
                    }
                    var rr = datasetReplayService.replay(CurrentContext.require().getWorkspaceId(),
                            "dataset-replay", item.getInput(), request.getSystemPrompt(), request.getModel());
                    targetTraceId = rr.getTraceId();
                    ri.setTraceId(rr.getTraceId());
                    ri.setSourceTraceId(item.getTraceId());
                    ri.setReplayOutput(rr.getOutput() != null && rr.getOutput().length() > 4000
                            ? rr.getOutput().substring(0, 4000) : rr.getOutput());
                } else {
                    ri.setTraceId(item.getTraceId());
                }
                var result = evaluationRunService.evaluate(evaluator, targetTraceId, 1, true);
                ri.setScore(result.getScore());
                ri.setLabel(result.getLabel());
                ri.setReason(result.getReason());
                if (EvaluatorEntity.LABEL_PASS.equals(result.getLabel())) {
                    pass++;
                } else if (EvaluatorEntity.LABEL_FAIL.equals(result.getLabel())) {
                    fail++;
                }
            } catch (Exception e) {
                ri.setTraceId(targetTraceId);
                ri.setLabel(EvaluatorEntity.LABEL_ERROR);
                String message = String.valueOf(e.getMessage());
                ri.setReason(message.substring(0, Math.min(500, message.length())));
            }
            datasetRunItemMapper.insert(ri);
        }
        run.setStatus(DatasetRunEntity.STATUS_COMPLETED);
        run.setPassCount(pass);
        run.setFailCount(fail);
        run.setFinishedAt(LocalDateTime.now());
        int updated = datasetRunMapper.update(null, Wrappers.<DatasetRunEntity>lambdaUpdate()
                .eq(DatasetRunEntity::getId, run.getId())
                .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_RUNNING)
                .set(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_COMPLETED)
                .set(DatasetRunEntity::getPassCount, pass)
                .set(DatasetRunEntity::getFailCount, fail)
                .set(DatasetRunEntity::getFinishedAt, LocalDateTime.now()));
        // 取消与旧同步执行的竞态：不能把 CANCELLED 覆盖回 COMPLETED。
        return ApiResult.ok(updated == 0 ? datasetRunMapper.selectById(run.getId()) : run);
    }

    /** 进度与结果复用既有 run 详情；正文按内容权限裁剪。 */
    @GetMapping("/{id}/runs/{runId}/cost")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> runCost(@PathVariable String id,
                                                  @PathVariable String runId) {
        requireOwned(id);
        return ApiResult.ok(datasetRunService.costReport(
                CurrentContext.require().getWorkspaceId(), id, runId));
    }

    @GetMapping("/{id}/runs/{runId}/quality-model")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> qualityModel(@PathVariable String id,
                                                       @PathVariable String runId) {
        requireOwned(id);
        return ApiResult.ok(datasetRunService.qualityModelReport(
                CurrentContext.require().getWorkspaceId(), id, runId));
    }

    @GetMapping("/{id}/runs/{runId}")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> runDetail(@PathVariable String id, @PathVariable String runId) {
        DatasetEntity dataset = requireOwned(id);
        DatasetRunEntity run = datasetRunService.requireRun(dataset.getWorkspaceId(), id, runId);
        List<DatasetRunItemEntity> items = datasetRunService.itemsOf(runId);
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        datasetRunService.enrich(run, items, contentAllowed, true);
        Map<String, Object> m = new HashMap<>();
        m.put("run", run);
        m.put("items", items);
        m.put("contentRestricted", !contentAllowed);
        return ApiResult.ok(m);
    }

    /** 任务账本：一个样本 × 一个评估器一条记录；历史 run 没有任务行时 legacy=true。 */
    @GetMapping("/{id}/runs/{runId}/tasks")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> runTasks(@PathVariable String id,
                                                   @PathVariable String runId,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(required = false) String evaluatorKey,
                                                   @RequestParam(defaultValue = "1") int pageNumber,
                                                   @RequestParam(defaultValue = "50") int pageSize) {
        DatasetEntity dataset = requireOwned(id);
        datasetRunService.requireRun(dataset.getWorkspaceId(), id, runId);
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        return ApiResult.ok(evalTaskService.list(dataset.getWorkspaceId(), id, runId,
                status, evaluatorKey, pageNumber, pageSize, contentAllowed));
    }

    /** 基线/候选 run 的可比性与逐样本 diff；不把不同样本集/评估器的差异当成回归。 */
    @GetMapping("/{id}/runs/comparison")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> compareRuns(@PathVariable String id,
                                                      @RequestParam String baselineRunId,
                                                      @RequestParam String candidateRunId) {
        if (baselineRunId == null || baselineRunId.isBlank()
                || candidateRunId == null || candidateRunId.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "baselineRunId and candidateRunId are required");
        }
        DatasetEntity dataset = requireOwned(id);
        DatasetRunEntity baseline = datasetRunService.requireRun(dataset.getWorkspaceId(), id, baselineRunId);
        DatasetRunEntity candidate = datasetRunService.requireRun(dataset.getWorkspaceId(), id, candidateRunId);
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        List<DatasetRunItemEntity> baselineItems = datasetRunService.itemsOf(baselineRunId);
        List<DatasetRunItemEntity> candidateItems = datasetRunService.itemsOf(candidateRunId);
        datasetRunService.enrich(baseline, baselineItems, contentAllowed, true);
        datasetRunService.enrich(candidate, candidateItems, contentAllowed, true);
        return ApiResult.ok(datasetRunComparisonService.compare(baseline, baselineItems, candidate,
                candidateItems, contentAllowed));
    }

    /** attempt 链汇总；只返回各次尝试的计数与预算，不返回样本正文。 */
    @GetMapping("/{id}/runs/{runId}/attempts")
    @RequiresPermission(Permissions.DATASET_QUERY)
    public ApiResult<Map<String, Object>> runAttempts(@PathVariable String id, @PathVariable String runId) {
        DatasetEntity dataset = requireOwned(id);
        return ApiResult.ok(datasetRunService.attemptChain(dataset.getWorkspaceId(), id, runId));
    }

    /** 从 INTERRUPTED run 续跑：新 attempt 保留已执行证据，只补未执行样本。 */
    @PostMapping("/{id}/runs/{runId}/resume")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.run.resume", resourceType = "dataset")
    public ResponseEntity<ApiResult<DatasetRunEntity>> resumeRun(
            @PathVariable String id,
            @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        DatasetEntity dataset = requireOwned(id);
        requireContentPermission();
        DatasetRunEntity run = datasetRunService.resume(dataset.getWorkspaceId(), id, runId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResult.ok(run));
    }

    /** PARTIAL_FAILED run 重试 ERROR 样本：新 attempt，源 run 与已成功样本不变。 */
    @PostMapping("/{id}/runs/{runId}/retry")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.run.retry", resourceType = "dataset")
    public ResponseEntity<ApiResult<DatasetRunEntity>> retryRun(
            @PathVariable String id,
            @PathVariable String runId,
            @RequestHeader("Idempotency-Key") String idempotencyKey) {
        DatasetEntity dataset = requireOwned(id);
        requireContentPermission();
        DatasetRunEntity run = datasetRunService.retryFailed(dataset.getWorkspaceId(), id, runId, idempotencyKey);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResult.ok(run));
    }

    /** 取消活动 run；重复取消幂等，终态不会回退。 */
    @PostMapping("/{id}/runs/{runId}/cancel")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "dataset.run.cancel", resourceType = "dataset")
    public ApiResult<Map<String, Object>> cancelRun(@PathVariable String id, @PathVariable String runId) {
        DatasetEntity dataset = requireOwned(id);
        DatasetRunEntity run = datasetRunService.cancel(dataset.getWorkspaceId(), id, runId);
        boolean contentAllowed = CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT);
        datasetRunService.enrich(run, datasetRunService.itemsOf(runId), contentAllowed, false);
        Map<String, Object> m = new HashMap<>();
        m.put("run", run);
        m.put("cancelled", DatasetRunEntity.STATUS_CANCELLED.equals(run.getStatus()));
        return ApiResult.ok(m);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "dataset.delete", resourceType = "dataset")
    @Transactional
    public ApiResult<Void> delete(@PathVariable String id) {
        requireOwned(id);
        // 级联清理：条目 + 历史实验的明细行 + 实验头（无 DB 外键，逐表删除）
        datasetItemMapper.delete(Wrappers.<DatasetItemEntity>lambdaQuery()
                .eq(DatasetItemEntity::getDatasetId, id));
        for (DatasetRevisionEntity revision : revisionService.list(CurrentContext.require().getWorkspaceId(), id)) {
            revisionItemMapper.delete(Wrappers.<DatasetRevisionItemEntity>lambdaQuery()
                    .eq(DatasetRevisionItemEntity::getRevisionId, revision.getId()));
            revisionMapper.deleteById(revision.getId());
        }
        List<DatasetRunEntity> runs = datasetRunMapper.selectList(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getDatasetId, id));
        for (DatasetRunEntity run : runs) {
            datasetRunItemMapper.delete(Wrappers.<DatasetRunItemEntity>lambdaQuery()
                    .eq(DatasetRunItemEntity::getRunId, run.getId()));
            judgeReviewMapper.delete(Wrappers.<io.emonlens.server.storage.entity.JudgeReviewEntity>lambdaQuery()
                    .eq(io.emonlens.server.storage.entity.JudgeReviewEntity::getRunId, run.getId()));
        }
        List<String> runIds = runs.stream().map(DatasetRunEntity::getId).collect(Collectors.toList());
        evalTaskService.deleteByRunIds(runIds);
        if (!runIds.isEmpty()) {
            // 准入槽 / Gate 以 run 为归属；无 DB 外键，必须显式清理
            runSlotMapper.delete(Wrappers.<DatasetRunSlotEntity>lambdaQuery()
                    .in(DatasetRunSlotEntity::getRunId, runIds));
        }
        qualityGateMapper.delete(Wrappers.<QualityGateEntity>lambdaQuery()
                .eq(QualityGateEntity::getDatasetId, id));
        // 成对比较：先清明细/人工复核，再删评估头
        for (PairwiseEvaluationEntity pair : pairwiseEvaluationMapper.selectList(
                Wrappers.<PairwiseEvaluationEntity>lambdaQuery()
                        .eq(PairwiseEvaluationEntity::getDatasetId, id))) {
            pairwiseItemMapper.delete(Wrappers.<PairwiseItemEntity>lambdaQuery()
                    .eq(PairwiseItemEntity::getEvaluationId, pair.getId()));
            pairwiseHumanReviewMapper.delete(Wrappers.<PairwiseHumanReviewEntity>lambdaQuery()
                    .eq(PairwiseHumanReviewEntity::getEvaluationId, pair.getId()));
        }
        pairwiseEvaluationMapper.delete(Wrappers.<PairwiseEvaluationEntity>lambdaQuery()
                .eq(PairwiseEvaluationEntity::getDatasetId, id));
        // 自动规则候选 → 运行头
        for (AutoRubricRunEntity rubricRun : autoRubricRunMapper.selectList(
                Wrappers.<AutoRubricRunEntity>lambdaQuery()
                        .eq(AutoRubricRunEntity::getDatasetId, id))) {
            autoRubricCandidateMapper.delete(Wrappers.<AutoRubricCandidateEntity>lambdaQuery()
                    .eq(AutoRubricCandidateEntity::getRunId, rubricRun.getId()));
        }
        autoRubricRunMapper.delete(Wrappers.<AutoRubricRunEntity>lambdaQuery()
                .eq(AutoRubricRunEntity::getDatasetId, id));
        healthSnapshotMapper.delete(Wrappers.<DatasetHealthSnapshotEntity>lambdaQuery()
                .eq(DatasetHealthSnapshotEntity::getDatasetId, id));
        qualityCaseMapper.delete(Wrappers.<QualityCaseEntity>lambdaQuery()
                .eq(QualityCaseEntity::getDatasetId, id));
        datasetRunMapper.delete(Wrappers.<DatasetRunEntity>lambdaQuery()
                .eq(DatasetRunEntity::getDatasetId, id));
        datasetMapper.deleteById(id);
        return ApiResult.ok(null);
    }

    private void enrichRuns(List<DatasetRunEntity> runs) {
        if (runs.isEmpty()) {
            return;
        }
        List<String> runIds = runs.stream().map(DatasetRunEntity::getId).collect(Collectors.toList());
        List<DatasetRunItemEntity> allItems = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery().in(DatasetRunItemEntity::getRunId, runIds));
        Map<String, List<DatasetRunItemEntity>> byRun = new HashMap<>();
        for (DatasetRunItemEntity item : allItems) {
            byRun.computeIfAbsent(item.getRunId(), k -> new ArrayList<>()).add(item);
        }
        for (DatasetRunEntity run : runs) {
            datasetRunService.enrich(run, byRun.getOrDefault(run.getId(), new ArrayList<>()), false, true);
        }
    }

    private void requireContentPermission() {
        if (!CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT)) {
            throw new EmonLensException(ErrorCode.FORBIDDEN,
                    "无内容查看权限，不能创建包含正文快照的数据集运行");
        }
    }

    /** 写入样本正文/合成结果的入口必须有内容权限，避免盲编辑把正文覆盖为空。 */
    private void requireContentWritePermission() {
        if (!CurrentContext.require().hasPermission(Permissions.TRACE_CONTENT)) {
            throw new EmonLensException(ErrorCode.FORBIDDEN,
                    "无内容查看权限，不能写入样本正文或批准合成候选");
        }
    }

    private DatasetEntity requireOwned(String id) {
        DatasetEntity dataset = datasetMapper.selectById(id);
        if (dataset == null || !dataset.getWorkspaceId().equals(CurrentContext.require().getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found");
        }
        return dataset;
    }

    private Map<String, Object> toVO(DatasetEntity entity) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", entity.getId());
        m.put("datasetKey", entity.getDatasetKey());
        m.put("description", entity.getDescription());
        m.put("blueprintJson", entity.getBlueprintJson());
        m.put("domainPackId", entity.getDomainPackId());
        m.put("createdAt", entity.getCreatedAt());
        return m;
    }
}
