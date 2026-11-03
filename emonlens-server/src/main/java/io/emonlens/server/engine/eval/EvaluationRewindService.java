package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.storage.entity.EvalRewindJobEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.EvalRewindJobMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Rewind（R2-1）：按 filter 圈选历史 trace 批量补评。
 *
 * <p>作业是「可暂停的游标」：按 (start_time, trace_id) 递增推进，每次只处理限速额度内的一批；
 * 幂等由规则命中账本保证——已评估过的 trace 再次被圈选只会记 deduped，不会重复花钱。
 * 暂停/恢复/取消只改作业状态，不改已产出的结论。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationRewindService {

    private static final int MAX_BATCH = 50;

    private final EvalRewindJobMapper jobMapper;
    private final TraceSummaryMapper traceSummaryMapper;
    private final EvaluationRuleService ruleService;
    private final ObjectMapper objectMapper;

    public Map<String, Object> create(String workspaceId, String requestKey,
                                      EvaluationRuleService.RewindRequest request, String createdBy) {
        String key = requestKey == null ? "" : requestKey.trim();
        if (key.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "缺少 Idempotency-Key 请求头");
        }
        if (key.length() > 128) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "Idempotency-Key 不能超过 128 字符");
        }
        if (request == null || request.getRuleKey() == null || request.getRuleKey().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "ruleKey is required");
        }
        // 规则必须存在且属于本工作空间（不存在直接 404，避免建出无法执行的作业）
        ruleService.get(workspaceId, request.getRuleKey());
        Map<String, Object> filter = request.getFilters() == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(request.getFilters());
        int rate = request.getRatePerMinute() == null ? 60 : Math.max(1, request.getRatePerMinute());
        if (rate > 6000) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "ratePerMinute 不能超过 6000");
        }

        EvalRewindJobEntity existing = jobMapper.selectOne(Wrappers.<EvalRewindJobEntity>lambdaQuery()
                .eq(EvalRewindJobEntity::getWorkspaceId, workspaceId)
                .eq(EvalRewindJobEntity::getRequestKey, key));
        if (existing != null) {
            return toMap(hydrate(existing));
        }
        int total = countCandidates(workspaceId, filter);
        int limit = request.getLimit() == null ? total : Math.min(total, Math.max(0, request.getLimit()));
        EvalRewindJobEntity job = new EvalRewindJobEntity();
        job.setWorkspaceId(workspaceId);
        job.setRequestKey(key);
        job.setRuleKey(request.getRuleKey().trim());
        job.setFilterJson(toJson(filter));
        job.setStatus(EvalRewindJobEntity.STATUS_PENDING);
        job.setRatePerMinute(rate);
        job.setTotal(limit);
        job.setProcessed(0);
        job.setEvaluated(0);
        job.setDeduped(0);
        job.setFailed(0);
        job.setCreatedBy(createdBy);
        job.setCreatedAt(LocalDateTime.now());
        try {
            jobMapper.insert(job);
        } catch (DuplicateKeyException e) {
            EvalRewindJobEntity raced = jobMapper.selectOne(Wrappers.<EvalRewindJobEntity>lambdaQuery()
                    .eq(EvalRewindJobEntity::getWorkspaceId, workspaceId)
                    .eq(EvalRewindJobEntity::getRequestKey, key));
            if (raced != null) {
                return toMap(hydrate(raced));
            }
            throw new EmonLensException(ErrorCode.CONFLICT, "Rewind 作业并发创建冲突，请重试");
        }
        return toMap(hydrate(job));
    }

    public List<Map<String, Object>> list(String workspaceId) {
        List<EvalRewindJobEntity> jobs = jobMapper.selectList(Wrappers.<EvalRewindJobEntity>lambdaQuery()
                .eq(EvalRewindJobEntity::getWorkspaceId, workspaceId)
                .orderByDesc(EvalRewindJobEntity::getCreatedAt)
                .last("LIMIT 50"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (EvalRewindJobEntity job : jobs) {
            out.add(toMap(hydrate(job)));
        }
        return out;
    }

    public Map<String, Object> get(String workspaceId, String jobId) {
        return toMap(hydrate(requireJob(workspaceId, jobId)));
    }

    public Map<String, Object> pause(String workspaceId, String jobId) {
        return changeStatus(workspaceId, jobId, EvalRewindJobEntity.STATUS_PAUSED);
    }

    public Map<String, Object> resume(String workspaceId, String jobId) {
        EvalRewindJobEntity job = requireJob(workspaceId, jobId);
        if (EvalRewindJobEntity.STATUS_COMPLETED.equals(job.getStatus())
                || EvalRewindJobEntity.STATUS_CANCELLED.equals(job.getStatus())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "已结束的作业不能恢复");
        }
        job.setStatus(EvalRewindJobEntity.STATUS_PENDING);
        jobMapper.updateById(job);
        return toMap(hydrate(job));
    }

    public Map<String, Object> cancel(String workspaceId, String jobId) {
        return changeStatus(workspaceId, jobId, EvalRewindJobEntity.STATUS_CANCELLED);
    }

    private Map<String, Object> changeStatus(String workspaceId, String jobId, String status) {
        EvalRewindJobEntity job = requireJob(workspaceId, jobId);
        job.setStatus(status);
        if (EvalRewindJobEntity.STATUS_CANCELLED.equals(status)) {
            job.setFinishedAt(LocalDateTime.now());
        }
        jobMapper.updateById(job);
        return toMap(hydrate(job));
    }

    // ==================== 执行（worker 调用） ====================

    /** 推进一批：认领一个作业，处理限速额度内的候选 trace；返回本轮处理的 trace 数。 */
    public int runOnce() {
        EvalRewindJobEntity candidate = jobMapper.selectOne(Wrappers.<EvalRewindJobEntity>lambdaQuery()
                .eq(EvalRewindJobEntity::getStatus, EvalRewindJobEntity.STATUS_PENDING)
                .orderByAsc(EvalRewindJobEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (candidate == null) {
            return 0;
        }
        int claimed = jobMapper.update(null, Wrappers.<EvalRewindJobEntity>lambdaUpdate()
                .eq(EvalRewindJobEntity::getId, candidate.getId())
                .eq(EvalRewindJobEntity::getStatus, EvalRewindJobEntity.STATUS_PENDING)
                .set(EvalRewindJobEntity::getStatus, EvalRewindJobEntity.STATUS_RUNNING)
                .set(EvalRewindJobEntity::getStartedAt, LocalDateTime.now()));
        if (claimed == 0) {
            return 0;
        }
        EvalRewindJobEntity job = jobMapper.selectById(candidate.getId());
        int batch = Math.max(1, Math.min(MAX_BATCH, job.getRatePerMinute() / 12));
        return processBatch(job, batch);
    }

    private int processBatch(EvalRewindJobEntity job, int batch) {
        Map<String, Object> filter = parseMap(job.getFilterJson());
        List<TraceSummaryEntity> traces = nextCandidates(job, filter, batch);
        int processed = 0;
        for (TraceSummaryEntity summary : traces) {
            EvalRewindJobEntity current = jobMapper.selectById(job.getId());
            if (!EvalRewindJobEntity.STATUS_RUNNING.equals(current.getStatus())) {
                return processed; // 已被暂停 / 取消：保留游标，下次从这里继续
            }
            int produced = 0;
            try {
                produced = ruleService.runForTrace(job.getWorkspaceId(), summary, true);
            } catch (Exception e) {
                log.error("rewind eval failed, job={}, trace={}", job.getId(), summary.getTraceId(), e);
                current.setFailed(current.getFailed() == null ? 1 : current.getFailed() + 1);
            }
            processed++;
            current.setProcessed((current.getProcessed() == null ? 0 : current.getProcessed()) + 1);
            current.setEvaluated((current.getEvaluated() == null ? 0 : current.getEvaluated()) + produced);
            if (produced == 0) {
                current.setDeduped((current.getDeduped() == null ? 0 : current.getDeduped()) + 1);
            }
            current.setCursorStartTime(summary.getStartTime());
            current.setCursorTraceId(summary.getTraceId());
            jobMapper.updateById(current);
        }
        finishIfDone(job.getId());
        return processed;
    }

    private void finishIfDone(String jobId) {
        EvalRewindJobEntity job = jobMapper.selectById(jobId);
        if (job == null || !EvalRewindJobEntity.STATUS_RUNNING.equals(job.getStatus())) {
            return;
        }
        boolean limited = job.getTotal() != null && job.getProcessed() != null
                && job.getTotal() > 0 && job.getProcessed() >= job.getTotal();
        int remaining = nextCandidates(job, parseMap(job.getFilterJson()), 1).size();
        if (limited || remaining == 0) {
            job.setStatus(EvalRewindJobEntity.STATUS_COMPLETED);
            job.setFinishedAt(LocalDateTime.now());
            jobMapper.updateById(job);
        }
    }

    private List<TraceSummaryEntity> nextCandidates(EvalRewindJobEntity job, Map<String, Object> filter,
                                                    int limit) {
        var query = Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, job.getWorkspaceId())
                .eq(TraceSummaryEntity::getStatus, TraceSummaryEntity.STATUS_COMPLETED);
        LocalDateTime minStart = parseTime(filter.get("minStartTime"));
        LocalDateTime maxStart = parseTime(filter.get("maxStartTime"));
        if (minStart != null) {
            query.ge(TraceSummaryEntity::getStartTime, minStart);
        }
        if (maxStart != null) {
            query.le(TraceSummaryEntity::getStartTime, maxStart);
        }
        if (filter.get("agentKeys") instanceof List && !((List<?>) filter.get("agentKeys")).isEmpty()) {
            query.in(TraceSummaryEntity::getAgentKey, (List<?>) filter.get("agentKeys"));
        }
        if (Boolean.TRUE.equals(filter.get("onlyErrors"))) {
            query.gt(TraceSummaryEntity::getErrorCount, 0);
        }
        if (job.getCursorStartTime() != null) {
            query.and(w -> w.gt(TraceSummaryEntity::getStartTime, job.getCursorStartTime())
                    .or(o -> o.eq(TraceSummaryEntity::getStartTime, job.getCursorStartTime())
                            .gt(TraceSummaryEntity::getTraceId, job.getCursorTraceId())));
        }
        query.orderByAsc(TraceSummaryEntity::getStartTime)
                .orderByAsc(TraceSummaryEntity::getTraceId)
                .last("LIMIT " + Math.max(1, limit));
        return traceSummaryMapper.selectList(query);
    }

    private int countCandidates(String workspaceId, Map<String, Object> filter) {
        var query = Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                .eq(TraceSummaryEntity::getStatus, TraceSummaryEntity.STATUS_COMPLETED);
        LocalDateTime minStart = parseTime(filter.get("minStartTime"));
        LocalDateTime maxStart = parseTime(filter.get("maxStartTime"));
        if (minStart != null) {
            query.ge(TraceSummaryEntity::getStartTime, minStart);
        }
        if (maxStart != null) {
            query.le(TraceSummaryEntity::getStartTime, maxStart);
        }
        if (filter.get("agentKeys") instanceof List && !((List<?>) filter.get("agentKeys")).isEmpty()) {
            query.in(TraceSummaryEntity::getAgentKey, (List<?>) filter.get("agentKeys"));
        }
        if (Boolean.TRUE.equals(filter.get("onlyErrors"))) {
            query.gt(TraceSummaryEntity::getErrorCount, 0);
        }
        Long count = traceSummaryMapper.selectCount(query);
        return count == null ? 0 : (int) Math.min(Integer.MAX_VALUE, count);
    }

    private EvalRewindJobEntity requireJob(String workspaceId, String jobId) {
        EvalRewindJobEntity job = jobId == null ? null : jobMapper.selectById(jobId);
        if (job == null || !workspaceId.equals(job.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "rewind job not found");
        }
        return job;
    }

    private EvalRewindJobEntity hydrate(EvalRewindJobEntity job) {
        job.setFilter(parseMap(job.getFilterJson()));
        return job;
    }

    private Map<String, Object> toMap(EvalRewindJobEntity job) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", job.getId());
        map.put("ruleKey", job.getRuleKey());
        map.put("filter", job.getFilter());
        map.put("status", job.getStatus());
        map.put("ratePerMinute", job.getRatePerMinute());
        map.put("total", job.getTotal());
        map.put("processed", job.getProcessed());
        map.put("evaluated", job.getEvaluated());
        map.put("deduped", job.getDeduped());
        map.put("failed", job.getFailed());
        map.put("cursorTraceId", job.getCursorTraceId());
        map.put("cursorStartTime", job.getCursorStartTime());
        map.put("error", job.getError());
        map.put("createdBy", job.getCreatedBy());
        map.put("createdAt", job.getCreatedAt());
        map.put("startedAt", job.getStartedAt());
        map.put("finishedAt", job.getFinishedAt());
        return map;
    }

    private LocalDateTime parseTime(Object value) {
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(String.valueOf(value).trim());
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "时间过滤必须是 ISO-8601（如 2026-11-01T00:00:00）: " + value);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "rewind json 序列化失败");
        }
    }

    private Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
