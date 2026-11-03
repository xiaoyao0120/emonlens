package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

/**
 * 一个样本 × 一个评估器 = 一条任务。任务行是 Run/Task/Attempt 事实源的首层：
 * 只记录判定、原因、用量与证据哈希，不保存候选正文（正文仍受内容权限约束）。
 */
@Data
@TableName("et_eval_task")
public class EvalTaskEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    /** 评估器给出了 PASS/FAIL 判定。 */
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    /** 评估器明确无法判定（UNKNOWN）；不是执行失败，也不是通过。 */
    public static final String STATUS_UNKNOWN = "UNKNOWN";
    public static final String STATUS_FAILED = "FAILED";
    /** 预算/中断导致未执行。 */
    public static final String STATUS_SKIPPED = "SKIPPED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String runId;
    private String runItemId;
    private String datasetId;
    private String itemId;
    private Integer itemOrder;
    private String evaluatorId;
    private String evaluatorKey;
    /** EXPRESSION/REGEX/LLM_JUDGE/TRAJECTORY/SDK；SDK 任务由外部回写结果，服务端不执行 */
    private String evaluatorType;
    private Integer evaluatorVersion;
    private Integer evaluatorOrder;
    private String evaluatorFingerprint;
    private String judgeTemplateKey;
    private Integer judgeTemplateVersion;
    private String judgeTemplateHash;
    private String cacheKey;
    private String executionStatus;
    private String verdict;
    private BigDecimal score;
    private String reasonCode;
    private String reason;
    private String model;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer totalTokens;
    private Integer latencyMs;
    private Integer attemptCount;
    @JsonIgnore
    private String resultJson;
    private String evidenceHash;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;
}
