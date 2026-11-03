package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.api.dto.PageVO;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 数据集批量导入/导出（CSV / JSONL）。
 *
 * <p>导入是“逐行校验 + 按 contentHash 幂等”：dryRun 只校验；atomic 全成或全败；
 * 非 atomic 写入合法行并返回行级错误。导出受 TRACE_CONTENT 控制，无权限时不带正文。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DatasetImportService {

    private static final int MAX_ROWS = 1000;
    private static final int MAX_CONTENT_CHARS = 5 * 1024 * 1024;

    private static final List<String> CSV_COLUMNS = List.of(
            "input", "expected_output", "retrieval_context", "expected_tools", "turns",
            "expectations", "metadata", "purpose_tags", "risk_level", "group_key",
            "split", "failure_mode", "trace_id", "source_type", "source_ref");

    private final DatasetItemMapper datasetItemMapper;
    private final EvaluationCaseService evaluationCaseService;
    private final DatasetHealthService datasetHealthService;
    private final ObjectMapper objectMapper;
    private final EmonLensProperties properties;
    private final AttachmentPolicy attachmentPolicy;

    @Data
    public static class ImportResult {
        private int total;
        private int created;
        /** 校验通过、可写入的行数；dry-run 下也返回，便于前端提示“可新建 N 条”。 */
        private int wouldCreate;
        private int duplicated;
        private int invalid;
        private boolean dryRun;
        private List<Map<String, Object>> errors = new ArrayList<>();
    }

    @Transactional
    public ImportResult importItems(String workspaceId, String datasetId, String format,
                                    String content, boolean dryRun, boolean atomic) {
        ImportResult result = new ImportResult();
        result.setDryRun(dryRun);
        if (content == null || content.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "content is required");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "content 超过 " + MAX_CONTENT_CHARS + " 字符");
        }
        List<Map<String, Object>> rows = parse(format, content);
        result.setTotal(rows.size());
        if (rows.size() > MAX_ROWS) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "单次最多导入 " + MAX_ROWS + " 行，当前 " + rows.size());
        }
        int maxChars = properties.getDatasetRun().getMaxSnapshotChars();
        Set<String> seen = new LinkedHashSet<>();
        List<DatasetItemEntity> toInsert = new ArrayList<>();
        int rowNumber = 0;
        for (Map<String, Object> raw : rows) {
            rowNumber++;
            try {
                DatasetItemEntity item = toItem(datasetId, raw, maxChars);
                datasetHealthService.validateRiskLevel(workspaceId, datasetId, item.getRiskLevel());
                String hash = evaluationCaseService.hash(item);
                item.setContentHash(hash);
                if (hash != null && (!seen.add(hash) || exists(datasetId, hash))) {
                    result.setDuplicated(result.getDuplicated() + 1);
                    continue;
                }
                toInsert.add(item);
            } catch (Exception e) {
                result.setInvalid(result.getInvalid() + 1);
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("row", rowNumber);
                error.put("message", e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
                result.getErrors().add(error);
            }
        }
        if (atomic && !result.getErrors().isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "atomic 导入失败，" + result.getInvalid() + " 行非法：" + result.getErrors());
        }
        result.setWouldCreate(toInsert.size());
        if (!dryRun) {
            insertAll(toInsert);
            result.setCreated(toInsert.size());
        } else {
            result.setCreated(0);
        }
        log.info("dataset import finished, dataset={}, format={}, dryRun={}, created={}, duplicated={}, invalid={}",
                datasetId, format, dryRun, result.getCreated(), result.getDuplicated(), result.getInvalid());
        return result;
    }

    /** 在 importItems 的同一事务内执行；DB 写入失败时整批回滚，避免半批入库。 */
    private void insertAll(List<DatasetItemEntity> items) {
        for (DatasetItemEntity item : items) {
            datasetItemMapper.insert(item);
        }
    }

    /** 导出为 JSONL 或 CSV；contentAllowed=false 时不输出正文。 */
    /** 导出页大小：流式写出时每批读取的行数，内存占用与单批成正比。 */
    private static final int EXPORT_PAGE_SIZE = 500;

    /**
     * 流式导出：按页读取样本、逐行写出，避免大样本集把全量正文一次性驻留内存。
     * 行格式与历史导出完全一致（CSV 带 BOM 表头 / JSONL），调用方负责设置响应头。
     */
    public void writeItems(java.io.Writer out, String datasetId, String format, boolean contentAllowed)
            throws java.io.IOException {
        boolean csv = "csv".equalsIgnoreCase(format);
        if (csv) {
            out.write('\uFEFF');
            out.write(String.join(",", CSV_COLUMNS));
            out.write('\n');
        }
        int pageNumber = 1;
        while (true) {
            Page<DatasetItemEntity> page = datasetItemMapper.selectPage(
                    PageVO.safePage(pageNumber, EXPORT_PAGE_SIZE),
                    Wrappers.<DatasetItemEntity>lambdaQuery()
                            .eq(DatasetItemEntity::getDatasetId, datasetId)
                            .orderByAsc(DatasetItemEntity::getCreatedAt));
            List<DatasetItemEntity> records = page.getRecords();
            for (DatasetItemEntity item : records) {
                writeRow(out, item, csv, contentAllowed);
            }
            if (records.size() < EXPORT_PAGE_SIZE) {
                break;
            }
            pageNumber++;
        }
        out.flush();
    }

    private void writeRow(java.io.Writer out, DatasetItemEntity item, boolean csv, boolean contentAllowed)
            throws java.io.IOException {
        Map<String, Object> row = exportRow(item, contentAllowed);
        if (csv) {
            List<String> cells = new ArrayList<>();
            for (String column : CSV_COLUMNS) {
                cells.add(csvCell(row.get(column)));
            }
            out.write(String.join(",", cells));
        } else {
            try {
                out.write(objectMapper.writeValueAsString(row));
            } catch (Exception e) {
                throw new EmonLensException(ErrorCode.INTERNAL_ERROR, "export serialization failed");
            }
        }
        out.write('\n');
    }

    private Map<String, Object> exportRow(DatasetItemEntity item, boolean contentAllowed) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", item.getId());
        row.put("schema_version", item.getSchemaVersion() == null ? 1 : item.getSchemaVersion());
        row.put("status", item.getStatus());
        row.put("purpose_tags", item.getPurposeTags());
        row.put("risk_level", item.getRiskLevel());
        row.put("group_key", item.getGroupKey());
        row.put("split", item.getSplit());
        row.put("failure_mode", item.getFailureMode());
        row.put("content_hash", item.getContentHash());
        row.put("source_type", item.getSourceType());
        row.put("source_ref", item.getSourceRef());
        if (contentAllowed) {
            row.put("input", item.getInput());
            row.put("expected_output", item.getExpectedOutput());
            row.put("trace_id", item.getTraceId());
            row.put("retrieval_context", evaluationCaseService.readForResponse(item.getRetrievalContextJson()));
            row.put("expected_tools", evaluationCaseService.readForResponse(item.getExpectedToolsJson()));
            row.put("turns", evaluationCaseService.readForResponse(item.getTurnsJson()));
            row.put("expectations", evaluationCaseService.readForResponse(item.getExpectationsJson()));
            row.put("metadata", evaluationCaseService.readForResponse(item.getMetadataJson()));
            // R3-4：附件与正文同口径——有 TRACE_CONTENT 导原文，没有则 base64 换 sha256 占位
            row.put("attachments", contentAllowed
                    ? attachmentPolicy.parse(item.getAttachmentsJson())
                    : attachmentPolicy.redacted(item.getAttachmentsJson()));
        }
        return row;
    }

    private List<Map<String, Object>> parse(String format, String content) {
        if ("jsonl".equalsIgnoreCase(format)) {
            return parseJsonl(content);
        }
        if ("csv".equalsIgnoreCase(format)) {
            return parseCsv(content);
        }
        throw new EmonLensException(ErrorCode.BAD_REQUEST, "format must be csv or jsonl");
    }

    private List<Map<String, Object>> parseJsonl(String content) {
        List<Map<String, Object>> rows = new ArrayList<>();
        String[] lines = content.split("\\r?\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) {
                continue;
            }
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> row = objectMapper.readValue(line, Map.class);
                rows.add(row);
            } catch (Exception e) {
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "JSONL 第 " + (i + 1) + " 行不是合法 JSON: " + e.getMessage());
            }
        }
        return rows;
    }

    private List<Map<String, Object>> parseCsv(String content) {
        List<List<String>> table = parseCsvRows(content);
        if (table.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> headers = new ArrayList<>(table.get(0));
        // 导出 CSV 带 UTF-8 BOM（Excel 兼容）；回导时必须去掉，否则首列表头会变成 \uFEFFinput
        if (!headers.isEmpty() && headers.get(0) != null) {
            headers.set(0, headers.get(0).replace("\uFEFF", "").trim());
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i < table.size(); i++) {
            List<String> values = table.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            boolean empty = true;
            for (int c = 0; c < headers.size(); c++) {
                String value = c < values.size() ? values.get(c) : "";
                if (!value.isEmpty()) {
                    empty = false;
                }
                row.put(headers.get(c).trim(), value);
            }
            if (!empty) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** RFC4180 最小解析：支持引号、转义双引号、字段内换行。 */
    private List<List<String>> parseCsvRows(String content) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < content.length() && content.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
                continue;
            }
            if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < content.length() && content.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    private DatasetItemEntity toItem(String datasetId, Map<String, Object> raw, int maxChars) {
        String input = text(raw, "input");
        String expected = text(raw, "expected_output", "expectedOutput");
        String metadata = jsonField(raw, "metadata", "metadataJson");
        String retrieval = jsonField(raw, "retrieval_context", "retrievalContext");
        String expectedTools = jsonField(raw, "expected_tools", "expectedTools");
        String turns = jsonField(raw, "turns");
        String expectations = jsonField(raw, "expectations");
        String attachments = jsonField(raw, "attachments");
        String traceId = text(raw, "trace_id", "traceId");
        if ((traceId == null || traceId.isBlank()) && (input == null || input.isBlank())) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "trace_id 或 input 至少填一项");
        }
        boolean hasV2 = EvaluationCaseService.hasV2Fields(retrieval, expectedTools, turns, expectations);
        int schemaVersion = evaluationCaseService.effectiveSchemaVersion(
                integer(raw, "schema_version", "schemaVersion"), hasV2);
        evaluationCaseService.validate(schemaVersion, input, expected, metadata,
                retrieval, expectedTools, turns, expectations, maxChars);

        DatasetItemEntity item = new DatasetItemEntity();
        item.setDatasetId(datasetId);
        item.setTraceId(traceId);
        item.setInput(input);
        item.setExpectedOutput(expected);
        item.setMetadataJson(metadata);
        item.setSchemaVersion(schemaVersion);
        item.setRetrievalContextJson(retrieval);
        item.setExpectedToolsJson(expectedTools);
        item.setTurnsJson(turns);
        item.setExpectationsJson(expectations);
        item.setAttachmentsJson(attachmentPolicy.normalize(attachments));
        item.setPurposeTags(text(raw, "purpose_tags", "purposeTags"));
        item.setRiskLevel(defaultIfBlank(text(raw, "risk_level", "riskLevel"), "UNASSIGNED"));
        item.setGroupKey(text(raw, "group_key", "groupKey"));
        item.setSplit(defaultIfBlank(text(raw, "split"), "UNASSIGNED"));
        item.setFailureMode(text(raw, "failure_mode", "failureMode"));
        item.setSourceType("IMPORT");
        item.setSourceRef("import");
        item.setStatus("ACTIVE");
        item.setCreatedAt(LocalDateTime.now());
        return item;
    }

    private boolean exists(String datasetId, String hash) {
        Long count = datasetItemMapper.selectCount(Wrappers.<DatasetItemEntity>lambdaQuery()
                .eq(DatasetItemEntity::getDatasetId, datasetId)
                .eq(DatasetItemEntity::getContentHash, hash)
                // 已软失效样本不算重复：导入应允许重新引入为 ACTIVE
                .and(w -> w.eq(DatasetItemEntity::getStatus, "ACTIVE")
                        .or().isNull(DatasetItemEntity::getStatus)));
        return count != null && count > 0;
    }

    private String jsonField(Map<String, Object> raw, String... keys) {
        Object value = first(raw, keys);
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String text = (String) value;
            return text.isBlank() ? null : text;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, keys[0] + " 序列化失败");
        }
    }

    private String text(Map<String, Object> raw, String... keys) {
        Object value = first(raw, keys);
        return value == null ? null : String.valueOf(value);
    }

    private Integer integer(Map<String, Object> raw, String... keys) {
        Object value = first(raw, keys);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, keys[0] + " 必须是整数");
        }
    }

    private Object first(Map<String, Object> raw, String... keys) {
        for (String key : keys) {
            if (raw.containsKey(key)) {
                Object value = raw.get(key);
                if (value != null && !(value instanceof String && ((String) value).isBlank())) {
                    return value;
                }
            }
        }
        return null;
    }

    private String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String csvCell(Object value) {
        if (value == null) {
            return "";
        }
        String text;
        if (value instanceof String) {
            text = (String) value;
        } else {
            try {
                text = objectMapper.writeValueAsString(value);
            } catch (Exception e) {
                text = String.valueOf(value);
            }
        }
        if (text.indexOf(',') >= 0 || text.indexOf('"') >= 0
                || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
