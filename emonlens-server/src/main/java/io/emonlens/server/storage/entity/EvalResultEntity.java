package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_eval_result")
public class EvalResultEntity {

    /** 标注队列完成项写入 et_eval_result 时的 evaluatorKey 前缀；删除队列时按此前缀级联清理 */
    public static final String ANNOTATION_KEY_PREFIX = "annotation:";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String evaluatorId;

    private String evaluatorKey;

    private Integer evaluatorVersion;

    private String workspaceId;


    private String agentKey;

    private String agentVersion;

    private String traceId;

    private Integer attempt;

    private BigDecimal score;

    private String label;

    private BigDecimal confidence;

    private String reason;

    private String evidence;

    /** LLM_JUDGE：criterion 级结构化结果 JSON（仅 criterion 配置时存在） */
    private String criterionResultsJson;

    private String judgeModel;
    /** LLM_JUDGE：本次评审消耗的 tokens（OpenAI usage） */
    private Integer promptTokens;
    private Integer completionTokens;

    /** 评估口径（R2-1）：TRACE / SESSION；历史结果为 null，按 TRACE 处理 */
    private String scope;
    /** SESSION scope：所属会话；TRACE scope 为 null（会话可从 trace 反查） */
    private String sessionId;
    /** 产出该结论的评估规则 key（R2-1）；手动评估为 null */
    private String ruleKey;

    private LocalDateTime createdAt;

    /** 数据集运行原因码（仅内存传递，不落 et_eval_result） */
    @TableField(exist = false)
    private String reasonCode;

    /** 数据集运行：本次是否实际发起了评审模型调用（用于调用次数统计） */
    @TableField(exist = false)
    private Boolean judgeCalled;

    /** LLM_JUDGE：HOLISTIC / CLAIMS / G_EVAL */
    @TableField(exist = false)
    private String judgeMode;
    /** LLM_JUDGE：预设 key（rag-faithfulness 等）；空表示自定义 rubric */
    @TableField(exist = false)
    private String preset;
    @TableField(exist = false)
    private String judgeTemplateKey;
    @TableField(exist = false)
    private Integer judgeTemplateVersion;
    @TableField(exist = false)
    private String judgeTemplateHash;
    /** CLAIMS：逐条 claim 结果 JSON */
    @TableField(exist = false)
    private String claimsJson;
    /** CLAIMS：聚合结果 JSON（passCount/failCount/unknownCount/passRatio） */
    @TableField(exist = false)
    private String claimAggregateJson;

    /** 命中评测缓存：本次没有发起 Judge 调用，成本为 0 */
    @TableField(exist = false)
    private Boolean cacheHit;
    /** 缓存 key（内部审计；不含正文） */
    @TableField(exist = false)
    private String cacheKey;
}
