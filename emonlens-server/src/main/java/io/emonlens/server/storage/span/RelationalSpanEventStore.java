package io.emonlens.server.storage.span;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.mapper.SpanEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 关系型实现（H2 / MySQL，MyBatis-Plus 承载）。默认实现，kind=mysql。
 * 幂等写入依赖 et_span_event 的 (workspace_id, trace_id, span_id) 唯一键，重复行捕获 DuplicateKeyException 跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RelationalSpanEventStore implements SpanEventStore {

    private final SpanEventMapper spanEventMapper;

    @Override
    public String kind() {
        return "mysql";
    }

    @Override
    public InsertResult insertBatch(List<SpanEventEntity> spans) {
        List<SpanEventEntity> written = new ArrayList<>(spans.size());
        int duplicates = 0;
        int rejected = 0;
        for (SpanEventEntity span : spans) {
            try {
                spanEventMapper.insert(span);
                written.add(span);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 幂等：SDK 重试 / 钩子重放产生的重复 span 跳过
                duplicates++;
            } catch (org.springframework.dao.DataIntegrityViolationException e) {
                // 单行数据超长/约束失败只丢弃该行：不能让一颗坏数据回滚整批 500 条 span
                rejected++;
                log.warn("span rejected (constraint): traceId={} spanId={} reason={}",
                        span.getTraceId(), span.getSpanId(), e.getMostSpecificCause().getMessage());
            }
        }
        return new InsertResult(written, duplicates, rejected);
    }

    @Override
    public List<SpanEventEntity> loadByTrace(String workspaceId, String traceId) {
        if (!hasText(workspaceId)) {
            throw new IllegalArgumentException("workspaceId must not be blank");
        }
        return spanEventMapper.selectList(wrapper(workspaceId)
                .eq(SpanEventEntity::getTraceId, traceId)
                .orderByAsc(SpanEventEntity::getStartTime));
    }

    @Override
    public java.util.Map<String, long[]> sumTokensByTraceIds(String workspaceId, List<String> traceIds) {
        java.util.Map<String, long[]> result = new java.util.HashMap<>();
        if (traceIds == null || traceIds.isEmpty()) {
            return result;
        }
        // 聚合下推到 SQL：这里以前把匹配到的 span 逐行取回内存再累加，7 天窗口就是上万行
        // （实测 1.5 万行要 7.5 秒）。改成 GROUP BY trace_id 后只回每 trace 一行（约 250 行）。
        // 分段是为了避免 IN 参数个数过大；500 与服务端其它批量查询保持一致。
        int step = 500;
        for (int i = 0; i < traceIds.size(); i += step) {
            List<String> chunk = traceIds.subList(i, Math.min(traceIds.size(), i + step));
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SpanEventEntity> query =
                    new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
            query.select("trace_id",
                            "COALESCE(SUM(input_tokens), 0) AS input_tokens",
                            "COALESCE(SUM(output_tokens), 0) AS output_tokens",
                            "COALESCE(SUM(cached_input_tokens), 0) AS cached_input_tokens")
                    .eq("workspace_id", workspaceId)
                    .in("trace_id", chunk)
                    .groupBy("trace_id");
            for (java.util.Map<String, Object> row : spanEventMapper.selectMaps(query)) {
                Object traceId = mapValue(row, "trace_id");
                if (traceId == null) {
                    continue;
                }
                long[] v = result.computeIfAbsent(String.valueOf(traceId), k -> new long[3]);
                v[0] += asLong(mapValue(row, "input_tokens"));
                v[1] += asLong(mapValue(row, "output_tokens"));
                v[2] += asLong(mapValue(row, "cached_input_tokens"));
            }
        }
        return result;
    }

    private static long asLong(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    @Override
    public List<java.util.Map<String, Object>> sumUsageByModel(String workspaceId, LocalDateTime since) {
        return spanEventMapper.sumUsageByModel(workspaceId, since);
    }

    @Override
    public java.util.Map<String, Long> countByTraceIds(String workspaceId, List<String> traceIds) {
        java.util.Map<String, Long> result = new java.util.HashMap<>();
        if (traceIds == null || traceIds.isEmpty()) {
            return result;
        }
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<SpanEventEntity> query =
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<>();
        query.select("trace_id", "COUNT(*) AS cnt")
                .eq("workspace_id", workspaceId)
                .in("trace_id", traceIds)
                .groupBy("trace_id");
        for (java.util.Map<String, Object> row : spanEventMapper.selectMaps(query)) {
            Object traceId = mapValue(row, "trace_id");
            Object count = mapValue(row, "cnt");
            if (traceId != null && count instanceof Number) {
                result.put(String.valueOf(traceId), ((Number) count).longValue());
            }
        }
        return result;
    }

    /** selectMaps 的键在 H2 是大写、MySQL 保持原样：大小写不敏感取值 */
    private static Object mapValue(java.util.Map<String, Object> row, String key) {
        if (row.containsKey(key)) {
            return row.get(key);
        }
        for (java.util.Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    @Override
    public SpanPage<SpanEventEntity> search(SpanQuery q) {
        LambdaQueryWrapper<SpanEventEntity> wrapper = wrapper(q.getWorkspaceId())
                .eq(hasText(q.getAgentKey()), SpanEventEntity::getAgentKey, q.getAgentKey())
                .eq(hasText(q.getSpanType()), SpanEventEntity::getSpanType, q.getSpanType())
                .eq(hasText(q.getModelName()), SpanEventEntity::getModelName, q.getModelName())
                .eq(hasText(q.getStatus()), SpanEventEntity::getStatus, q.getStatus())
                .eq(hasText(q.getTraceId()), SpanEventEntity::getTraceId, q.getTraceId())
                .in(q.getSpanTypes() != null && !q.getSpanTypes().isEmpty(),
                        SpanEventEntity::getSpanType, q.getSpanTypes())
                .ge(q.getStartTimeFrom() != null, SpanEventEntity::getStartTime, q.getStartTimeFrom())
                .lt(q.getStartTimeTo() != null, SpanEventEntity::getStartTime, q.getStartTimeTo());
        if (hasText(q.getSpanNameLike())) {
            String kw = SpanEventStore.escapeLike(q.getSpanNameLike().trim());
            if (!kw.isEmpty()) {
                String pattern = "%" + kw + "%";
                wrapper.and(w -> w.apply("span_name LIKE {0} ESCAPE '!'", pattern)
                        .or().apply("tool_name LIKE {0} ESCAPE '!'", pattern));
            }
        }
        if (q.getMinDurationMs() != null) {
            wrapper.ge(SpanEventEntity::getDurationMs, q.getMinDurationMs());
        }
        if (q.isCountOnly()) {
            return SpanPage.of(List.of(), spanEventMapper.selectCount(wrapper));
        }
        wrapper.orderBy(true, !q.isOrderDesc(), SpanEventEntity::getStartTime);
        Page<SpanEventEntity> page = spanEventMapper.selectPage(new Page<>(q.getPageNumber(), q.getPageSize()), wrapper);
        return SpanPage.of(page.getRecords(), page.getTotal());
    }

    @Override
    public int purgeBefore(LocalDateTime threshold, int batchSize) {
        return spanEventMapper.delete(new LambdaQueryWrapper<SpanEventEntity>()
                .lt(SpanEventEntity::getIngestTime, threshold)
                .last("LIMIT " + Math.max(1, batchSize)));
    }

    @Override
    public int updateCosts(List<SpanEventEntity> spans) {
        if (spans == null || spans.isEmpty()) {
            return 0;
        }
        for (SpanEventEntity span : spans) {
            spanEventMapper.updateById(span);
        }
        return spans.size();
    }

    private LambdaQueryWrapper<SpanEventEntity> wrapper(String workspaceId) {
        LambdaQueryWrapper<SpanEventEntity> w = new LambdaQueryWrapper<>();
        if (hasText(workspaceId)) {
            w.eq(SpanEventEntity::getWorkspaceId, workspaceId);
        }
        return w;
    }

    private boolean hasText(String s) {
        return s != null && !s.isEmpty();
    }
}
