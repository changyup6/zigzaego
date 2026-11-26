import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "e2e/local",
  testMatch: [
    "price-comparison.spec.ts",
    "demo-refinements.spec.ts",
    "receipt-demo.spec.ts",
    "closing-time.spec.ts",
    "tablet-documents.spec.ts",
  ],
  workers: 1,
  reporter: "list",
  use: { baseURL: "http://localhost:3200", browserName: "chromium", reducedMotion: "reduce" },
  webServer: {
    command: "npm run dev -- --port 3200",
    env: {
      NEXT_PUBLIC_API_URL: "http://localhost:8080",
      NEXT_PUBLIC_DEMO: "1",
      CI: "1",
      __NEXT_AGENT_UPGRADE: "security",
    },
    url: "http://localhost:3200",
    reuseExistingServer: false,
  },
});
