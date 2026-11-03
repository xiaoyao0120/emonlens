# EmonLens 评测与准入操作指南

> 适用版本：0.1.0（评测体系 3.0 首期 + G02/G05/G09/G10 增量）。
> 面向：要回答“这次改动能不能放行”的 Agent 负责人、评测同学和发布同学。
> 部署与接入见《EmonLens部署运维手册》《Agent接入教程》；平台日常观测见《EmonLens操作指南》。

---

## 1. 先记住一条主线

EmonLens 3.0 把一次发布判断拆成一条可回看的链：

```text
Trace 证据
  → 数据集样本（用途/风险/切分）
  → 评估器与套件（确定性检查优先）
  → 运行实验（冻结 revision，记录覆盖率/UNKNOWN/ERROR）
  → Judge 人工盲审校准
  → 成对比较 / run diff
  → 准入 Gate（PASS / FAIL / BLOCKED）
  → 质量案例闭环（修复 → 回归 run → PASS Gate 关闭）
  → 业务实验证据（独立展示，不改变离线 Gate）
```

**执行完成 ≠ 质量通过。** `COMPLETED` 只表示 run 跑完了；能不能放行要看 Gate，以及 Gate 的 `verification.usable` 是否仍为 true。

---

## 2. 页面地图

| 页面 | 路径 | 什么时候用 |
| --- | --- | --- |
| 质量总览 | `/quality` | 每天先看这里：哪些数据集有可用 PASS Gate、哪些 BLOCKED、哪些案例未关闭 |
| 数据集 | `/datasets` | 收录样本、冻结 revision、跑实验、看逐样本结果 |
| 评估器 | `/evaluators` | 建 EXPRESSION / REGEX / LLM_JUDGE / TRAJECTORY；右上角「评估套件」创建套件 |
| 运行报告 | `/datasets/:id/runs/:runId` | 可分享/可刷新的完整报告：结果、任务账本、校准、Gate、业务证据、案例 |
| 质量案例 | `/quality-cases` | 登记、推进、关联回归 run、关闭 |
| 领域包 | `/datasets` →「领域包」 | 声明质量维度、风险分类、数据蓝图、Gate 模板 |
| 规则候选 | `/datasets` →「规则候选」 | 从失败样本生成候选规则并在 HOLDOUT 审计 |
| 业务实验 | `/business-experiments` | 登记外部实验、导入版本化报告、管理签名凭据 |
| 标注队列 | `/annotation-queues` | 人工抽检 Trace，结果计入评估统计 |

> 运行报告现在有独立 URL；打开后点右上角「复制链接」即可发给同事。

---

## 3. 端到端操作

### 3.1 准备样本

1. 在「调用链路」打开一条真实 Trace，点「收录数据集」；或在「数据集」里新建后手动添加样本。
2. 给样本标注：
   - **用途**：happy / boundary / regression 等；
   - **风险**：high / medium / low（绑定领域包后必须属于 pack 的 riskTaxonomy）；
   - **切分**：TRAIN / VALIDATION / HOLDOUT（规则候选审计只使用 HOLDOUT）；
   - **失败模式**：如 latency / reference_mismatch / unsafe_tool。
3. 在「数据集 → 样本与设置」检查「数据集蓝图与保鲜」：
   - 蓝图覆盖不足会给出 warning；
   - 过期样本可以软失效，不会物理删除，也不会影响历史 run。
4. 运行实验会自动冻结当前样本为 revision；也可以手动「冻结当前样本」。

### 3.2 建评估器与套件

1. 「评估器 → ＋ 新建评估器」：
   - **EXPRESSION**：零依赖表达式，如 `error_count == 0`；
   - **REGEX**：对 completion / prompt / tool_payload 做正则断言；
   - **LLM_JUDGE**：Rubric + 可选 criteria；结果是“模型建议”，未校准前不能单独支撑 Gate。
2. 需要多个评估器一起跑时，点「评估器 → 评估套件」：
   - 填 suiteKey、名称、选择 1–5 个评估器；
   - 套件创建后冻结当时的评估器版本，后续改评估器不会改变已有套件。
3. 评估器可以填「质量维度」，对应领域包 `qualityModel.dimensions[].key`；运行报告会按维度汇总。

### 3.3 运行实验

1. 进入数据集，点右上角「▶ 运行实验」。
2. 选择：
   - **TRACE**：读取已采集 Trace 判定，不调用模型；
   - **REPLAY**：用样本 input 重新生成候选，再判定；会产生外部调用费用。
3. 可选「评估套件」，或直接选 1–5 个评估器。
4. 在「预算、复核与样本修订」里可以设置：
   - 外部调用上限 / Token 上限 / 成本上限（USD）；
   - 人工复核模式：SINGLE 或 DOUBLE_BLIND；
   - 指定已有 revision，保证两次 run 使用同一批样本。
5. 提交后后台执行；同一时间允许的活动 run 由服务端配置决定。预算用尽后未执行样本标 `BUDGET_EXCEEDED`。

### 3.4 看运行报告

打开「实验（Runs）→ 结果」，或直接访问 `/datasets/:id/runs/:runId`。重点看：

- **计数守恒**：样本 = PASS + FAIL + UNKNOWN + ERROR + 未完成；
- **通过率** = PASS / (PASS + FAIL)；
- **覆盖率** = (PASS + FAIL) / 样本数；
- **UNKNOWN / ERROR 单列**，不要把它们当 PASS；
- **逐样本结果**：每条样本的判定、原因、逐评估器/维度、输入/候选/参考；
- **任务账本**：样本 × 评估器逐条留痕；迟到结果不能覆盖终态；
- **尝试链**：续跑/重试会保留原 run，不被覆盖。

### 3.5 Judge 校准（含 LLM_JUDGE 时）

1. 在运行报告里打开「Judge 校准（人工盲审）」。
2. 人工先看样本独立给 PASS/FAIL；全部样本提交前，页面会隐藏 run 统计、逐样本结果和 Gate。
3. 判定口径：
   - 至少 10 条、全部审完、一致率 ≥ 80% 才是 `OK`；
   - 少于 10 条为 `INSUFFICIENT_SAMPLE`；
   - 一致率低于 80% 为 `LOW_AGREEMENT`；
   - DOUBLE_BLIND 下两人分歧必须由第三方仲裁，未仲裁保持 `IN_PROGRESS`。
4. 校准结果会进入 Gate；未完成校准时 Gate 为 `BLOCKED`，一致率低为 `FAIL`。
5. 全部审完后可导出校准 CSV 供审计。

### 3.6 成对比较 / run diff

1. 在「实验与结果 → 实验对比」选择 A、B 两次 run。
2. 先看比较性提示：
   - `sampleSetHash` 不同、评估器指纹不同、内容处理规则不同 → 不可直接比较；
   - 此时「成对偏好」按钮会禁用，避免把不可比差异当成质量变化。
3. 可比时点「成对偏好」：
   - 可配 1–3 个评审模型、每方向 1–3 次、位置随机化与 seed；
   - 双向映射后一致才输出 A/B/TIE，冲突记 UNKNOWN；
   - 结果只证明相对优劣，不证明绝对合格。
4. 历史成对比较保存在「成对比较历史」，点任意一行看逐样本偏好与原因。

### 3.7 生成准入 Gate

1. 在运行报告里找到「准入结论（Gate）」。
2. 选择 Gate 模板（如果数据集绑定了领域包），或使用默认政策 90/0/0/24h：
   - `minCoverage`：最低判断覆盖率；
   - `maxFail`：允许的 FAIL 数；
   - `maxUnknown`：允许的 UNKNOWN 数；
   - `validHours`：结论有效期。
3. 点「生成准入结论」，得到 `PASS` / `FAIL` / `BLOCKED`。
4. 检查项包括：
   - run 终态、未执行样本、样本计数守恒；
   - 覆盖率、FAIL/ERROR/UNKNOWN 预算；
   - revision/version、sampleSetHash、候选指纹绑定；
   - prompt 版本绑定（`PROMPT_BINDING`；绑定 production 时要求指针未移动，见 3.12）；
   - Judge 校准（含 Judge 的 run）；
   - 成本预算（如设置）。
5. **不要只看历史 `decision=PASS`。** 通过 `GET /api/v1/quality-gates/{id}/verification` 校验：
   - 是否过期；
   - run 是否仍 COMPLETED；
   - 候选指纹是否变化；
   - **绑定的 prompt 版本是否仍是当前 production**（`PROMPT_PRODUCTION_MOVED` / `PROMPT_PRODUCTION_MISSING`）；
   - 是否被同 run 更新的 Gate 取代；
   - 校准是否仍有效。
6. CI/CD 只消费 `verification.usable=true` 且 `decision=PASS` 的 Gate；`BLOCKED`、超时、未知枚举一律不放行。

### 3.8 质量案例闭环

1. 在「质量案例」点「＋ 登记案例」，或在运行报告里「登记案例」。
2. 填标题、现象、根因分类、Owner，可关联源 run / 源样本。
3. 状态迁移：`OPEN → INVESTIGATING → FIXED`；也可以 `REJECTED`。
4. 修复后：
   - 填 `fixFingerprint`（如 prompt-v2 / commit-sha）和修复说明；
   - 关联回归 run；
   - 关闭条件：状态 FIXED、回归 run COMPLETED、该 run 最新 Gate 为 PASS 且 `verification.usable=true`。
5. 关闭后写 `verifiedBy/verifiedAt`；Case 不自动改代码、不自动部署。

### 3.9 业务实验证据（可选）

1. 「业务实验 → ＋ 绑定实验」：填 source、externalExperimentId、设计类型、主指标、最小样本量，可绑定一次 run。
2. 打开实验详情，导入版本化报告：
   - `reportVersion` 不可覆盖；同 payload 重复导入幂等；
   - 质量检查覆盖有效样本量、成熟度、SRM、护栏和统计推断；
   - `RANDOMIZED` 才可能得到 POSITIVE/NEGATIVE；`OBSERVATIONAL` 固定 INCONCLUSIVE；
   - 样本不足/未成熟/SRM 失败/护栏失败为 BLOCKED。
3. 业务证据独立展示，不写进 0–100 分，也不改变离线 Gate。
4. 需要来源签名时，先在「签名凭据」登记 RSA 公钥，导入证据带 `keyId/signature/signedAt/nonce`。

### 3.10 领域包（可选）

1. 「数据集与资产 → 领域包 → ＋ 发布领域包版本」。
2. manifest 使用 `domain-pack/v1`，声明：
   - `qualityModel.dimensions`：质量维度、权重、依赖；
   - `riskTaxonomy`：风险分类；
   - `datasetBlueprint`：最小样本量、风险/切分/用途配比、保鲜天数；
   - `gateTemplates`：Gate 政策模板。
3. 发布后不可变；同版本同 digest 幂等，同版本不同 digest 冲突。
4. 在「数据集 → 样本与设置 → 绑定领域包」绑定；数据集自己的蓝图优先于 pack 蓝图。
5. 升级只允许同一 packKey 的更高 PUBLISHED 版本；历史 run 保留旧版本。

### 3.11 规则候选（可选）

1. 「数据集与资产 → 规则候选」选择数据集和源 run（必须 COMPLETED）。
2. 可以手填候选规则，或勾选「从非 HOLDOUT 失败样本生成」。
3. 审计只在 `split=HOLDOUT` 且有 PASS/FAIL 标签的样本上计算 precision/recall/support。
4. Bootstrap 稳定性、CV 折数、语义去重都是可选高级项；不确定时保持默认。
5. 候选状态为 PROPOSED / REJECTED；人工批准只改状态，不自动发布规则、不改 Gate。

### 3.12 Prompt 版本绑定与矩阵升格（0.2.0）

1. 想让结论回答「上线的是哪个 prompt」，提交 run 时带 `promptName`（可加 `promptVersion` 或 `promptLabel`）：
   解析出的 `(name, version, hash)` 会写进 run 与冻结快照，并进入候选指纹。
2. 用矩阵实验调参时（构建 →「调试与提示词」→「矩阵实验」），矩阵本身**不是**准入证据；对要放行的那一列点「升格为正式实验」，
   平台按该列冻结的 prompt 版本与生成参数创建 dataset run，随后照常生成 Gate。
3. **Gate 会因 prompt 改版而失效**：把 `production` 指向新版本后，旧 Gate 的 `verification.usable` 立刻变为 `false`，
   原因形如 `PROMPT_PRODUCTION_MOVED:support-assistant:v3->v4`。CI 必须重新跑评测，不能继续用旧结论。
4. 想回答「这个 prompt 版本被哪些 run 评过、结论如何」，用 `GET /api/v1/prompts/{name}/runs`，
   或在「提示词 → 版本抽屉 → 评估证据」查看（含 runId、prompt 版本、Gate 决策、通过/失败、run 标签、提交号）。
5. CI 基线：给某条 run 打 `baseline` 标签（同一数据集内唯一），后续 PR 的评测会与它做逐样本 diff。

---

## 4. 口径速查

| 指标 | 公式 / 含义 |
| --- | --- |
| 通过率 | PASS / (PASS + FAIL) |
| 覆盖率 | (PASS + FAIL) / 样本数 |
| UNKNOWN | 证据不足或无法判断，不是 PASS，也不是 FAIL |
| ERROR | 执行错误；Gate 默认不允许 |
| COMPLETED | 执行完成，不等于质量通过 |
| Gate PASS | 检查项全部通过且未过期、未被取代 |
| Gate BLOCKED | 无法判断；CI 不得放行 |
| Judge OK | 全部审完、至少 10 条、一致率 ≥ 80% |

---

## 5. 常见问题

**Q：为什么 run 是 COMPLETED，Gate 却是 BLOCKED？**
A：常见原因是覆盖率不足、有 UNKNOWN/ERROR、Judge 未校准、缺少 revision/version 绑定，或 Gate 已过期。打开 Gate 卡片看具体检查项。

**Q：为什么两个 run 不能成对比较？**
A：`sampleSetHash`、评估器指纹或内容处理规则不一致。用同一批样本和同一套评估器复跑，再比较。

**Q：为什么信任分是空的？**
A：V2 评分只在五维证据全部 COMPLETE 时给总分；缺失维度显示 `NOT_CONFIGURED` 或 `INSUFFICIENT_EVIDENCE`，不再用 100 分代替。

**Q：业务实验 POSITIVE 了，是不是就可以发布？**
A：不是。业务证据独立展示，不改变离线 Gate；发布仍以 Gate 和 verification 为准。

**Q：旧 Gate 还能用吗？**
A：用 `GET /api/v1/quality-gates/{id}/verification` 校验；过期、run 状态变化、候选指纹变化、prompt 版本被移出 production、被新 Gate 取代、校准失效都会让 `usable=false`。

**Q：prompt 改了，之前的 Gate 为什么失效？**
A：run 绑定了 `(prompt name, version, hash)`。把 `production` 指向新版本后，被评的那一版已经不是上线版本，结论不再成立，
verification 返回 `PROMPT_PRODUCTION_MOVED`。要么用新版本重跑评测，要么把 production 指回被评版本（回滚）。

**Q：矩阵跑出来效果更好，能直接当放行依据吗？**
A：不能。矩阵是试跑产物（无候选指纹、不进 Gate）。请用「升格为正式实验」把它变成 dataset run，再按 Gate 流程放行。

---

## 6. CI 消费示例

```bash
# 1. 提交评测（开放 API，需 eval:run scope 和 Idempotency-Key）
curl -X POST "$EMONLENS/api/v1/open/eval-runs" \
  -H "X-Api-Token: $TOKEN" \
  -H "Idempotency-Key: $CI_PIPELINE_ID" \
  -H "Content-Type: application/json" \
  -d '{"datasetId":"...","suiteId":"...","mode":"TRACE"}'

# 2. 轮询 run 终态
curl "$EMONLENS/api/v1/open/eval-runs/$RUN_ID" -H "X-Api-Token: $TOKEN"

# 3. 生成 Gate（内部 API 或已生成的 Gate ID）
# 4. 校验 Gate
curl "$EMONLENS/api/v1/open/quality-gates/$GATE_ID/verification" -H "X-Api-Token: $TOKEN"
```

只有 `decision=PASS` 且 `usable=true` 才继续发布；否则停止并展示检查项。CI 日志只记录 runId/gateId 和摘要，不输出正文、密钥或个人信息。

### 6.1 用官方 Action 一行接入（0.2.0）

不想自己写脚本时，直接用仓库根目录的 composite Action（示例工作流 `docs/examples/github-action-eval.yml`）：

```yaml
permissions:
  contents: read
  pull-requests: write
steps:
  - uses: actions/checkout@v4
  - uses: your-org/emonlens@v0.2.0
    with:
      host: ${{ vars.EMONLENS_HOST }}
      api-token: ${{ secrets.EMONLENS_API_TOKEN }}
      dataset: ${{ vars.EMONLENS_DATASET_ID }}
      evaluators: ${{ vars.EMONLENS_EVALUATOR_IDS }}
      prompt-name: support-assistant
      baseline-label: baseline
```

它会：跑评测 → 校验 Gate → 把「通过率 / 覆盖率 / Gate 决策 / 未通过检查项 / 与基线 diff」写成一条幂等更新的 PR 评论 →
用 CLI 退出码决定 job 成败（Gate 非 PASS 时 job 失败）。评论不含样本正文。

---

## 7. V1.1 新增能力

> 本节是速查。字段限制、完整示例、API 调用和排错见《EmonLens评测增强功能使用指南》。

### 7.1 样本契约 v2（检索 / 工具 / 多轮 / 期望）

数据集详情 → 样本 →「添加/编辑样本」→「高级：检索 / 工具 / 多轮 / 期望」。填写任意一项会自动使用 `schemaVersion=2`：

- **检索上下文**：`[{"id":"doc-1","content":"...","score":0.9,"source":"kb/x.md"}]`
- **期望工具**：`[{"name":"query_order","type":"FUNCTION","inputParameters":{"orderId":"123"},"required":true}]`
- **多轮会话**：`[{"role":"user","content":"..."},{"role":"assistant","content":"..."}]`
- **样本级期望**：`{"must":["..."],"mustNot":["..."],"unknownPolicy":"BLOCK"}`

TRACE 模式会自动从 tool/retrieval span 投影实际工具调用和检索证据；REPLAY 只做文本重放，不产生工具轨迹。缺证据时评估器返回 `UNKNOWN` 并给出原因码（如 `TOOLS_CALLED_MISSING` / `RETRIEVAL_CONTEXT_MISSING`），不判 FAIL。

### 7.2 Judge 模板与 CLAIMS 判分

评估器创建/编辑 → LLM_JUDGE：

- **判分模式**：`HOLISTIC`（默认，整体 0–10）或 `CLAIMS`（逐条 claim）。
- **Judge 模板 key**：留空使用 `platform/holistic-v1`；CLAIMS 推荐 `platform/claim-v1`。
- **所需证据字段**：如 `candidate_output, retrieval_context`；缺失返回 UNKNOWN。
- **CLAIMS 策略**：最多 claim 数（硬上限，模型返回超限按非法输出判 UNKNOWN）、PASS 最低通过比例、UNKNOWN 策略（BLOCK/FAIL/PASS；PASS 下全部 UNKNOWN 仍为 UNKNOWN）、是否要求 evidenceRefs。

平台模板只读；workspace 模板通过 `POST /api/v1/judge-templates` 创建，只追加版本。评估器保存时若未指定模板版本，会固定当时的最新已发布版本；之后发布新模板不会改变已有评估器和旧 run，要升级需显式填写新版本。模板 key/version/hash 会冻结在评估器版本、运行快照和任务账本中；Gate 的 `JUDGE_TEMPLATES` 检查新 run 是否带模板 hash。

### 7.3 TRAJECTORY 轨迹评测

评估器类型选择 `TRAJECTORY`，填写 JSON：

```json
{
  "trajectoryMode": "SUBSET",
  "expectedTools": [{"name": "query_order", "required": true}],
  "forbiddenTools": ["delete_order"],
  "maxSteps": 12,
  "allowCycles": false,
  "argumentRules": [
    {"tool": "query_order", "path": "orderId", "required": true, "regex": "^[0-9]+$"}
  ]
}
```

确定性检查，不调用 LLM；只在 TRACE 模式有工具证据。

### 7.4 SDK / CLI

```bash
export EMONLENS_HOST="http://localhost:8300"
export EMONLENS_API_TOKEN="<开放 API Token，含 eval:run / eval:read / score:read>"

# 提交并等待，失败非零退出
emonlens eval run --dataset <datasetId> --suite <suiteId> --mode TRACE --wait --assert
# 校验历史 Gate（CI 必须消费 usable + decision）
emonlens gate check --gate-id <gateId> --json

# SDK 评测器：run 终态后执行本地 scorer 并回写；Token 需 eval:run/eval:read（正文另需 eval:content）
emonlens eval run --dataset <datasetId> --evaluator <evaluatorId> --wait   --sdk-runner ./my_scorers.py --assert
```

Python：

```python
import emonlens

result = emonlens.evaluate(dataset_id="...", suite_id="...", mode="TRACE", wait=True)
emonlens.assert_test(result)
verification = emonlens.gate_check("gate-id")
```

### 7.5 成本、重试与缓存

- `GET /api/v1/datasets/{id}/runs/{runId}/cost` 返回 `byPhase`（generation/judge）、`byModel`、`byEvaluator`、`cache`、`unpricedModels`；多模型逐项计价，缺价格不伪造总额。
- Judge 重试：`emonlens.eval.llm.max-attempts`（默认 3）、`initial-backoff-ms`、`max-backoff-ms`、`jitter`。429/5xx/网络/超时重试；401/403/404、配额/计费/上下文超限不重试。
- 评测缓存：`emonlens.eval.cache-enabled=true` 开启（默认关闭）；key 绑定样本内容 + evaluator spec + 模板 hash + judge model + temperature + judgeMode，任一变化自然失效；`cache-ttl-hours` 默认 168；temperature>0 默认不缓存。命中时 `cacheHit=true`、`judgeCalled=false`、成本 0；成本页 `cache.hits` 可见，`cache.hitRate = hits / (hits + judgeCalls)`，`cache.judgeEvaluations` 为分母。

### 7.6 批量导入 / 导出

数据集详情 → 样本卡片 →「导入」：

- 支持 JSONL（每行一个样本）和 CSV（首行表头）。
- `dry-run` 只校验不写库，返回 `wouldCreate`（可新建数量）；`atomic` 全成或全败；默认非 atomic 跳过非法行、写入合法行并返回行级错误。
- 按 `contentHash` 幂等：重复样本计入 `duplicated`，不会重复插入；单次最多 1000 行。
- JSON 字段（retrieval_context / expected_tools / turns / expectations / metadata）在 CSV 中是 JSON 字符串。
- 导出 JSONL/CSV 受 `TRACE_CONTENT` 控制：无内容权限时只导出标签、状态和 hash，不导出正文。

### 7.7 合成数据集（冷启动）

数据集详情 → 样本卡片 →「合成」：

- 来源：`SCRATCH`（纯生成）、`DATASET_ITEMS`（从现有样本进化）、`DOCUMENTS`（从上传文本生成）、`TRACE_CLUSTER`（从 Trace 生成）。
- 配置：生成数量、最低质量分（critic 阈值 0–1）、critic 重试次数、场景描述。
- 流程：worker 调 LLM 生成候选 → critic 打分 → 低于阈值重试 → 去重（精确 hash + bigram Dice）→ 写 DRAFT 候选。
- 人工批准：候选列表逐条「批准」→ 复制为 ACTIVE 样本（`source_type=SYNTHETIC`）；「拒绝」→ REJECTED。
- 没有任何候选会自动进入工作集；低质量候选标 REJECTED 不可批准。
- 开关：`emonlens.dataset-synthesis.enabled=true`（默认 false）；需要系统设置 → LLM 端点已配置。

### 7.8 升级顺序

已有 MySQL 库依次执行：

```bash
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-02-eval-case-v2.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-09-judge-template.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-16-eval-cache.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-23-dataset-synthesis.sql
mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-30-dataset-item-hash-index.sql
```

脚本幂等，可重复执行；执行前备份 `et_dataset_item` / `et_dataset_revision_item` / `et_evaluator_version` / `et_eval_task`。
