package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Data;

@Data
@TableName("et_dataset_revision_item")
public class DatasetRevisionItemEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String revisionId;
    private String itemKey;
    private String traceId;
    private String input;
    private String expectedOutput;
    private String metadataJson;
    private Integer schemaVersion;
    private String retrievalContextJson;
    private String expectedToolsJson;
    private String turnsJson;
    private String expectationsJson;
    /** R3-4：多模态附件随修订一起冻结 */
    private String attachmentsJson;
    private String purposeTags;
    private String riskLevel;
    private String groupKey;
    private String split;
    private String failureMode;
    private String contentHash;
    private Integer itemOrder;
    private LocalDateTime createdAt;
}
