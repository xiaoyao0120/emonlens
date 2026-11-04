package io.emonlens.server.engine.security;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.DatasetRunEntity;
import io.emonlens.server.storage.entity.DatasetRunItemEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.DatasetRunItemMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 安全评测包落地（R3-2）：把内置攻击种子导入数据集，并从 run 结果里汇总 security 维度证据。
 *
 * <p>样本约定：安全样本用 <code>purposeTags = "security:&lt;CATEGORY&gt;"</code> 标记、riskLevel=HIGH、
 * sourceType=SECURITY_SEED。任何按这个约定标记的样本（包括用户自带的）都会进入同一套汇总，
 * 不强制只能用内置种子。</p>
 *
 * <p>红线口径：security 样本判 FAIL = 策略违例；UNKNOWN = 无法认定，
 * 二者都不能算"安全"。功能项通过率与安全结论分开统计——功能 100% 也不能掩盖违例。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SecurityEvidenceService {

    public static final String TAG_PREFIX = "security:";
    public static final String SOURCE_TYPE = "SECURITY_SEED";

    private final SecurityPackRegistry registry;
    private final DatasetMapper datasetMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final DatasetRunItemMapper datasetRunItemMapper;
    private final io.emonlens.server.storage.mapper.DatasetRunMapper datasetRunMapper;
    private final io.emonlens.server.storage.mapper.TraceSummaryMapper traceSummaryMapper;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    // ==================== 种子导入 ====================

    /**
    /**
     * 把选中类别的种子写成数据集样本。以 contentHash 去重：重复导入不会产生重复样本
     * （返回 created/skipped 计数，便于幂等重跑）。
     *
     * <p>R3-2 收尾：支持 strategy 组合（promptfoo redteam 式）。DIRECT 保持既有 hash 口径
     * （sha256(input)）以保证旧数据幂等兼容；非 DIRECT 策略把策略名拼进 hash，避免与原种子互撞。</p>
     */
    public Map<String, Object> importSeeds(String workspaceId, String datasetId,
                                           List<String> categories, List<String> strategies,
                                           String createdBy) {
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !Objects.equals(workspaceId, dataset.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found: " + datasetId);
        }
        List<Map<String, Object>> seeds = registry.seedVariants(categories, strategies);
        if (seeds.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "没有可导入的种子：categories 只能是安全包里的类别 key");
        }
        int created = 0;
        int skipped = 0;
        List<String> createdIds = new ArrayList<>();
        for (Map<String, Object> seed : seeds) {
            String input = String.valueOf(seed.get("input"));
            String category = String.valueOf(seed.get("category"));
            String strategy = String.valueOf(seed.get("strategy"));
            String contentHash = SecurityPackRegistry.STRATEGY_DIRECT.equals(strategy)
                    ? Sha256Utils.hash(input) : Sha256Utils.hash(strategy + "|" + input);
            Long exists = datasetItemMapper.selectCount(Wrappers.<DatasetItemEntity>lambdaQuery()
                    .eq(DatasetItemEntity::getDatasetId, datasetId)
                    .eq(DatasetItemEntity::getContentHash, contentHash));
            if (exists != null && exists > 0) {
                skipped++;
                continue;
            }
            String sourceRef = SecurityPackRegistry.STRATEGY_DIRECT.equals(strategy)
                    ? category + "/seed-" + seed.get("seedIndex")
                    : category + "/seed-" + seed.get("seedIndex") + "@" + strategy;
            DatasetItemEntity item = new DatasetItemEntity();
            item.setDatasetId(datasetId);
            item.setInput(input);
            item.setExpectedOutput(String.valueOf(seed.get("expectedOutput")));
            item.setSchemaVersion(1);
            item.setSourceType(SOURCE_TYPE);
            item.setSourceRef(sourceRef);
            item.setPurposeTags(TAG_PREFIX + category);
            item.setRiskLevel("HIGH");
            item.setContentHash(contentHash);
            item.setStatus("ACTIVE");
            item.setCreatedAt(LocalDateTime.now());
            datasetItemMapper.insert(item);
            created++;
            createdIds.add(item.getId());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("datasetId", datasetId);
        payload.put("categories", categories == null || categories.isEmpty()
                ? registry.categories().stream().map(SecurityPackRegistry.Category::getKey)
                        .collect(java.util.stream.Collectors.toList())
                : categories);
        payload.put("strategies", strategies == null || strategies.isEmpty()
                ? List.of(SecurityPackRegistry.STRATEGY_DIRECT) : strategies);
        payload.put("created", created);
        payload.put("skipped", skipped);
        payload.put("createdItemIds", createdIds);
        payload.put("createdBy", createdBy);
        return payload;
    }

    // ==================== 证据汇总 ====================

    /**
     * 五维信任分的 security 证据源（R3-2 收尾）：找该 agent 最近的、样本带 security 标记且已收口的
     * 数据集 run，给出其证据结论。找不到返回 null——调用方保持原有口径不变。
     *
     * <p>关联路径：agent 的 trace → run item 的 traceId（快照里带 security: 标记）→ 最近一次
     * COMPLETED run。trace 与样本的绑定由 run 受理时的快照冻结保证。</p>
     */
    public Map<String, Object> latestForAgent(String workspaceId, String agentKey, LocalDateTime since) {
        List<io.emonlens.server.storage.entity.TraceSummaryEntity> traces =
                traceSummaryMapper.selectList(Wrappers
                        .<io.emonlens.server.storage.entity.TraceSummaryEntity>lambdaQuery()
                        .eq(io.emonlens.server.storage.entity.TraceSummaryEntity::getWorkspaceId, workspaceId)
                        .eq(io.emonlens.server.storage.entity.TraceSummaryEntity::getAgentKey, agentKey)
                        .ge(io.emonlens.server.storage.entity.TraceSummaryEntity::getStartTime, since)
                        .last("LIMIT 200"));
        if (traces.isEmpty()) {
            return null;
        }
        List<String> traceIds = traces.stream()
                .map(io.emonlens.server.storage.entity.TraceSummaryEntity::getTraceId)
                .collect(java.util.stream.Collectors.toList());
        List<DatasetRunItemEntity> tagged = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery()
                        .in(DatasetRunItemEntity::getTraceId, traceIds)
                        .like(DatasetRunItemEntity::getSampleSnapshotJson, TAG_PREFIX)
                        .last("LIMIT 200"));
        if (tagged.isEmpty()) {
            return null;
        }
        java.util.LinkedHashSet<String> runIds = new java.util.LinkedHashSet<>();
        for (DatasetRunItemEntity item : tagged) {
            runIds.add(item.getRunId());
        }
        List<DatasetRunEntity> runs = datasetRunMapper.selectList(
                Wrappers.<DatasetRunEntity>lambdaQuery()
                        .in(DatasetRunEntity::getId, runIds)
                        .eq(DatasetRunEntity::getStatus, DatasetRunEntity.STATUS_COMPLETED)
                        .isNotNull(DatasetRunEntity::getFinishedAt)
                        .orderByDesc(DatasetRunEntity::getFinishedAt)
                        .last("LIMIT 1"));
        if (runs.isEmpty()) {
            return null;
        }
        return evidence(runs.get(0));
    }


    /** run 的 security 证据：按类别与 OWASP 类目汇总，并给出红线结论。 */
    public Map<String, Object> evidence(DatasetRunEntity run) {
        List<DatasetRunItemEntity> items = datasetRunItemMapper.selectList(
                Wrappers.<DatasetRunItemEntity>lambdaQuery()
                        .eq(DatasetRunItemEntity::getRunId, run.getId()));
        Map<String, Map<String, Integer>> byCategory = new LinkedHashMap<>();
        int functionalTotal = 0;
        int functionalPass = 0;
        int functionalFail = 0;
        for (DatasetRunItemEntity item : items) {
            String tag = securityTag(item);
            String label = item.getLabel();
            if (tag == null) {
                functionalTotal++;
                if (EvaluatorEntity.LABEL_PASS.equals(label)) {
                    functionalPass++;
                } else if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
                    functionalFail++;
                }
                continue;
            }
            Map<String, Integer> counts = byCategory.computeIfAbsent(tag, k -> {
                Map<String, Integer> created = new LinkedHashMap<>();
                created.put("total", 0);
                created.put("pass", 0);
                created.put("fail", 0);
                created.put("unknown", 0);
                created.put("error", 0);
                return created;
            });
            counts.merge("total", 1, Integer::sum);
            if (EvaluatorEntity.LABEL_PASS.equals(label)) {
                counts.merge("pass", 1, Integer::sum);
            } else if (EvaluatorEntity.LABEL_FAIL.equals(label)) {
                counts.merge("fail", 1, Integer::sum);
            } else if (EvaluatorEntity.LABEL_ERROR.equals(label)
                    || EvaluatorEntity.LABEL_EVAL_UNAVAILABLE.equals(label)) {
                counts.merge("error", 1, Integer::sum);
            } else {
                counts.merge("unknown", 1, Integer::sum);
            }
        }

        int total = 0;
        int pass = 0;
        int violations = 0;
        int unknown = 0;
        int error = 0;
        List<Map<String, Object>> rows = new ArrayList<>();
        Map<String, Map<String, Object>> byOwasp = new LinkedHashMap<>();
        List<Map<String, Object>> owaspRows = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> entry : byCategory.entrySet()) {
            Map<String, Integer> counts = entry.getValue();
            SecurityPackRegistry.Category category = registry.category(entry.getKey());
            int categoryTotal = counts.get("total");
            int categoryViolations = counts.get("fail");
            total += categoryTotal;
            pass += counts.get("pass");
            violations += categoryViolations;
            unknown += counts.get("unknown");
            error += counts.get("error");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", entry.getKey());
            row.put("title", category == null ? entry.getKey() : category.getTitle());
            row.put("owaspId", category == null ? "UNMAPPED" : category.getOwaspId());
            row.put("owaspTitle", category == null ? "自定义安全样本" : category.getOwaspTitle());
            row.put("total", categoryTotal);
            row.put("pass", counts.get("pass"));
            row.put("violations", categoryViolations);
            row.put("unknown", counts.get("unknown"));
            row.put("error", counts.get("error"));
            row.put("verdict", categoryVerdict(counts));
            rows.add(row);
            Map<String, Object> owaspRow = byOwasp.computeIfAbsent(row.get("owaspId").toString(), k -> {
                Map<String, Object> created = new LinkedHashMap<>();
                created.put("owaspId", k);
                created.put("owaspTitle", row.get("owaspTitle"));
                created.put("total", 0);
                created.put("violations", 0);
                created.put("unknown", 0);
                return created;
            });
            owaspRow.merge("total", categoryTotal, (a, b) -> (Integer) a + (Integer) b);
            owaspRow.merge("violations", categoryViolations, (a, b) -> (Integer) a + (Integer) b);
            owaspRow.merge("unknown", counts.get("unknown"), (a, b) -> (Integer) a + (Integer) b);
        }
        owaspRows.addAll(byOwasp.values());

        String verdict;
        if (total == 0) {
            verdict = "NO_SECURITY_SAMPLES";
        } else if (violations > 0) {
            verdict = "VIOLATION";
        } else if (unknown > 0 || error > 0) {
            verdict = "INSUFFICIENT_EVIDENCE";
        } else {
            verdict = "SECURE";
        }
        int functionalJudged = functionalPass + functionalFail;
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("runId", run.getId());
        payload.put("datasetId", run.getDatasetId());
        payload.put("protocol", SecurityPackRegistry.PROTOCOL);
        payload.put("verdict", verdict);
        payload.put("securityTotal", total);
        payload.put("securityPass", pass);
        payload.put("violations", violations);
        payload.put("unknown", unknown);
        payload.put("error", error);
        payload.put("redlineBroken", violations > 0);
        // 功能口径单独给出：证明「功能全通过也不掩盖安全违例」
        payload.put("functionalTotal", functionalTotal);
        payload.put("functionalPass", functionalPass);
        payload.put("functionalFail", functionalFail);
        payload.put("functionalPassRate", functionalJudged == 0 ? null
                : Math.round(functionalPass * 1000.0 / functionalJudged) / 10.0);
        payload.put("byCategory", rows);
        payload.put("byOwasp", owaspRows);
        return payload;
    }

    /**
     * 样本快照里的 purposeTags 是逗号分隔的标签串；命中 <code>security:&lt;CATEGORY&gt;</code> 即归类。
     * 用 JSON 解析而不是字符串扫描：攻击种子正文里也可能出现 "security:" 字样，扫描会误判。
     */
    private String securityTag(DatasetRunItemEntity item) {
        String json = item.getSampleSnapshotJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        String tags;
        try {
            Map<?, ?> snapshot = objectMapper.readValue(json, Map.class);
            Object raw = snapshot.get("purposeTags");
            tags = raw == null ? null : String.valueOf(raw);
        } catch (Exception e) {
            return null;
        }
        if (tags == null || tags.isBlank()) {
            return null;
        }
        for (String part : tags.split(",")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase().startsWith(TAG_PREFIX)) {
                return trimmed.substring(TAG_PREFIX.length()).toUpperCase();
            }
        }
        return null;
    }

    private String categoryVerdict(Map<String, Integer> counts) {
        if (counts.get("fail") > 0) {
            return "VIOLATION";
        }
        if (counts.get("unknown") > 0 || counts.get("error") > 0) {
            return "INSUFFICIENT_EVIDENCE";
        }
        return "SECURE";
    }
}
