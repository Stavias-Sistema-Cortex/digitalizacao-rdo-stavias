import { describe, expect, it } from "vitest";

import { classifyFieldConflict } from "./fieldConflict";

describe("classifyFieldConflict", () => {
  it("combines independent top-level field changes", () => {
    expect(classifyFieldConflict(
      { titulo: "A", prazo: "2026-07-20" },
      { titulo: "B", prazo: "2026-07-20" },
      { titulo: "A", prazo: "2026-07-21" },
    )).toEqual({
      canAutoMerge: true,
      merged: { titulo: "B", prazo: "2026-07-21" },
      conflicts: {},
    });
  });

  it("preserves base, local and remote for a same-field conflict", () => {
    expect(classifyFieldConflict(
      { titulo: "A" },
      { titulo: "B" },
      { titulo: "C" },
    )).toEqual({
      canAutoMerge: false,
      merged: { titulo: "B" },
      conflicts: {
        titulo: { base: "A", local: "B", remote: "C" },
      },
    });
  });

  it("compares recursively canonical JSON instead of object identity", () => {
    expect(classifyFieldConflict(
      { metadata: { status: "ATIVA", ordem: 1 }, prazo: "2026-07-20" },
      { metadata: { ordem: 1, status: "ATIVA" }, prazo: "2026-07-21" },
      { prazo: "2026-07-20", metadata: { ordem: 1, status: "ATIVA" } },
    )).toEqual({
      canAutoMerge: true,
      merged: {
        metadata: { ordem: 1, status: "ATIVA" },
        prazo: "2026-07-21",
      },
      conflicts: {},
    });
  });

  it.each([
    [
      "object",
      { geometria: { type: "Point", coordinates: [-47, -23] } },
      { geometria: { type: "Point", coordinates: [-47.1, -23] } },
      { geometria: { type: "Point", coordinates: [-47, -23.1] } },
    ],
    [
      "array",
      { participantes: ["ana", "bia"] },
      { participantes: ["ana", "bia", "caio"] },
      { participantes: ["ana"] },
    ],
  ])("treats a changed %s subtree as one atomic field", (
    _label,
    base,
    local,
    remote,
  ) => {
    const resolution = classifyFieldConflict(base, local, remote);

    expect(resolution.canAutoMerge).toBe(false);
    expect(resolution.merged).toEqual(local);
    expect(resolution.conflicts).toEqual({
      [Object.keys(base)[0]]: {
        base: Object.values(base)[0],
        local: Object.values(local)[0],
        remote: Object.values(remote)[0],
      },
    });
  });

  it("rejects sparse arrays instead of treating holes as canonical JSON", () => {
    const sparse = new Array<string>(1);

    expect(() => classifyFieldConflict(
      { participantes: [] },
      { participantes: sparse },
      { participantes: [] },
    )).toThrow("Canonical JSON rejects undefined at $[0].");
  });
});
