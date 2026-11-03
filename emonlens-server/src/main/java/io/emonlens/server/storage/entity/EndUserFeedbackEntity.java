package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 端用户反馈（R2-2）：score 类型学 = numeric / categorical / boolean / correction。
 *
 * <p>每条反馈同时落一条 {@link EvalResultEntity}（evaluatorKey=end-user-feedback），
 * 这样既有的评估监控、质量视图与信任分不必为"外部反馈"新增数据通路；
 * 类型化明细留在这张表里，供转数据集与后续分析使用。</p>
 */
@Data
@TableName("et_end_user_feedback")
public class EndUserFeedbackEntity {

    public static final String KIND_NUMERIC = "NUMERIC";
    public static final String KIND_CATEGORICAL = "CATEGORICAL";
    public static final String KIND_BOOLEAN = "BOOLEAN";
    public static final String KIND_CORRECTION = "CORRECTION";

    public static final String SOURCE_END_USER = "END_USER";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String traceId;
    private String tokenId;
    private String kind;
    private BigDecimal valueNum;
    private String valueText;
    /** 用户给出的期望输出（CORRECTION）；可直接转数据集样本 */
    private String correction;
    private String comment;
    private String userRef;
    private String source;
    /** 归一后的判定：PASS / FAIL（与 human score 同口径，供监控与质量视图复用） */
    private String label;
    private BigDecimal score;
    private LocalDateTime createdAt;
}
