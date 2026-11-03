package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.config.SysSettingService;
import io.emonlens.server.engine.detect.TrajectorySteps;
import io.emonlens.server.ingest.ContentCaptureService;
import io.emonlens.server.storage.entity.ContentEntity;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetRevisionEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.ContentMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.trace.TraceAssemblyService;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * M2：受理时冻结一次运行所需的全部可变输入。
 * 冻结后，编辑样本/评估器/模型默认值不会改变已受理 run；快照中不保存任何凭据。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetRunSnapshotService {

    public static final String PROTOCOL = "emonlens.dataset-run/2";
    /** REPLAY run 的合成 agent 归属；必须写入快照，否则评分侧无法归因。 */
    public static final String REPLAY_AGENT_KEY = "dataset-replay";
    public static final String REPLAY_AGENT_VERSION = "replay";
    public static final String CONTENT_PROCESSING_VERSION = "v1";
    public static final String MODE_TRACE = "TRACE";
    public static final String MODE_REPLAY = "REPLAY";
    /** R3-1：多轮会话仿真——按 persona 驱动一段对话后再评估 */
    public static final String MODE_REPLAY_SIM = "REPLAY_SIM";

    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;
    private final AttachmentPolicy attachmentPolicy;
    private final SysSettingService sysSettingService;
    private final TraceSummaryMapper traceSummaryMapper;
    private final ContentMapper contentMapper;
    private final TraceAssemblyService traceAssemblyService;
    private final EvaluationRunService evaluationRunService;
    private final EvaluationCaseService evaluationCaseService;
    private final JudgeTemplateService judgeTemplateService;

    @Data
    public static class FrozenRun {
        private Map<String, Object> configSnapshot;
        private List<FrozenItem> items = new ArrayList<>();
    }

    @Data
    public static class FrozenItem {
        private String itemId;
        private String traceId;
        private String sourceTraceId;
        private Map<String, Object> sampleSnapshot;
    }

    public FrozenRun freeze(DatasetEntity dataset, List<DatasetItemEntity> items,
                            List<EvaluatorEntity> evaluators, DatasetRevisionEntity revision,
                            String requestedMode, String systemPrompt, String requestedModel,
                            Integer maxExternalCalls, Map<String, Object> suiteSnapshot) {
        return freeze(dataset, items, evaluators, revision, requestedMode, systemPrompt, requestedModel,
                maxExternalCalls, null, suiteSnapshot);
    }

    public FrozenRun freeze(DatasetEntity dataset, List<DatasetItemEntity> items,
                            List<EvaluatorEntity> evaluators, DatasetRevisionEntity revision,
                            String requestedMode, String systemPrompt, String requestedModel,
                            Integer maxExternalCalls, Long maxTokens,
                            Map<String, Object> suiteSnapshot) {
        return freeze(dataset, items, evaluators, revision, requestedMode, systemPrompt, requestedModel,
                maxExternalCalls, maxTokens, suiteSnapshot, null, null);
    }

    /**
     * @param promptRef 受理时解析好的 prompt 版本引用；进候选指纹，prompt 换版即指纹变化
     * @param commitSha 触发本次 run 的提交号；只为可追溯，不改变判定口径
     */
    public FrozenRun freeze(DatasetEntity dataset, List<DatasetItemEntity> items,
                            List<EvaluatorEntity> evaluators, DatasetRevisionEntity revision,
                            String requestedMode, String systemPrompt, String requestedModel,
                            Integer maxExternalCalls, Long maxTokens,
                            Map<String, Object> suiteSnapshot,
                            io.emonlens.server.engine.prompt.PromptService.PromptRef promptRef,
                            String commitSha) {
        return freeze(dataset, items, evaluators, revision, requestedMode, systemPrompt, requestedModel,
                maxExternalCalls, maxTokens, suiteSnapshot, promptRef, commitSha, null);
    }

    /**
     * R3-1：REPLAY_SIM 额外冻结仿真配置（persona / goal / maxTurns / endpointUrl），并让它进候选指纹。
     *
     * @param simulation 为空表示非仿真 run
     */
    public FrozenRun freeze(DatasetEntity dataset, List<DatasetItemEntity> items,
                            List<EvaluatorEntity> evaluators, DatasetRevisionEntity revision,
                            String requestedMode, String systemPrompt, String requestedModel,
                            Integer maxExternalCalls, Long maxTokens,
                            Map<String, Object> suiteSnapshot,
                            io.emonlens.server.engine.prompt.PromptService.PromptRef promptRef,
                            String commitSha, Map<String, Object> simulation) {
        String mode = MODE_REPLAY_SIM.equalsIgnoreCase(requestedMode) ? MODE_REPLAY_SIM
                : MODE_REPLAY.equalsIgnoreCase(requestedMode) ? MODE_REPLAY : MODE_TRACE;
        int maxChars = properties.getDatasetRun().getMaxSnapshotChars();
        if (evaluators == null || evaluators.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "至少需要一个评估器");
        }

        List<DatasetItemEntity> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparing(DatasetItemEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DatasetItemEntity::getId));

        String effectiveModel = effectiveModel(requestedModel);
        List<Map<String, Object>> evaluatorSnapshots = new ArrayList<>();
        for (EvaluatorEntity evaluator : evaluators) {
            evaluatorSnapshots.add(evaluatorSnapshot(evaluator, parseSpec(evaluator), effectiveModel));
        }

        FrozenRun frozen = new FrozenRun();
        List<String> sampleHashParts = new ArrayList<>();
        int order = 0;
        for (DatasetItemEntity item : ordered) {
            checkItemTextLength(item, maxChars);
            Map<String, Object> sample = new LinkedHashMap<>();
            sample.put("itemId", item.getId());
            sample.put("itemOrder", order++);
            sample.put("itemTraceId", item.getTraceId());
            sample.put("input", item.getInput());
            sample.put("expectedOutput", item.getExpectedOutput());
            sample.put("inputHash", Sha256Utils.hash(item.getInput() == null ? "" : item.getInput()));
            sample.put("expectedHash", Sha256Utils.hash(item.getExpectedOutput() == null ? "" : item.getExpectedOutput()));
            String caseHash = evaluationCaseService.hash(item);
            sample.put("schemaVersion", evaluationCaseService.schemaVersion(item.getSchemaVersion()));
            sample.put("caseHash", caseHash);
            sample.put("retrievalContext", evaluationCaseService.list(
                    item.getRetrievalContextJson(), "retrievalContext"));
            sample.put("expectedTools", evaluationCaseService.list(
                    item.getExpectedToolsJson(), "expectedTools"));
            sample.put("turns", evaluationCaseService.list(item.getTurnsJson(), "turns"));
            sample.put("expectations", evaluationCaseService.object(
                    item.getExpectationsJson(), "expectations"));
            sample.put("purposeTags", item.getPurposeTags());
            sample.put("riskLevel", item.getRiskLevel());
            sample.put("groupKey", item.getGroupKey());
            sample.put("split", item.getSplit());
            sample.put("failureMode", item.getFailureMode());
            List<Map<String, Object>> frozenAttachments = attachmentPolicy.parse(item.getAttachmentsJson());
            sample.put("attachments", frozenAttachments);
            if (!frozenAttachments.isEmpty()) {
                log.info("frozen sample carries {} attachment(s), item={}", frozenAttachments.size(), item.getId());
            }

            if (MODE_TRACE.equals(mode)) {
                freezeTraceItem(sample, item, dataset.getWorkspaceId());
            } else {
                // REPLAY 与 REPLAY_SIM 都不绑定 trace：候选内容由执行期产生
                freezeReplayItem(sample, item);
            }

            Map<String, Object> sampleSnapshot = shrinkIfNeeded(sample, maxChars, item.getId());
            FrozenItem fi = new FrozenItem();
            fi.setItemId(item.getId());
            fi.setTraceId(MODE_TRACE.equals(mode) ? item.getTraceId() : null);
            fi.setSourceTraceId(item.getTraceId());
            fi.setSampleSnapshot(sampleSnapshot);
            frozen.getItems().add(fi);

            sampleHashParts.add(item.getId() + "|" + caseHash);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("protocol", PROTOCOL);
        config.put("runMode", mode);
        config.put("datasetId", dataset.getId());
        config.put("workspaceId", dataset.getWorkspaceId());
        config.put("domainPackId", dataset.getDomainPackId());
        config.put("datasetRevision", revisionSnapshot(revision));
        config.put("evaluator", evaluatorSnapshots.get(0));
        config.put("evaluators", evaluatorSnapshots);
        config.put("generation", generationSnapshot(mode, systemPrompt, effectiveModel));
        if (MODE_REPLAY_SIM.equals(mode)) {
            config.put("simulation", simulationSnapshot(simulation, effectiveModel));
        }
        config.put("sampleSetHash", Sha256Utils.hash(String.join("\n", sampleHashParts)));
        config.put("sampleCount", ordered.size());
        config.put("contentProcessing", contentPolicySnapshot());
        Map<String, Object> budget = new LinkedHashMap<>();
        budget.put("maxExternalCalls", maxExternalCalls);
        budget.put("maxTokens", maxTokens);
        config.put("budget", budget);
        config.put("createdAt", LocalDateTime.now().toString());
        if (suiteSnapshot != null) {
            config.put("suite", suiteSnapshot);
        }
        if (promptRef != null && promptRef.getName() != null) {
            Map<String, Object> prompt = new LinkedHashMap<>();
            prompt.put("name", promptRef.getName());
            prompt.put("version", promptRef.getVersion());
            prompt.put("label", promptRef.getLabel());
            prompt.put("hash", promptRef.getHash());
            config.put("promptRef", prompt);
        }
        if (commitSha != null && !commitSha.isBlank()) {
            config.put("commitSha", commitSha.trim());
        }
        config.put("candidateFingerprint", candidateFingerprint(revision, evaluatorSnapshots, mode,
                systemPrompt, effectiveModel, suiteSnapshot, dataset.getDomainPackId(),
                promptRef, commitSha, MODE_REPLAY_SIM.equals(mode)
                        ? simulationSnapshot(simulation, effectiveModel) : null));
        frozen.setConfigSnapshot(config);
        return frozen;
    }

    private void freezeReplayItem(Map<String, Object> sample, DatasetItemEntity item) {
        sample.put("agentKey", REPLAY_AGENT_KEY);
        sample.put("agentVersion", REPLAY_AGENT_VERSION);
        sample.put("contentStatus", item.getInput() == null || item.getInput().isBlank()
                ? "INPUT_MISSING" : "REPLAY_PENDING");
        sample.put("contentStatusReason", item.getInput() == null || item.getInput().isBlank()
                ? "样本缺少 input，无法重放" : null);
        sample.put("candidateComplete", item.getInput() != null && !item.getInput().isBlank());
        sample.put("candidateOutput", null);
        sample.put("contents", Map.of());
        sample.put("contentCaptureModes", Map.of());
        sample.put("contentTruncated", Map.of());
    }

    private void freezeTraceItem(Map<String, Object> sample, DatasetItemEntity item, String workspaceId) {
        String traceId = item.getTraceId();
        sample.put("traceId", traceId);
        sample.put("sourceTraceId", traceId);
        if (traceId == null || traceId.isBlank()) {
            markTraceMissing(sample, "样本没有关联 Trace");
            return;
        }
        TraceSummaryEntity summary = traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .eq(TraceSummaryEntity::getTraceId, traceId));
        if (summary == null || !workspaceId.equals(summary.getWorkspaceId())) {
            markTraceMissing(sample, "Trace 不存在或不属于当前工作空间");
            return;
        }
        if (!TraceSummaryEntity.STATUS_COMPLETED.equals(summary.getStatus())) {
            sample.put("agentKey", summary.getAgentKey());
            sample.put("agentVersion", summary.getAgentVersion());
            sample.put("spanCount", summary.getSpanCount());
            sample.put("errorCount", summary.getErrorCount());
            sample.put("durationMs", summary.getDurationMs());
            sample.put("contentStatus", "TRACE_NOT_COMPLETED");
            sample.put("contentStatusReason",
                    "Trace 尚未完成（status=" + summary.getStatus() + "），没有最终候选输出");
            sample.put("candidateOutput", null);
            sample.put("candidateComplete", false);
            sample.put("contents", Map.of());
            sample.put("contentCaptureModes", Map.of());
            sample.put("contentTruncated", Map.of());
            return;
        }
        sample.put("agentKey", summary.getAgentKey());
        sample.put("agentVersion", summary.getAgentVersion());
        sample.put("spanCount", summary.getSpanCount());
        sample.put("errorCount", summary.getErrorCount());
        sample.put("durationMs", summary.getDurationMs());

        List<SpanEventEntity> spans = traceAssemblyService.loadSpans(workspaceId, traceId);
        sample.put("toolCalls", spans.stream().filter(TrajectorySteps::isStep).count());
        sample.put("llmCalls", spans.stream().filter(s -> "llm".equals(s.getSpanType())).count());
        sample.put("toolsCalled", toolsCalled(spans));
        applyTraceRetrievalContext(sample, spans);
        sample.put("traceDigest", evaluationRunService.traceDigest(summary, spans, traceId));

        Map<String, String> contents = new LinkedHashMap<>();
        Map<String, String> modes = new LinkedHashMap<>();
        Map<String, Boolean> truncated = new LinkedHashMap<>();
        for (ContentEntity content : latestContents(traceId)) {
            if (content.getBody() != null) {
                contents.put(content.getKind(), content.getBody());
            }
            modes.put(content.getKind(), content.getCaptureMode());
            truncated.put(content.getKind(), isTruncated(content));
        }
        // CLI Agent（如 Codex）常把最终回答放在根 agent span，而不是 et_content。
        // 内容采集未开启时，用根 span 的 input/output 作为可评估正文，避免真实数据全部 UNKNOWN。
        SpanEventEntity agentSpan = latestAgentSpan(spans);
        if (!contents.containsKey(ContentEntity.KIND_COMPLETION)
                && agentSpan != null && agentSpan.getOutputText() != null
                && !agentSpan.getOutputText().isBlank()) {
            contents.put(ContentEntity.KIND_COMPLETION, agentSpan.getOutputText());
            modes.put(ContentEntity.KIND_COMPLETION, ContentCaptureService.MODE_FULL);
            truncated.put(ContentEntity.KIND_COMPLETION, false);
            sample.put("candidateSource", "AGENT_SPAN");
        }
        if (!contents.containsKey(ContentEntity.KIND_PROMPT)
                && agentSpan != null && agentSpan.getInputText() != null
                && !agentSpan.getInputText().isBlank()) {
            contents.put(ContentEntity.KIND_PROMPT, agentSpan.getInputText());
            modes.put(ContentEntity.KIND_PROMPT, ContentCaptureService.MODE_FULL);
            truncated.put(ContentEntity.KIND_PROMPT, false);
            sample.put("inputSource", "AGENT_SPAN");
        }
        sample.put("contents", contents);
        sample.put("contentCaptureModes", modes);
        sample.put("contentTruncated", truncated);
        // 样本没有手填 input 时，用 trace 的 prompt 内容作为展示/导出输入；参考与判定仍只认样本字段
        sample.put("traceInput", contents.get(ContentEntity.KIND_PROMPT));

        String candidate = contents.get(ContentEntity.KIND_COMPLETION);
        String completionMode = modes.get(ContentEntity.KIND_COMPLETION);
        sample.put("candidateOutput", candidate);
        if (candidate == null) {
            sample.put("contentStatus", "CANDIDATE_MISSING");
            sample.put("contentStatusReason", "trace 没有采集到 completion 内容，根 agent span 也没有 output");
            sample.put("candidateComplete", false);
        } else if (ContentCaptureService.MODE_HASHED.equals(completionMode)
                || ContentCaptureService.MODE_NONE.equals(completionMode)) {
            sample.put("contentStatus", "HASHED_ONLY");
            sample.put("contentStatusReason", "completion 采集模式=" + completionMode + "，没有可评估正文");
            sample.put("candidateComplete", false);
        } else if (Boolean.TRUE.equals(truncated.get(ContentEntity.KIND_COMPLETION))) {
            sample.put("contentStatus", "CONTENT_TRUNCATED");
            sample.put("contentStatusReason", "completion 在采集上限处被截断，无法确认完整匹配");
            sample.put("candidateComplete", false);
        } else {
            sample.put("contentStatus", "COMPLETE");
            sample.put("candidateComplete", true);
        }
        sample.put("candidateHash", candidate == null ? null : Sha256Utils.hash(candidate));
    }

    /** 实际工具调用序列：只取 tool span；输入/输出尽量解析为 JSON，失败保留原文。 */
    private List<Map<String, Object>> toolsCalled(List<SpanEventEntity> spans) {
        List<Map<String, Object>> tools = new ArrayList<>();
        int order = 0;
        for (SpanEventEntity span : spans) {
            if (!"tool".equals(span.getSpanType())) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", firstNonBlank(span.getToolName(), span.getSpanName(), "unknown"));
            tool.put("type", "FUNCTION");
            tool.put("inputParameters", jsonOrText(span.getInputText()));
            tool.put("output", jsonOrText(span.getOutputText()));
            tool.put("status", span.getStatus());
            tool.put("spanId", span.getSpanId());
            tool.put("order", order++);
            tools.add(tool);
        }
        return tools;
    }

    /** 样本未显式提供 retrievalContext 时，用 retrieval span 的输出作为实际检索证据。 */
    @SuppressWarnings("unchecked")
    private void applyTraceRetrievalContext(Map<String, Object> sample, List<SpanEventEntity> spans) {
        Object existing = sample.get("retrievalContext");
        boolean sampleProvided = existing instanceof List && !((List<?>) existing).isEmpty();
        if (sampleProvided) {
            sample.put("retrievalContextSource", "SAMPLE");
            return;
        }
        List<Map<String, Object>> contexts = new ArrayList<>();
        for (SpanEventEntity span : spans) {
            if (!"retrieval".equals(span.getSpanType())) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", span.getSpanId());
            item.put("content", span.getOutputText() == null ? "" : span.getOutputText());
            item.put("source", firstNonBlank(span.getToolName(), span.getSpanName(), ""));
            item.put("spanId", span.getSpanId());
            contexts.add(item);
        }
        if (contexts.isEmpty()) {
            sample.put("retrievalContextSource", "NONE");
            return;
        }
        sample.put("retrievalContext", contexts);
        sample.put("retrievalContextSource", "TRACE");
    }

    private Object jsonOrText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            return text;
        }
    }

    private static String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private void markTraceMissing(Map<String, Object> sample, String reason) {
        sample.put("contentStatus", "TRACE_MISSING");
        sample.put("contentStatusReason", reason);
        sample.put("candidateOutput", null);
        sample.put("candidateComplete", false);
        sample.put("contents", Map.of());
        sample.put("contentCaptureModes", Map.of());
        sample.put("contentTruncated", Map.of());
    }

    /** trace 派生字段超限时丢弃可重建内容并标记不可完整评估；用户提供的 input/reference 超限则直接拒绝。 */
    private Map<String, Object> shrinkIfNeeded(Map<String, Object> sample, int maxChars, String itemId) {
        if (jsonLength(sample) <= maxChars) {
            return sample;
        }
        sample.put("traceDigest", null);
        sample.put("contents", Map.of());
        sample.put("contentCaptureModes", Map.of());
        sample.put("contentTruncated", Map.of());
        // trace 派生证据可重建，超限时优先丢弃；用户显式提供的 v2 字段保留，
        // 若仍超限则明确拒绝，避免“存进去了但评估时被静默裁剪”。
        sample.put("toolsCalled", List.of());
        if ("TRACE".equals(sample.get("retrievalContextSource"))) {
            sample.put("retrievalContext", List.of());
            sample.put("retrievalContextSource", "DROPPED");
        }
        sample.put("candidateComplete", false);
        sample.put("contentStatus", "SNAPSHOT_TOO_LARGE");
        sample.put("contentStatusReason", "快照超过 " + maxChars + " 字符，trace 派生内容未保存");
        if (jsonLength(sample) > maxChars) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "样本 " + itemId + " 的 input/expectedOutput 超过快照上限 " + maxChars + " 字符，请拆分或缩短后再运行");
        }
        log.warn("dataset sample snapshot shrunk, item={}, reason=too large", itemId);
        return sample;
    }

    private void checkItemTextLength(DatasetItemEntity item, int maxChars) {
        if (item.getInput() != null && item.getInput().length() > maxChars) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "样本 " + item.getId() + " 的 input 超过快照上限 " + maxChars + " 字符");
        }
        if (item.getExpectedOutput() != null && item.getExpectedOutput().length() > maxChars) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "样本 " + item.getId() + " 的 expectedOutput 超过快照上限 " + maxChars + " 字符");
        }
    }

    /** 优先取根 agent span（parentSpanId 为空），否则取最新的带 output 的 agent span。 */
    private SpanEventEntity latestAgentSpan(List<SpanEventEntity> spans) {
        List<SpanEventEntity> agents = spans.stream()
                .filter(s -> "agent".equals(s.getSpanType()))
                .filter(s -> s.getOutputText() != null && !s.getOutputText().isBlank())
                .collect(java.util.stream.Collectors.toList());
        if (agents.isEmpty()) {
            return null;
        }
        Comparator<SpanEventEntity> byStart = Comparator.comparing(SpanEventEntity::getStartTime,
                Comparator.nullsFirst(Comparator.naturalOrder()));
        return agents.stream().filter(s -> s.getParentSpanId() == null).max(byStart)
                .orElseGet(() -> agents.stream().max(byStart).orElse(null));
    }

    private List<ContentEntity> latestContents(String traceId) {
        List<ContentEntity> all = contentMapper.selectList(Wrappers.<ContentEntity>lambdaQuery()
                .eq(ContentEntity::getTraceId, traceId)
                .orderByDesc(ContentEntity::getCreatedAt));
        Map<String, ContentEntity> latest = new LinkedHashMap<>();
        for (ContentEntity c : all) {
            latest.putIfAbsent(c.getKind(), c);
        }
        return new ArrayList<>(latest.values());
    }

    private ContentEntity latestContent(String traceId, String kind) {
        return contentMapper.selectOne(Wrappers.<ContentEntity>lambdaQuery()
                .eq(ContentEntity::getTraceId, traceId)
                .eq(ContentEntity::getKind, kind)
                .orderByDesc(ContentEntity::getCreatedAt)
                .last("LIMIT 1"));
    }

    private boolean isTruncated(ContentEntity content) {
        int maxLength = properties.getContent().getMaxContentLength();
        return content.getContentLength() != null && content.getContentLength() > maxLength;
    }

    private Map<String, Object> revisionSnapshot(DatasetRevisionEntity revision) {
        Map<String, Object> value = new LinkedHashMap<>();
        if (revision == null) {
            return value;
        }
        value.put("id", revision.getId());
        value.put("revision", revision.getRevision());
        value.put("label", revision.getLabel());
        value.put("manifestHash", revision.getManifestHash());
        value.put("itemCount", revision.getItemCount());
        return value;
    }

    private String candidateFingerprint(DatasetRevisionEntity revision,
                                        List<Map<String, Object>> evaluatorSnapshots,
                                        String mode, String systemPrompt, String effectiveModel,
                                        Map<String, Object> suiteSnapshot, String domainPackId,
                                        io.emonlens.server.engine.prompt.PromptService.PromptRef promptRef,
                                        String commitSha, Map<String, Object> simulation) {
        StringBuilder material = new StringBuilder();
        material.append(revision == null ? "" : revision.getManifestHash()).append('|')
                .append(mode).append('|')
                .append(effectiveModel == null ? "" : effectiveModel).append('|')
                .append(systemPrompt == null ? "" : systemPrompt);
        // 仿真配置进指纹：换 persona / 轮数 / 端点就是换候选，旧结论不可比。
        if (simulation != null) {
            material.append("|sim:").append(simulation.get("fingerprint"));
        }
        for (Map<String, Object> snapshot : evaluatorSnapshots) {
            material.append('|').append(snapshot.get("fingerprint"));
        }
        if (suiteSnapshot != null) {
            material.append("|suite:").append(suiteSnapshot.get("fingerprint"));
        }
        if (domainPackId != null && !domainPackId.isBlank()) {
            material.append("|pack:").append(domainPackId);
        }
        // prompt 版本进指纹：换 prompt 版本 / 换 production 指针，旧结论自然不可比。
        if (promptRef != null && promptRef.getName() != null) {
            material.append("|prompt:").append(promptRef.getName())
                    .append(":v").append(promptRef.getVersion())
                    .append(':').append(promptRef.getHash());
        }
        // 提交号只做可追溯，不进指纹：同配置不同提交不应被判为不同候选。
        return Sha256Utils.hash(material.toString());
    }

    private Map<String, Object> evaluatorSnapshot(EvaluatorEntity evaluator, EvaluatorSpec spec,
                                                  String effectiveModel) {
        Map<String, Object> raw = parseSpecMap(evaluator);
        if (EvaluatorEntity.TYPE_LLM_JUDGE.equals(evaluator.getType())
                && (spec.getJudgeModel() == null || spec.getJudgeModel().isEmpty())) {
            // 冻结实际有效的评审模型，运行中不再读取默认模型
            spec.setJudgeModel(effectiveModel);
            raw.put("judgeModel", effectiveModel);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("id", evaluator.getId());
        snapshot.put("key", evaluator.getEvaluatorKey());
        snapshot.put("type", evaluator.getType());
        snapshot.put("version", evaluator.getVersion());
        snapshot.put("spec", raw);
        String templateMaterial = "";
        if (EvaluatorEntity.TYPE_LLM_JUDGE.equals(evaluator.getType())) {
            JudgeTemplate template = judgeTemplateService.resolve(evaluator.getWorkspaceId(),
                    spec.getJudgeTemplateKey(), spec.getJudgeTemplateVersion());
            // 旧评估器可能没有显式版本；运行快照固定版本，执行时不再解析“最新模板”
            raw.put("judgeTemplateVersion", template.getVersion());
            Map<String, Object> templateSnapshot = new LinkedHashMap<>();
            templateSnapshot.put("key", template.getKey());
            templateSnapshot.put("version", template.getVersion());
            templateSnapshot.put("hash", template.getHash());
            templateSnapshot.put("source", template.getSource());
            snapshot.put("judgeTemplate", templateSnapshot);
            templateMaterial = "|" + template.getHash();
        }
        snapshot.put("fingerprint", Sha256Utils.hash(
                canonicalJson(evaluator.getType()) + "|" + canonicalJson(raw) + templateMaterial));
        snapshot.put("effectiveJudgeModel", EvaluatorEntity.TYPE_LLM_JUDGE.equals(evaluator.getType())
                ? spec.getJudgeModel() : null);
        snapshot.put("referenceRequired", Boolean.TRUE.equals(spec.getReferenceRequired()));
        return snapshot;
    }

    private Map<String, Object> generationSnapshot(String mode, String systemPrompt, String model) {
        Map<String, Object> generation = new LinkedHashMap<>();
        generation.put("mode", mode);
        if (MODE_REPLAY.equals(mode)) {
            generation.put("systemPrompt", systemPrompt == null || systemPrompt.isBlank()
                    ? "You are a helpful assistant." : systemPrompt);
            generation.put("model", model);
            generation.put("temperature", 0.2);
        }
        return generation;
    }

    /**
     * 仿真配置快照：persona / goal / maxTurns / endpointUrl 参与候选指纹。
     * 端点指纹只取 scheme://host:port/path（同端点换 query 参数仍视为同一候选），完整 URL 单独存便于回溯。
     */
    private Map<String, Object> simulationSnapshot(Map<String, Object> simulation, String fallbackModel) {
        if (simulation == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "REPLAY_SIM 需要 simulation 配置（persona / maxTurns / endpointUrl）");
        }
        String persona = simulation.get("persona") == null ? null : String.valueOf(simulation.get("persona"));
        String goal = simulation.get("goal") == null ? null : String.valueOf(simulation.get("goal"));
        String endpointUrl = simulation.get("endpointUrl") == null
                ? null : String.valueOf(simulation.get("endpointUrl"));
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "REPLAY_SIM 需要 simulation.endpointUrl");
        }
        int maxTurns = simulation.get("maxTurns") instanceof Number
                ? ((Number) simulation.get("maxTurns")).intValue()
                : properties.getSim().getMaxTurns();
        String model = simulation.get("model") == null
                ? fallbackModel : String.valueOf(simulation.get("model"));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("protocol", "conversation-sim/1");
        snapshot.put("persona", persona);
        snapshot.put("goal", goal);
        snapshot.put("maxTurns", Math.max(1, Math.min(maxTurns, properties.getSim().getMaxTurns())));
        snapshot.put("endpointUrl", endpointUrl);
        snapshot.put("model", model);
        snapshot.put("temperature", simulation.get("temperature") instanceof Number
                ? ((Number) simulation.get("temperature")).doubleValue() : 0.7);
        snapshot.put("fingerprint", Sha256Utils.hash(persona + "|" + goal + "|"
                + snapshot.get("maxTurns") + "|" + endpointKey(endpointUrl) + "|" + model + "|"
                + snapshot.get("temperature")));
        return snapshot;
    }

    /** 端点指纹只取 scheme://host:port/path，避免 query 里的临时参数把候选指纹打散。 */
    private String endpointKey(String endpointUrl) {
        try {
            java.net.URI uri = java.net.URI.create(endpointUrl);
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            return (uri.getScheme() == null ? "http" : uri.getScheme()) + "://" + uri.getHost() + port
                    + (uri.getPath() == null ? "" : uri.getPath());
        } catch (Exception e) {
            return endpointUrl;
        }
    }

    private Map<String, Object> contentPolicySnapshot() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("version", CONTENT_PROCESSING_VERSION);
        policy.put("captureMode", properties.getContent().getCaptureMode());
        policy.put("maxContentLength", properties.getContent().getMaxContentLength());
        policy.put("exactMatchNormalization", "trim+unified-newlines");
        return policy;
    }

    private String effectiveModel(String requestedModel) {
        if (requestedModel != null && !requestedModel.isBlank()) {
            return requestedModel.trim();
        }
        SysSettingService.EvalLlm db = sysSettingService.getEvalLlm();
        if (db != null && db.getModel() != null && !db.getModel().isBlank()) {
            return db.getModel();
        }
        return properties.getEval().getLlm().getModel();
    }

    private EvaluatorSpec parseSpec(EvaluatorEntity evaluator) {
        try {
            return objectMapper.readValue(evaluator.getSpecJson(), EvaluatorSpec.class);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "evaluator spec unparseable: " + evaluator.getId());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSpecMap(EvaluatorEntity evaluator) {
        try {
            return objectMapper.readValue(evaluator.getSpecJson(), Map.class);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "evaluator spec unparseable: " + evaluator.getId());
        }
    }

    private String canonicalJson(Object value) {
        try {
            ObjectMapper canonical = objectMapper.copy();
            canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return canonical.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "canonical json failed");
        }
    }

    private int jsonLength(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value).length();
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "snapshot json failed");
        }
    }
}
