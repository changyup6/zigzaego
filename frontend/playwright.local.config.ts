import { defineConfig } from "@playwright/test";

// 네트워크 API 는 테스트가 전부 가로채며 실제 가게 데이터에는 접근하지 않는다.
const preview = process.env.UI_FEEDBACK_BASE_URL;
const port = Number(process.env.UI_FEEDBACK_PORT ?? 3100);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("로컬 포트 범위 오류");
if (preview && preview !== "http://localhost:3000")
  throw new Error("전용 localhost:3000 미리보기만 허용한다");

export default defineConfig({
  testDir: "e2e/local",
  workers: 1,
  reporter: "list",
  use: {
    baseURL: preview ?? `http://localhost:${port}`,
    browserName: "chromium",
    reducedMotion: "reduce",
  },
  webServer: preview
    ? undefined
    : {
        command:
          `NEXT_PUBLIC_API_URL=http://localhost:8080 NEXT_PUBLIC_DEMO=1 npm run dev -- --port ${port}`,
        url: `http://localhost:${port}`,
        reuseExistingServer: false,
      },
});
