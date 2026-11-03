package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 矩阵列对比（Diff）：指定一列为 base，其余列逐样本与之比较。
 * 确定性变化（文本是否变）与语义偏好（成对比较）分开记录，两者冲突时给 TRADEOFF。
 */
@Data
@TableName("et_playground_diff")
public class PlaygroundDiffEntity {

    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String matrixId;
    private String baseColumn;
    private String targetColumn;
    private String protocolVersion;
    private String judgeModelsJson;
    private Boolean positionRandomized;
    private Long positionSeed;
    private Integer itemCount;
    private Integer sameCount;
    private Integer changedCount;
    private Integer improvedCount;
    private Integer regressedCount;
    private Integer tradeoffCount;
    private Integer tieCount;
    private Integer unknownCount;
    private String status;
    private String createdBy;
    private LocalDateTime createdAt;

    @TableField(exist = false)
    private java.util.List<java.util.Map<String, Object>> items;
}
