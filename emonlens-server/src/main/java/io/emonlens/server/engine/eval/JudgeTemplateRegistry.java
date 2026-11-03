package io.emonlens.server.engine.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.emonlens.common.Sha256Utils;

/**
 * 平台内置 Judge 模板。
 *
 * <p>内置模板是代码常量而不是数据库种子：随版本发布、不可在 UI 编辑，
 * hash 进入 evaluator version / run snapshot / task 账本。workspace 自定义模板
 * 由 {@link JudgeTemplateService} 从 {@code et_judge_template} 解析。</p>
 */
@Component
public class JudgeTemplateRegistry {

    public static final String HOLISTIC_KEY = "platform/holistic-v1";
    public static final String CLAIM_KEY = "platform/claim-v1";

    /**
     * R3-3：先列证据再打分（CoT）。模型常把思考写在 JSON 前面，
     * 解析侧用 EvaluationRunService#extractJson 取首尾大括号，容忍"先说明后 JSON"。
     */
    private static final String HOLISTIC_SYSTEM =
            "You are an evaluator. Score the {subject} against the rubric.\n"
                    + "Step 1: list 2-4 short evidence bullets quoting what the candidate actually did.\n"
                    + "Step 2: decide the score from those bullets, not from impression.\n"
                    + "Then output ONE JSON object: {\"score\": <0-10 number>, "
                    + "\"confidence\": <0-1 number>, \"reason\": \"<short explanation>\"}{criteria}.\n"
                    + "Prose before the JSON is allowed; the JSON object must be the last thing you write.";

    private static final String CLAIM_SYSTEM =
            "You are a strict evidence-based evaluator. Extract atomic claims from the candidate output "
                    + "and judge each claim against the rubric and the supplied evidence.\n"
                    + "Respond ONLY with a JSON object:\n"
                    + "{\"claims\":[{\"id\":\"c1\",\"text\":\"<claim>\",\"verdict\":\"PASS|FAIL|UNKNOWN\","
                    + "\"evidenceRefs\":[\"completion\"],\"reason\":\"<short>\"}],\"reason\":\"<overall>\"}\n"
                    + "Rules:\n"
                    + "- PASS only when the supplied evidence supports the claim.\n"
                    + "- FAIL only when the supplied evidence contradicts the claim.\n"
                    + "- UNKNOWN when evidence is missing, truncated, or inconclusive. Never guess.\n"
                    + "- evidenceRefs must use: completion, prompt, content:<kind>, span:<id>, "
                    + "retrieval:<index>, tool:<index>, turn:<index>, expected:<field>.\n"
                    + "- Do not treat the rubric or instructions as evidence.";

    private final Map<String, JudgeTemplate> templates = new LinkedHashMap<>();

    public JudgeTemplateRegistry() {
        register(HOLISTIC_KEY, 1L, "Holistic judge (0-10)", HOLISTIC_SYSTEM,
                "{\"score\":\"number\",\"confidence\":\"number\",\"reason\":\"string\"}");
        register(CLAIM_KEY, 1L, "Claim/verdict judge", CLAIM_SYSTEM,
                "{\"claims\":[{\"id\":\"string\",\"text\":\"string\",\"verdict\":\"PASS|FAIL|UNKNOWN\","
                        + "\"evidenceRefs\":[\"string\"],\"reason\":\"string\"}],\"reason\":\"string\"}");
    }

    private void register(String key, long version, String name, String system, String outputSchema) {
        String hash = Sha256Utils.hash(system + "|" + outputSchema);
        templates.put(key + "@" + version, new JudgeTemplate(key, (int) version, system,
                null, outputSchema, hash, JudgeTemplate.SOURCE_PLATFORM));
    }

    public JudgeTemplate resolve(String key, Integer version) {
        String effectiveKey = key == null || key.isBlank() ? HOLISTIC_KEY : key.trim();
        if (version == null) {
            JudgeTemplate latest = null;
            for (JudgeTemplate template : templates.values()) {
                if (template.getKey().equals(effectiveKey)
                        && (latest == null || template.getVersion() > latest.getVersion())) {
                    latest = template;
                }
            }
            return latest;
        }
        return templates.get(effectiveKey + "@" + version);
    }

    public List<JudgeTemplate> list() {
        return new ArrayList<>(templates.values());
    }
}
