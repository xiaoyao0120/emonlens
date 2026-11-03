package io.emonlens.server.storage.entity;

import java.time.LocalDateTime;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;

@Data
@TableName("et_dataset_run")
public class DatasetRunEntity {

    /** 运行状态只表达执行结果；业务判 FAIL 不影响 COMPLETED。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_PARTIAL_FAILED = "PARTIAL_FAILED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";
    /** 用户主动取消：终态，worker 不得再覆盖；未执行 item 保留 INCOMPLETE/CANCELLED。 */
    public static final String STATUS_CANCELLED = "CANCELLED";

    @TableId(type = IdType.ASSIGN_UUID)
    private String id;

    private String workspaceId;
    private String datasetId;
    /** 本次 run 绑定的不可变数据集修订；历史 run 为空 */
    private String datasetRevisionId;
    /** 受理时冻结的领域包版本；用于证明当时使用的行业配置。 */
    private String domainPackId;
    /** 断点续跑来源 run；初次运行为 null */
    private String resumedFromRunId;
    /** 第几次尝试；初次为 1，续跑递增 */
    private Integer attempt;
    /** 已持久化的外部调用预留数；预算续跑只认该账本，历史 run 为 null */
    private Integer externalCallsReserved;
    /** 可选：整次 run 的 token 预算（输入+输出，按 provider usage 结算） */
    private Long maxTokens;
    private Long tokensReserved;
    private Long tokensSpent;
    /** 本次 run 绑定的不可变 Eval Suite；未绑定为 null */
    private String suiteId;
    /** 候选标识（如 prompt v3 / model gpt-4o），用于 A/B 与 Gate 展示 */
    private String candidateLabel;
    /** 候选指纹：revision + 评估器版本 + 生成配置的 sha256 */
    private String candidateFingerprint;
    private String evaluatorId;
    private String evaluatorKey;
    /** TRACE=评估已有 trace；REPLAY=用 LLM 重放样本输入生成新输出后评估 */
    private String runMode;
    /** 重放使用的模型名（REPLAY 模式） */
    private String model;
    /** 受理时绑定的 prompt 版本引用（R1-3）：候选指纹包含它的内容 hash */
    private String promptName;
    private Integer promptVersion;
    /** 受理时按哪个 label 解析的 prompt（production / latest / 自定义） */
    private String promptLabel;
    private String promptHash;
    /** CI 基线标签（如 baseline）；同一数据集可有多个 run，按标签取最近一条 */
    private String runLabel;
    /** 触发本次 run 的提交号：把结论回溯到提交 */
    private String commitSha;
    private String status;
    private Integer itemCount;
    private Integer passCount;
    private Integer failCount;
    /** 客户端幂等键；历史 run 为空，不参与新请求去重 */
    private String requestKey;
    /** 请求体指纹，用于同键不同请求的冲突判断 */
    private String requestHash;
    /** 受理时冻结的协议、评估器、生成配置与样本清单 hash（不含任何凭据） */
    @JsonIgnore
    private String configSnapshotJson;
    /** worker 租约 token：只允许当前持有者刷新进度/完成；不返回客户端 */
    @JsonIgnore
    private String leaseToken;
    /** 租约到期时间；过期后旧 worker 不得再完成 */
    private LocalDateTime leaseExpiresAt;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** 解析后的配置快照（仅响应使用） */
    @TableField(exist = false)
    private Map<String, Object> configSnapshot;
    @TableField(exist = false)
    private Integer unknownCount;
    @TableField(exist = false)
    private Integer errorCount;
    @TableField(exist = false)
    private Integer incompleteCount;
    /** PASS / (PASS + FAIL)，百分比；分母为零时为 null */
    @TableField(exist = false)
    private Double passRate;
    /** (PASS + FAIL) / itemCount，百分比 */
    @TableField(exist = false)
    private Double coverage;
    /** 旧 run 未保存快照，不具备可比性证明 */
    @TableField(exist = false)
    private Boolean legacyNoSnapshot;
    /** 运行级调用与 usage 汇总（可获取时） */
    @TableField(exist = false)
    private Map<String, Object> usageSummary;
    /** 本次 run 冻结的评估器 key（兼容旧单评估器） */
    @TableField(exist = false)
    private java.util.List<String> evaluatorKeys;
    @TableField(exist = false)
    private Integer evaluatorCount;
}
