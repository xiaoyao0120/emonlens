package io.emonlens.server.storage.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

/**
 * 矩阵单元格：一列 × 一个样本 = 一次生成（可选评估）。
 * 单元格相互独立，单格失败只写 ERROR，不中断整批。
 */
@Data
@TableName("et_playground_cell")
public class PlaygroundCellEntity {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_OK = "OK";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String matrixId;
    private String columnKey;
    private String itemId;
    private Integer itemOrder;
    private String status;
    private String output;
    /** 输出 sha256：diff 的确定性比较只认 hash，避免大小写/空白噪声 */
    private String outputHash;
    private String renderedPrompt;
    /** 冻结的样本输入 / 参考：Diff 的 judge 与导出都以此为准，不回读可变的样本表 */
    private String inputText;
    private String expectedText;
    private String error;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer latencyMs;
    private BigDecimal cost;
    /** 挂了评估套件时的逐评估器结果（JSON 数组） */
    private String scoresJson;
    /** 套件聚合结论：PASS / FAIL / UNKNOWN / ERROR，未挂套件为 null */
    private String aggregateLabel;
    private BigDecimal score;
    private LocalDateTime createdAt;
    private LocalDateTime finishedAt;

    @TableField(exist = false)
    private java.util.List<java.util.Map<String, Object>> scores;
}
