package io.emonlens.server.openapi;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.audit.AuditService;
import io.emonlens.server.engine.monitor.MonitorService;
import io.emonlens.server.engine.eval.DatasetRunService;
import io.emonlens.server.engine.eval.SdkEvalResultService;
import io.emonlens.server.engine.gate.QualityGateService;
import io.emonlens.server.engine.score.TrustScoreService;
import io.emonlens.server.storage.entity.AccessTokenEntity;
import io.emonlens.server.storage.entity.MonitorEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.QualityGateEntity;
import io.emonlens.server.storage.entity.TrustScoreEntity;
import io.emonlens.server.storage.mapper.AccessTokenMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * 开放 API（X-Api-Token）：外部系统（CI/CD、ITSM、数据质量平台）机器对机器调用。
 * token 属于签发时的工作空间，全部数据访问按该工作空间隔离。
 */
@RestController
@RequestMapping("/api/v1/open")
@RequiredArgsConstructor
public class OpenApiController {

    private final AccessTokenMapper accessTokenMapper;
    private final MonitorService monitorService;
    private final TrustScoreService trustScoreService;
    private final QualityGateService qualityGateService;
    private final DatasetRunService datasetRunService;
    private final SdkEvalResultService sdkEvalResultService;
    private final io.emonlens.server.engine.eval.DatasetRunComparisonService datasetRunComparisonService;
    private final AuditService auditService;

    private AccessTokenEntity verify(String token, String scope) {
        if (token == null || token.isEmpty()) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "missing X-Api-Token");
        }
        AccessTokenEntity entity = accessTokenMapper.selectOne(Wrappers.<AccessTokenEntity>lambdaQuery()
                .eq(AccessTokenEntity::getTokenHash, Sha256Utils.hash(token))
                .eq(AccessTokenEntity::getStatus, AccessTokenEntity.STATUS_ACTIVE));
        if (entity == null) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "invalid api token");
        }
        // 兼容历史签发的 scope 串：trim 去空格，避免 "score:read, monitors:write" 第二个 scope 误判 403
        if (!parseScopes(entity).contains(scope)) {
            throw new EmonLensException(ErrorCode.FORBIDDEN, "token missing scope: " + scope);
        }
        entity.setLastUsedAt(LocalDateTime.now());
        accessTokenMapper.updateById(entity);
        return entity;
    }

    /** token 是否拥有指定 scope；用于单请求的多 scope 组合（eval:read + eval:content）。 */
    private boolean hasScope(AccessTokenEntity entity, String scope) {
        return parseScopes(entity).contains(scope);
    }

    private Set<String> parseScopes(AccessTokenEntity entity) {
        Set<String> scopes = new HashSet<>();
        for (String s : String.valueOf(entity.getScopes()).split(",")) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                scopes.add(trimmed);
            }
        }
        return scopes;
    }

    @GetMapping("/score/agents/{agentKey}")
    public ApiResult<TrustScoreEntity> agentScore(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String agentKey) {
        AccessTokenEntity tokenEntity = verify(token, "score:read");
        TrustScoreEntity snapshot = trustScoreService.latest(tokenEntity.getWorkspaceId(), agentKey, "24h");
        if (snapshot == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "no score snapshot for agent: " + agentKey);
        }
        return ApiResult.ok(snapshot);
    }

    /** CI 生成准入结论；只应消费 PASS，BLOCKED 不得放行。 */
    @PostMapping("/quality-gates")
    public ApiResult<QualityGateEntity> createQualityGate(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestBody OpenGateBody body) {
        AccessTokenEntity tokenEntity = verify(token, "score:read");
        if (body.getRunId() == null || body.getRunId().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "runId is required");
        }
        QualityGateEntity gate = qualityGateService.evaluate(tokenEntity.getWorkspaceId(), body.getRunId(),
                body.getPolicyKey(), body.getMinCoverage() == null ? 90.0 : body.getMinCoverage(),
                body.getMaxFail() == null ? 0 : body.getMaxFail(),
                body.getMaxUnknown() == null ? 0 : body.getMaxUnknown(),
                body.getValidHours() == null ? 24 : body.getValidHours(), "open-api");
        auditService.recordSystem(tokenEntity.getWorkspaceId(),
                "open-api:" + tokenEntity.getName(), "open.quality_gate.evaluate", "quality_gate", gate.getId(),
                Map.of("runId", body.getRunId(), "policyKey", String.valueOf(body.getPolicyKey()),
                        "decision", String.valueOf(gate.getDecision()),
                        "coverage", String.valueOf(gate.getCoverage())));
        return ApiResult.ok(gate);
    }

    /** CI 校验某个历史 Gate 是否仍可用：过期、被取代、run 变更或指纹变化都返回 usable=false。 */
    @GetMapping("/quality-gates/{id}/verification")
    public ApiResult<Map<String, Object>> verifyQualityGate(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String id) {
        AccessTokenEntity tokenEntity = verify(token, "score:read");
        return ApiResult.ok(qualityGateService.verify(tokenEntity.getWorkspaceId(), id));
    }

    /** CI 读取某次 run 的最新准入结论 */
    @GetMapping("/quality-gates/{runId}")
    public ApiResult<QualityGateEntity> latestQualityGate(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String runId) {
        AccessTokenEntity tokenEntity = verify(token, "score:read");
        QualityGateEntity gate = qualityGateService.latest(tokenEntity.getWorkspaceId(), runId);
        if (gate == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "no quality gate for run: " + runId);
        }
        return ApiResult.ok(gate);
    }

    @Data
    public static class OpenRunBody {
        private String datasetId;
        private String evaluatorId;
        private List<String> evaluatorIds;
        private String suiteId;
        private String revisionId;
        private String mode;
        private String systemPrompt;
        private String model;
        private String candidateLabel;
        private String candidateFingerprint;
        private Integer maxExternalCalls;
        private Long maxTokens;
        private String reviewMode;
        /** R1-3：绑定 prompt 版本（version 优先；只给 name 时按 label，缺省 production） */
        private String promptName;
        private Integer promptVersion;
        private String promptLabel;
        /** R1-4：CI 基线标签与提交号 */
        private String runLabel;
        private String commitSha;
    }

    /** SDK/CI 提交一次数据集评测；只返回 run 标识与状态，不返回样本正文。 */
    @PostMapping("/eval-runs")
    public ResponseEntity<ApiResult<Map<String, Object>>> createEvalRun(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody OpenRunBody body) {
        AccessTokenEntity tokenEntity = verify(token, "eval:run");
        if (body == null || body.getDatasetId() == null || body.getDatasetId().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "datasetId is required");
        }
        List<String> evaluatorIds = body.getEvaluatorIds();
        if ((evaluatorIds == null || evaluatorIds.isEmpty())
                && body.getEvaluatorId() != null && !body.getEvaluatorId().isBlank()) {
            evaluatorIds = List.of(body.getEvaluatorId());
        }
        if ((evaluatorIds == null || evaluatorIds.isEmpty())
                && (body.getSuiteId() == null || body.getSuiteId().isBlank())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "evaluatorId/evaluatorIds 或 suiteId 至少提供一个");
        }
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        DatasetRunService.AcceptResult result = datasetRunService.accept(
                tokenEntity.getWorkspaceId(), body.getDatasetId(), evaluatorIds,
                body.getRevisionId(), body.getMode(), body.getSystemPrompt(), body.getModel(),
                body.getCandidateLabel(), body.getCandidateFingerprint(),
                body.getMaxExternalCalls(), body.getMaxTokens(), body.getReviewMode(),
                body.getSuiteId(), idempotencyKey, null,
                body.getPromptName() == null || body.getPromptName().isBlank() ? null
                        : new io.emonlens.server.engine.prompt.PromptService.PromptRef(
                                body.getPromptName().trim(), body.getPromptVersion(),
                                body.getPromptLabel(), null),
                body.getRunLabel(), body.getCommitSha());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", result.getRun().getId());
        payload.put("status", result.getRun().getStatus());
        payload.put("created", result.isCreated());
        payload.put("run", result.getRun());
        auditService.recordSystem(tokenEntity.getWorkspaceId(),
                "open-api:" + tokenEntity.getName(), result.isCreated() ? "open.eval_run.create" : "open.eval_run.reuse",
                "dataset_run", result.getRun().getId(),
                Map.of("datasetId", body.getDatasetId(), "mode", String.valueOf(body.getMode()),
                        "suiteId", String.valueOf(body.getSuiteId()),
                        "evaluatorIds", body.getEvaluatorIds() == null ? List.of() : body.getEvaluatorIds(),
                        "candidateLabel", String.valueOf(body.getCandidateLabel())));
        return ResponseEntity.status(result.isCreated() ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResult.ok(payload));
    }

    /**
     * CI 基线解析（R1-4）：按 runLabel 取该数据集最近一条 run。
     * 返回 run 概要 + 最新 Gate，不含样本正文；没有基线时返回 resolved=false。
     */
    @GetMapping("/eval-runs")
    public ApiResult<Map<String, Object>> listEvalRuns(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestParam String datasetId,
            @RequestParam(defaultValue = "baseline") String label) {
        AccessTokenEntity tokenEntity = verify(token, "eval:read");
        DatasetRunEntity run = datasetRunService.latestByLabel(
                tokenEntity.getWorkspaceId(), datasetId, label);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("resolved", run != null);
        payload.put("label", label);
        payload.put("datasetId", datasetId);
        if (run == null) {
            return ApiResult.ok(payload);
        }
        datasetRunService.enrich(run, datasetRunService.itemsOf(run.getId()), false, false);
        run.setConfigSnapshot(null);
        payload.put("run", run);
        payload.put("gate", qualityGateService.latest(tokenEntity.getWorkspaceId(), run.getId()));
        return ApiResult.ok(payload);
    }

    /**
     * CI 基线与候选的 diff 摘要（R1-4）：只回传计数与 transition，不回传样本正文。
     * 基线未指定时按 runLabel 解析该数据集最近一条基线 run。
     */
    @GetMapping("/eval-runs/{runId}/diff")
    public ApiResult<Map<String, Object>> diffAgainstBaseline(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String runId,
            @RequestParam(required = false) String baselineRunId,
            @RequestParam(defaultValue = "baseline") String label) {
        AccessTokenEntity tokenEntity = verify(token, "eval:read");
        DatasetRunEntity candidate = datasetRunService.requireRun(tokenEntity.getWorkspaceId(), runId);
        DatasetRunEntity baseline = baselineRunId == null || baselineRunId.isBlank()
                ? datasetRunService.latestByLabel(tokenEntity.getWorkspaceId(),
                        candidate.getDatasetId(), label)
                : datasetRunService.requireRun(tokenEntity.getWorkspaceId(), baselineRunId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("baselineResolved", baseline != null);
        payload.put("label", label);
        if (baseline == null) {
            return ApiResult.ok(payload);
        }
        payload.put("baselineRunId", baseline.getId());
        payload.put("diff", datasetRunComparisonService.compare(baseline,
                datasetRunService.itemsOf(baseline.getId()), candidate,
                datasetRunService.itemsOf(candidate.getId()), false));
        return ApiResult.ok(payload);
    }

    /** SDK/CI 查询 run 状态与最新 Gate；不返回样本正文。 */
    @GetMapping("/eval-runs/{runId}")
    public ApiResult<Map<String, Object>> getEvalRun(            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String runId) {
        AccessTokenEntity tokenEntity = verify(token, "eval:read");
        DatasetRunEntity run = datasetRunService.requireRun(tokenEntity.getWorkspaceId(), runId);
        // CI 消费 unknown/error/incomplete 计数；不带正文，也不回传大配置快照
        datasetRunService.enrich(run, datasetRunService.itemsOf(runId), false, false);
        run.setConfigSnapshot(null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("run", run);
        QualityGateEntity gate = qualityGateService.latest(tokenEntity.getWorkspaceId(), runId);
        payload.put("gate", gate);
        return ApiResult.ok(payload);
    }

    @Data
    public static class OpenGateBody {
        private String runId;
        private String policyKey;
        private Double minCoverage;
        private Integer maxFail;
        private Integer maxUnknown;
        private Integer validHours;
    }

    /**
     * SDK 评测器任务清单与样本载荷。eval:read 即可拉取；样本正文（input/expected/candidate 等）
     * 需要额外 eval:content scope，否则只回传 hash 与状态——与导出脱敏口径一致。
     */
    @GetMapping("/eval-runs/{runId}/sdk-tasks")
    public ApiResult<Map<String, Object>> sdkTasks(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String runId) {
        AccessTokenEntity tokenEntity = verify(token, "eval:read");
        boolean contentAllowed = hasScope(tokenEntity, "eval:content");
        Map<String, Object> payload = sdkEvalResultService.sdkTasks(
                tokenEntity.getWorkspaceId(), runId, contentAllowed);
        return ApiResult.ok(payload);
    }

    @Data
    public static class SdkResultsBody {
        private List<SdkEvalResultService.SdkResultInput> results;
    }

    /** SDK 评测结果回写：按任务账本终态机落账，幂等重复返回 DUPLICATE，冲突返回 CONFLICT。 */
    @PostMapping("/eval-runs/{runId}/results")
    public ApiResult<Map<String, Object>> pushSdkResults(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @PathVariable String runId,
            @RequestBody SdkResultsBody body) {
        AccessTokenEntity tokenEntity = verify(token, "eval:run");
        List<SdkEvalResultService.SdkResultInput> results = body == null ? null : body.getResults();
        Map<String, Object> payload = sdkEvalResultService.pushResults(
                tokenEntity.getWorkspaceId(), runId, results);
        long applied = 0;
        if (payload.get("results") instanceof List) {
            for (Object raw : (List<?>) payload.get("results")) {
                if (raw instanceof Map && "APPLIED".equals(((Map<?, ?>) raw).get("outcome"))) {
                    applied++;
                }
            }
        }
        auditService.recordSystem(tokenEntity.getWorkspaceId(),
                "open-api:" + tokenEntity.getName(), "open.eval_run.sdk_results", "dataset_run", runId,
                Map.of("submitted", results == null ? 0 : results.size(), "applied", applied));
        return ApiResult.ok(payload);
    }

    /** 外部系统注册规则（如 CI 流水线自动挂门禁）；workspace 随 token */
    @PostMapping("/monitors")
    public ApiResult<Map<String, Object>> createMonitor(
            @RequestHeader(value = "X-Api-Token", required = false) String token,
            @RequestBody MonitorBody body) {
        AccessTokenEntity tokenEntity = verify(token, "monitors:write");
        if (body == null || isBlank(body.getAgentKey()) || isBlank(body.getMonitorKey())
                || isBlank(body.getKind())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "agentKey, monitorKey and kind are required");
        }
        if (body.getSeverity() != null && !isBlank(body.getSeverity())
                && !Set.of("CRITICAL", "HIGH", "MEDIUM", "LOW")
                        .contains(body.getSeverity().trim().toUpperCase())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "severity must be CRITICAL, HIGH, MEDIUM or LOW");
        }
        String severity = isBlank(body.getSeverity()) ? null : body.getSeverity().trim().toUpperCase();
        MonitorEntity entity = monitorService.create(tokenEntity.getWorkspaceId(), body.getAgentKey(),
                body.getMonitorKey(), body.getKind(), body.getSpec(),
                severity, body.getDimension());
        auditService.recordSystem(tokenEntity.getWorkspaceId(),
                "open-api:" + tokenEntity.getName(), "open.monitor.create", "monitor", entity.getId(),
                Map.of("agentKey", body.getAgentKey(), "monitorKey", body.getMonitorKey(),
                        "kind", body.getKind(), "severity", severity == null ? "HIGH" : severity,
                        "dimension", entity.getDimension()));
        return ApiResult.ok(Map.of(
                "id", entity.getId(),
                "monitorKey", entity.getMonitorKey(),
                "workspaceId", entity.getWorkspaceId()));
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Data
    public static class MonitorBody {
        private String agentKey;
        private String monitorKey;
        private String kind;
        private java.util.Map<String, Object> spec;
        private String severity;
        private String dimension;
    }
}
