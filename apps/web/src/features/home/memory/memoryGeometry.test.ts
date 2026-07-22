import { existsSync } from "node:fs";
import { execFileSync } from "node:child_process";
import path from "node:path";
import { describe, expect, it } from "vitest";

const browserCandidates = [
  process.env.CORTEX_BROWSER_BIN,
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
].filter((candidate): candidate is string => Boolean(candidate));
const browser = browserCandidates.find(existsSync);

describe("Memory real-browser geometry", () => {
  it.runIf(Boolean(browser))(
    "keeps page, query, filters and long evidence within 901/1000/1100/620",
    () => {
      const script = path.resolve(
        process.cwd(),
        "scripts/verify-memory-geometry.mjs",
      );
      const output = execFileSync(process.execPath, [script], {
        cwd: process.cwd(),
        env: { ...process.env, CORTEX_BROWSER_BIN: browser },
        encoding: "utf8",
      });
      expect(output).toContain("Memory geometry verified: 4 scenarios");
    },
    30_000,
  );
});
