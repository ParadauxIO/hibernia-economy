import { defineConfig, devices } from '@playwright/test';

// The integration runner owns Paper, schema migrations, fixtures and the app.
// Deliberately no globalSetup/resetDb and no managed webServer here.
if (!process.env.E2E_BASE_URL || !process.env.IT_EXPLORER_FIXTURE || !process.env.E2E_AUTH_SECRET) {
  throw new Error('Live-server browser tests require the disposable integration runner environment.');
}
export default defineConfig({
  testDir: './test/live-server',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['junit', { outputFile: 'test-results/live-server.xml' }]],
  use: {
    baseURL: process.env.E2E_BASE_URL,
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
