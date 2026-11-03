package io.emonlens.server.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.eval.DatasetRunService;
import io.emonlens.server.engine.security.SecurityEvidenceService;
import io.emonlens.server.engine.security.SecurityPackRegistry;

import lombok.RequiredArgsConstructor;

/**
 * 安全评测包（R3-2）：包清单 / 种子 / 导入数据集 / run 的 security 证据。
 *
 * <p>合规边界：种子只用于评测自家 Agent 的防御能力，平台不提供对外攻击执行能力。</p>
 */
@RestController
@RequiredArgsConstructor
public class SecurityPackController {

    private final SecurityPackRegistry registry;
    private final SecurityEvidenceService evidenceService;
    private final DatasetRunService datasetRunService;

    /** 包清单：类别、OWASP 映射、检测口径、可接受行为与种子数量。 */
    @GetMapping("/api/v1/security/pack")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> pack() {
        return ApiResult.ok(registry.manifest());
    }

    /** 种子正文：导入数据集前预览（可选按类别过滤）。 */
    @GetMapping("/api/v1/security/pack/seeds")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> seeds(
            @RequestParam(required = false) List<String> categories) {
        return ApiResult.ok(registry.seeds(categories));
    }

    /** 把选中类别的种子写成数据集样本（按 input 去重，重复导入幂等）。 */
    @PostMapping("/api/v1/datasets/{datasetId}/security-seeds")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "security.seeds.import", resourceType = "dataset")
    public ApiResult<Map<String, Object>> importSeeds(@PathVariable String datasetId,
                                                      @RequestBody Map<String, Object> body) {
        List<String> categories = new java.util.ArrayList<>();
        Object raw = body == null ? null : body.get("categories");
        if (raw instanceof List) {
            for (Object item : (List<?>) raw) {
                if (item != null) {
                    categories.add(String.valueOf(item));
                }
            }
        }
        return ApiResult.ok(evidenceService.importSeeds(CurrentContext.require().getWorkspaceId(),
                datasetId, categories, CurrentContext.require().getUsername()));
    }

    /** run 的 security 证据：按类别与 OWASP 类目汇总，含红线结论与功能口径。 */
    @GetMapping("/api/v1/datasets/{datasetId}/runs/{runId}/security")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> evidence(@PathVariable String datasetId,
                                                   @PathVariable String runId) {
        var run = datasetRunService.requireRun(CurrentContext.require().getWorkspaceId(),
                datasetId, runId);
        return ApiResult.ok(evidenceService.evidence(run));
    }
}
