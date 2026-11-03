package io.emonlens.server.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.playground.PlaygroundMatrixService;

import lombok.RequiredArgsConstructor;

/**
 * Playground 矩阵实验 API（R1-2）：矩阵 = 数据集样本 × 列（prompt 版本 × 模型 × 参数）。
 *
 * <p>矩阵是试跑产物（不进 Gate）；Diff 给出逐样本确定性变化与语义偏好；
 * 升格接口把某一列变成正式 dataset run，才进入准入证据链路。</p>
 */
@RestController
@RequestMapping("/api/v1/playground/matrices")
@RequiredArgsConstructor
public class PlaygroundMatrixController {

    private final PlaygroundMatrixService matrixService;

    /** 启动前成本预估（只算不落库）：先看账单再决定要不要跑。 */
    @PostMapping("/estimate")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    public ApiResult<Map<String, Object>> estimate(
            @RequestBody PlaygroundMatrixService.CreateRequest request) {
        return ApiResult.ok(matrixService.estimate(
                CurrentContext.require().getWorkspaceId(), request));
    }

    /** 受理矩阵：要求 Idempotency-Key 与 confirmEstimate=true。 */
    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "playground.matrix.create", resourceType = "playground")
    public ResponseEntity<ApiResult<Map<String, Object>>> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody PlaygroundMatrixService.CreateRequest request) {
        Map<String, Object> payload = matrixService.create(
                CurrentContext.require().getWorkspaceId(), idempotencyKey, request,
                CurrentContext.require().getUsername());
        boolean created = "PENDING".equals(payload.get("status"));
        return ResponseEntity.status(created ? HttpStatus.ACCEPTED : HttpStatus.OK)
                .body(ApiResult.ok(payload));
    }

    @GetMapping
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<java.util.List<Map<String, Object>>> list(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResult.ok(matrixService.list(CurrentContext.require().getWorkspaceId(), limit));
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        return ApiResult.ok(matrixService.detail(CurrentContext.require().getWorkspaceId(), id));
    }

    /** 网格 CSV 导出：一行一个样本，列按列定义展开。 */
    @GetMapping("/{id}/export.csv")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ResponseEntity<byte[]> export(@PathVariable String id) {
        String csv = matrixService.exportCsv(CurrentContext.require().getWorkspaceId(), id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"emonlens-matrix-" + id + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** 列对比：一列为 base，其余列与之逐样本比较（确定性变化 + 语义偏好）。 */
    @PostMapping("/{id}/diff")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "playground.matrix.diff", resourceType = "playground")
    public ApiResult<Map<String, Object>> diff(@PathVariable String id,
                                               @RequestBody PlaygroundMatrixService.DiffRequest request) {
        return ApiResult.ok(matrixService.diff(CurrentContext.require().getWorkspaceId(), id,
                request, CurrentContext.require().getUsername()));
    }

    @GetMapping("/{id}/diffs/{diffId}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> diffDetail(@PathVariable String id,
                                                     @PathVariable String diffId) {
        return ApiResult.ok(matrixService.diffDetail(
                CurrentContext.require().getWorkspaceId(), id, diffId));
    }

    /**
     * 把某一列升格为正式 dataset run（R1-3）：幂等键由 matrix + column 推导，
     * 重复点击返回同一条 run，不会重复计费。
     */
    @PostMapping("/{id}/columns/{columnKey}/promote")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "playground.matrix.promote", resourceType = "playground")
    public ApiResult<Map<String, Object>> promote(@PathVariable String id,
                                                  @PathVariable String columnKey,
                                                  @RequestBody(required = false)
                                                  PlaygroundMatrixService.PromoteRequest request) {
        return ApiResult.ok(matrixService.promote(CurrentContext.require().getWorkspaceId(), id,
                columnKey, request == null ? new PlaygroundMatrixService.PromoteRequest() : request,
                CurrentContext.require().getUsername()));
    }
}
