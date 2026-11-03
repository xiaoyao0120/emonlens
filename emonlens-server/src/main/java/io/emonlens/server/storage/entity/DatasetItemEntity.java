package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_dataset_item")
public class DatasetItemEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String datasetId;
    private String traceId;
    private String input;
    private String expectedOutput;
    private String metadataJson;
    /** 样本契约版本：1=旧样本，2=含检索/工具/多轮/期望 */
    private Integer schemaVersion;
    private String retrievalContextJson;
    private String expectedToolsJson;
    private String turnsJson;
    private String expectationsJson;
    /** R3-4：多模态附件（JSON 数组：kind/mime/url|base64/alt）；不涉及 TRAJECTORY */
    private String attachmentsJson;
    /** case/v2 的 canonical SHA-256；旧样本为空，由 revision/snapshot 计算 */
    private String contentHash;
    /** MANUAL / TRACE / IMPORT / SYNTHETIC */
    private String sourceType;
    /** 来源引用：synthesis:<jobId>/<candidateId>、import 文件名等 */
    private String sourceRef;
    /** 样本用途标签（逗号分隔，如 happy,boundary,regression） */
    private String purposeTags;
    /** 风险级别：UNASSIGNED/LOW/MEDIUM/HIGH/CRITICAL */
    private String riskLevel;
    /** 同一意图/场景的分组键，用于按组统计 */
    private String groupKey;
    /** 数据划分：UNASSIGNED/DEV/VALIDATION/HOLDOUT */
    private String split;
    /** 该样本要防止的失败模式，便于坏案例闭环 */
    private String failureMode;
    /** ACTIVE / QUARANTINED；QUARANTINED 不进入新 run，但仍保留历史。 */
    private String status;
    private String invalidReason;
    private java.time.LocalDateTime invalidatedAt;
    private LocalDateTime createdAt;
}
