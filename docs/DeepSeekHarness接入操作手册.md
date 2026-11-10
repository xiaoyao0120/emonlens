# DeepSeek Harness（DSH）接入 EmonLens 操作手册

> 适用：DeepSeek Harness 桌面版 / CLI；macOS / Linux / Windows；Python ≥ 3.9（回放 zstd 会话需要 3.14+，或 `pip install zstandard`）。

## 0. 与其它 Agent 的关键差异（先读这段）

Claude Code / Codex / ZCode 都是**hooks 协议**：客户端在事件发生时回调我们的命令，所以能做到准实时。
**DSH 没有等价的对外钩子**，它把每个会话写成本地文件。因此本接入是**回放式**：

```
~/.dsh/sessions/<工作区标记>/session-<uuid>/session.v4.jsonl.zstd   ← DSH 自己写
        │  emonlens-dsh-sync 解压 + 解析 + 转换
        ▼
EmonLens /api/v1/ingest/events   ← 一个 turn 变成一条 trace
```

由此带来两条必须知道的边界：

- **不是实时的**：你在 DSH 里跑完一轮之后（或手动/定时）执行一次同步才会出现在 EmonLens。想让它在轮次结束时自动跑，见 §7。
- **幂等靠服务端**：同一次会话重复同步不会重复入库（服务端按 `traceId + spanId` 去重）。实测：重放后 span 数不变。

## 1. 安装

```bash
pip install <emonlens仓库>/emonlens-sdk-python
# 若 Python < 3.14，解压 zstd 需要额外依赖：
pip install zstandard
```

安装后多出命令 `emonlens-dsh-sync`。不装包也可以直接用源码跑：

```bash
PYTHONPATH=<emonlens仓库>/emonlens-sdk-python/src python -m emonlens.dsh_sync --help
```

## 2. 签发 Ingest Key

EmonLens →「智能体接入」→ 签发 Ingest Key（形如 `tk-...`，明文只显示一次）。
这把 key 是**数据面**凭据，只需 `X-Trust-Key`，不需要用户登录 token。

## 3. 先看一遍要上报什么（不发数据）

```bash
emonlens-dsh-sync --host http://你的EmonLens地址:8300 --dry-run --limit 1
# 会话 1 个，trace 71 条，span 5660 个
# dry-run：未发送
```

`--dry-run` 只解析，用来确认「会话选对了、trace/span 数量符合预期」。一个 turn 就是一条 trace。

## 4. 正式同步

```bash
# 只同步最近 3 个会话
emonlens-dsh-sync --host http://你的EmonLens地址:8300 \
  --ingest-key tk-你的Key --limit 3

# 只同步某一个会话（按目录名或会话 id 片段匹配都行）
emonlens-dsh-sync --host ... --ingest-key tk-... --session ff91948e

# 只要时间线/用量，不要对话正文
emonlens-dsh-sync --host ... --ingest-key tk-... --limit 5 --no-content

# 换一个 agentKey（默认 dsh），便于和别的 Agent 分开看
emonlens-dsh-sync --host ... --ingest-key tk-... --agent-key dsh-nightly
```

环境变量也给：`EMONLENS_HOST` / `EMONLENS_INGEST_KEY`，两者都在时命令行可省略。
退出码：全部成功 0；有任何一条被服务端拒绝（例如 span 非法）返回 1，并把拒绝原因打出来。

## 5. 在 EmonLens 里你将看到

| 位置 | 内容 |
| --- | --- |
| 「调用链路」 | `agentKey=dsh` 的 Trace，**一轮对话一条**；根 span `dsh_turn` 的 input=你的提问、output=该轮最终回答 |
| Trace 详情 | 瀑布图里该轮的 `assistant_turn`（llm）与每个工具调用（tool，带真实耗时与参数/结果） |
| 「会话」 | 同一 DSH 会话的所有轮次聚合在同一个 `sessionId` 下，可回放多轮 |
| 「洞察与成本」 | llm span 带 `inputTokens/outputTokens/cachedInputTokens`，维护「模型价格」后自动算成本 |
| 「事件告警」 | 工具失败（DSH 的 `isError`）落成 `status=ERROR` 的 span，可被 VALIDATION 规则命中 |

实测（MySQL 8 + 一个 78KB 的真实会话）：2 轮 → 2 条 trace / 31 个 span，其中 1 agent + 2 llm + 2 tool / 26 span，
工具耗时 4.1–4.5s 与真实一致；同一会话重放后 span 数不变（幂等）。

## 6. 字段映射（要核对数据时看这里）

| DSH 事件 / 字段 | EmonLens |
| --- | --- |
| `session.id` | `sessionId`（会话聚合键） |
| `session.agentPreset` | `agentVersion` |
| `turn/start.time` / `turn/end.time` | 根 span 的 `startTime` / `endTime`（epoch 毫秒 → ISO8601，保留毫秒） |
| `user/message.content[].text` | 根 span `input` |
| 该轮最后一条 `assistant/message.content[].text` | 根 span `output` |
| `assistant/message.message.source.model` | llm span `modelName` |
| `assistant/message.usage.inputTokens` / `outputTokens` / `cacheReadTokens` | `inputTokens` / `outputTokens` / `cachedInputTokens` |
| `tool/call.data.name` + `arguments` | tool span `toolName` + `input` |
| `tool/result.message.content[].text` | tool span `output` |
| `tool/result.message.isError` | tool span `status=ERROR` + `errorType=TOOL_ERROR` |
| `turn/end.reason.error` | 根 span `status=ERROR` |

traceId 形如 `dsh-<会话id前24位>-t<轮次>`，可直接用接口查：

```bash
curl -s "$HOST/api/v1/traces/dsh-ff91948e-520e-41a7-ab77--t2" -H "Authorization: Bearer $JWT"
```

## 7. 让它自动跑（可选）

DSH 没有钩子，所以自动化的办法是在**你的 shell / 任务计划**里按需触发。两种常见做法：

```bash
# ① 每次退出 DSH 后同步（bash/zsh 的 ~/.bashrc 或 ~/.zshrc）
dsh() { command dsh "$@"; emonlens-dsh-sync --host http://... --ingest-key tk-... --limit 1 >/dev/null 2>&1; }

# ② 定时同步最近会话（cron，每 10 分钟）
*/10 * * * * EMONLENS_HOST=http://... EMONLENS_INGEST_KEY=tk-... emonlens-dsh-sync --limit 2 >/dev/null 2>&1
```

Windows 可用「任务计划程序」调用同样的命令。**建议加 `--limit`**：不加会把 `~/.dsh/sessions` 下所有历史会话都回放一遍。

## 8. 故障排查

| 现象 | 原因 / 处理 |
| --- | --- |
| `需要 zstd 支持` | Python < 3.14 且未装 `zstandard`：升级解释器或 `pip install zstandard` |
| `会话 N 个，trace 0 条` | 选中的是空会话（只有 `session` 记录，没有 turn）。用 `--dry-run` 换个会话确认 |
| 退出码 1，`rejected` 里写 `spanId must be <= 32 chars` | 旧版本桥接用 DSH 的 32 位 callId 拼 spanId 超长。当前版本已改为哈希，升级 SDK 即可 |
| 上报成功但页面没数据 | 检查 `--host` 指向的实例与你在看的实例是否同一个；再确认 trace 的 `agentKey` 是 `dsh`（或你自定义的值） |
| 只能看到最近一轮，历史会话不出现 | 「调用链路」默认筛选**近 24 小时**。回放的是几天前的会话时，把时间窗改成「近 7 天」或自定义范围 |
| 正文是空的 | 加了 `--no-content`，或工作空间「内容采集模式」为 REDACTED/HASHED |
| 想重来一遍 | 直接重跑即可（幂等）。要彻底清掉可用 `DELETE FROM et_span_event WHERE trace_id LIKE 'dsh-%'` 与对应 `et_trace_summary` |

## 9. 隐私与合规

DSH 会话里是**真实对话内容**（含你输入的路径、命令输出）。默认会把这些作为 span 的 input/output 上报。
如果不希望内容离开本机，用 `--no-content`：仍会上报轮次、模型、token、工具名与耗时，只是没有正文。
平台侧还可以把工作空间的内容采集模式设为 REDACTED/HASHED 做二次兜底。

## 10. 已知限制

- 只回放**已完成**的会话文件；正在写入的会话也能读，但最后一轮可能不完整（重放会补上）。
- `assistant/message` 的 `startTime` 与 `endTime` 相同（事件只有单个时间戳，DSH 未记录 LLM 调用耗时），
  因此 llm span 的 `durationMs` 为 0；**工具 span 的耗时是真实的**。
- 子 agent（`delegationDepth > 0`）的会话是独立文件，会各自成为一条 trace 链，不与父会话合并。
- 不解析 `stream` 里的增量片段（只取最终消息），避免把同一段文本重复计入。
