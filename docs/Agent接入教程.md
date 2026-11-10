# EmonLens Agent 接入教程

> 面向 AI Agent 开发者：把 Codex CLI、Claude Code、ZCode、自研 Python Agent 的调用数据接入 EmonLens，获得调用链路、评估、成本与信任分观测能力。

---

## 1. 概念模型（30 秒）

| 概念 | 说明 | 对应 Langfuse |
| --- | --- | --- |
| **Trace** | 一次完整的 Agent 调用（一个任务/一轮对话） | Trace |
| **Span / Observation** | Trace 内的一步：LLM 调用、工具执行、检索、Agent 根节点 | Observation |
| **spanType** | `agent`（根）/ `llm` / `tool` / `retrieval`，四种 | type |
| **Session** | 多次 Trace 的会话分组（传相同 `sessionId` 即自动聚合） | Session |
| **environment** | `production` / `staging` / 自定义，用于环境分流筛选 | Environment |

一个 Trace = 1 个 `agent` 根 span + N 个 `llm`/`tool`/`retrieval` 子 span，通过 `parentSpanId` 挂树。

---

## 2. 准备：签发 Ingest Key

1. 登录 EmonLens → **Agent 接入** → Ingest Keys → **签发 Key**
2. 建议填**名称（用途标签）**，如 `prod-ingest` / `ci-key`：多把 Key 并存时列表里能直接看出该吊销哪把（未命名会显示「未命名」）
3. 明文（形如 `tk-xxxx-xxxx-xxxx`）**仅显示一次**，保存好
4. 所有上报请求携带请求头：`X-Trust-Key: <你的Key>`

> 数据面（上报 Trace）用 Ingest Key；控制面（回写评分等）用「系统设置 → API Token」签发的 OpenAPI Token（请求头 `X-Api-Token: eak-...`），两者不要混用。

> Key 轮换：在 Ingest Keys 列表里对旧 Key 点「吊销」即可（吊销后该 Key 立即 40101，
> 已上报的历史 Trace 保留）。Agent 本体也可以在「Agent 接入 → Agent 列表」里「注销」——
> 工具清单随之移除，历史 Trace 同样保留。

---

## 3. HTTP 接口规范（所有语言通用）

### 3.1 上报 Span（推荐批量）

```bash
curl -X POST http://<emonlens-host>:8300/api/v1/ingest/events \
  -H "X-Trust-Key: $EMONLENS_INGEST_KEY" \
  -H "Content-Type: application/json" \
  -d '[
  {
    "traceId": "my-trace-001",
    "spanId": "root",
    "agentKey": "my-agent",
    "agentVersion": "v1",
    "spanType": "agent",
    "spanName": "invoke_agent",
    "startTime": "2026-09-25T10:00:00",
    "endTime":   "2026-09-25T10:00:03",
    "sessionId": "chat-42",
    "userId": "user-a",
    "environment": "production",
    "input":  "帮我分析工单 #1024 的处理进度",
    "output": "工单 #1024 已进入二线处理"
  },
  {
    "traceId": "my-trace-001",
    "spanId": "llm-1",
    "parentSpanId": "root",
    "agentKey": "my-agent",
    "spanType": "llm",
    "spanName": "call_model",
    "modelName": "gpt-4o-mini",
    "startTime": "2026-09-25T10:00:01",
    "endTime":   "2026-09-25T10:00:03",
    "status": "OK",
    "inputTokens": 512,
    "outputTokens": 344,
    "input":  "系统提示词+用户问题",
    "output": "模型回答原文"
  }
]'
```

**成功响应**（逐条校验，坏数据不影响好数据）：

```json
{ "code": 0, "data": { "accepted": 2, "rejected": 0, "dropped": 0 } }
```

### 3.2 字段说明

| 字段 | 必填 | 说明 |
| --- | --- | --- |
| `traceId` / `spanId` | ✅ | 全局唯一标识；同一 traceId 自动聚合成一条链路 |
| `parentSpanId` | — | 父 span；根 span 留空 |
| `agentKey` | ✅ | Agent 标识（如 `credit-agent`），评分/信任分按它聚合 |
| `agentVersion` | — | 版本号，用于 A/B 对比 |
| `spanType` | ✅ | `agent` / `llm` / `tool` / `retrieval` |
| `startTime` / `endTime` | ✅/— | ISO-8601（`yyyy-MM-dd'T'HH:mm:ss`）；llm/tool 建议必填以生成耗时瀑布 |
| `status` | — | `OK`（默认）/ `ERROR`（配 `errorType`） |
| `modelName` | — | llm span 填模型名 → 自动按「模型价格」估算成本 |
| `inputTokens` / `outputTokens` | — | token 用量，参与成本估算 |
| `input` / `output` | — | 原文内容，受工作空间「内容采集模式」脱敏控制 |
| `sessionId` / `userId` / `environment` | — | 会话聚合 / 用户归属 / 环境分流 |

**约束**：单批 ≤ 500 条；`status` 只能 `OK`/`ERROR`；非法字段会被逐条拒绝（响应 `rejected` 数组带原因）。

### 3.3 OTLP 兼容端点

已使用 OpenTelemetry SDK 的 Agent 可直接指向：

```bash
POST http://<emonlens-host>:8300/api/v1/ingest/otlp/v1/traces   # OTLP/JSON
```

OTLP 属性映射到 EmonLens 字段：

| OTel 属性 / 字段 | EmonLens 字段 |
| --- | --- |
| `agent.key` / resource service.name | `agentKey` |
| `agent.version` | `agentVersion` |
| span kind GENERATOR/INTERNAL | `spanType=agent` |
| `gen_ai.system` + `gen_ai.request.model` | `modelName` |
| `gen_ai.usage.input_tokens` / `output_tokens` | `inputTokens` / `outputTokens` |
| `session.id` | `sessionId` |
| `user.id` | `userId` |
| `deployment.environment` | `environment` |

**不写代码的 OTLP 路径**——用官方 OTel Collector 转发，任何语言的 Agent 都不改代码：

```yaml
# otel-collector-config.yaml
exporters:
  otlphttp:
    endpoint: http://emonlens:8300/api/v1/ingest/otlp/v1/traces
    headers:
      X-Trust-Key: tk-xxxx-xxxx-xxxx
service:
  pipelines:
    traces:
      exporters: [otlphttp]
```

### 3.4 Trace 完成判定

| 判定条件 | 说明 |
| --- | --- |
| **根 Span 结束** | `parentSpanId` 为空的 Span 有 `endTime` → 立即判定完成 |
| **空闲超时** | RUNNING 状态超过 15 分钟无新 Span → 自动判定完成 |

Trace 完成后自动触发：Monitor 检测 → Evaluation → Trust Score 重算 → 通知。
同一 `traceId` 可多次上报，Span 会追加到同一链路；重复的 `spanId` 被忽略。

### 3.5 内容采集与脱敏

`input` / `output` 的采集模式由服务端 `content_capture_mode` 控制：

| 模式 | 行为 | 适用环境 |
| --- | --- | --- |
| `NONE` | 完全忽略 input/output 字段 | 高敏感生产环境 |
| `HASHED` | 只保存 SHA-256 哈希，不保存原文 | 需要关联但不需阅读 |
| `REDACTED` | **脱敏后存储**（默认） | 推荐生产模式 |
| `FULL` | 原文存储（截断 8192 字符） | 开发/受控环境 |

REDACTED 自动掩码：手机号 `***REDACTED-PHONE***`、邮箱 `***REDACTED-EMAIL***`、身份证 `***REDACTED-ID***`、Secret（sk-/Bearer/AKIA）`***REDACTED-SECRET***`，正文截断 8192 字符。

### 3.6 错误码

| 错误码 | 说明 | 处理 |
| --- | --- | --- |
| `0` | 成功 | — |
| `40101` | Ingest Key 无效或已吊销 | 检查 Key，或在「Agent 接入」页重新签发 |
| `40000` | 请求格式错误 | 检查 JSON 格式与必填字段（响应 `rejected` 数组带逐条原因） |
| `50301` | 服务限流 | 稍后重试 |

上报失败不影响 Agent 主流程：ingest 接口异步非阻塞，收到即返回；失败 Span 被丢弃并计数。

---

## 4. Python 自研 Agent 接入

### 4.0 官方 SDK（推荐）

```bash
pip install emonlens              # 或 monorepo 本地: pip install -e ./emonlens-sdk-python
export EMONLENS_HOST="http://emonlens:8300"
export EMONLENS_INGEST_KEY="tk-xxxx"
export EMONLENS_AGENT="my-agent"
```

**方式 A：@observe 装饰器（业务代码零侵入，推荐）**

```python
from emonlens import observe, update_current

@observe()                        # 根 span：入参/返回值自动记录
def answer_question(question: str) -> str:
    docs = retrieve(question)     # 自动成为子 span
    return generate(question)

@observe(span_type="retrieval")
def retrieve(q): ...

@observe(span_type="llm")
def generate(q):
    ...
    update_current(model="gpt-4o-mini", input_tokens=512, output_tokens=344)
    return answer
```

**方式 B：Trace 上下文管理器（手动控制）**

```python
from emonlens import Trace

with Trace(session_id="chat-42", input="用户问题") as tr:
    tr.llm("call_model", model="gpt-4o-mini", input=prompt, output=answer,
           input_tokens=512, output_tokens=344)
    tr.tool("query_db", input=sql, output=rows)
```

零第三方依赖；后台批量上报、重试、flush、OpenAI/LangChain 集成详见 `emonlens-sdk-python/README.md`。未配置环境变量时 SDK 自动 no-op（零副作用）。

**方式 C：运行时拉取提示词（版本化管理 Prompt）**

提示词不必硬编码在代码里——在「提示词」页建好版本后，Agent 运行时按标签拉取，并在 Trace 上自动建立 prompt 关联（哪个版本、被哪些调用用到，平台侧自动统计）：

```python
import emonlens

p = emonlens.get_prompt("credit-assistant")                    # production 标签；60s TTL 缓存
text = p.compile(question="工单 #1024 的处理进度是多少？")          # 渲染 {{变量}}（未提供的保留原样）

with emonlens.Trace(prompt=p, session_id="chat-42") as tr:     # trace 自动关联 prompt 版本
    tr.llm("call_model", input=text, output=answer)

latest = emonlens.get_prompt("credit-assistant", label="latest", force_refresh=True)   # 绕过缓存
```

拉取失败（未配置、网络不通、prompt 不存在）一律返回 `None` 且不抛错，不阻塞业务——
未 init 时调用同样安全。数据面接口 `GET /api/v1/ingest/prompts/{name}/resolve`
（X-Trust-Key 鉴权）也可被其他语言/网关直接复用。

### 4.1 轻量客户端（无 SDK 时，复制即用）

```python
# emonlens.py — EmonLens 上报客户端
import os, time, uuid, threading, requests

KEY      = os.environ["EMONLENS_INGEST_KEY"]           # tk-xxxx-xxxx-xxxx
ENDPOINT = os.environ.get("EMONLENS_HOST", "http://localhost:8300") + "/api/v1/ingest/events"
AGENT    = os.environ.get("EMONLENS_AGENT", "my-agent")

def _ts(t):  # epoch 秒 → ISO-8601
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(t))

def _post(spans):
    try:
        r = requests.post(ENDPOINT, json=spans,
                          headers={"X-Trust-Key": KEY}, timeout=5)
        r.raise_for_status()
    except Exception as e:
        print("[emonlens] 上报失败(不影响业务):", e)   # 观测失败不拖垮业务

class Trace:
    """用法：
    with Trace(session_id="chat-42", input="用户问题") as tr:
        tr.llm("call_model", model="gpt-4o-mini", input=prompt, output=answer,
               input_tokens=512, output_tokens=344)
        tr.tool("query_db", input="sql...", output="rows...")
    """
    def __init__(self, session_id=None, user_id=None, environment="production", input=None):
        self.trace_id = uuid.uuid4().hex[:16]
        self.session_id, self.user_id, self.env = session_id, user_id, environment
        self.base = dict(traceId=self.trace_id, agentKey=AGENT, agentVersion="v1",
                         sessionId=session_id, userId=user_id, environment=environment)
        self.t0 = time.time()
        self.root = dict(self.base, spanId="root", spanType="agent", spanName="invoke_agent",
                         startTime=_ts(self.t0), input=input)
        self._spans, self._lock = [self.root], threading.Lock()

    def _add(self, span_type, name, start, end, **kw):
        with self._lock:
            self._spans.append(dict(self.base, spanId=uuid.uuid4().hex[:8],
                parentSpanId="root", spanType=span_type, spanName=name,
                startTime=_ts(start), endTime=_ts(end), **kw))

    def llm(self, name, model, input=None, output=None, input_tokens=0, output_tokens=0, error=None):
        now = time.time()
        self._add("llm", name, now, now, modelName=model, input=input, output=output,
                  inputTokens=input_tokens, outputTokens=output_tokens,
                  status="ERROR" if error else "OK", errorType=error)

    def tool(self, name, input=None, output=None, error=None):
        now = time.time()
        self._add("tool", name, now, now, toolName=name, input=input, output=output,
                  status="ERROR" if error else "OK", errorType=error)

    def retrieval(self, name, input=None, output=None):
        now = time.time()
        self._add("retrieval", name, now, now, input=input, output=output)

    def __enter__(self): return self
    def __exit__(self, *exc):
        has_err = exc[0] is not None
        self.root["endTime"] = _ts(time.time())
        self.root["status"] = "ERROR" if has_err else "OK"
        if has_err: self.root["errorType"] = type(exc[1]).__name__
        threading.Thread(target=_post, args=(self._spans,), daemon=True).start()
```

### 4.2 包一层 OpenAI SDK（自动记录 LLM span）

```python
from openai import OpenAI
from emonlens import Trace

client = OpenAI()

def ask(prompt: str, session_id: str):
    with Trace(session_id=session_id, input=prompt) as tr:
        resp = client.chat.completions.create(
            model="gpt-4o-mini",
            messages=[{"role": "user", "content": prompt}])
        tr.llm("call_model", model="gpt-4o-mini",
               input=prompt, output=resp.choices[0].message.content,
               input_tokens=resp.usage.prompt_tokens,
               output_tokens=resp.usage.completion_tokens,
               # 缓存命中量一定要报：否则命中部分按全价计费，成本虚高数倍
               # （各家字段不同：OpenAI 在 prompt_tokens_details.cached_tokens，
               #   DeepSeek 等兼容端点习惯直挂 prompt_cache_hit_tokens）
               cached_input_tokens=(resp.usage.prompt_tokens_details.cached_tokens
                                    if resp.usage.prompt_tokens_details else 0))
        return resp.choices[0].message.content
```

同一 `session_id` 的多次调用自动在「会话」页聚合成对话流。

**更省事：用 drop-in 集成替换 import**，缓存量、模型、耗时、ERROR 全部自动记录，无需手写 `tr.llm(...)`：

```python
from emonlens.integrations.openai import OpenAI      # 替换 from openai import OpenAI
client = OpenAI()                                     # 其余用法完全不变
resp = client.chat.completions.create(...)            # 自动产生 llm span
```

已包装实例则用 `from emonlens.integrations.openai import wrap_openai; wrap_openai(client)`。
缓存字段的三种形态（`prompt_tokens_details.cached_tokens` / `prompt_cache_hit_tokens` /
`cached_tokens` 直挂）都会识别；调用抛错时 span 标 ERROR 且异常继续抛给业务方，不会吞。

**成本怎么算**：未命中输入按「输入价」、命中部分按「缓存价」（未配置则取输入价 10%，对齐
OpenAI/DeepSeek 惯例）、输出按「输出价」，三者单价在「模型价格」页配置。价格可以后配——
创建/修改价格会立即重算该模型全部已有 span。

### 4.3 Java（Spring RestTemplate）

无 SDK 的语言直接用 HTTP 上报，遥测失败必须不影响业务主流程：

```java
@Service
public class AgentTelemetryService {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String EMONLENS_URL = "http://emonlens:8300/api/v1/ingest/events";
    private static final String TRUST_KEY = "tk-xxxx-xxxx-xxxx"; // 从配置读取

    public void reportAgentRun(String traceId, String prompt, String completion,
                               String model, long latencyMs) {
        String now = java.time.Instant.now().toString();
        String end = java.time.Instant.now().plusMillis(latencyMs).toString();

        List<Map<String, Object>> spans = List.of(
            Map.of(
                "traceId", traceId, "spanId", "root", "agentKey", "my-agent",
                "spanType", "agent", "spanName", "invoke_agent",
                "startTime", now, "endTime", end, "input", prompt, "output", completion
            ),
            Map.of(
                "traceId", traceId, "spanId", "llm-1", "parentSpanId", "root",
                "agentKey", "my-agent", "spanType", "llm", "spanName", "call_model",
                "modelName", model, "startTime", now, "endTime", end,
                "inputTokens", 512, "outputTokens", 344,
                "input", prompt, "output", completion
            )
        );

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trust-Key", TRUST_KEY);
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            restTemplate.postForEntity(EMONLENS_URL, new HttpEntity<>(spans, headers), String.class);
        } catch (Exception e) {
            log.warn("telemetry report failed: {}", e.getMessage());  // 不抛出
        }
    }
}
```

生产环境建议后台线程异步发送（单批 ≤ 500 条），失败只记日志。
Java 侧完整的字段语义、错误码与脱敏规则见上文 §3.2 / §3.5 / §3.6，与语言无关。

---

## 5. Codex CLI 接入（hooks，已验证）

> 📄 **独立操作手册**：[`Codex接入操作手册.md`](Codex接入操作手册.md)（同目录）——含安全追加注册脚本、离线验证、故障排查与卸载步骤，可直接发给其他开发者照做。

Codex CLI 支持 hooks（`~/.codex/hooks.json`，stdin 传事件 JSON）。一次 Codex 会话 = 一条
EmonLens Trace：根 span（`codex_session`）记录用户提问与最终回答，每个工具调用一个 tool 子 span。

**① 安装 SDK 并一键注册**（推荐，桥接由 SDK 维护）：

```bash
pip install emonlens        # 或本地：pip install -e ./emonlens-sdk-python
emonlens-codex-setup --host http://你的EmonLens地址:8300 --key tk-你的IngestKey
```

该命令写 `~/.emonlens/config.json`（GUI 版 Codex 读不到 shell 环境变量，这是主通道），并**安全追加**
注册 5 个事件到 `~/.codex/hooks.json`（保留已有钩子、重复执行幂等），钩子命令指向 SDK 所在解释器的绝对路径。
覆盖 SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / Stop。

**② 桥接行为**（`emonlens.codex_hooks`，无需手写）：

- 每轮 `UserPromptSubmit` 开新 trace，traceId 由 `turn_id` 派生——**resume / 上下文压缩时再次触发
  SessionStart 也不会把同轮次的工具调用与 llm/root 拆到不同 trace**
- `PreToolUse` + `PostToolUse` 配对计算工具真实耗时（毫秒精度）
- 任何失败静默，不影响 Codex 本身

**③ 验证**：跑一次 Codex 对话，「调用链路」出现 `agentKey=codex-cli` 的 Trace——
根 span（codex_session，input=你的提问）+ 每个 tool 调用一个子 span（含工具名、参数与耗时）。

> 不想装 SDK 时的备选：`emonlens-sdk-python/examples/codex_hooks.py` 是一个零依赖单文件实现，
> 但它**不随 SDK 更新**（无 turn_id 分组、无毫秒精度、不支持 PreToolUse），仅建议临时试用。
> 事件字段名可能随 Codex 版本变化，桥接已做防御性解析；如字段变化，检查 `_pick()` 的候选键。

## 6. Claude Code 接入（hooks，一键）

```bash
pip install emonlens
emonlens-claude-setup --host http://你的EmonLens地址:8300 --key tk-你的Key
```

该命令写 `~/.emonlens/config.json`（GUI 版 Claude Code 读不到 shell 环境变量，这是主通道），
并向 `~/.claude/settings.json` 注册 5 个钩子
（SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / Stop，保留已有钩子，重复执行幂等）。

之后正常使用 Claude Code 即可：
- 根 span（agentKey=claude-code）：input=你的提问，output=**自动从会话记录（transcript）提取的最终回答**
- 每次工具调用一个 tool 子 span（toolName / tool_input / tool_response）
- `PreToolUse` + `PostToolUse` 配对得出工具真实耗时（毫秒精度）；Claude 事件不带 `turn_id`，
  同会话的 span 靠桥接状态归到同一条 trace

> 手动注册 / transcript 提取原理 / 故障排查见 [`Claude接入操作手册.md`](Claude接入操作手册.md)（同目录）。

## 7. ZCode 接入（hooks，一键）

```bash
pip install emonlens
emonlens-zcode-setup --host http://你的EmonLens地址:8300 --key tk-你的Key
```

该命令写 `~/.emonlens/config.json`，并向 `~/.zcode/cli/config.json` 注册 6 个钩子
（SessionStart / UserPromptSubmit / PreToolUse / PostToolUse / PostToolUseFailure / Stop），
同时设置 `hooks.enabled = true`（ZCode 配置文件的钩子默认禁用）。保留已有钩子与顶层
`plugins` 配置，重复执行幂等。**注册后需新开 ZCode 会话或重启客户端。**

之后正常使用 ZCode 即可：
- 根 span（agentKey=zcode）：input=你的提问，output=Stop 事件直接携带的最终回答
- 每次工具调用一个 tool 子 span；工具失败（PostToolUseFailure）上报 ERROR span
- 事件原生带 `turnId`，同轮 tool / llm / root 自动落同一条 trace；
  每轮 tokens 从 `~/.zcode/cli/rollout/model-io-<session_id>.jsonl` 聚合，含缓存命中断

> 配置格式差异（`hooks.events` + `enabled`）、离线验证、故障排查与卸载见
> [`ZCode接入操作手册.md`](ZCode接入操作手册.md)（同目录）。

## 8. DeepSeek Harness 接入（回放式，非钩子）

DSH **没有对外钩子**，它把每个会话写成本地 `~/.dsh/sessions/**/session.v4.jsonl.zstd`。所以接入方式是**回放**：
解压会话文件、把**一轮对话转成一条 trace**，再上报。

```bash
pip install <emonlens仓库>/emonlens-sdk-python   # Python < 3.14 时另需 pip install zstandard
emonlens-dsh-sync --host http://你的EmonLens地址:8300 --ingest-key tk-你的Key --limit 1 --dry-run
emonlens-dsh-sync --host http://你的EmonLens地址:8300 --ingest-key tk-你的Key --limit 3
```

之后在「调用链路」看到 `agentKey=dsh` 的 trace：根 span `dsh_turn`（input=提问、output=该轮最终回答），
子 span 是该轮的 `assistant_turn`（llm，带 tokens 与模型）和每个工具调用（tool，带真实耗时与参数/结果）。
同一 DSH 会话的轮次按 `sessionId` 聚合，可在「会话」里回放多轮。重复同步幂等（服务端按 traceId+spanId 去重）。

> 与 hooks 协议的差别（**不是实时**）、字段映射、自动化触发、隐私开关（`--no-content`）与已知限制见
> [`DeepSeekHarness接入操作手册.md`](DeepSeekHarness接入操作手册.md)（同目录）。

## 9. 生产建议

| 主题 | 建议 |
| --- | --- |
| **批量上报** | 单批 ≤ 500 条；Agent 侧用后台线程异步发送（见 4.1），观测失败不影响业务 |
| **环境分流** | 开发接 `environment: "staging"`，生产 `production`；「调用链路」可按环境筛选对比 |
| **内容脱敏** | 工作空间「内容采集模式」支持 REDACTED/HASHED；手机号/邮箱/身份证自动打码 |
| **成本核算** | 在「模型价格」页维护各模型 $/1M tokens；未上报 cost 时自动按 token 估算 |
| **质量评估** | 建 EXPRESSION/REGEX/TRAJECTORY 评估器（确定性，零依赖）自动评分；LLM_JUDGE 需配置 `emonlens.eval.llm.base-url` |
| **数据保留** | 后端按 `emonlens.retention.*` 配置自动清理过期 span/事件，无需人工运维 |

---

## 10. 接入检查清单

- [ ] 签发 Ingest Key 并配置 `X-Trust-Key`
- [ ] 根 span（`spanType=agent`）+ 子 span 结构正确，`parentSpanId` 挂对
- [ ] `sessionId` 传了（会话聚合）、`environment` 传了（环境分流）
- [ ] llm span 带 `modelName` + tokens（成本自动估算）
- [ ] 「调用链路」能看到 Trace，「会话」能看到聚合，「用量成本」有数据
- [ ] 故意造一次失败（status=ERROR）→「事件告警」规则命中

---

*文档版本 V1.0 · 对应 EmonLens 0.1.0 · 接口变更见 `docs/` 目录*
