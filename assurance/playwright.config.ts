import { defineConfig, devices } from "@playwright/test";

const live = process.env.LIW_ASSURANCE_TARGET === "staging" || process.env.LIW_ASSURANCE_TARGET === "production";

export default defineConfig({
  testDir: "./tests",
  outputDir: "test-results/artifacts",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: 1,
  timeout: live ? 90_000 : 30_000,
  expect: { timeout: 10_000 },
  reporter: live
    ? [["./reporters/safe-evidence.ts"]]
    : [["line"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    ...devices["Desktop Chrome"],
    locale: "en-US",
    timezoneId: "Europe/Budapest",
    serviceWorkers: "block",
    trace: live ? "off" : "retain-on-failure",
    screenshot: live ? "off" : "only-on-failure",
    video: "off",
  },
  ...(live
    ? {}
    : { webServer: {
        command: "npm exec vite -- --host 127.0.0.1 --port 4173",
        url: "http://127.0.0.1:4173/src/harness/",
        reuseExistingServer: !process.env.CI,
        stdout: "pipe",
        stderr: "pipe",
      } }),
  projects: [
    {
      name: "deterministic",
      testMatch: "deterministic.spec.ts",
      use: { baseURL: "http://127.0.0.1:4173/src/harness/" },
    },
    {
      name: "visual",
      testMatch: "visual.spec.ts",
      use: { baseURL: "http://127.0.0.1:4173/src/harness/" },
    },
    { name: "staging", testMatch: "live.spec.ts", grep: /@staging/ },
    { name: "production", testMatch: "live.spec.ts", grep: /@production/ },
  ],
});
