package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetRevisionEntity;
import io.emonlens.server.storage.entity.DatasetRevisionItemEntity;
import io.emonlens.server.storage.mapper.DatasetRevisionItemMapper;
import io.emonlens.server.storage.mapper.DatasetRevisionMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据集修订：把当前样本集合冻结成不可变 revision。
 * run 必须引用 revision；此后编辑/删除样本不会改变已受理 run 的样本集合。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetRevisionService {

    private final DatasetRevisionMapper revisionMapper;
    private final DatasetRevisionItemMapper revisionItemMapper;
    private final EvaluationCaseService evaluationCaseService;

    @Transactional
    public DatasetRevisionEntity freeze(DatasetEntity dataset, List<DatasetItemEntity> items,
                                        String label, String createdBy) {
        if (items == null || items.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "数据集没有样本，无法冻结修订");
        }
        List<DatasetItemEntity> ordered = new ArrayList<>(items);
        ordered.sort(Comparator.comparing(DatasetItemEntity::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(DatasetItemEntity::getId));

        DatasetRevisionEntity latest = revisionMapper.selectOne(Wrappers.<DatasetRevisionEntity>lambdaQuery()
                .eq(DatasetRevisionEntity::getWorkspaceId, dataset.getWorkspaceId())
                .eq(DatasetRevisionEntity::getDatasetId, dataset.getId())
                .orderByDesc(DatasetRevisionEntity::getRevision)
                .last("LIMIT 1"));
        int nextRevision = latest == null || latest.getRevision() == null ? 1 : latest.getRevision() + 1;

        DatasetRevisionEntity revision = new DatasetRevisionEntity();
        revision.setWorkspaceId(dataset.getWorkspaceId());
        revision.setDatasetId(dataset.getId());
        revision.setRevision(nextRevision);
        revision.setLabel(label == null || label.isBlank() ? "v" + nextRevision : label.trim());
        revision.setItemCount(ordered.size());
        revision.setCreatedBy(createdBy);
        revision.setCreatedAt(LocalDateTime.now());
        revision.setManifestHash(manifestHash(ordered));
        revisionMapper.insert(revision);

        int order = 0;
        List<DatasetRevisionItemEntity> frozenItems = new ArrayList<>();
        for (DatasetItemEntity item : ordered) {
            DatasetRevisionItemEntity frozen = new DatasetRevisionItemEntity();
            frozen.setWorkspaceId(dataset.getWorkspaceId());
            frozen.setRevisionId(revision.getId());
            frozen.setItemKey(item.getId());
            frozen.setTraceId(item.getTraceId());
            frozen.setInput(item.getInput());
            frozen.setExpectedOutput(item.getExpectedOutput());
            frozen.setMetadataJson(item.getMetadataJson());
            frozen.setSchemaVersion(evaluationCaseService.schemaVersion(item.getSchemaVersion()));
            frozen.setRetrievalContextJson(item.getRetrievalContextJson());
            frozen.setExpectedToolsJson(item.getExpectedToolsJson());
            frozen.setTurnsJson(item.getTurnsJson());
            frozen.setExpectationsJson(item.getExpectationsJson());
        frozen.setAttachmentsJson(item.getAttachmentsJson());
            frozen.setPurposeTags(item.getPurposeTags());
            frozen.setRiskLevel(item.getRiskLevel());
            frozen.setGroupKey(item.getGroupKey());
            frozen.setSplit(item.getSplit());
            frozen.setFailureMode(item.getFailureMode());
            frozen.setContentHash(contentHash(item));
            frozen.setItemOrder(order++);
            frozen.setCreatedAt(LocalDateTime.now());
            revisionItemMapper.insert(frozen);
            frozenItems.add(frozen);
        }
        revision.setItems(frozenItems);
        log.info("dataset revision frozen, dataset={}, revision={}, items={}",
                dataset.getId(), nextRevision, frozenItems.size());
        return revision;
    }

    public DatasetRevisionEntity requireOwned(String workspaceId, String datasetId, String revisionId) {
        DatasetRevisionEntity revision = revisionMapper.selectById(revisionId);
        if (revision == null || !workspaceId.equals(revision.getWorkspaceId())
                || !datasetId.equals(revision.getDatasetId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset revision not found");
        }
        revision.setItems(itemsOf(revisionId));
        return revision;
    }

    public List<DatasetRevisionItemEntity> itemsOf(String revisionId) {
        return revisionItemMapper.selectList(Wrappers.<DatasetRevisionItemEntity>lambdaQuery()
                .eq(DatasetRevisionItemEntity::getRevisionId, revisionId)
                .orderByAsc(DatasetRevisionItemEntity::getItemOrder));
    }

    public List<DatasetRevisionEntity> list(String workspaceId, String datasetId) {
        return revisionMapper.selectList(Wrappers.<DatasetRevisionEntity>lambdaQuery()
                .eq(DatasetRevisionEntity::getWorkspaceId, workspaceId)
                .eq(DatasetRevisionEntity::getDatasetId, datasetId)
                .orderByDesc(DatasetRevisionEntity::getRevision));
    }

    /** 转成冻结快照服务使用的样本视图；id 保持原 itemKey。 */
    public List<DatasetItemEntity> toItems(DatasetRevisionEntity revision) {
        List<DatasetItemEntity> items = new ArrayList<>();
        for (DatasetRevisionItemEntity frozen : revision.getItems() == null
                ? itemsOf(revision.getId()) : revision.getItems()) {
            DatasetItemEntity item = new DatasetItemEntity();
            item.setId(frozen.getItemKey());
            item.setDatasetId(revision.getDatasetId());
            item.setTraceId(frozen.getTraceId());
            item.setInput(frozen.getInput());
            item.setExpectedOutput(frozen.getExpectedOutput());
            item.setMetadataJson(frozen.getMetadataJson());
            item.setSchemaVersion(frozen.getSchemaVersion());
            item.setRetrievalContextJson(frozen.getRetrievalContextJson());
            item.setExpectedToolsJson(frozen.getExpectedToolsJson());
            item.setTurnsJson(frozen.getTurnsJson());
            item.setExpectationsJson(frozen.getExpectationsJson());
        item.setAttachmentsJson(frozen.getAttachmentsJson());
            item.setContentHash(frozen.getContentHash());
            item.setPurposeTags(frozen.getPurposeTags());
            item.setRiskLevel(frozen.getRiskLevel());
            item.setGroupKey(frozen.getGroupKey());
            item.setSplit(frozen.getSplit());
            item.setFailureMode(frozen.getFailureMode());
            // 用 itemOrder 生成确定性时间，保证快照层再次按 createdAt 排序时仍与 revision 顺序一致。
            item.setCreatedAt(java.time.LocalDateTime.of(1970, 1, 1, 0, 0)
                    .plusSeconds(frozen.getItemOrder() == null ? 0 : frozen.getItemOrder()));
            items.add(item);
        }
        return items;
    }

    public Map<String, Object> toMap(DatasetRevisionEntity revision) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", revision.getId());
        map.put("revision", revision.getRevision());
        map.put("label", revision.getLabel());
        map.put("manifestHash", revision.getManifestHash());
        map.put("itemCount", revision.getItemCount());
        map.put("createdBy", revision.getCreatedBy());
        map.put("createdAt", revision.getCreatedAt());
        return map;
    }

    private String manifestHash(List<DatasetItemEntity> items) {
        List<String> parts = new ArrayList<>();
        for (DatasetItemEntity item : items) {
            parts.add(item.getId() + "|" + contentHash(item));
        }
        return Sha256Utils.hash(String.join("\n", parts));
    }

    private String contentHash(DatasetItemEntity item) {
        return evaluationCaseService.hash(item);
    }
}
