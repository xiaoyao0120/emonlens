package io.emonlens.server.storage.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/** 逐样本 diff 结论：deterministic=文本是否变化；preference=语义偏好。 */
@Data
@TableName("et_playground_diff_item")
public class PlaygroundDiffItemEntity {

    public static final String DET_SAME = "SAME";
    public static final String DET_CHANGED = "CHANGED";
    public static final String DET_MISSING = "MISSING";

    public static final String PREF_IMPROVEMENT = "IMPROVEMENT";
    public static final String PREF_REGRESSION = "REGRESSION";
    public static final String PREF_TRADEOFF = "TRADEOFF";
    public static final String PREF_TIE = "TIE";
    public static final String PREF_UNKNOWN = "UNKNOWN";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String diffId;
    private String itemId;
    private String deterministic;
    private String preference;
    private String reasonCode;
    private String reason;
    /** 两边评估套件分数/结论的差异（未挂套件为空） */
    private String scoreDeltaJson;
    /** judge 原始调用（方向、位置、模型、结论）留痕 */
    private String callsJson;
}
