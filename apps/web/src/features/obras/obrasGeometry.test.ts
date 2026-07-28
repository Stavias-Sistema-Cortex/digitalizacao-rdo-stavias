import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const browser = [
  process.env.CORTEX_BROWSER_BIN,
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
].filter((candidate): candidate is string => Boolean(candidate))
  .find(existsSync);

describe("Obras Trash real-browser geometry", () => {
  it.runIf(Boolean(browser))(
    "fits maximum sidebar scenarios without horizontal overflow",
    () => {
      const output = execFileSync(
        process.execPath,
        [path.resolve(
          process.cwd(),
          "scripts/verify-obras-trash-geometry.mjs",
        )],
        {
          cwd: process.cwd(),
          env: { ...process.env, CORTEX_BROWSER_BIN: browser },
          encoding: "utf8",
        },
      );
      expect(output).toContain(
        "Obras trash geometry verified: 4 scenarios",
      );
    },
    30_000,
  );
});
