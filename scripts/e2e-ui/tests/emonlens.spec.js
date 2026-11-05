const { test, expect, request } = require('@playwright/test');

/**
 * EmonLens 页面操作 E2E（浏览器级）。
 *
 * 覆盖真实用户路径：登录 → 新建数据集 → Trace 列表进详情 → 评估器质量扫描（会写 Finding）
 * → 实验排行（选数据集、看散点与排行）→ 矩阵实验 / 告警页可打开 → 关键页面无 console 错误。
 *
 * 数据准备走 API（ingest / dataset / evaluator / run），断言全部落在页面 DOM 上——
 * 这是我们此前缺失的一层：后端 HTTP 全绿并不代表页面能用。
 */

const ADMIN = { username: 'admin', password: '123456' };
const INGEST_KEY = 'dev-trust-key-001';
const RUN_STAMP = Date.now();
const AGENT = `ui-e2e-agent-${RUN_STAMP}`;
const TRACE_ID = `ui-e2e-trace-${RUN_STAMP}`;
const DATASET_KEY = `ui-e2e-ds-${RUN_STAMP}`;
// 实验排行用例另建一个数据集：与"页面新建数据集"用例的 key 必须不同，否则重复建集会被拒
const BOARD_DATASET_KEY = `ui-e2e-board-${RUN_STAMP}`;
// 跨 describe 共享：第一个 describe 里建好的数据集，模型链路 describe 的矩阵用例要复用
let sharedDatasetId = null;
let sharedEvaluatorKey = null;

const IGNORED_CONSOLE = [/favicon/i, /ResizeObserver/i, /Failed to load resource/i];

function localDateTime(offsetSeconds = 0) {
  const d = new Date(Date.now() + offsetSeconds * 1000);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
}

function collectConsoleErrors(page) {
  const errors = [];
  page.on('console', (msg) => {
    if (msg.type() !== 'error') return;
    const text = msg.text();
    if (IGNORED_CONSOLE.some((re) => re.test(text))) return;
    errors.push(text);
  });
  page.on('pageerror', (err) => errors.push(`pageerror: ${err.message}`));
  return errors;
}

async function login(page) {
  await page.goto('/login');
  await page.getByPlaceholder('用户名').fill(ADMIN.username);
  await page.getByPlaceholder('密码').fill(ADMIN.password);
  await page.getByRole('button', { name: /登\s*录/ }).click();
  await page.waitForURL(/\/dashboard/, { timeout: 30_000 });
  await expect(page.locator('.base-page, .dashboard-page, main').first()).toBeVisible();
}

async function apiLogin(ctx) {
  const res = await ctx.post('/api/v1/auth/login', {
    data: { username: ADMIN.username, password: ADMIN.password },
  });
  expect(res.ok(), `login http ${res.status()}`).toBeTruthy();
  const body = await res.json();
  expect(body.code, `login body ${JSON.stringify(body)}`).toBe(0);
  return body.data.token;
}

async function ingestTrace(ctx, traceId) {
  const res = await ctx.post('/api/v1/ingest/events', {
    headers: { 'X-Trust-Key': INGEST_KEY },
    data: [
      {
        traceId,
        spanId: 'root',
        agentKey: AGENT,
        agentVersion: 'v1',
        spanType: 'agent',
        spanName: 'invoke_agent',
        startTime: localDateTime(-5),
        endTime: localDateTime(-4),
        input: '页面 E2E：帮我查一下订单状态',
        output: '您的订单已发货，预计明天送达。',
      },
    ],
  });
  expect(res.status(), `ingest http ${res.status()}`).toBe(200);
}

async function waitTrace(ctx, token, traceId) {
  for (let i = 0; i < 40; i += 1) {
    const res = await ctx.get(`/api/v1/traces/${traceId}`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const body = await res.json();
    const status = body?.data?.summary?.status;
    if (status === 'COMPLETED') return;
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`trace ${traceId} not completed in time`);
}

async function seedDatasetWithRun(ctx, token) {
  const auth = { Authorization: `Bearer ${token}` };
  const ds = await (
    await ctx.post('/api/v1/datasets', { headers: auth, data: { datasetKey: BOARD_DATASET_KEY } })
  ).json();
  expect(ds.code, JSON.stringify(ds)).toBe(0);
  const datasetId = ds.data.id;

  const item = await (
    await ctx.post(`/api/v1/datasets/${datasetId}/items`, {
      headers: auth,
      data: { traceId: TRACE_ID, input: '请帮我查一下订单状态', expectedOutput: '您的订单已发货' },
    })
  ).json();
  expect(item.code, JSON.stringify(item)).toBe(0);

  const ev = await (
    await ctx.post('/api/v1/evaluators', {
      headers: auth,
      data: {
        evaluatorKey: `ui-e2e-expr-${RUN_STAMP}`,
        type: 'EXPRESSION',
        spec: { type: 'EXPRESSION', passWhen: 'span_count >= 0' },
      },
    })
  ).json();
  expect(ev.code, JSON.stringify(ev)).toBe(0);

  const run = await (
    await ctx.post(`/api/v1/datasets/${datasetId}/runs/async`, {
      headers: { ...auth, 'Idempotency-Key': `ui-e2e-run-${RUN_STAMP}` },
      data: { evaluatorIds: [ev.data.id], mode: 'TRACE' },
    })
  ).json();
  expect(run.code, JSON.stringify(run)).toBe(0);
  const runId = run.data.runId;

  for (let i = 0; i < 60; i += 1) {
    const detail = await (
      await ctx.get(`/api/v1/datasets/${datasetId}/runs/${runId}`, { headers: auth })
    ).json();
    const status = detail?.data?.run?.status;
    if (status === 'COMPLETED' || status === 'FAILED') break;
    await new Promise((r) => setTimeout(r, 500));
  }
  return { datasetId, evaluatorKey: `ui-e2e-expr-${RUN_STAMP}` };
}

test.describe.serial('EmonLens 页面操作', () => {
  /** @type {import('@playwright/test').APIRequestContext} */
  let ctx;
  let token;
  let datasetId;

  test.beforeAll(async ({ baseURL }) => {
    ctx = await request.newContext({ baseURL });
    token = await apiLogin(ctx);
    await ingestTrace(ctx, TRACE_ID);
    await waitTrace(ctx, token, TRACE_ID);
  });

  test.afterAll(async () => {
    await ctx?.dispose();
  });

  test('登录后进入总览，侧边栏与内容区可见', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);
    await expect(page).toHaveURL(/\/dashboard/);
    await expect(page.locator('body')).toContainText('总览');
    await expect(page.locator('text=/调用链路|评估结果|数据集/').first()).toBeVisible();
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('通过页面表单新建数据集并出现在列表', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);
    await page.goto('/datasets');
    await page.getByRole('button', { name: /新建数据集/ }).click();
    await page.getByPlaceholder('如 support-qa-regression').fill(DATASET_KEY);
    await page.getByRole('button', { name: '创建' }).click();
    // naive-ui message 提示成功，或列表直接出现新行
    await expect(
      page.locator('.n-message, .n-data-table-tr').filter({ hasText: DATASET_KEY }).first(),
    ).toBeVisible({ timeout: 30_000 });
    await expect(page.locator('.n-data-table-tr').filter({ hasText: DATASET_KEY })).toHaveCount(1);
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('Trace 列表点进详情，span 与输出渲染', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);
    await page.goto('/traces');
    // 列表把 Trace ID 截断成前 16 字符 + 省略号（见 TraceListView 的 traceId 渲染），
    // 所以按前缀匹配；完整 ID 由跳转后的 URL 断言来确认。
    const rowPrefix = TRACE_ID.slice(0, 16);
    const row = page.locator('.n-data-table-tr').filter({ hasText: rowPrefix }).first();
    await expect(row).toBeVisible({ timeout: 30_000 });
    // 跳转挂在 Trace ID 单元格的 .trace-link 上（不是整行）
    await row.locator('.trace-link').click();
    await expect(page).toHaveURL(new RegExp(`/traces/${TRACE_ID}`));
    await expect(page.locator('body')).toContainText('invoke_agent');
    await expect(page.locator('body')).toContainText('订单');
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('评估器质量页点「扫描并写入告警」走通并给出反馈', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);
    await page.goto('/evaluator-quality');
    await expect(page.locator('body')).toContainText('评估器质量');
    await page.getByRole('button', { name: /扫描并写入告警/ }).click();
    await expect(page.locator('.n-message').first()).toContainText(/已写入/, { timeout: 30_000 });
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('实验排行：选中数据集后散点与排行表渲染', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    const seeded = await seedDatasetWithRun(ctx, token);
    datasetId = seeded.datasetId;
    sharedDatasetId = datasetId;
    sharedEvaluatorKey = seeded.evaluatorKey;
    await login(page);
    // 用 ?datasetId= 深链进入：报表可分享，也让 E2E 不依赖下拉交互
    await page.goto(`/experiment-leaderboard?datasetId=${datasetId}`);
    await expect(page.locator('body')).toContainText('Leaderboard');
    await expect(page.locator('body')).toContainText(BOARD_DATASET_KEY);

    await expect(page.locator('svg.chart circle').first()).toBeVisible({ timeout: 30_000 });
    const circles = await page.locator('svg.chart circle').count();
    expect(circles, '散点至少应有一个 run 点').toBeGreaterThan(0);
    // 回归守卫：半径/坐标算成 NaN 会让圆点消失（曾经真的发生过）
    const geometry = await page.locator('svg.chart circle').evaluateAll((nodes) =>
      nodes.map((n) => ({
        r: n.getAttribute('r'),
        cx: n.getAttribute('cx'),
        cy: n.getAttribute('cy'),
      })),
    );
    for (const g of geometry) {
      expect(Number.isNaN(Number(g.r)), `circle r=${g.r} 不能是 NaN`).toBe(false);
      expect(Number.isNaN(Number(g.cx)), `circle cx=${g.cx} 不能是 NaN`).toBe(false);
      expect(Number.isNaN(Number(g.cy)), `circle cy=${g.cy} 不能是 NaN`).toBe(false);
    }
    await expect(page.locator('.n-data-table-tr').filter({ hasText: '%' }).first()).toBeVisible();
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('矩阵实验与告警页可正常打开', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);
    await page.goto('/matrix');
    await expect(page.locator('.base-page').first()).toBeVisible();
    await expect(page.locator('body')).toContainText(/矩阵|抽样/);

    await page.goto('/alerts');
    await expect(page.locator('.base-page').first()).toBeVisible();
    await expect(page.locator('body')).toContainText(/告警|事件/);
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('数据集详情页可打开并显示样本', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    expect(datasetId, '依赖上一个用例创建的数据集').toBeTruthy();
    await login(page);
    await page.goto(`/datasets/${datasetId}`);
    await expect(page.locator('.base-page').first()).toBeVisible();
    await expect(page.locator('body')).toContainText(BOARD_DATASET_KEY);
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// 需要"真实调用模型"的链路：用 fixtures/fake_llm.py 起一个 OpenAI 兼容假端点，
// 先在系统设置页里配置它，再在试跑页真正跑一次。这样 UI → 设置持久化 → LLM 客户端
// → 响应渲染 整条链路都被验证，且不依赖外部模型与密钥。
// ---------------------------------------------------------------------------

test.describe.serial('EmonLens 页面操作（模型链路）', () => {
  const FAKE_LLM_URL = process.env.FAKE_LLM_URL || 'http://fake-llm:8080/v1';
  let ctx;
  let token;

  test.beforeAll(async ({ baseURL }) => {
    ctx = await request.newContext({ baseURL });
    token = await apiLogin(ctx);
  });

  test.afterAll(async () => {
    await ctx?.dispose();
  });

  test('系统设置里配置 LLM 端点并保存生效', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);
    await page.goto('/sys/settings');
    // naive-ui 的 tab 不是 role=tab；先等它可见再用短超时点击（用 .catch 兜 90s 默认超时会拖死整条用例）
    const llmTab = page.locator('.n-tabs-tab').filter({ hasText: 'LLM 端点' }).first();
    await expect(llmTab).toBeVisible({ timeout: 30_000 });
    await llmTab.click({ timeout: 10_000 });
    await expect(page.getByPlaceholder(/^https?:\/\//).first()).toBeVisible({ timeout: 30_000 });

    await page.getByPlaceholder(/^https?:\/\//).first().fill(FAKE_LLM_URL);
    await page.getByPlaceholder(/sk-\.\.\.|已保存/).first().fill('sk-e2e-fake');
    await page.getByPlaceholder(/gpt-4o-mini/).first().fill('fake-model');

    await page.getByRole('button', { name: '保存' }).first().click();
    await expect(page.locator('.n-message').first()).toContainText(/已保存/, { timeout: 30_000 });
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('矩阵实验提交并跑到出结果（真实调用假端点）', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    // 用模型链路里建好的数据集（含 1 条 trace 样本）
    expect(sharedDatasetId, '依赖前面用例创建的数据集').toBeTruthy();
    await login(page);
    await page.goto(`/matrix?datasetId=${sharedDatasetId}`);
    await expect(page.locator('.base-page').first()).toBeVisible();

    // 升格为正式实验要求矩阵挂评估器：在多选里选中造数时创建的那个
    await page.locator('.n-base-selection').filter({ hasText: '挂评估器' }).click();
    await page
      .locator('.n-base-select-option:visible')
      .filter({ hasText: sharedEvaluatorKey })
      .first()
      .click({ timeout: 10_000 });
    await page.keyboard.press('Escape');

    // 矩阵页默认就有多列（空 prompt）——必须把**每一列**都填上，否则后端会以
    // 「第 N 列缺少 prompt」拒绝提交。没有列时才需要新增。
    if ((await page.getByPlaceholder(/列名/).count()) === 0) {
      await page.getByRole('button', { name: /新增列/ }).click();
    }
    const promptBoxes = page.locator('textarea');
    const promptCount = await promptBoxes.count();
    for (let i = 0; i < promptCount; i += 1) {
      await promptBoxes.nth(i).fill('请复述：{{input}}');
    }
    const modelInputs = page.getByPlaceholder(/^模型$/);
    const modelCount = await modelInputs.count();
    for (let i = 0; i < modelCount; i += 1) {
      await modelInputs.nth(i).fill('fake-model');
    }
    await page.getByPlaceholder(/列名/).first().fill('e2e-col');

    // 第一次点运行只做预估（页面会提示先预估），第二次才真正提交
    await page.getByRole('button', { name: '运行矩阵' }).click();
    await expect(page.locator('.n-message').first()).toContainText(/预估|先看一次/, { timeout: 60_000 });
    await page.getByRole('button', { name: '运行矩阵' }).click();
    await expect(page.locator('.n-message').last()).toContainText(/已受理|正在执行/, { timeout: 60_000 });

    // 轮询到终态，并断言单元格里出现假端点的回复
    await expect(page.locator('body')).toContainText(/COMPLETED|PARTIAL/, { timeout: 120_000 });
    await expect(page.locator('.cell-out').first()).toContainText('fake-llm-ok', { timeout: 120_000 });

    // Diff：base / 对比列各选第一项，judge 用假端点模型；结果汇总（改进/退步/持平计数）必须渲染
    const diffSelects = page.locator('.diff-bar .n-base-selection');
    // naive-ui 会把关闭过的下拉菜单留在 DOM 里（display:none），必须只点"可见"的选项
    await diffSelects.nth(0).click();
    await page.locator('.n-base-select-option:visible').first().click({ timeout: 10_000 });
    await diffSelects.nth(1).click();
    // 对比列必须与 base 不同列：矩阵默认只有 2 列，选第二个可见选项（c2）
    await page.locator('.n-base-select-option:visible').nth(1).click({ timeout: 10_000 });
    await page.getByPlaceholder(/judge 模型/).fill('fake-model');
    await page.getByRole('button', { name: '对比', exact: true }).click();
    await expect(page.locator('.diff-summary')).toBeVisible({ timeout: 60_000 });

    // 升格为正式实验：弹窗自动预选列 → 填运行标签与提交号 → 确认
    await page.getByRole('button', { name: /升格为正式实验/ }).click();
    const labelInput = page.getByPlaceholder(/^baseline$/);
    await expect(labelInput).toBeVisible({ timeout: 10_000 });
    await labelInput.fill('baseline');
    await page.getByPlaceholder(/commit SHA/).fill(`e2e-${RUN_STAMP}`);
    await page.getByRole('button', { name: '升格', exact: true }).click();
    await expect(page.locator('.n-message').last()).toContainText(/已升格为正式实验/, { timeout: 60_000 });
    const promotedText = await page.locator('.n-message').last().textContent();
    const runIdMatch = promotedText && promotedText.match(/[0-9a-f]{16,}/);
    expect(runIdMatch, `升格消息应携带 runId: ${promotedText}`).toBeTruthy();

    // 桥接验证：数据集确实多了一个 runLabel=baseline 的正式 run，并跑到终态
    const auth = { Authorization: `Bearer ${token}` };
    const list = await (
      await ctx.get(`/api/v1/datasets/${sharedDatasetId}/runs?pageNumber=1&pageSize=10`, { headers: auth })
    ).json();
    // 该端点返回 data 直接是数组（不是分页对象），两种形状都兼容
    const runs = Array.isArray(list?.data) ? list.data : (list?.data?.records || []);
    let promotedRun = runs.find((r) => r.runLabel === 'baseline');
    // 升格是异步受理：run 可能刚创建还没出现在列表里，短暂重试
    for (let i = 0; i < 20 && !promotedRun; i += 1) {
      await new Promise((r) => setTimeout(r, 500));
      const again = await (
        await ctx.get(`/api/v1/datasets/${sharedDatasetId}/runs?pageNumber=1&pageSize=10`, { headers: auth })
      ).json();
      const rows = Array.isArray(again?.data) ? again.data : (again?.data?.records || []);
      promotedRun = rows.find((r) => r.runLabel === 'baseline');
    }
    expect(promotedRun, '升格应产生 runLabel=baseline 的正式 run').toBeTruthy();
    let promotedStatus = promotedRun.status;
    for (let i = 0; i < 60 && promotedStatus !== 'COMPLETED' && promotedStatus !== 'FAILED'; i += 1) {
      await new Promise((r) => setTimeout(r, 500));
      const d = await (
        await ctx.get(`/api/v1/datasets/${sharedDatasetId}/runs/${promotedRun.id}`, { headers: auth })
      ).json();
      promotedStatus = d?.data?.run?.status;
    }
    expect(promotedStatus, '升格出的正式 run 应跑到 COMPLETED').toBe('COMPLETED');
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('试跑页真正调用端点并渲染输出', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);
    await page.goto('/playground');
    await page.getByPlaceholder(/设定角色/).fill('你是一名客服助手。');
    await page.getByPlaceholder(/客户资料/).fill('客户问：我的订单到哪了？');
    await page.getByRole('button', { name: /运行/ }).first().click();

    // 假端点固定回复里带 fake-llm-ok，出现在输出卡片即证明整条链路通了
    await expect(page.locator('body')).toContainText('fake-llm-ok', { timeout: 60_000 });
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });
});
// ---------------------------------------------------------------------------
// 系统设置里的"签发的凭据真的能用"与"资产发布"链路。
// 不需要模型：验证 UI 表单结果能被 API 使用，以及发布类表单落到列表。
// ---------------------------------------------------------------------------

test.describe.serial('EmonLens 页面操作（设置与资产）', () => {
  let ctx;

  test.beforeAll(async ({ baseURL }) => {
    ctx = await request.newContext({ baseURL });
  });

  test.afterAll(async () => {
    await ctx?.dispose();
  });

  test('签发的 OpenAPI Token 能真实调用开放接口', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    await login(page);
    await page.goto('/sys/settings');
    const tab = page.locator('.n-tabs-tab').filter({ hasText: 'API Token' }).first();
    await expect(tab).toBeVisible({ timeout: 30_000 });
    await tab.click({ timeout: 10_000 });

    await page.getByRole('button', { name: /签发 Token/ }).click();
    await page.getByPlaceholder(/如 BI 报表集成/).fill(`e2e-token-${RUN_STAMP}`);
    await page.getByRole('button', { name: '签发', exact: true }).click();

    const plain = page.locator('.key-plain');
    await expect(plain).toBeVisible({ timeout: 30_000 });
    const issued = (await plain.textContent()).trim();
    expect(issued, '明文 token 应在签发弹窗里出现一次').toMatch(/^eak-/);
    await page.getByRole('button', { name: '我已保存' }).click();
    await expect(page.locator('.n-data-table-tr').filter({ hasText: `e2e-token-${RUN_STAMP}` }).first())
      .toBeVisible({ timeout: 30_000 });

    // 真凭据能用：开放 API（X-Api-Token）不应再报未认证
    const good = await ctx.get(`/api/v1/open/score/agents/${AGENT}`, {
      headers: { 'X-Api-Token': issued },
    });
    const goodBody = await good.json();
    expect(goodBody.code, `开放接口应接受该 token: ${JSON.stringify(goodBody)}`).not.toBe(40101);

    // 假 token 必须被拒（证明上面不是"接口对谁都放行"）
    const bad = await ctx.get(`/api/v1/open/score/agents/${AGENT}`, {
      headers: { 'X-Api-Token': 'eak-definitely-wrong' },
    });
    expect((await bad.json()).code, '错误 token 必须 40101').toBe(40101);

    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('通知渠道创建后出现在列表', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    const senderName = `e2e-webhook-${RUN_STAMP}`;
    await login(page);
    await page.goto('/sys/settings');
    const tab = page.locator('.n-tabs-tab').filter({ hasText: '通知' }).first();
    await expect(tab).toBeVisible({ timeout: 30_000 });
    await tab.click({ timeout: 10_000 });

    await page.getByRole('button', { name: /新建渠道/ }).click();
    // 渠道类型：EMAIL / WEBHOOK，选 WEBHOOK 后配置样例会切换成 url+secret
    await page.locator('.n-form-item').filter({ hasText: '渠道类型' }).locator('.n-base-selection').click();
    await page.locator('.n-base-select-option:visible').filter({ hasText: 'WEBHOOK' }).first().click({ timeout: 10_000 });
    await page.getByPlaceholder(/如 运维邮件组/).fill(senderName);
    await page
      .locator('.n-form-item')
      .filter({ hasText: '配置（JSON）' })
      .locator('textarea')
      .fill(JSON.stringify({ url: 'https://hooks.example.com/e2e', secret: 'e2e-secret-key-0123456789abcdef', keyVersion: 'v1' }));
    await page.getByRole('button', { name: '创建', exact: true }).click();
    await expect(page.locator('.n-message').last()).toContainText(/渠道已创建/, { timeout: 30_000 });
    await expect(page.locator('.n-data-table-tr').filter({ hasText: senderName }).first())
      .toBeVisible({ timeout: 30_000 });
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });

  test('发布领域包版本后出现在列表', async ({ page }) => {
    const errors = collectConsoleErrors(page);
    const packKey = `e2e-pack-${RUN_STAMP}`;
    await login(page);
    await page.goto('/domain-packs');
    await page.getByRole('button', { name: /发布领域包版本/ }).click();
    await page.getByPlaceholder('customer-support').fill(packKey);
    await page.getByPlaceholder('1.0.0').fill('1.0.0');
    // 名称现在有 required 标记与 placeholder（本轮补的可用性修复）
    await page.getByPlaceholder(/如 客服领域包/).fill(`E2E 领域包 ${RUN_STAMP}`);
    // manifest 已预填合法模板（domain-pack/v1），不额外改
    await page.getByRole('button', { name: '发布', exact: true }).click();
    await expect(page.locator('.n-message').last()).toContainText(/已发布/, { timeout: 30_000 });
    await expect(page.locator('.n-data-table-tr').filter({ hasText: packKey }).first())
      .toBeVisible({ timeout: 30_000 });
    expect(errors, `console errors: ${errors.join(' | ')}`).toEqual([]);
  });
});