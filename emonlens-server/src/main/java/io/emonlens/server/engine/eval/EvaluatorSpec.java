package io.emonlens.server.engine.eval;

import java.util.List;

import lombok.Data;

/**
 * Evaluator DSL（et_evaluator.spec_json）。
 * EXPRESSION：trace 级 SpEL，true=PASS；
 * REGEX：对采集内容做匹配断言（requireMatch=true 表示命中才 PASS）；
 * LLM_JUDGE：rubric + trace 摘要交 LLM 评分，score>=minScore 判 PASS。
 */
@Data
public class EvaluatorSpec {

    private String type;

    /** 可选：映射到 Domain Pack qualityModel.dimensions[].key，用于质量模型运行时报告。 */
    private String qualityDimension;

    /** EXPRESSION：trace 级上下文 SpEL 布尔表达式，true=PASS */
    private String passWhen;

    /** REGEX：作用内容（prompt|completion|tool_payload），默认 completion */
    private String field;

    /** REGEX：正则表达式 */
    private String pattern;

    /** REGEX：命中正则才 PASS（默认 true）；false 表示命中即 FAIL */
    private Boolean requireMatch;

    /** LLM_JUDGE：评审 rubric */
    private String rubric;

    /**
     * LLM_JUDGE：可选的逐条评分维度。required=true（默认）时，任一未通过即整体 FAIL；
     * 模型必须为每个 criterion 返回 passed，否则按不可用处理。
     */
    private List<Criterion> criteria;

    /** LLM_JUDGE：采样温度，默认 0 */
    private Double temperature;

    /** LLM_JUDGE：PASS 最低分（0-10），默认 6；CLAIMS 模式使用 claimsPolicy.minPassRatio */
    private Double minScore;

    /** LLM_JUDGE：HOLISTIC（默认）/ CLAIMS；CLAIMS 逐条抽取 claim 并判 PASS/FAIL/UNKNOWN */
    private String judgeMode;

    /** LLM_JUDGE：平台或 workspace 模板 key；空 = platform/holistic-v1 */
    private String judgeTemplateKey;

    /** LLM_JUDGE：模板版本；空 = 该 key 最新版本 */
    private Integer judgeTemplateVersion;

    /** LLM_JUDGE：claim 聚合策略 */
    private ClaimsPolicy claimsPolicy;

    /** LLM_JUDGE / SDK：判定所需样本字段；LLM_JUDGE 缺失时返回 UNKNOWN 而不是 FAIL，SDK 声明输入契约 */
    private List<String> requiredFields;

    /** SDK： scorer 代码的人类可读引用（如模块路径或函数名），仅留痕，服务端不执行 */
    private String scorerRef;

    /** 覆盖全局 judge model */
    private String judgeModel;

    /**
     * EXPRESSION / LLM_JUDGE：缺少参考或候选输出时返回 UNKNOWN，而不是 FAIL。
     * 是否要求参考由该显式配置决定，绝不根据表达式文本猜测。
     */
    private Boolean referenceRequired;

    /** EXPRESSION 预设标识 / LLM_JUDGE 指标预设 */
    private String preset;

    /** TRAJECTORY：期望工具（SUBSET/ORDER/EXACT 的基准） */
    private List<ExpectedTool> expectedTools;

    /** TRAJECTORY：SUBSET（默认）/ ORDER / EXACT */
    private String trajectoryMode;

    /** TRAJECTORY：出现即 FAIL 的工具 */
    private List<String> forbiddenTools;

    /** TRAJECTORY：允许的最大工具调用步数 */
    private Integer maxSteps;

    /** TRAJECTORY：是否允许重复块；默认 false */
    private Boolean allowCycles;

    /** TRAJECTORY：工具参数规则 */
    private List<ArgumentRule> argumentRules;

    @Data
    public static class Criterion {
        private String id;
        private String label;
        private String description;
        private Boolean required;
    }

    @Data
    public static class ExpectedTool {
        private String name;
        private Boolean required;
    }

    @Data
    public static class ArgumentRule {
        private String tool;
        /** 简单点路径，如 orderId / payload.id */
        private String path;
        private Boolean required;
        private String regex;
        private String equals;
    }

    @Data
    public static class ClaimsPolicy {
        /** 最多抽取多少条 claim，1..20，默认 20 */
        private Integer maxClaims;
        /** PASS claim 必须至少有一个可解析 evidenceRef，默认 true */
        private Boolean requireEvidence;
        /** UNKNOWN 处理：BLOCK（默认）/ FAIL / PASS */
        private String unknownPolicy;
        /** PASS 所需的最低通过比例，0..1，默认 0.8 */
        private Double minPassRatio;
    }
}
