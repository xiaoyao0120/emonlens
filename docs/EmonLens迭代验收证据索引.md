# EmonLens 迭代方案验收证据索引

对应 `docs/EmonLens评测平台迭代方案.md` 的 R1–R3 条目。每条给出：实现落点、自动化证据、真机（真实 HTTP）证据、已知偏差。
测试命令统一为：

```bash
docker run --rm -v <repo>:/work -w /work -v emonlens_m2:/root/.m2 \
  maven:3.9-eclipse-temurin-17 mvn -B -o test -Dtest=<Class> -DfailIfNoTests=false
```

真机验证统一用 `SPRING_PROFILES_ACTIVE=demo` 的 jar（H2 内存库、`admin/123456`、ingest key `dev-trust-key-001`），
必要时在同网络起假 LLM / 假 Agent 容器，只经真实 HTTP 断言。

## R1 体验与工程化（0.2.0）

| 条目 | 实现落点 | 自动化证据 | 真机证据 |
| --- | --- | --- | --- |
| R1-1 一键接入 | `action.yml`、SDK `emonlens/ci.py`、ingest | `FeatureParityTest`(9)、`SdkEvalFlowTest`(7) | `scripts/verify_all.py` 实跑（待补：需实例 + 密码） |
| R1-2 矩阵实验 | `PlaygroundMatrixService/Worker`、`MatrixView.vue` | `PlaygroundMatrixFlowTest` | 矩阵 run 抽样 12 次调用、429 容量约束 |
| R1-3 prompt 绑定 | `PromptService`、TracePromptService、`PROMPT_BINDING` Gate | `PromptRegistryFlowTest`、`FeatureParityTest` | 建 prompt 版本 + production 指针漂移后 Gate 判过期 |
| R1-4 CI 门禁 | `ci.py`、run label、`verification` 契约 | `ContractCoverageTest`、`FeatureParityTest` | `POST /ci/runs` → decision/verification 字段实测 |

## R2 在线评测闭环（0.3.0）

| 条目 | 实现落点 | 自动化证据 | 真机证据 |
| --- | --- | --- | --- |
| R2-1 评估规则 v2 | `EvaluationRuleService`、`EvaluationRewindService` | `EvaluationRuleFlowTest`(6) | 规则命中/回溯 run 实测 |
| R2-2 双通道反馈 | `EndUserFeedbackService`、`PublicFeedbackController` | `EndUserFeedbackFlowTest`(6) | 免鉴权 token、限流 429 实测 |
| R2-3 评估器质量看板 | `EvaluatorQualityService`、`EvaluatorQualityView.vue` | `EvaluatorQualityFlowTest`(2) | `GET/POST /evaluator-quality`，健康数据下 findingsCreated=0 |
| R2-4 对比增强 | `ExperimentInsightService`、`ExperimentLeaderboardView.vue` | `ExperimentInsightFlowTest`(4) | 散点/leaderboard/repetition/pairwise 口径 400 实测 |

## R3 Agent 专项（0.4.0）

| 条目 | 实现落点 | 自动化证据 | 真机证据 |
| --- | --- | --- | --- |
| R3-1 多轮仿真 | `ConversationSimulator`、`SimulationReportService` | `ConversationSimFlowTest`(1) | 假 Agent+LLM：turns=2、stop=SATISFIED、三相 2c/240pt + 2c/80pt |
| R3-2 安全包 | `SecurityPackRegistry`、`SecurityEvidenceService`、`SECURITY_REDLINE` | `SecurityPackFlowTest`(4) | pack 7 类 / 种子幂等 / evidence VIOLATION / Gate BLOCKED 且功能通过率 100% |
| R3-3 Judge 升级 | `EvaluationRunService`（CoT/校准/多 judge）、`JudgeTemplateRegistry` | `JudgeUpgradeFlowTest`(2)、`HttpLlmClientTest`(9) | 双 judge 冲突 → unknown=1、judgeCalls=2、tokens=240 |
| R3-4 多模态 | `AttachmentPolicy`、`chatWithImages`、revision 冻结、导出脱敏 | `MultimodalFlowTest`(3) | 附件持久化/校验 400/导出可见；**vision content part 未在打包实例观察到（open loop）** |

## 全量回归

```bash
mvn -B -o test     # 322 用例
```

最近一次干净全量（收尾轮，含信任分/批量复跑/词表配置与 AWR 验证报告后的最终代码）：**325 用例，0 失败 0 错误**
（分两段执行：字母序 A–P 275/275 绿 + S–W 补跑 50/50 绿；此前 323 用例 1 失败的时序抖动未再出现）。

> 说明：并发跑 Maven（同一 `target/`）会互相覆盖产物，曾造成一次 10F/77E 的假失败；该结果已作废，不是代码问题。

## 仍未闭合的偏差（按影响排序）

1. **R3-4 真机 vision part 未发送 —— 已修复**：根因是 `HttpLlmClient.chatWithImages` 覆写在一次文件重写中
   意外丢失，接口默认实现静默退化为纯文本（编译无信号）。修复后 `javap` 验证 boot jar 字节码 +
   真机 E2E 实测 `reason: vision parts=1`（run PASS）。
2. **R2-1 SPAN scope 复用 TRACE 语义**；Evaluation Monitor 分母未随 scope 调整。
3. **R2-2** 分类标签已开放配置词表（`categorical-positive/negative`，收尾完成）；token 限流是单实例内存计数（分布式需引入 Redis）。
4. **R2-3** 缓存指标按工作空间汇总未拆到评估器；蓝图覆盖只标注是否配置。
5. **R2-4** 复跑已支持 `{"count":N}` 批量（容量不足如实返回 created/refused，真机实测）；散点仅成本横轴（自绘 SVG）。
6. **R3-1** 仅 HTTP 直连（SDK runner 未实现）；目标达成靠模拟用户自评；被测 Agent 的 HTTP 调用无法由平台计量（模拟用户 LLM 调用已在预算熔断内，收尾时修正了此前错误记载）。
7. **R3-2** 种子是固定集合；红线固定 0 不接受模板放宽；security 证据已接入五维信任分（VIOLATION → security 维度 0 分，收尾完成）。
8. **R3-3** 校准 few-shot 是"判定倾向提示"而非原文片段；MEAN 策略下标签分歧仍判 UNKNOWN。
9. **AWR** 各 completed 项尚未生成 AWR 验证报告（`completion_not_checked`）；`make verify` 需实例与密码。
