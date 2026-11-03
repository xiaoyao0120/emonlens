package io.emonlens.server.api;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
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
import io.emonlens.server.engine.eval.EvaluationRewindService;
import io.emonlens.server.engine.eval.EvaluationRuleService;

import lombok.RequiredArgsConstructor;

/**
 * 评估规则 v2 API（R2-1）：规则 CRUD、按 scope/规则汇总、Rewind 作业管理。
 *
 * <p>规则是「在线评估的准入配置」：改写规则会影响之后的自动评估，不回溯改已产出的结论
 * （回溯要走 Rewind，且同样幂等）。</p>
 */
@RestController
@RequestMapping("/api/v1/eval-rules")
@RequiredArgsConstructor
public class EvaluationRuleController {

    private final EvaluationRuleService ruleService;
    private final EvaluationRewindService rewindService;

    @GetMapping
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> list() {
        return ApiResult.ok(ruleService.list(CurrentContext.require().getWorkspaceId()));
    }

    /** 新建或更新一条规则（同 ruleKey 覆盖）；不传的字段保持默认值。 */
    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.rule.upsert", resourceType = "eval_rule")
    public ApiResult<Map<String, Object>> upsert(@RequestBody EvaluationRuleService.RuleRequest request) {
        return ApiResult.ok(ruleService.upsert(CurrentContext.require().getWorkspaceId(), request,
                CurrentContext.require().getUsername()));
    }

    @GetMapping("/{ruleKey}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> get(@PathVariable String ruleKey) {
        return ApiResult.ok(ruleService.get(CurrentContext.require().getWorkspaceId(), ruleKey));
    }

    @PostMapping("/{ruleKey}/enable")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.rule.enable", resourceType = "eval_rule")
    public ApiResult<Map<String, Object>> enable(@PathVariable String ruleKey) {
        return ApiResult.ok(ruleService.setEnabled(
                CurrentContext.require().getWorkspaceId(), ruleKey, true));
    }

    @PostMapping("/{ruleKey}/disable")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.rule.disable", resourceType = "eval_rule")
    public ApiResult<Map<String, Object>> disable(@PathVariable String ruleKey) {
        return ApiResult.ok(ruleService.setEnabled(
                CurrentContext.require().getWorkspaceId(), ruleKey, false));
    }

    /** 只删规则定义；已产出的评估结果与命中账本保留（审计需要）。 */
    @DeleteMapping("/{ruleKey}")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.rule.delete", resourceType = "eval_rule")
    public ApiResult<Map<String, Object>> delete(@PathVariable String ruleKey) {
        ruleService.delete(CurrentContext.require().getWorkspaceId(), ruleKey);
        return ApiResult.ok(Map.of("ruleKey", ruleKey, "deleted", true));
    }

    /** 按 scope（TRACE / SESSION）与规则汇总最近窗口的结论；SESSION 有独立汇总口径。 */
    @GetMapping("/summary")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> summary(@RequestParam(required = false) String ruleKey,
                                                  @RequestParam(defaultValue = "24") int windowHours) {
        return ApiResult.ok(ruleService.summary(CurrentContext.require().getWorkspaceId(),
                ruleKey, windowHours));
    }

    /** Rewind：按 filter 圈选历史 trace 补评；同 Idempotency-Key 返回同一作业。 */
    @PostMapping("/{ruleKey}/rewinds")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.rule.rewind.create", resourceType = "eval_rule")
    public ApiResult<Map<String, Object>> createRewind(
            @PathVariable String ruleKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody(required = false) EvaluationRuleService.RewindRequest request) {
        EvaluationRuleService.RewindRequest body = request == null
                ? new EvaluationRuleService.RewindRequest() : request;
        body.setRuleKey(ruleKey);
        return ApiResult.ok(rewindService.create(CurrentContext.require().getWorkspaceId(),
                idempotencyKey, body, CurrentContext.require().getUsername()));
    }

    @GetMapping("/rewinds")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<List<Map<String, Object>>> rewinds() {
        return ApiResult.ok(rewindService.list(CurrentContext.require().getWorkspaceId()));
    }

    @GetMapping("/rewinds/{jobId}")
    @RequiresPermission(Permissions.EVALUATION_QUERY)
    public ApiResult<Map<String, Object>> rewind(@PathVariable String jobId) {
        return ApiResult.ok(rewindService.get(CurrentContext.require().getWorkspaceId(), jobId));
    }

    @PostMapping("/rewinds/{jobId}/pause")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.rule.rewind.pause", resourceType = "eval_rule")
    public ApiResult<Map<String, Object>> pauseRewind(@PathVariable String jobId) {
        return ApiResult.ok(rewindService.pause(CurrentContext.require().getWorkspaceId(), jobId));
    }

    @PostMapping("/rewinds/{jobId}/resume")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.rule.rewind.resume", resourceType = "eval_rule")
    public ApiResult<Map<String, Object>> resumeRewind(@PathVariable String jobId) {
        return ApiResult.ok(rewindService.resume(CurrentContext.require().getWorkspaceId(), jobId));
    }

    @PostMapping("/rewinds/{jobId}/cancel")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "eval.rule.rewind.cancel", resourceType = "eval_rule")
    public ApiResult<Map<String, Object>> cancelRewind(@PathVariable String jobId) {
        return ApiResult.ok(rewindService.cancel(CurrentContext.require().getWorkspaceId(), jobId));
    }
}
