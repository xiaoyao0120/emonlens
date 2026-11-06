package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.expression.Expression;
import org.springframework.expression.spel.SpelEvaluationException;
import org.springframework.expression.spel.SpelParseException;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.SimpleEvaluationContext;
import org.springframework.context.expression.MapAccessor;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.server.config.EmonLensProperties;
import io.emonlens.server.engine.detect.TrajectorySteps;
import io.emonlens.server.ingest.ContentCaptureService;
import io.emonlens.server.storage.entity.ContentEntity;
import io.emonlens.server.storage.entity.EvalResultEntity;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.SpanEventEntity;
import io.emonlens.server.storage.entity.TraceSummaryEntity;
import io.emonlens.server.storage.mapper.ContentMapper;
import io.emonlens.server.storage.mapper.EvalResultMapper;
import io.emonlens.server.storage.mapper.TraceSummaryMapper;
import io.emonlens.server.storage.trace.TraceAssemblyService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 评估执行：三类 Evaluator 对指定 trace 产出 EvalResult（结构化输出，V1.1 §13.2）。
 * LLM 不可用/输出非法 → label=EVAL_UNAVAILABLE（红线：不得当作 PASS）。
 * attempt 递增由调用方控制（uk: evaluator+trace+attempt）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationRunService {

    private static final SpelExpressionParser PARSER = new SpelExpressionParser();
    private static final int PROMPT_CONTENT_LIMIT = 2000;

    /**
     * EXPRESSION 评估器允许的实例方法白名单：与监控 where 规则同口径（字符串/集合只读操作），
     * 外加预设需要的 reference_match.exact。表达式是可配置文本，不能用 withInstanceMethods()
     * 放开任意方法链（getClass()/classLoader 等反射链只应是服务端代码能力，不该出现在租户表达式里）。
     */
    private static final java.util.Set<String> SAFE_METHODS = java.util.Set.of(
            "contains", "startsWith", "endsWith", "equals", "equalsIgnoreCase",
            "toLowerCase", "toUpperCase", "trim", "isEmpty", "size", "length");

    private static final org.springframework.expression.MethodResolver SAFE_METHOD_RESOLVER =
            (context, target, name, arguments) -> {
                if (target == null) {
                    return null;
                }
                boolean allowed = SAFE_METHODS.contains(name)
                        || (target instanceof ReferenceMatch && "exact".equals(name));
                if (!allowed) {
                    return null;
                }
                for (java.lang.reflect.Method m : target.getClass().getMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == arguments.size()) {
                        return (org.springframework.expression.MethodExecutor)
                                new org.springframework.expression.spel.support.ReflectiveMethodExecutor(m);
                    }
                }
                return null;
            };

    /** 数据集运行原因码：既用于 result_details_json，也用于页面区分 UNKNOWN 与 ERROR */
    public static final String RC_INPUT_MISSING = "INPUT_MISSING";
    public static final String RC_REFERENCE_MISSING = "REFERENCE_MISSING";
    public static final String RC_RETRIEVAL_CONTEXT_MISSING = "RETRIEVAL_CONTEXT_MISSING";
    public static final String RC_EXPECTED_TOOLS_MISSING = "EXPECTED_TOOLS_MISSING";
    public static final String RC_TOOLS_CALLED_MISSING = "TOOLS_CALLED_MISSING";
    public static final String RC_TURNS_MISSING = "TURNS_MISSING";
    public static final String RC_TRACE_DIGEST_MISSING = "TRACE_DIGEST_MISSING";
    public static final String RC_CANDIDATE_MISSING = "CANDIDATE_MISSING";
    public static final String RC_CANDIDATE_INCOMPLETE = "CANDIDATE_INCOMPLETE";
    public static final String RC_CONTENT_NOT_CAPTURED = "CONTENT_NOT_CAPTURED";
    public static final String RC_CONTENT_NOT_READABLE = "CONTENT_NOT_READABLE";
    public static final String RC_CONTENT_TRUNCATED = "CONTENT_TRUNCATED";
    public static final String RC_SNAPSHOT_UNAVAILABLE = "SNAPSHOT_UNAVAILABLE";
    public static final String RC_EXPRESSION_ERROR = "EXPRESSION_ERROR";
    public static final String RC_REGEX_REJECTED = "REGEX_REJECTED";
    public static final String RC_JUDGE_UNAVAILABLE = "JUDGE_UNAVAILABLE";
    public static final String RC_INVALID_JUDGE_OUTPUT = "INVALID_JUDGE_OUTPUT";
    public static final String RC_UNKNOWN_EVALUATOR_TYPE = "UNKNOWN_EVALUATOR_TYPE";

    private final TraceAssemblyService traceAssemblyService;
    private final TraceSummaryMapper traceSummaryMapper;
    private final ContentMapper contentMapper;
    private final EvalResultMapper evalResultMapper;
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;
    private final io.emonlens.server.storage.mapper.JudgeReviewMapper judgeReviewMapper;
    private final AttachmentPolicy attachmentPolicy;
    private final EmonLensProperties properties;
    private final JudgeTemplateService judgeTemplateService;
    private final TrajectoryEvaluator trajectoryEvaluator;
    private final JudgePresetRegistry judgePresetRegistry;

    /** 执行评估并落库；save=false 时仅试跑不落库（/test 接口） */
    public EvalResultEntity evaluate(EvaluatorEntity evaluator, String traceId, int attempt, boolean save) {
        TraceSummaryEntity summary = traceSummaryMapper.selectOne(Wrappers.<TraceSummaryEntity>lambdaQuery()
                .eq(TraceSummaryEntity::getWorkspaceId, evaluator.getWorkspaceId())
                .eq(TraceSummaryEntity::getTraceId, traceId));
        if (summary == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "trace not found: " + traceId);
        }
        // 数据级跨工作空间守卫：evaluator 与 trace 必须同工作空间（引擎线程无用户上下文）
        if (!summary.getWorkspaceId().equals(evaluator.getWorkspaceId())) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "trace not found: " + traceId);
        }
        List<SpanEventEntity> spans = traceAssemblyService.loadSpans(evaluator.getWorkspaceId(), traceId);
        EvaluatorSpec spec = parseSpec(evaluator);

        EvalResultEntity result;
        try {
            if (EvaluatorEntity.TYPE_EXPRESSION.equals(evaluator.getType())) {
                result = runExpression(spec, evaluator, summary, spans);
            } else if (EvaluatorEntity.TYPE_REGEX.equals(evaluator.getType())) {
                result = runRegex(spec, evaluator, summary, traceId);
            } else if (EvaluatorEntity.TYPE_LLM_JUDGE.equals(evaluator.getType())) {
                result = runLlmJudge(spec, evaluator, summary, spans, traceId);
            } else if (EvaluatorEntity.TYPE_TRAJECTORY.equals(evaluator.getType())) {
                result = runTrajectory(evaluator, spec, summary, spans);
            } else {
                throw new EmonLensException(ErrorCode.BAD_REQUEST, "unknown evaluator type: " + evaluator.getType());
            }
        } catch (RunTokenBudgetStore.BudgetExceededException e) {
            throw e;
        } catch (LlmUnavailableException e) {
            result = unavailable(evaluator, summary, "LLM unavailable: " + e.getMessage());
        }
        result.setWorkspaceId(summary.getWorkspaceId());
        result.setEvaluatorId(evaluator.getId());
        result.setEvaluatorKey(evaluator.getEvaluatorKey());
        result.setEvaluatorVersion(evaluator.getVersion());
        result.setAgentKey(summary.getAgentKey());
        result.setTraceId(traceId);
        result.setAttempt(attempt);
        result.setCreatedAt(LocalDateTime.now());
        if (save) {
            try {
                evalResultMapper.insert(result);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 同 evaluator+trace+attempt 幂等：返回已存在结果
                return evalResultMapper.selectOne(Wrappers.<EvalResultEntity>lambdaQuery()
                        .eq(EvalResultEntity::getEvaluatorId, evaluator.getId())
                        .eq(EvalResultEntity::getTraceId, traceId)
                        .eq(EvalResultEntity::getAttempt, attempt));
            }
        }
        return result;
    }

    private EvalResultEntity runExpression(EvaluatorSpec spec, EvaluatorEntity evaluator,
                                           TraceSummaryEntity summary, List<SpanEventEntity> spans) {
        Map<String, Object> context = traceContext(summary, spans);
        if (Boolean.TRUE.equals(spec.getReferenceRequired())
                && (isBlank(context.get("candidate_output")) || isBlank(context.get("expected_output")))) {
            return unavailable(evaluator, summary,
                    "reference required but candidate/expected output is not available for trace evaluation");
        }
        Boolean pass;
        try {
            pass = PARSER.parseExpression(spec.getPassWhen()).getValue(
                    SimpleEvaluationContext.forPropertyAccessors(new MapAccessor())
                            .withMethodResolvers(SAFE_METHOD_RESOLVER).build(),
                    context, Boolean.class);
        } catch (SpelParseException | SpelEvaluationException e) {
            // 表达式写错（变量名拼错、类型不匹配）时别把裸异常冒成 50000：
            // 否则配置阶段只得到一句 "internal error"，用户无从自查。
            // 变量表直接由上下文派生，新增变量不会漏告诉用户。
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "表达式求值失败：" + e.getMessage()
                    + "；可用变量：" + String.join(", ", new java.util.TreeSet<>(context.keySet())));
        }
        EvalResultEntity result = base(evaluator, summary);
        result.setLabel(Boolean.TRUE.equals(pass)
                ? EvaluatorEntity.LABEL_PASS : EvaluatorEntity.LABEL_FAIL);
        result.setScore(pass ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
        result.setConfidence(BigDecimal.ONE);
        result.setReason("expression: " + spec.getPassWhen() + " => " + pass);
        return result;
    }

    private EvalResultEntity runRegex(EvaluatorSpec spec, EvaluatorEntity evaluator,
                                      TraceSummaryEntity summary, String traceId) {
        EvalResultEntity result = base(evaluator, summary);
        String kind = spec.getField() == null || spec.getField().isEmpty()
                ? ContentEntity.KIND_COMPLETION : spec.getField();
        ContentEntity content = contentMapper.selectOne(Wrappers.<ContentEntity>lambdaQuery()
                .eq(ContentEntity::getTraceId, traceId)
                .eq(ContentEntity::getKind, kind)
                .in(ContentEntity::getCaptureMode,
                        io.emonlens.server.ingest.ContentCaptureService.MODE_REDACTED,
                        io.emonlens.server.ingest.ContentCaptureService.MODE_FULL)
                .orderByDesc(ContentEntity::getCreatedAt)
                .last("LIMIT 1"));
        if (content == null || content.getBody() == null) {
            result.setLabel(EvaluatorEntity.LABEL_NOT_APPLICABLE);
            result.setReason("content kind '" + kind + "' not captured for trace");
            return result;
        }
        boolean matched;
        try {
            // 用户可写模式跑在守护线程限时内：灾难性回溯不能挂死唯一的事件消费线程（ReDoS 防护）
            matched = SafeRegex.matches(SafeRegex.compile(spec.getPattern()), content.getBody());
        } catch (EmonLensException e) {
            result.setLabel(EvaluatorEntity.LABEL_NOT_APPLICABLE);
            result.setReason("regex rejected: " + e.getMessage());
            return result;
        }
        boolean requireMatch = spec.getRequireMatch() == null || spec.getRequireMatch();
        boolean pass = matched == requireMatch;
        result.setLabel(pass ? EvaluatorEntity.LABEL_PASS : EvaluatorEntity.LABEL_FAIL);
        result.setScore(pass ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
        result.setConfidence(BigDecimal.ONE);
        result.setReason("regex '" + spec.getPattern() + "' matched=" + matched
                + ", requireMatch=" + requireMatch);
        return result;
    }

    private EvalResultEntity runTrajectory(EvaluatorEntity evaluator, EvaluatorSpec spec,
                                           TraceSummaryEntity summary, List<SpanEventEntity> spans) {
        EvaluationSample sample = new EvaluationSample();
        sample.setAgentKey(summary.getAgentKey());
        sample.setAgentVersion(summary.getAgentVersion());
        sample.setToolsCalled(toolsFromSpans(spans));
        TrajectoryEvaluator.EvaluationResult judged = trajectoryEvaluator.evaluate(evaluator, spec, sample);
        return trajectoryResult(base(evaluator, summary), judged);
    }

    private EvalResultEntity runTrajectorySample(EvaluatorEntity evaluator, EvaluatorSpec spec,
                                                 EvaluationSample sample) {
        TrajectoryEvaluator.EvaluationResult judged = trajectoryEvaluator.evaluate(evaluator, spec, sample);
        return trajectoryResult(sampleBase(evaluator, sample), judged);
    }

    private EvalResultEntity trajectoryResult(EvalResultEntity result,
                                              TrajectoryEvaluator.EvaluationResult judged) {
        result.setLabel(judged.label);
        result.setScore(EvaluatorEntity.LABEL_PASS.equals(judged.label)
                ? BigDecimal.valueOf(100) : EvaluatorEntity.LABEL_FAIL.equals(judged.label)
                ? BigDecimal.ZERO : null);
        result.setConfidence(BigDecimal.ONE);
        result.setReasonCode(judged.reasonCode);
        result.setReason(clipReason(judged.reason));
        result.setJudgeCalled(false);
        try {
            result.setCriterionResultsJson(objectMapper.writeValueAsString(judged.matches));
        } catch (Exception e) {
            result.setCriterionResultsJson("[]");
        }
        return result;
    }

    private List<Map<String, Object>> toolsFromSpans(List<SpanEventEntity> spans) {
        List<Map<String, Object>> tools = new ArrayList<>();
        int order = 0;
        for (SpanEventEntity span : spans) {
            if (!"tool".equals(span.getSpanType())) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", span.getToolName() == null || span.getToolName().isBlank()
                    ? span.getSpanName() : span.getToolName());
            tool.put("inputParameters", jsonOrText(span.getInputText()));
            tool.put("output", jsonOrText(span.getOutputText()));
            tool.put("status", span.getStatus());
            tool.put("spanId", span.getSpanId());
            tool.put("order", order++);
            tools.add(tool);
        }
        return tools;
    }

    private Object jsonOrText(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(text, Object.class);
        } catch (Exception e) {
            return text;
        }
    }

    private EvalResultEntity runLlmJudge(EvaluatorSpec spec, EvaluatorEntity evaluator,
                                         TraceSummaryEntity summary, List<SpanEventEntity> spans,
                                         String traceId) {
        if (Boolean.TRUE.equals(spec.getReferenceRequired())) {
            return unavailable(evaluator, summary,
                    "reference required but trace evaluation has no expected output");
        }
        String digest = traceDigest(summary, spans, traceId);
        JudgeTemplate template = judgeTemplateService.resolve(evaluator.getWorkspaceId(),
                spec.getJudgeTemplateKey(), spec.getJudgeTemplateVersion());
        String mode = effectiveJudgeMode(spec);
        if ("CLAIMS".equals(mode)) {
            return runClaimJudge(evaluator, summary, null, spec, template, digest);
        }
        String system = renderTemplate(template, "agent trace", spec);
        String user = "Rubric:\n" + spec.getRubric() + criteriaPrompt(spec)
                + "\n\nTrace:\n" + digest;
        String instruction = presetInstruction(spec);
        if (!instruction.isBlank()) {
            user += "\n\nPreset task:\n" + instruction;
        }
        // R3-3：把盲审一致的校准结论注入 prompt（可开关）。它是"判定倾向"提示，不是原文 few-shot 片段。
        String calibration = calibrationBlock(evaluator, spec);
        if (!calibration.isBlank()) {
            user += "\n\n" + calibration;
        }
        List<String> models = judgeModels(spec);
        if (models.size() > 1) {
            return runMultiJudge(evaluator, summary, spec, template, system, user, models);
        }
        LlmClient.ChatReply reply = llmClient.chatWithUsage(system, user,
                spec.getJudgeModel(), spec.getTemperature() == null ? 0.0 : spec.getTemperature());
        try {
            JsonNode node = objectMapper.readTree(extractJson(reply.content()));
            // 缺 score / score 非数字（模型未按格式作答）：按不可用处理并保留原始输出，
            // 而非兜底成 0 分 FAIL——那会让评审结果看起来像"业务判负"且无从排查
            JsonNode scoreNode = node.get("score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                log.warn("judge output missing numeric score for evaluator {}: {}",
                        evaluator.getId(), reply.content());
                EvalResultEntity result = unavailable(evaluator, summary,
                        "invalid judge output: " + reply.content());
                result.setReasonCode("INVALID_JUDGE_OUTPUT");
                applyTemplateMetadata(result, mode, spec, template);
                copyUsage(result, reply);
                return result;
            }
            EvalResultEntity result = base(evaluator, summary);
            applyTemplateMetadata(result, mode, spec, template);
            BigDecimal score = BigDecimal.valueOf(Math.max(0.0, Math.min(10.0, scoreNode.asDouble())))
                    .setScale(2, RoundingMode.HALF_UP);
            double minScore = spec.getMinScore() == null ? 6.0 : spec.getMinScore();
            result.setScore(score);
            result.setConfidence(BigDecimal.valueOf(
                    Math.max(0.0, Math.min(1.0, node.path("confidence").asDouble(0))))
                    .setScale(3, RoundingMode.HALF_UP));
            boolean scorePass = score.doubleValue() >= minScore;
            boolean criteriaPass = applyCriterionResults(result, spec, node);
            result.setLabel(scorePass && criteriaPass
                    ? EvaluatorEntity.LABEL_PASS : EvaluatorEntity.LABEL_FAIL);
            result.setReasonCode(scorePass && criteriaPass ? "JUDGE_PASS"
                    : scorePass ? "JUDGE_CRITERION_FAIL" : "JUDGE_FAIL");
            String reason = node.path("reason").asText("");
            result.setReason(clipReason(reason.isEmpty() ? "（评审模型未给理由）" : reason));
            result.setJudgeModel(effectiveJudgeModel(spec));
            result.setPromptTokens(reply.promptTokens());
            result.setCompletionTokens(reply.completionTokens());
            return result;
        } catch (Exception e) {
            // 输出非法是确定性问题，不重试；直接标 EVAL_UNAVAILABLE，但保留已产生的 usage。
            log.warn("judge output unparseable for evaluator {}: {}", evaluator.getId(), reply.content());
            EvalResultEntity result = unavailable(evaluator, summary,
                    "invalid judge output: " + reply.content());
            result.setReasonCode("INVALID_JUDGE_OUTPUT");
            applyTemplateMetadata(result, mode, spec, template);
            copyUsage(result, reply);
            return result;
        }
    }

    /** 判官原文留痕：序列化失败不该拖垮评估，退化成空数组。 */
    private String recordsJson(List<Map<String, Object>> records) {
        try {
            return objectMapper.writeValueAsString(records);
        } catch (Exception e) {
            log.warn("judge records serialization failed", e);
            return "[]";
        }
    }

    /** 判官 token 累加（可能为 null，表示模型没回报 usage）。 */
    private static Integer plus(Integer current, Integer delta) {
        if (delta == null) {
            return current;
        }
        return current == null ? delta : current + delta;
    }

    /** R3-3：判官名单——显式 judgeModels 优先，否则退回单 judgeModel。 */
    private List<String> judgeModels(EvaluatorSpec spec) {
        List<String> models = new ArrayList<>();
        if (spec.getJudgeModels() != null) {
            for (String model : spec.getJudgeModels()) {
                if (model != null && !model.isBlank() && !models.contains(model.trim())) {
                    models.add(model.trim());
                }
            }
        }
        if (models.isEmpty()) {
            models.add(effectiveJudgeModel(spec));
        }
        return models;
    }

    /**
     * R3-3：盲审一致的校准结论注入 prompt（可开关）。
     *
     * <p>已知偏差：评审记录只存标签与原因、不存样本原文，所以这里是"判定倾向"提示
     * （该评估器在已一致的盲审样本上的 PASS/FAIL 分布），而不是原文 few-shot 片段。</p>
     */
    private String calibrationBlock(EvaluatorEntity evaluator, EvaluatorSpec spec) {
        if (!properties.getEval().isJudgeFewShotEnabled()) {
            return "";
        }
        int max = Math.max(0, properties.getEval().getJudgeFewShotMax());
        if (max == 0) {
            return "";
        }
        List<io.emonlens.server.storage.entity.JudgeReviewEntity> reviews =
                judgeReviewMapper.selectList(Wrappers
                        .<io.emonlens.server.storage.entity.JudgeReviewEntity>lambdaQuery()
                        .eq(io.emonlens.server.storage.entity.JudgeReviewEntity::getWorkspaceId,
                                evaluator.getWorkspaceId())
                        .eq(io.emonlens.server.storage.entity.JudgeReviewEntity::getEvaluatorKey,
                                evaluator.getEvaluatorKey())
                        .apply("judge_label = human_label")
                        .orderByDesc(io.emonlens.server.storage.entity.JudgeReviewEntity::getCreatedAt)
                        .last("LIMIT " + max));
        if (reviews.isEmpty()) {
            return "";
        }
        long pass = reviews.stream()
                .filter(r -> EvaluatorEntity.LABEL_PASS.equalsIgnoreCase(r.getJudgeLabel())).count();
        long fail = reviews.size() - pass;
        StringBuilder block = new StringBuilder();
        block.append("Calibration from blind review (judge and human agreed on these, ")
                .append(reviews.size()).append(" samples: ").append(pass).append(" PASS / ")
                .append(fail).append(" FAIL):");
        for (int i = 0; i < reviews.size(); i++) {
            block.append("\n- example ").append(i + 1).append(": agreed label ")
                    .append(reviews.get(i).getJudgeLabel());
            if (reviews.get(i).getNote() != null && !reviews.get(i).getNote().isBlank()) {
                block.append(" — ").append(clipReason(reviews.get(i).getNote()));
            }
        }
        block.append("\nUse them only to calibrate strictness; the rubric still decides.");
        return block.toString();
    }

    /**
     * R3-3：多 judge 聚合。策略：
     * <ul>
     *   <li>MAJORITY（默认）：严格多数（&gt; n/2）才算结论，否则 UNKNOWN——冲突不硬判</li>
     *   <li>CONSERVATIVE：全体一致才给结论，否则 UNKNOWN</li>
     *   <li>MEAN：均分 + 同向保护。必须有严格多数（&gt; n/2）同向，<b>且</b>判 PASS 时均分达到 minScore；
     *       任一不满足即 UNKNOWN（JUDGE_DISAGREEMENT / JUDGE_MEAN_BELOW_MIN_SCORE），分数仍保留供排查</li>
     * </ul>
     * 每个 model 的原始输出都写进 judgeRecordsJson，便于回溯"为什么判成这样"。
     */
    private EvalResultEntity runMultiJudge(EvaluatorEntity evaluator, TraceSummaryEntity summary,
                                           EvaluatorSpec spec, JudgeTemplate template,
                                           String system, String user, List<String> models) {
        double temperature = spec.getTemperature() == null ? 0.0 : spec.getTemperature();
        double minScore = spec.getMinScore() == null ? 6.0 : spec.getMinScore();
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<BigDecimal> scores = new ArrayList<>();
        List<String> summaryParts = new ArrayList<>();
        Integer promptTokens = null;
        Integer completionTokens = null;
        boolean invalid = false;
        for (String model : models) {
            LlmClient.ChatReply reply = llmClient.chatWithUsage(system, user, model, temperature);
            promptTokens = plus(promptTokens, reply.promptTokens());
            completionTokens = plus(completionTokens, reply.completionTokens());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("model", model);
            record.put("raw", clipReason(reply.content()));
            try {
                JsonNode node = objectMapper.readTree(extractJson(reply.content()));
                JsonNode scoreNode = node.get("score");
                if (scoreNode == null || !scoreNode.isNumber()) {
                    invalid = true;
                    record.put("label", EvaluatorEntity.LABEL_EVAL_UNAVAILABLE);
                    records.add(record);
                    continue;
                }
                double score = Math.max(0.0, Math.min(10.0, scoreNode.asDouble()));
                boolean pass = score >= minScore;
                String label = pass ? EvaluatorEntity.LABEL_PASS : EvaluatorEntity.LABEL_FAIL;
                record.put("score", BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
                record.put("label", label);
                record.put("reason", node.path("reason").asText(""));
                records.add(record);
                labels.add(label);
                scores.add(BigDecimal.valueOf(score));
                summaryParts.add(model + "=" + label + "(" + BigDecimal.valueOf(score)
                        .setScale(1, RoundingMode.HALF_UP) + ")");
            } catch (Exception e) {
                invalid = true;
                record.put("label", EvaluatorEntity.LABEL_EVAL_UNAVAILABLE);
                records.add(record);
            }
        }

        EvalResultEntity result = base(evaluator, summary);
        applyTemplateMetadata(result, effectiveJudgeMode(spec), spec, template);
        result.setJudgeModel(String.join(",", models));
        result.setPromptTokens(promptTokens);
        result.setCompletionTokens(completionTokens);
        result.setJudgeRecordsJson(recordsJson(records));
        if (!scores.isEmpty()) {
            BigDecimal total = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            result.setScore(total.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP));
        }

        String policy = spec.getJudgeAggregation() == null ? "MAJORITY"
                : spec.getJudgeAggregation().trim().toUpperCase();
        long pass = labels.stream().filter(EvaluatorEntity.LABEL_PASS::equals).count();
        long fail = labels.stream().filter(EvaluatorEntity.LABEL_FAIL::equals).count();
        String label;
        String reasonCode;
        if (invalid || labels.isEmpty()) {
            label = EvaluatorEntity.LABEL_EVAL_UNAVAILABLE;
            reasonCode = "INVALID_JUDGE_OUTPUT";
        } else if ("MEAN".equals(policy)) {
            // 均分 + 同向保护：
            //   1) 必须有严格多数同向（> n/2），否则分歧不硬判 → UNKNOWN
            //   2) 判 PASS 时，均分还必须达到 minScore，避免"多数通过但均值其实没过线"
            // 于是 1 PASS + 1 FAIL 仍是 UNKNOWN；2 PASS + 1 FAIL 且均分达标才给 PASS。
            // 均分始终保留在 score 里，便于回溯"为什么判成这样"。
            if (pass * 2 > labels.size()) {
                BigDecimal mean = result.getScore();
                if (mean == null || mean.doubleValue() >= minScore) {
                    label = EvaluatorEntity.LABEL_PASS;
                    reasonCode = "JUDGE_PASS";
                } else {
                    label = EvaluatorEntity.LABEL_UNKNOWN;
                    reasonCode = "JUDGE_MEAN_BELOW_MIN_SCORE";
                }
            } else if (fail * 2 > labels.size()) {
                label = EvaluatorEntity.LABEL_FAIL;
                reasonCode = "JUDGE_FAIL";
            } else {
                label = EvaluatorEntity.LABEL_UNKNOWN;
                reasonCode = "JUDGE_DISAGREEMENT";
            }
        } else if ("CONSERVATIVE".equals(policy)) {
            if (pass == labels.size()) {
                label = EvaluatorEntity.LABEL_PASS;
                reasonCode = "JUDGE_PASS";
            } else if (fail == labels.size()) {
                label = EvaluatorEntity.LABEL_FAIL;
                reasonCode = "JUDGE_FAIL";
            } else {
                label = EvaluatorEntity.LABEL_UNKNOWN;
                reasonCode = "JUDGE_DISAGREEMENT";
            }
        } else {
            if (pass * 2 > labels.size()) {
                label = EvaluatorEntity.LABEL_PASS;
                reasonCode = "JUDGE_PASS";
            } else if (fail * 2 > labels.size()) {
                label = EvaluatorEntity.LABEL_FAIL;
                reasonCode = "JUDGE_FAIL";
            } else {
                label = EvaluatorEntity.LABEL_UNKNOWN;
                reasonCode = "JUDGE_DISAGREEMENT";
            }
        }
        result.setLabel(label);
        result.setReasonCode(reasonCode);
        result.setReason(clipReason(String.join("; ", summaryParts) + " => " + label
                + " by " + policy));
        return result;
    }

    private EvalResultEntity unavailable(EvaluatorEntity evaluator, TraceSummaryEntity summary, String reason) {
        EvalResultEntity result = base(evaluator, summary);
        result.setLabel(EvaluatorEntity.LABEL_EVAL_UNAVAILABLE);
        result.setReason(clipReason(reason));
        return result;
    }

    /** et_eval_result.reason 是 VARCHAR(1024)：评审模型原文/异常信息超长会直接让整条结果落库失败。 */
    private static String clipReason(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 1000 ? reason : reason.substring(0, 1000) + "…";
    }

    private EvalResultEntity base(EvaluatorEntity evaluator, TraceSummaryEntity summary) {
        EvalResultEntity result = new EvalResultEntity();
        result.setAgentVersion(summary.getAgentVersion());
        return result;
    }

    /** trace 级评估上下文（EXPRESSION 用）；此处的键即用户可写的 SpEL 变量名 */
    Map<String, Object> traceContext(TraceSummaryEntity summary, List<SpanEventEntity> spans) {
        Map<String, Object> context = new HashMap<>();
        context.put("trace_id", summary.getTraceId());
        context.put("agent_key", summary.getAgentKey());
        context.put("agent_version", summary.getAgentVersion());
        context.put("span_count", summary.getSpanCount());
        context.put("error_count", summary.getErrorCount());
        context.put("duration_ms", summary.getDurationMs());
        context.put("tool_calls", spans.stream().filter(TrajectorySteps::isStep).count());
        context.put("llm_calls", spans.stream().filter(s -> "llm".equals(s.getSpanType())).count());
        // M1：参考答案只对数据集样本生效；trace 级评估没有 expected_output，
        // referenceRequired=true 时在上方先判 EVAL_UNAVAILABLE，绝不因缺失而判 FAIL。
        context.put("candidate_output", latestContent(summary.getTraceId(), ContentEntity.KIND_COMPLETION));
        context.put("expected_output", null);
        context.put("reference_match", new ReferenceMatch());
        return context;
    }

    /** 数据集样本的表达式上下文：现有 trace 指标 + 候选/参考 + 精确匹配助手 */
    Map<String, Object> sampleContext(EvaluationSample sample) {
        Map<String, Object> context = new HashMap<>();
        context.put("trace_id", sample.getTraceId());
        context.put("agent_key", sample.getAgentKey());
        context.put("agent_version", sample.getAgentVersion());
        context.put("span_count", sample.getSpanCount());
        context.put("error_count", sample.getErrorCount());
        context.put("duration_ms", sample.getDurationMs());
        context.put("tool_calls", sample.getToolCalls());
        context.put("llm_calls", sample.getLlmCalls());
        context.put("input", sample.getInput());
        context.put("candidate_output", sample.getCandidateOutput());
        context.put("expected_output", sample.getExpectedOutput());
        context.put("retrieval_context", sample.getRetrievalContext());
        context.put("expected_tools", sample.getExpectedTools());
        context.put("tools_called", sample.getToolsCalled());
        context.put("turns", sample.getTurns());
        context.put("expectations", sample.getExpectations());
        context.put("reference_match", new ReferenceMatch());
        return context;
    }

    public String traceDigest(TraceSummaryEntity summary, List<SpanEventEntity> spans, String traceId) {
        StringBuilder sb = new StringBuilder();
        sb.append("agent=").append(summary.getAgentKey())
                .append(", version=").append(summary.getAgentVersion())
                .append(", status=").append(summary.getStatus())
                .append(", errors=").append(summary.getErrorCount())
                .append(", duration_ms=").append(summary.getDurationMs()).append('\n');
        for (SpanEventEntity span : spans) {
            sb.append("- [").append(span.getSpanType()).append("] ")
                    .append(span.getToolName() != null ? span.getToolName() : span.getSpanName())
                    .append("#").append(span.getSpanId()).append(span.getModelName() == null ? "" : " model=" + span.getModelName())
                    .append(SpanEventEntity.STATUS_ERROR.equals(span.getStatus())
                            ? " ERROR:" + span.getErrorType() : "")
                    .append('\n');
            // LLM span 的真实 I/O 是评审质量的关键输入（受内容采集模式控制，截断防超长）
            if ("llm".equals(span.getSpanType())) {
                if (span.getInputText() != null && !span.getInputText().isEmpty()) {
                    sb.append("  input: ").append(span.getInputText(), 0, Math.min(1500, span.getInputText().length())).append('\n');
                }
                if (span.getOutputText() != null && !span.getOutputText().isEmpty()) {
                    sb.append("  output: ").append(span.getOutputText(), 0, Math.min(1500, span.getOutputText().length())).append('\n');
                }
            }
        }
        String completion = latestContent(traceId, ContentEntity.KIND_COMPLETION);
        if (completion != null) {
            sb.append("completion: ").append(completion, 0, Math.min(PROMPT_CONTENT_LIMIT, completion.length()));
        }
        return sb.toString();
    }

    private String latestContent(String traceId, String kind) {
        ContentEntity content = contentMapper.selectOne(Wrappers.<ContentEntity>lambdaQuery()
                .eq(ContentEntity::getTraceId, traceId)
                .eq(ContentEntity::getKind, kind)
                .orderByDesc(ContentEntity::getCreatedAt)
                .last("LIMIT 1"));
        return content == null ? null : content.getBody();
    }

    // ==================== M1：数据集样本判定（不落 et_eval_result） ====================

    /**
     * 用受理时冻结的样本执行一次判定。与 trace 评估共用三类判定语义，但不读写 et_eval_result。
     * 数据不可用 → UNKNOWN；评估器/执行错误 → ERROR；业务结论仍是 PASS/FAIL。
     */
    public EvalResultEntity evaluateSample(EvaluatorEntity evaluator, EvaluationSample sample) {
        EvaluatorSpec spec;
        try {
            spec = parseSpec(evaluator);
        } catch (EmonLensException e) {
            return sampleError(evaluator, sample, "EVALUATOR_SPEC_INVALID", e.getMessage());
        }
        EvalResultEntity result;
        if (EvaluatorEntity.TYPE_EXPRESSION.equals(evaluator.getType())) {
            result = runExpressionSample(spec, evaluator, sample);
        } else if (EvaluatorEntity.TYPE_REGEX.equals(evaluator.getType())) {
            result = runRegexSample(spec, evaluator, sample);
        } else if (EvaluatorEntity.TYPE_LLM_JUDGE.equals(evaluator.getType())) {
            result = runLlmJudgeSample(spec, evaluator, sample);
        } else if (EvaluatorEntity.TYPE_TRAJECTORY.equals(evaluator.getType())) {
            result = runTrajectorySample(evaluator, spec, sample);
        } else {
            result = sampleError(evaluator, sample, RC_UNKNOWN_EVALUATOR_TYPE,
                    "unknown evaluator type: " + evaluator.getType());
        }
        result.setWorkspaceId(evaluator.getWorkspaceId());
        result.setEvaluatorId(evaluator.getId());
        result.setEvaluatorKey(evaluator.getEvaluatorKey());
        result.setEvaluatorVersion(evaluator.getVersion());
        result.setAgentKey(sample.getAgentKey());
        result.setAgentVersion(sample.getAgentVersion());
        result.setTraceId(sample.getTraceId());
        result.setAttempt(1);
        result.setCreatedAt(LocalDateTime.now());
        return result;
    }

    private EvalResultEntity runExpressionSample(EvaluatorSpec spec, EvaluatorEntity evaluator,
                                                 EvaluationSample sample) {
        if (Boolean.TRUE.equals(spec.getReferenceRequired())) {
            if (isBlank(sample.getExpectedOutput())) {
                return unknown(evaluator, sample, RC_REFERENCE_MISSING,
                        "该评估器要求参考答案，但样本没有 expected_output；不作 FAIL 处理");
            }
            if (isBlank(sample.getCandidateOutput())) {
                return unknown(evaluator, sample, RC_CANDIDATE_MISSING,
                        "无法取得最终候选输出；不作 FAIL 处理");
            }
            if (Boolean.FALSE.equals(sample.getCandidateComplete())) {
                return unknown(evaluator, sample, RC_CANDIDATE_INCOMPLETE,
                        "候选输出不完整（内容采集截断或快照超限），无法确认精确匹配");
            }
        }
        Map<String, Object> context = sampleContext(sample);
        boolean preset = ReferenceMatch.PRESET_KEY.equals(spec.getPreset());
        Boolean pass;
        try {
            pass = PARSER.parseExpression(spec.getPassWhen()).getValue(
                    SimpleEvaluationContext.forPropertyAccessors(new MapAccessor())
                            .withMethodResolvers(SAFE_METHOD_RESOLVER).build(),
                    context, Boolean.class);
        } catch (SpelParseException | SpelEvaluationException e) {
            return sampleError(evaluator, sample, RC_EXPRESSION_ERROR,
                    "表达式求值失败：" + e.getMessage()
                            + "；可用变量：" + String.join(", ", new java.util.TreeSet<>(context.keySet())));
        }
        EvalResultEntity result = sampleBase(evaluator, sample);
        result.setLabel(Boolean.TRUE.equals(pass) ? EvaluatorEntity.LABEL_PASS : EvaluatorEntity.LABEL_FAIL);
        result.setScore(pass ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
        result.setConfidence(BigDecimal.ONE);
        result.setReasonCode(preset
                ? (Boolean.TRUE.equals(pass) ? "REFERENCE_EXACT_MATCH_PASS" : "REFERENCE_EXACT_MATCH_FAIL")
                : (Boolean.TRUE.equals(pass) ? "EXPRESSION_PASS" : "EXPRESSION_FAIL"));
        if (preset) {
            result.setReason("参考答案精确匹配：" + (Boolean.TRUE.equals(pass) ? "通过" : "未通过")
                    + "（仅统一换行、去除首尾空白，保留大小写与内部内容；不适用于同义答案）");
        } else {
            result.setReason("expression: " + spec.getPassWhen() + " => " + pass);
        }
        return result;
    }

    private EvalResultEntity runRegexSample(EvaluatorSpec spec, EvaluatorEntity evaluator,
                                            EvaluationSample sample) {
        String kind = spec.getField() == null || spec.getField().isEmpty()
                ? ContentEntity.KIND_COMPLETION : spec.getField();
        String content = sample.content(kind);
        String mode = sample.captureMode(kind);
        if (content == null || content.isEmpty()) {
            return unknown(evaluator, sample, RC_CONTENT_NOT_CAPTURED,
                    "content kind '" + kind + "' not captured for this sample"
                            + (sample.getContentStatusReason() == null ? "" : "（" + sample.getContentStatusReason() + "）"));
        }
        if (!ContentCaptureService.MODE_REDACTED.equals(mode) && !ContentCaptureService.MODE_FULL.equals(mode)) {
            return unknown(evaluator, sample, RC_CONTENT_NOT_READABLE,
                    "content kind '" + kind + "' capture mode=" + mode + "，仅有 hash 或未采集");
        }
        if (sample.truncated(kind)) {
            return unknown(evaluator, sample, RC_CONTENT_TRUNCATED,
                    "content kind '" + kind + "' 在采集上限处被截断，无法保证正则结果可靠");
        }
        boolean matched;
        try {
            matched = SafeRegex.matches(SafeRegex.compile(spec.getPattern()), content);
        } catch (EmonLensException e) {
            return sampleError(evaluator, sample, RC_REGEX_REJECTED, "regex rejected: " + e.getMessage());
        }
        boolean requireMatch = spec.getRequireMatch() == null || spec.getRequireMatch();
        boolean pass = matched == requireMatch;
        EvalResultEntity result = sampleBase(evaluator, sample);
        result.setLabel(pass ? EvaluatorEntity.LABEL_PASS : EvaluatorEntity.LABEL_FAIL);
        result.setScore(pass ? BigDecimal.valueOf(100) : BigDecimal.ZERO);
        result.setConfidence(BigDecimal.ONE);
        result.setReasonCode(pass ? "REGEX_PASS" : "REGEX_FAIL");
        result.setReason("regex '" + spec.getPattern() + "' matched=" + matched
                + ", requireMatch=" + requireMatch);
        return result;
    }

    private EvalResultEntity runLlmJudgeSample(EvaluatorSpec spec, EvaluatorEntity evaluator,
                                               EvaluationSample sample) {
        if (isBlank(sample.getCandidateOutput())) {
            return unknown(evaluator, sample, RC_CANDIDATE_MISSING,
                    "无法取得最终候选输出；不作 FAIL 处理");
        }
        if (Boolean.FALSE.equals(sample.getCandidateComplete())) {
            return unknown(evaluator, sample, RC_CANDIDATE_INCOMPLETE,
                    "候选输出不完整（内容采集截断或快照超限），无法完整评审");
        }
        boolean hasReference = !isBlank(sample.getExpectedOutput());
        if (Boolean.TRUE.equals(spec.getReferenceRequired()) && !hasReference) {
            return unknown(evaluator, sample, RC_REFERENCE_MISSING,
                    "该评估器要求参考答案，但样本没有 expected_output；不作 FAIL 处理");
        }
        String missing = missingRequiredField(spec, sample);
        if (missing != null) {
            return unknown(evaluator, sample, requiredFieldReason(missing),
                    "该评估器要求 " + missing + "，但样本/快照没有；不作 FAIL 处理");
        }

        JudgeTemplate template = judgeTemplateService.resolve(evaluator.getWorkspaceId(),
                spec.getJudgeTemplateKey(), spec.getJudgeTemplateVersion());
        String mode = effectiveJudgeMode(spec);
        if ("CLAIMS".equals(mode)) {
            return runClaimJudge(evaluator, null, sample, spec, template, sample.getTraceDigest());
        }

        String system = renderTemplate(template, "candidate output", spec);
        StringBuilder user = new StringBuilder();
        user.append("Rubric:\n").append(spec.getRubric()).append(criteriaPrompt(spec)).append("\n\n");
        if (hasReference) {
            user.append("Reference answer (evaluation basis; the candidate does not need to match verbatim):\n")
                    .append(sample.getExpectedOutput()).append("\n\n");
        } else {
            user.append("No reference answer was provided. Evaluate only against the rubric.\n\n");
        }
        user.append("Candidate output:\n").append(sample.getCandidateOutput()).append('\n');
        appendEvidence(user, sample);
        if (sample.getTraceDigest() != null && !sample.getTraceDigest().isEmpty()) {
            user.append("\nTrace context:\n").append(sample.getTraceDigest());
        }
        String instruction = presetInstruction(spec);
        if (!instruction.isBlank()) {
            user.append("\n\nPreset task:\n").append(instruction);
        }
        // R3-4：把附件作为证据写进 prompt；外链图片同时交给 vision 端点
        String attachmentBlock = attachmentPolicy.promptBlock(sample.getAttachments());
        if (!attachmentBlock.isBlank()) {
            user.append("\n\n").append(attachmentBlock);
        }
        // R3-3：校准提示 + 多 judge（样本路径与 trace 路径保持同一套口径）
        String calibration = calibrationBlock(evaluator, spec);
        if (!calibration.isBlank()) {
            user.append("\n\n").append(calibration);
        }
        List<String> models = judgeModels(spec);
        if (models.size() > 1) {
            return runMultiJudgeSample(evaluator, sample, spec, template, system, user.toString(), models);
        }
        LlmClient.ChatReply reply;
        try {
            reply = llmClient.chatWithImages(system, user.toString(),
                    attachmentPolicy.imageUrls(sample.getAttachments()),
                    spec.getJudgeModel(), spec.getTemperature() == null ? 0.0 : spec.getTemperature());
        } catch (RunTokenBudgetStore.BudgetExceededException e) {
            throw e;
        } catch (LlmUnavailableException e) {
            EvalResultEntity result = unknown(evaluator, sample, RC_JUDGE_UNAVAILABLE,
                    "LLM unavailable: " + e.getMessage());
            result.setJudgeCalled(!String.valueOf(e.getMessage()).contains("not configured"));
            applyTemplateMetadata(result, mode, spec, template);
            return result;
        }
        try {
            JsonNode node = objectMapper.readTree(extractJson(reply.content()));
            JsonNode scoreNode = node.get("score");
            if (scoreNode == null || !scoreNode.isNumber()) {
                EvalResultEntity result = unknown(evaluator, sample, RC_INVALID_JUDGE_OUTPUT,
                        "invalid judge output: " + reply.content());
                result.setJudgeCalled(true);
                applyTemplateMetadata(result, mode, spec, template);
                copyUsage(result, reply);
                return result;
            }
            EvalResultEntity result = sampleBase(evaluator, sample);
            result.setJudgeCalled(true);
            applyTemplateMetadata(result, mode, spec, template);
            BigDecimal score = BigDecimal.valueOf(Math.max(0.0, Math.min(10.0, scoreNode.asDouble())))
                    .setScale(2, RoundingMode.HALF_UP);
            double minScore = spec.getMinScore() == null ? 6.0 : spec.getMinScore();
            result.setScore(score);
            result.setConfidence(BigDecimal.valueOf(
                    Math.max(0.0, Math.min(1.0, node.path("confidence").asDouble(0))))
                    .setScale(3, RoundingMode.HALF_UP));
            boolean scorePass = score.doubleValue() >= minScore;
            boolean criteriaPass = applyCriterionResults(result, spec, node);
            result.setLabel(scorePass && criteriaPass
                    ? EvaluatorEntity.LABEL_PASS : EvaluatorEntity.LABEL_FAIL);
            result.setReasonCode(scorePass && criteriaPass ? "JUDGE_PASS"
                    : scorePass ? "JUDGE_CRITERION_FAIL" : "JUDGE_FAIL");
            String reason = node.path("reason").asText("");
            result.setReason(clipReason(reason.isEmpty() ? "（评审模型未给理由）" : reason));
            result.setJudgeModel(effectiveJudgeModel(spec));
            copyUsage(result, reply);
            return result;
        } catch (Exception e) {
            EvalResultEntity result = unknown(evaluator, sample, RC_INVALID_JUDGE_OUTPUT,
                    "invalid judge output: " + reply.content());
            result.setJudgeCalled(true);
            applyTemplateMetadata(result, mode, spec, template);
            copyUsage(result, reply);
            return result;
        }
    }

    /** CLAIMS 模式：逐条 claim 判定，失败/未知按固定策略聚合。 */
    private EvalResultEntity runClaimJudge(EvaluatorEntity evaluator, TraceSummaryEntity summary,
                                           EvaluationSample sample, EvaluatorSpec spec,
                                           JudgeTemplate template, String traceDigest) {
        String user = buildClaimUserPrompt(spec, sample, traceDigest);
        LlmClient.ChatReply reply;
        try {
            reply = llmClient.chatWithUsage(template.getSystemPrompt(), user,
                    spec.getJudgeModel(), spec.getTemperature() == null ? 0.0 : spec.getTemperature());
        } catch (RunTokenBudgetStore.BudgetExceededException e) {
            throw e;
        } catch (LlmUnavailableException e) {
            EvalResultEntity result = sample != null
                    ? unknown(evaluator, sample, RC_JUDGE_UNAVAILABLE, "LLM unavailable: " + e.getMessage())
                    : unavailable(evaluator, summary, "LLM unavailable: " + e.getMessage());
            result.setJudgeCalled(!String.valueOf(e.getMessage()).contains("not configured"));
            applyTemplateMetadata(result, "CLAIMS", spec, template);
            return result;
        }
        try {
            JsonNode node = objectMapper.readTree(extractJson(reply.content()));
            boolean requireEvidence = spec.getClaimsPolicy() == null
                    || !Boolean.FALSE.equals(spec.getClaimsPolicy().getRequireEvidence());
            int maxClaims = spec.getClaimsPolicy() == null
                    || spec.getClaimsPolicy().getMaxClaims() == null
                    ? 20 : spec.getClaimsPolicy().getMaxClaims();
            List<Claim> claims = parseClaims(node, requireEvidence, maxClaims);
            double minPassRatio = spec.getClaimsPolicy() == null
                    || spec.getClaimsPolicy().getMinPassRatio() == null
                    ? 0.8 : spec.getClaimsPolicy().getMinPassRatio();
            String unknownPolicy = spec.getClaimsPolicy() == null
                    || spec.getClaimsPolicy().getUnknownPolicy() == null
                    ? ClaimAggregator.POLICY_BLOCK : spec.getClaimsPolicy().getUnknownPolicy();
            ClaimAggregator.Result aggregate = ClaimAggregator.aggregate(
                    claims, minPassRatio, unknownPolicy);

            EvalResultEntity result = sample != null
                    ? sampleBase(evaluator, sample) : base(evaluator, summary);
            result.setJudgeCalled(true);
            applyTemplateMetadata(result, "CLAIMS", spec, template);
            result.setScore(BigDecimal.valueOf(aggregate.getPassRatio() * 10.0)
                    .setScale(2, RoundingMode.HALF_UP));
            int judged = aggregate.getPassCount() + aggregate.getFailCount();
            result.setConfidence(BigDecimal.valueOf(claims.isEmpty() ? 0.0
                            : (double) judged / claims.size())
                    .setScale(3, RoundingMode.HALF_UP));
            result.setLabel(aggregate.getLabel());
            result.setReasonCode(aggregate.getReasonCode());
            result.setReason(clipReason(claimReason(aggregate, claims)));
            result.setClaimsJson(objectMapper.writeValueAsString(claims));
            result.setClaimAggregateJson(objectMapper.writeValueAsString(aggregate));
            result.setJudgeModel(effectiveJudgeModel(spec));
            copyUsage(result, reply);
            return result;
        } catch (Exception e) {
            log.warn("claim judge output unparseable for evaluator {}: {}",
                    evaluator.getId(), reply.content());
            EvalResultEntity result = sample != null
                    ? unknown(evaluator, sample, RC_INVALID_JUDGE_OUTPUT,
                            "invalid judge output: " + reply.content())
                    : unavailable(evaluator, summary, "invalid judge output: " + reply.content());
            result.setJudgeCalled(true);
            result.setReasonCode(RC_INVALID_JUDGE_OUTPUT);
            applyTemplateMetadata(result, "CLAIMS", spec, template);
            copyUsage(result, reply);
            return result;
        }
    }

    private List<Claim> parseClaims(JsonNode node, boolean requireEvidence, int maxClaims) {
        JsonNode array = node == null ? null : node.get("claims");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("judge output missing claims array");
        }
        if (array.size() > maxClaims) {
            // 超限不截断：截断可能丢掉 FAIL claim 而把整体误判为 PASS；整次判分按非法输出处理。
            throw new IllegalArgumentException("judge returned " + array.size()
                    + " claims, exceeds maxClaims=" + maxClaims);
        }
        List<Claim> claims = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (JsonNode value : array) {
            if (value == null || !value.isObject()) {
                throw new IllegalArgumentException("claim must be an object");
            }
            String id = value.path("id").asText("");
            String text = value.path("text").asText("");
            String verdict = value.path("verdict").asText("").trim().toUpperCase();
            if (id.isBlank() || text.isBlank()) {
                throw new IllegalArgumentException("claim id/text is required");
            }
            if (!ids.add(id)) {
                throw new IllegalArgumentException("duplicate claim id: " + id);
            }
            if (!"PASS".equals(verdict) && !"FAIL".equals(verdict) && !"UNKNOWN".equals(verdict)) {
                throw new IllegalArgumentException("claim verdict must be PASS/FAIL/UNKNOWN: " + id);
            }
            Claim claim = new Claim();
            claim.setId(id);
            claim.setText(text);
            claim.setVerdict(verdict);
            List<String> refs = new ArrayList<>();
            JsonNode refsNode = value.get("evidenceRefs");
            if (refsNode != null && refsNode.isArray()) {
                for (JsonNode ref : refsNode) {
                    if (ref.isTextual() && !ref.asText().isBlank()) {
                        refs.add(ref.asText());
                    }
                }
            }
            claim.setEvidenceRefs(refs);
            String reason = value.path("reason").asText("");
            claim.setReason(clipReason(reason));
            if (requireEvidence && "PASS".equals(verdict) && refs.isEmpty()) {
                claim.setVerdict("UNKNOWN");
                claim.setReason(clipReason("缺少证据引用，按 UNKNOWN 处理"
                        + (reason.isBlank() ? "" : "：" + reason)));
            }
            claims.add(claim);
        }
        if (claims.isEmpty()) {
            throw new IllegalArgumentException("judge output has no claims");
        }
        return claims;
    }

    private String buildClaimUserPrompt(EvaluatorSpec spec, EvaluationSample sample, String traceDigest) {
        int maxClaims = spec.getClaimsPolicy() == null
                || spec.getClaimsPolicy().getMaxClaims() == null
                ? 20 : spec.getClaimsPolicy().getMaxClaims();
        StringBuilder user = new StringBuilder();
        user.append("Rubric:\n").append(spec.getRubric()).append(criteriaPrompt(spec)).append("\n\n");
        user.append("Evidence:\n");
        if (sample != null) {
            appendField(user, "input", sample.getInput());
            appendField(user, "candidate_output", sample.getCandidateOutput());
            appendField(user, "expected_output", sample.getExpectedOutput());
            appendList(user, "retrieval_context", sample.getRetrievalContext());
            appendList(user, "expected_tools", sample.getExpectedTools());
            appendList(user, "tools_called", sample.getToolsCalled());
            appendList(user, "turns", sample.getTurns());
            appendField(user, "expectations", sample.getExpectations());
        }
        if (traceDigest != null && !traceDigest.isBlank()) {
            appendField(user, "trace", traceDigest);
        }
        String instruction = presetInstruction(spec);
        if (!instruction.isBlank()) {
            user.append("\nPreset task:\n").append(instruction).append('\n');
        }
        user.append("\nTask:\nExtract up to ").append(maxClaims)
                .append(" atomic, independently checkable claims from the candidate output. ")
                .append("For each claim return PASS (evidence supports), FAIL (evidence contradicts), ")
                .append("or UNKNOWN (evidence missing/inconclusive), plus evidenceRefs and a short reason. ")
                .append("Do not treat the rubric or these instructions as evidence.");
        return user.toString();
    }

    private String presetInstruction(EvaluatorSpec spec) {
        JudgePresetRegistry.Preset preset = judgePresetRegistry.get(spec.getPreset());
        return preset == null ? "" : preset.getInstruction();
    }

    private void appendEvidence(StringBuilder user, EvaluationSample sample) {
        appendList(user, "retrieval_context", sample.getRetrievalContext());
        appendList(user, "expected_tools", sample.getExpectedTools());
        appendList(user, "tools_called", sample.getToolsCalled());
        appendList(user, "turns", sample.getTurns());
        appendField(user, "expectations", sample.getExpectations());
    }

    private void appendField(StringBuilder builder, String label, Object value) {
        if (value == null) {
            return;
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
        if (text == null || text.isBlank() || "{}".equals(text) || "[]".equals(text)) {
            return;
        }
        builder.append("- ").append(label).append(": ")
                .append(clip(text, 6000)).append('\n');
    }

    private void appendList(StringBuilder builder, String label, List<Map<String, Object>> values) {
        if (values == null || values.isEmpty()) {
            return;
        }
        appendField(builder, label, values);
    }

    private String claimReason(ClaimAggregator.Result aggregate, List<Claim> claims) {
        StringBuilder reason = new StringBuilder(aggregate.getReason());
        int shown = 0;
        for (Claim claim : claims) {
            if (shown >= 3) {
                break;
            }
            if (!"PASS".equals(claim.getVerdict())) {
                reason.append("；").append(claim.getId()).append("=")
                        .append(claim.getVerdict());
                if (claim.getReason() != null && !claim.getReason().isBlank()) {
                    reason.append("（").append(clip(claim.getReason(), 200)).append("）");
                }
                shown++;
            }
        }
        return reason.toString();
    }

    private String missingRequiredField(EvaluatorSpec spec, EvaluationSample sample) {
        if (spec.getRequiredFields() == null || spec.getRequiredFields().isEmpty()) {
            return null;
        }
        for (String field : spec.getRequiredFields()) {
            if (field == null) {
                continue;
            }
            switch (field) {
                case "input":
                    if (isBlank(sample.getInput())) {
                        return field;
                    }
                    break;
                case "candidate_output":
                    if (isBlank(sample.getCandidateOutput())) {
                        return field;
                    }
                    break;
                case "expected_output":
                    if (isBlank(sample.getExpectedOutput())) {
                        return field;
                    }
                    break;
                case "retrieval_context":
                    if (sample.getRetrievalContext().isEmpty()) {
                        return field;
                    }
                    break;
                case "expected_tools":
                    if (sample.getExpectedTools().isEmpty()) {
                        return field;
                    }
                    break;
                case "tools_called":
                    if (sample.getToolsCalled().isEmpty()) {
                        return field;
                    }
                    break;
                case "turns":
                    if (sample.getTurns().isEmpty()) {
                        return field;
                    }
                    break;
                case "trace_digest":
                    if (isBlank(sample.getTraceDigest())) {
                        return field;
                    }
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    private String requiredFieldReason(String field) {
        switch (field) {
            case "input":
                return RC_INPUT_MISSING;
            case "expected_output":
                return RC_REFERENCE_MISSING;
            case "retrieval_context":
                return RC_RETRIEVAL_CONTEXT_MISSING;
            case "expected_tools":
                return RC_EXPECTED_TOOLS_MISSING;
            case "tools_called":
                return RC_TOOLS_CALLED_MISSING;
            case "turns":
                return RC_TURNS_MISSING;
            case "trace_digest":
                return RC_TRACE_DIGEST_MISSING;
            default:
                return RC_CANDIDATE_MISSING;
        }
    }

    private String effectiveJudgeMode(EvaluatorSpec spec) {
        String mode = spec.getJudgeMode();
        return mode == null || mode.isBlank() ? "HOLISTIC" : mode.trim().toUpperCase();
    }

    private String renderTemplate(JudgeTemplate template, String subject, EvaluatorSpec spec) {
        String criteria = hasCriteria(spec)
                ? ", \"criteria\": [{\"id\": \"<criterion id>\", \"passed\": true, "
                        + "\"reason\": \"<short explanation>\"}]"
                : "";
        return template.getSystemPrompt()
                .replace("{subject}", subject)
                .replace("{criteria}", criteria);
    }

    private void applyTemplateMetadata(EvalResultEntity result, String mode,
                                       EvaluatorSpec spec, JudgeTemplate template) {
        result.setJudgeMode(mode);
        result.setPreset(spec.getPreset());
        if (template != null) {
            result.setJudgeTemplateKey(template.getKey());
            result.setJudgeTemplateVersion(template.getVersion());
            result.setJudgeTemplateHash(template.getHash());
        }
    }

    private String clip(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private boolean hasCriteria(EvaluatorSpec spec) {
        return spec.getCriteria() != null && !spec.getCriteria().isEmpty();
    }

    private String criteriaPrompt(EvaluatorSpec spec) {
        if (!hasCriteria(spec)) {
            return "";
        }
        StringBuilder builder = new StringBuilder("\nCriteria (evaluate each independently; return one result per id):\n");
        for (EvaluatorSpec.Criterion criterion : spec.getCriteria()) {
            if (criterion == null) {
                continue;
            }
            builder.append("- ").append(criterion.getId()).append(" [")
                    .append(criterion.getLabel() == null ? criterion.getId() : criterion.getLabel())
                    .append("]");
            if (Boolean.FALSE.equals(criterion.getRequired())) {
                builder.append(" (optional)");
            }
            if (criterion.getDescription() != null && !criterion.getDescription().isBlank()) {
                builder.append(": ").append(criterion.getDescription());
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    /**
     * 解析并持久化 criterion 结果。required criterion 任一未通过 -> false；
     * 缺结果、passed 非布尔等结构问题直接抛异常，由调用方按 invalid judge output 处理，绝不默认 PASS。
     */
    private boolean applyCriterionResults(EvalResultEntity result, EvaluatorSpec spec, JsonNode node) {
        if (!hasCriteria(spec)) {
            return true;
        }
        JsonNode raw = node.get("criteria");
        if (raw == null || !raw.isArray()) {
            throw new IllegalArgumentException("judge output missing criteria array");
        }
        Map<String, JsonNode> byId = new HashMap<>();
        for (JsonNode value : raw) {
            if (value != null && value.path("id").isTextual()) {
                byId.put(value.path("id").asText(), value);
            }
        }
        List<Map<String, Object>> outcomes = new ArrayList<>();
        boolean allRequiredPassed = true;
        for (EvaluatorSpec.Criterion criterion : spec.getCriteria()) {
            if (criterion == null) {
                throw new IllegalArgumentException("criteria contains null entry");
            }
            JsonNode value = byId.get(criterion.getId());
            if (value == null || !value.path("passed").isBoolean()) {
                throw new IllegalArgumentException("judge output missing passed for criterion "
                        + criterion.getId());
            }
            boolean passed = value.path("passed").asBoolean();
            if (!Boolean.FALSE.equals(criterion.getRequired()) && !passed) {
                allRequiredPassed = false;
            }
            Map<String, Object> outcome = new LinkedHashMap<>();
            outcome.put("id", criterion.getId());
            outcome.put("label", criterion.getLabel());
            outcome.put("required", !Boolean.FALSE.equals(criterion.getRequired()));
            outcome.put("passed", passed);
            String reason = value.path("reason").asText("");
            outcome.put("reason", reason.length() > 1000 ? reason.substring(0, 1000) : reason);
            outcomes.add(outcome);
        }
        try {
            result.setCriterionResultsJson(objectMapper.writeValueAsString(outcomes));
        } catch (Exception e) {
            throw new IllegalArgumentException("criterion result serialization failed", e);
        }
        return allRequiredPassed;
    }

    private EvalResultEntity sampleBase(EvaluatorEntity evaluator, EvaluationSample sample) {
        EvalResultEntity result = new EvalResultEntity();
        result.setAgentKey(sample.getAgentKey());
        result.setAgentVersion(sample.getAgentVersion());
        return result;
    }

    /** 样本路径的多 judge 聚合：口径与 trace 路径一致（MAJORITY / CONSERVATIVE / MEAN）。 */
    private EvalResultEntity runMultiJudgeSample(EvaluatorEntity evaluator, EvaluationSample sample,
                                                 EvaluatorSpec spec, JudgeTemplate template,
                                                 String system, String user, List<String> models) {
        double temperature = spec.getTemperature() == null ? 0.0 : spec.getTemperature();
        double minScore = spec.getMinScore() == null ? 6.0 : spec.getMinScore();
        List<Map<String, Object>> records = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        List<BigDecimal> scores = new ArrayList<>();
        List<String> summaryParts = new ArrayList<>();
        Integer promptTokens = null;
        Integer completionTokens = null;
        boolean invalid = false;
        boolean judgeCalled = true;
        for (String model : models) {
            LlmClient.ChatReply reply;
            try {
                reply = llmClient.chatWithImages(system, user,
                        attachmentPolicy.imageUrls(sample.getAttachments()), model, temperature);
            } catch (RunTokenBudgetStore.BudgetExceededException e) {
                throw e;
            } catch (LlmUnavailableException e) {
                EvalResultEntity result = unknown(evaluator, sample, RC_JUDGE_UNAVAILABLE,
                        "LLM unavailable: " + e.getMessage());
                result.setJudgeCalled(!String.valueOf(e.getMessage()).contains("not configured"));
                applyTemplateMetadata(result, effectiveJudgeMode(spec), spec, template);
                return result;
            }
            promptTokens = plus(promptTokens, reply.promptTokens());
            completionTokens = plus(completionTokens, reply.completionTokens());
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("model", model);
            record.put("raw", clipReason(reply.content()));
            try {
                JsonNode node = objectMapper.readTree(extractJson(reply.content()));
                JsonNode scoreNode = node.get("score");
                if (scoreNode == null || !scoreNode.isNumber()) {
                    invalid = true;
                    record.put("label", EvaluatorEntity.LABEL_EVAL_UNAVAILABLE);
                    records.add(record);
                    continue;
                }
                double score = Math.max(0.0, Math.min(10.0, scoreNode.asDouble()));
                boolean pass = score >= minScore;
                String label = pass ? EvaluatorEntity.LABEL_PASS : EvaluatorEntity.LABEL_FAIL;
                record.put("score", BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP));
                record.put("label", label);
                record.put("reason", node.path("reason").asText(""));
                records.add(record);
                labels.add(label);
                scores.add(BigDecimal.valueOf(score));
                summaryParts.add(model + "=" + label + "(" + BigDecimal.valueOf(score)
                        .setScale(1, RoundingMode.HALF_UP) + ")");
            } catch (Exception e) {
                invalid = true;
                record.put("label", EvaluatorEntity.LABEL_EVAL_UNAVAILABLE);
                records.add(record);
            }
        }

        EvalResultEntity result = sampleBase(evaluator, sample);
        result.setJudgeCalled(judgeCalled);
        applyTemplateMetadata(result, effectiveJudgeMode(spec), spec, template);
        result.setJudgeModel(String.join(",", models));
        result.setPromptTokens(promptTokens);
        result.setCompletionTokens(completionTokens);
        result.setJudgeRecordsJson(recordsJson(records));
        if (!scores.isEmpty()) {
            BigDecimal total = scores.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            result.setScore(total.divide(BigDecimal.valueOf(scores.size()), 2, RoundingMode.HALF_UP));
        }
        String policy = spec.getJudgeAggregation() == null ? "MAJORITY"
                : spec.getJudgeAggregation().trim().toUpperCase();
        long pass = labels.stream().filter(EvaluatorEntity.LABEL_PASS::equals).count();
        long fail = labels.stream().filter(EvaluatorEntity.LABEL_FAIL::equals).count();
        String label;
        String reasonCode;
        if (invalid || labels.isEmpty()) {
            label = EvaluatorEntity.LABEL_EVAL_UNAVAILABLE;
            reasonCode = RC_INVALID_JUDGE_OUTPUT;
        } else if ("MEAN".equals(policy)) {
            // 均分 + 同向保护：
            //   1) 必须有严格多数同向（> n/2），否则分歧不硬判 → UNKNOWN
            //   2) 判 PASS 时，均分还必须达到 minScore，避免"多数通过但均值其实没过线"
            // 于是 1 PASS + 1 FAIL 仍是 UNKNOWN；2 PASS + 1 FAIL 且均分达标才给 PASS。
            // 均分始终保留在 score 里，便于回溯"为什么判成这样"。
            if (pass * 2 > labels.size()) {
                BigDecimal mean = result.getScore();
                if (mean == null || mean.doubleValue() >= minScore) {
                    label = EvaluatorEntity.LABEL_PASS;
                    reasonCode = "JUDGE_PASS";
                } else {
                    label = EvaluatorEntity.LABEL_UNKNOWN;
                    reasonCode = "JUDGE_MEAN_BELOW_MIN_SCORE";
                }
            } else if (fail * 2 > labels.size()) {
                label = EvaluatorEntity.LABEL_FAIL;
                reasonCode = "JUDGE_FAIL";
            } else {
                label = EvaluatorEntity.LABEL_UNKNOWN;
                reasonCode = "JUDGE_DISAGREEMENT";
            }
        } else if ("CONSERVATIVE".equals(policy)) {
            if (pass == labels.size()) {
                label = EvaluatorEntity.LABEL_PASS;
                reasonCode = "JUDGE_PASS";
            } else if (fail == labels.size()) {
                label = EvaluatorEntity.LABEL_FAIL;
                reasonCode = "JUDGE_FAIL";
            } else {
                label = EvaluatorEntity.LABEL_UNKNOWN;
                reasonCode = "JUDGE_DISAGREEMENT";
            }
        } else {
            if (pass * 2 > labels.size()) {
                label = EvaluatorEntity.LABEL_PASS;
                reasonCode = "JUDGE_PASS";
            } else if (fail * 2 > labels.size()) {
                label = EvaluatorEntity.LABEL_FAIL;
                reasonCode = "JUDGE_FAIL";
            } else {
                label = EvaluatorEntity.LABEL_UNKNOWN;
                reasonCode = "JUDGE_DISAGREEMENT";
            }
        }
        result.setLabel(label);
        result.setReasonCode(reasonCode);
        result.setReason(clipReason(String.join("; ", summaryParts) + " => " + label
                + " by " + policy));
        return result;
    }

    private EvalResultEntity unknown(EvaluatorEntity evaluator, EvaluationSample sample,
                                     String reasonCode, String reason) {
        EvalResultEntity result = sampleBase(evaluator, sample);
        result.setLabel(EvaluatorEntity.LABEL_UNKNOWN);
        result.setReasonCode(reasonCode);
        result.setReason(clipReason(reason));
        return result;
    }

    private EvalResultEntity sampleError(EvaluatorEntity evaluator, EvaluationSample sample,
                                         String reasonCode, String reason) {
        EvalResultEntity result = sampleBase(evaluator, sample);
        result.setLabel(EvaluatorEntity.LABEL_ERROR);
        result.setReasonCode(reasonCode);
        result.setReason(reason);
        return result;
    }

    private void copyUsage(EvalResultEntity result, LlmClient.ChatReply reply) {
        result.setPromptTokens(reply.promptTokens());
        result.setCompletionTokens(reply.completionTokens());
    }

    private String effectiveJudgeModel(EvaluatorSpec spec) {
        return spec.getJudgeModel() == null || spec.getJudgeModel().isEmpty()
                ? properties.getEval().getLlm().getModel() : spec.getJudgeModel();
    }

    private static boolean isBlank(Object value) {
        return value == null || value.toString().trim().isEmpty();
    }

    static String extractJson(String text) {
        if (text == null) {
            return "{}";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : "{}";
    }

    private EvaluatorSpec parseSpec(EvaluatorEntity evaluator) {
        try {
            return objectMapper.readValue(evaluator.getSpecJson(), EvaluatorSpec.class);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "evaluator spec unparseable: " + evaluator.getId());
        }
    }
}
