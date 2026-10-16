import { defineConfig, devices } from '@playwright/test';

// Two modes:
//   * E2E_BASE_URL set   — point at an already-running instance (e.g. a real
//     deployment). Nothing is seeded or started; auth specs require explicit
//     test credentials/shim flags and the mutating suite requires a test DB.
//   * E2E_BASE_URL unset — managed: seed the disposable DB named by DB_* (see
//     test/e2e/global-setup.ts), build and start the app here with the auth shim
//     (E2E_TEST_AUTH=1), and run every spec against it. This is what
//     `npm run test:e2e` does locally and what the CI e2e job runs.
const managed = !process.env.E2E_BASE_URL;
const port = Number(process.env.E2E_PORT ?? 3100);
const baseURL = process.env.E2E_BASE_URL || `http://127.0.0.1:${port}`;

if (managed) {
  // Read by the specs (in the workers, which inherit this env): the app below
  // runs with the auth shim, so the role-gated specs can run.
  process.env.E2E_AUTH_SHIM = '1';
  process.env.E2E_AUTH_SECRET = 'e2e-only-not-a-secret';
  process.env.E2E_BASE_URL = baseURL;
}

export default defineConfig({
  testDir: './test/e2e',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  ...(process.env.CI && { workers: 2 }),
  // The retry is there to capture a trace, not to turn a flake green: a test
  // that only passes on retry still fails the CI run.
  failOnFlakyTests: !!process.env.CI,
  reporter: process.env.CI ? [['list'], ['html', { open: 'never' }]] : 'list',
  ...(managed && {
    globalSetup: './test/e2e/global-setup.ts',
    webServer: {
      command: `npm run build && npm run start -- --hostname 127.0.0.1 --port ${port}`,
      // Not '/': the server is started before global-setup seeds the DB, and
      // the dashboard 500s on an empty schema.
      url: `${baseURL}/healthz`,
      timeout: 300_000,
      reuseExistingServer: false,
      // The access log is one JSON line per request; keep the output to results.
      stdout: 'ignore',
      stderr: 'pipe',
      env: {
        E2E_TEST_AUTH: '1',
        AUTH_SECRET: 'e2e-only-not-a-secret',
        AUTH_URL: baseURL,
        AUTH_TRUST_HOST: 'true',
        // Signed-session tests bypass external OAuth but Auth.js still needs
        // a valid provider configuration in clean CI environments.
        AUTH_KEYCLOAK_ISSUER: 'https://identity.example.test/realms/test',
        AUTH_KEYCLOAK_ID: 'explorer',
        SITE_URL: baseURL,
      },
    },
  }),
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    // Chromium-based phone preset: the iPhone presets need WebKit, which we don't install.
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
});
