package io.emonlens.server.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import lombok.RequiredArgsConstructor;

/**
 * 控制面 JWT 拦截链：/api/v1/** 全拦截，豁免登录、数据面（X-Trust-Key）、开放 API
 * 与端用户反馈的公开入口（/api/v1/feedback/**，只认路径里的预签 token，读不到正文）。
 */
@Configuration
@RequiredArgsConstructor
public class WebSecurityConfig implements WebMvcConfigurer {

    private final AuthenticationInterceptor authenticationInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authenticationInterceptor)
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns(
                        "/api/v1/auth/login",
                        "/api/v1/ingest/**",
                        "/api/v1/open/**",
                        "/api/v1/feedback/**");
    }
}
