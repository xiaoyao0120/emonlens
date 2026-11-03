package io.emonlens.server.engine.eval;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.Data;

/**
 * 数据集运行的单条样本输入（M1）：只传受理时冻结的候选、参考、表达式上下文与必要内容。
 * 不复用 trace 读取路径，确保运行后编辑样本/评估器不会改变已受理 run 的判定。
 */
@Data
public class EvaluationSample {

    private String itemId;
    private Integer itemOrder;
    private String traceId;
    private String sourceTraceId;
    private String agentKey;
    private String agentVersion;

    private String input;
    /** 待评估的候选输出：TRACE=采集到的 completion；REPLAY=本次 LLM 生成结果 */
    private String candidateOutput;
    private String expectedOutput;

    /** 样本契约版本与 canonical hash */
    private Integer schemaVersion;
    private String caseHash;
    /** 检索上下文：样本显式提供，或 TRACE 模式从 retrieval span 投影 */
    private List<Map<String, Object>> retrievalContext = new ArrayList<>();
    /** 期望工具：样本声明 */
    private List<Map<String, Object>> expectedTools = new ArrayList<>();
    /** 实际工具调用：TRACE 模式从 tool span 投影 */
    private List<Map<String, Object>> toolsCalled = new ArrayList<>();
    /** 多轮会话：样本显式提供；第一版不从 trace 推断 */
    private List<Map<String, Object>> turns = new ArrayList<>();
    /** 样本级期望：must / mustNot / unknownPolicy */
    private Map<String, Object> expectations = new LinkedHashMap<>();
    /** R3-4：多模态附件（IMAGE：mime/url|base64/alt） */
    private List<Map<String, Object>> attachments = new ArrayList<>();

    private Integer spanCount;
    private Integer errorCount;
    private Long durationMs;
    private Long toolCalls;
    private Long llmCalls;

    /** LLM_JUDGE 用的 trace 摘要（TRACE 冻结；REPLAY 只依赖候选输出，可空） */
    private String traceDigest;

    /** REGEX 作用内容：kind -> 正文 */
    private Map<String, String> contents = new LinkedHashMap<>();
    /** kind -> 采集模式（NONE/HASHED/REDACTED/FULL） */
    private Map<String, String> contentCaptureModes = new LinkedHashMap<>();
    /** kind -> 内容是否在采集上限处被截断（无法保证完整匹配） */
    private Map<String, Boolean> contentTruncated = new LinkedHashMap<>();

    /** COMPLETE / CONTENT_NOT_CAPTURED / HASHED_ONLY / CANDIDATE_MISSING / TRACE_MISSING / SNAPSHOT_TOO_LARGE */
    private String contentStatus;
    private String contentStatusReason;

    /** 候选输出是否已截断或无法完整取得；true 时精确匹配/Judge 必须返回 UNKNOWN */
    private Boolean candidateComplete;

    public String content(String kind) {
        return contents == null ? null : contents.get(kind);
    }

    public String captureMode(String kind) {
        return contentCaptureModes == null ? null : contentCaptureModes.get(kind);
    }

    public boolean truncated(String kind) {
        return contentTruncated != null && Boolean.TRUE.equals(contentTruncated.get(kind));
    }
}
