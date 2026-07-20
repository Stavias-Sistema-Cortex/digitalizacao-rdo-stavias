import { describe, expect, it } from "vitest";

import { databaseNameForScope } from "./localDataNamespace";

const OWNER = "00000000-0000-4000-8000-000000000001";

describe("localDataNamespace", () => {
  it("isola o mesmo proprietário quando papel ou obras mudam", async () => {
    const betaOne = await databaseNameForScope(
      OWNER,
      "BETA:00000000-0000-4000-8000-000000000002",
    );
    const betaTwo = await databaseNameForScope(
      OWNER,
      "BETA:00000000-0000-4000-8000-000000000003",
    );
    const alfa = await databaseNameForScope(OWNER, "ALFA:GLOBAL");

    expect(betaOne).not.toBe(betaTwo);
    expect(betaOne).not.toBe(alfa);
    expect(betaTwo).not.toBe(alfa);
    expect(betaOne).not.toContain("cortex-web");
  });

  it("produz namespace estável sem expor a lista de obras no nome", async () => {
    const material = "BETA:00000000-0000-4000-8000-000000000002";
    const first = await databaseNameForScope(OWNER, material);
    const second = await databaseNameForScope(OWNER, material);

    expect(first).toBe(second);
    expect(first).not.toContain(
      "00000000-0000-4000-8000-000000000002",
    );
  });
});
