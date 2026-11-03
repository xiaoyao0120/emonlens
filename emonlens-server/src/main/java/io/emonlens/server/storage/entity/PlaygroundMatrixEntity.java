package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * Playground 矩阵实验（R1-2）：数据集样本 × 列（prompt 版本 / 模型 / 参数）的网格试跑。
 *
 * <p>矩阵只是试跑产物：不进 Gate、不进信任分、没有候选指纹。要成为准入证据必须显式
 * 升格为 dataset run（promote），升格后沿用 dataset run 的冻结与指纹口径。</p>
 */
@Data
@TableName("et_playground_matrix")
public class PlaygroundMatrixEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    /** 有单元格失败但网格跑完：结论可用，失败格单独列出 */
    public static final String STATUS_PARTIAL = "PARTIAL";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    /** 客户端幂等键：同一 key 重放返回同一矩阵 */
    private String requestKey;
    private String requestHash;
    private String datasetId;
    /** 受理时冻结的数据集修订；矩阵始终在冻结样本上跑 */
    private String datasetRevisionId;
    private String name;
    private String status;
    /** 列定义（JSON 数组）：每列 = 一个 prompt 版本 + 模型 + 参数 */
    private String columnsJson;
    /** 挂载的评估器（JSON 数组）：升格为 dataset run 时沿用 */
    private String evaluatorIdsJson;
    /** 启动前成本预估（JSON） */
    private String estimateJson;
    private Integer itemCount;
    private Integer cellCount;
    private Integer completedCells;
    private Integer failedCells;
    private Long promptTokens;
    private Long completionTokens;
    private BigDecimal cost;
    private String currency;
    private String unpricedModels;
    private String error;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    @TableField(exist = false)
    private java.util.List<java.util.Map<String, Object>> columns;
    @TableField(exist = false)
    private java.util.List<String> evaluatorIds;
    @TableField(exist = false)
    private java.util.Map<String, Object> estimate;
}
