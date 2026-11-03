package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_evaluator")
public class EvaluatorEntity {

    public static final String TYPE_EXPRESSION = "EXPRESSION";
    public static final String TYPE_REGEX = "REGEX";
    public static final String TYPE_LLM_JUDGE = "LLM_JUDGE";
    /** 确定性轨迹检查：期望工具集合/顺序/禁用/步数/参数 */
    public static final String TYPE_TRAJECTORY = "TRAJECTORY";
    /**
     * SDK 评测器：判定逻辑在用户环境执行（Python scorer），结果经开放 API 回写任务账本。
     * 服务端只声明输入契约（spec.requiredFields），不执行任何用户代码。
     */
    public static final String TYPE_SDK = "SDK";

    public static final String LABEL_PASS = "PASS";
    public static final String LABEL_FAIL = "FAIL";
    /** 数据集运行：无法取得参考/候选/内容时的明确未判定 */
    public static final String LABEL_UNKNOWN = "UNKNOWN";
    /** 数据集运行：样本执行失败（生成失败、评估器配置错误等） */
    public static final String LABEL_ERROR = "ERROR";
    public static final String LABEL_EVAL_UNAVAILABLE = "EVAL_UNAVAILABLE";
    public static final String LABEL_NOT_APPLICABLE = "N/A";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;


    private String evaluatorKey;

    private String type;

    private String specJson;

    private Integer enabled;

    private Integer version;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
