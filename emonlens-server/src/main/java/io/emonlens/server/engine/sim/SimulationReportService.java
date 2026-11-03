package io.emonlens.server.engine.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;

import lombok.RequiredArgsConstructor;

/**
 * 仿真会话报告（R3-1）：把一次 REPLAY_SIM run 的轮次、停止原因与三相成本汇总出来。
 *
 * <p>三相成本：simulation（模拟用户 LLM）、generation（被测 Agent，端点回报 usage 时才有）、
 * judge（评估器）。分开记账的原因很实际——仿真失败通常是"模拟用户太贵"或"Agent 太慢"，
 * 混成一个数字就查不出是谁的问题。</p>
 */
@Service
@RequiredArgsConstructor
public class SimulationReportService {

    private final DatasetRunItemMapper runItemMapper;
    private final ObjectMapper objectMapper;

    public Map<String, Object> report(DatasetRunEntity run) {
        List<DatasetRunItemEntity> items = runItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery()
                        .eq(DatasetRunItemEntity::getRunId, run.getId()));
        int turns = 0;
        int sessions = 0;
        int simulationCalls = 0;
        int simulationPromptTokens = 0;
        int simulationCompletionTokens = 0;
        int generationCalls = 0;
        int generationPromptTokens = 0;
        int generationCompletionTokens = 0;
        int judgeCalls = 0;
        int judgePromptTokens = 0;
        int judgeCompletionTokens = 0;
        Map<String, Integer> stopReasons = new LinkedHashMap<>();
        List<Map<String, Object>> itemRows = new ArrayList<>();
        for (DatasetRunItemEntity item : items) {
            Map<String, Object> details = parse(item.getResultDetailsJson());
            Map<String, Object> phases = asMap(details.get("usagePhases"));
            if (phases == null) {
                continue;
            }
            Map<String, Object> simPhase = asMap(phases.get("simulation"));
            Map<String, Object> genPhase = asMap(phases.get("generation"));
            Map<String, Object> judgePhase = asMap(phases.get("judge"));
            int itemTurns = intOf(phases.get("turns"));
            turns += itemTurns;
            sessions++;
            simulationCalls += intOf(simPhase == null ? null : simPhase.get("calls"));
            simulationPromptTokens += intOf(simPhase == null ? null : simPhase.get("promptTokens"));
            simulationCompletionTokens += intOf(simPhase == null ? null : simPhase.get("completionTokens"));
            generationCalls += intOf(genPhase == null ? null : genPhase.get("calls"));
            generationPromptTokens += intOf(genPhase == null ? null : genPhase.get("promptTokens"));
            generationCompletionTokens += intOf(genPhase == null ? null : genPhase.get("completionTokens"));
            judgeCalls += intOf(judgePhase == null ? null : judgePhase.get("calls"));
            judgePromptTokens += intOf(judgePhase == null ? null : judgePhase.get("promptTokens"));
            judgeCompletionTokens += intOf(judgePhase == null ? null : judgePhase.get("completionTokens"));
            String stopReason = phases.get("stopReason") == null ? "UNKNOWN" : String.valueOf(phases.get("stopReason"));
            stopReasons.merge(stopReason, 1, Integer::sum);

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemId", item.getItemId());
            row.put("label", item.getLabel());
            row.put("turns", itemTurns);
            row.put("stopReason", stopReason);
            row.put("sessionId", phases.get("sessionId"));
            row.put("traceIds", phases.get("traceIds"));
            itemRows.add(row);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("runMode", run.getRunMode());
        payload.put("protocol", "conversation-sim/1");
        payload.put("sessions", sessions);
        payload.put("turns", turns);
        payload.put("stopReasons", stopReasons);
        payload.put("items", itemRows);
        Map<String, Object> phases = new LinkedHashMap<>();
        phases.put("simulation", phase("模拟用户 LLM", simulationCalls, simulationPromptTokens,
                simulationCompletionTokens));
        phases.put("generation", phase("被测 Agent", generationCalls, generationPromptTokens,
                generationCompletionTokens));
        phases.put("judge", phase("评估器", judgeCalls, judgePromptTokens, judgeCompletionTokens));
        payload.put("costPhases", phases);
        payload.put("note", "generation 只有被测端点回报 usage 时才有 token 数；缺失不按 0 计而是少计入");
        return payload;
    }

    private Map<String, Object> phase(String title, int calls, int promptTokens, int completionTokens) {
        Map<String, Object> phase = new LinkedHashMap<>();
        phase.put("title", title);
        phase.put("calls", calls);
        phase.put("promptTokens", promptTokens);
        phase.put("completionTokens", completionTokens);
        return phase;
    }

    private Map<String, Object> parse(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map)) {
            return null;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
            map.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return map;
    }

    private int intOf(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
