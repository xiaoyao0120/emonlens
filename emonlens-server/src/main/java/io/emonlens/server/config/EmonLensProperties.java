package io.emonlens.server.config;

import java.util.UUID;

import javax.annotation.PostConstruct;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "emonlens")
public class EmonLensProperties {

    /** 实例标识，用于 et_event.claim_owner；缺省启动时生成 UUID（无中心化水平扩展） */
    private String instanceId;

    private Ingest ingest = new Ingest();
    private Trace trace = new Trace();
    private Sweeper sweeper = new Sweeper();
    private Event event = new Event();
    private Behavior behavior = new Behavior();
    private Worker worker = new Worker();
    private Eval eval = new Eval();
    private DatasetRun datasetRun = new DatasetRun();
    private DatasetSynthesis datasetSynthesis = new DatasetSynthesis();
    private Playground playground = new Playground();
    private Feedback feedback = new Feedback();
    private EvaluatorQuality evaluatorQuality = new EvaluatorQuality();
    private Sim sim = new Sim();
    private Metric metric = new Metric();
    private Push push = new Push();
    private Score score = new Score();
    private Security security = new Security();
    private Content content = new Content();
    private Otlp otlp = new Otlp();
    private Retention retention = new Retention();
    private Storage storage = new Storage();
    private Notify notify = new Notify();

    @Data
    public static class Ingest {
        /** 启动时自动注册的开发用 ingest key；置空则不注册 */
        private String seedKey = "dev-trust-key-001";
        private int queueCapacity = 50000;
        private int batchSize = 500;
        private long flushIntervalMs = 200;
        /** 每 workspace 每分钟 spans 额度（令牌桶，桶容量=额度允许突发）；0 = 不限流 */
        private long rateLimitSpansPerMinute = 20000;
    }

    @Data
    public static class Trace {
        /** RUNNING 状态超过该时长无新 span 判定完成 */
        private long idleTimeoutMs = 900000;
        /** summary 计数对账周期（毫秒）；0=关闭。修复 StarRocks 等非事务数据面的计数漂移 */
        private long reconcileIntervalMs = 900000;
        /** 只对账最近 N 小时有 span 活动的 COMPLETED trace */
        private int reconcileRecentHours = 24;
        /** 单轮对账的 trace 上限 */
        private int reconcileBatchSize = 500;
    }

    @Data
    public static class Sweeper {
        private long intervalMs = 15000;
        private int batchSize = 100;
    }

    @Data
    public static class Event {
        private long pollIntervalMs = 1000;
        private int pollBatchSize = 10;
        private int maxRetry = 3;
        private long retryBackoffMs = 60000;
        /** CLAIMED 事件租约（毫秒）：必须大于最慢 handler（多次 Judge + webhook），默认 5 分钟 */
        private long leaseMs = 300_000;
    }

    @Data
    public static class Behavior {
        private int maxConsecutiveRepeat = 3;
        private int minCycleRepeat = 2;
        private int maxSteps = 50;
    }

    @Data
    public static class Worker {
        /** bootstrap 单体内嵌后台线程组开关；独立部署进程时 api 实例侧置 false */
        private boolean enabled = true;
    }

    @Data
    public static class Eval {
        /** LLM Provider（OpenAI 兼容或 Anthropic Messages）；base-url 空 = LLM 不可用 */
        private Llm llm = new Llm();

    @Data
    public static class Llm {
        /** wire protocol：openai（/chat/completions）| anthropic（/messages）；空 = openai */
        private String protocol = "openai";
        private String baseUrl = "";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private int timeoutMs = 20000;
        /** 瞬时错误（429/5xx/网络）最大尝试次数；1=关闭重试 */
        private int maxAttempts = 3;
        private long initialBackoffMs = 500;
        private long maxBackoffMs = 5000;
        /** 退避抖动比例（0-1）；避免多实例同时重试打爆 provider */
        private double jitter = 0.3;
    }

        /** 生产采样默认比例（0-1]；0 表示关闭自动评估 */
        private double defaultRate = 0.01;
        /** 每小时自动评估预算上限 */
        private int maxPerHour = 5000;
        /** 每 trace 单 evaluator 最大尝试次数（attempt 上限） */
        private int maxAttempt = 2;
        /** 自动评估事件 handler 是否启用 */
        private boolean autoRunEnabled = true;
        /** LLM Judge 结果缓存；默认关闭，开启后按 sample+evaluator+template+model 严格命中 */
        private boolean cacheEnabled = false;
        private int cacheTtlHours = 168;
        /** temperature>0 的 Judge 默认不缓存（结果不确定） */
        private boolean cacheAllowStochastic = false;
        private String cacheCleanupCron = "0 10 3 * * *";
        /** SDK 评测器结果回写宽限期（小时）；超过后终端 run 上的未回写任务按 SKIPPED 收敛 */
        private int sdkResultGraceHours = 24;
        /** 忽略会话空闲阈值（测试 / 手工补评）：为 true 时 SESSION 规则立即评估 */
        private boolean ignoreSessionIdle = false;    }

    /** 数据集异步运行（试点）：批量上限、单线程轮询与离线快照预算 */
    @Data
    public static class DatasetRun {
        /** 每批最大样本数；超过直接拒绝 */
        private int maxItems = 20;
        /** 单次 run 最多评估器数量，避免成本失控 */
        private int maxEvaluators = 5;
        /** 单次 run 允许声明的外部调用次数上限（预算字段的硬上限） */
        private int maxExternalCallsPerRun = 10000;
        /** 后台单线程轮询间隔 */
        private long pollIntervalMs = 1000;
        /** 单条快照 JSON 的字符上限；超限拒绝或标为无法完整评估，绝不截断后声称完整匹配 */
        private int maxSnapshotChars = 50000;
        /** 试点模式：旧同步入口停止接受新评测，只允许异步入口 */
        private boolean pilotMode = false;
        /** worker 租约时长（秒）；旧 worker 过期后不能刷新进度或完成 */
        private int leaseSeconds = 900;
        /** 数据库级并发活动 run 上限；默认 1，保持试点单活动语义 */
        private int maxActiveRuns = 1;
        /** 本实例的 dataset-run worker 线程数；多实例部署时按实例配置 */
        private int workerThreads = 1;
        /** 单 workspace 并发活动 run 上限；0 表示不额外限制（仍受 maxActiveRuns 约束） */
        private int maxActiveRunsPerWorkspace = 0;
    }

    /** 合成数据集：候选先 DRAFT，人工批准后才进入工作集。 */
    @Data
    public static class DatasetSynthesis {
        private boolean enabled = false;
        private int workerThreads = 1;
        private long pollIntervalMs = 1000;
        /** 单 job 候选上限（硬上限） */
        private int maxCandidates = 50;
        /** 默认 critic 阈值 */
        private double minQuality = 0.6;
        /** 默认 critic 重试次数 */
        private int maxRetries = 2;
    }

    /** Playground 矩阵实验（R1-2）：试跑产物，有硬上限防止账单失控。 */
    @Data
    public static class Playground {
        private Matrix matrix = new Matrix();
    }

    @Data
    public static class Matrix {
        /** 单矩阵样本上限（与 dataset run 的 maxItems 独立，试跑比正式 run 更宽松） */
        private int maxSamples = 20;
        /** 列上限：一列 = 一个 prompt 版本 × 模型 × 参数 */
        private int maxColumns = 8;
        /** 可挂的评估器上限（与 dataset run 的 maxEvaluators 同口径） */
        private int maxEvaluators = 5;
        /** 单元格并发度：单格失败不影响整批 */
        private int concurrency = 3;
        /** 网格总格数上限：concurrency × 并发度的成本护栏 */
        private int maxCells = 200;
        /** 整批外部调用上限（生成 + 评估） */
        private int maxCalls = 1000;
        private long pollIntervalMs = 1000;
        private int workerThreads = 1;
    }

    /** 多轮会话仿真（R3-1，REPLAY_SIM）。 */
    @Data
    public static class Sim {
        /** 单条样本最多模拟多少轮 */
        private int maxTurns = 6;
        private int connectTimeoutMs = 5000;
        /** 被测 Agent 单轮应答超时 */
        private int agentTimeoutMs = 20000;
        /** 允许把仿真打到内网/回环地址（自建服务或测试用；默认禁止以防 SSRF） */
        private boolean allowPrivateTargets = false;
    }

    /** 评估器质量看板（R2-3）：判定阈值与扫描节奏。 */
    @Data
    public static class EvaluatorQuality {
        /** 默认统计窗口（小时） */
        private int windowHours = 168;
        /** 样本「陈旧」的判定天数 */
        private int staleDays = 90;
        /** 低于该一致率判 LOW_TRUST（与 Gate 的 80% 同口径） */
        private double minAgreement = 80.0;
        /** UNKNOWN + 未执行占比达到该值判 NOISY */
        private double unknownAlertRate = 0.2;
        /** 窗口内成本超过该值判 COSTLY */
        private double costlyPerWindow = 5.0;
        /** 低于该盲审条数时一致率不作为定论（只给补样本建议） */
        private int minReviewedForTrust = 10;
        /** 数据集最新 revision 超过该天数判 ROTTING */
        private int rotRevisionDays = 30;
        /** 超过该天数判 WARNING */
        private int warnRevisionDays = 14;
        /** 每日扫描：把低可信评估器与腐烂数据集写成 Finding */
        private String cron = "0 10 3 * * *";
    }

    /** 端用户反馈（R2-2）：预签 token 的配额、过期、限流，以及负反馈触发 Finding 的阈值。 */
    @Data
    public static class Feedback {
        /** 单个 token 默认可用次数 */
        private int tokenMaxUses = 5;
        /** 单个 token 默认有效期（分钟；7 天） */
        private int tokenExpiresMinutes = 10080;
        /** 同一 token 每分钟最多提交次数（滑动窗口） */
        private int rateLimitPerMinute = 20;
        /** 同一 trace 在窗口内累计多少条负面反馈触发 Finding */
        private int findingThreshold = 3;
        private int findingWindowHours = 24;
        private String findingSeverity = "MEDIUM";
    }

    @Data
    public static class Metric {        /** Metric Monitor 评估线程周期（按窗口对齐扫描） */
        private long scanIntervalMs = 60000;
        /** 基线比较使用的历史窗口数 */
        private int baselineWindows = 6;
        /** 单窗口聚合样本上限（防大窗口拖垮内存） */
        private int sampleLimit = 10000;
    }

    @Data
    public static class Push {
        /** Webhook 回推开关与下游地址 */
        private boolean enabled = false;
        private String baseUrl = "";
        /** HMAC-SHA256 签名密钥（双方共享） */
        private String secret = "";
        private int timeoutMs = 5000;
    }

    @Data
    public static class Score {
        /** Score 重算兜底周期 */
        private long recomputeIntervalMs = 600000;
        /** 计算窗口（小时） */
        private int windowHours = 24;
        /** 五维权重（加权几何平均） */
        private double weightPerformance = 0.25;
        private double weightBehavior = 0.25;
        private double weightOutput = 0.30;
        private double weightSecurity = 0.10;
        private double weightContext = 0.10;
    }

    @Data
    public static class Security {
        /** dev | prod（prod 强制 jwt-secret、非默认 admin 密码、非 dev ingest key） */
        private String mode = "dev";
        private String jwtSecret = "";
        /** 固定窗口限流（每分钟请求数）；0=不限 */
        private int rateLimitPerMinute = 0;
        /** 登录防爆破：同一 账号+IP 连续失败该次数后锁定 */
        private int loginMaxFailures = 5;
        /** 登录失败锁定时长（分钟） */
        private int loginLockMinutes = 15;
        /** 单 IP 每分钟登录尝试上限（含失败） */
        private int loginIpLimitPerMinute = 30;
        /** 是否信任 X-Forwarded-For 取客户端 IP（仅在可信反代之后开启，默认取 remoteAddr） */
        private boolean trustForwardedFor = false;
        /** prod 引导：admin 仍为 seed 密码时，用该值完成首次轮换（仅经环境变量注入，勿写配置文件） */
        private String bootstrapAdminPassword = "";
    }

    @Data
    public static class Content {
        /** 内容采集级别：NONE/HASHED/REDACTED/FULL */
        private String captureMode = "REDACTED";
        /** 单条内容最大保留长度（超出截断） */
        private int maxContentLength = 8192;
    }

    @Data
    public static class Otlp {
        /** OTLP/HTTP-JSON trace 接入端点开关 */
        private boolean enabled = true;
        /** 请求体字符上限（防御性钳制，防止超大批次在解析期占满内存）；超出返回 400 */
        private int maxBodyChars = 33_554_432;
    }

    @Data
    public static class Retention {
        /** span 保留天数；0=不清理 */
        private int spanDays = 30;
        /** 内容保留天数；0=不清理 */
        private int contentDays = 7;
        /** 事件 DONE/DEAD 记录保留天数；0=不清理 */
        private int eventDays = 7;
        /** 信任分快照保留天数（趋势历史）；0=不清理 */
        private int scoreDays = 180;
        /** 通知/推送记录保留天数；0=不清理 */
        private int notifyRecordDays = 30;
        /** 数据集健康快照保留天数；0=不清理 */
        private int healthSnapshotDays = 30;
        private long sweepIntervalMs = 3600000;
        private int batchSize = 5000;
    }

    @Data
    public static class Notify {
        /** 通知派发总开关（关闭时事件仍消费但不发通知） */
        private boolean dispatchEnabled = true;
        /** 允许 webhook 指向回环/私网地址（SSRF 防护的例外开关，仅供本机集成测试；生产保持 false） */
        private boolean allowPrivateWebhookTargets = false;
    }

    @Data
    public static class Storage {
        /** 数据面存储：mysql（默认，开发）| starrocks（DDL 见 sql/emonlens-starrocks.sql） */
        private String mode = "mysql";
        private Starrocks starrocks = new Starrocks();
    }

    @Data
    public static class Starrocks {
        /** MySQL 协议 JDBC 地址；配置后注册 starrocks span store 实现，配合 mode=starrocks 激活 */
        private String jdbcUrl = "";
        private String username = "root";
        private String password = "";
        private int maxPoolSize = 8;
        private int writeBatchSize = 500;
    }

    @PostConstruct
    public void initInstanceId() {
        if (instanceId == null || instanceId.isEmpty()) {
            instanceId = UUID.randomUUID().toString();
        }
    }
}
