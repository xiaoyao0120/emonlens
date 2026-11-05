package io.emonlens.server.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.ApiResult;
import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.audit.Audit;
import io.emonlens.server.auth.CurrentContext;
import io.emonlens.server.auth.Permissions;
import io.emonlens.server.auth.RequiresPermission;
import io.emonlens.server.storage.entity.AnnotationQueueEntity;
import io.emonlens.server.storage.entity.AnnotationQueueItemEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.AnnotationQueueItemMapper;
import io.emonlens.server.storage.mapper.AnnotationQueueMapper;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;

/**
 * Annotation Queues（对标 LangSmith 注解队列）：
 * 把待人工复核的 Trace 组成队列，标注员逐条领取打分，结果同时写入评估结果。
 */
@RestController
@RequestMapping("/api/v1/annotation-queues")
@RequiredArgsConstructor
public class AnnotationQueueController {

    private final AnnotationQueueMapper queueMapper;
    private final AnnotationQueueItemMapper itemMapper;
    private final TraceSummaryMapper traceSummaryMapper;
    private final EvalResultMapper evalResultMapper;
    private final io.emonlens.server.engine.event.EventService eventService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Data
    public static class CreateQueueRequest {
        private String name;
        private String description;
    }

    @Data
    public static class AddItemsRequest {
        private List<String> traceIds;
    }

    @Data
    public static class CompleteRequest {
        private Double score;
        private String comment;
    }

    @GetMapping
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<List<Map<String, Object>>> list() {
        String ws = CurrentContext.require().getWorkspaceId();
        List<AnnotationQueueEntity> queues = queueMapper.selectList(Wrappers.<AnnotationQueueEntity>lambdaQuery()
                .eq(AnnotationQueueEntity::getWorkspaceId, ws)
                .orderByDesc(AnnotationQueueEntity::getCreatedAt));
        return ApiResult.ok(queues.stream().map(q -> {
            Map<String, Object> m = toVO(q);
            List<AnnotationQueueItemEntity> items = itemMapper.selectList(Wrappers.<AnnotationQueueItemEntity>lambdaQuery()
                    .eq(AnnotationQueueItemEntity::getQueueId, q.getId()));
            m.put("total", items.size());
            m.put("pending", items.stream().filter(i -> "PENDING".equals(i.getStatus())).count());
            m.put("completed", items.stream().filter(i -> "COMPLETED".equals(i.getStatus())).count());
            return m;
        }).collect(Collectors.toList()));
    }

    @PostMapping
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "annotation-queue.create", resourceType = "annotation-queue")
    public ApiResult<Map<String, Object>> create(@RequestBody CreateQueueRequest request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "name is required");
        }
        AnnotationQueueEntity entity = new AnnotationQueueEntity();
        entity.setWorkspaceId(CurrentContext.require().getWorkspaceId());
        entity.setName(request.getName().trim());
        entity.setDescription(request.getDescription());
        entity.setCreatedBy(CurrentContext.require().getUsername());
        entity.setCreatedAt(LocalDateTime.now());
        queueMapper.insert(entity);
        return ApiResult.ok(toVO(entity));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "annotation-queue.delete", resourceType = "annotation-queue")
    public ApiResult<Void> delete(@PathVariable String id) {
        requireOwned(id);
        queueMapper.deleteById(id);
        itemMapper.delete(Wrappers.<AnnotationQueueItemEntity>lambdaQuery()
                .eq(AnnotationQueueItemEntity::getQueueId, id));
        // complete 时写入 et_eval_result 的人工评分一并清除：否则队列删除后仍留下
        // evaluator_key 指向不存在队列的孤儿评分（会话/用户洞察/评估结果里的幽灵分数）
        evalResultMapper.delete(Wrappers.<io.emonlens.server.storage.entity.EvalResultEntity>lambdaQuery()
                .eq(io.emonlens.server.storage.entity.EvalResultEntity::getEvaluatorKey,
                        EvalResultEntity.ANNOTATION_KEY_PREFIX + id));
        return ApiResult.ok(null);
    }

    @GetMapping("/{id}")
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<Map<String, Object>> detail(@PathVariable String id) {
        AnnotationQueueEntity queue = requireOwned(id);
        Map<String, Object> m = toVO(queue);
        m.put("items", itemMapper.selectList(Wrappers.<AnnotationQueueItemEntity>lambdaQuery()
                .eq(AnnotationQueueItemEntity::getQueueId, id)
                .orderByAsc(AnnotationQueueItemEntity::getCreatedAt)));
        return ApiResult.ok(m);
    }

    @PostMapping("/{id}/items")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "annotation-queue.item.add", resourceType = "annotation-queue")
    public ApiResult<Integer> addItems(@PathVariable String id, @RequestBody AddItemsRequest request) {
        requireOwned(id);
        int added = 0;
        for (String traceId : request.getTraceIds() == null ? List.<String>of() : request.getTraceIds()) {
            String tid = traceId == null ? "" : traceId.trim();
            if (tid.isEmpty()) {
                continue;
            }
            TraceSummaryEntity summary = traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                    .eq(TraceSummaryEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                    .eq(TraceSummaryEntity::getTraceId, tid));
            if (summary == null || !summary.getWorkspaceId().equals(CurrentContext.require().getWorkspaceId())) {
                continue;
            }
            Long dup = itemMapper.selectCount(Wrappers.<AnnotationQueueItemEntity>lambdaQuery()
                    .eq(AnnotationQueueItemEntity::getQueueId, id)
                    .eq(AnnotationQueueItemEntity::getTraceId, tid));
            if (dup > 0) {
                continue;
            }
            AnnotationQueueItemEntity item = new AnnotationQueueItemEntity();
            item.setQueueId(id);
            item.setTraceId(tid);
            item.setStatus("PENDING");
            item.setCreatedAt(LocalDateTime.now());
            try {
                itemMapper.insert(item);
                added++;
            } catch (DuplicateKeyException e) {
                // 并发 addItems：uk_annotation_queue_trace 兜底，重复 trace 直接跳过
            }
        }
        return ApiResult.ok(added);
    }

    /** 领取下一个待标注样本（跳过当前用户已完成的） */
    @GetMapping("/{id}/next")
    @RequiresPermission(Permissions.TRACE_QUERY)
    public ApiResult<AnnotationQueueItemEntity> next(@PathVariable String id) {
        requireOwned(id);
        AnnotationQueueItemEntity item = itemMapper.selectOne(Wrappers.<AnnotationQueueItemEntity>lambdaQuery()
                .eq(AnnotationQueueItemEntity::getQueueId, id)
                .eq(AnnotationQueueItemEntity::getStatus, "PENDING")
                .orderByAsc(AnnotationQueueItemEntity::getCreatedAt)
                .last("LIMIT 1"));
        return ApiResult.ok(item);
    }

    /** 完成标注：写回队列项 + 同步一条人工评分到评估结果 */
    /** 分配标注人（LangSmith assignee 语义）：指定后 item.reviewer 预写为被分配人，完成时以实际提交人为准 */
    @PutMapping("/{id}/items/{itemId}/assign")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "annotation-queue.item.assign", resourceType = "annotation-queue")
    public ApiResult<AnnotationQueueItemEntity> assign(@PathVariable String id, @PathVariable String itemId,
                                                       @RequestBody AssignRequest request) {
        requireOwned(id);
        AnnotationQueueItemEntity item = itemMapper.selectById(itemId);
        if (item == null || !item.getQueueId().equals(id)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "queue item not found");
        }
        if ("COMPLETED".equals(item.getStatus())) {
            throw new EmonLensException(ErrorCode.CONFLICT, "item already completed");
        }
        String reviewer = request.getReviewer() == null ? null : request.getReviewer().trim();
        if (reviewer == null || reviewer.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "reviewer is required");
        }
        item.setReviewer(reviewer);
        itemMapper.updateById(item);
        return ApiResult.ok(item);
    }

    @PostMapping("/{id}/items/{itemId}/complete")
    @RequiresPermission(Permissions.EVALUATION_RUN)
    @Audit(action = "annotation-queue.item.complete", resourceType = "annotation-queue")
    public ApiResult<AnnotationQueueItemEntity> complete(@PathVariable String id, @PathVariable String itemId,
                                                         @RequestBody CompleteRequest request) {
        requireOwned(id);
        AnnotationQueueItemEntity item = itemMapper.selectById(itemId);
        if (item == null || !item.getQueueId().equals(id)) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "queue item not found");
        }
        String submitter = CurrentContext.require().getUsername();
        double score = request.getScore() == null ? 5.0 : Math.max(0, Math.min(10, request.getScore()));

        item.setStatus("COMPLETED");
        // 标注人归属：已分配（assign）则保留被分配人，未分配则记实际提交人
        if (item.getReviewer() == null || item.getReviewer().isEmpty()) {
            item.setReviewer(submitter);
        }
        String reviewer = item.getReviewer();
        item.setScore(BigDecimal.valueOf(score).setScale(2, java.math.RoundingMode.HALF_UP));
        item.setComment(request.getComment());
        item.setCompletedAt(LocalDateTime.now());
        itemMapper.updateById(item);

        TraceSummaryEntity summary = traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, CurrentContext.require().getWorkspaceId())
                .eq(TraceSummaryEntity::getTraceId, item.getTraceId()));
        if (summary != null) {
            EvalResultEntity result = new EvalResultEntity();
            result.setEvaluatorId("annotation");
            result.setEvaluatorKey(EvalResultEntity.ANNOTATION_KEY_PREFIX + id);
            result.setEvaluatorVersion(1);
            result.setWorkspaceId(summary.getWorkspaceId());
            result.setAgentKey(summary.getAgentKey());
            result.setAgentVersion(summary.getAgentVersion());
            result.setTraceId(item.getTraceId());
            result.setAttempt(1);
            result.setScore(item.getScore());
            result.setLabel(score >= 6.0 ? EvaluatorLabel.PASS : EvaluatorLabel.FAIL);
            result.setConfidence(BigDecimal.ONE);
            result.setReason("annotation by " + reviewer
                    + (submitter.equals(reviewer) ? "" : " (submitted by " + submitter + ")")
                    + (request.getComment() == null || request.getComment().isEmpty() ? "" : ": " + request.getComment()));
            result.setJudgeModel("human");
            result.setCreatedAt(LocalDateTime.now());
            evalResultMapper.insert(result);
            // 人工评分同样是该 agent 的评估证据：必须触发信任分重算，
            // 否则快照会一直停在旧值（此前只有自动评估与 Finding 会发 SCORE_CHANGED）
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("traceId", item.getTraceId());
            fields.put("agentKey", summary.getAgentKey());
            fields.put("source", "annotation");
            String scorePayload;
            try {
                scorePayload = objectMapper.writeValueAsString(fields);
            } catch (Exception e) {
                scorePayload = "{}";
            }
            eventService.enqueue(io.emonlens.server.engine.event.EventType.SCORE_CHANGED, summary.getWorkspaceId(),
                    summary.getAgentKey(), scorePayload);
        }
        return ApiResult.ok(item);
    }

    private static final class EvaluatorLabel {
        static final String PASS = "PASS";
        static final String FAIL = "FAIL";
    }

    private AnnotationQueueEntity requireOwned(String id) {
        AnnotationQueueEntity queue = queueMapper.selectById(id);
        if (queue == null || !queue.getWorkspaceId().equals(CurrentContext.require().getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "annotation queue not found");
        }
        return queue;
    }

    private Map<String, Object> toVO(AnnotationQueueEntity entity) {
        Map<String, Object> m = new HashMap<>();
        m.put("id", entity.getId());
        m.put("name", entity.getName());
        m.put("description", entity.getDescription());
        m.put("createdBy", entity.getCreatedBy());
        m.put("createdAt", entity.getCreatedAt());
        return m;
    }

    @Data
    public static class AssignRequest {
        /** 被分配的标注人用户名 */
        private String reviewer;
    }
}
