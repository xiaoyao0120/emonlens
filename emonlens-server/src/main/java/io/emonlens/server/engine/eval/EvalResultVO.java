package io.emonlens.server.engine.eval;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import io.emonlens.server.storage.entity.EvalResultEntity;

import lombok.Data;

@Data
public class EvalResultVO {

    private String id;
    private String evaluatorKey;
    private Integer evaluatorVersion;
    private String agentKey;
    private String traceId;
    private Integer attempt;
    private BigDecimal score;
    private String label;
    private BigDecimal confidence;
    private String reason;
    /** 结论原因码：界面翻成中文展示，原始码留在 tooltip 里供排查 */
    private String reasonCode;
    private String judgeModel;
    /** LLM_JUDGE criterion 级结果原始 JSON；无 criteria 配置时为 null */
    private String criterionResultsJson;
    /** HOLISTIC / CLAIMS */
    private String judgeMode;
    private String preset;
    private String judgeTemplateKey;
    private Integer judgeTemplateVersion;
    private String judgeTemplateHash;
    private String claimsJson;
    private String claimAggregateJson;
    private Integer promptTokens;
    private Integer completionTokens;
    private LocalDateTime createdAt;

    public static EvalResultVO from(EvalResultEntity entity) {
        EvalResultVO vo = new EvalResultVO();
        vo.setId(entity.getId());
        vo.setEvaluatorKey(entity.getEvaluatorKey());
        vo.setEvaluatorVersion(entity.getEvaluatorVersion());
        vo.setAgentKey(entity.getAgentKey());
        vo.setTraceId(entity.getTraceId());
        vo.setAttempt(entity.getAttempt());
        vo.setScore(entity.getScore());
        vo.setLabel(entity.getLabel());
        vo.setConfidence(entity.getConfidence());
        vo.setReason(entity.getReason());
        vo.setReasonCode(entity.getReasonCode());
        vo.setJudgeModel(entity.getJudgeModel());
        vo.setCriterionResultsJson(entity.getCriterionResultsJson());
        vo.setJudgeMode(entity.getJudgeMode());
        vo.setPreset(entity.getPreset());
        vo.setJudgeTemplateKey(entity.getJudgeTemplateKey());
        vo.setJudgeTemplateVersion(entity.getJudgeTemplateVersion());
        vo.setJudgeTemplateHash(entity.getJudgeTemplateHash());
        vo.setClaimsJson(entity.getClaimsJson());
        vo.setClaimAggregateJson(entity.getClaimAggregateJson());
        vo.setPromptTokens(entity.getPromptTokens());
        vo.setCompletionTokens(entity.getCompletionTokens());
        vo.setCreatedAt(entity.getCreatedAt());
        return vo;
    }
}
