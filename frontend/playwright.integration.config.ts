import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './integration',
  globalSetup: './integration/setup.ts',
  workers: 1,
  fullyParallel: false,
  timeout: 30000,
  use: {
    ...devices['Desktop Chrome'],
    channel: 'chrome',
    baseURL: 'http://127.0.0.1:5174',
    trace: 'retain-on-failure',
  },
  outputDir: 'test-results/integration',
  webServer: {
    command: 'npm run dev -- --port 5174 --strictPort',
    url: 'http://127.0.0.1:5174',
    reuseExistingServer: false,
    env: { API_TARGET: 'http://127.0.0.1:18081' },
  },
});
