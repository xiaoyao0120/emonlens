package io.emonlens.server.engine.eval;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.emonlens.server.api.dto.PageVO;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.emonlens.common.EmonLensException;
import io.emonlens.common.ErrorCode;
import io.emonlens.common.Sha256Utils;
import io.emonlens.server.storage.entity.EvaluatorEntity;
import io.emonlens.server.storage.entity.EvaluatorVersionEntity;
import io.emonlens.server.storage.mapper.EvaluatorMapper;
import io.emonlens.server.storage.mapper.EvaluatorVersionMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import io.emonlens.server.auth.WorkspaceGuard;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluatorService {

    /** LLM_JUDGE 证据字段与 SDK scorer 输入契约共用的样本字段词表 */
    static final java.util.Set<String> SUPPORTED_SAMPLE_FIELDS = java.util.Set.of(
            "input", "candidate_output", "expected_output", "retrieval_context",
            "expected_tools", "tools_called", "turns", "trace_digest");

    private final EvaluatorMapper evaluatorMapper;
    private final EvaluatorVersionMapper evaluatorVersionMapper;
    private final ObjectMapper objectMapper;
    private final JudgeTemplateService judgeTemplateService;
    private final JudgePresetRegistry judgePresetRegistry;

    @Transactional
    public EvaluatorEntity create(String workspaceId, String evaluatorKey, String type,
                                  Map<String, Object> spec) {
        spec = judgePresetRegistry.applyDefaults(type, spec);
        List<String> errors = validateSpec(workspaceId, type, spec);
        if (!errors.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, String.join("; ", errors));
        }
        spec = pinJudgeTemplateVersion(workspaceId, type, spec);
        Long exists = evaluatorMapper.selectCount(Wrappers.<EvaluatorEntity>lambdaQuery()
                .eq(EvaluatorEntity::getWorkspaceId, workspaceId)
                .eq(EvaluatorEntity::getEvaluatorKey, evaluatorKey));
        if (exists != null && exists > 0) {
            throw new EmonLensException(ErrorCode.CONFLICT, "evaluator_key already exists: " + evaluatorKey);
        }
        EvaluatorEntity entity = new EvaluatorEntity();
        entity.setWorkspaceId(workspaceId);
        entity.setEvaluatorKey(evaluatorKey);
        entity.setType(type);
        entity.setSpecJson(toJson(spec));
        entity.setEnabled(1);
        entity.setVersion(1);
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());
        evaluatorMapper.insert(entity);
        insertVersion(entity);
        return entity;
    }

    @Transactional
    public EvaluatorEntity update(String id, Map<String, Object> spec) {
        EvaluatorEntity entity = getById(id);
        if (spec == null) {
            return entity;
        }
        spec = judgePresetRegistry.applyDefaults(entity.getType(), spec);
        List<String> errors = validateSpec(entity.getWorkspaceId(), entity.getType(), spec);
        if (!errors.isEmpty()) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, String.join("; ", errors));
        }
        spec = pinJudgeTemplateVersion(entity.getWorkspaceId(), entity.getType(), spec);
        String nextSpec = toJson(spec);
        if (nextSpec.equals(entity.getSpecJson())) {
            return entity;
        }
        entity.setSpecJson(nextSpec);
        entity.setVersion(entity.getVersion() == null ? 2 : entity.getVersion() + 1);
        entity.setUpdatedAt(LocalDateTime.now());
        evaluatorMapper.updateById(entity);
        insertVersion(entity);
        return entity;
    }

    /** 历史评估器可能没有版本行；读取时补一条当前版本，保证旧定义也可追溯。 */
    @Transactional
    public List<EvaluatorVersionEntity> versions(String id) {
        EvaluatorEntity entity = getById(id);
        List<EvaluatorVersionEntity> rows = evaluatorVersionMapper.selectList(
                Wrappers.<EvaluatorVersionEntity>lambdaQuery()
                        .eq(EvaluatorVersionEntity::getWorkspaceId, entity.getWorkspaceId())
                        .eq(EvaluatorVersionEntity::getEvaluatorId, entity.getId())
                        .orderByDesc(EvaluatorVersionEntity::getVersion));
        if (rows.isEmpty()) {
            insertVersion(entity);
            rows = evaluatorVersionMapper.selectList(Wrappers.<EvaluatorVersionEntity>lambdaQuery()
                    .eq(EvaluatorVersionEntity::getWorkspaceId, entity.getWorkspaceId())
                    .eq(EvaluatorVersionEntity::getEvaluatorId, entity.getId())
                    .orderByDesc(EvaluatorVersionEntity::getVersion));
        }
        return rows;
    }

    public EvaluatorVersionEntity currentVersion(EvaluatorEntity entity) {
        List<EvaluatorVersionEntity> rows = versions(entity.getId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void insertVersion(EvaluatorEntity entity) {
        EvaluatorVersionEntity version = new EvaluatorVersionEntity();
        version.setWorkspaceId(entity.getWorkspaceId());
        version.setEvaluatorId(entity.getId());
        version.setVersion(entity.getVersion() == null ? 1 : entity.getVersion());
        version.setType(entity.getType());
        version.setSpecJson(entity.getSpecJson());

        String templateMaterial = "";
        if (EvaluatorEntity.TYPE_LLM_JUDGE.equals(entity.getType())) {
            EvaluatorSpec spec = parseSpec(entity);
            JudgeTemplate template = judgeTemplateService.resolve(entity.getWorkspaceId(),
                    spec == null ? null : spec.getJudgeTemplateKey(),
                    spec == null ? null : spec.getJudgeTemplateVersion());
            version.setTemplateKey(template.getKey());
            version.setTemplateVersion(template.getVersion());
            version.setTemplateHash(template.getHash());
            templateMaterial = "|" + template.getHash();
        }
        version.setFingerprint(Sha256Utils.hash(
                entity.getType() + "|" + canonicalSpec(entity.getSpecJson()) + templateMaterial));
        version.setCreatedAt(LocalDateTime.now());
        evaluatorVersionMapper.insert(version);
    }

    private String canonicalSpec(String specJson) {
        try {
            Object value = objectMapper.readValue(specJson, Object.class);
            ObjectMapper canonical = objectMapper.copy();
            canonical.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
            return canonical.writeValueAsString(value);
        } catch (Exception e) {
            return specJson == null ? "" : specJson;
        }
    }

    /**
     * 未显式指定模板版本时，在创建/编辑时固定到当时的已发布版本。
     * 否则“最新版本”会在运行时漂移，评估器版本 fingerprint 与真实判分语义不一致。
     */
    private Map<String, Object> pinJudgeTemplateVersion(String workspaceId, String type,
                                                        Map<String, Object> spec) {
        if (spec == null || !EvaluatorEntity.TYPE_LLM_JUDGE.equals(type)
                || spec.get("judgeTemplateVersion") != null) {
            return spec;
        }
        Object key = spec.get("judgeTemplateKey");
        JudgeTemplate template = judgeTemplateService.resolve(workspaceId,
                key == null ? null : String.valueOf(key), null);
        spec.put("judgeTemplateVersion", template.getVersion());
        return spec;
    }

    public EvaluatorEntity getById(String id) {
        EvaluatorEntity entity = evaluatorMapper.selectById(id);
        if (entity == null) {
            throw new EmonLensException(ErrorCode.NOT_FOUND, "evaluator not found: " + id);
        }
        WorkspaceGuard.owned(entity.getWorkspaceId());
        return entity;
    }

    public Page<EvaluatorEntity> page(String workspaceId, int pageNumber, int pageSize) {
        LambdaQueryWrapper<EvaluatorEntity> wrapper = Wrappers.lambdaQuery(EvaluatorEntity.class);
        wrapper.eq(EvaluatorEntity::getWorkspaceId, workspaceId);
        wrapper.orderByDesc(EvaluatorEntity::getCreatedAt);
        return evaluatorMapper.selectPage(PageVO.safePage(pageNumber, pageSize), wrapper);
    }

    public List<EvaluatorEntity> listEnabled(String workspaceId) {
        return evaluatorMapper.selectList(Wrappers.<EvaluatorEntity>lambdaQuery()
                .eq(EvaluatorEntity::getWorkspaceId, workspaceId)
                .eq(EvaluatorEntity::getEnabled, 1));
    }

    public EvaluatorEntity setEnabled(String id, boolean enabled) {
        EvaluatorEntity entity = getById(id);
        entity.setEnabled(enabled ? 1 : 0);
        entity.setUpdatedAt(LocalDateTime.now());
        evaluatorMapper.updateById(entity);
        return entity;
    }

    /** 删除评估器定义；调用方负责先清理 et_eval_result 等关联数据（见 EvaluatorController.delete） */
    public void remove(String id) {
        getById(id);
        evaluatorMapper.deleteById(id);
    }

    public List<String> validateSpec(String workspaceId, String type, Map<String, Object> spec) {
        List<String> errors = new ArrayList<>();
        EvaluatorSpec parsed;
        try {
            parsed = objectMapper.convertValue(spec == null ? Map.of() : spec, EvaluatorSpec.class);
        } catch (IllegalArgumentException e) {
            errors.add("spec has invalid field type: " + e.getMessage());
            return errors;
        }
        if (parsed.getQualityDimension() != null && !parsed.getQualityDimension().isBlank()
                && !parsed.getQualityDimension().matches("[a-z][a-z0-9_-]{0,63}")) {
            errors.add("qualityDimension 必须匹配 [a-z][a-z0-9_-]{0,63}");
            return errors;
        }
        if (EvaluatorEntity.TYPE_EXPRESSION.equals(type)) {
            if (parsed.getPassWhen() == null || parsed.getPassWhen().isEmpty()) {
                errors.add("expression evaluator requires 'passWhen'");
            }
            return errors;
        }
        if (EvaluatorEntity.TYPE_REGEX.equals(type)) {
            if (parsed.getPattern() == null || parsed.getPattern().isEmpty()) {
                errors.add("regex evaluator requires 'pattern'");
                return errors;
            }
            if (parsed.getPattern().length() > SafeRegex.MAX_PATTERN_LENGTH) {
                errors.add("pattern too long: max " + SafeRegex.MAX_PATTERN_LENGTH + " chars");
                return errors;
            }
            try {
                java.util.regex.Pattern.compile(parsed.getPattern());
            } catch (Exception e) {
                errors.add("invalid pattern: " + e.getMessage());
            }
            return errors;
        }
        if (EvaluatorEntity.TYPE_TRAJECTORY.equals(type)) {
            if (parsed.getTrajectoryMode() != null && !parsed.getTrajectoryMode().isBlank()
                    && !"SUBSET".equals(parsed.getTrajectoryMode())
                    && !"ORDER".equals(parsed.getTrajectoryMode())
                    && !"EXACT".equals(parsed.getTrajectoryMode())) {
                errors.add("trajectoryMode must be SUBSET/ORDER/EXACT");
            }
            if (parsed.getExpectedTools() != null) {
                if (parsed.getExpectedTools().size() > 50) {
                    errors.add("expectedTools supports at most 50 items");
                }
                for (EvaluatorSpec.ExpectedTool tool : parsed.getExpectedTools()) {
                    if (tool == null || tool.getName() == null
                            || !tool.getName().matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
                        errors.add("expectedTools[].name must match [A-Za-z][A-Za-z0-9_.-]{0,127}");
                    }
                }
            }
            if (parsed.getForbiddenTools() != null && parsed.getForbiddenTools().size() > 50) {
                errors.add("forbiddenTools supports at most 50 items");
            }
            if (parsed.getMaxSteps() != null && (parsed.getMaxSteps() < 1 || parsed.getMaxSteps() > 1000)) {
                errors.add("maxSteps must be 1..1000");
            }
            if (parsed.getArgumentRules() != null) {
                if (parsed.getArgumentRules().size() > 50) {
                    errors.add("argumentRules supports at most 50 items");
                }
                for (EvaluatorSpec.ArgumentRule rule : parsed.getArgumentRules()) {
                    if (rule == null || rule.getTool() == null || rule.getTool().isBlank()
                            || rule.getPath() == null || rule.getPath().isBlank()) {
                        errors.add("argumentRules[].tool/path is required");
                        continue;
                    }
                    if (rule.getRegex() != null && !rule.getRegex().isBlank()) {
                        try {
                            java.util.regex.Pattern.compile(rule.getRegex());
                        } catch (Exception e) {
                            errors.add("argumentRules[].regex invalid: " + e.getMessage());
                        }
                    }
                }
            }
            return errors;
        }
        if (EvaluatorEntity.TYPE_LLM_JUDGE.equals(type)) {
            if (parsed.getRubric() == null || parsed.getRubric().isEmpty()) {
                errors.add("llm_judge evaluator requires 'rubric'");
            }
            if (parsed.getPreset() != null && !parsed.getPreset().isBlank()
                    && judgePresetRegistry.get(parsed.getPreset()) == null) {
                errors.add("unknown judge preset: " + parsed.getPreset());
            }
            String judgeMode = parsed.getJudgeMode();
            if (judgeMode != null && !judgeMode.isBlank()
                    && !"HOLISTIC".equals(judgeMode) && !"CLAIMS".equals(judgeMode)) {
                errors.add("judgeMode must be HOLISTIC or CLAIMS");
            }
            EvaluatorSpec.ClaimsPolicy policy = parsed.getClaimsPolicy();
            if (policy != null) {
                if (policy.getMaxClaims() != null
                        && (policy.getMaxClaims() < 1 || policy.getMaxClaims() > 20)) {
                    errors.add("claimsPolicy.maxClaims must be 1..20");
                }
                if (policy.getUnknownPolicy() != null
                        && !"PASS".equals(policy.getUnknownPolicy())
                        && !"FAIL".equals(policy.getUnknownPolicy())
                        && !"BLOCK".equals(policy.getUnknownPolicy())) {
                    errors.add("claimsPolicy.unknownPolicy must be PASS/FAIL/BLOCK");
                }
                if (policy.getMinPassRatio() != null
                        && (policy.getMinPassRatio() < 0 || policy.getMinPassRatio() > 1)) {
                    errors.add("claimsPolicy.minPassRatio must be 0..1");
                }
            }
            if (parsed.getRequiredFields() != null) {
                for (String field : parsed.getRequiredFields()) {
                    if (field == null || !SUPPORTED_SAMPLE_FIELDS.contains(field)) {
                        errors.add("requiredFields contains unsupported field: " + field);
                    }
                }
            }
            try {
                judgeTemplateService.resolve(workspaceId, parsed.getJudgeTemplateKey(),
                        parsed.getJudgeTemplateVersion());
            } catch (EmonLensException e) {
                errors.add(e.getMessage());
            }
            if (parsed.getCriteria() != null && !parsed.getCriteria().isEmpty()) {
                if (parsed.getCriteria().size() > 10) {
                    errors.add("criteria supports at most 10 items");
                }
                java.util.Set<String> ids = new java.util.HashSet<>();
                for (EvaluatorSpec.Criterion criterion : parsed.getCriteria()) {
                    if (criterion == null || criterion.getId() == null || criterion.getId().isBlank()
                            || !criterion.getId().matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) {
                        errors.add("criterion id must match [A-Za-z][A-Za-z0-9_-]{0,31}");
                        continue;
                    }
                    if (!ids.add(criterion.getId())) {
                        errors.add("duplicate criterion id: " + criterion.getId());
                    }
                    if (criterion.getLabel() == null || criterion.getLabel().isBlank()) {
                        errors.add("criterion label is required: " + criterion.getId());
                    }
                    if (criterion.getDescription() != null && criterion.getDescription().length() > 1000) {
                        errors.add("criterion description too long: " + criterion.getId());
                    }
                }
            }
            return errors;
        }
        if (EvaluatorEntity.TYPE_SDK.equals(type)) {
            if (parsed.getRequiredFields() == null || parsed.getRequiredFields().isEmpty()) {
                errors.add("sdk evaluator requires 'requiredFields'（ scorer 需要的样本字段）");
                return errors;
            }
            for (String field : parsed.getRequiredFields()) {
                if (field == null || !SUPPORTED_SAMPLE_FIELDS.contains(field)) {
                    errors.add("requiredFields contains unsupported field: " + field);
                }
            }
            if (parsed.getScorerRef() != null && parsed.getScorerRef().length() > 256) {
                errors.add("scorerRef too long: max 256 chars");
            }
            return errors;
        }
        errors.add("type must be EXPRESSION, REGEX, LLM_JUDGE, TRAJECTORY or SDK");
        return errors;
    }

    public EvaluatorSpec parseSpec(EvaluatorEntity evaluator) {
        try {
            return objectMapper.readValue(evaluator.getSpecJson(), EvaluatorSpec.class);
        } catch (Exception e) {
            log.error("failed to parse evaluator spec, id={}", evaluator.getId(), e);
            return null;
        }
    }

    private String toJson(Map<String, Object> spec) {
        try {
            return objectMapper.writeValueAsString(spec);
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "spec is not valid JSON object");
        }
    }

    public Map<String, Object> fromJson(String specJson) {
        try {
            return objectMapper.readValue(specJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new EmonLensException(ErrorCode.BAD_REQUEST, "spec_json is not valid JSON object");
        }
    }
}
