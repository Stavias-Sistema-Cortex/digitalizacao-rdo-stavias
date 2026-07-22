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

  it.each([
    ["base", { invalid: new Array<string>(1) }, {}, {}],
    ["local", {}, { invalid: undefined }, {}],
    ["remote", {}, {}, (() => {
      const cycle: Record<string, unknown> = {};
      cycle.self = cycle;
      return { invalid: cycle };
    })()],
  ])("validates a value present only in %s", (
    _side,
    base,
    local,
    remote,
  ) => {
    expect(() => classifyFieldConflict(base, local, remote)).toThrow(
      "Canonical JSON rejects",
    );
  });

  it.each([
    ["non-finite number", Number.NaN],
    ["function", () => undefined],
    ["bigint", BigInt(1)],
    ["symbol", Symbol("invalid")],
    ["non-plain object", new Date("2026-07-17T12:00:00Z")],
    ["symbol key", (() => {
      const value = { visible: true };
      Object.defineProperty(value, Symbol("invalid"), {
        enumerable: true,
        value: true,
      });
      return value;
    })()],
  ])("rejects a one-sided %s", (_label, invalid) => {
    expect(() => classifyFieldConflict(
      {},
      {},
      { invalid },
    )).toThrow("Canonical JSON rejects");
  });

  it("preserves a JSON __proto__ field as an own data property", () => {
    const base = JSON.parse(
      '{"__proto__":{"source":"base"},"prazo":"2026-07-20"}',
    ) as Record<string, unknown>;
    const local = JSON.parse(
      '{"__proto__":{"source":"local"},"prazo":"2026-07-20"}',
    ) as Record<string, unknown>;
    const remote = JSON.parse(
      '{"__proto__":{"source":"remote"},"prazo":"2026-07-21"}',
    ) as Record<string, unknown>;

    const resolution = classifyFieldConflict(base, local, remote);

    expect(Object.getPrototypeOf(resolution.merged)).toBe(Object.prototype);
    expect(Object.hasOwn(resolution.merged, "__proto__")).toBe(true);
    expect(Object.hasOwn(resolution.conflicts, "__proto__")).toBe(true);
    expect(resolution).toEqual({
      canAutoMerge: false,
      merged: JSON.parse(
        '{"__proto__":{"source":"local"},"prazo":"2026-07-21"}',
      ),
      conflicts: JSON.parse(
        '{"__proto__":{"base":{"source":"base"},"local":{"source":"local"},"remote":{"source":"remote"}}}',
      ),
    });
  });
});
