# 页面操作 E2E（Playwright）

后端 HTTP 全绿 ≠ 页面能用。这套用例在真实浏览器里走用户路径，断言落在 DOM 上。

## 覆盖范围

| 用例 | 页面操作 | 断言 |
| --- | --- | --- |
| 登录 | 填用户名/密码 → 点登录 | 跳转 `/dashboard`、侧边栏与内容区可见 |
| 新建数据集 | 数据集页 → ＋新建数据集 → 填 Key → 创建 | 成功提示或列表出现新行，且只出现一行 |
| Trace 详情 | 列表点行 → 详情页 | URL 切到 `/traces/:id`，span 名与输出渲染 |
| 评估器质量 | 点「扫描并写入告警」 | 出现「已写入 …」反馈（真实写入 Finding） |
| 实验排行 | 选数据集 → 看散点与排行 | SVG 散点至少 1 个点、排行表有百分比行 |
| 矩阵实验 / 告警 | 直接打开两个页面 | 页面骨架与关键文案渲染 |
| 数据集详情 | 打开刚建的数据集 | 页面显示数据集 Key |
| 系统设置（模型链路） | 打开「LLM 端点」页签 → 填 Base URL / API Key / 模型 → 保存 | 出现「已保存…」提示 |
| 试跑（模型链路） | 填 System Prompt 与 Prompt → ▶ 运行 | 输出卡片出现假端点的固定回复 `fake-llm-ok` |

每个页面级用例都额外断言**无 console error**（过滤 favicon / ResizeObserver / 静态资源 404 之类噪声）。

## 前置

应用已在跑，且同时提供 UI 与 API（demo profile 即可）：

```bash
# UI 静态资源来自 emonlens-server/src/main/resources/static/，先构建 UI 再打包
docker run --rm -v "$PWD/emonlens-ui:/app" -w /app node:20-alpine \
  sh -c "node node_modules/vite/bin/vite.js build"
docker run --rm -v "$PWD:/work" -w /work -v emonlens_m2:/root/.m2 \
  maven:3.9-eclipse-temurin-17 mvn -B -o -DskipTests package
docker run -d --name emonlens-ui-e2e-app -p 8300:8300 \
  -e SPRING_PROFILES_ACTIVE=demo -e EMONLENS_SECURITY_MODE=dev \
  -v "$PWD/emonlens-server/target/emonlens-server-0.2.0.jar:/app/app.jar" \
  maven:3.9-eclipse-temurin-17 java -jar /app/app.jar
```

演示凭据：`admin/123456`、ingest key `dev-trust-key-001`（`SPRING_PROFILES_ACTIVE=demo` 时由 seed 注册）。

## 运行

本机（需 Node 20 + Playwright 浏览器）：

```bash
cd scripts/e2e-ui
npm install
EMONLENS_BASE_URL=http://127.0.0.1:8300 npx playwright test
npx playwright show-report report     # 失败时看 trace / 截图
```

容器（推荐，无需本机装浏览器）——Playwright 镜像自带匹配版本的 Chromium：

```bash
docker network create emonlens-e2e 2>/dev/null || true
docker run --rm --network emonlens-e2e \
  -v "$PWD/scripts/e2e-ui:/work" -w /work \
  -e EMONLENS_BASE_URL=http://<app-container-name>:8300 \
  mcr.microsoft.com/playwright:v1.47.2-jammy \
  sh -c "npm install --no-audit --no-fund && npx playwright test"
```

镜像 tag 必须与 `package.json` 里 `@playwright/test` 版本一致，否则浏览器路径不匹配。

## 模型链路怎么跑（不需要真实模型）

`fixtures/fake_llm.py` 是一个 OpenAI 兼容的最小假端点，回复固定带 `fake-llm-ok` 前缀：

```bash
docker network create emonlens-uie2e 2>/dev/null || true
docker run -d --name fake-llm --network emonlens-uie2e \
  -v "$PWD/fixtures/fake_llm.py:/fake.py:ro" python:3.11-alpine python /fake.py
```

用例顺序上，**先**在系统设置页把 Base URL 指向 `http://fake-llm:8080/v1` 并保存，
**再**到试跑页运行 —— 这样验证的是"UI 表单保存 → 设置持久化 → LLM 客户端 → 响应渲染"整条链路，
而不是靠环境变量把端点注进去。跑的时候传 `FAKE_LLM_URL`（默认 `http://fake-llm:8080/v1`）。

## 设计取舍

- **单 worker 串行**：所有用例共享同一个演示工作空间，并行会互相干扰（容量、限流、列表顺序）。
- **数据用 API 准备、断言只用页面**：这样失败能明确指向"UI 坏了"而不是"造数失败"。
- **不追求覆盖率**：只挑会真正坏掉用户路径的操作（登录、建集、看详情、触发扫描、看图表），不做逐字段校验。
- 已知未覆盖：矩阵实验的完整提交链路（需要 LLM 端点）、系统设置页的表单保存、领域包上传。
  这几项要么依赖外部模型端点，要么属于低频配置，留给后续按需补。
