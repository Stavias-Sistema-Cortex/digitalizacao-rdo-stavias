import { describe, expect, it } from "vitest";

import {
  classifyFieldConflict,
  remoteSnapshotEvidence,
} from "./fieldConflict";

describe("canonical field conflict evidence", () => {
  it("auto-merges disjoint local and remote changes with stable canonical comparison", () => {
    const result = classifyFieldConflict(
      { titulo: "A", meta: { b: 2, a: 1 }, responsavel: "Ana" },
      { titulo: "B", meta: { a: 1, b: 2 }, responsavel: "Ana" },
      {
        complete: true,
        snapshot: {
          titulo: "A",
          meta: { b: 2, a: 1 },
          responsavel: "Bia",
        },
      },
    );

    expect(result.canAutoMerge).toBe(true);
    expect(result.merged).toEqual({
      titulo: "B",
      meta: { a: 1, b: 2 },
      responsavel: "Bia",
    });
    expect(result.conflicts).toEqual([]);
  });

  it("persists base/local/remote triples for a same-field conflict", () => {
    const result = classifyFieldConflict(
      { titulo: "A" },
      { titulo: "B" },
      { complete: true, snapshot: { titulo: "C" } },
    );

    expect(result.canAutoMerge).toBe(false);
    expect(result.conflicts).toEqual([
      {
        field: "titulo",
        base: { present: true, value: "A" },
        local: { present: true, value: "B" },
        remote: { present: true, value: "C" },
      },
    ]);
  });

  it("records unavailable remote state explicitly and never invents a value", () => {
    const result = classifyFieldConflict(
      { titulo: "A" },
      { titulo: "B" },
      { complete: false, snapshot: null },
    );

    expect(result.canAutoMerge).toBe(false);
    expect(result.conflicts).toEqual([
      {
        field: "titulo",
        base: { present: true, value: "A" },
        local: { present: true, value: "B" },
        remote: { present: false },
      },
    ]);
  });

  it("requires an explicit completeness marker before trusting a server snapshot", () => {
    expect(
      remoteSnapshotEvidence({ snapshotRemoto: { titulo: "C" } }),
    ).toEqual({ complete: false, snapshot: null });
    expect(
      remoteSnapshotEvidence({
        remoteCompleto: true,
        snapshotRemoto: { titulo: "C" },
      }),
    ).toEqual({ complete: true, snapshot: { titulo: "C" } });
  });
});
