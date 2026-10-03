import fs from "node:fs";
import path from "node:path";
import type {
  FullResult,
  Reporter,
  TestCase,
  TestResult,
} from "@playwright/test/reporter";
import { createEvidence } from "../lib/evidence";

export default class SafeEvidenceReporter implements Reporter {
  private readonly records: unknown[] = [];

  onTestEnd(test: TestCase, result: TestResult): void {
    const environment = process.env.LIW_ASSURANCE_TARGET ?? "deterministic";
    const releaseDigest = process.env.LIW_RELEASE_DIGEST ?? "local";
    const record = createEvidence(test, result, environment, releaseDigest);
    this.records.push(record);
    process.stdout.write(`${JSON.stringify(record)}\n`);
  }

  onEnd(result: FullResult): void {
    const output = path.resolve("test-results/safe-evidence.json");
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(
      output,
      `${JSON.stringify({ status: result.status, records: this.records }, null, 2)}\n`,
      { mode: 0o600 },
    );
  }
}
