# EmonLens 评测平台迭代方案

> 版本：v1（2026-11-03）。基于 0.1.0（评测体系 3.0 + V1.1 增强）现状盘点。
> 状态更新（0.2.0）：R1 四特性已全部落地（R1-1 SDK 评测器、R1-2 矩阵实验、R1-3 prompt × 实验、R1-4 GitHub Action），
> 下文 R1 各节已标注「✅ 已实现」与实现落点；R2/R3 保持规划状态，按 0.3.0 / 0.4.0 窗口推进。
> 对标：Braintrust、LangSmith、W&B Weave（商业）；DeepEval、Ragas、promptfoo、Langfuse、Arize Phoenix（开源）。
> 面向：维护者与贡献者，用于规划 0.2.x – 0.4.x 的评测能力演进。

---

## 1. 结论先行

EmonLens 与竞品的能力分布是互补的：

- **EmonLens 的强项在「治理与准入」**：Gate + verification 口径、Judge 人工盲审校准、质量案例闭环、Incident 状态机、五维信任分。这些在八家对标产品里没有完整等价物，是本项目的护城河，迭代中不能破坏其口径严格性。
- **竞品的强项在「评测开发者体验」**：SDK 侧自定义评测器、Playground 矩阵实验、Prompt 版本与实验打通、端用户反馈回流、CI 胶水（PR 评论 / GitHub Action）。这恰是当前使用 EmonLens 评测体系时最明显的摩擦点。

据此规划三个迭代窗口，主题依次是 **评测 DX → 在线评估与反馈闭环 → Agent 专项与安全**：

| 窗口 | 主题 | 关键交付 | 主要对标 |
| --- | --- | --- | --- |
| R1 (0.2.0) | 评测开发者体验 | SDK 自定义评测器（push 模式）、Playground 矩阵 + Diff、Prompt 版本 × 实验打通、GitHub Action | Braintrust Eval()/scorers、LangSmith experiments、promptfoo 矩阵 |
| R2 (0.3.0) | 在线评估与反馈闭环 | 评估规则 v2（scope/filter/回溯）、端用户反馈采集、score 类型学、实验对比增强 | Braintrust online scoring、LangSmith feedback、Langfuse managed evals |
| R3 (0.4.0) | Agent 专项与安全 | 多轮会话仿真、安全评测包接入 Gate、Judge 技术升级、多模态 | DeepEval ConversationSimulator、promptfoo redteam、Phoenix 结构化判分 |

排期原则：R1 的 push API 是 R2/R3 的地基（仿真、反馈、CI 都要把结果写回统一账本），必须先做；每个特性给出验收标准，达不到就不放版本。

---

## 2. 现状盘点

### 2.1 已有能力

| 域 | 能力 |
| --- | --- |
| 离线评测 | 数据集（revision 冻结、TRAIN/VALIDATION/HOLDOUT、蓝图与保鲜、风险分类）、样本契约 v2（检索上下文 / 期望工具 / 多轮 / 期望）、批量导入导出、合成数据集（4 来源 + critic） |
| 评估器 | EXPRESSION / REGEX / LLM_JUDGE / TRAJECTORY 四类；13 个指标预设（RAG 6、对话 3、Agentic 4）；HOLISTIC / CLAIMS 判分；Judge 模板版本冻结；套件（1–5 评估器，版本冻结） |
| 实验 | TRACE / REPLAY 两种模式；预算（调用 / Token / 成本）；重试与续跑（attempt 链）；评测缓存；任务账本（样本 × 评估器逐条留痕） |
| 校准与比较 | Judge 人工盲审（一致率 ≥80%、DOUBLE_BLIND 仲裁）；成对比较（多模型、位置随机化、A/B/TIE）；run diff（指纹可比性检查） |
| 准入与治理 | Gate（PASS/FAIL/BLOCKED + verification.usable）；质量案例闭环（FIXED + 回归 run + PASS Gate 才能关）；业务实验证据（SRM / 护栏 / 签名）；领域包；规则候选（auto-rubric） |
| 在线评估 | Trace 完成自动评估（error 强制 + 全局采样率 + 小时预算）；Evaluation Monitor 阈值判定（pass_rate / avg_score 窗口）→ Finding |
| Prompt / Playground | Prompt 版本 + label + resolve + usage；Playground 单次运行 |
| 人工反馈 | Trace 级 human score、评论、标注队列 |
| SDK / CI | `emonlens eval run --wait --assert`、`gate check`；开放 API（Idempotency-Key）；CI 只消费 usable + PASS |

### 2.2 相对优势（迭代中必须保住）

1. **Gate 语义严格**：`COMPLETED ≠ 通过`，UNKNOWN / ERROR 单列，CI 消费 `decision=PASS && verification.usable=true`。八家竞品都把「分数」当结论，没有一家把「可验证、会过期、会被取代的准入结论」做成一等对象。
2. **Judge 盲审校准**：先人工独立判分、再对比一致率的流程（含 DOUBLE_BLIND 仲裁）。LangSmith 只有「审计 evaluator 分数」，Braintrust 无强制校准；这是 EmonLens 独有且与「Gate 可信」直接挂钩。
3. **质量案例闭环**：人主导修复 + 回归 run + PASS Gate 才能关闭。竞品的 Loop / Engine 用 agent 自动推 fix PR，方向激进但脱离「治理留痕」场景；EmonLens 的闭环天然是审计友好的。
4. **业务实验证据独立**（SRM / 护栏 / 签名）与**五维信任分**（任一维度塌陷拉低总分）。

### 2.3 主要缺口

| # | 缺口 | 竞品参照 | 影响 |
| --- | --- | --- | --- |
| G1 | 用户不能写代码自定义评测器：四类评估器全在服务端跑，表达力止步于表达式 / 正则 / rubric / 轨迹 JSON | 全部竞品的核心模型：Braintrust code scorers、LangSmith code evaluator、DeepEval `BaseMetric`、promptfoo `python`/`javascript` 断言 | 表达力天花板低，复杂业务判定（如「金额计算正确」）做不了 |
| G2 | Playground 只能单次运行，无矩阵、无 diff | Braintrust Tasks × dataset rows 矩阵 + Diff mode；promptfoo YAML 矩阵 | 调 prompt 效率低，改动效果靠肉眼比 |
| G3 | Prompt 与实验脱节：实验不 pin prompt 版本，Playground 不能一键转正式实验，无环境晋升链路 | Braintrust prompt 环境标签 + 跨环境 promotion；LangSmith prompt commit history | 「上线的是哪个 prompt、按哪次实验放行的」断链 |
| G4 | 在线评估 scope 粗：全局采样率一套，无按规则过滤，无会话级评估，不能回溯历史 trace | Braintrust online scoring（Trace/Span/Group 三层 scope、per-rule 采样、Rewind 回溯）；Langfuse managed evals（filter + 采样率写回） | 线上质量只能抽查单轮，慢问题 / 会话级问题测不到 |
| G5 | 无端用户反馈通道：human score 只能平台内人工打 | LangSmith presigned feedback tokens + enforced feedback configs；Langfuse scores（numeric/categorical/boolean/correction） | 生产真实满意度进不了评测闭环 |
| G6 | CI 消费靠裸 CLI，无 GitHub Action、无 PR 评论 | Braintrust / promptfoo 官方 Action + PR 评论；DeepEval pytest 插件 | 门禁结果不回 PR，评审看不到 |
| G7 | 实验对比只有 run diff 文本口径，无散点（分数 vs 成本 / 延迟）、无 repetitions 方差、无 leaderboard | Weave 版本化 Evaluation + Leaderboard；Phoenix `repetitions=n` | 模型 / prompt 选型缺决策视图 |
| G8 | 无安全评测：security 维度缺专门证据来源 | promptfoo redteam（plugins × strategies、OWASP LLM Top 10 映射）、DeepTeam；商业三强均不做独立红队产品 | 信任分 security 维度空转 |
| G9 | 无多轮会话仿真：REPLAY 只做单轮文本重放 | DeepEval ConversationSimulator（persona、停止条件）；LangSmith simulate multi-turn | 对话型 Agent 没法离线压测 |
| G10 | 无多模态评测 | 商业三强 + DeepEval 均支持图片附件 | 图片输入 / 输出场景无法评测 |

---

## 3. 竞品对标速览

### 3.1 能力矩阵

| 能力 | Braintrust | LangSmith | Weave | DeepEval | promptfoo | Langfuse | Phoenix | **EmonLens 现状** |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 代码自定义评测器 | ✅ TS/Python scorers | ✅ code evaluator | ✅ scorer 类 / remote | ✅ BaseMetric | ✅ 内联脚本 | ✅ Custom Evals | ✅ Python/TS | ❌（仅表达式/正则） |
| LLM-as-judge 模板 | ✅ | ✅ + Tuned Evaluators | ✅ litellm | ✅ G-Eval 等 | ✅ llm-rubric/g-eval | ✅ managed evals | ✅ function-calling 结构化 | ✅ 模板 + CLAIMS + 预设 |
| Judge 校准（人工盲审） | ❌ | ⚠️ 审计分数 | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ 独有 |
| RAG 指标套件 | ✅ autoevals | ✅ openevals | ✅ RAGAS 系 | ✅ 6 项 | ✅ | ⚠️ 自建 | ✅ | ✅ 6 项预设 |
| 轨迹 / 工具评测 | ⚠️ hooks + metadata | ✅ trajectory | ⚠️ LLM judge 读 transcript | ✅ agentic 指标 | ✅ 断言 DSL（tool-sequence、tool-call-f1） | ❌ | ⚠️ | ✅ TRAJECTORY（确定性） |
| 多轮会话仿真 | ❌ | ⚠️ 模式文档 | ❌ | ✅ Simulator | ⚠️ redteam 多轮 | ❌ | ❌ | ❌ |
| Playground 矩阵 + Diff | ✅ | ⚠️ Playground Evaluate | ⚠️ Evaluation Playground | ❌ | ✅ YAML 矩阵 | ⚠️ playground 跑实验 | ⚠️ experiments | ❌ 单次运行 |
| Prompt 版本管理 | ✅ 环境 + Terraform | ✅ Hub + webhook | ✅ | ❌ | ⚠️ 文件即版本 | ✅ | ⚠️ | ✅ 版本 + label |
| 数据集版本 / 切分 | ✅ pipelines + 漂移监控 | ✅ versions/diffs/splits | ✅ | ⚠️ goldens | ⚠️ CSV | ⚠️ | ⚠️ | ✅ revision + 切分 + 蓝图 |
| 在线评估 scope | ✅ Trace/Span/Group + Rewind | ✅ 最全（含多轮在线评估器） | ✅ Monitors → 13 Signals | ⚠️ @observe(metrics) | ❌ | ✅ filter + 采样 | ⚠️ 付费版 | ⚠️ 全局采样 |
| 端用户反馈采集 | ⚠️ | ✅ presigned token | ⚠️ | ❌ | ❌ | ⚠️ scores API | ⚠️ | ❌ |
| 成对比较 | ⚠️ scorers | ✅ 内建 | ❌ | ⚠️ Arena G-Eval | ⚠️ select-best | ⚠️ 模式文档 | ❌ | ✅ 双向映射 + 校准 |
| 安全评测 / 红队 | ❌ | ❌ | ⚠️ jailbreak signal | ✅ DeepTeam | ✅ 最强（合规映射） | ❌ | ❌ | ❌ |
| CI PR 评论 / Action | ✅ | ⚠️ recipes | ❌ | ✅ pytest | ✅ Action + SARIF | ❌ | ❌ | ⚠️ 裸 CLI |
| 实验排行 / 散点 | ⚠️ 对比视图 | ⚠️ | ✅ Leaderboard | ⚠️ 云端 | ✅ viewer | ⚠️ | ⚠️ | ❌ |
| 准入 Gate + verification | ❌ | ❌ | ❌ | ⚠️ assert 退出码 | ⚠️ assert | ❌ | ❌ | ✅ 独有 |

### 3.2 每家一句话

- **Braintrust**：代码优先 `Eval()` + autoevals 库；online scoring 三层 scope + Rewind 回溯；Playground 矩阵 + Diff mode（Improvement / Regression / Tradeoff / Tie）；Loop / Topics / Patterns 用 agent 挖掘线上 trace 并推 fix PR。
- **LangSmith**：评估器类型学最全（judge / decision model / code / composite / summary / pairwise，含多轮在线评估器）；Tuned Evaluators（官方托管蒸馏 judge）；Engine 把 trace 聚类成 issue 生命周期；presigned feedback token 是最完整的用户回流设计。
- **W&B Weave**：Evaluation / Leaderboard 是版本化对象；13 个零配置生产 Signals（批量单次 LLM 调用出 13 类判定）；Guardrails 内联并自动兼作 monitor。
- **DeepEval**：30+ 研究型指标（G-Eval、DAG、QAG、JevEval）；pytest 插件化门禁；ConversationSimulator 用户仿真；红队拆分为 DeepTeam（40+ 漏洞 × 10+ 攻击，CVSS 评分）。
- **promptfoo**：YAML 断言库覆盖确定性 / NLP / model-graded / trajectory；矩阵实验 UX 事实标准；红队 plugins × strategies 并映射 OWASP LLM Top 10 / NIST AI RMF / MITRE ATLAS，输出 SARIF。
- **Langfuse**：managed evals（UI 配 judge + filter + 采样率写回 trace）；scores 是一等对象且分四类；自托管友好，但评测表达力靠自建。
- **Arize Phoenix**：judge 用 function calling 强制结构化输出（label + explanation，无自由文本解析）；eval 运行本身也作为 trace 审计；experiments 支持 repetitions 测方差。

### 3.3 对规划有直接影响的洞察

1. **商业三强都不做独立红队产品**，安全以 scorer / signal 形态存在；红队心智被 promptfoo / DeepTeam 占据。EmonLens 做「安全评测进 Gate」是对两者都没占住的位置：把红队结果变成 security 维度证据和 BLOCKED 红线。
2. **2026 年三家都上了「agent 挖掘 trace → 建议修复」**（Loop / Topics / Patterns、Engine）。EmonLens 的规则候选 + 质量案例是人工版本，正确策略不是再造一个自动修码 agent，而是把「聚类 → 候选 → 案例化」的自动化程度逐级提高。
3. **score 类型学 + presigned token 反馈**（LangSmith）是跨产品共识方向，实现成本低、闭环价值高。
4. **矩阵 Playground + Diff mode 已是事实标准**，且 EmonLens 已有 PairwiseEvaluationService 和成本核算，改造路径短。
5. **Phoenix 的经验**：judge 输出用结构化约束（EmonLens 的 CLAIMS 已做到），eval 运行本身可审计（EmonLens 的任务账本已部分覆盖，需补 judge 原始请求 / 响应留痕）。

---

## 4. 迭代路线

### R1（0.2.0）：评测开发者体验

> 目标一句话：让用户能在自己的 Python 里写评测器、在 Playground 里跑矩阵、在 PR 里看到门禁结论。

#### R1-1 SDK 自定义评测器（push 模式）　✅ 已实现（0.1.0 → R1，含账本口径 / eval:content scope / SDK_EVALUATORS Gate 检查项 / 宽限清理）

- **对标**：Braintrust code scorers、LangSmith code evaluator、DeepEval `BaseMetric`。竞品共性是「评测代码在用户环境跑，平台只收结果」，这与 EmonLens「Java 单进程 + 不替用户执行任意代码」的安全边界天然契合，不做服务端代码执行。
- **方案要点**：
  - 开放 API 新增 `POST /api/v1/open/eval-runs/{runId}/results`：按 `datasetItemId + evaluatorKey` 幂等写回，复用任务账本（迟到结果不覆盖终态的既有约束不变）。
  - 评估器新增 `SDK` 类型：spec 里声明输入契约（需要 input / candidate_output / expected_output 等字段），run 提交时样本载荷经开放 API 下发（受 `TRACE_CONTENT` 权限控制），SDK 在用户环境执行 scorer 函数后 push 回来。
  - SDK 侧：`@emonlens.scorer` 装饰器 + `emonlens.eval.push_results(run_id, results)`；CLI `emonlens eval run --sdk-runner ./my_scorers.py`。
  - 账本与 Gate：SDK 结果与 server 侧结果同口径（PASS/FAIL/UNKNOWN/ERROR + score + 原因），账本记录 `origin=SDK`；Gate 检查项新增 `SDK_EVALUATORS`——声明了 SDK 评测器但结果未收齐 → BLOCKED（与 UNKNOWN 预算语义一致）。
- **验收标准**：
  1. 一个含 SDK 评测器的 run，服务端评测器与 SDK 评测器结果落在同一份任务账本，计数守恒公式不变；
  2. push 重复 / 迟到结果幂等；
  3. 无 `TRACE_CONTENT` 权限时样本正文脱敏下发，仅给 hash 与标签；
  4. `--wait --assert` 对含 SDK 评测器的 run 语义不变。

#### R1-2 Playground 矩阵实验 + Diff　✅ 已实现（0.2.0：矩阵 / 预估硬确认 / 网格并发 / 逐格评分 / CSV / Diff + TRADEOFF）

- **对标**：Braintrust Tasks × dataset rows + Diff mode；promptfoo 矩阵。
- **方案要点**：
  - Playground 支持选数据集（或样本子集）× 多个 prompt 版本 × 多模型 × 参数，网格并发执行，单元格记录输出 / token / 成本 / 延迟 / 评估器分数（可挂评估套件）。
  - Diff：指定一列为 base，其余列与之逐样本比较——确定性变化直接标，语义变化复用 PairwiseEvaluationService 出 Improvement / Regression / Tradeoff / TIE。
  - 成本预估在启动前给出（复用既有 byModel 计价）。
- **验收标准**：矩阵跑完能导出网格 CSV；Diff 结论可追溯到逐样本偏好记录；单格失败不中断整批。
- **实现落点**：`et_playground_matrix` / `et_playground_cell` / `et_playground_diff` / `et_playground_diff_item`；
  `PlaygroundMatrixService`（预估、受理、网格执行、Diff、CSV、升格）+ `PlaygroundMatrixWorker` + `PlaygroundMatrixController`
  （`/api/v1/playground/matrices`）；成对判定口径抽成 `PairwiseJudge`，由 dataset run 的 PairwiseEvaluation 与矩阵 Diff 共用；
  UI 新增「矩阵实验」页（`MatrixView.vue`）。
  - 成本护栏：`POST /matrices/estimate` 先给预估，受理必须 `confirmEstimate=true`；默认上限 20 样本 × 8 列 / 200 格 / 1000 次外部调用。
  - 确定性变化按输出 hash + 归一化文本比对；语义偏好把目标列当候选 B（B 胜=改进，A 胜=退步），
    偏好与评估结论冲突时给 `TRADEOFF`（`PREFERENCE_UP_SCORE_DOWN` / `PREFERENCE_DOWN_SCORE_UP`）。
  - 单格失败写 `ERROR` 且矩阵落 `PARTIAL`，其余格照常有结论。

#### R1-3 Prompt 版本 × 实验打通　✅ 已实现（0.2.0：run 绑定 prompt 版本 / 指纹含 prompt / production 指针移动使 Gate 失效 / 升格 / 反向查 run）

- **对标**：Braintrust prompt 环境标签 + promotion；LangSmith prompt commit history。
- **方案要点**：
  - Dataset run 记录 prompt 版本引用（进既有「候选指纹绑定」检查项；prompt 改版后旧 Gate 自动 verification 失效）。
  - Playground「转正式实验」：矩阵列一键升格为 dataset run，沿用冻结语义。
  - label 语义收紧：`production` label 全局唯一，resolve 留审计日志；SDK 增加 `emonlens.get_prompt(name, label="production")`。
- **验收标准**：从 prompt 版本能回答「它被哪些 run 评过、Gate 结论是什么」；两次 run 用不同 prompt 版本时 run diff 给出不可比或降级提示。
- **实现落点**：`PromptService`（解析 / 内容 hash / 解析留痕 `et_prompt_resolution`）；run 新增 `prompt_name/version/label/hash` 列，
  `candidateFingerprint` 纳入 prompt 内容 hash；Gate 新增 `PROMPT_BINDING` 检查项，verification 增加
  `PROMPT_PRODUCTION_MOVED` / `PROMPT_PRODUCTION_MISSING` 失效原因；`GET /prompts/{name}/runs` 反查评估证据；
  `POST /playground/matrices/{id}/columns/{key}/promote` 升格（幂等键由矩阵 + 列推导）；
  run diff 增加 `promptComparison` 降级提示（版本不同仍可比，但只说明相对差异）。
  - label 收紧：新建版本声明 production 时自动从同名旧版本摘除（此前会出现两个版本同时带 production）。
  - SDK `get_prompt` 已存在，本次接入同一套解析口径并在数据面 resolve 留痕（source=SDK），响应增加 `promptHash`。

#### R1-4 GitHub Action + PR 评论　✅ 已实现（0.2.0：composite Action / 幂等评论 / 基线 run 标签 / 提交号回溯）

- **对标**：Braintrust / promptfoo 官方 Action；DeepEval pytest 门禁。
- **方案要点**：
  - 发布官方 composite Action：内部执行 `emonlens eval run --wait --assert` + `gate check`，把通过率 / 覆盖率 / Gate 结论 / 与基线 run 的 diff 摘要渲染成 PR 评论（评论幂等更新，不刷屏）。
  - 基线 run 由 dataset 绑定（如 `baseline` 标签），CI 注入 commit SHA 使候选指纹可追溯。
- **验收标准**：PR 上一次评论可反复更新；Gate 非 PASS 时 Action 失败退出并在评论里给出检查项清单。
- **实现落点**：根目录 `action.yml`（composite，setup-python → 安装仓库内 SDK → 跑评测并写 report → 渲染评论 → 用 CLI 退出码决定 job 结果）；
  SDK 新增 `emonlens.ci`（纯函数渲染 + GitHub 评论 upsert，按隐藏 marker 幂等更新）与 `emonlens ci comment` 子命令；
  CLI 新增 `--prompt-name/--prompt-version/--prompt-label/--run-label/--commit-sha/--baseline-label/--report-json`、
  `gate check --run-id`；开放 API 新增基线解析 `GET /open/eval-runs?datasetId=&label=` 与 diff 摘要
  `GET /open/eval-runs/{runId}/diff`；`et_dataset_run` 增加 `run_label`（同数据集内唯一）/`commit_sha`，
  提供 `POST /datasets/{id}/runs/{runId}/label` 打标。示例工作流见 `docs/examples/github-action-eval.yml`。
  - 评论只含计数、检查项与标识，不含样本正文；Gate verification 不可用时明确写「不得作为放行依据」。

### R2（0.3.0）：在线评估与反馈闭环

> 目标一句话：让线上质量可持续度量，把真实用户的反馈变成评测样本。

#### R2-1 评估规则 v2（scope / filter / 回溯）　✅ 已实现（规则对象 / 过滤 / 采样与预算 / 命中幂等 / SESSION 聚合 / Rewind）

- **对标**：Braintrust online scoring（Trace / Span / Group 三层 + per-rule 采样 + Rewind）；Langfuse filter + 采样率。
- **方案要点**：
  - 把 EvaluationAutoRunService 的全局采样升级为规则对象：scope（TRACE / SPAN / SESSION）、过滤条件（agent、trace 名称、release、延迟区间、metadata 匹配）、per-rule 采样率与小时预算、错误强制采样保留。
  - SESSION scope：会话结束（或空闲超时）后对聚合视图执行评估器，覆盖多轮 / 跨 trace 问题。
  - Rewind：按 filter 圈选历史 trace 批量补评（限速、可暂停、幂等），结果照常进账本与 Monitor。
  - Monitor 阈值判定保持独立层（复用 EvaluationMonitorChecker），但分母口径随 scope 走。
- **验收标准**：同一 trace 命中多条规则时结果可归并去重；Rewind 跑完的 trace 不重复评；SESSION 评估在报告中有独立汇总。
- **实现落点**：`et_eval_rule` / `et_eval_rule_hit` / `et_eval_rewind_job` 三张表 + `et_eval_result` 增列
  `scope` / `session_id` / `rule_key`；`EvaluationRuleService`（规则维护、过滤匹配、采样与预算、归并去重、SESSION 聚合、
  汇总）+ `EvaluationRewindService`（作业游标、限速、暂停/恢复/取消）+ `EvaluationRuleWorker`（`@Scheduled` 推进）
  + `EvaluationRuleController`（`/api/v1/eval-rules`，含 `/summary` 与 `/rewinds`）。
  - **向后兼容**：工作空间没有启用规则时仍走旧的全局采样，升级不改变既有行为；删除/禁用全部规则即回退。
  - **幂等**：命中账本唯一键 `(rule_key, subject_id)`——同一规则对同一 trace / 会话只评估一次，
    Rewind 重跑与重复完成事件都不会重复花钱；小时预算也按账本计数（进程重启不清零）。
  - **归并去重**：一次 trace 完成里多条规则命中时按 `priority` 消费评估器，同一评估器只跑一次，后续规则记 `DEDUPED`。
  - **SESSION**：会话按 `session_idle_minutes` 空闲后评估一次，主体写成 `session:<id>`（不污染真实 trace 的
    `(evaluator, trace, attempt)` 唯一键），结论带 `scope=SESSION`，在 `/summary` 的 `byScope` 里独立汇总。
  - **Rewind**：按 `(start_time, trace_id)` 游标推进，`ratePerMinute/12` 控制每轮批量；暂停只改作业状态、保留游标。
  - 已知偏差（后续补齐）：① SPAN scope 暂按 TRACE 的正文口径处理（当前 span 级证据通过 trace 正文采集实现，
    独立 span 采样需要 span→content 映射先落地）；② Monitor 阈值判定的分母口径未随 scope 调整，
    仍是「按 agentKey + evaluatorKey 的最近 N 条结果」，需要等 SESSION 结果的统计口径统一后再改。

#### R2-2 端用户反馈采集　✅ 已实现（预签 token / 四类 score / 转样本 / 阈值 Finding）

- **对标**：LangSmith presigned feedback tokens；Langfuse score 类型学。
- **方案要点**：
  - `POST /api/v1/feedback/{token}`：SDK / 服务端为 trace 预签 token（可限次数、可过期、匿名可提交、限流、剥离正文读取权限）。
  - score 类型学：numeric / categorical / boolean / correction（correction 即用户给出的期望输出，可直接转样本）。
  - 反馈落在既有 human score 体系；阈值触发 Finding（复用 4 类监控规则的扩展位）；一键「收录数据集」把带 correction 的 trace 变样本（打通既有 Trace → 数据集入口）。
- **验收标准**：token 泄露场景只能写分数不能读正文；correction 类反馈进数据集时走样本契约校验。
- **实现落点**：`et_feedback_token`（只存 sha256）/ `et_end_user_feedback`（类型化明细）两张表；
  `EndUserFeedbackService` + `PublicFeedbackController`（`/api/v1/feedback/{token}`，从 JWT 拦截链豁免）
  + `TraceFeedbackController`（签发 / 吊销 / 查看 / 收录数据集）。
  - **权限边界**：token 只授予「给指定 trace 写一条反馈」；用 token 当 bearer 访问控制面仍是 40101；
    `GET /api/v1/feedback/{token}` 只回 `valid / expiresAt / remainingUses`，不含 traceId 与任何正文。
  - **类型归一**：numeric 0–10（≥6 记 PASS，与人工评分同口径）、boolean（true/up→PASS）、
    categorical（好/差等词表映射，未知词记 UNKNOWN 而不是硬判）、correction（记 FAIL 并把纠正内容单独留存）。
  - **落库**：每条反馈同时写一条 `et_eval_result`（evaluatorKey=`end-user-feedback`），
    既有监控、质量视图与信任分不需要新增数据通路；`reason` 只写类型与是否带纠正，**不写用户正文**。
  - **Finding**：同一 trace 在窗口内负面反馈数量「刚好跨过阈值」时产生一条 `END_USER_FEEDBACK` Finding，
    不会每条差评都开一条。
  - **转样本**：只有 correction 类可收录；输入取 trace 的 `inputPreview`，期望输出取纠正内容，
    走 `EvaluationCaseService.validate`（超长/非法直接 400，不静默截断）。
  - 已知偏差：categorical 目前是内置词表映射（不提供自定义标签体系）；token 限流是单实例内存窗口，
    多实例部署时各实例独立计数（仍能挡住脚本刷量，但不是全局限额）。

#### R2-3 评估器质量看板　✅ 已实现（一致率 / UNKNOWN 率 / 成本 / 数据集腐烂 / 告警接入）

- **对标**：LangSmith auditing evaluator scores；Braintrust dataset 漂移监控。
- **方案要点**：按评估器汇总盲审一致率趋势、UNKNOWN / ERROR 率、缓存命中率、成本；低一致率评估器给出「降级建议」（如改 CLAIMS、换模板版本）；数据集蓝图覆盖 / 保鲜度告警接入 Alerts 页。
- **验收标准**：能回答「哪个评估器最不可信、哪个数据集在腐烂」。
- **实现落点**：`EvaluatorQualityService` + `EvaluatorQualityController`（`GET /api/v1/evaluator-quality`、
  `POST /api/v1/evaluator-quality/scan`）+ `EvaluatorQualityScheduler`（每日扫描）+ UI「评估器质量」页。
  - **一次报告两个答案**：`leastTrusted` 直接给最不可信的评估器（按 LOW_TRUST → NOISY → COSTLY 排序，
    再按一致率升序）；`datasets[]` 给每个数据集的陈旧样本占比与 revision 年龄，腐烂的排最前。
  - **判定口径**：一致率 < 80% → `LOW_TRUST`（与 Gate 的校准阈值同口径）；UNKNOWN + 未执行 ≥ 20% → `NOISY`
    （ERROR / EVAL_UNAVAILABLE 单独计为「未执行」，不混进 UNKNOWN）；窗口成本超阈值 → `COSTLY`；
    数据集陈旧占比 ≥ 50% 或 revision 超 30 天 → `ROTTING`，≥ 25% / 14 天 → `WARNING`。
  - **可解释建议**：低一致率建议改 CLAIMS 拆条或换模板版本；高 UNKNOWN 建议补证据或放宽 referenceRequired；
    高成本建议开缓存或改用确定性评估器；盲审样本不足 10 条时明确提示「一致率还不具统计意义」。
  - **告警接入 Alerts**：扫描把结论写成 Finding（`EVALUATOR_QUALITY` / `DATASET_ROT`），
    复用既有 Incident 归并与通知；24 小时内同一对象只报一次。质量告警是平台级对象，
    `agent_key` 用哨兵值 `*`。
  - 已知偏差：① 缓存指标按工作空间汇总（缓存 key 绑定 sample 指纹与模板 hash，不绑定 evaluator key），
    没有拆到单个评估器；② 数据集「蓝图覆盖」只标注是否配置，未做类别级缺口对账（那部分仍在数据集健康页）。

#### R2-4 实验对比增强

- **对标**：Weave Leaderboard；Phoenix repetitions。
- **方案要点**：run 列表支持分数 vs 成本 / 延迟散点与筛选；run 支持 repetitions（同配置重跑 N 次，报告均值与方差，成对比较要求同 repetitions 口径）；数据集维度 leaderboard（模型 × 套件通过率矩阵）。
- **验收标准**：leaderboard 排序口径可解释（覆盖率不足的 run 显式降权 / 标灰）。

### R3（0.4.0）：Agent 专项与安全

> 目标一句话：补齐 Agent 特有评测形态，让 security 维度有真实证据来源。

#### R3-1 多轮会话仿真（REPLAY-SIM）

- **对标**：DeepEval ConversationSimulator（persona、停止条件）；LangSmith simulate multi-turn。
- **方案要点**：
  - 新 run 模式 `REPLAY_SIM`：平台按 persona + 场景生成模拟用户轮次，被测 Agent 两种接入方式——HTTP 端点直连（promptfoo provider 模式），或 SDK runner（用户环境起 agent，复用 R1-1 push 通道）。
  - 复用样本契约 v2 的 turns 结构与 3 个对话预设评估器；停止条件（最大轮数 / 目标达成 / persona 满意度）；预算与缓存沿用。
- **验收标准**：仿真会话完整入 trace 体系（可观测可回放）；成本核算分 generation / simulation / judge 三相。

#### R3-2 安全评测包

- **对标**：promptfoo redteam（plugins × strategies、OWASP LLM Top 10 映射）、DeepTeam；Weave jailbreak signal。
- **方案要点**：
  - 领域包机制扩展出「安全包」：内置攻击类别种子集（注入、越狱诱导、PII 诱导、工具滥用、越权轨迹模式等），每类挂检测口径（既有 VALIDATION 规则 / TRAJECTORY forbiddenTools / 安全预设 judge）。
  - 结果进入信任分 security 维度作为专属证据源；Gate 模板可声明 `securityFail=0` 红线（违例直接 BLOCKED，不吃通用 maxFail 预算）。
  - 报告按 OWASP LLM Top 10 类目映射汇总。合规边界：所有攻击样本平台本地生成 / 用户自带，仅服务评测用途，不提供对外攻击能力。
- **验收标准**：一次 run 能同时输出功能 Gate 与 security 维度证据；红线违例即使整体通过率 100% 也 BLOCKED。

#### R3-3 Judge 技术升级

- **对标**：DeepEval G-Eval / few-shot；Phoenix 结构化判分（CLAIMS 已具备）。
- **方案要点**：
  - HOLISTIC 模板补 CoT 形态（先列证据再打分）；few-shot 注入——**从盲审校准一致样本中自动选取示例**进 judge prompt（这是把 EmonLens 独有校准数据变成判分质量的独有路径）。
  - 多 judge 集成：单评估器可配 2–3 个 judge 模型 + 聚合策略（majority / mean / 保守），复用成对比较的多模型基建。
  - judge 原始请求 / 响应留痕进任务账本详情（补审计闭环）。
- **验收标准**：few-shot 开启后同一批样本重跑一致率不降（回归守护）；多 judge 冲突时按策略出 UNKNOWN 而非硬判。

#### R3-4 多模态评测（可延后）

- **对标**：商业三强图片附件评测。
- **方案要点**：样本契约 v2 增附件字段（image URL / base64，走对象存储与 `TRACE_CONTENT` 权限）；judge 模板证据字段支持图片（端点需 vision 模型）；TRAJECTORY 不涉及。
- **验收标准**：附件生命周期与导出脱敏口径与正文一致。

### 明确不做（non-goals）

- **自动修码 agent**（对标 Loop / Engine / fix PR）：超出治理平台边界；质量案例已预留 handoff 点（fixFingerprint），由用户自己的 agent 消费。
- **自研列存 / 向量引擎**（对标 Brainstore）：继续走 MySQL（+ 可选 StarRocks）。
- **托管蒸馏 judge**（对标 Tuned Evaluators）：需要模型训练管线，与单 jar 自托管定位冲突；以 few-shot 校准近似替代。
- **语音评测**、Terraform provider、BTQL 式自由查询语言。

---

## 5. 依赖与风险

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| R1-1 push API 设计不当会污染账本口径 | Gate 可信度 | 结果一律走任务账本终态机；SDK 评测器缺结果 → BLOCKED 而非放行；先行评审 API 契约 |
| 矩阵 Playground 成本失控 | 用户账单 | 启动前成本预估硬确认；复用既有预算机制 |
| 仿真 / 红队功能被滥用 | 平台合规定位 | 攻击资产仅评测用途、本地生成、不外置攻击执行器；文档明示授权边界 |
| 在线评估规则变多后评估成本上升 | 部署成本 | per-rule 预算 + 缓存（cache key 已含模板 hash / judge model）默认继承 |
| 功能面扩大稀释「治理」定位 | 产品心智 | 每个迭代以 Gate / 账本 / 校准为主线验收；非治理向特性（多模态）可延后 |

## 6. 版本节奏建议

- 0.2.0：R1 四特性 + 文档（评测增强指南 v2.2 → 迭代并入主指南）。**已发布**：R1-1/R1-2/R1-3/R1-4 全部落地，
  验收项由 `PlaygroundMatrixFlowTest`（矩阵/Diff/升格/prompt 失效）、`SdkEvalFlowTest`（SDK 评测器）与
  `emonlens-sdk-python/tests/test_ci.py`（PR 评论幂等）覆盖。
- 0.3.0：R2 四特性，评估规则 v2 含一次不兼容 spec 升级（提供迁移 SQL，沿用幂等迁移约定）。
- 0.4.0：R3 按依赖顺序落地（安全包 → 仿真 → judge 升级 → 多模态），多模态视需求可顺延。
- 每个 window 结束跑一次 `make verify`，SDK 变更同步 `emonlens-sdk-python` 用例与 `make sdk-test`。
