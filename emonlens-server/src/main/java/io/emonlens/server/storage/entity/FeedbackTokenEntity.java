package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 端用户反馈的预签 token（R2-2）：只授予「给指定 trace 写一条反馈」的能力。
 *
 * <p>库里只存 token 的 sha256，不存明文；配额（maxUses）、过期与吊销都在服务端判定，
 * 这个 token 读不到 trace 正文，也没有任何其它 scope。</p>
 */
@Data
@TableName("et_feedback_token")
public class FeedbackTokenEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String traceId;
    private String tokenHash;
    private Integer maxUses;
    private Integer usedCount;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime lastUsedAt;
    private String createdBy;
    private LocalDateTime createdAt;
}
