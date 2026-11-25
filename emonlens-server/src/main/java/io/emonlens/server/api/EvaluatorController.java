package io.emonlens.server.api;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.validation.Valid;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.common.ApiResult;
import io.emonlens.server.engine.eval.EvalResultVO;
import io.emonlens.server.engine.eval.EvaluationRunService;
import io.emonlens.server.engine.eval.EvaluatorService;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.EvaluatorVersionEntity;
import io.emonlens.server.storage.mapper.EvalResultMapper;

import lombok.RequiredArgsConstructor;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class EvaluatorController {

    private static final String DEFAULT_WORKSPACE = "ws-default";
    private static final String DEFAULT_PROJECT = "p-2001";

    private final EvaluatorService evaluatorService;
    private final EvaluationRunService evaluationRunService;
    private final EvalResultMapper evalResultMapper;
    private final io.emonlens.server.engine.eval.JudgePresetRegistry judgePresetRegistry;

    @GetMapping("/evaluators/presets")
    @RequiresPermission(Permissions.EVALUATOR_QUERY)
    public ApiResult<List<Map<String, Object>>> presets() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (io.emonlens.server.engine.eval.JudgePresetRegistry.Preset preset
                : judgePresetRegistry.list()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", preset.getKey());
            row.put("judgeMode", preset.getJudgeMode());
            row.put("templateKey", preset.getTemplateKey());
            row.put("requiredFields", preset.getRequiredFields());
            row.put("instruction", preset.getInstruction());
            rows.add(row);
        }
        return ApiResult.ok(rows);
    }

    @PostMapping("/evaluators")
    @RequiresPermission(Permissions.EVALUATOR_MANAGE)
    @Audit(action = "evaluator.create", resourceType = "evaluator")
    public ApiResult<Map<String, Object>> create(@Valid @RequestBody EvaluatorCreateRequest request) {
        EvaluatorEntity entity = evaluatorService.create(CurrentContext.require().getWorkspaceId(),
                request.getEvaluatorKey(), request.getType(), request.getSpec());
        return ApiResult.ok(entityToMap(entity));
    }

    @GetMapping("/evaluators")
    @RequiresPermission(Permissions.EVALUATOR_QUERY)
    public ApiResult<PageVO<Map<String, Object>>> page(@RequestParam(defaultValue = "1") int pageNumber,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        Page<EvaluatorEntity> page = evaluatorService.page(CurrentContext.require().getWorkspaceId(),
                pageNumber, pageSize);
        return ApiResult.ok(PageVO.from(page, page.getRecords().stream()
                .map(this::entityToMap).collect(Collectors.toList())));
    }

    @GetMapping("/evaluators/{id}")
    @RequiresPermission(Permissions.EVALUATOR_QUERY)
    public ApiResult<Map<String, Object>> get(@PathVariable String id) {
        return ApiResult.ok(entityToMap(evaluatorService.getById(id)));
    }

    /** 不可变版本历史；旧 run 可通过版本号/fingerprint 追溯当时的 spec。 */
    @GetMapping("/evaluators/{id}/versions")
    @RequiresPermission(Permissions.EVALUATOR_QUERY)
    public ApiResult<List<Map<String, Object>>> versions(@PathVariable String id) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (EvaluatorVersionEntity v : evaluatorService.versions(id)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", v.getId());
            m.put("version", v.getVersion());
            m.put("type", v.getType());
            m.put("fingerprint", v.getFingerprint());
            m.put("templateKey", v.getTemplateKey());
            m.put("templateVersion", v.getTemplateVersion());
            m.put("templateHash", v.getTemplateHash());
            m.put("spec", evaluatorService.fromJson(v.getSpecJson()));
            m.put("createdAt", v.getCreatedAt());
            rows.add(m);
        }
        return ApiResult.ok(rows);
    }

    @PutMapping("/evaluators/{id}")
    @RequiresPermission(Permissions.EVALUATOR_MANAGE)
    @Audit(action = "evaluator.update", resourceType = "evaluator")
    public ApiResult<Map<String, Object>> update(@PathVariable String id,
                                                 @RequestBody EvaluatorUpdateRequest request) {
        return ApiResult.ok(entityToMap(evaluatorService.update(id, request.getSpec())));
    }

    @PostMapping("/evaluators/{id}/enable")
    @RequiresPermission(Permissions.EVALUATOR_MANAGE)
    @Audit(action = "evaluator.enable", resourceType = "evaluator")
    public ApiResult<Map<String, Object>> enable(@PathVariable String id) {
        return ApiResult.ok(entityToMap(evaluatorService.setEnabled(id, true)));
    }

    @PostMapping("/evaluators/{id}/disable")
    @RequiresPermission(Permissions.EVALUATOR_MANAGE)
    @Audit(action = "evaluator.disable", resourceType = "evaluator")
    public ApiResult<Map<String, Object>> disable(@PathVariable String id) {
        return ApiResult.ok(entityToMap(evaluatorService.setEnabled(id, false)));
    }

    /**
     * 删除评估器定义。此前只能启用/停用，试错阶段的定义会永久留在列表里。
     * 级联清理该评估器的评估结果：否则 et_eval_result 会残留 evaluatorKey
     * 指向已不存在定义的孤儿记录（与注解队列、数据集的删除策略一致）。
     */
    @DeleteMapping("/evaluators/{id}")
    @RequiresPermission(Permissions.EVALUATOR_MANAGE)
    @Audit(action = "evaluator.delete", resourceType = "evaluator")
    public ApiResult<Void> delete(@PathVariable String id) {
        requireOwned(id);
        evalResultMapper.delete(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getEvaluatorId, id));
        evaluatorService.remove(id);
        return ApiResult.ok(null);
    }

    /** 行级隔离：实体不存在或不属于当前工作空间时统一按 NOT_FOUND 处理（避免泄露存在性）。 */
    private EvaluatorEntity requireOwned(String id) {
        EvaluatorEntity entity = evaluatorService.getById(id);
        if (entity == null || !CurrentContext.require().getWorkspaceId().equals(entity.getWorkspaceId())) {
            throw new io.emonlens.common.EmonLensException(io.emonlens.common.ErrorCode.NOT_FOUND,
                    "evaluator not found");
        }
        return entity;
    }

    @PostMapping("/evaluators/{id}/validate")
    @RequiresPermission(Permissions.EVALUATOR_MANAGE)
    public ApiResult<List<String>> validate(@PathVariable String id) {
        EvaluatorEntity entity = evaluatorService.getById(id);
        return ApiResult.ok(evaluatorService.validateSpec(entity.getWorkspaceId(),
                entity.getType(), evaluatorService.fromJson(entity.getSpecJson())));
    }

    /** 试跑：对指定 trace 执行一次评估，不落库不采样。
     *  需要 trace:content：试跑会把 span 内容发给 LLM——仅 evaluator:query 会成为无内容权限者提取内容的通道 */
    @PostMapping("/evaluators/{id}/test")
    @RequiresPermission(Permissions.TRACE_CONTENT)
    public ApiResult<EvalResultVO> test(@PathVariable String id, @RequestBody EvalTestRequest request) {
        EvaluatorEntity entity = evaluatorService.getById(id);
        return ApiResult.ok(EvalResultVO.from(
                evaluationRunService.evaluate(entity, request.getTraceId(), 1, false)));
    }

    /** 手动批量评估：对指定 trace 集合逐个执行并落库（绕过采样） */
    @PostMapping("/evaluations/run")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "evaluation.run", resourceType = "evaluation")
    public ApiResult<List<EvalResultVO>> run(@RequestBody EvalRunRequest request) {
        List<EvalResultVO> results = new java.util.ArrayList<>();
        for (String traceId : request.getTraceIds()) {
            results.add(EvalResultVO.from(evaluationRunService.evaluate(
                    evaluatorService.getById(request.getEvaluatorId()), traceId, 1, true)));
        }
        return ApiResult.ok(results);
    }

    @GetMapping("/eval-results/overview")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> evalOverview() {
        List<EvalResultEntity> all = evalResultMapper.selectList(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .orderByDesc(EvalResultEntity::getCreatedAt));
        Map<String, List<EvalResultEntity>> byEvaluator = all.stream()
                .collect(Collectors.groupingBy(r -> r.getEvaluatorKey() == null ? "unknown" : r.getEvaluatorKey(),
                        LinkedHashMap::new, Collectors.toList()));
        LocalDateTime weekAgo = LocalDateTime.now().minusDays(7);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, List<EvalResultEntity>> e : byEvaluator.entrySet()) {
            List<EvalResultEntity> rs = e.getValue();
            long pass = rs.stream().filter(r -> "PASS".equals(r.getLabel())).count();
            long fail = rs.stream().filter(r -> "FAIL".equals(r.getLabel())).count();
            long judged = pass + fail;
            long recent7d = rs.stream()
                    .filter(r -> r.getCreatedAt() != null && !r.getCreatedAt().isBefore(weekAgo))
                    .count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("evaluatorKey", e.getKey());
            m.put("total", rs.size());
            m.put("recent7d", recent7d);
            m.put("pass", pass);
            m.put("fail", fail);
            m.put("passRate", judged == 0 ? null : Math.round(pass * 1000.0 / judged) / 10.0);
            m.put("lastEvaluatedAt", rs.get(0).getCreatedAt());
            m.put("agents", rs.stream().map(EvalResultEntity::getAgentKey).filter(a -> a != null)
                    .distinct().sorted().collect(Collectors.toList()));
            rows.add(m);
        }
        rows.sort((a, b) -> Long.compare(((Number) b.get("total")).longValue(),
                ((Number) a.get("total")).longValue()));
        return ApiResult.ok(rows);
    }

    @GetMapping("/eval-results")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<PageVO<EvalResultVO>> evalResults(@RequestParam(required = false) String evaluatorKey,
                                                       @RequestParam(required = false) String agentKey,
                                                       @RequestParam(required = false) String traceId,
                                                       @RequestParam(required = false) String label,
                                                       @RequestParam(defaultValue = "1") int pageNumber,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        Page<EvalResultEntity> page = evalResultMapper.selectPage(PageVO.safePage(pageNumber, pageSize),
                Wrappers.<EvalResultEntity>lambdaQuery()
                        .eq(EvalResultEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                        .eq(evaluatorKey != null && !evaluatorKey.isEmpty(),
                                EvalResultEntity::getEvaluatorKey, evaluatorKey)
                        .eq(agentKey != null && !agentKey.isEmpty(), EvalResultEntity::getAgentKey, agentKey)
                        .eq(traceId != null && !traceId.isEmpty(), EvalResultEntity::getTraceId, traceId)
                        .eq(label != null && !label.isEmpty(), EvalResultEntity::getLabel, label)
                        .orderByDesc(EvalResultEntity::getCreatedAt));
        return ApiResult.ok(PageVO.from(page, page.getRecords().stream()
                .map(EvalResultVO::from).collect(Collectors.toList())));
    }

    /** 评估结果全量统计（通过率 KPI / 近 14 天趋势）：不再由“最新 500 条”近似。 */
    @GetMapping("/eval-results/stats")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> evalStats(@RequestParam(required = false) String evaluatorKey,
                                                    @RequestParam(required = false) String agentKey,
                                                    @RequestParam(required = false) String traceId) {
        String ws = CurrentContext.require().getWorkspaceId();
        QueryWrapper<EvalResultEntity> totalsQuery = evalFilter(ws, evaluatorKey, agentKey, traceId);
        totalsQuery.select("label", "COUNT(*) AS cnt").groupBy("label");
        long total = 0, pass = 0, fail = 0, other = 0;
        for (Map<String, Object> row : evalResultMapper.selectMaps(totalsQuery)) {
            Object labelRaw = mapValue(row, "label");
            String label = labelRaw == null ? "" : String.valueOf(labelRaw);
            long count = mapCount(row, "cnt");
            total += count;
            if ("PASS".equals(label)) {
                pass += count;
            } else if ("FAIL".equals(label)) {
                fail += count;
            } else {
                other += count;
            }
        }

        QueryWrapper<EvalResultEntity> dailyQuery = evalFilter(ws, evaluatorKey, agentKey, traceId);
        dailyQuery.select("CAST(created_at AS DATE) AS stat_day", "label", "COUNT(*) AS cnt")
                .ge("created_at", LocalDateTime.now().minusDays(14))
                .groupBy("CAST(created_at AS DATE)", "label")
                .orderByAsc("CAST(created_at AS DATE)");
        Map<String, Map<String, Long>> byDay = new java.util.TreeMap<>();
        for (Map<String, Object> row : evalResultMapper.selectMaps(dailyQuery)) {
            Object dayRaw = mapValue(row, "stat_day");
            Object labelRaw = mapValue(row, "label");
            if (dayRaw == null) {
                continue;
            }
            String day = String.valueOf(dayRaw).substring(0, Math.min(10, String.valueOf(dayRaw).length()));
            byDay.computeIfAbsent(day, k -> new LinkedHashMap<>())
                    .merge(labelRaw == null ? "" : String.valueOf(labelRaw), mapCount(row, "cnt"), Long::sum);
        }
        List<Map<String, Object>> daily = new ArrayList<>();
        for (Map.Entry<String, Map<String, Long>> entry : byDay.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("day", entry.getKey());
            row.put("pass", entry.getValue().getOrDefault("PASS", 0L));
            row.put("fail", entry.getValue().getOrDefault("FAIL", 0L));
            daily.add(row);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", total);
        result.put("pass", pass);
        result.put("fail", fail);
        result.put("other", other);
        result.put("daily", daily);
        return ApiResult.ok(result);
    }

    private QueryWrapper<EvalResultEntity> evalFilter(String workspaceId, String evaluatorKey,
                                                      String agentKey, String traceId) {
        QueryWrapper<EvalResultEntity> query = new QueryWrapper<>();
        query.eq("workspace_id", workspaceId);
        if (evaluatorKey != null && !evaluatorKey.isEmpty()) {
            query.eq("evaluator_key", evaluatorKey);
        }
        if (agentKey != null && !agentKey.isEmpty()) {
            query.eq("agent_key", agentKey);
        }
        if (traceId != null && !traceId.isEmpty()) {
            query.eq("trace_id", traceId);
        }
        return query;
    }

    private static Object mapValue(Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static long mapCount(Map<String, Object> row, String key) {
        Object value = mapValue(row, key);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private Map<String, Object> entityToMap(EvaluatorEntity entity) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", entity.getId());
        map.put("workspaceId", entity.getWorkspaceId());
        map.put("evaluatorKey", entity.getEvaluatorKey());
        map.put("type", entity.getType());
        map.put("spec", evaluatorService.fromJson(entity.getSpecJson()));
        map.put("enabled", entity.getEnabled());
        map.put("version", entity.getVersion());
        // 列表按 created_at 倒序，却一直没把这个字段返回给前端（新建的评估器看不出创建时间）
        map.put("createdAt", entity.getCreatedAt());
        map.put("updatedAt", entity.getUpdatedAt());
        return map;
    }

    @lombok.Data
    public static class EvaluatorCreateRequest {
        private String workspaceId = DEFAULT_WORKSPACE;
        private String evaluatorKey;
        private String type;
        private Map<String, Object> spec;
    }

    @lombok.Data
    public static class EvaluatorUpdateRequest {
        private Map<String, Object> spec;
    }

    @lombok.Data
    public static class EvalTestRequest {
        private String traceId;
    }

    @lombok.Data
    public static class EvalRunRequest {
        private String evaluatorId;
        private List<String> traceIds;
    }
}
