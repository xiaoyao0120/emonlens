package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 规则命中账本（R2-1）：既是小时预算的计数器，也是幂等记录。
 *
 * <p>唯一键 (rule_key, subject_id)：同一个规则对同一个主体（trace / 会话）只评估一次，
 * Rewind 重复跑、trace 重复完成事件、多规则同时命中都不会重复花钱。</p>
 */
@Data
@TableName("et_eval_rule_hit")
public class EvalRuleHitEntity {

    /** 命中且已评估（含错误强制） */
    public static final String DECISION_SAMPLED = "SAMPLED";
    /** 错误强制采样 */
    public static final String DECISION_FORCED = "FORCED";
    /** 采样率未命中 */
    public static final String DECISION_SKIPPED_RATE = "SKIPPED_RATE";
    /** 小时预算用尽 */
    public static final String DECISION_SKIPPED_BUDGET = "SKIPPED_BUDGET";
    /** 已被其它规则评估过同一评估器（归并去重） */
    public static final String DECISION_DEDUPED = "DEDUPED";
    /** 过滤条件不匹配（仅 Rewind 记录，实时链路不落库） */
    public static final String DECISION_FILTERED = "FILTERED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String ruleKey;
    private String scope;
    /** traceId（TRACE/SPAN）或 sessionId（SESSION） */
    private String subjectId;
    private String decision;
    private Integer evalCount;
    private String detail;
    private LocalDateTime createdAt;
}
