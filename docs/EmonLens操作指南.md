# EmonLens 操作指南

> 适用版本：0.1.0。面向平台的**使用者与运维者**：从首次配置到日常操作的完整手册。
> 部署/环境变量/容量规划见《EmonLens部署运维手册.md》；SDK 开发细节见 SDK README 与 docs/ 各接入手册。
> **评测 3.0 的完整流程**（数据集 → 评估器/套件 → 运行 → 校准 → Gate → 质量案例）见[《EmonLens评测与准入操作指南》](EmonLens评测与准入操作指南.md)。

---

## 1. 平台速览（5 分钟读懂）

EmonLens 对 LLM Agent 做**全链路可信观测**：采集（Trace/Span）→ 检测（行为/规则）→ 评估（自动/人工）→ 评分（信任分）→ 告警（Incident/通知）→ 治理（RBAC/审计）。

**三层模型**（对标 Langfuse，扩展信任维度）：

| 概念 | 含义 | 对应页面 |
|---|---|---|
| **Trace** | Agent 一次完整调用（含 LLM/工具/检索各步骤） | 调用链路 |
| **Session** | 同一 sessionId 的多轮对话聚合 | 会话 |
| **Span** | Trace 内的单步（agent / llm / tool / retrieval） | Trace 详情瀑布图 |
| **Finding** | 规则/检测器命中的单次异常 | 事件告警 · 异常发现 |
| **Incident** | 同一指纹（fingerprint）Findings 的聚合告警 | 事件告警 · 触发历史 |
| **Evaluator** | 自动评估器（表达式/正则/LLM） | 评估器 |
| **信任分** | 性能/行为/输出/安全/上下文 五维加权 + 状态分级 | 总览、Agent 接入 |

**状态分级**：`TRUSTED` → `WATCH` → `AT_RISK` → `DEGRADED` → `UNTRUSTED`（越靠后越需要关注）。

---

## 2. 首次配置（管理员 10 分钟）

### 2.1 启动与登录

```bash
cp .env.example .env          # 修改 5 个必填项（见部署手册 §2）
docker compose up -d --build
open http://localhost:8300    # 用户名 admin，密码 = 你在 .env 里设的 EMONLENS_ADMIN_PASSWORD
```

首启为**干净空平台**（无演示数据），业务数据全部由 Agent 上报产生。
admin 种子密码 `123456` 不提供对外使用：prod 模式下若仍是该值，门禁会用 `.env` 里的
`EMONLENS_ADMIN_PASSWORD` 强制轮换；轮换后该变量即可从 `.env` 移除。
只想看栈状态/日志/停止栈时可跳过建 `.env`，直接用 `make ps` / `make logs` / `make down`。

### 2.2 建议的初始动作（按顺序）

1. **改 admin 密码**：右上角头像 → 退出前先在「用户与角色」确认 admin 密码已轮换。
2. **配置模型价格**：「模型价格」→ 添加所用模型（如 `gpt-4o-mini` 输入 0.15 / 输出 0.60 USD/M tokens）——**不配价格，成本永远显示 $0**。
3. **配置 LLM 端点**：「系统设置 → LLM 端点」先选**协议**（`OpenAI 兼容（/chat/completions）` 或 `Anthropic / Claude（/messages）`），再填 Base URL + Key → 点「测试连通性」。Base URL 填到 `/v1` 即可，系统按协议自动补端点，也可直接粘贴完整 endpoint。这是 **Playground 与 LLM 类评估器**的依赖；不配置时 Playground 明确提示 `LLM_UNAVAILABLE`。
4. **签发 Ingest Key**：「Agent 接入 → Ingest Keys」→ 签发并复制明文（平台只存哈希，**离开页面无法再看**）。
5. （可选）**通知渠道**：「系统设置 → 通知」配置 SMTP 发件人或 Webhook，用于 Incident 派发。

---

## 3. Agent 接入（六选一）

| 方式 | 适用 | 最小步骤 |
|---|---|---|
| **Python SDK** | 自研 Python Agent | 见下三步 |
| **Codex CLI** | Codex 用户 | `pip install emonlens && emonlens-codex-setup --host … --key …` |
| **Claude Code** | Claude 用户 | `emonlens-claude-setup --host … --key …` |
| **ZCode** | ZCode 用户 | `emonlens-zcode-setup --host … --key …`（新开会话后生效） |
| **DeepSeek Harness** | DSH 用户 | `emonlens-dsh-sync --host … --ingest-key … --limit 3`（**回放式**，跑完会话后同步；非实时） |
| **OTLP** | 已有 OpenTelemetry 埋点 | POST `/api/v1/ingest/otlp/v1/traces`（JSON/HTTP，带 X-Trust-Key） |

**Python SDK 三步接入**（只需环境变量，无需调用 init）：

```bash
export EMONLENS_HOST="http://emonlens:8300"
export EMONLENS_INGEST_KEY="<2.4 签发的明文 Key>"
export EMONLENS_AGENT="my-agent"
```

```python
from emonlens import Trace

with Trace(session_id="chat-1", user_id="u1", input="用户问题") as tr:
    tr.llm("call_model", model="gpt-4o-mini", input=prompt, output=answer,
           input_tokens=512, output_tokens=344)
    tr.tool("query_db", input={"sql": "..."}, output={"rows": 5})   # dict 自动序列化
```

或全自动埋点（推荐）：`@observe` 装饰函数即可，入参/返回值/异常自动采集，`update_current(model=..., input_tokens=...)` 补充用量。两种方式可混用：`with Trace(...)` 内调用 `@observe` 函数会自动挂到同一 Trace。

**验证接入**：跑一次对话 → 「调用链路」出现该 Agent 的 Trace（`COMPLETED`、模型列有值）→ 「Agent 接入」健康列为 `正常`。

---

## 4. 日常操作手册（按页面）

### 4.1 总览 `/`

信任分卡（总体分 + 五维 + 状态分布 + 最高/最低 Agent）、调用趋势、错误率、P95 延迟、成本、模型用量、Top 用户、近期错误、最新告警。顶部可切时间范围（24h/7d/30d）与 Agent/环境过滤。**每天看一眼这里即可掌握全局**。

### 4.2 调用链路 `/traces`（核心页面）

- **检索**：顶部时间范围 + `＋ 筛选`（Agent/状态/模型/耗时/token 成本区间/标签/环境/错误有无），支持关键字搜输入输出摘要。
- **详情**：点击行 → 右侧面板展示输入/输出全文、**瀑布时间线**（父子树、各 span 耗时、tokens）、异常发现、信任分变化。tokens 口径为「输入 / 输出（缓存命中 N）」——命中 Prompt Cache 的部分单列，便于区分真实计费量。
- **协作动作**（详情面板内）：打标签、评论、人工评分（1-5 分）、收录数据集（弹窗内可直接新建数据集，无需预先创建）。
- **保存视图**：当前筛选条件可命名保存，下次从「视图 ▾」一键套用，也可删除（有确认）。
- **导出**：右上「导出 CSV」（按当前筛选导出全部页，不止当前一页）。
- **对比**：列表勾选两条 → 对比视图：元信息并排（不同项高亮）、Input/Output 内容 diff、按 span 名+类型逐一配对的耗时/成本/token 差异，以及结论摘要条（谁更快/更贵/token 更多）。

### 4.3 会话 `/sessions`

同一 sessionId 的多轮调用聚合为对话级视图：调用数/错误/成本/总耗时/Tokens 汇总 + 时间线（相邻调用显示「⏱ 间隔」chip）。点进看会话内全部 Trace。

### 4.4 事件告警 `/alerts` —— 监控规则使用指南

三栏：**规则** / **触发历史（Incident）** / **异常发现（Finding）**。命中链路：规则命中 span → Finding → 同指纹聚合为 Incident → （配了通知时）按模板派发。

三个页签都可深链（`/alerts?tab=history`、`/alerts?tab=findings`），刷新后保持当前页签，也可直接把链接发给同事；不带参数时默认落在「规则」。

#### 三种规则类型怎么选

| 类型 | 判定对象 | 典型场景 | 新建难度 |
|---|---|---|---|
| **VALIDATION** | 单条 span（逐条判定） | LLM 调用失败、慢调用、非白名单模型、限流错误 | 点模板即可 |
| **METRIC** | 一段时间窗的聚合指标 | 错误率 >10%、平均耗时超标、步数超限 | 下拉选择 |
| **EVALUATION** | 评估器通过率 | 自动评估通过率跌破阈值 | 需先建评估器 |

#### VALIDATION 四步上手（无需手写表达式）

1. 「＋ 新建规则」→ 填规则 Key、选作用 Agent；
2. 点**常用模板**一键填入（LLM 调用失败 / 慢调用 >30s / 限流类错误 / 非白名单模型 / 输出超长…），模板会同时填好 Span 类型与条件表达式，可再手改；
3. 表单底部展开**「可用字段与语法速查」**：全部可引用字段（status、error_type、model_name、duration_ms、tokens 等 + attributes 内字段如 cwd、turnId）与运算符。**点字段名会自动插入「字段 == 」比较骨架**（不是裸字段名），直接补右侧值即可；表达式缺比较运算或以运算符结尾时，表单会实时给出提示；
4. 创建后**先点列表中的「试跑」**：对最近 20 条真实 Trace 逐条显示命中明细（不影响线上），确认符合预期再保持启用。

> 规则用旧了直接删：操作列「删除」会连同样规的触达状态一起清除，已产生的 Incident/Finding 保留作为审计轨迹。

#### where 表达式速查（SpEL 沙箱，true = 违规）

```
比较      status == 'ERROR'      duration_ms > 30000
逻辑      status == 'ERROR' and span_name.contains('call')
集合      !({'gpt-4o-mini','gpt-4o'}.contains(model_name))     ← 白名单外即违规
正则      error_type matches '.*RateLimit.*'
空判断    model_name == null        tool_name != null
字符串    span_name.startsWith('query')   tool_name.contains('db')
attributes 字段可直接引用（如 turnTotalTokens > 100000）
```

> 沙箱说明：表达式在只读上下文执行，无类型引用/构造/静态方法，仅放行 contains、startsWith、
> endsWith、equalsIgnoreCase、toLowerCase、toUpperCase、trim、isEmpty、size、length 等确定性方法。

#### 推荐的第一批规则（从模板开始）

| 规则 | 模板 | 严重级建议 |
|---|---|---|
| Agent 可用性 | LLM 调用失败（status == 'ERROR'） | HIGH |
| 性能劣化 | 慢调用 >30s | MEDIUM |
| 依赖健康 | 限流类错误（error_type matches '.*RateLimit.*'） | MEDIUM |
| 成本/额度 | 输出超长 >4K tokens | LOW |

命中后：**触发历史**页确认（认领）→ 处理 → 恢复（关闭）；同一指纹的重复命中自动聚合到同一 Incident，不会刷屏。

### 4.5 评估器 `/evaluators` 与评估结果 `/evaluations`

- 四类评估器：**EXPRESSION**（零依赖表达式，如 `error_count == 0`）、**REGEX**（对指定字段正则断言，如 PII/关键词）、**LLM_JUDGE**（调用 LLM 端点按 Rubric 判分，需先在「系统设置 → LLM 端点」配置并测通）、**TRAJECTORY**（对工具调用顺序/参数/步数做确定性检查，仅 TRACE 有工具证据）。
- 「测试」按钮：拿任意 trace 单条试测，直接看分数/标签/判定原因。
- EXPRESSION 可用变量（表单内常驻列表）：`trace_id` `agent_key` `agent_version` `span_count` `error_count` `duration_ms` `tool_calls` `llm_calls`——注意是**下划线风格**，与实体的 camelCase 不同。写错变量名会在试跑时直接报错并附上这份清单，不会静默判 FAIL。
- 「删除」按钮：连同该评估器的历史评估结果一起清除（删除前有确认，不可恢复）；只想临时停用则用状态开关，历史结果保留。
- 自动评估：开启后按采样率（系统设置 → 自动评估采样，默认 1%）对上报流量自动评估。
- 评估结果页：按评估器/标签（PASS/FAIL）筛选，可导出 CSV；**点任意一行**看该条结果的完整详情（分数、置信度、判定原因全文、跳转对应 Trace）。
- LLM_JUDGE 的失败是**可诊断**的：端点不通或模型没按 JSON 格式作答（缺 score 等）时，结果标 `EVAL_UNAVAILABLE`，原因里带服务端返回的原始内容；不会伪装成 FAIL 分数。

### 4.6 数据集 `/datasets` 与回归实验

两种建法：① 页面直接新建后添加样本；② **从 Trace 详情「收录数据集」**（把生产输入/输出沉淀为期望样本，推荐闭环）。

- 「运行实验」选评估器 + 模式：
  - **TRACE**：对样本关联的历史 Trace 重新评估（检验评估器变更的影响）；
  - **REPLAY**：用样本 input 重新生成输出再评估（真正的回归测试，需 LLM 端点）。
- 详情页查看通过率趋势、两次实验 diff（regressed/improved/same）、导出 CSV；每条实验点「结果」看逐样本明细（分数/标签/原因）。
- **运行报告可深链**：打开结果后地址会变成 `/datasets/:id/runs/:runId`，右上角「复制链接」可直接发给同事；校准、Gate、业务证据、质量案例都在报告里。
- **评估套件**：在「评估器」页右上角「评估套件」把 1–5 个评估器冻结成一个版本，运行实验时选择套件即可复现当时的评估标准。
- **完整 3.0 流程**（校准 / Gate / 质量案例 / 领域包 / 业务实验）见[《EmonLens评测与准入操作指南》](EmonLens评测与准入操作指南.md)。
- **Playground 里也能批量跑**：Playground 底部「数据集批量试跑」选数据集 → 配置变量映射（第一个变量自动取样本 input，其余可取期望输出或手填）→「运行全部」，逐行看输出与对照结论。

### 4.7 提示词 `/prompts` 与 Playground `/playground`

- Prompt 版本化：同名多版本，`production` 标签供 Agent 运行时拉取（SDK `emonlens.get_prompt("name")`，60s 缓存，失败不阻塞业务）。
- Playground：左侧渲染模板 + 变量试跑；**A/B 对照**开两组参数并发执行对比输出与耗时。
- 「Agent 接入」页展示数据面地址与 Ingest Keys 管理（签发/吊销/最近使用）。

### 4.8 标注队列 `/annotation-queues`

建队列（如"客服助手周度抽检"）→ 添加样本（每行一个 Trace ID）→ 逐条「领取」打分 + 评语完成。标注进度以进度条呈现；也可以把样本**预分配给**具体标注人。完成的人工评分会写入该 Trace 的评估结果（计入信任分与评估统计），删队列时连带清理。

- Playground：左侧渲染模板 + 变量试跑；**A/B 对照**开两组参数并发执行对比输出与耗时。
- 「Agent 接入」页展示数据面地址与 Ingest Keys 管理（签发/吊销/最近使用）。

### 4.9 用户洞察 `/users` 与用量成本 `/usage`

- 用户洞察：按 `user_id` 聚合调用/会话/错误/成本，点开看单用户全部行为。
- 用量成本：按天/模型/Agent 维度的调用量与成本（依赖 2.2-2 的模型价格配置）。

### 4.10 系统设置 `/sys/settings`（管理员）

四个标签：**LLM 端点**（评估/Playground 共用，可测试连通性）、**通知**（发件人/模板/发送记录）、**审计日志**（全部写操作留痕：谁/何时/对什么做了什么）、**API Token**（开放 API 凭据，见 §5.3）。

### 4.11 用户与角色 `/sys/users-roles`

内置 3 角色：**admin**（全部 27 项权限）/ **operator**（业务读写，无系统管理）/ **viewer**（8 项只读）。新建用户指派角色即可，权限在角色上维护。

---

## 5. 机器对机器访问

### 5.1 数据面（上报）

`POST /api/v1/ingest/events`（JSON 数组，头 `X-Trust-Key: <Ingest Key>`）。限流：每 workspace 默认 20000 spans/分钟，超限返回 **429 + Retry-After**，SDK 自动退避重试本批不丢数据。

### 5.2 OTLP

`POST /api/v1/ingest/otlp/v1/traces`，标准 OTLP/HTTP-JSON；attributes 里带 `emonlens.agent_key`（或 service.name）即可归属 Agent。

### 5.3 开放 API（X-Api-Token）

「系统设置 → API Token」签发（带 scope，如 `score:read`）→ 外部系统以 `X-Api-Token` 头调用，例如：

```bash
curl -H "X-Api-Token: eak-xxx" http://emonlens:8300/api/v1/open/score/agents/my-agent
```

token 可随时吊销，吊销立即失效。

---

## 6. 运维与验证工具

| 工具 | 命令 | 用途 |
|---|---|---|
| 冒烟（13 项） | `bash scripts/smoke.sh [BASE] [USER] [PASS]` | 发布后快速确认核心链路（健康/登录/8 个核心 API/前端产物/数据面拒绝语义） |
| 全功能验证 | `python3 scripts/verify_all.py [BASE] [USER] [PASS]` | 逐域黑盒走查全部功能闭环（幂等可重复执行） |
| 容量监控 | `GET /api/v1/observations/stats`（admin JWT） | 队列水位/累计接收/丢弃/限流计数；`droppedTotal` 增长 = 处理能力不足 |
| 备份 | `docker compose exec mysql mysqldump …` | 见部署手册 §5 |

**验证基准**（0.1.0 发布时）：后端 78 + SDK 41 测试全绿；黑盒验证 80/80；冒烟 13/13。

**常见故障速查**：

| 现象 | 原因与处理 |
|---|---|
| 会话页无数据 | Agent 上报的 span 缺 sessionId（旧版 hooks 的 bug，已修复——升级 SDK 后新数据自动归组）；历史无归属数据无法回填 |
| 用户洞察页无数据 | Agent 上报未携带 userId：SDK 接入在 Trace 传 `user_id`；Codex/Claude hooks 在 `~/.emonlens/config.json` 加 `"user": "标识"` 或设 `EMONLENS_USER` |
| 成本恒为 $0 | 未配置模型价格（§2.2-2） |
| Playground 报 LLM_UNAVAILABLE | 未配置 LLM 端点或端点不通（§2.2-3） |
| 连通性测试报 `llm http status 400 … MissingSessionID` | 端点要求 `x-opencode-session` 会话头（OpenCode Zen/Go）：0.1.0 起服务端已自动发送，升级后重测；仍失败则确认 Base URL 用 `https://opencode.ai/inference/go/openai/v1`（OpenAI 协议）或 `…/anthropic/v1`（Claude） |
| 连通性测试报 404 / `model is unavailable` | 协议选错或模型未开通：Claude 系模型选 Anthropic 协议；OpenAI 协议走 `/chat/completions`，`/responses` 专用模型需换对应端点 |
| 连通性测试报 401 | API Key 错误或未覆盖已保存值：设置页 Key 留空表示沿用旧值，换 Key 需重新输入 |
| Trace 一直 RUNNING | 轮次进行中（正常）；或根 span 缺失由 idle 超时（15 分钟）收口 |
| 模型列显示为空 | Agent 未携带 model（检查 SDK/桥接版本） |
| SDK 数据不上报 | ① 环境变量未设齐（HOST/KEY/AGENT）；② key 已吊销；③ `~/.emonlens/failed/` 有失败落盘件（服务端 4xx 拒收，人工检查后可恢复重放） |
| 登录 50301 | 触发防爆破锁定，等 15 分钟 |

---

## 7. 版本与支持

- 已知限制与多实例边界：见[部署运维手册](EmonLens部署运维手册.md) §1
- 文档族：[`../README.md`](README.md)（设计 / 操作 / 参考三类索引）
