import { defineConfig, devices } from '@playwright/test';

const baseURL = 'http://127.0.0.1:5174';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  use: {
    baseURL,
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chrome',
      use: { ...devices['Desktop Chrome'], channel: 'chrome' },
    },
  ],
  webServer: [
    {
      command: 'node e2e/oidc-provider.mjs',
      url: 'http://127.0.0.1:18082/.well-known/openid-configuration',
      reuseExistingServer: false,
    },
    {
      command: 'npm run dev -- --port 5174 --strictPort',
      url: baseURL,
      reuseExistingServer: false,
      env: {
        VITE_COGNITO_AUTHORITY: 'http://127.0.0.1:18082',
        VITE_COGNITO_DOMAIN: 'http://127.0.0.1:18082',
        VITE_COGNITO_CLIENT_ID: 'frontend-test',
        VITE_AUTH_REDIRECT_URI: `${baseURL}/auth/callback`,
        VITE_AUTH_LOGOUT_URI: `${baseURL}/login`,
      },
    },
  ],
});
