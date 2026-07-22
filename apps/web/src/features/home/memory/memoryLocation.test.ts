import { describe, expect, it } from "vitest";

import { memoryHref } from "./memoryLocation";

describe("memoryHref", () => {
  it("creates an encoded worksite entity link", () => {
    expect(memoryHref({
      obraId: "obra 1",
      entityType: "OBRA",
      entityId: "obra 1",
    })).toBe(
      "/home?tab=memory&obraId=obra+1&entityType=OBRA&entityId=obra+1",
    );
  });

  it("omits blank values and keeps memory first", () => {
    expect(memoryHref({ rdoId: " rdo-1 ", entityType: "" }))
      .toBe("/home?tab=memory&rdoId=rdo-1");
  });
});
