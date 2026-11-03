package io.emonlens.server.engine.feedback;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.eval.EvaluationCaseService;
import io.emonlens.server.engine.finding.FindingService;
import io.emonlens.server.storage.entity.DatasetEntity;
import io.emonlens.server.storage.entity.DatasetItemEntity;
import io.emonlens.server.storage.entity.EndUserFeedbackEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.FeedbackTokenEntity;
import io.emonlens.server.storage.entity.FindingEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.DatasetItemMapper;
import io.emonlens.server.storage.mapper.DatasetMapper;
import io.emonlens.server.storage.mapper.EndUserFeedbackMapper;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.FeedbackTokenMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 端用户反馈采集（R2-2）。
 *
 * <p>安全模型：平台为某条 trace 预签一个 token，只授予「给这条 trace 写一条反馈」的能力——
 * 读不到正文、没有其它 scope、可限次数、可过期、可吊销。库里只存 token 的 sha256。</p>
 *
 * <p>数据模型：score 类型学 NUMERIC / CATEGORICAL / BOOLEAN / CORRECTION。四类都会落一条
 * {@code et_eval_result}（evaluatorKey=end-user-feedback），因此既有监控与质量视图直接可用；
 * 类型化明细留在 {@code et_end_user_feedback}，correction 可以一键转成数据集样本。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndUserFeedbackService {

    public static final String EVALUATOR_KEY = "end-user-feedback";
    /** 数值反馈与人工评分同口径：0–10，>=6 记为 PASS */
    private static final double NUMERIC_PASS_THRESHOLD = 6.0;
    private static final int MAX_COMMENT = 1000;
    private static final int MAX_CORRECTION = 2000;
    private static final int MAX_TEXT = 200;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FeedbackTokenMapper tokenMapper;
    private final EndUserFeedbackMapper feedbackMapper;
    private final TraceSummaryMapper traceSummaryMapper;
    private final EvalResultMapper evalResultMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetItemMapper datasetItemMapper;
    private final EvaluationCaseService evaluationCaseService;
    private final FindingService findingService;
    private final EmonLensProperties properties;
    private final ObjectMapper objectMapper;

    /** 每 token 的滑动窗口限流（单实例内存；多实例时各实例独立计数，仍能挡住脚本刷量） */
    private final ConcurrentHashMap<String, List<Long>> rateWindow = new ConcurrentHashMap<>();

    // ==================== 请求体 ====================

    @Data
    public static class IssueRequest {
        private Integer maxUses;
        private Integer expiresInMinutes;
    }

    @Data
    public static class SubmitRequest {
        /** NUMERIC / CATEGORICAL / BOOLEAN / CORRECTION */
        private String kind;
        private Double valueNum;
        private String valueText;
        private String correction;
        private String comment;
        /** 调用方自己的匿名用户标识（不含 PII；平台只原样保存） */
        private String userRef;
    }

    // ==================== 内部：签发 / 吊销 / 查询 ====================

    public Map<String, Object> issue(String workspaceId, String traceId, IssueRequest request,
                                     String createdBy) {
        TraceSummaryEntity summary = requireTrace(workspaceId, traceId);
        int maxUses = request == null || request.getMaxUses() == null
                ? properties.getFeedback().getTokenMaxUses() : request.getMaxUses();
        if (maxUses < 1 || maxUses > 100) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "maxUses 只允许 1..100");
        }
        int minutes = request == null || request.getExpiresInMinutes() == null
                ? properties.getFeedback().getTokenExpiresMinutes() : request.getExpiresInMinutes();
        if (minutes < 0 || minutes > 60 * 24 * 365) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "expiresInMinutes 只允许 0..525600（0=已过期）");
        }
        String token = "fb_" + randomHex(24);
        FeedbackTokenEntity entity = new FeedbackTokenEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setTraceId(summary.getTraceId());
        entity.setTokenHash(Sha256Utils.hash(token));
        entity.setMaxUses(maxUses);
        entity.setUsedCount(0);
        entity.setExpiresAt(LocalDateTime.now().plusMinutes(minutes));
        entity.setCreatedBy(createdBy);
        entity.setCreatedAt(LocalDateTime.now());
        tokenMapper.insert(entity);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("token", token); // 只在这个响应里出现一次，之后无法再取明文
        payload.put("tokenId", entity.getId());
        payload.put("traceId", entity.getTraceId());
        payload.put("maxUses", entity.getMaxUses());
        payload.put("expiresAt", entity.getExpiresAt());
        payload.put("endpoint", "/api/v1/feedback/" + token);
        return payload;
    }

    public Map<String, Object> revoke(String workspaceId, String traceId, String tokenId) {
        FeedbackTokenEntity entity = requireTokenEntity(workspaceId, traceId, tokenId);
        if (entity.getRevokedAt() == null) {
            entity.setRevokedAt(LocalDateTime.now());
            tokenMapper.updateById(entity);
        }
        return tokenView(entity, false);
    }

    public List<Map<String, Object>> list(String workspaceId, String traceId) {
        requireTrace(workspaceId, traceId);
        List<EndUserFeedbackEntity> rows = feedbackMapper.selectList(
                Wrappers.<EndUserFeedbackEntity>lambdaQuery()
                        .eq(EndUserFeedbackEntity::getWorkspaceId, workspaceId)
                        .eq(EndUserFeedbackEntity::getTraceId, traceId)
                        .orderByDesc(EndUserFeedbackEntity::getCreatedAt));
        List<Map<String, Object>> out = new ArrayList<>();
        for (EndUserFeedbackEntity row : rows) {
            out.add(feedbackView(row));
        }
        return out;
    }

    /** 公开：token 状态（不含任何 trace 内容，调用方只能判断能不能继续提交）。 */
    public Map<String, Object> status(String token) {
        FeedbackTokenEntity entity = findByToken(token);
        return tokenView(entity, true);
    }

    // ==================== 公开：提交反馈 ====================

    public Map<String, Object> submit(String token, SubmitRequest request) {
        FeedbackTokenEntity entity = findByToken(token);
        validateUsable(entity);
        enforceRateLimit(entity);
        if (request == null) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "feedback body is required");
        }
        Normalized normalized = normalize(request);
        EndUserFeedbackEntity feedback = new EndUserFeedbackEntity();
        feedback.setWorkspaceId(entity.getWorkspaceId());
        feedback.setTraceId(entity.getTraceId());
        feedback.setTokenId(entity.getId());
        feedback.setKind(normalized.kind);
        feedback.setValueNum(normalized.valueNum);
        feedback.setValueText(normalized.valueText);
        feedback.setCorrection(normalized.correction);
        feedback.setComment(truncate(request.getComment(), MAX_COMMENT));
        feedback.setUserRef(truncate(request.getUserRef(), 128));
        feedback.setSource(EndUserFeedbackEntity.SOURCE_END_USER);
        feedback.setLabel(normalized.label);
        feedback.setScore(normalized.score);
        feedback.setCreatedAt(LocalDateTime.now());
        feedbackMapper.insert(feedback);

        entity.setUsedCount(entity.getUsedCount() == null ? 1 : entity.getUsedCount() + 1);
        entity.setLastUsedAt(LocalDateTime.now());
        tokenMapper.updateById(entity);

        writeEvalResult(entity, feedback);
        maybeRaiseFinding(entity, feedback);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accepted", true);
        payload.put("feedbackId", feedback.getId());
        payload.put("label", normalized.label);
        payload.put("remainingUses", Math.max(0, entity.getMaxUses() - entity.getUsedCount()));
        return payload;
    }

    // ==================== 内部：correction → 数据集 ====================

    /**
     * 把带 correction 的反馈收录为数据集样本：输入取 trace 的输入预览，期望输出取用户纠正内容，
     * 走与手动新增样本完全相同的契约校验（超长/非法字段直接 400，不静默截断）。
     */
    public Map<String, Object> promote(String workspaceId, String traceId, String feedbackId,
                                       String datasetId, String createdBy) {
        TraceSummaryEntity summary = requireTrace(workspaceId, traceId);
        EndUserFeedbackEntity feedback = feedbackMapper.selectById(feedbackId);
        if (feedback == null || !workspaceId.equals(feedback.getWorkspaceId())
                || !traceId.equals(feedback.getTraceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "feedback not found");
        }
        if (feedback.getCorrection() == null || feedback.getCorrection().isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "只有 correction 类反馈可以收录为样本（该反馈没有纠正内容）");
        }
        DatasetEntity dataset = datasetMapper.selectById(datasetId);
        if (dataset == null || !workspaceId.equals(dataset.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "dataset not found: " + datasetId);
        }
        String input = summary.getInputPreview();
        if (input == null || input.isBlank()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST,
                    "trace 没有输入预览，无法作为样本输入；请在样本里手工补充 input");
        }
        evaluationCaseService.validate(EvaluationCaseService.SCHEMA_V1, input,
                feedback.getCorrection(), null, null, null, null, null,
                properties.getDatasetRun().getMaxSnapshotChars());

        DatasetItemEntity item = new DatasetItemEntity();
        item.setDatasetId(dataset.getId());
        item.setTraceId(traceId);
        item.setInput(input);
        item.setExpectedOutput(feedback.getCorrection());
        item.setSchemaVersion(EvaluationCaseService.SCHEMA_V1);
        // et_dataset_item.source_type 是 VARCHAR(16)：这里用短标识，完整来源见 source_ref
        item.setSourceType("USER_FEEDBACK");
        item.setSourceRef("feedback:" + feedback.getId());
        item.setStatus("ACTIVE");
        item.setCreatedAt(LocalDateTime.now());
        datasetItemMapper.insert(item);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("itemId", item.getId());
        payload.put("datasetId", dataset.getId());
        payload.put("traceId", traceId);
        payload.put("feedbackId", feedback.getId());
        payload.put("expectedOutputLength", feedback.getCorrection().length());
        payload.put("createdBy", createdBy);
        return payload;
    }

    // ==================== 归一与校验 ====================

    private static class Normalized {
        private String kind;
        private BigDecimal valueNum;
        private String valueText;
        private String correction;
        private String label;
        private BigDecimal score;
    }

    private Normalized normalize(SubmitRequest request) {
        String kind = request.getKind() == null ? "" : request.getKind().trim().toUpperCase();
        Normalized n = new Normalized();
        n.kind = kind;
        switch (kind) {
            case EndUserFeedbackEntity.KIND_NUMERIC: {
                if (request.getValueNum() == null) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "NUMERIC 需要 valueNum（0..10）");
                }
                double value = Math.max(0, Math.min(10, request.getValueNum()));
                n.valueNum = BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
                n.score = n.valueNum;
                n.label = value >= NUMERIC_PASS_THRESHOLD ? "PASS" : "FAIL";
                break;
            }
            case EndUserFeedbackEntity.KIND_BOOLEAN: {
                Boolean value = parseBoolean(request.getValueText());
                if (value == null) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST,
                            "BOOLEAN 需要 valueText=true/false（或 up/down）");
                }
                n.valueText = value ? "true" : "false";
                n.label = value ? "PASS" : "FAIL";
                n.score = value ? BigDecimal.valueOf(10) : BigDecimal.ZERO;
                break;
            }
            case EndUserFeedbackEntity.KIND_CATEGORICAL: {
                String text = truncate(request.getValueText(), MAX_TEXT);
                if (text == null || text.isBlank()) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "CATEGORICAL 需要 valueText");
                }
                n.valueText = text;
                n.label = categoricalLabel(text);
                n.score = "PASS".equals(n.label) ? BigDecimal.valueOf(10)
                        : "FAIL".equals(n.label) ? BigDecimal.ZERO : null;
                break;
            }
            case EndUserFeedbackEntity.KIND_CORRECTION: {
                String correction = truncate(request.getCorrection(), MAX_CORRECTION);
                if (correction == null || correction.isBlank()) {
                    throw new EmonLensException(ErrorCode.BAD_REQUEST, "CORRECTION 需要 correction 内容");
                }
                n.correction = correction;
                // 用户专门写出正确输出 = 本次回答错了：记 FAIL，但不把纠正文本写进 reason（正文受内容权限约束）
                n.label = "FAIL";
                n.score = BigDecimal.ZERO;
                break;
            }
            default:
                throw new EmonLensException(ErrorCode.BAD_REQUEST,
                        "kind 只支持 NUMERIC / CATEGORICAL / BOOLEAN / CORRECTION");
        }
        return n;
    }

    private Boolean parseBoolean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase();
        if (List.of("true", "1", "yes", "up", "thumbs_up", "good").contains(value)) {
            return Boolean.TRUE;
        }
        if (List.of("false", "0", "no", "down", "thumbs_down", "bad").contains(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * CATEGORICAL 判定：内置词表 + 配置词表（`emonlens.feedback.categorical-positive/negative`，
     * 逗号分隔）合并生效；都不命中才是 UNKNOWN。配置项让业务自己的评语不被迫落到 UNKNOWN。
     */
    private String categoricalLabel(String text) {
        String value = text.trim().toLowerCase();
        List<String> positives = new ArrayList<>(List.of("good", "great", "helpful", "positive",
                "满意", "好", "有用", "正确", "5", "4"));
        positives.addAll(splitWords(properties.getFeedback().getCategoricalPositive()));
        if (positives.contains(value)) {
            return "PASS";
        }
        List<String> negatives = new ArrayList<>(List.of("bad", "poor", "unhelpful", "negative",
                "不满意", "差", "无用", "错误", "1", "2"));
        negatives.addAll(splitWords(properties.getFeedback().getCategoricalNegative()));
        if (negatives.contains(value)) {
            return "FAIL";
        }
        return "UNKNOWN";
    }

    private List<String> splitWords(String raw) {
        List<String> words = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return words;
        }
        for (String word : raw.split(",")) {
            String trimmed = word.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                words.add(trimmed);
            }
        }
        return words;
    }

    // ==================== 内部：落评估结果 / 触发 Finding ====================

    /** 反馈同时进 et_eval_result，复用既有评估监控与质量视图（不新增数据通路）。 */
    private void writeEvalResult(FeedbackTokenEntity token, EndUserFeedbackEntity feedback) {
        TraceSummaryEntity summary = traceSummaryMapper.selectOne(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, token.getWorkspaceId())
                        .eq(TraceSummaryEntity::getTraceId, token.getTraceId()));
        EvalResultEntity result = new EvalResultEntity();
        result.setEvaluatorId(EVALUATOR_KEY);
        result.setEvaluatorKey(EVALUATOR_KEY);
        result.setEvaluatorVersion(1);
        result.setWorkspaceId(token.getWorkspaceId());
        result.setAgentKey(summary == null ? null : summary.getAgentKey());
        result.setAgentVersion(summary == null ? null : summary.getAgentVersion());
        result.setTraceId(token.getTraceId());
        result.setAttempt(nextAttempt(token.getTraceId()));
        result.setScore(feedback.getScore());
        result.setLabel(feedback.getLabel());
        result.setConfidence(BigDecimal.ONE);
        // reason 只写类型与是否有纠正，不写用户正文（正文权限与导出脱敏口径一致）
        result.setReason("end-user feedback: " + feedback.getKind()
                + (feedback.getCorrection() == null ? "" : " (with correction)"));
        result.setJudgeModel("end-user");
        result.setScope("TRACE");
        result.setCreatedAt(LocalDateTime.now());
        evalResultMapper.insert(result);
    }

    private int nextAttempt(String traceId) {
        EvalResultEntity last = evalResultMapper.selectOne(Wrappers.<EvalResultEntity>lambdaQuery()
                .eq(EvalResultEntity::getEvaluatorId, EVALUATOR_KEY)
                .eq(EvalResultEntity::getTraceId, traceId)
                .orderByDesc(EvalResultEntity::getAttempt)
                .last("LIMIT 1"));
        return last == null ? 1 : last.getAttempt() + 1;
    }

    /**
     * 负面反馈达到阈值才产生 Finding：只在「刚好跨过阈值」的那一次触发，
     * 避免每条差评都开一条 Finding 刷屏。阈值与窗口可配置。
     */
    private void maybeRaiseFinding(FeedbackTokenEntity token, EndUserFeedbackEntity feedback) {
        if (!"FAIL".equals(feedback.getLabel())) {
            return;
        }
        int threshold = Math.max(1, properties.getFeedback().getFindingThreshold());
        int windowHours = Math.max(1, properties.getFeedback().getFindingWindowHours());
        LocalDateTime since = LocalDateTime.now().minusHours(windowHours);
        Long negatives = feedbackMapper.selectCount(Wrappers.<EndUserFeedbackEntity>lambdaQuery()
                .eq(EndUserFeedbackEntity::getWorkspaceId, token.getWorkspaceId())
                .eq(EndUserFeedbackEntity::getTraceId, token.getTraceId())
                .eq(EndUserFeedbackEntity::getLabel, "FAIL")
                .ge(EndUserFeedbackEntity::getCreatedAt, since));
        if (negatives == null || negatives < threshold || negatives > threshold) {
            return;
        }
        TraceSummaryEntity summary = traceSummaryMapper.selectOne(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, token.getWorkspaceId())
                        .eq(TraceSummaryEntity::getTraceId, token.getTraceId()));
        FindingEntity finding = new FindingEntity();
        finding.setWorkspaceId(token.getWorkspaceId());
        finding.setAgentKey(summary == null ? null : summary.getAgentKey());
        finding.setAgentVersion(summary == null ? null : summary.getAgentVersion());
        finding.setTraceId(token.getTraceId());
        finding.setDimension("output");
        finding.setType("END_USER_FEEDBACK");
        finding.setSeverity(properties.getFeedback().getFindingSeverity());
        finding.setDetectorId("end-user-feedback");
        finding.setMessage("近 " + windowHours + " 小时收到 " + negatives + " 条负面端用户反馈（阈值 "
                + threshold + "），建议核查该回答并考虑收录为质量案例");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("negativeCount", negatives);
        evidence.put("threshold", threshold);
        evidence.put("windowHours", windowHours);
        evidence.put("lastFeedbackKind", feedback.getKind());
        try {
            finding.setEvidence(objectMapper.writeValueAsString(evidence));
        } catch (Exception e) {
            finding.setEvidence(null);
        }
        try {
            findingService.record(finding);
        } catch (Exception e) {
            log.error("end-user feedback finding failed, trace={}", token.getTraceId(), e);
        }
    }

    // ==================== 校验工具 ====================

    private FeedbackTokenEntity findByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new EmonLensException(ErrorCode.UNAUTHORIZED, "feedback token is required");
        }
        FeedbackTokenEntity entity = tokenMapper.selectOne(Wrappers.<FeedbackTokenEntity>lambdaQuery()
                .eq(FeedbackTokenEntity::getTokenHash, Sha256Utils.hash(token.trim())));
        if (entity == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "feedback token not found");
        }
        return entity;
    }

    private void validateUsable(FeedbackTokenEntity entity) {
        if (entity.getRevokedAt() != null) {
            throw new EmonLensException(ErrorCode.FORBIDDEN, "feedback token revoked");
        }
        if (entity.getExpiresAt() != null && !entity.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new EmonLensException(ErrorCode.FORBIDDEN, "feedback token expired");
        }
        if (entity.getUsedCount() != null && entity.getMaxUses() != null
                && entity.getUsedCount() >= entity.getMaxUses()) {
            throw new EmonLensException(ErrorCode.CONFLICT, "feedback token uses exhausted");
        }
    }

    /** 滑动 1 分钟窗口限流：同一 token 每分钟最多 N 次（N 可配置）。 */
    private void enforceRateLimit(FeedbackTokenEntity entity) {
        int limit = Math.max(1, properties.getFeedback().getRateLimitPerMinute());
        long now = System.currentTimeMillis();
        long cutoff = now - 60_000L;
        List<Long> hits = rateWindow.computeIfAbsent(entity.getTokenHash(), k -> new ArrayList<>());
        synchronized (hits) {
            hits.removeIf(t -> t < cutoff);
            if (hits.size() >= limit) {
                throw new EmonLensException(ErrorCode.RATE_LIMITED,
                        "feedback rate limit exceeded: " + limit + "/minute");
            }
            hits.add(now);
        }
        if (rateWindow.size() > 5_000) {
            rateWindow.entrySet().removeIf(e -> e.getValue().isEmpty());
        }
    }

    private TraceSummaryEntity requireTrace(String workspaceId, String traceId) {
        TraceSummaryEntity summary = traceSummaryMapper.selectOne(
                Wrappers.<TraceSummaryEntity>lambdaQuery()
                        .eq(TraceSummaryEntity::getWorkspaceId, workspaceId)
                        .eq(TraceSummaryEntity::getTraceId, traceId));
        if (summary == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "trace not found: " + traceId);
        }
        return summary;
    }

    private FeedbackTokenEntity requireTokenEntity(String workspaceId, String traceId, String tokenId) {
        FeedbackTokenEntity entity = tokenId == null ? null : tokenMapper.selectById(tokenId);
        if (entity == null || !workspaceId.equals(entity.getWorkspaceId())
                || (traceId != null && !traceId.equals(entity.getTraceId()))) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "feedback token not found");
        }
        return entity;
    }

    private Map<String, Object> tokenView(FeedbackTokenEntity entity, boolean publicView) {
        Map<String, Object> map = new LinkedHashMap<>();
        boolean usable = entity.getRevokedAt() == null
                && (entity.getExpiresAt() == null || entity.getExpiresAt().isAfter(LocalDateTime.now()))
                && (entity.getUsedCount() == null || entity.getMaxUses() == null
                    || entity.getUsedCount() < entity.getMaxUses());
        map.put("valid", usable);
        map.put("expiresAt", entity.getExpiresAt());
        map.put("remainingUses", Math.max(0,
                (entity.getMaxUses() == null ? 0 : entity.getMaxUses())
                        - (entity.getUsedCount() == null ? 0 : entity.getUsedCount())));
        if (!publicView) {
            map.put("tokenId", entity.getId());
            map.put("traceId", entity.getTraceId());
            map.put("maxUses", entity.getMaxUses());
            map.put("usedCount", entity.getUsedCount());
            map.put("revokedAt", entity.getRevokedAt());
            map.put("lastUsedAt", entity.getLastUsedAt());
            map.put("createdAt", entity.getCreatedAt());
        }
        return map;
    }

    private Map<String, Object> feedbackView(EndUserFeedbackEntity row) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", row.getId());
        map.put("traceId", row.getTraceId());
        map.put("kind", row.getKind());
        map.put("valueNum", row.getValueNum());
        map.put("valueText", row.getValueText());
        // correction 是用户正文：只在内网控制面返回，导出/公开侧另行裁剪
        map.put("hasCorrection", row.getCorrection() != null && !row.getCorrection().isBlank());
        map.put("correction", row.getCorrection());
        map.put("comment", row.getComment());
        map.put("userRef", row.getUserRef());
        map.put("label", row.getLabel());
        map.put("score", row.getScore());
        map.put("createdAt", row.getCreatedAt());
        return map;
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buffer) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }

    /** 供控制器判断：反馈是否存在且属于该 trace（避免把别人的反馈 id 拿来转样本）。 */
    public void requireOwned(String workspaceId, String traceId, String feedbackId) {
        EndUserFeedbackEntity row = feedbackMapper.selectById(feedbackId);
        if (row == null || !Objects.equals(workspaceId, row.getWorkspaceId())
                || !Objects.equals(traceId, row.getTraceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "feedback not found");
        }
    }

    /** 反馈年龄（便于运营判断"这条差评是不是刚发生"）。 */
    public Duration age(EndUserFeedbackEntity feedback) {
        return feedback.getCreatedAt() == null ? Duration.ZERO
                : Duration.between(feedback.getCreatedAt(), LocalDateTime.now());
    }
}
