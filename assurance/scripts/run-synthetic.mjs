import { spawn } from "node:child_process";
import fs from "node:fs/promises";
import path from "node:path";

const target = process.env.LIW_ASSURANCE_TARGET;
if (target !== "production") {
  process.stderr.write(
    "SYNTHETIC_HARNESS_FAILURE: scheduled runner requires LIW_ASSURANCE_TARGET=production\n",
  );
  process.exit(2);
}

const child = spawn(
  process.execPath,
  ["node_modules/@playwright/test/cli.js", "test", "--project=production"],
  { stdio: "inherit", env: process.env },
);
const exitCode = await new Promise((resolve) => child.on("exit", resolve));

async function accessToken() {
  const tokenResponse = await fetch(
    "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token",
    { headers: { "Metadata-Flavor": "Google" } },
  );
  if (!tokenResponse.ok) throw new Error("metadata token unavailable");
  return tokenResponse.json();
}

async function publishMetric(metricType, value, token) {
  const projectId = process.env.GOOGLE_CLOUD_PROJECT;
  if (!projectId) throw new Error("GOOGLE_CLOUD_PROJECT is missing");
  const response = await fetch(
    `https://monitoring.googleapis.com/v3/projects/${encodeURIComponent(projectId)}/timeSeries`,
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token.access_token}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        timeSeries: [
          {
            metric: { type: metricType },
            resource: { type: "global", labels: { project_id: projectId } },
            points: [
              {
                interval: { endTime: new Date().toISOString() },
                value: { int64Value: String(value) },
              },
            ],
          },
        ],
      }),
    },
  );
  if (!response.ok) throw new Error(`metric write returned ${response.status}`);
}

let evidence;
try {
  evidence = JSON.parse(
    await fs.readFile(path.resolve("test-results/safe-evidence.json"), "utf8"),
  );
} catch {
  evidence = { records: [] };
}

const safeEvidencePath = path.resolve("test-results/safe-evidence.json");
if (exitCode !== 0 && !evidence.records?.[0]) {
  const record = {
    schema: "line-item-watch.synthetic-result.v1",
    environment: "production",
    journey: "production read-only HubSpot synthetic journey",
    releaseDigest: process.env.LIW_RELEASE_DIGEST ?? "unavailable",
    outcome: "FAIL",
    failureClass: "SYNTHETIC_HARNESS_FAILURE",
    failingStep: "Playwright exited before safe test evidence was produced",
    correlationIds: [],
    durationMs: null,
    attempt: 0,
  };
  evidence = { status: "failed", records: [record] };
  await fs.mkdir(path.dirname(safeEvidencePath), { recursive: true });
  await fs.writeFile(safeEvidencePath, `${JSON.stringify(evidence, null, 2)}\n`, {
    mode: 0o600,
  });
  process.stderr.write(`${JSON.stringify(record)}\n`);
}
const failureClass = evidence.records?.[0]?.failureClass;
try {
  const token = await accessToken();
  await publishMetric(
    "custom.googleapis.com/line_item_watch/synthetic/product_failed",
    exitCode !== 0 && failureClass === "PRODUCT_FAILURE" ? 1 : 0,
    token,
  );
  if (exitCode === 0) {
    await publishMetric(
      "custom.googleapis.com/line_item_watch/synthetic/success",
      1,
      token,
    );
  }
} catch (error) {
  process.stderr.write(
    `${JSON.stringify({ schema: "line-item-watch.synthetic-result.v1", environment: "production", journey: "publish monitoring heartbeat", releaseDigest: process.env.LIW_RELEASE_DIGEST ?? "unavailable", outcome: "FAIL", failureClass: "SYNTHETIC_HARNESS_FAILURE", failingStep: String(error) })}\n`,
  );
  if (exitCode === 0) process.exit(2);
}

if (exitCode !== 0 && process.env.LIW_EVIDENCE_BUCKET) {
  try {
    const evidencePath = path.resolve("test-results/safe-evidence.json");
    const body = await fs.readFile(evidencePath);
    const token = await accessToken();
    const execution = process.env.CLOUD_RUN_EXECUTION ?? `manual-${Date.now()}`;
    const objectName = `failures/${new Date().toISOString().slice(0, 10)}/${execution}/safe-evidence.json`;
    const upload = new URL(
      `https://storage.googleapis.com/upload/storage/v1/b/${encodeURIComponent(process.env.LIW_EVIDENCE_BUCKET)}/o`,
    );
    upload.searchParams.set("uploadType", "media");
    upload.searchParams.set("name", objectName);
    const response = await fetch(upload, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token.access_token}`,
        "Content-Type": "application/json",
      },
      body,
    });
    if (!response.ok) throw new Error(`evidence upload returned ${response.status}`);
    process.stdout.write(
      `${JSON.stringify({ schema: "line-item-watch.synthetic-evidence-upload.v1", outcome: "PASS", objectName })}\n`,
    );
  } catch (error) {
    process.stderr.write(
      `${JSON.stringify({ schema: "line-item-watch.synthetic-result.v1", environment: "production", journey: "upload sanitized failure evidence", releaseDigest: process.env.LIW_RELEASE_DIGEST ?? "unavailable", outcome: "FAIL", failureClass: "SYNTHETIC_HARNESS_FAILURE", failingStep: String(error) })}\n`,
    );
  }
}

process.exit(typeof exitCode === "number" ? exitCode : 1);
