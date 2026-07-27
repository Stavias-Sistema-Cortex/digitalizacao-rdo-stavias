import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import path from "node:path";
import { describe, expect, it } from "vitest";

const dialogCss = readFileSync(
  new URL("./RdoCreationDialog.css", import.meta.url),
  "utf8",
);
const workforceCss = readFileSync(
  new URL("./RdoWorkforceEditor.css", import.meta.url),
  "utf8",
);
const browserCandidates = [
  process.env.CORTEX_BROWSER_BIN,
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
].filter((candidate): candidate is string => Boolean(candidate));
const browser = browserCandidates.find(existsSync);

describe("geometria real do fluxo de criação do RDO", () => {
  it.runIf(Boolean(browser))(
    "mantém título, obra selecionada, data e ações visíveis em 1440x900, 1280x720 e 390x844",
    () => {
      const script = path.resolve(
        process.cwd(),
        "scripts/verify-rdo-creation-geometry.mjs",
      );
      const output = execFileSync(process.execPath, [script], {
        cwd: process.cwd(),
        env: { ...process.env, CORTEX_BROWSER_BIN: browser },
        encoding: "utf8",
      });
      expect(output).toContain("RDO creation geometry verified: 3 scenarios");
    },
    30_000,
  );

  it("restringe a rolagem vertical à lista de obras e respeita movimento reduzido", () => {
    expect(dialogCss).toMatch(/\.rdo-creation-body\s*\{[^}]*overflow:\s*hidden/);
    expect(dialogCss).toMatch(/\.rdo-creation-worksite-list\s*\{[^}]*overflow-y:\s*auto/);
    expect(dialogCss).not.toMatch(/\.rdo-creation-body\s*\{[^}]*overflow-y:\s*auto/);
    expect(dialogCss).toMatch(/@media \(prefers-reduced-motion: reduce\)[\s\S]*?animation:\s*none/);
    expect(workforceCss).toMatch(/\.rdo-workforce-table-region[\s\S]*?overflow:\s*auto/);
  });
});
