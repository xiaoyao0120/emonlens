package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 评估规则（R2-1）：替代「全局采样率」的规则对象。
 *
 * <p>一条规则 = scope（TRACE / SPAN / SESSION）+ 过滤条件 + 评估器 + 采样与预算。
 * 没有启用规则的工作空间继续走旧的全局采样，保证升级不改变既有行为。</p>
 */
@Data
@TableName("et_eval_rule")
public class EvalRuleEntity {

    public static final String SCOPE_TRACE = "TRACE";
    public static final String SCOPE_SPAN = "SPAN";
    public static final String SCOPE_SESSION = "SESSION";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String ruleKey;
    private String name;
    private Boolean enabled;
    private String scope;
    /** 过滤条件：{agentKeys,traceNames,environments,promptNames,minDurationMs,maxDurationMs,attributes} */
    private String filtersJson;
    private String evaluatorIdsJson;
    /** 0..1；错误 trace 且 errorForced=true 时忽略采样率 */
    private Double sampleRate;
    private Boolean errorForced;
    /** 每小时最多评估次数；0=不限 */
    private Integer maxPerHour;
    /** SESSION scope：会话空闲多少分钟后评估 */
    private Integer sessionIdleMinutes;
    /** 数值越小越先匹配；多规则命中时按优先级合并 */
    private Integer priority;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @TableField(exist = false)
    private java.util.Map<String, Object> filters;
    @TableField(exist = false)
    private java.util.List<String> evaluatorIds;
}
