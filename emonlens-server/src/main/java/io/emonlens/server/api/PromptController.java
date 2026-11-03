package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.storage.entity.PromptEntity;
import io.emonlens.server.storage.mapper.PromptMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Prompt Management（对标 Langfuse Prompt Management）：
 * 按名称版本化存储，支持 production/latest 标签解析与回滚。
 */
@RestController
@RequestMapping("/api/v1/prompts")
@RequiredArgsConstructor
public class PromptController {

    public static final String LABEL_PRODUCTION = "production";
    public static final String LABEL_LATEST = "latest";

    private final PromptMapper promptMapper;
    private final TraceSummaryMapper traceSummaryMapper;
    private final EvalResultMapper evalResultMapper;
    private final io.emonlens.server.engine.prompt.PromptService promptService;
    private final io.emonlens.server.storage.mapper.DatasetRunMapper datasetRunMapper;
    private final io.emonlens.server.storage.mapper.QualityGateMapper qualityGateMapper;

    @Data
    public static class CreateRequest {
        private String name;
        private String prompt;
        private Map<String, Object> config;
        private List<String> labels;
        private Boolean setProduction;
    }

    @Data
    public static class LabelRequest {
        private String label;
        private Integer version;
    }

    /** 提示词列表：每个 name 聚合为一行（最新版本内容 + 版本数 + production 指向），创建时间倒序 */
    @GetMapping
    @RequiresPermission(Permissions.PROMPT_QUERY)
    public ApiResult<PageVO<Map<String, Object>>> page(@RequestParam(defaultValue = "1") int pageNumber,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        String ws = CurrentContext.require().getWorkspaceId();
        List<PromptEntity> all = promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                .eq(PromptEntity::getWorkspaceId, ws)
                .orderByDesc(PromptEntity::getVersion));
        Map<String, List<PromptEntity>> byName = new java.util.LinkedHashMap<>();
        for (PromptEntity e : all) {
            byName.computeIfAbsent(e.getName(), k -> new java.util.ArrayList<>()).add(e);
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (Map.Entry<String, List<PromptEntity>> entry : byName.entrySet()) {
            List<PromptEntity> vs = entry.getValue();
            PromptEntity latest = vs.get(0);
            Map<String, Object> vo = toVO(latest);
            vo.put("versionCount", vs.size());
            Integer productionVersion = null;
            for (PromptEntity v : vs) {
                if (labelsOf(v).contains(LABEL_PRODUCTION)) {
                    productionVersion = v.getVersion();
                    break;
                }
            }
            vo.put("productionVersion", productionVersion);
            rows.add(vo);
        }
        rows.sort((a, b) -> String.valueOf(b.get("createdAt")).compareTo(String.valueOf(a.get("createdAt"))));
        int page = Math.max(1, pageNumber);
        int size = Math.max(1, Math.min(pageSize, 1000));
        int from = Math.max(0, Math.min((page - 1) * size, rows.size()));
        int to = Math.max(from, Math.min(from + size, rows.size()));
        return ApiResult.ok(PageVO.of(rows.subList(from, to), rows.size(), page, size));
    }

    /** 删除提示词（全部版本）。已上报的历史 trace 用量统计保留，仅移除提示词本体。 */
    @org.springframework.web.bind.annotation.DeleteMapping("/{name}")
    @RequiresPermission(Permissions.PROMPT_MANAGE)
    @Audit(action = "prompt.delete", resourceType = "prompt")
    public ApiResult<Map<String, Object>> delete(@PathVariable String name) {
        String ws = CurrentContext.require().getWorkspaceId();
        List<PromptEntity> versions = promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                .eq(PromptEntity::getWorkspaceId, ws)
                .eq(PromptEntity::getName, name));
        if (versions.isEmpty()) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "提示词不存在: " + name);
        }
        promptMapper.delete(Wrappers.<PromptEntity>lambdaQuery()
                .eq(PromptEntity::getWorkspaceId, ws)
                .eq(PromptEntity::getName, name));
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        m.put("deletedVersions", versions.size());
        return ApiResult.ok(m);
    }

    /**
     * Prompt 用量统计（Langfuse prompt linkage）：按版本统计关联 trace 数与评估通过率。
     * trace 的 prompt_name/prompt_version 由 SDK 上报 span attributes 透传建立。
     */
    @GetMapping("/{name}/usage")
    @RequiresPermission(Permissions.PROMPT_QUERY)
    public ApiResult<List<Map<String, Object>>> usage(@PathVariable String name) {
        String ws = CurrentContext.require().getWorkspaceId();
        List<TraceSummaryEntity> linked = traceSummaryMapper.selectList(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, ws)
                        .eq(TraceSummaryEntity::getPromptName, name));
        Map<String, List<TraceSummaryEntity>> byVersion = linked.stream()
                .collect(Collectors.groupingBy(s -> s.getPromptVersion() == null ? "" : s.getPromptVersion(),
                        java.util.TreeMap::new, Collectors.toList()));
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (Map.Entry<String, List<TraceSummaryEntity>> e : byVersion.entrySet()) {
            List<String> traceIds = e.getValue().stream().map(TraceSummaryEntity::getTraceId).collect(Collectors.toList());
            long pass = 0;
            long fail = 0;
            if (!traceIds.isEmpty()) {
                List<EvalResultEntity> results = evalResultMapper.selectList(
                        Wrappers.<EvalResultEntity>lambdaQuery()
                                .eq(EvalResultEntity::getWorkspaceId, ws)
                                .in(EvalResultEntity::getTraceId, traceIds)
                                .in(EvalResultEntity::getLabel, "PASS", "FAIL"));
                pass = results.stream().filter(r -> "PASS".equals(r.getLabel())).count();
                fail = results.size() - pass;
            }
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("promptVersion", e.getKey());
            m.put("traceCount", e.getValue().size());
            m.put("errorTraces", e.getValue().stream().filter(s -> s.getErrorCount() != null && s.getErrorCount() > 0).count());
            m.put("passCount", pass);
            m.put("failCount", fail);
            long judged = pass + fail;
            m.put("passRate", judged > 0 ? Math.round(pass * 100.0 / judged) : null);
            m.put("lastUsedAt", e.getValue().stream().map(s -> s.getStartTime()).filter(s -> s != null)
                    .max(java.time.LocalDateTime::compareTo).map(Object::toString).orElse(""));
            out.add(m);
        }
        return ApiResult.ok(out);
    }

    /** 某名称的全部版本（版本倒序） */
    @GetMapping("/{name}/versions")
    @RequiresPermission(Permissions.PROMPT_QUERY)
    public ApiResult<List<Map<String, Object>>> versions(@PathVariable String name) {
        String ws = CurrentContext.require().getWorkspaceId();
        List<PromptEntity> versions = promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                .eq(PromptEntity::getWorkspaceId, ws)
                .eq(PromptEntity::getName, name)
                .orderByDesc(PromptEntity::getVersion));
        return ApiResult.ok(versions.stream().map(this::toVO).collect(Collectors.toList()));
    }

    /** 按标签解析（production / latest），供 Agent 运行时拉取；每次解析留痕（谁取的、哪个版本） */
    @GetMapping("/{name}/resolve")
    @RequiresPermission(Permissions.PROMPT_QUERY)
    public ApiResult<Map<String, Object>> resolve(@PathVariable String name,
                                                  @RequestParam(defaultValue = LABEL_PRODUCTION) String label,
                                                  @RequestParam(required = false) String source) {
        String ws = CurrentContext.require().getWorkspaceId();
        PromptEntity hit = promptService.resolve(ws, name, label);
        promptService.recordResolution(hit, label, source == null || source.isBlank()
                ? io.emonlens.server.engine.prompt.PromptService.SOURCE_UI : source,
                CurrentContext.require().getUsername());
        return ApiResult.ok(toVO(hit));
    }

    /**
     * 从 prompt 版本反查评估证据（R1-3）：它被哪些 run 评过、Gate 结论是什么。
     * 只返回元数据与结论，不含候选正文。
     */
    @GetMapping("/{name}/runs")
    @RequiresPermission(Permissions.PROMPT_QUERY)
    public ApiResult<List<Map<String, Object>>> runs(@PathVariable String name) {
        String ws = CurrentContext.require().getWorkspaceId();
        List<io.emonlens.server.storage.entity.DatasetRunEntity> runs = datasetRunMapper.selectList(
                Wrappers.<io.emonlens.server.storage.entity.DatasetRunEntity>lambdaQuery()
                        .eq(io.emonlens.server.storage.entity.DatasetRunEntity::getWorkspaceId, ws)
                        .eq(io.emonlens.server.storage.entity.DatasetRunEntity::getPromptName, name)
                        .orderByDesc(io.emonlens.server.storage.entity.DatasetRunEntity::getStartedAt));
        Map<String, io.emonlens.server.storage.entity.QualityGateEntity> latestGate = new HashMap<>();
        for (io.emonlens.server.storage.entity.DatasetRunEntity run : runs) {
            io.emonlens.server.storage.entity.QualityGateEntity gate = qualityGateMapper.selectOne(
                    Wrappers.<io.emonlens.server.storage.entity.QualityGateEntity>lambdaQuery()
                            .eq(io.emonlens.server.storage.entity.QualityGateEntity::getRunId, run.getId())
                            .orderByDesc(io.emonlens.server.storage.entity.QualityGateEntity::getCreatedAt)
                            .orderByDesc(io.emonlens.server.storage.entity.QualityGateEntity::getId)
                            .last("LIMIT 1"));
            if (gate != null) {
                latestGate.put(run.getId(), gate);
            }
        }
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        for (io.emonlens.server.storage.entity.DatasetRunEntity run : runs) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("runId", run.getId());
            row.put("datasetId", run.getDatasetId());
            row.put("promptName", run.getPromptName());
            row.put("promptVersion", run.getPromptVersion());
            row.put("promptLabel", run.getPromptLabel());
            row.put("promptHash", run.getPromptHash());
            row.put("runMode", run.getRunMode());
            row.put("runLabel", run.getRunLabel());
            row.put("commitSha", run.getCommitSha());
            row.put("status", run.getStatus());
            row.put("passCount", run.getPassCount());
            row.put("failCount", run.getFailCount());
            row.put("startedAt", run.getStartedAt());
            row.put("finishedAt", run.getFinishedAt());
            io.emonlens.server.storage.entity.QualityGateEntity gate = latestGate.get(run.getId());
            row.put("gateDecision", gate == null ? null : gate.getDecision());
            row.put("gateId", gate == null ? null : gate.getId());
            row.put("gateValidUntil", gate == null ? null : gate.getValidUntil());
            row.put("gateCoverage", gate == null ? null : gate.getCoverage());
            rows.add(row);
        }
        return ApiResult.ok(rows);
    }

    /** 创建新版本：version 自动 +1；可同时打 production 标签（实现发布/回滚） */
    @PostMapping
    @RequiresPermission(Permissions.PROMPT_MANAGE)
    @Audit(action = "prompt.create", resourceType = "prompt")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateRequest request) {
        String ws = CurrentContext.require().getWorkspaceId();
        if (request.getName() == null || request.getName().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "name is required");
        }
        if (request.getPrompt() == null || request.getPrompt().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "prompt is required");
        }
        Integer nextVersion = promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                        .eq(PromptEntity::getWorkspaceId, ws)
                        .eq(PromptEntity::getName, request.getName())
                        .orderByDesc(PromptEntity::getVersion)
                        .last("LIMIT 1"))
                .stream().findFirst().map(v -> v.getVersion() + 1).orElse(1);
        PromptEntity entity = new PromptEntity();
        entity.setWorkspaceId(ws);
        entity.setName(request.getName().trim());
        entity.setVersion(nextVersion);
        entity.setPrompt(request.getPrompt());
        entity.setConfigJson(toJson(request.getConfig()));
        List<String> labels = request.getLabels() == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(request.getLabels());
        if (Boolean.TRUE.equals(request.getSetProduction()) && !labels.contains(LABEL_PRODUCTION)) {
            labels.add(LABEL_PRODUCTION);
        }
        // label 语义收紧（R1-3）：production 同名唯一。先插入新版本再从旧版本摘除，
        // 避免插入失败时把旧版本上的 production 一起弄丢。
        entity.setLabels(String.join(",", labels));
        entity.setCreatedBy(CurrentContext.require().getUsername());
        entity.setCreatedAt(LocalDateTime.now());
        promptMapper.insert(entity);
        if (labels.contains(LABEL_PRODUCTION)) {
            detachLabel(ws, entity.getName(), LABEL_PRODUCTION, nextVersion);
        }
        return ApiResult.ok(toVO(entity));
    }

    /** 把标签指到指定版本（发布 / 回滚）；同标签从其它版本摘除 */
    @PostMapping("/{name}/label")
    @RequiresPermission(Permissions.PROMPT_MANAGE)
    @Audit(action = "prompt.label", resourceType = "prompt")
    public ApiResult<Map<String, Object>> setLabel(@PathVariable String name, @RequestBody LabelRequest request) {
        String ws = CurrentContext.require().getWorkspaceId();
        List<PromptEntity> versions = promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                .eq(PromptEntity::getWorkspaceId, ws)
                .eq(PromptEntity::getName, name));
        PromptEntity target = versions.stream()
                .filter(v -> v.getVersion().equals(request.getVersion()))
                .findFirst()
                .orElseThrow(() -> new EmonLensException(ErrorCode.NOT_FOUND, "version not found: " + request.getVersion()));
        for (PromptEntity v : versions) {
            List<String> labels = new java.util.ArrayList<>(labelsOf(v));
            if (v.getId().equals(target.getId())) {
                if (!labels.contains(request.getLabel())) {
                    labels.add(request.getLabel());
                }
            } else {
                labels.remove(request.getLabel());
            }
            v.setLabels(String.join(",", labels));
            promptMapper.updateById(v);
        }
        return ApiResult.ok(toVO(target));
    }

    /** 把某个 label 从同名其它版本上摘除，保证 label 指向唯一（production 语义收紧）。 */
    private void detachLabel(String ws, String name, String label, Integer keepVersion) {
        for (PromptEntity v : promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                .eq(PromptEntity::getWorkspaceId, ws)
                .eq(PromptEntity::getName, name))) {
            if (v.getVersion().equals(keepVersion)) {
                continue;
            }
            List<String> labels = new java.util.ArrayList<>(labelsOf(v));
            if (labels.remove(label)) {
                v.setLabels(String.join(",", labels));
                promptMapper.updateById(v);
            }
        }
    }

    private Integer maxVersion(String ws, String name) {        return promptMapper.selectList(Wrappers.<PromptEntity>lambdaQuery()
                        .eq(PromptEntity::getWorkspaceId, ws)
                        .eq(PromptEntity::getName, name)
                        .orderByDesc(PromptEntity::getVersion)
                        .last("LIMIT 1"))
                .stream().findFirst().map(PromptEntity::getVersion).orElse(0);
    }

    private List<String> labelsOf(PromptEntity entity) {
        if (entity.getLabels() == null || entity.getLabels().isBlank()) {
            return List.of();
        }
        return Arrays.stream(entity.getLabels().split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(java.util.stream.Collectors.toList());
    }

    private String toJson(Map<String, Object> config) {
        if (config == null) {
            return "{}";
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(config);
        } catch (Exception e) {
            return "{}";
        }
    }

    private Map<String, Object> toVO(PromptEntity entity) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", entity.getId());
        m.put("name", entity.getName());
        m.put("version", entity.getVersion());
        m.put("prompt", entity.getPrompt());
        m.put("labels", labelsOf(entity));
        m.put("createdBy", entity.getCreatedBy());
        m.put("createdAt", entity.getCreatedAt());
        try {
            m.put("config", new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    entity.getConfigJson() == null ? "{}" : entity.getConfigJson(), Map.class));
        } catch (Exception e) {
            m.put("config", Map.of());
        }
        return m;
    }
}
