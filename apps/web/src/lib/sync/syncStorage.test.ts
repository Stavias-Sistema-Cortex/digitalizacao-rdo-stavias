import { describe, expect, it } from "vitest";

import { rdoAfterConflict } from "./syncStorage";
import type { LocalRdoRecord } from "../db/db.types";
import type { SyncPushMutationResult } from "./sync.types";

const baseRdo = {
  id: "rdo-1",
  syncStatus: "SYNCING",
  versaoEntidade: 3,
  updatedAt: "2026-07-02T10:00:00.000Z",
} as unknown as LocalRdoRecord;

function conflictResult(
  conflito: Record<string, unknown> | null,
): SyncPushMutationResult {
  return {
    clientMutationId: "m-1",
    status: "DESCARTADA",
    entidadeTipo: "RDO",
    entidadeId: "rdo-1",
    conflito,
    erro: "Conflito de versão.",
  };
}

describe("rdoAfterConflict", () => {
  it("adota a versão atual do servidor informada no conflito", () => {
    const updated = rdoAfterConflict(
      baseRdo,
      conflictResult({
        entidadeTipo: "RDO",
        entidadeId: "rdo-1",
        baseVersao: 3,
        versaoAtual: 12,
      }),
      "2026-07-02T11:00:00.000Z",
    );

    expect(updated.syncStatus).toBe("CONFLICT");
    expect(updated.versaoEntidade).toBe(12);
    expect(updated.updatedAt).toBe("2026-07-02T11:00:00.000Z");
  });

  it("mantém a versão local quando o conflito não traz versão válida", () => {
    expect(
      rdoAfterConflict(baseRdo, conflictResult(null), "t").versaoEntidade,
    ).toBe(3);

    expect(
      rdoAfterConflict(
        baseRdo,
        conflictResult({ versaoAtual: "não numérico" }),
        "t",
      ).versaoEntidade,
    ).toBe(3);
  });
});
