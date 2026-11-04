const { defineConfig } = require('@playwright/test');

/**
 * 页面操作 E2E 配置。
 *
 * EMONLENS_BASE_URL 指向已经在跑的应用（demo profile 即可，它同时提供 UI 静态资源与 API）。
 * 单 worker 串行：用例之间共享同一份演示数据（admin/123456、ws-default），并行会互相干扰。
 */
module.exports = defineConfig({
  testDir: './tests',
  timeout: 90_000,
  expect: { timeout: 20_000 },
  fullyParallel: false,
  workers: 1,
  retries: 1,
  // 产物统一落到仓库根 test/（已 gitignore）：报告与失败截图/轨迹不进远端仓库
  outputDir: '../../test/e2e-ui/results',
  reporter: [['list'], ['html', { open: 'never', outputFolder: '../../test/e2e-ui/report' }]],
  use: {
    baseURL: process.env.EMONLENS_BASE_URL || 'http://127.0.0.1:8300',
    locale: 'zh-CN',
    viewport: { width: 1440, height: 900 },
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
  },
});
