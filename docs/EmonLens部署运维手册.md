# EmonLens 部署运维手册

> 适用版本：0.1.0。标准交付形态有两种：**Docker Compose**（§2，自带 MySQL）与 **tar.gz 单机分发件**（§3，自备 MySQL、无 Docker）。K8s/Helm 为演进方向。

## 1. 架构与部署形态

```
浏览器 ──► nginx(TLS, 可选) ──► emonlens-server :8300 ──► MySQL 8.0
Agent/SDK ──(X-Trust-Key)────►  /api/v1/ingest/*
```

- **单部署单元**：API、控制台前端（静态资源内嵌）、后台任务线程组都在 `emonlens-server` 进程内。
  6 个后台线程及其关停后果（`EMONLENS_WORKER_ENABLED=false` 会把它们**全部**关掉）：

  | 线程 | 职责 | 关停后 |
  |---|---|---|
  | span-batch-runner | 消费 ingest 内存队列、落库 span 并推进 trace 组装 | span 只进内存队列，**停机才刷盘**；运行期不可见 |
  | event-poll-runner | 认领消费 `et_event`（监测→Finding→Incident→通知） | 该实例不消费；**其他开着 worker 的实例会认领**（DB 原子认领），多实例下不影响 |
  | trace-sweep-runner | 收口 idle 的 RUNNING trace | 该实例不扫；同上，依赖开着 worker 的实例 |
  | metric-runner | 指标规则扫描 | 同上 |
  | retention-runner | 保留策略清理 | 同上 |
  | score-runner | 信任分重算；启动后约 5 秒先做一次兜底重算，之后按周期执行 | 同上 |

  **结论**：只有 `span 落库 + trace 组装` 是进程内的、关掉就只收不落；其余 5 项靠 DB 认领，
  多实例天然分摊。所以：
  - 多实例部署想让 API 与后台处理分开时，**api 实例也请保持默认 `true`**，靠 `et_event` 认领分摊，
    不要关掉 span 落库线程。
  - 若整个部署里**没有任何**开着 worker 的实例，事件/扫描/清理/评分/收口全都不会发生。
  - 单实例关 worker 的唯一合理场景：低频上报、或可接受"停机才可见"的临时观测。
- **已知边界**：登录防爆破/全局限流为实例内存态。多实例部署时，登录限流与接口限流建议由前置网关承担。
- **优雅停机**：进程停止时自动把 ingest 内存队列中未落盘的 span 刷入数据库（api-only 实例同样生效；
  落库失败批次丢弃并告警，最多尝试 3 轮）。滚动重启不丢已接收数据。
  ⚠️ api-only 实例若被 `kill -9` 或 OOMKill，队列内 span 全部丢失——请保证优雅停机（SIGTERM）。
- **容量监控**：`GET /api/v1/observations/stats`（admin JWT）返回队列水位 / 容量 / 累计接收 / 累计丢弃 / 限流计数——
  `queueSize` 持续 > 0 或 `droppedTotal` 增长说明处理速率不足，需降低 SDK 采样或扩容；
  `rateLimitedTotal` 增长说明某 workspace 触达 ingest 限流额度（日志有对应 workspace ID）；
  SDK 侧最终失败批次本地落盘于 `~/.emonlens/failed/*.json`（可人工恢复）。
- **Ingest 限流**：每 workspace 令牌桶（桶容量 = 每分钟额度，允许突发，按秒回填），超限整批拒绝并返回
  `HTTP 429 + Retry-After`；额度用 `EMONLENS_INGEST_RATE_LIMIT`（默认 20000 spans/分钟，0=不限）调整。
  SDK 收到 429 按服务端指示退避重试本批，不会丢数据也不会落盘失败件。

## 2. 首次部署（Docker Compose）

```bash
cp .env.example .env
# 必改项（全部强随机）：EMONLENS_JWT_SECRET / EMONLENS_INGEST_KEY /
# EMONLENS_ADMIN_PASSWORD / MYSQL_ROOT_PASSWORD / EMONLENS_DB_PASSWORD
docker compose up -d --build
docker compose ps                 # 两个容器 healthy 即就绪
curl -s http://localhost:8300/actuator/health
```

首次启动自动完成：

1. MySQL 初始化执行 `emonlens-server/sql/emonlens-mysql.sql`（64 张表 + 系统种子：工作空间/RBAC/菜单/admin/通知模板，**不含演示业务数据**）。
2. 应用以 `prod` profile 启动，**启动门禁（SecurityGateRunner）** 校验：
   - `EMONLENS_JWT_SECRET` 非空且 ≥16 字符；
   - `EMONLENS_INGEST_KEY` 不得为 `dev-trust-key-001`（升级库中的历史 dev key 会被自动禁用）；
   - admin 若仍为种子密码 `123456`，用 `EMONLENS_ADMIN_PASSWORD`（≥8 位）**自动轮换**后放行，
     未提供该变量则拒绝启动。
3. 首启为空平台：Agent/Trace/评估/告警等业务数据全部由 SDK/Agent 上报产生（dev 模式启动时仅自动注册 seed ingest key，不灌演示数据）。

登录后第一件事：**用 `EMONLENS_INGEST_KEY` 的明文值配置 SDK/Agent**（平台只存哈希，不回显明文）。

镜像发布物：`make image-offline` 成功后导出 `artifacts/emonlens-server-<版本>.tar.gz`
（构建出的镜像若被 Docker 存储回收，tar 文件不受影响）。交付/迁移时：

```bash
gunzip -c artifacts/emonlens-server-0.1.0.tar.gz | docker load
docker tag emonlens/server:0.1.0 emonlens/server:latest   # 可选
```

镜像构建两条路径（`make image` / `make image-offline`）：

- 默认在容器内完整构建（UI + jar），需构建机访问 Maven/npm 仓库；
- 构建机网络受限时先 `make build` 宿主机打包，再 `make image-offline` 直接装配 jar。
- 运行时镜像不含 curl/tzdata：容器健康检查用 bash `/dev/tcp` 直连本机 8300 断言 HTTP 200，
  时区由 JVM `-Duser.timezone` 承担（Java 自带 tzdb，无需系统 tzdata）。
- **构建卡在 `load metadata`**：BuildKit 解析基础镜像元数据需访问 Docker Hub；国内网络受限时
  给 Docker Desktop 配置 registry mirror（Settings → Docker Engine 加 `registry-mirrors`）。
- **registry mirror 返回 403/超时**：可从可达 registry 预热基础镜像后重试完整构建，例如：
  `docker pull public.ecr.aws/docker/library/node:20-alpine`
  `docker pull public.ecr.aws/docker/library/maven:3.9-eclipse-temurin-11`
  `docker pull eclipse-temurin:11-jre-jammy`
  然后 `docker tag ...` 成官方名称，并执行 `make image DOCKER_BUILD_ARGS='--pull=false'`。
  若只做单机 jar 部署，则无需完整镜像构建；先 `make build` 再 `make image-offline`。
- **只想产出运行镜像**：`make image-offline` 只需要 `eclipse-temurin:11-jre-jammy`；
- **镜像被回收/构建中途失败**：先看 `docker system df`——镜像未挂容器时可能被回收、磁盘吃紧
  （Images+Containers 超 60GB）也会让拉取失败；清理后重试 `make image-offline`。
  宿主机 jar（`emonlens-server/target/emonlens-server-0.1.0.jar`）始终与代码同步，
  镜像仅是发布物，重建不影响已在运行的服务。

## 3. 单机 tar.gz 部署（无 Docker）

适用于不能运行 Docker 的服务器。分发件由 `make dist` 构建：

```bash
make dist
# 产物：emonlens-dist/target/emonlens-0.2.0-bin.tar.gz（约 31MB）
```

前置：**JDK/JRE 11+**、**MySQL 8.0**（utf8mb4）。分发件不含数据库。

### 3.1 目录结构

```text
emonlens-0.1.0/
├── bin/emonlens.sh        启动/停止/状态/日志
├── bin/setenv.sh          环境初始化（被启动脚本 source）
├── conf/                  配置样例（emonlens.env.example、application-prod.yml.example）
├── lib/emonlens-server-0.1.0.jar    Spring Boot fat jar（含内嵌控制台）
├── sql/emonlens-mysql.sql         建库 DDL + 系统种子
├── sql/emonlens-starrocks.sql     可选：span 数据面 OLAP
├── sdk/                   Python SDK wheel/sdist
├── scripts/smoke.sh        13 项发布冒烟
├── scripts/verify_all.py   101 项全功能黑盒验证（含 SDK 评测通道）
├── docs/                  设计文档与操作手册
├── VERSION
└── README.md
```

### 3.2 部署步骤

```bash
tar -xzf emonlens-0.1.0-bin.tar.gz
cd emonlens-0.1.0

# 1) 建库并导入（一次性）
mysql -h127.0.0.1 -uroot -p -e \
  "CREATE DATABASE IF NOT EXISTS emonlens DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -h127.0.0.1 -uroot -p emonlens < sql/emonlens-mysql.sql

# 2) 配置（所有 change-me 换强随机值）
cp conf/emonlens.env.example conf/emonlens.env
vi conf/emonlens.env

# 3) 启停
bin/emonlens.sh start      # 后台启动并等待 /actuator/health 通过
bin/emonlens.sh status
bin/emonlens.sh logs 200
bin/emonlens.sh restart
bin/emonlens.sh stop       # SIGTERM 优雅停机（刷 ingest 队列）
```

配置解析约定（`conf/emonlens.env`）：`KEY=VALUE` 每行一项，`#` 开头为注释；
值含空格时加引号（`JAVA_OPTS="-Xms1g -Xmx2g"`），不加引号也会按整行解析；
`bin/setenv.sh` 只做文本解析、不执行文件内容。

### 3.3 与 Compose 形态的差异

| 项 | Compose | tar.gz |
|---|---|---|
| MySQL | 容器内自动初始化 | **自备**，手工执行 `sql/emonlens-mysql.sql` |
| 端口 | `EMONLENS_PORT` 仅做端口映射，应用固定 8300 | `EMONLENS_PORT` 直接传给 `--server.port` |
| 日志 | `/app/logs/emonlens.log`（卷） | 默认 `<APP_HOME>/logs/emonlens.log` |
| 后台线程 | `EMONLENS_WORKER_ENABLED` | 同左 |
| 启动门禁 | prod profile 校验 | 同左（脚本在启动前先做一遍同口径校验，报错更早） |

### 3.4 systemd 托管（推荐）

```ini
# /etc/systemd/system/emonlens.service
[Unit]
Description=EmonLens Agent Trust Platform
After=network.target mysql.service

[Service]
Type=forking
User=emonlens
WorkingDirectory=/opt/emonlens-0.1.0
ExecStart=/opt/emonlens-0.1.0/bin/emonlens.sh start
ExecStop=/opt/emonlens-0.1.0/bin/emonlens.sh stop
PIDFile=/opt/emonlens-0.1.0/run/emonlens.pid
Restart=on-failure
TimeoutStartSec=120
TimeoutStopSec=90
# JVM 参数也可写在 conf/emonlens.env 的 JAVA_OPTS 里
Environment="JAVA_OPTS=-Xms1g -Xmx2g"

[Install]
WantedBy=multi-user.target
```

`Type=forking` 依赖脚本写出的 pid 文件；`TimeoutStopSec` 需大于脚本的 60s 优雅停机等待。

## 4. 环境变量参考

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `EMONLENS_JWT_SECRET` | ✅ | — | 控制面 JWT 签名密钥，≥16 字符强随机 |
| `EMONLENS_INGEST_KEY` | ✅ | — | 数据面上报凭据（SDK 侧 `EMONLENS_INGEST_KEY`） |
| `EMONLENS_ADMIN_PASSWORD` | ✅ | — | 首启 admin 密码轮换值；改密后可从 .env 移除 |
| `EMONLENS_DB_PASSWORD` / `MYSQL_ROOT_PASSWORD` | ✅ | — | 数据库口令；tar.gz 本机启动也会映射到 `SPRING_DATASOURCE_PASSWORD` |
| `EMONLENS_DB_USER` | | `emonlens` | 应用库账号 |
| `EMONLENS_DB_HOST` / `EMONLENS_DB_PORT` / `EMONLENS_DB_NAME` | | `127.0.0.1` / `3306` / `emonlens` | 数据库地址（tar.gz 形态常用；Compose 内部走 `SPRING_DATASOURCE_URL`） |
| `EMONLENS_DB_POOL` | | `20` | Hikari 连接池上限 |
| `EMONLENS_PORT` / `EMONLENS_BIND` | | `8300` / `127.0.0.1` | 服务端口 / 绑定地址（对外暴露必须前置反代） |
| `EMONLENS_RATE_LIMIT` | | `600` | 控制面全局限流（次/分钟，实例内存固定窗口）；0=不限 |
| `EMONLENS_INGEST_RATE_LIMIT` | | `20000` | 每 workspace ingest 限流（spans/分钟，令牌桶，超限 429 + Retry-After）；0=不限 |
| `EMONLENS_TRUST_XFF` | | `false` | 可信反代后置 true：登录防爆破按 `X-Forwarded-For` 首段取 IP |
| `EMONLENS_LOG_LEVEL` | | `INFO` | `io.emonlens` 日志级别 |
| `JAVA_OPTS` | | `-Xms512m -Xmx1024m` | JVM 参数 |
| `EMONLENS_WORKER_ENABLED` | | `true` | 置 `false` 则关闭本实例后台线程（span 只进内存队列、停机才刷盘），见「部署形态」 |
| `EMONLENS_EVAL_CACHE_ENABLED` | | `false` | LLM Judge 结果缓存；严格 key + TTL，命中不产生成本 |
| `EMONLENS_DATASET_SYNTHESIS_ENABLED` | | `false` | 允许 LLM 合成数据集候选；需先配置系统设置 → LLM 端点 |
| `EMONLENS_OTLP_MAX_BODY_CHARS` | | `33554432` | OTLP/HTTP-JSON 接入请求体字符上限（超出 400）；collector 大批次时可调大 |
| `EMONLENS_LOG_FILE` | | Compose `/app/logs/emonlens.log`；tar.gz `<APP_HOME>/logs/emonlens.log` | 文件日志路径 |

登录防爆破细项（`application-prod.yml` 可覆盖）：连续失败 5 次锁 15 分钟、单 IP 每分钟 30 次尝试上限。

## 5. 安全基线（本轮生产化已内置）

| 项 | 状态 |
|---|---|
| 控制面认证 | JWT(HS256) + PBKDF2 密码；**登出即吊销**（黑名单表 `et_auth_token_blacklist`，多实例生效） |
| RBAC | 35 个控制器中 28 个带类级 `@RequiresPermission`；其余 7 个为登录接口、数据面（Ingest/X-Trust-Key）、开放 API（X-Api-Token + scope）、SPA 转发、登录可读的元数据/自作用域接口（`/sys/menus`、`/sys/workspaces`，代码内注明） |
| 数据面 | IngestKey（sha256 入库）+ OTLP 端点开关；开放 API 走 X-Api-Token + scope |
| 登录防护 | 单 IP 限流 + 账号×IP 失败锁定；登录走独立防护不占全局窗口 |
| 门禁 | prod 拒绝：缺密钥/弱密钥/dev ingest key/默认 admin 密码 |
| Actuator | 仅暴露 `/actuator/health`（`show-details: never`） |
| 登录页 | 不再展示默认账号口令 |
| 内容安全 | `capture-mode`（NONE/HASHED/REDACTED/FULL）+ 内容保留天数清理 |

**仍建议在网关层完成**：TLS 终止、按 IP 的全局限流、WAF。接口错误统一 HTTP 200 + 业务码体
（前端/SDK 已适配）属平台既有契约，网关健康检查请用 `/actuator/health`。

## 6. 日常运维

```bash
make logs                 # 跟随 server 日志
make ps                   # 健康状态
docker compose logs mysql # 数据库日志
```

`make ps` / `make logs` / `make down` 不需要 `.env`：compose 的 `${VAR:?}` 必填校验会把
只读命令一起挡在外面（缺 .env 时报 `required variable MYSQL_ROOT_PASSWORD is missing a value`），
这三个目标会自动改用 `scripts/compose-required.env` 占位凭据完成插值，不影响已在运行的容器。
启停类命令（`make up`、`docker compose restart`）仍要求真实 `.env`，避免用公开的占位值把服务拉起来。

- **日志**：控制台（stdout）+ 文件 `/app/logs/emonlens.log`，按天 + 100MB 滚动，保留 14 天/总量 2GB。
- **备份**：`docker compose exec mysql mysqldump -uroot -p$MYSQL_ROOT_PASSWORD --single-transaction emonlens > backup.sql`（建议每日 cron）。
- **恢复**：`docker compose exec -T mysql mysql -uroot -p$MYSQL_ROOT_PASSWORD emonlens < backup.sql`。
- **升级**：`git pull && docker compose up -d --build`；schema 变更先执行独立增量 SQL，
  再重启后端。用户试点（V1.0 最小重构）使用
  `emonlens-server/sql/migrations/2026-10-19-pilot-dataset-run.sql`，执行前先看脚本开头的重复明细预检。
- **清理策略**（默认自动）：span 30 天、内容 7 天、事件记录 7 天，可经 application-prod.yml 覆盖。

- **tar.gz 部署的备份/恢复**：数据库凭据见 `conf/emonlens.env`。

  ```bash
  # 备份（建议每日 cron；保留 off-site 副本）
  mysqldump -h127.0.0.1 -uemonlens -p --single-transaction --routines --triggers emonlens > backup-$(date +%F).sql

  # 恢复（建议先 bin/emonlens.sh stop，避免恢复期间写入）
  bin/emonlens.sh stop
  mysql -h127.0.0.1 -uemonlens -p emonlens < backup-2026-10-31.sql
  bin/emonlens.sh start
  ```

- **tar.gz 部署的升级**：先备份数据库；替换 `lib/emonlens-server-*.jar` 与 `docs/`；
  如版本带增量 SQL，先按迁移脚本说明执行；然后 `bin/emonlens.sh restart`。
  不要只替换 jar 而跳过 schema 变更。

### 6.1 用户试点环境（V1.0 最小重构）

- 启用试点 profile：`SPRING_PROFILES_ACTIVE=prod,pilot`（本地体验可用 `demo,pilot`）。
- 试点行为：旧同步入口 `POST /datasets/{id}/runs` 拒绝新评测，只接受
  `POST /datasets/{id}/runs/async` + `Idempotency-Key`；默认单实例同一时刻只允许一个运行
  （`emonlens.dataset-run.max-active-runs` / `worker-threads` 可按实例调大，默认均为 1，
  调大后多 run 会排队或并行，不再保证单活动；`max-active-runs-per-workspace` 可限制单团队占用）；单批最多 20 条；自动 LLM 评估强制关闭。
- 已有 MySQL 库先执行增量 SQL：

  ```bash
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-19-pilot-dataset-run.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-19-eval-usability.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-eval-suite.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-criterion-results.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-run-lease.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-run-resume.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-run-budget-ledger.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-run-token-budget.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-run-admission-slot.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-gate-created-at-micros.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-judge-calibration.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-judge-double-blind.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-dataset-blueprint.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-business-evidence.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-pairwise.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-quality-case.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-judge-protocol.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-dataset-health-snapshot.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-domain-pack.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-domain-pack-binding.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-business-evidence-signature.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-business-multiple-comparisons.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-pairwise-multi-judge.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-auto-rubric-audit.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-pairwise-position-randomization.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-auto-rubric-generation.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-auto-rubric-stability.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-pairwise-human-review.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-dataset-item-quarantine.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-20-auto-rubric-cv.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-21-eval-task-ledger.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-27-tenant-isolation.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-27-incident-open-fp.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-content-unique.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-annotation-item-unique.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-score-scope-precision.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-event-handler-delivery.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-10-28-retention-indexes.sql
  # V1.1 评测能力增强
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-02-eval-case-v2.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-09-judge-template.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-16-eval-cache.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-23-dataset-synthesis.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-30-dataset-item-hash-index.sql
  # 0.2.0：Playground 矩阵 / prompt 版本绑定 / CI 基线（含 SDK 评测器任务类型列）
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-12-07-eval-task-type.sql
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-10-playground-matrix-prompt-binding.sql
  # R2-1：评估规则 v2（规则对象 / 命中账本 / Rewind 作业 / eval_result 增列）
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-11-eval-rule-v2.sql
  # R2-2：端用户反馈（预签 token / 类型化反馈）
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-12-end-user-feedback.sql
  # R3-3：评估结果结论原因码落库（JUDGE_DISAGREEMENT / JUDGE_MEAN_BELOW_MIN_SCORE 等）
  mysql -u<user> -p <db> < emonlens-server/sql/migrations/2026-11-06-eval-result-reason-code.sql
  ```

  脚本均可重复执行；pilot 脚本开头会输出重复明细预检，若返回行或唯一键创建失败，
  停止迁移并人工处理，不要删除历史记录。新建库直接执行 `sql/emonlens-mysql.sql`
  （已包含新列与唯一键）。
- 部署后重启后端，并刷新浏览器以加载新前端。
- 试点页面只展示“检查结果与待复核项”，不展示五维信任总分，不生成上线许可。

## 7. 健康与排障

| 现象 | 排查 |
|---|---|
| 容器重启循环，日志见"prod 模式…" | 门禁拒绝：检查 JWT_SECRET 长度 / INGEST_KEY 非 dev 值 / 设置 EMONLENS_ADMIN_PASSWORD |
| 登录返回 `50301` | 触发登录限流/锁定，等待窗口或从可信 IP 操作 |
| 登录返回 `40101` | 凭据错误；admin 忘记密码 → 用 EMONLENS_ADMIN_PASSWORD 重启自动轮换（仅当仍为 seed 密码时）或手工 UPDATE |
| SDK 上报 401/无数据 | `X-Trust-Key` 与平台 Ingest Key 不匹配；确认使用 `EMONLENS_INGEST_KEY` 明文 |
| `/actuator/health` 503 | MySQL 未就绪或连接数耗尽；`docker compose logs mysql` |
| 页面 404 | 前端产物未内嵌：`make build` 后重新打包，或 compose `--build` |
| **中文全是乱码**（工作空间名/菜单/角色/通知模板） | 种子数据被双重编码。`sql/emonlens-mysql.sql` 必须以 `SET NAMES utf8mb4;` 开头——MySQL 官方 CLI 的 `character_set_client` 默认 latin1，docker-entrypoint 用它加载 init 脚本时会把 UTF-8 字节当 latin1 再转存。确认存在该行后 `docker compose down -v && make up` 重建卷；校验：`docker exec emonlens-mysql mysql -uroot -p emonlens -N -e "SELECT HEX(name) FROM et_workspace"`，正确值为 `E9BB98E8AEA4...`（单次编码）而非 `C3A9C2BB...`（双重编码） |

## 8. 本地开发约定

- 后端测试自洽（内存 H2），无需本地 MySQL：`make test`。
- `application.yml` 默认连 MySQL（`localhost:3306/emonlens`）；口令用 `SPRING_DATASOURCE_PASSWORD`
  环境变量注入，不要把真实口令写进仓库。无 MySQL 的体验环境用 `SPRING_PROFILES_ACTIVE=demo`
  启动内存 H2（自动建表灌种子，数据不持久化）。
- schema 三处同步：`emonlens-server/sql/emonlens-mysql.sql`（生产）、
  `emonlens-server/src/main/resources/schema-h2.sql`（测试/演示）、实体与 Mapper。改表三处一起改。
- 发布构建与冒烟：`make release`（全量测试 + UI 内嵌打包 + 镜像 tag 0.1.0）、
  `scripts/smoke.sh`（对运行中的实例做健康检查/登录/核心 API 冒烟，详见仓库根 README）。
