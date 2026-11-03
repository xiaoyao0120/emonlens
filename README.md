<h1 align="center">
  <img src="docs/assets/emonlens-logo.png" height="150" />
</h1>


<p align="center">
  <b>Agent Trust 可信观测平台</b><br/>
  看得见每一次调用，算得清每一分成本，说得清一个 Agent 是否可信。
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-11-orange" alt="Java 11" />
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7-6DB33F" alt="Spring Boot 2.7" />
  <img src="https://img.shields.io/badge/Vue-3.5-42B883" alt="Vue 3.5" />
  <img src="https://img.shields.io/badge/Python-3.9%2B-3776AB" alt="Python 3.9+" />
  <img src="https://img.shields.io/badge/Docker-ready-2496ED" alt="Docker ready" />
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue" alt="Apache-2.0" />
</p>

<p align="center">
  <a href="#-快速开始">快速开始</a> ·
  <a href="#-核心能力">核心能力</a> ·
  <a href="#-它是怎么工作的">架构</a> ·
  <a href="#-与-langfuse--langsmith-的差异">对比</a> ·
  <a href="#-部署形态">部署</a> ·
  <a href="#-文档">文档</a> ·
  <a href="#-参与贡献">参与贡献</a>
</p>

---

EmonLens 是一个**开源、可自托管**的 AI Agent 可信观测平台。它不只回答"Agent 做了什么"，还回答三个更难的问题：

- **花了多少钱？** —— Token、成本、缓存命中率按 trace / agent / user / model 逐层下钻。
- **行为是否健康？** —— 重复调用、死循环、轨迹越界、步数失控，内置检测，无需自己写规则。
- **这个 Agent 可信吗？** —— 五维信任分 + Finding → Incident 的告警闭环，给出可追溯的结论。

> **English TL;DR** — EmonLens is an open-source, self-hostable Agent Trust observability platform:
> trace ingest (HTTP / OTLP / Python SDK), cache-aware cost analytics, four monitor kinds plus
> built-in loop detection, four evaluator types (expression / regex / LLM judge / tool trajectory), dataset regression experiments, annotation queues,
> incident management, and a five-dimension trust score. Java 11 + Spring Boot + MySQL on the server,
> Vue 3 for the console, and a zero-dependency Python SDK. Docs are currently in Chinese.

## ✨ 核心能力

- **Trace 全链路观测** —— 支持 HTTP/JSON、OTLP，或直接用 Python SDK 接入；10 种 span 类型（agent / workflow / llm / tool / retrieval / guardrail / handoff / memory / approval / business_action）；Observations 树 + 瀑布图、Trace 双条对比、会话聚合、用户洞察、标签与评论、CSV 导出。
- **成本与用量** —— 区分输入 / 输出 / 缓存命中三类 token，缓存命中默认按输入价 10% 计费；模型价格后配会自动回溯重算历史 span；用量页按 model / agent / user 下钻。
- **行为检测** —— 四类监控规则（Validation / Trajectory / Metric / Evaluation）+ 内置行为循环检测（完全重复、循环块、步数超限）；Validation 的 `where` 表达式跑在只读 SpEL 沙箱里，仅放行 10 个字符串/集合方法。
- **自动评估** —— 四类评估器（表达式 / 正则 / LLM Judge / 工具轨迹）、Judge 模板版本化与 claim 逐条判分、13 个 Agentic/RAG/多轮指标预设、评估套件、按比例或按错误强制采样、人工标注队列；数据集支持检索上下文/期望工具/多轮样本、CSV/JSONL 批量导入导出（dry-run/atomic/contentHash 幂等）、LLM 合成候选（critic 过滤 + 人工批准）、Trace 回放与批量实验，两个 run 之间做逐样本 diff；Judge 结果缓存按 sample+模板+模型严格命中，成本按 generation/judge/evaluator 分账。
- **评测与准入闭环** —— 质量总览；数据集 revision 与样本用途/风险/切分；可深链的运行报告；Judge 人工盲审校准；Pairwise 成对偏好；Gate `PASS / FAIL / BLOCKED` 与 `verification.usable`；质量案例回归闭环；Domain Pack；外部业务实验证据。
- **信任评分** —— performance / behavior / output / security / context 五维，按权重做**几何平均**（任一维塌陷都会显著拉低总分）；存在 CRITICAL Finding 时状态直接判为 CRITICAL；每次评分留存快照。
- **告警闭环** —— Finding 按指纹归并为 Incident，`OPEN → ACKNOWLEDGED → RESOLVED` 状态机；邮件与 Webhook 双通道，Webhook 带 HMAC-SHA256 签名；事件表原子认领、退避重试、死信可人工重投。
- **Prompt 与 Playground** —— 版本管理、`production` / `latest` 标签（production 同名唯一）、行级 diff、版本用量统计、每次 resolve 留痕；Playground 支持 A/B 双跑、数据集批量试跑、一键存为提示词，以及**矩阵实验**（数据集 × prompt 版本 × 模型，启动前成本预估硬确认、网格并发、逐格评估、网格 CSV、逐样本 Diff 给出改进/退步/有得有失）；矩阵列可一键**升格为正式实验**并在 run 上绑定 prompt 版本。
- **CI 门禁** —— 官方 composite Action（`action.yml`）：跑评测 + 校验 Gate + 把结论与基线 diff 幂等回写成 PR 评论；`baseline` 运行标签与 `commitSha` 让「哪次提交、按哪条基线放行」可查。
- **平台能力** —— 工作空间隔离、27 个权限串（3 套预设角色）、操作审计、开放 API（`X-Api-Token` + scope）、数据保留策略、运行时配置。

## 🚀 快速开始

### 1. 启动服务（Docker Compose）

```bash
git clone https://github.com/<your-org>/emonlens.git && cd emonlens
cp .env.example .env
# 编辑 .env：JWT 密钥 / Ingest Key / admin 密码 / 数据库口令 全部换成强随机值
docker compose up -d --build
```

打开 <http://localhost:8300>，用 `admin` + 你在 `.env` 里设置的 `EMONLENS_ADMIN_PASSWORD` 登录。

首次启动会自动建库并写入系统种子（工作空间 / RBAC / 菜单 / admin / 通知模板），**不包含任何演示业务数据**——平台从空开始，数据全部由 Agent 上报产生。

> 标准形态默认连 MySQL（`localhost:3306/emonlens`，口令经 `SPRING_DATASOURCE_PASSWORD` 注入）。
> 只想本地体验、不想装 MySQL？`SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run`（JDK 11+）以内存 H2
> 启动演示模式，登录 `admin / 123456`，数据不持久化，启动日志会明确提示演示凭据。生产部署一律走
> `prod` profile（Compose / tar.gz 已默认），门禁会强制轮换全部默认值。

### 2. 接入你的 Agent（3 个环境变量 + 1 个装饰器）

```bash
pip install emonlens   # PyPI 发布前：pip install -e ./emonlens-sdk-python
```

```bash
export EMONLENS_HOST="http://localhost:8300"
export EMONLENS_INGEST_KEY="<Agent 接入页签发的 Ingest Key>"
export EMONLENS_AGENT="my-agent"
```

```python
from emonlens import observe

@observe(span_type="retrieval")
def retrieve(question: str) -> list[str]:
    ...  # 你的检索实现

@observe(span_type="llm")
def generate(question: str, docs: list[str]) -> str:
    ...  # 你的 LLM 调用

@observe(name="answer")            # 根 span，默认 agent 类型
def answer(question: str) -> str:
    return generate(question, retrieve(question))
```

SDK 后台批量上报（2s / 100 条），业务零阻塞；网络失败指数退避，Ingest Key 失效时告警一次并停止上报；未配置环境变量时进入 **no-op 模式**，不产生任何副作用。

### 3. 打开 Traces 页面

调用几次之后，`/traces` 里就能看到完整链路、token、成本与耗时。给 Agent 配上监控规则与评估器后，`/alerts` 会出现 Finding 与 Incident，`/dashboard` 会给出信任分。

## 🔌 接入方式

| 方式 | 入口 | 适用场景 |
| --- | --- | --- |
| Python SDK | `pip install emonlens` | 自研 Agent，`@observe` / `Trace` 手动埋点 |
| OpenAI drop-in | `from emonlens.integrations.openai import OpenAI` | 已用 OpenAI SDK，换一行 import 即完成埋点 |
| LangChain | `EmonLensCallbackHandler` | 现有 Chain / Agent 挂 callback |
| CLI Agent hooks | `emonlens-codex-setup` / `emonlens-claude-setup` / `emonlens-zcode-setup` | Codex CLI / Claude Code / ZCode 一键接管，自动按轮次分组 |
| 评测 SDK / CLI | `emonlens.evaluate()` / `emonlens eval run` / `emonlens gate check` | CI 提交数据集评测、消费 Gate、非零退出码 |
| OTLP/HTTP | `POST /api/v1/ingest/otlp/v1/traces` | 任意 OpenTelemetry Collector / 其他语言 |
| HTTP/JSON | `POST /api/v1/ingest/events` | 自定义上报，单批 ≤ 500 span |

```python
# 把 "from openai import OpenAI" 换成：
from emonlens.integrations.openai import OpenAI
client = OpenAI()                       # 其余代码不变，自动记录 model / tokens / 耗时

# 手动埋点（prompt / answer / sql / rows 来自你的业务代码）
from emonlens import Trace
with Trace(session_id="chat-1", input="用户问题") as tr:
    tr.llm("call_model", model="gpt-4o-mini", input=prompt, output=answer,
           input_tokens=100, output_tokens=50, cached_input_tokens=60)
    tr.tool("query_db", input=sql, output=rows)
```

## 🧠 它是怎么工作的

```text
Agent / SDK / OTel Collector
   │  X-Trust-Key
   ▼
emonlens-server（单进程）
   ├─ 接入层   校验 → 令牌桶限流 → 内存队列（50k）
   ├─ 落库层   批处理线程 → span 幂等写入 → trace 汇总 → 成本估算
   ├─ 事件表   TRACE_COMPLETED / FINDING / INCIDENT / SCORE …（DB 原子认领）
   │            ├─ 检测：4 类监控规则 + 内置循环检测
   │            ├─ 评估：表达式 / 正则 / LLM Judge
   │            ├─ 评分：五维加权几何平均
   │            └─ 通知：EMAIL / WEBHOOK（HMAC 签名）
   └─ 控制台   内嵌 Vue 3 UI + REST API
   │
   ▼
MySQL 8（元数据 + 治理面；span 数据面可选 StarRocks）
```

**信任分公式**：五个维度按权重取几何平均，任一维度塌陷都会显著拉低总分。

```text
overall = performance^0.25 × behavior^0.25 × output^0.30 × security^0.10 × context^0.10
罚分维度：CRITICAL +40 / HIGH +15 / WARNING +5，score = 100 / (1 + penalty / 80)
状态：存在 CRITICAL Finding → CRITICAL；overall ≥ 85 → TRUSTED；否则 AT_RISK
```

**为什么是几何平均？** 算术平均会让四个 95 分的维度掩盖一个 20 分的安全维度；几何平均不会。一个"性能很好但行为失控"的 Agent，不应该拿到高分。

## 🆚 与 Langfuse / LangSmith 的差异

三个项目都做 Agent 可观测，但切入点不同：

| | EmonLens | Langfuse | LangSmith |
| --- | --- | --- | --- |
| 定位 | 可观测 **+ 行为治理 + 信任结论** | LLM 工程平台（观测 / Prompt / 评估） | LLM 调试与评估平台 |
| 开源协议 | Apache-2.0 | MIT（部分企业功能除外） | 闭源商业 SaaS |
| 自托管 | ✅ 单 jar / Compose | ✅ | 企业版 |
| 服务端技术栈 | Java 11 + Spring Boot + MySQL（span 可选 StarRocks） | TypeScript + ClickHouse | 托管服务 |
| Trace / 会话 / 成本 | ✅ | ✅ | ✅ |
| Prompt 管理 / Playground | ✅ 版本 + label + resolve 留痕 | ✅ | ✅ |
| Playground 矩阵 + Diff | ✅ | ⚠️ 跑实验 | ⚠️ Playground Evaluate |
| 评估 / 数据集实验 | ✅ 4 类评估器 + claim 判分 | ✅ | ✅ |
| CI 门禁（Action + PR 评论） | ✅ Gate + verification 口径 | — | ⚠️ recipes |
| 行为循环 / 轨迹越界检测 | ✅ 内置 | 需自定义 | 需自定义 |
| 五维信任分 | ✅ | — | — |
| Incident 状态机 | ✅ | — | — |

**怎么选？**

- 团队已经在用 LangChain、需要最成熟的托管体验 → **LangSmith**。
- 需要成熟的 LLM 工程全家桶（Prompt / 评估 / 数据集 / 丰富集成）、偏好 TypeScript 技术栈 → **Langfuse**。
- 需要把"Agent 是否可信"变成可量化的结论，希望在自有 Java/MySQL 体系内私有化部署，且在意行为安全与成本审计 → **EmonLens**。

EmonLens 在 Trace、Prompt、评估、数据集这些基础能力上参考了同类产品的成熟做法；差异化的部分是把**行为检测、事件闭环、信任评分**做成一等公民，而不是让用户自己拼装。

## 📦 部署形态

| 形态 | 命令 / 产物 | 适用 |
| --- | --- | --- |
| Docker Compose | `docker compose up -d --build` | 单机标准交付，自带 MySQL |
| tar.gz 分发件 | `make dist` → `emonlens-dist/target/emonlens-0.2.0-bin.tar.gz` | 不能跑 Docker 的服务器，自备 MySQL 8.0 |
| 单 jar | `make build` → `emonlens-server/target/emonlens-server-0.2.0.jar` | 自有调度 / 容器平台，自行管理进程 |
| 离线镜像 | `make build && make image-offline` | 构建机无法访问 Maven/npm 仓库 |

生产部署前请过一遍[部署手册](docs/EmonLens部署运维手册.md)的安全基线：

- 所有凭据使用强随机值；`prod` profile 启动门禁会拒绝弱 JWT 密钥、dev ingest key、默认 admin 口令。
- Compose 默认把端口绑定到 `127.0.0.1`；对外暴露请前置 nginx + TLS，反代后置 `EMONLENS_TRUST_XFF=true`。
- 登录防爆破与接口限流是**实例内存态**，多实例部署请由网关承担全局限流。
- span 落库走进程内队列（上限 50000），**请保证优雅停机（SIGTERM）**；`kill -9` 会丢失队列内数据。
- 后台任务靠数据库原子认领，多实例可直接加副本；但 api 实例也建议保持 `EMONLENS_WORKER_ENABLED=true`。

## 📁 仓库结构

```text
emonlens/
├── emonlens-server/      Spring Boot 单部署单元：REST API + ingest + 检测/评估/评分引擎
│                         + 6 个后台线程组 + 内嵌控制台；sql/ 下是 MySQL 与 StarRocks DDL
├── emonlens-ui/          Vue 3 + Vite 6 + naive-ui 控制台（构建产物写入 server static/）
├── emonlens-sdk-python/  零依赖 Python SDK：@observe / Trace / OpenAI / LangChain / CLI hooks
├── emonlens-common/      ApiResult / ErrorCode / SHA-256 等共享契约
├── emonlens-dist/        发布分发件（release profile 激活）：打包 tar.gz
├── docs/                 操作手册
├── scripts/              smoke.sh（13 项冒烟）、verify_all.py（12 域端到端）、compose.sh
├── Dockerfile            多阶段构建（UI → jar → 运行时镜像）
├── docker-compose.yml    MySQL 8 + emonlens
└── Makefile              build / test / dist / image / release / smoke / verify
```

## 🛠 本地开发

前置：JDK 11+、Maven 3.6+、Node 20+、Python 3.9+。

```bash
# 后端：全量测试跑在内存 H2 上，不依赖本地 MySQL
make test

# 前端：dev server 5175，/api 代理到 8300
cd emonlens-ui && npm ci && npm run dev

# 一键打包（UI 内嵌 + fat jar）
make build

# 发布分发件（fat jar + 启停脚本 + SQL + 配置样例 + SDK + 文档）
make dist

# Python SDK：109 个测试
make sdk-test

# 对运行中的实例做黑盒验收
make smoke  PASS=<admin 密码>                 # 13 项冒烟
make verify PASS=<admin 密码>                 # 12 个域端到端（幂等，可重复执行）
```

## 🗺 Roadmap

- **Context Trust 数据面** —— 接入外部数据质量/上下文质量结果，让 `context` 维度从"只看 Finding"变成"可接收外部证据"。
- **IM 通知渠道** —— 飞书 / 钉钉（枚举已预留，当前实现 EMAIL / WEBHOOK）。
- **K8s / Helm** —— 目前是单机 Compose 与 tar.gz；Helm Chart 是下一步。
- **社区共建** —— 更多语言 SDK、评估器与集成，欢迎开 Issue 认领。

## 🤝 参与贡献

欢迎 Issue、讨论与 PR。开始之前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。

- **报告 Bug**：附上版本、复现步骤、相关日志（请先脱敏，不要贴 Ingest Key / JWT / 数据库口令）。
- **功能建议**：说明使用场景与期望行为，而不只是实现方式。
- **提交代码**：一个 PR 只做一件事；改行为请补测试；若影响使用方式，请同步更新 `docs/` 下受影响的文档。
- **文档**：描述已实现的现状，操作文档必须"照做即可"；同一条事实只在一处维护。

## 🔒 安全

- 密码使用 PBKDF2WithHmacSHA256（20000 轮 + 16 字节随机盐）；JWT 为自实现 HS256，登出即写入吊销黑名单。
- 27 个权限串 + 工作空间行级隔离；跨工作空间访问统一返回"资源不存在"，不泄露资源存在性。
- 采集层支持 `NONE / HASHED / REDACTED / FULL` 四种内容模式，默认 `REDACTED`，入库前按正则脱敏密钥、身份证、手机号、邮箱。
- Validation 规则的 `where` 跑在只读 SpEL 沙箱：无类型引用、无构造器、无静态方法，仅 10 个只读方法。
- 容器以非 root 用户运行；Actuator 仅暴露 `/actuator/health`。

**已知缺口**（欢迎贡献）：通知渠道配置与 LLM apiKey 目前明文入库（设计意图是 AES 加密）；用户密码暂无复杂度策略。

## 📚 文档

| 我想… | 看这里 |
| --- | --- |
| 部署到服务器 | [部署运维手册](docs/EmonLens部署运维手册.md) |
| 日常使用平台 | [操作指南](docs/EmonLens操作指南.md) |
| 做评测与准入闭环 | [评测与准入操作指南](docs/EmonLens评测与准入操作指南.md) |
| 用 v2 样本/导入导出/合成/CLAIMS/TRAJECTORY/CLI | [评测增强功能使用指南](docs/EmonLens评测增强功能使用指南.md) |
| 把 Agent 数据接进来 | [Agent 接入教程](docs/Agent接入教程.md) |
| 接入 Codex CLI / Claude Code / ZCode | [Codex 手册](docs/Codex接入操作手册.md) · [Claude 手册](docs/Claude接入操作手册.md) · [ZCode 手册](docs/ZCode接入操作手册.md) |
| Python SDK 详细用法 | [SDK README](emonlens-sdk-python/README.md) |

## 📄 License

[Apache License 2.0](LICENSE) © EmonLens contributors
