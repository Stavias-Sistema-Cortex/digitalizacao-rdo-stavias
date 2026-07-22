import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

const createPage = readFileSync(
  resolve(process.cwd(), "./src/features/rdos/RdoCreatePage.tsx"),
  "utf8",
);

describe("RDO raw JSON authorization", () => {
  it("renders the raw JSON control and preview only for ALFA", () => {
    expect(createPage).toContain(
      'import { getSession, isAlfa } from "../auth/authSession";',
    );
    expect(createPage).toContain(
      "const canViewRawPayload = isAlfa(getSession());",
    );
    expect(createPage).toMatch(
      /\{canViewRawPayload && showJson && \(/,
    );
    expect(createPage).toMatch(
      /\{canViewRawPayload && \(\s*<button/,
    );
  });
});
