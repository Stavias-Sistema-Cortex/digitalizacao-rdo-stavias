import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

const page = readFileSync(
  resolve(process.cwd(), "./src/features/integracoes/IntegracoesPage.tsx"),
  "utf8",
);

describe("Academy partial synchronization presentation", () => {
  it("reports a partial import as an explicit partial result, not a failure", () => {
    expect(page).toContain('case "PARTIAL":\n      return "Parcial";');
    expect(page).toContain(
      'result.status === "SUCCESS" || result.status === "PARTIAL"',
    );
  });
});
