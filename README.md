<p align="center">
  <img src="docs/assets/emonlens-logo.png" height="120" alt="EmonLens" />
</p>

<h3 align="center">EmonLens</h3>

<p align="center">开源的 AI Agent 可观测与评测平台</p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-11-orange" alt="Java 11" />
  <img src="https://img.shields.io/badge/Spring%20Boot-2.7-6DB33F" alt="Spring Boot 2.7" />
  <img src="https://img.shields.io/badge/Vue-3.5-42B883" alt="Vue 3.5" />
  <img src="https://img.shields.io/badge/Python-3.9%2B-3776AB" alt="Python 3.9+" />
  <img src="https://img.shields.io/badge/License-Apache--2.0-blue" alt="Apache-2.0" />
</p>

<p align="center">
  <a href="#快速开始">快速开始</a> ·
  <a href="#接入-agent">接入 Agent</a> ·
  <a href="#功能">功能</a> ·
  <a href="#文档">文档</a> ·
  <a href="#参与贡献">参与贡献</a>
</p>

EmonLens 采集 Agent 的 trace 与 span，做成本核算、行为检测、离线评估和上线准入，最后落到 Finding / Incident 与五维信任分。

## 快速开始

```bash
git clone https://github.com/datavane/emonlens.git
cd emonlens
cp .env.example .env      # JWT 密钥、Ingest Key、admin 密码、数据库口令改成强随机值
docker compose up -d --build
```

打开 <http://localhost:8300>，用 `admin` 和 `.env` 里的 `EMONLENS_ADMIN_PASSWORD` 登录。首次启动会自动建库并写入系统种子数据。

不想装 MySQL 时用内存 H2：

```bash
SPRING_PROFILES_ACTIVE=demo mvn spring-boot:run   # JDK 11+，账号 admin / 123456，数据不持久化
```

生产环境走 `prod` profile（Compose 与 tar.gz 默认），启动时会拒绝默认凭据。

## 接入 Agent

```bash
pip install emonlens       # PyPI 发布前：pip install -e ./emonlens-sdk-python
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

| 方式 | 用法 |
| --- | --- |
| OpenAI drop-in | 把 `from openai import OpenAI` 换成 `from emonlens.integrations.openai import OpenAI` |
| LangChain | 挂 `EmonLensCallbackHandler` |
| CLI Agent hooks | `emonlens-codex-setup` / `emonlens-claude-setup` / `emonlens-zcode-setup` |
| 手动埋点 | `with Trace(session_id=..., input=...) as tr: tr.llm(...)` |
| OTLP/HTTP | `POST /api/v1/ingest/otlp/v1/traces` |
| HTTP/JSON | `POST /api/v1/ingest/events`，单批 ≤ 500 span |

## 功能

### 观测与成本

- Trace 树、瀑布图、trace 对比、会话聚合、用户洞察与 CSV 导出，覆盖 10 种 span 类型
- 输入 / 输出 / 缓存命中三类 token 分开计费；用量按 model / agent / user 下钻

### 检测与告警

- 四类监控规则（VALIDATION / TRAJECTORY / METRIC / EVALUATION），内置循环检测
- Finding 按指纹归并为 Incident，邮件与 Webhook（HMAC-SHA256 签名）通知

### 评估与准入

- 四类评估器，配合数据集实验、人工标注与 Judge 盲审校准
- Gate 输出 `PASS` / `FAIL` / `BLOCKED` 与 `verification.usable`，CI 只消费 PASS

### 信任分

五维加权几何平均，任一维度塌陷都会拉低总分：

```text
overall = performance^0.25 × behavior^0.25 × output^0.30 × security^0.10 × context^0.10
```

## 架构

```text
Agent / SDK / OTel Collector
   │  Ingest Key
   ▼
emonlens-server（单进程）
   ├─ 接入   校验 → 令牌桶限流 → 有界队列（默认 50000）
   ├─ 落库   批处理线程 → span 幂等写入 → trace 汇总 → 成本估算
   ├─ 事件   TRACE_COMPLETED / FINDING / INCIDENT / SCORE …（DB 原子认领）
   │          ├─ 检测：4 类监控规则 + 循环检测
   │          ├─ 评估：表达式 / 正则 / LLM Judge / 工具轨迹
   │          ├─ 评分：五维加权几何平均
   │          └─ 通知：邮件 / Webhook
   └─ 控制台  内嵌 Vue 3 前端 + REST API
                  │
                  ▼
           MySQL 8（元数据与治理面；span 数据面可选 StarRocks）
```

## 与 Langfuse、LangSmith 的区别

| | EmonLens | Langfuse | LangSmith |
| --- | --- | --- | --- |
| 定位 | 观测 + 行为检测 + 准入与信任结论 | LLM 工程平台 | LLM 调试与评估 |
| 开源协议 | Apache-2.0 | MIT（部分企业功能除外） | 闭源 SaaS |
| 自托管 | 单 jar / Compose | 支持 | 企业版 |
| 服务端技术栈 | Java 11 + Spring Boot + MySQL | TypeScript + ClickHouse | 托管服务 |
| 内置循环与轨迹检测 | 支持 | 需自定义 | 需自定义 |
| 五维信任分 | 支持 | 无 | 无 |
| Incident 状态机 | 支持 | 无 | 无 |

## 部署

| 形态 | 命令 / 产物 |
| --- | --- |
| Docker Compose | `docker compose up -d --build` |
| tar.gz 分发件 | `make dist` → `emonlens-dist/target/emonlens-0.1.0-bin.tar.gz` |
| 单 jar | `make build` → `emonlens-server/target/emonlens-server-0.1.0.jar` |
| 离线镜像 | `make build && make image-offline` |

生产部署请先过一遍[部署运维手册](docs/EmonLens部署运维手册.md)。两个最容易踩的点：Compose 默认把端口绑在 `127.0.0.1`，对外暴露要前置 nginx + TLS；span 先进进程内队列（默认 50000），需要保证优雅停机，`kill -9` 会丢队列里的数据。

## 文档

- [部署运维手册](docs/EmonLens部署运维手册.md)
- [操作指南](docs/EmonLens操作指南.md)
- [评测与准入操作指南](docs/EmonLens评测与准入操作指南.md)
- [评测增强功能使用指南](docs/EmonLens评测增强功能使用指南.md)
- [Agent 接入教程](docs/Agent接入教程.md)
- [Codex](docs/Codex接入操作手册.md) / [Claude Code](docs/Claude接入操作手册.md) / [ZCode](docs/ZCode接入操作手册.md) 接入手册
- [Python SDK](emonlens-sdk-python/README.md)

## 开发

```bash
make test        # 后端测试，跑在内存 H2 上，不依赖本地 MySQL
make sdk-test    # Python SDK 测试
make build       # 构建 UI 并打进 fat jar
make dist        # tar.gz 分发件
make smoke PASS=<admin 密码>
make verify PASS=<admin 密码>
```

前端开发：`cd emonlens-ui && npm ci && npm run dev`，5175 端口，`/api` 代理到 8300。

## 参与贡献

欢迎 Issue 与 PR，提交前请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。安全问题请勿公开披露细节。

## License

[Apache License 2.0](LICENSE) © EmonLens contributors
