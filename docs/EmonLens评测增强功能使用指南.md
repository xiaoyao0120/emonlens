# EmonLens 评测增强功能使用指南

> 适用版本：0.2.0（在 0.1.0 评测增强基础上新增：Playground 矩阵实验与 Diff、Prompt 版本 × 实验打通、GitHub Action 与 PR 评论）。
> 面向：要落地 RAG / Agent / 多轮评测的评测同学、Agent 负责人和 CI 维护者。
> 本文只讲“怎么用、字段怎么填、失败怎么查”。评测准入的整体流程见《EmonLens评测与准入操作指南》；部署与环境变量见《EmonLens部署运维手册》。

---

## 1. 能力地图

| 能力 | 入口 | 解决什么问题 | 默认状态 |
| --- | --- | --- | --- |
| 样本契约 v2 | 数据集详情 →「样本与设置」→ 添加/编辑样本 → 高级字段 | 让 RAG、工具调用、多轮会话有可评测的证据 | 可用；填写 v2 字段自动 `schemaVersion=2` |
| 批量导入 / 导出 | 数据集详情 →「样本（Items）」→ 导入 / 导出 JSONL / 导出 CSV | 冷启动、跨环境搬运、Excel 协作 | 可用 |
| 合成数据集 | 同一卡片 →「合成」 | 没有历史样本时，用 LLM 生成候选并人工筛选 | 默认关闭，需显式开启 |
| Judge 模板 | 评估器 → LLM_JUDGE →「Judge 模板 key」 | 把评审指令版本化、可追溯 | 平台模板可用；workspace 模板需 API 创建 |
| 指标预设 | 评估器 → LLM_JUDGE →「指标预设」 | 一键套用 Agentic / RAG / 多轮指标 | 13 个内置预设 |
| CLAIMS 判分 | 评估器 → LLM_JUDGE →「判分模式 = CLAIMS」 | 把整体打分拆成逐条可核验 claim | 可用 |
| TRAJECTORY | 评估器 → 类型 = TRAJECTORY | 对工具调用顺序、参数、步数做确定性检查 | 可用；仅 TRACE 模式有工具证据 |
| 评测缓存 | 配置开关 | 重复 Judge 调用不重复付费 | 默认关闭 |
| 成本报告 | `GET /api/v1/datasets/{id}/runs/{runId}/cost`；运行报告成本区 | 按阶段/模型/评估器看 token 与费用 | 可用 |
| SDK / CLI | `emonlens` Python 包 | CI 提交评测、等待结果、校验历史 Gate | 可用；需开放 API Token |
| 矩阵实验（0.2.0） | 构建 →「调试与提示词」→「矩阵实验」 | 一次跑「数据集 × prompt 版本 × 模型」网格，看谁的输出更好 | 可用；默认 20 样本 × 8 列 |
| 矩阵 Diff（0.2.0） | 矩阵详情 → Diff | 逐样本给出改进 / 退步 / 有得有失 / 持平 | 可用；语义判定消耗 judge 调用 |
| Prompt 版本绑定（0.2.0） | run 请求带 `promptName`/`promptVersion` | 回答「这次 run 评的是哪个 prompt 版本」 | 可用；绑定后进候选指纹 |
| CI 门禁 Action（0.2.0） | 仓库根 `action.yml` | PR 上跑评测并把 Gate 结论回写成评论 | 可用；需 `pull-requests: write` |

**三条使用原则：**

1. **证据不足返回 UNKNOWN，不返回 FAIL。** 缺检索上下文、缺工具轨迹、缺参考答案时，先补证据，不要用 FAIL 掩盖数据问题。
2. **Judge 是“模型建议”。** 含 LLM_JUDGE 的 run，Gate 会要求人工盲审校准；未达到校准要求时 Gate 返回 `BLOCKED`，不会因为 Judge 说 PASS 就放行。
3. **合成样本不会自动进数据集。** worker 只产出候选，必须人工逐条批准；低质量和重复候选会被标为 REJECTED。

---

## 2. 使用前准备

### 2.1 LLM 端点

需要 Judge、合成、REPLAY 生成时，先在「系统设置 → LLM 端点」配置：

- 协议：`openai`（`/chat/completions`）或 `anthropic`（`/messages`）；
- Base URL、API Key、默认模型；
- 用页面上的“测试连通性”确认可用。

未配置时：

- LLM_JUDGE 结果标为 `EVAL_UNAVAILABLE`，原因码通常是 `JUDGE_UNAVAILABLE`；
- 合成任务会以 `FAILED` 结束，错误信息包含 `LLM 未配置`；
- REPLAY 生成会失败，任务进入 ERROR。

### 2.2 开关与默认值

| 配置项 | 环境变量（compose） | 默认值 | 说明 |
| --- | --- | --- | --- |
| `emonlens.eval.cache-enabled` | `EMONLENS_EVAL_CACHE_ENABLED` | `false` | 开启 LLM Judge 结果缓存 |
| `emonlens.eval.cache-ttl-hours` | — | `168` | 缓存 TTL（小时） |
| `emonlens.eval.cache-allow-stochastic` | — | `false` | `temperature>0` 的 Judge 是否允许缓存 |
| `emonlens.eval.llm.max-attempts` | — | `3` | Judge/生成调用的最大尝试次数 |
| `emonlens.eval.llm.initial-backoff-ms` | — | `500` | 首次重试退避 |
| `emonlens.eval.llm.max-backoff-ms` | — | `5000` | 最大退避 |
| `emonlens.eval.llm.jitter` | — | `0.3` | 退避抖动比例 |
| `emonlens.eval.sdk-result-grace-hours` | — | `24` | SDK 评测器结果回写宽限期；超时未回写按 SKIPPED 收敛 |
| `emonlens.dataset-synthesis.enabled` | `EMONLENS_DATASET_SYNTHESIS_ENABLED` | `false` | 允许创建合成任务 |
| `emonlens.dataset-synthesis.max-candidates` | — | `50` | 单任务候选硬上限 |
| `emonlens.dataset-synthesis.min-quality` | — | `0.6` | critic 默认阈值 |
| `emonlens.dataset-synthesis.max-retries` | — | `2` | critic 默认重试次数 |
| `emonlens.otlp.max-body-chars` | `EMONLENS_OTLP_MAX_BODY_CHARS` | `33554432` | OTLP/HTTP-JSON 接入请求体字符上限（超出 400）；collector 大批次时可调大 |

### 2.3 权限

| 操作 | 控制面权限 | 开放 API scope |
| --- | --- | --- |
| 查看数据集/样本/合成任务 | `workspace:trust:dataset:query` | — |
| 添加/编辑样本、导入、创建合成任务、批准候选 | `workspace:trust:dataset:manage` + `workspace:trust:trace:content` | — |
| 软失效/删除样本、拒绝候选 | `workspace:trust:dataset:manage` | — |
| 查看评估器/模板 | `workspace:trust:evaluator:query` | — |
| 创建/修改评估器、模板 | `workspace:trust:evaluator:manage` | — |
| 运行/重试/续跑实验（控制面） | `workspace:trust:evaluation:run` + `workspace:trust:trace:content` | `eval:run`（开放 API 不需内容权限） |
| 读取 run 与 Gate | `workspace:trust:evaluation:query` | `eval:read` / `score:read` |
| 查看/导出样本正文 | `workspace:trust:trace:content` | — |

没有 `TRACE_CONTENT` 时：页面隐藏正文，导出只含标签、状态和 hash；添加/编辑样本、导入、创建合成任务、批准候选都会被拒绝（避免盲编辑把正文覆盖为空）；合成任务的 source/payload 和候选过滤原因备注也会被裁剪；运行报告中的 input/expected、检索上下文、期望工具、多轮、期望、claim 原文/理由会被移除，只保留 `claimAggregate` 的计数与比例。不要用“没有正文”判断样本为空。

开放 API Token 在「系统设置 → API Token」签发，明文只显示一次；根据用途勾选 `eval:run`、`eval:read`、`eval:content`、`score:read`。请求头是 `X-Api-Token: eak-...`，不要和数据面的 Ingest Key 混用。`eval:content` 只用于 SDK 评测器拉取样本载荷（含正文）；没有它时 sdk-tasks 只回传 hash 与状态。

### 2.4 数据库迁移

已有 MySQL 库按顺序执行以下幂等脚本，执行前备份：

```bash
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-02-eval-case-v2.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-09-judge-template.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-16-eval-cache.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-23-dataset-synthesis.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-30-dataset-item-hash-index.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-12-07-eval-task-type.sql
```

迁移只加表/加列/加索引，不改写旧样本。旧样本保持 `schemaVersion=1`，旧 run 和 Gate 继续按原样读取。

---

## 3. 样本契约 v2

### 3.1 页面操作

1. 打开「数据集 → 某个数据集 → 样本与设置」。
2. 点「添加样本」或样本行的「编辑」。
3. 填写基础字段（输入、期望输出、用途、风险、切分、失败模式）。
4. 展开「高级：检索 / 工具 / 多轮 / 期望」，按下方格式填写 JSON。
5. 保存后，样本表的「契约」列会显示 `v2 · 检索 N / 工具 N / 多轮 N / 期望`。

> 只填基础字段时保持 v1；填写任意 v2 字段会自动升级到 v2。不要手工把 `schemaVersion` 写回 1 再保留 v2 字段，后端会直接拒绝。

### 3.2 字段与限制

| 字段 | 类型 | 限制与说明 |
| --- | --- | --- |
| `schemaVersion` | 1 / 2 | 有 v2 字段时自动为 2；显式写 1 且带 v2 字段 → 校验错误（业务码 40000） |
| `retrievalContext` | 数组 | 最多 20 条；每条必须是对象，`content` 必填且 ≤ 8000 字符；`id` / `source` ≤ 255；`score` 若填必须是 0–1 |
| `expectedTools` | 数组 | 最多 50 条；`name` 匹配 `[A-Za-z][A-Za-z0-9_.-]{0,127}`；`type` 只能是 `FUNCTION` / `MCP`；`required` 必须是布尔；`inputParameters` 必须是对象且序列化后 ≤ 4000 字符 |
| `turns` | 数组 | 最多 50 轮；`role` 只能是 `user` / `assistant` / `tool` / `system`；`content` 必填且 ≤ 8000 字符；`toolsCalled` 若填必须是数组 |
| `expectations` | 对象 | `must` / `mustNot` 最多各 20 条，每条非空字符串 ≤ 500 字符；`unknownPolicy` 只能是 `PASS` / `FAIL` / `BLOCK` |
| `metadata` | 对象 | 必须是 JSON 对象；进入 `contentHash`，修改会改变样本指纹 |
| 总长度 | — | 样本契约字段总长度受 `emonlens.dataset-run.max-snapshot-chars`（默认 50000）限制；超限在写入时直接拒绝，不等到运行才截断 |

### 3.3 JSON 示例

```json
{
  "input": "帮我查一下订单 A123 的退款进度",
  "expectedOutput": "订单 A123 已退款，预计 1–3 个工作日到账。",
  "schemaVersion": 2,
  "retrievalContext": [
    {"id": "doc-1", "content": "A123 于 2026-10-20 完成退款审批。", "score": 0.93, "source": "kb/refund.md"}
  ],
  "expectedTools": [
    {"name": "query_order", "type": "FUNCTION", "inputParameters": {"orderId": "A123"}, "required": true}
  ],
  "turns": [
    {"role": "user", "content": "订单 A123 退了吗？"},
    {"role": "assistant", "content": "正在为您查询。"}
  ],
  "expectations": {
    "must": ["说明退款状态", "给出到账时间"],
    "mustNot": ["承诺立即到账"],
    "unknownPolicy": "BLOCK"
  }
}
```

### 3.4 运行时如何使用这些字段

**TRACE 模式：**

- `toolsCalled` 从 trace 的 `tool` span 投影：`name` 优先取 `toolName`，其次 `spanName`；`inputParameters` / `output` 尽量解析为 JSON，解析失败保留原文；按 span 顺序排列。
- 如果样本没有显式 `retrievalContext`，运行时会用 `retrieval` span 的输出作为实际检索证据，并标记 `retrievalContextSource=TRACE`。
- 如果样本显式提供了 `retrievalContext`，以样本为准，标记 `retrievalContextSource=SAMPLE`。

**REPLAY 模式：**

- 使用样本 `input` 重新生成候选，只做文本重放。
- 不产生 `toolsCalled`；来自 trace 的检索证据会被丢弃（`retrievalContextSource=DROPPED`）。
- 样本显式填写的 `retrievalContext` 仍会保留，因此 RAG 指标可以在 REPLAY 下使用，但 Agent 轨迹类指标不行。

**关于 `expectations`：**

`must` / `mustNot` / `unknownPolicy` 会作为评审证据传入 LLM Judge 的 Evidence 部分，帮助模型理解样本期望；当前运行时不把它当独立硬规则执行，也不会因为 `mustNot` 命中就自动 FAIL。需要硬规则时，请使用 TRAJECTORY、EXPRESSION 或 REGEX 评估器。

### 3.5 常见校验错误

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `retrievalContext 必须是数组` | 填了对象或字符串 | 改成 `[{...}]` |
| `retrievalContext[0].content 必填` | 缺 content | 每条检索证据必须有正文 |
| `turns[1].role 只能是 user/assistant/tool/system` | role 拼写错误 | 改成四者之一 |
| `retrievalContext/expectedTools/turns/expectations 需要 schemaVersion=2` | 显式写了 `schemaVersion=1` 又带 v2 字段 | 删掉 `schemaVersion` 或改成 2 |
| `样本契约字段总长度 ... 超过快照上限` | 单条样本太大 | 拆分样本或缩短检索正文 |

---

## 4. 批量导入与导出

### 4.1 页面操作

1. 数据集详情 →「样本（Items）」卡片。
2. 「导入」：粘贴 CSV/JSONL，先勾选“仅校验（dry-run）”看结果，再取消勾选执行。
3. 「导出 JSONL」/「导出 CSV」：浏览器直接下载 `dataset-<id>.jsonl` / `dataset-<id>.csv`。

### 4.2 JSONL 格式

每行一个 JSON 对象，空行忽略。字段支持 snake_case 和 camelCase：

```jsonl
{"input":"q1","expected_output":"a1","risk_level":"LOW","purpose_tags":"regression"}
{"input":"q2","schema_version":2,"retrieval_context":[{"id":"d1","content":"ctx"}],"expected_tools":[{"name":"query_order","required":true}]}
{"metadata":{"lang":"zh"},"input":"q3"}
```

`metadata` 推荐直接写 JSON 对象；也接受 JSON 字符串。`retrieval_context` / `expected_tools` / `turns` / `expectations` 也接受对象/数组或 JSON 字符串。

### 4.3 CSV 格式

首行表头，支持 RFC4180 引号、字段内逗号和换行。JSON 字段在单元格里写 JSON 字符串：

```csv
input,expected_output,retrieval_context,purpose_tags,risk_level
"q, with comma","a1","[{""id"":""d1"",""content"":""ctx""}]",regression,HIGH
```

可用的表头（多写未知列会被忽略，少写字段按默认值）：

`input, expected_output, retrieval_context, expected_tools, turns, expectations, metadata, purpose_tags, risk_level, group_key, split, failure_mode, trace_id, source_type, source_ref`

- `input` 与 `trace_id` 至少一个非空。
- `split` 留空默认 `UNASSIGNED`，`risk_level` 留空默认 `UNASSIGNED`。
- `source_type` / `source_ref` 仅用于导出审计；导入后统一写为 `IMPORT` / `import`。
- 导出的 CSV 带 UTF-8 BOM（Excel 兼容）；回导时会自动去掉 BOM。

### 4.4 dry-run、atomic 与计数

| 模式 | 行为 |
| --- | --- |
| `dryRun=true` | 只解析、校验、去重，不写库。`created=0`，`wouldCreate` 给出“可新建”数量 |
| `atomic=true` | 任一行校验失败 → 整批失败（业务码 40000），不写任何行 |
| 默认（`dryRun=false, atomic=false`） | 跳过非法行，写入合法行；数据库写入异常时整批回滚 |

返回示例：

```json
{
  "total": 4,
  "created": 2,
  "wouldCreate": 2,
  "duplicated": 1,
  "invalid": 1,
  "dryRun": false,
  "errors": [{"row": 4, "message": "turns[0].role 只能是 user/assistant/tool/system"}]
}
```

- `row` 是解析后的数据行序号（JSONL 跳过空行；CSV 不含表头）。
- `duplicated` 是 `contentHash` 已存在或本批内重复的行数。
- 单次最多 1000 行、内容最多 5 MiB；单条样本仍受快照上限约束。

### 4.5 幂等与去重

`contentHash` 由样本语义字段计算：版本、traceId、input、expectedOutput、metadata、用途、风险、切分、失败模式、检索、工具、多轮、期望。任一字段变化都会得到新 hash。

- 重复导入同一内容 → `duplicated +1`，不会插入第二条。
- 已软失效（`INVALIDATED`）的样本不算重复，重新导入会创建新的 ACTIVE 样本。
- 导入不会覆盖已有样本，也不会改写历史 revision。

### 4.6 导出与权限

- JSONL 导出包含 `id`、`schema_version`、`status`、标签、`content_hash`、`source_type/source_ref`，有内容权限时再包含正文和 v2 字段。
- CSV 导出列固定为：`input, expected_output, retrieval_context, expected_tools, turns, expectations, metadata, purpose_tags, risk_level, group_key, split, failure_mode, trace_id, source_type, source_ref`；不包含 id / status / content_hash / schema_version。
- 没有 `TRACE_CONTENT` 时，正文列不输出；不要用导出文件判断样本是否为空。
- 需要完整往返（保留 `schema_version` 和 v2 空契约）时优先用 JSONL；CSV 适合人工编辑和 Excel 协作。

### 4.7 API 调用

控制面接口的业务错误通常是 HTTP 200 + `ApiResult.code`（如校验失败 40000、冲突 40901）；只有限流是 HTTP 429。脚本里要判断 `code`，不能只看 HTTP 状态。

```bash
# dry-run
curl -X POST "$HOST/api/v1/datasets/$DATASET_ID/items/import" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{"format":"jsonl","dryRun":true,"atomic":false,"content":"{\"input\":\"q1\"}\n"}'
```

```bash
# 导出（format=jsonl|csv）
curl -L "$HOST/api/v1/datasets/$DATASET_ID/items/export?format=jsonl" \
  -H "Authorization: Bearer $JWT"
```

### 4.8 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| `JSONL 第 N 行不是合法 JSON` | 整行 JSON 语法错误 | JSONL 是“每行一个完整对象”，不能写成跨行数组 |
| dry-run 显示“可新建 0，重复 N” | 内容与已有 ACTIVE 样本相同 | 修改内容或换数据集 |
| `atomic 导入失败` | 有非法行 | 先 dry-run 看 `errors[].message`，修正后重试 |
| 回导 CSV 报“trace_id 或 input 至少填一项” | 旧版本 BOM 问题 | 升级到含 BOM 修复的版本；临时可用 `sed` 去掉首字符 |
| 导出没有正文 | 当前账号无 `TRACE_CONTENT` | 换有内容权限的账号，或只做标签/结构迁移 |

---

## 5. 合成数据集

### 5.1 开启条件

1. `emonlens.dataset-synthesis.enabled=true`（compose 用 `EMONLENS_DATASET_SYNTHESIS_ENABLED=true`）。
2. LLM 端点已配置且可用。
3. 当前账号有 `DATASET_MANAGE`。

未开启时创建任务返回校验错误（业务码 40000）：`dataset synthesis is disabled`。

### 5.2 页面操作

1. 数据集详情 →「样本（Items）」→「合成」。
2. 选择来源、生成数量、最低质量分、critic 重试次数；可填场景描述。
3. 点「开始生成」。页面每 2 秒刷新任务状态，也可以手动点「刷新」。
4. 任务完成后，候选列表显示 `DRAFT` / `REJECTED` / `APPROVED`。
5. 对 `DRAFT` 候选点「批准」→ 进入数据集（`source_type=SYNTHETIC`）；点「拒绝」→ `REJECTED`。

关闭抽屉或离开页面会停止前端轮询；重新打开「合成」会恢复最近一次任务及其候选，点「新建任务」再回到来源配置。任务本身仍在后台执行。

### 5.3 四种来源

| `sourceType` | 用途 | `source` 示例 | 说明 |
| --- | --- | --- | --- |
| `SCRATCH` | 没有任何样本，从场景描述冷启动 | `{"numGoldens": 10}` | `numGoldens` 缺省用 `maxCandidates`；场景描述通过 `config.styling.scenario` 传入 |
| `DATASET_ITEMS` | 从现有样本进化 | `{"itemIds":["id1","id2"]}` | `itemIds` 为空表示当前数据集全部 ACTIVE 样本；种子包含 input、expected、retrieval_context |
| `DOCUMENTS` | 从上传文本生成 | `{"documents":[{"name":"refund.md","content":"退款政策……"}]}` | content 按 1500 字符切块，每块一个种子；适合制度/FAQ |
| `TRACE_CLUSTER` | 从真实 Trace 生成 | `{"traceIds":["trace-abc","trace-def"]}` | 取 trace 的输入/输出预览作为种子；找不到的 trace 会跳过 |

### 5.4 生成配置

```json
{
  "maxCandidates": 10,
  "minQuality": 0.6,
  "maxRetries": 2,
  "model": "gpt-4o-mini",
  "styling": {"scenario": "售后客服，涉及退款、物流、换货"},
  "evolutions": {
    "REASONING": 0.2,
    "MULTI_CONTEXT": 0.2,
    "CONCRETIZING": 0.2,
    "CONSTRAINED": 0.1,
    "COMPARATIVE": 0.1,
    "HYPOTHETICAL": 0.1,
    "IN_BREADTH": 0.1
  }
}
```

| 字段 | 默认 | 约束 |
| --- | --- | --- |
| `maxCandidates` | 配置默认 | 1 到 `emonlens.dataset-synthesis.max-candidates`（默认 50） |
| `minQuality` | 0.6 | 0–1；低于阈值的候选标 REJECTED |
| `maxRetries` | 2 | 0–3；每个候选最多生成 `maxRetries+1` 次 |
| `model` | 系统 LLM 默认模型 | 可单独指定合成模型 |
| `styling` | 空 | 任意 JSON；UI 只写 `scenario` |
| `evolutions` | 7 类均分 | key 到权重的映射；用于轮换生成策略 |

### 5.5 生成与筛选流程

1. 生成模型按 `evolution` + `styling` + 种子生成一个样本 JSON（`input` 必填，`expected_output`、`retrieval_context`、`expected_tools`、`turns`、`purpose_tags`、`risk_level` 可选）。
2. critic 模型按“真实、自洽、有上下文依据”打 0–1 分；低于 `minQuality` 时按 `maxRetries` 重试。
3. 通过质量门槛后做去重：
   - 完全重复：同任务内 `candidateKey` 已存在 → `DUPLICATE`；
   - 近重复：与当前数据集已有文本的字符 bigram Dice ≥ 0.8 → `DUPLICATE_NEAR`。
4. 通过的候选写 `DRAFT`；失败/低质量/重复写 `REJECTED`，并在「过滤原因」列显示原因。

**过滤原因：**

| 值 | 含义 |
| --- | --- |
| `GENERATION_FAILED` | 生成或 critic 调用失败（含重试后仍失败） |
| `LOW_QUALITY: <reason>` | critic 分数低于阈值 |
| `DUPLICATE` | 同任务内完全相同 |
| `DUPLICATE_NEAR` | 与已有样本高度相似（Dice ≥ 0.8） |
| `REJECTED_BY_REVIEWER` | 人工拒绝（页面默认备注；API 可传自定义备注） |

### 5.6 批准与拒绝

- 只有 `DRAFT` 可批准/拒绝；`REJECTED` 不可批准。
- 批准时重新计算 `contentHash`；如果数据集中已有相同 ACTIVE 样本，会复用已有样本并记录 `createdItemId`，不会插入重复行。
- 批准后候选变 `APPROVED`，任务 `acceptedCount + 1`；拒绝后 `rejectedCount + 1`。
- 批准/拒绝是幂等的：重复批准同一候选返回已入库样本；重复拒绝返回已有候选。
- 合成任务不会自动批准，也不会写 revision；需要跑实验时仍由 run 自动冻结或手动「冻结当前样本」。

### 5.7 任务状态

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 已创建，等待 worker 认领 |
| `RUNNING` | 正在生成候选 |
| `COMPLETED` | 所有候选生成完成且无生成错误 |
| `PARTIAL_FAILED` | 有候选生成失败，但任务结束；可用候选仍可批准 |
| `FAILED` | 任务失败（如 LLM 未配置、无可用种子） |
| `INTERRUPTED` | worker 停止/重启导致中断；已有候选仍保留 |

### 5.8 API 调用

```bash
# 创建（Idempotency-Key 可选；同 key + 同数据集 + 同请求返回已有任务 created=false；跨数据集或内容不同返回冲突业务码 40901）
curl -X POST "$HOST/api/v1/datasets/$DATASET_ID/synthesis-jobs" \
  -H "Authorization: Bearer $JWT" \
  -H "Idempotency-Key: syn-demo-001" \
  -H "Content-Type: application/json" \
  -d '{
    "sourceType":"SCRATCH",
    "source":{"numGoldens":5},
    "config":{"maxCandidates":5,"minQuality":0.6,"maxRetries":2,
              "styling":{"scenario":"售后客服退款场景"}}
  }'

# 任务与候选
curl "$HOST/api/v1/datasets/$DATASET_ID/synthesis-jobs" -H "Authorization: Bearer $JWT"
curl "$HOST/api/v1/datasets/$DATASET_ID/synthesis-jobs/$JOB_ID" -H "Authorization: Bearer $JWT"
curl "$HOST/api/v1/datasets/$DATASET_ID/synthesis-jobs/$JOB_ID/candidates?status=DRAFT" -H "Authorization: Bearer $JWT"

# 批准 / 拒绝
curl -X POST "$HOST/api/v1/datasets/$DATASET_ID/synthesis-jobs/$JOB_ID/candidates/$CANDIDATE_ID/approve" \
  -H "Authorization: Bearer $JWT"
curl -X POST "$HOST/api/v1/datasets/$DATASET_ID/synthesis-jobs/$JOB_ID/candidates/$CANDIDATE_ID/reject" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" \
  -d '{"note":"与现有样本重复，人工拒绝"}'
```

### 5.9 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 创建任务报 `dataset synthesis is disabled` | 开关未开 | 打开 `emonlens.dataset-synthesis.enabled` 并重启 |
| 任务 `FAILED`，错误含 `LLM 未配置` | 端点未配置 | 系统设置 → LLM 端点，先测连通性 |
| `FAILED`，错误含“没有可用来源” | DATASET_ITEMS 无 ACTIVE 样本 / TRACE 找不到 / DOCUMENTS 为空 | 换来源或先补样本 |
| 候选全是 `LOW_QUALITY` | 阈值过高或场景描述太泛 | 降低 `minQuality`，补充场景与上下文 |
| 候选全是 `DUPLICATE_NEAR` | 数据集已有相似样本 | 换来源、提高多样性，或先清理重复样本 |
| 页面状态不更新 | 抽屉已关闭/后台标签页节流 | 重新打开「合成」或调用 API 查看；前端轮询已做停止兜底 |

---

## 6. 评估器：预设、模板、CLAIMS 与 TRAJECTORY

### 6.1 创建入口

「评估器 → ＋ 新建评估器」：

- `evaluatorKey` 唯一，创建后不可改；
- 类型创建后不可改；
- 修改 spec 会新增评估器版本，旧 run 仍绑定旧版本；
- 「质量维度」可填领域包 `qualityModel.dimensions[].key`，用于运行报告按维度汇总。

### 6.2 13 个指标预设

LLM_JUDGE 类型下选择「指标预设」后，会自动填入判分模式、模板 key 和所需证据字段；仍可手动改。预设只影响评估器 spec，运行时不动态变化。

| 预设 key | 模式 | 模板 | 必需证据 | 用途 |
| --- | --- | --- | --- | --- |
| `task-completion` | CLAIMS | `platform/claim-v1` | input, candidate_output | 任务是否真正完成，区分部分完成 |
| `plan-adherence` | CLAIMS | `platform/claim-v1` | input, trace_digest | 实际执行是否遵循计划 |
| `argument-correctness` | CLAIMS | `platform/claim-v1` | input, tools_called | 工具参数是否与任务/上下文一致 |
| `step-efficiency` | HOLISTIC | `platform/holistic-v1` | input, trace_digest | 工具/步骤是否冗余、低效、绕路（0–10） |
| `rag-faithfulness` | CLAIMS | `platform/claim-v1` | candidate_output, retrieval_context | 答案中的 claim 是否被检索上下文支撑 |
| `rag-answer-relevancy` | CLAIMS | `platform/claim-v1` | input, candidate_output | 答案是否回应用户问题 |
| `rag-contextual-recall` | CLAIMS | `platform/claim-v1` | expected_output, retrieval_context | 参考答案的 statement 是否能在检索上下文中找到依据 |
| `rag-contextual-precision` | CLAIMS | `platform/claim-v1` | input, expected_output, retrieval_context | 检索上下文是否相关、排序是否合理 |
| `rag-contextual-relevancy` | CLAIMS | `platform/claim-v1` | input, retrieval_context | 检索上下文中的 statement 是否与问题相关 |
| `rag-hallucination` | CLAIMS | `platform/claim-v1` | candidate_output, retrieval_context | 答案中的 claim 是否被检索上下文否定 |
| `conversation-goal-accuracy` | CLAIMS | `platform/claim-v1` | turns | 多轮目标步骤是否达成 |
| `conversation-knowledge-retention` | CLAIMS | `platform/claim-v1` | turns | 后续轮次是否保持已确认事实 |
| `conversation-turn-faithfulness` | CLAIMS | `platform/claim-v1` | turns | 逐轮 assistant 陈述是否被当轮检索上下文支撑 |

**模式选择建议：**

- RAG 指标和 Agentic 细粒度判断优先 CLAIMS：能定位是哪条 claim 失败。
- 只有整体质量、没有可拆 claim 时用 HOLISTIC。
- Agent 轨迹类预设需要 `tools_called` / `trace_digest`，只能在 TRACE 模式使用；RAG 指标在 REPLAY 下需要样本显式提供 `retrieval_context`。

### 6.3 CLAIMS 判分

评估器 LLM_JUDGE → 判分模式选 `CLAIMS`：

| 字段 | 说明 |
| --- | --- |
| 最多 claim 数 | 1–20，默认 20。**硬约束**：模型返回超过该数量时整次判分按非法输出处理，结果为 UNKNOWN（原因码 `INVALID_JUDGE_OUTPUT`），不会截断 |
| PASS 最低通过比例 | 0–1，默认 0.8；只统计 PASS/FAIL，UNKNOWN 不计入分母 |
| UNKNOWN 策略 | `BLOCK`（默认，整体 UNKNOWN）/ `FAIL`（按失败）/ `PASS`（有可判定 PASS/FAIL 时忽略 UNKNOWN；全部 UNKNOWN 仍为 UNKNOWN） |
| 证据要求 | 勾选后，PASS claim 必须有至少一个非空 `evidenceRefs`；否则该 claim 被改为 UNKNOWN |

**聚合规则（固定，不可配置）：**

1. 任意 claim 为 FAIL → 整体 FAIL；
2. 存在 UNKNOWN 且策略 `BLOCK` → 整体 UNKNOWN；
3. 存在 UNKNOWN 且策略 `FAIL` → 整体 FAIL；
4. 全部 UNKNOWN → UNKNOWN；
5. 否则 `passRatio = PASS / (PASS + FAIL)`；达到阈值 → PASS，否则 FAIL。

结果字段：

- `label`：PASS / FAIL / UNKNOWN；
- `score`：展示用，等于 `passRatio × 10`；
- `confidence`：`(PASS+FAIL) / 全部 claim`；
- `claims`：逐条 `id / text / verdict / evidenceRefs / reason`；
- `claimAggregate`：`passCount / failCount / unknownCount / passRatio / label / reasonCode`。

**为什么会 UNKNOWN：**

| 原因码 | 含义 |
| --- | --- |
| `CLAIM_UNKNOWN` | 有 claim 无法确认，且策略是 BLOCK |
| `ALL_CLAIMS_UNKNOWN` | 所有 claim 都是 UNKNOWN |
| `INVALID_JUDGE_OUTPUT` | 输出非法：缺 claims 数组、claim 缺 id/text、id 重复、verdict 非法、claim 数超过 maxClaims |
| `JUDGE_UNAVAILABLE` | LLM 不可用或未配置 |
| `RETRIEVAL_CONTEXT_MISSING` 等 | requiredFields 要求的证据缺失，未发起 Judge 调用 |

### 6.4 Judge 模板

**内置模板（只读）：**

| key | 用途 | 输出 |
| --- | --- | --- |
| `platform/holistic-v1` | 整体 0–10 分 | `{"score":0-10,"confidence":0-1,"reason":"..."}` |
| `platform/claim-v1` | 逐条 claim | `{"claims":[{"id","text","verdict","evidenceRefs","reason"}],"reason":"..."}` |

`judgeTemplateKey` 留空等价于 `platform/holistic-v1`。key 必须以 `platform/` 或 `workspace/` 开头；写错会在保存评估器时直接报错，不会静默回退。

**workspace 自定义模板（当前通过 API 管理）：**

```bash
curl -X POST "$HOST/api/v1/judge-templates" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "templateKey":"workspace/refund-claim",
    "name":"退款 claim 评审",
    "systemPrompt":"You are a strict evidence-based evaluator. ... {subject} ... {criteria}",
    "userPrompt":"",
    "outputSchema":"{\"claims\":\"array\"}"
  }'
```

- `templateKey` 必须匹配 `workspace/[A-Za-z0-9._-]{1,80}`。
- 相同内容（systemPrompt + userPrompt + outputSchema 指纹）重复创建返回现有版本。
- 内容变化 → 追加新版本，不覆盖旧版本。
- 运行时当前只渲染 `systemPrompt`（替换 `{subject}`、`{criteria}`）；`userPrompt` / `outputSchema` 进入版本指纹和审计，修改它们也会产生新版本。
- UI 的「Judge 模板版本」可留空：留空时，创建/保存会把当时的已发布最新版本固定进 spec；之后发布新模板不会改变已有评估器。要升级模板，显式填写新版本并保存，评估器会生成新版本。
- 指定版本需保证该 key 下存在该版本；不存在时保存评估器直接报错，不会回退到最新版本。
- 平台模板不可编辑；workspace 模板可 `POST /api/v1/judge-templates/{id}/deprecate` 停用。已固定的旧版本仍可被旧评估器/旧 run 解析。

**冻结与追溯：**

- 评估器创建/编辑时把 `judgeTemplateVersion` 固定进 spec；评估器版本记录 `templateKey / templateVersion / templateHash`；
- run 快照同样固定模板版本与 hash；执行时按快照版本解析，不会在运行时重新选“最新模板”；
- 任务账本也带模板字段，便于从结果回溯到当时的评审指令；
- 新 run 的 Gate 会检查 LLM_JUDGE 是否带模板 hash（检查项 `JUDGE_TEMPLATES`）；历史 run 没有该字段时保持兼容，不会因为升级把旧 Gate 全部打成 BLOCKED；
- 模板发布新版本不会改写旧 run，也不会自动让旧 Gate 失效。旧 run 始终按创建时固定的模板版本复核。

### 6.5 TRAJECTORY 轨迹评测

类型选 `TRAJECTORY`，填写 JSON：

```json
{
  "trajectoryMode": "SUBSET",
  "expectedTools": [
    {"name": "query_order", "required": true},
    {"name": "send_sms", "required": false}
  ],
  "forbiddenTools": ["delete_order"],
  "maxSteps": 12,
  "allowCycles": false,
  "argumentRules": [
    {"tool": "query_order", "path": "orderId", "required": true, "regex": "^[0-9]+$"},
    {"tool": "query_order", "path": "payload.channel", "equals": "app"}
  ]
}
```

| 字段 | 约束 | 行为 |
| --- | --- | --- |
| `trajectoryMode` | `SUBSET` / `ORDER` / `EXACT` | SUBSET：必需工具都出现即可；ORDER：必需工具按顺序出现（允许中间有其他工具）；EXACT：实际工具序列必须完全等于“required=true 的期望工具”序列 |
| `expectedTools` | 最多 50 条；name 合法工具名 | 评估器里非空时覆盖样本的 `expectedTools`；`required=false` 只作展示，不进入必需集合 |
| `forbiddenTools` | 最多 50 个 | 实际出现任意一个 → FAIL |
| `maxSteps` | 1–1000 | 实际工具调用数 > maxSteps → FAIL |
| `allowCycles` | 默认 false | false 时检测连续重复块（长度 2–8，重复 ≥2 次）→ FAIL |
| `argumentRules` | 最多 50 条；tool/path 必填 | 取该工具**第一次**调用；`path` 是 `inputParameters` 的点路径（如 `payload.id`）；`required` 默认 true；`regex` 用 find 语义；`equals` 按字符串比较 |

**证据与结果：**

- 实际工具序列来自 TRACE 的 `tool` span；REPLAY 不产生工具轨迹。
- 没有 `toolsCalled` → `UNKNOWN`，原因码 `TOOLS_CALLED_MISSING`，不判 FAIL。
- 参数是 JSON 字符串时会先尝试解析；解析失败或路径不存在 → 规则失败（required=true）或跳过（required=false）。
- 参数规则引用的工具一次都没调用 → 该规则 FAIL。
- 结果 `label` 为 `PASS` / `FAIL` / `UNKNOWN`，原因码 `TRAJECTORY_PASS` / `TRAJECTORY_FAIL` / `TOOLS_CALLED_MISSING`，`criteria` 中逐条列出期望工具、禁用工具、步数、循环、参数规则的通过情况。

**JSON 填写注意：**

- 必须是 JSON 对象，不能是数组或字符串；
- 不要在 spec 里写 `"type"`；后端会按评估器类型固定为 TRAJECTORY；
- 保存时会校验 mode、工具名、步数、正则；非法规则直接拒绝，不会等到运行时才报错。

### 6.6 评估器 API 示例

```bash
# RAG faithfulness（CLAIMS + 平台模板 + 证据字段）
curl -X POST "$HOST/api/v1/evaluators" \
  -H "Authorization: Bearer $JWT" \
  -H "Content-Type: application/json" \
  -d '{
    "evaluatorKey":"rag-faithfulness-v1",
    "type":"LLM_JUDGE",
    "spec":{
      "rubric":"判断候选答案中的每条 claim 是否被检索上下文支撑。",
      "judgeMode":"CLAIMS",
      "judgeTemplateKey":"platform/claim-v1",
      "requiredFields":["candidate_output","retrieval_context"],
      "claimsPolicy":{"maxClaims":20,"requireEvidence":true,
                      "unknownPolicy":"BLOCK","minPassRatio":0.8},
      "temperature":0,
      "referenceRequired":false,
      "preset":"rag-faithfulness"
    }
  }'

# 查看可用预设
curl "$HOST/api/v1/evaluators/presets" -H "Authorization: Bearer $JWT"
```

### 6.7 SDK 评测器（自定义 Python scorer）

需要表达"表达式/正则/Judge 都写不出来的判定逻辑"（金额核算、领域校验、内部 SDK 调用）时，用 SDK 评测器：**服务端只声明输入契约，判定代码在你的环境执行，结果经开放 API 回写任务账本**。服务端不执行任何用户代码。

**创建评估器**：类型选 `SDK`，填「输入契约」（requiredFields，如 `input, candidate_output, expected_output`）与可选 `scorerRef`（ scorer 代码的人类可读引用，仅留痕）。

**写 scorer**（`my_scorers.py`）：

```python
import emonlens

@emonlens.scorer("amount-check")
def check(sample: emonlens.SdkSample):
    # sample.input / sample.candidate_output / sample.expected_output 等快捷属性
    # sample.missing_fields() 返回契约声明但载荷缺失的字段（建议此时返回 UNKNOWN）
    if sample.missing_fields():
        return {"verdict": "UNKNOWN", "reasonCode": "EVIDENCE_MISSING"}
    return sample.expected_output in (sample.candidate_output or "")
```

返回值按宽容度归一：`bool`（True=PASS/False=FAIL）、0–1 数值（score）、
`dict`（`verdict/score/reason/reasonCode`）或 `emonlens.ScorerResult`。
scorer 抛异常按 `ERROR` 记账（reasonCode `SCORER_RAISED`），不会让整批回写失败。

**运行**（run 终态后自动拉取任务、执行、回写）：

```bash
emonlens eval run --dataset ds-... --evaluator ev-... --wait   --sdk-runner ./my_scorers.py --assert

# 对已有 run 补跑 / 重放 scorer（幂等重复返回 DUPLICATE，不重复记账）
emonlens eval sdk --run run-... --runner ./my_scorers.py
```

```python
summary = emonlens.run_sdk_scorers(run_id, "./my_scorers.py")
summary["applied"]   # 成功回写数
summary["conflicts"] # 与已落账本结论冲突数（终态不可覆盖）
```

**语义与口径**：

- worker 执行样本时跳过 SDK 评估器；其样本级结论先按 `UNKNOWN`（reasonCode `AWAITING_SDK_RESULTS`）占位参与聚合，**计数守恒在任意时刻成立**；
- 结果回写后按任务账本重聚合该样本并刷新 run 计数；迟到的重复回写返回 `DUPLICATE`，与已落结论不一致返回 `CONFLICT`；
- Token 需要 `eval:read`（拉任务）+ `eval:run`（回写）；样本正文还要求 `eval:content`，没有它时载荷只含 hash 与状态；
- run 终态后超过 `emonlens.eval.sdk-result-grace-hours`（默认 24h）仍未回写的任务按 `SKIPPED`（`SDK_RESULT_NOT_RECEIVED`）收敛；
- Gate 新增检查项 `SDK_EVALUATORS`：声明了 SDK 评估器但结果未收齐 → `BLOCKED`，CI 不得放行。

---

## 7. 运行、结果与成本

### 7.1 TRACE / REPLAY 与证据

| 模式 | 会做什么 | 适用 |
| --- | --- | --- |
| `TRACE` | 读取已采集 trace，投影工具调用/检索证据，按评估器判定；不调用生成模型 | Agent 轨迹、工具参数、线上真实检索 |
| `REPLAY` | 用样本 input 重新生成候选，再判定；会产生外部调用费用 | 回归测试、RAG 答案质量、prompt 对比 |

- TRAJECTORY 和依赖 `tools_called` / `trace_digest` 的预设只能在 TRACE 下工作。
- RAG 指标在 REPLAY 下也能工作，但需要样本显式提供 `retrievalContext`；否则 requiredFields 会让结果变 UNKNOWN。
- 缺证据的原因码：`INPUT_MISSING`、`REFERENCE_MISSING`、`RETRIEVAL_CONTEXT_MISSING`、`EXPECTED_TOOLS_MISSING`、`TOOLS_CALLED_MISSING`、`TURNS_MISSING`、`TRACE_DIGEST_MISSING`、`CANDIDATE_MISSING`。

### 7.2 看结果

数据集详情 → 运行报告：

- 逐样本结论：PASS / FAIL / UNKNOWN / ERROR；
- 评估器结果标签：CLAIMS 模式会在评估器标签后追加 `c1: PASS`、`c2: UNKNOWN` 等 claim 标签；悬停可看 reason 和 evidenceRefs；
- Judge 模板版本/hash、`judgeMode`、`preset` 在结果详情和任务账本中可见；
- UNKNOWN 不是“通过”，也不会被统计成 PASS。

### 7.3 成本报告

```bash
curl "$HOST/api/v1/datasets/$DATASET_ID/runs/$RUN_ID/cost" -H "Authorization: Bearer $JWT"
```

返回关键字段：

| 字段 | 含义 |
| --- | --- |
| `promptTokens` / `completionTokens` / `totalTokens` | 整个 run 的 token |
| `byPhase.generation` / `byPhase.judge` | 生成阶段 / Judge 阶段的调用、token、费用 |
| `byModel[]` | 按模型拆分的 token 与费用 |
| `byEvaluator[]` | 按评估器拆分的 Judge token 与费用 |
| `cache.hits` | 缓存命中次数 |
| `cache.judgeCalls` | 实际发起的 Judge 调用次数（命中不算） |
| `cache.judgeEvaluations` | `hits + judgeCalls`，即参与 Judge 评估的总次数 |
| `cache.hitRate` | `hits / judgeEvaluations`，百分比；没有 Judge 评估时为 null |
| `unpricedModels` | 没有配置单价的模型；不会伪造总额 |
| `priced` / `completePricing` | 是否有模型已计价 / 是否全部模型都已计价 |
| `total` / `currency` | 已计价部分的总额与币种 |

成本页的模型价格在「模型价格」页面维护；缺少价格时只展示 token 和 `PRICE_MISSING`，不计算假费用。

### 7.4 缓存

开启 `emonlens.eval.cache-enabled=true` 后，仅对 LLM_JUDGE 生效：

- key 绑定：样本内容（caseHash、candidate、traceDigest、retrieval/tools/turns/expectations）、evaluator spec、模板 hash、judge model、temperature、judgeMode、Provider 指纹（协议 + Base URL）。
- `temperature>0` 默认不缓存；设置 `cache-allow-stochastic=true` 才允许。
- 缓存的是结果，不是“跳过评估”：命中结果仍带 `cacheHit=true`，`judgeCalled=false`，token 为 0，成本为 0。
- `ERROR`、`EVAL_UNAVAILABLE`、`N/A` 不缓存；`JUDGE_UNAVAILABLE`（LLM 暂时不可用）和 `INVALID_JUDGE_OUTPUT`（非法输出）也不缓存，避免恢复后仍命中坏结果。
- 由证据缺失产生的确定性 `UNKNOWN`（如 `RETRIEVAL_CONTEXT_MISSING`、`TOOLS_CALLED_MISSING`）可以缓存；补证据后样本内容变化会生成新 key。
- TTL 默认 168 小时；每天 03:10 清理过期行（`cache-cleanup-cron`）。
- 任何语义字段变化都会得到新 key，不会“悄悄复用”旧结论。修改评估器、模板、模型、温度、模式或样本内容都会自然失效。

### 7.5 重试、续跑与 attempt

- Judge/生成调用：429、500/502/503/504、网络/超时自动重试；401/403/404、配额/计费/上下文超限不重试（重试只会放大成本）。
- `POST /api/v1/datasets/{id}/runs/{runId}/retry`：只针对 `PARTIAL_FAILED` 中 ERROR 的样本，创建新 attempt；需要 `Idempotency-Key`。
- `POST /api/v1/datasets/{id}/runs/{runId}/resume`：从 `INTERRUPTED` 续跑，保留已执行证据，只补未执行样本；需要 `Idempotency-Key`。
- `GET /api/v1/datasets/{id}/runs/{runId}/attempts`：查看 attempt 链的计数与预算，不返回正文。
- `POST /api/v1/datasets/{id}/runs/{runId}/cancel`：取消活动 run，重复取消幂等，终态不回退。

### 7.6 Judge 校准与 Gate

含 LLM_JUDGE 的 run：

- Gate 的 `JUDGE_CALIBRATION` 检查要求所有可校准的 PASS/FAIL Judge 结果都完成人工盲审；
- 至少 10 条已审（`MIN_REVIEWED=10`）；
- 一致率不低于 80%（`MIN_AGREEMENT=80`）；
- DOUBLE_BLIND 模式下每条需要 primary + second；不一致时还需要 adjudicator；
- 校准协议不能混用（protocols 必须一致）；
- 没有 PASS/FAIL 的 Judge 结果（例如全部 UNKNOWN）时，检查为 BLOCKED。

因此：**Judge 说 PASS 不等于 Gate PASS。** 先看运行报告「5 · Judge 校准」，完成盲审后再做 Gate。历史 Gate 是否仍可用，用 `GET /api/v1/open/quality-gates/{gateId}/verification` 的 `usable` + `decision` 判断，不要只读旧 `decision=PASS`。

---

## 8. SDK 与 CLI

### 8.1 安装

SDK 在仓库 `emonlens-sdk-python/`，零第三方依赖，Python ≥ 3.9：

```bash
pip install -e emonlens-sdk-python
# 或安装构建好的 wheel
pip install emonlens-sdk-python/dist/emonlens-0.1.0-py3-none-any.whl
```

### 8.2 环境变量

| 变量 | 用途 |
| --- | --- |
| `EMONLENS_HOST` | 服务地址，如 `http://localhost:8300` |
| `EMONLENS_API_TOKEN` | 开放 API Token（`X-Api-Token`），需 `eval:run` / `eval:read` / `score:read` |

`EMONLENS_INGEST_KEY` 是数据面认证，不能用于评测 API；两者不要混用。

### 8.3 Python API

```python
import emonlens

result = emonlens.evaluate(
    dataset_id="ds-...",
    suite_id=None,                 # 与 evaluator_ids 二选一
    evaluator_ids=["eval-1"],      # 也可传 suite_id
    mode="TRACE",                  # TRACE / REPLAY
    revision_id=None,              # 可选：固定 revision
    candidate_label="prompt-v3",   # 可选：A/B 展示
    max_external_calls=50,         # 可选：外部调用预算
    max_tokens=200000,             # 可选：token 预算
    review_mode="SINGLE_BLIND",    # 可选：人工复核模式
    wait=True,
    timeout=900,
    poll_interval=3,
)

run = result["run"]
gate = result.get("gate") or {}
print(run["id"], run["status"], gate.get("decision"))

# CI 断言：默认 FAIL/BLOCKED/UNKNOWN 都失败
emonlens.assert_test(result)

# 自定义失败集合（例如允许 UNKNOWN 仅告警）
emonlens.assert_test(result, fail_on=["FAIL", "BLOCKED"])

# 校验历史 Gate；CI 必须消费 usable + decision
verification = emonlens.gate_check("gate-id")
assert verification["usable"] and verification["decision"] == "PASS"

# SDK 评测器：注册 scorer 并回写结果（详见 §6.7）
@emonlens.scorer("amount-check")
def amount_check(sample: emonlens.SdkSample):
    return sample.expected_output in (sample.candidate_output or "")

summary = emonlens.run_sdk_scorers(run_id, {"amount-check": amount_check})
assert summary["conflicts"] == 0
```

`evaluate` 自动生成 `Idempotency-Key`；在 CI 重试同一提交时，建议显式传 `idempotency_key`，避免重复创建 run。

### 8.4 CLI

```bash
export EMONLENS_HOST="http://localhost:8300"
export EMONLENS_API_TOKEN="eak-..."

# 提交并等待；默认按 run/gate 结果返回退出码
emonlens eval run \
  --dataset ds-... \
  --suite suite-... \
  --mode TRACE \
  --candidate-label prompt-v3 \
  --wait \
  --timeout 900 \
  --poll-interval 3

# 只看提交结果，不等待
emonlens eval run --dataset ds-... --evaluator eval-1 --json

# 断言模式：FAIL/BLOCKED/UNKNOWN 非零退出
emonlens eval run --dataset ds-... --evaluator eval-1 --wait --assert

# SDK 评测器：run 终态后执行本地 scorer 并回写（自动启用 --wait）
emonlens eval run --dataset ds-... --evaluator ev-... --wait --sdk-runner ./my_scorers.py

# 对已有 run 执行 SDK scorer（幂等；可重放）
emonlens eval sdk --run run-... --runner ./my_scorers.py

# 校验历史 Gate
emonlens gate check --gate-id gate-... --json
```

CLI 支持的 `eval run` 参数：`--dataset`、`--suite`、`--evaluator`（可重复）、`--mode`、`--revision`、`--candidate-label`、`--wait`、`--timeout`、`--poll-interval`、`--json`、`--assert`、`--sdk-runner`。`--system-prompt`、`--model`、预算、`reviewMode` 等参数目前只在 Python API/开放 API 中可用。

### 8.5 退出码

| 退出码 | 含义 |
| --- | --- |
| `0` | run 完成且没有失败/未知/错误；`--assert` 下断言通过 |
| `2` | run `FAILED` / `PARTIAL_FAILED` / `INTERRUPTED` / `CANCELLED`，或 `errorCount > 0`，或 Gate `FAIL` |
| `3` | `unknownCount > 0`、Gate `BLOCKED`、`--assert` 断言失败，或历史 Gate 不可用 |
| `4` | 配置/HTTP/API 错误（缺 host、缺 token、服务端非 0 code） |
| `5` | 等待超时 |

CI 示例：

```yaml
- name: EmonLens gate
  env:
    EMONLENS_HOST: ${{ secrets.EMONLENS_HOST }}
    EMONLENS_API_TOKEN: ${{ secrets.EMONLENS_API_TOKEN }}
  run: |
    emonlens eval run --dataset "$DATASET_ID" --suite "$SUITE_ID" \
      --mode TRACE --wait --timeout 900 --assert
    emonlens gate check --gate-id "$GATE_ID"
```

日志只记录 runId/gateId 和摘要；不要把样本正文、Token 或个人信息打进 CI 日志。

---

## 9. 升级、兼容与回滚

### 9.1 升级顺序

1. 备份 `et_dataset_item` / `et_dataset_revision_item` / `et_evaluator_version` / `et_eval_task`。
2. 按 §2.4 顺序执行五个迁移脚本。
3. 发布新版本服务端/前端。
4. 按需打开 `emonlens.eval.cache-enabled`、`emonlens.dataset-synthesis.enabled`。
5. 用 dry-run 导入验证样本契约；用一个小数据集跑 CLAIMS/TRAJECTORY 冒烟。

### 9.2 兼容性

- 旧样本（v1）不需要迁移，继续按原逻辑读取和运行。
- 旧 run、旧 Gate 不重写；新字段只对新建/新运行生效。
- 评估器/套件/版本的旧数据保持可读；新增的模板字段为空时按 `platform/holistic-v1` 处理。
- 导出/导入字段只增不减；旧 CSV 仍可导入。
- 缓存默认关闭；合成默认关闭；不打开开关不会产生新的外部调用。

### 9.3 回滚

- 先关闭 `emonlens.eval.cache-enabled` 和 `emonlens.dataset-synthesis.enabled`，停止新的 Judge 缓存/合成任务。
- 回滚应用到上一版本；新增列/表可以保留，旧代码不会读取它们。
- 已产生的合成候选、缓存行、新样本可以保留，不影响旧版本运行；如要清理，按 workspace/dataset 定向删除，不要整表清空。
- 已完成的 run/Gate 是审计记录，回滚不回写历史结论。

---

## 10. 验收清单

部署后用一个小数据集完成以下检查：

- [ ] 添加 v2 样本，页面「契约」列显示 `v2 · 检索/工具/多轮/期望`。
- [ ] JSONL 导入 dry-run 显示 `wouldCreate > 0`；实际导入后样本出现。
- [ ] 导出 JSONL/CSV；CSV 直接回导不报“缺 input”。
- [ ] 开启合成开关，创建 SCRATCH 任务；状态从 PENDING → COMPLETED，候选可批准/拒绝。
- [ ] 创建 LLM_JUDGE + CLAIMS 评估器，跑一条 REPLAY；报告出现 claim 标签；超 maxClaims 的模拟输出返回 UNKNOWN。
- [ ] 创建 TRAJECTORY 评估器，用一条含 tool span 的 TRACE 运行；无工具样本返回 `TOOLS_CALLED_MISSING`。
- [ ] 开启缓存后重跑同一 REPLAY：第二次 `cacheHit=true`，成本报告 `cache.hits=1`、`hitRate=100`（单次评估命中时）。
- [ ] 用 CLI `--wait --assert` 跑通；历史 Gate `gate check` 退出码符合预期。
- [ ] 含 Judge 的 run 在未完成盲审时 Gate 为 BLOCKED；完成至少 10 条盲审且一致率 ≥80% 后再生成 Gate。
- [ ] 矩阵：预估给出格数/调用数/成本；未确认 `confirmEstimate` 时受理被拒；跑完能导出 CSV。
- [ ] 矩阵 Diff：输出完全一致的列给出 `SAME` + `TIE`（不产生 judge 调用）；偏好与评估冲突时给出 `TRADEOFF`。
- [ ] 绑定了 `production` prompt 的 run 在 production 换版后，`gate check` 返回不可用且原因含 `PROMPT_PRODUCTION_MOVED`。
- [ ] `GET /api/v1/prompts/{name}/runs` 能回答「这条 prompt 版本被哪些 run 评过、Gate 结论是什么」。
- [ ] 同一数据集打两次 `baseline` 标签后，按标签只有一条 run 命中。
- [ ] CI：`emonlens eval run --report-json` + `emonlens ci comment --dry-run` 能在本地渲染出带结论与检查项的评论。

---

## 11. 故障排查索引

| 症状 | 优先检查 |
| --- | --- |
| 结果大量 UNKNOWN | 先看原因码：缺证据（`*_MISSING`）、LLM 不可用（`JUDGE_UNAVAILABLE`）、输出非法（`INVALID_JUDGE_OUTPUT`）、claim 无法确认（`CLAIM_UNKNOWN`） |
| RAG 指标全部 UNKNOWN | 样本是否有 `retrieval_context`；REPLAY 下是否显式提供；评估器 requiredFields 是否要求了 `expected_output` 但样本没有 |
| Agent 轨迹指标全部 UNKNOWN | 是否用了 TRACE；trace 是否有 `tool` span；是否误用 REPLAY |
| TRAJECTORY 报“参数规则引用工具未调用” | 期望工具名与实际 `toolName`/`spanName` 不一致；先看 run 详情的 toolsCalled |
| CLAIMS 全部 UNKNOWN | `requireEvidence=true` 但模型没给 evidenceRefs；`unknownPolicy=BLOCK`；或输出超过 maxClaims/格式非法 |
| Judge 结果全 FAIL | 检查 rubric 是否明确、参考是否缺失、模板是否选错；先用单条样本试跑 |
| Gate 一直 BLOCKED | 看 `checks`：覆盖率、UNKNOWN/ERROR 预算、Judge 校准、模板 hash、候选指纹 |
| 缓存不命中 | 开关是否开启；temperature 是否 >0；模型/Base URL/协议是否变化；评估器 spec 或模板是否更新；TTL 是否过期 |
| 合成任务 FAILED | LLM 配置、来源是否为空、`dataset-synthesis.enabled`、worker 日志 |
| 导入失败（业务码 40000） | 先 dry-run；看 `errors[].row/message`；检查 v2 字段是否显式写成 schemaVersion=1；单条是否超过快照上限 |
| 导出没有正文 | 账号是否缺 `TRACE_CONTENT`；这是权限裁剪，不是数据丢失 |
| 页面状态不刷新 | 后台标签页节流/IAB 环境；重新打开页面或调用 API；合成轮询关闭抽屉即停止 |

更细的准入流程、Gate 政策、质量案例和领域包操作，见《EmonLens评测与准入操作指南》。

---

## 12. 界面枚举中文化与多语言预留

- 界面上的状态、严重级、类型、决策等枚举统一显示中文（如 OPEN→待处理、HIGH→高、PASS→通过、REPLAY→重放）；技术代码保留在悬停提示里，便于与 API/日志对账。
- 枚举字典集中在 `emonlens-ui/src/common/i18n.js`，已按 `zh / en` 双语结构组织，并提供 `setLocale()`；后续接入“系统设置/用户偏好”语言切换时，只需要切换 locale，不需要改页面。
- 列表卡片统一固定高度：数据增多时在卡片内滚动，分页/表头保持可见（触发历史、异常发现、样本、实验、审计记录等均已按此处理）。

---

## 13. Playground 矩阵实验与 Diff（0.2.0）

调 prompt 时最常见的问题是「改完之后到底变好了没有」，靠肉眼比输出不可靠。矩阵把「数据集样本 × 列（prompt 版本 × 模型 × 参数）」一次跑成网格，再按列给出逐样本 Diff。

### 13.1 页面上怎么用

入口：构建 →「调试与提示词」→「矩阵实验」（`/matrix`）。

1. 选数据集与样本数（默认 10，上限 20；可用 `emonlens.playground.matrix.max-samples` 调整）。
2. 可选挂 1–5 个评估器（EXPRESSION / REGEX / LLM_JUDGE / TRAJECTORY）。挂上之后每个格子会同时给出 `PASS/FAIL/UNKNOWN/ERROR` 与平均分。
3. 添加列：每列可以是「提示词库里的某个版本」或直接写的模板；指定模型与温度。
4. 点「预估成本」→ 看格数、预计外部调用次数、预计 token 与成本（按「模型价格」计价；没配价目的模型会显式列出，不伪造总额）。
5. 点「运行矩阵」：受理后后台按并发度执行，页面每 1.5 秒刷新进度。
6. 跑完可「导出 CSV」；选 base 列与对比列点「对比」得到 Diff；需要成为准入证据时点「升格为正式实验」。

### 13.2 API

```bash
# 1) 预估（只算不落库）
curl -s -X POST "$HOST/api/v1/playground/matrices/estimate" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' -d '{
    "datasetId": "ds-1",
    "sampleLimit": 5,
    "evaluatorIds": ["ev-1"],
    "columns": [
      {"key":"c1","label":"v2-gpt4o","promptName":"support-assistant","promptVersion":2,"model":"gpt-4o","temperature":0.2},
      {"key":"c2","label":"v3-gpt4o","promptName":"support-assistant","promptVersion":3,"model":"gpt-4o","temperature":0.2}
    ]}'

# 2) 受理（必须确认成本；同 Idempotency-Key 同请求返回同一矩阵）
curl -s -X POST "$HOST/api/v1/playground/matrices" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: matrix-2026-11-03-1" \
  -H 'Content-Type: application/json' -d '{ ...同上..., "confirmEstimate": true }'

# 3) 看进度与结果（cells 内含 output / latencyMs / tokens / cost / scores）
curl -s "$HOST/api/v1/playground/matrices/{matrixId}" -H "Authorization: Bearer $JWT"

# 4) 导出网格 CSV
curl -s "$HOST/api/v1/playground/matrices/{matrixId}/export.csv" -H "Authorization: Bearer $JWT" -o grid.csv

# 5) Diff（base 列 vs 目标列）
curl -s -X POST "$HOST/api/v1/playground/matrices/{matrixId}/diff" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"baseColumn":"c1","targetColumn":"c2"}'
```

### 13.3 结论怎么读

- `deterministic`：`SAME`（输出 hash 或归一化文本一致）/ `CHANGED` / `MISSING`（有一列没输出）。
- `preference`：把目标列当候选 B —— `IMPROVEMENT`（B 更好）/ `REGRESSION`（A 更好）/ `TIE` / `UNKNOWN`（方向冲突或调用失败，不硬判）。
- `TRADEOFF`：语义偏好与评估结论冲突时出现，原因码是 `PREFERENCE_UP_SCORE_DOWN`（judge 说更好但评估从 PASS 变 FAIL）或反向。看到它说明「有得有失」，不要当成纯改进。
- 每个 `diff.items[]` 都能追溯到样本，`calls_json` 保留 judge 的实际呈现顺序与模型，供事后复核。

### 13.4 边界与限制

- 矩阵**不是**准入证据：没有候选指纹、不进 Gate、不进信任分。要放行必须「升格为正式实验」（见 §14.2）。
- 默认硬上限：20 样本 / 8 列 / 200 格 / 1000 次外部调用；超限直接 400 并说明原因。
- 不支持 SDK 评估器（需要外部回写），会在受理时拒绝。
- 单格失败写 `ERROR` 且矩阵落 `PARTIAL`，不会中断整批；实例重启时残留 `RUNNING` 会标 `FAILED`（不假装还在跑）。

---

## 14. Prompt 版本 × 实验与 CI 门禁（0.2.0）

### 14.1 让 run 绑定 prompt 版本

提交 run 时带上 `promptName`（可选 `promptVersion` / `promptLabel`）：

```bash
curl -s -X POST "$HOST/api/v1/open/eval-runs" \
  -H "X-Api-Token: $TOKEN" -H "Idempotency-Key: run-2026-11-03-1" \
  -H 'Content-Type: application/json' -d '{
    "datasetId": "ds-1", "evaluatorIds": ["ev-1"], "mode": "REPLAY",
    "promptName": "support-assistant", "promptLabel": "production",
    "runLabel": "candidate", "commitSha": "'"$GITHUB_SHA"'"
  }'
```

- 只给 `promptName` 时按 `promptLabel` 解析（缺省 `production`）；给 `promptVersion` 则以版本为准。
- 解析出的 `(name, version, hash)` 进候选指纹：换版本 → 指纹变化 → 两条 run 自然不可比。
- 每次解析都留痕（`et_prompt_resolution`：谁、什么时候、按哪个 label、取到哪个版本）。

### 14.2 从矩阵升格为正式实验

```bash
curl -s -X POST "$HOST/api/v1/playground/matrices/{matrixId}/columns/c2/promote" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"runLabel":"candidate","commitSha":"<你的 commit sha>"}'
```

升格沿用该列冻结的 prompt 版本与生成参数，创建一条 dataset run；幂等键由「矩阵 + 列」推导，重复点击返回同一条 run，不会重复计费。

### 14.3 Gate 会因 prompt 改版而失效

- 新增检查项 `PROMPT_BINDING`：绑定了 prompt 的 run 必须能给出 `version + hash`；若绑定的是 `production` 标签，**当前** production 指针必须仍指向同一版本。
- verification 的失效原因新增 `PROMPT_PRODUCTION_MOVED:name:v旧->v新` 与 `PROMPT_PRODUCTION_MISSING:name`：prompt 改版（把 production 指向新版本）后，旧 Gate 立即 `usable=false`，CI 必须重新跑评测。
- 未绑定 prompt 的历史 run 不受影响（不新增检查项，保持向后兼容）。

### 14.4 从 prompt 版本反查评估证据

`GET /api/v1/prompts/{name}/runs` 返回每条绑定过该 prompt 的 run：版本、run 标签、提交号、通过/失败计数、最新 Gate 决策与有效期。页面上在「提示词 → 版本抽屉」里以「评估证据」表展示。

### 14.5 GitHub Action 与 PR 评论

根目录 `action.yml` 是官方 composite Action，示例工作流见 `docs/examples/github-action-eval.yml`。

```yaml
permissions:
  contents: read
  pull-requests: write        # 评论需要写权限

steps:
  - uses: actions/checkout@v4
  - name: EmonLens eval gate
    id: emonlens
    uses: your-org/emonlens@v0.2.0
    with:
      host: ${{ vars.EMONLENS_HOST }}
      api-token: ${{ secrets.EMONLENS_API_TOKEN }}
      dataset: ${{ vars.EMONLENS_DATASET_ID }}
      evaluators: ${{ vars.EMONLENS_EVALUATOR_IDS }}
      mode: REPLAY
      prompt-name: support-assistant
      prompt-label: production
      baseline-label: baseline
      comment: "true"
```

- Action 内部：`emonlens eval run --wait --assert --report-json` → 渲染评论（同一条 PR 只保留一条，按隐藏 marker 幂等更新）→ 用 CLI 退出码决定 job 结果（0 PASS / 2 FAIL / 3 BLOCKED / 4 错误 / 5 超时）。
- 基线的来源：给某条 run 打 `baseline` 标签（`POST /api/v1/datasets/{id}/runs/{runId}/label`），同一数据集内该标签唯一；Action 会解析最近一条基线并把 `PASS→FAIL` / `FAIL→PASS` 写进评论。
- 评论只包含计数、Gate 检查项与标识（prompt 版本 / commit），**不含样本正文**；Gate verification 不可用时评论会明确写「不得作为放行依据」。

本地不装 GitHub 也能看渲染效果：

```bash
emonlens eval run --dataset ds-1 --suite suite-1 --wait --assert \
  --prompt-name support-assistant --baseline-label baseline \
  --report-json report.json
emonlens ci comment --report report.json --dry-run
```

---

## 15. 评估规则 v2：scope / filter / 回溯（R2-1）

在线评估从「一套全局采样率」升级为**规则对象**。只要某个工作空间里创建并启用了规则，自动评估就按规则走；
没有规则的空间行为完全不变（向后兼容），删掉/禁用全部规则即回退到全局采样。

### 15.1 一条规则是什么

| 字段 | 含义 |
| --- | --- |
| `ruleKey` | 规则标识（字母数字与 `_.:-`，最长 100；`summary`/`rewinds` 是保留字） |
| `scope` | `TRACE`（trace 完成后评估）或 `SESSION`（会话空闲后聚合评估） |
| `filters` | 全部满足才命中：`agentKeys` / `agentVersions` / `environments` / `promptNames` / `minDurationMs` / `maxDurationMs` / `onlyErrors` |
| `evaluatorIds` | 1–5 个评估器 |
| `sampleRate` | 0–1；**错误 trace 在 `errorForced` 下无视采样率** |
| `maxPerHour` | 每小时评估次数上限；0=不限（按命中账本计数，重启不清零） |
| `sessionIdleMinutes` | SESSION scope：空闲多少分钟后评估（默认 5） |
| `priority` | 越小越先匹配；多规则命中同一批评估器时用它决定谁"真正执行" |

```bash
# 1) 建规则：只评 support-agent 的慢请求，采样 20%，错误全量，每小时最多 200 次
curl -s -X POST "$HOST/api/v1/eval-rules" -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{
    "ruleKey": "support-slow",
    "name": "客服慢请求",
    "scope": "TRACE",
    "filters": {"agentKeys": ["support-agent"], "minDurationMs": 3000},
    "evaluatorIds": ["ev-1"],
    "sampleRate": 0.2,
    "errorForced": true,
    "maxPerHour": 200
  }'

# 2) 会话级规则：会话空闲 10 分钟后对多轮聚合视图评估一次
curl -s -X POST "$HOST/api/v1/eval-rules" -H "Authorization: Bearer $JWT" \
  -H 'Content-Type: application/json' -d '{
    "ruleKey": "session-quality", "scope": "SESSION",
    "evaluatorIds": ["ev-session"], "sessionIdleMinutes": 10
  }'

# 3) 汇总（按 scope 与规则分区，另带命中决策计数）
curl -s "$HOST/api/v1/eval-rules/summary?windowHours=24" -H "Authorization: Bearer $JWT"
```

### 15.2 幂等与去重（为什么不会重复花钱）

- 命中账本 `et_eval_rule_hit` 的唯一键是 `(rule_key, subject_id)`：同一条规则对同一条 trace / 同一个会话
  **只评估一次**。重复的 trace 完成事件、Rewind 重跑、多个规则同时命中都不会产生第二次调用。
- 多规则命中同一批评估器时，按 `priority` 顺序执行，同一评估器只跑一次；后命中的规则记 `DEDUPED`，
  结论的 `rule_key` 是**实际执行**的那条规则。
- 小时预算也读账本（`SAMPLED` + `FORCED` 计数），所以重启进程不会"刷新额度"。预算用尽记 `SKIPPED_BUDGET`。

### 15.3 SESSION scope 的口径

- 会话成员按 `session_id` 聚合，用每轮的 `inputPreview` / `outputPreview` 拼成多轮摘录作为候选输出；
  **不读取正文内容**（不受 `TRACE_CONTENT` 权限影响，也不会把正文写进评估请求之外的存储）。
- 结论写入 `et_eval_result`，带 `scope=SESSION`、`session_id`、`rule_key`，主体 id 为 `session:<sessionId>`
  （避免与真实 trace 的 `(evaluator, trace, attempt)` 唯一键冲突）。
- 会话仍活跃时不评估；空闲超过 `sessionIdleMinutes` 后由后台扫描补评，或由 `/summary` 前的正常流程触发。

### 15.4 Rewind：按 filter 补评历史 trace

```bash
# 建作业（同 Idempotency-Key 返回同一作业）
curl -s -X POST "$HOST/api/v1/eval-rules/support-slow/rewinds" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: rewind-2026-11-11-1" \
  -H 'Content-Type: application/json' -d '{
    "filters": {"minStartTime": "2026-11-01T00:00:00", "agentKeys": ["support-agent"]},
    "ratePerMinute": 120,
    "limit": 500
  }'

# 观察 / 暂停 / 恢复 / 取消
curl -s "$HOST/api/v1/eval-rules/rewinds/$JOB" -H "Authorization: Bearer $JWT"
curl -s -X POST "$HOST/api/v1/eval-rules/rewinds/$JOB/pause"  -H "Authorization: Bearer $JWT"
curl -s -X POST "$HOST/api/v1/eval-rules/rewinds/$JOB/resume" -H "Authorization: Bearer $JWT"
curl -s -X POST "$HOST/api/v1/eval-rules/rewinds/$JOB/cancel" -H "Authorization: Bearer $JWT"
```

- 游标按 `(start_time, trace_id)` 递增，暂停后保留游标，恢复从断点继续。
- 每轮处理量 = `ratePerMinute/12`（调度每 5 秒一轮，因此约等于每分钟 `ratePerMinute` 条）。
- 已评估过的 trace 会记 `deduped` 而不是重复评估；`evaluated/deduped/failed` 三个计数可直接判断作业效果。

### 15.5 与旧全局采样的关系

| 场景 | 行为 |
| --- | --- |
| 工作空间没有启用规则 | 走旧全局采样（`default_rate` + `max_per_hour` + 错误强制），完全不变 |
| 有启用规则 | 只按规则评估；全局采样率对该空间不再生效 |
| 规则全部禁用/删除 | 自动回到全局采样 |

### 15.6 已知边界（后续迭代）

- `SPAN` scope 目前复用 TRACE 的正文口径（独立 span 采样需要 span→content 映射先落地）。
- Evaluation Monitor 的阈值判定仍是「按 agentKey + evaluatorKey 的最近 N 条」，分母尚未随 scope 调整。

---

## 16. 端用户反馈采集（R2-2）

把真实用户的评价接进评测闭环：平台为某条 trace 预签一个**只能写反馈**的 token，端用户（或你的前端/客服系统）拿它提交评价，不接触正文与账号体系。

### 16.1 签发 token（控制面）

```bash
curl -s -X POST "$HOST/api/v1/traces/$TRACE_ID/feedback-tokens" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"maxUses": 3, "expiresInMinutes": 10080}'
# → {"token":"fb_...","tokenId":"...","maxUses":3,"expiresAt":"...","endpoint":"/api/v1/feedback/fb_..."}
```

- 明文 token **只在这个响应里出现一次**，库里只存 sha256。
- `maxUses` 1–100（默认 5），`expiresInMinutes` 0–525600（默认 7 天，0=立即过期）。
- 吊销：`POST /api/v1/traces/{traceId}/feedback-tokens/{tokenId}/revoke`。

### 16.2 提交反馈（公开，免登录）

```bash
# 数值（0–10，>=6 记为 PASS）
curl -s -X POST "$HOST/api/v1/feedback/$TOKEN" -H 'Content-Type: application/json' \
  -d '{"kind":"NUMERIC","valueNum":2,"comment":"答非所问","userRef":"anon-42"}'

# 布尔（点赞/点踩）
curl -s -X POST "$HOST/api/v1/feedback/$TOKEN" -d '{"kind":"BOOLEAN","valueText":"down"}'

# 分类（好/差/一般；未知词记 UNKNOWN，不硬判）
curl -s -X POST "$HOST/api/v1/feedback/$TOKEN" -d '{"kind":"CATEGORICAL","valueText":"差"}'

# 纠正：用户直接给出期望答案，可直接收录为数据集样本
curl -s -X POST "$HOST/api/v1/feedback/$TOKEN" -d '{
  "kind":"CORRECTION","correction":"在「财务中心 → 发票管理」提交开票申请"}'
```

返回 `{accepted, feedbackId, label, remainingUses}`。查询 token 状态（也免登录，但只回能不能继续提交）：

```bash
curl -s "$HOST/api/v1/feedback/$TOKEN"     # {"valid":true,"expiresAt":...,"remainingUses":2}
```

### 16.3 安全边界（刻意的最小权限）

| 场景 | 结果 |
| --- | --- |
| 拿 token 当 Bearer 访问 `/api/v1/traces/{id}` | 仍是 `40101`（token 不是登录凭据） |
| 泄露 token 后能读到什么 | 只能看 `valid/expiresAt/remainingUses`，读不到 traceId、正文、工作空间 |
| 刷量 | 单 token 每分钟限流（默认 20 次，可配）+ `maxUses` 配额 |
| 过期 / 吊销后 | `40301`；配额用尽 `40901` |

### 16.4 反馈落到哪里

- 每条反馈写一行 `et_end_user_feedback`（类型化明细），同时写一条 `et_eval_result`
  （`evaluatorKey=end-user-feedback`），所以既有的评估监控、质量总览、信任分**不需要改**就能看到它。
- `reason` 只记录类型与「是否带纠正」，**不写用户正文**；纠正内容只在 `et_end_user_feedback.correction` 里。
- 同一 trace 在窗口内负面反馈「刚好跨过阈值」（默认 3 条 / 24h）时产生一条 `END_USER_FEEDBACK` Finding，
  进入既有的 Incident 归并；不会每条差评都开一条。

### 16.5 把纠正反馈变成样本

```bash
curl -s -X POST "$HOST/api/v1/traces/$TRACE_ID/feedback/$FEEDBACK_ID/dataset" \
  -H "Authorization: Bearer $JWT" -H 'Content-Type: application/json' \
  -d '{"datasetId":"ds-1"}'
```

- 只有 `CORRECTION` 类可收录（其它类型返回 400，明确说明原因）。
- 样本输入取 trace 的 `inputPreview`，期望输出取用户纠正内容，`sourceType=USER_FEEDBACK`、
  `sourceRef=feedback:<id>`；写入前走 `EvaluationCaseService` 契约校验（超长/非法直接 400）。
- 若 trace 没有输入预览（未采集 prompt 内容），收录会被拒绝并提示手工补充 input。

### 16.6 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `emonlens.feedback.token-max-uses` | 5 | 单 token 默认可用次数 |
| `emonlens.feedback.token-expires-minutes` | 10080 | 单 token 默认有效期（7 天） |
| `emonlens.feedback.rate-limit-per-minute` | 20 | 单 token 每分钟提交上限（单实例内存窗口） |
| `emonlens.feedback.finding-threshold` | 3 | 触发 Finding 的负面反馈条数 |
| `emonlens.feedback.finding-window-hours` | 24 | 阈值统计窗口 |
| `emonlens.feedback.finding-severity` | MEDIUM | Finding 严重级 |

---

## 17. 评估器质量看板（R2-3）

入口：评测 →「评估器质量」（`/evaluator-quality`）。一次报告同时回答两件事：**哪个评估器最不可信**、**哪个数据集在腐烂**。

### 17.1 判定口径

| 判定 | 触发条件 | 建议动作 |
| --- | --- | --- |
| `LOW_TRUST` | 盲审一致率 < 80%（与 Gate 校准阈值同口径） | 复核 rubric；改 CLAIMS 逐条判分；换 Judge 模板版本 |
| `NOISY` | UNKNOWN + 未执行 ≥ 20% | 补证据（检索上下文 / 工具轨迹 / 参考答案）或放宽 `referenceRequired` |
| `COSTLY` | 窗口成本 > 阈值（默认 5） | 开评测缓存，或改用表达式 / 正则 / 轨迹等确定性评估器 |
| `OK` | 以上都不触发 | — |
| 数据集 `ROTTING` | 陈旧样本（> 90 天）≥ 50%，或最新 revision 超 30 天 | 补新样本并冻结新 revision |
| 数据集 `WARNING` | 陈旧 ≥ 25%，或 revision 超 14 天 | 按蓝图补齐缺失类别 |

- `ERROR` 与 `EVAL_UNAVAILABLE` 单独计为「未执行」，**不混进 UNKNOWN**——执行失败与证据不足是两回事。
- 盲审样本不足 10 条时会明确提示「一致率还不具统计意义」「建议补样本」。
- 缺模型价目时 `priced=false`、`cost=null`，不伪造金额。

### 17.2 API

```bash
# 一次拿全（默认最近 7 天、陈旧阈值 90 天）
curl -s "$HOST/api/v1/evaluator-quality?windowHours=168&staleDays=90" -H "Authorization: Bearer $JWT"
```

关键字段：

| 字段 | 含义 |
| --- | --- |
| `leastTrusted` | 最不可信的评估器（LOW_TRUST → NOISY → COSTLY → 一致率升序） |
| `evaluators[]` | 每个评估器的 evalCount / passRate / unknownRate / notExecutedRate / agreementRate / cost / verdict / advice |
| `datasets[]` | activeItems / staleItems / staleShare / latestRevision / revisionAgeDays / blueprintConfigured / verdict |
| `cache` | 窗口内缓存条目与节省 token（工作空间级） |
| `alerts[]` | 当前应关注的评估器与数据集（与扫描写入的 Finding 同源） |

### 17.3 告警接入 Alerts

```bash
# 手动触发一次（每日 03:10 也会自动扫）
curl -s -X POST "$HOST/api/v1/evaluator-quality/scan" -H "Authorization: Bearer $JWT"
# → {"findingsCreated":2}
```

- 写入 `EVALUATOR_QUALITY` / `DATASET_ROT` 两类 Finding，进入既有 Incident 归并与通知；
- 24 小时内同一对象只报一次（不会每天刷同样的告警）；
- 质量告警是平台级对象，`agent_key` 使用哨兵值 `*`，在 Alerts 页与「某个 agent」的告警区分得开。

### 17.4 配置项

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `emonlens.evaluator-quality.window-hours` | 168 | 默认统计窗口 |
| `emonlens.evaluator-quality.stale-days` | 90 | 「陈旧样本」判定天数 |
| `emonlens.evaluator-quality.min-agreement` | 80 | LOW_TRUST 阈值（%） |
| `emonlens.evaluator-quality.unknown-alert-rate` | 0.2 | NOISY 阈值 |
| `emonlens.evaluator-quality.costly-per-window` | 5.0 | COSTLY 成本阈值 |
| `emonlens.evaluator-quality.min-reviewed-for-trust` | 10 | 低于该盲审条数只给补样本建议 |
| `emonlens.evaluator-quality.rot-revision-days` | 30 | revision 超期判 ROTTING |
| `emonlens.evaluator-quality.warn-revision-days` | 14 | revision 超期判 WARNING |
| `emonlens.evaluator-quality.cron` | `0 10 3 * * *` | 每日扫描时间 |





