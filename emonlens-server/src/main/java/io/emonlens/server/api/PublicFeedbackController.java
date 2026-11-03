package io.emonlens.server.api;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.emonlens.common.ApiResult;
import io.emonlens.server.engine.feedback.EndUserFeedbackService;

import lombok.RequiredArgsConstructor;

/**
 * 端用户反馈的公开入口（R2-2）：只认 path 里的预签 token，不需要登录。
 *
 * <p>这个 token 只授予「给指定 trace 写一条反馈」的能力：读不到 trace 正文、
 * 拿不到工作空间信息、也不能调用任何其它接口。平台内部在 WebSecurityConfig 里
 * 把 /api/v1/feedback/** 从 JWT 拦截链里豁免，其余控制面接口仍然需要登录。</p>
 */
@RestController
@RequestMapping("/api/v1/feedback")
@RequiredArgsConstructor
public class PublicFeedbackController {

    private final EndUserFeedbackService feedbackService;

    /** 只回「还能不能提交」：有效性与剩余次数，不含 trace 内容。 */
    @GetMapping("/{token}")
    public ApiResult<Map<String, Object>> status(@PathVariable String token) {
        return ApiResult.ok(feedbackService.status(token));
    }

    @PostMapping("/{token}")
    public ApiResult<Map<String, Object>> submit(@PathVariable String token,
                                                 @RequestBody EndUserFeedbackService.SubmitRequest request) {
        return ApiResult.ok(feedbackService.submit(token, request));
    }
}
