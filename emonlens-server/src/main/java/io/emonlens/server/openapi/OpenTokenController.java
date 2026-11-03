package io.emonlens.server.openapi;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.WorkspaceGuard;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.AccessTokenEntity;
import io.emonlens.server.storage.mapper.AccessTokenMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;

/** 开放 API Token 管理（签发明文仅一次；吊销立即失效）。 */
@RestController
@RequestMapping("/api/v1/open-tokens")
@RequiredArgsConstructor
public class OpenTokenController {

    /** 系统实际使用的 scope 集合（与 OpenApiController 的校验一致）；签发时白名单校验，防止预埋未来 scope。 */
    public static final Set<String> ALLOWED_SCOPES = Set.of("eval:run", "eval:read", "eval:content",
            "score:read", "monitors:write");

    private final AccessTokenMapper accessTokenMapper;

    @Data
    public static class IssueRequest {
        private String name;
        private String scopes; // 逗号分隔，如 score:read,monitors:write
    }

    @Data
    public static class IssuedVO {
        private String id;
        private String token;
        private String tokenMasked;
    }

    @Data
    public static class ListVO {
        private String id;
        private String tokenMasked;
        private String name;
        private String scopes;
        private String status;
        private LocalDateTime createdAt;
        private LocalDateTime lastUsedAt;
    }

    @PostMapping
    @RequiresPermission(Permissions.OPENAPI_MANAGE)
    @Audit(action = "open-token.create", resourceType = "open-token")
    public ApiResult<IssuedVO> issue(@RequestBody(required = false) IssueRequest request) {
        String plain = "eak-" + random(16) + "-" + random(16);
        AccessTokenEntity entity = new AccessTokenEntity();
        entity.setTokenHash(Sha256Utils.hash(plain));
        entity.setTokenMasked("eak-****-" + plain.substring(plain.length() - 4));
        entity.setName(request == null || request.getName() == null ? "open-api token" : request.getName());
        entity.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        entity.setScopes(request == null || request.getScopes() == null
                ? "score:read" : normalizeScopes(request.getScopes()));
        entity.setStatus(AccessTokenEntity.STATUS_ACTIVE);
        entity.setCreatedAt(LocalDateTime.now());
        accessTokenMapper.insert(entity);
        IssuedVO vo = new IssuedVO();
        vo.setId(entity.getId());
        vo.setToken(plain);
        vo.setTokenMasked(entity.getTokenMasked());
        return ApiResult.ok(vo);
    }

    @GetMapping
    @RequiresPermission(Permissions.OPENAPI_MANAGE)
    public ApiResult<List<ListVO>> list() {
        List<AccessTokenEntity> tokens = accessTokenMapper.selectList(Wrappers.<AccessTokenEntity>lambdaQuery()
                .eq(AccessTokenEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .orderByDesc(AccessTokenEntity::getCreatedAt));
        return ApiResult.ok(tokens.stream().map(t -> {
            ListVO vo = new ListVO();
            vo.setId(t.getId());
            vo.setTokenMasked(t.getTokenMasked());
            vo.setName(t.getName());
            vo.setScopes(t.getScopes());
            vo.setStatus(t.getStatus());
            vo.setCreatedAt(t.getCreatedAt());
            vo.setLastUsedAt(t.getLastUsedAt());
            return vo;
        }).collect(Collectors.toList()));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.OPENAPI_MANAGE)
    @Audit(action = "open-token.revoke", resourceType = "open-token")
    public ApiResult<Void> revoke(@PathVariable String id) {
        AccessTokenEntity token = accessTokenMapper.selectById(id);
        if (token == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "token not found");
        }
        WorkspaceGuard.owned(token.getWorkspaceId());
        token.setStatus(AccessTokenEntity.STATUS_REVOKED);
        accessTokenMapper.updateById(token);
        return ApiResult.ok(null);
    }

    /** 去重、排序后返回规范化 scope 串；含未知或空白 scope 直接拒绝。 */
    private static String normalizeScopes(String raw) {
        Set<String> parsed = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (parsed.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "scopes is required, allowed: " + String.join(",", ALLOWED_SCOPES));
        }
        for (String scope : parsed) {
            if (!ALLOWED_SCOPES.contains(scope)) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "unknown scope: " + scope + ", allowed: " + String.join(",", ALLOWED_SCOPES));
            }
        }
        return String.join(",", parsed);
    }

    private static String random(int len) {
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
