package io.emonlens.server.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.engine.feedback.EndUserFeedbackService;

import lombok.RequiredArgsConstructor;

/**
 * 端用户反馈的控制面（R2-2）：签发/吊销 token、查看反馈、把 correction 收录为样本。
 * 与公开入口分离：这些动作需要登录与相应权限，且会写审计。
 */
@RestController
@RequestMapping("/api/v1/traces/{traceId}")
@RequiredArgsConstructor
public class TraceFeedbackController {

    private final EndUserFeedbackService feedbackService;

    /** 签发反馈 token：明文只在这次响应里出现，服务端只存 sha256。 */
    @PostMapping("/feedback-tokens")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "feedback.token.issue", resourceType = "trace")
    public ApiResult<Map<String, Object>> issue(
            @PathVariable String traceId,
            @RequestBody(required = false) EndUserFeedbackService.IssueRequest request) {
        return ApiResult.ok(feedbackService.issue(
                CurrentContext.require().getWorkspaceId(), traceId, request,
                CurrentContext.require().getUsername()));
    }

    @PostMapping("/feedback-tokens/{tokenId}/revoke")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "feedback.token.revoke", resourceType = "trace")
    public ApiResult<Map<String, Object>> revoke(@PathVariable String traceId,
                                                 @PathVariable String tokenId) {
        return ApiResult.ok(feedbackService.revoke(
                CurrentContext.require().getWorkspaceId(), traceId, tokenId));
    }

    @GetMapping("/feedback")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> list(@PathVariable String traceId) {
        return ApiResult.ok(feedbackService.list(
                CurrentContext.require().getWorkspaceId(), traceId));
    }

    /** 把带 correction 的反馈收录为数据集样本（走样本契约校验）。 */
    @PostMapping("/feedback/{feedbackId}/dataset")
    @RequiresPermission(Permissions.DATASET_MANAGE)
    @Audit(action = "feedback.promote", resourceType = "trace")
    public ApiResult<Map<String, Object>> promote(@PathVariable String traceId,
                                                  @PathVariable String feedbackId,
                                                  @RequestBody Map<String, Object> body) {
        Object datasetId = body == null ? null : body.get("datasetId");
        return ApiResult.ok(feedbackService.promote(
                CurrentContext.require().getWorkspaceId(), traceId, feedbackId,
                datasetId == null ? null : String.valueOf(datasetId),
                CurrentContext.require().getUsername()));
    }
}
