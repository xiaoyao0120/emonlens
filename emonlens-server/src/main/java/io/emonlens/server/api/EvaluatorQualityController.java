package io.emonlens.server.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.eval.EvaluatorQualityService;

import lombok.RequiredArgsConstructor;

/**
 * 评估器质量看板（R2-3）：
 * 一份报告同时回答「哪个评估器最不可信」与「哪个数据集在腐烂」，并可手动触发告警扫描。
 */
@RestController
@RequestMapping("/api/v1/evaluator-quality")
@RequiredArgsConstructor
public class EvaluatorQualityController {

    private final EvaluatorQualityService qualityService;

    @GetMapping
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> report(
            @RequestParam(defaultValue = "168") int windowHours,
            @RequestParam(defaultValue = "90") int staleDays) {
        return ApiResult.ok(qualityService.report(
                CurrentContext.require().getWorkspaceId(), windowHours, staleDays));
    }

    /** 手动触发一次扫描：把当前低可信评估器与腐烂数据集写成 Finding（24h 内同一对象只报一次）。 */
    @PostMapping("/scan")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "evaluator.quality.scan", resourceType = "workspace")
    public ApiResult<Map<String, Object>> scan() {
        int created = qualityService.scanAndRecord(CurrentContext.require().getWorkspaceId());
        return ApiResult.ok(Map.of("findingsCreated", created));
    }
}
