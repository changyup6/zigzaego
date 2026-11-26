import { defineConfig } from "@playwright/test";

// 스테이징 E2E. 대상은 E2E_BASE_URL(기본 zigzaego.example.com), API 는 E2E_API_URL(기본 zigzaego.example.com).
// 운영 주소를 실수로 부르지 않도록 스테이징 호스트와 localhost 만 허용한다.
const baseURL = process.env.E2E_BASE_URL ?? "https://zigzaego.example.com";
const host = new URL(baseURL).hostname;
if (host !== "zigzaego.example.com" && host !== "localhost") {
  throw new Error(
    `E2E_BASE_URL 은 스테이징(zigzaego.example.com) 또는 localhost 만 허용한다: ${host}`,
  );
}

export default defineConfig({
  testDir: "e2e",
  retries: 1,
  workers: 1,
  reporter: [["html", { open: "never" }], ["list"]],
  use: { baseURL, trace: "retain-on-failure" },
  projects: [
    { name: "setup", testMatch: /auth\.setup\.ts/ },
    {
      name: "phone",
      testMatch: /specs\/.*\.spec\.ts/,
      dependencies: ["setup"],
      use: { browserName: "chromium", viewport: { width: 375, height: 812 } },
    },
    {
      name: "desktop",
      testMatch: /specs\/.*\.spec\.ts/,
      dependencies: ["setup"],
      use: { browserName: "chromium", viewport: { width: 1360, height: 800 } },
    },
  ],
});
