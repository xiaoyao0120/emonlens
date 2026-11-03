package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * Prompt 版本解析留痕（R1-3）：谁在什么时候按哪个 label 取到了哪个版本。
 * resolve 是读操作，不进 et_audit_log 的操作审计，因此单独记一张表回答
 * “线上跑的是哪个 prompt 版本、谁在什么时候把 production 指到它”。
 */
@Data
@TableName("et_prompt_resolution")
public class PromptResolutionEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String name;
    private String label;
    private Integer version;
    private String promptHash;
    /** UI / SDK / API：解析来源，便于区分人工查看与运行时拉取 */
    private String source;
    private String requestedBy;
    private LocalDateTime createdAt;
}
