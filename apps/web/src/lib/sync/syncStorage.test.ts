import { describe, expect, it } from "vitest";

import {
  mutationAfterResolvableConflict,
  rdoAfterConflict,
} from "./syncStorage";
import type {
  LocalRdoRecord,
  OutboxMutationRecord,
} from "../db/db.types";
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

const baseMutation = {
  clientMutationId: "m-1",
  entidadeTipo: "RDO",
  entidadeId: "rdo-1",
  operacao: "ATUALIZAR_RDO_RASCUNHO",
  baseVersao: 3,
  payload: { numeroRdo: "RDO-1" },
  status: "CONFLICT",
  tentativas: 2,
  ultimaTentativaEm: "2026-07-02T10:30:00.000Z",
  ultimoErro: "Conflito de versão.",
  conflito: {
    entidadeTipo: "RDO",
    entidadeId: "rdo-1",
    baseVersao: 3,
    versaoAtual: 12,
  },
  criadaNoClienteEm: "2026-07-02T10:00:00.000Z",
  updatedAt: "2026-07-02T11:00:00.000Z",
} as OutboxMutationRecord;

describe("mutationAfterResolvableConflict", () => {
  it("reabre conflito com a versão atual do servidor como base", () => {
    const updated = mutationAfterResolvableConflict(
      baseMutation,
      "2026-07-02T12:00:00.000Z",
      "m-1-retry",
    );

    expect(updated).not.toBeNull();
    expect(updated?.clientMutationId).toBe("m-1-retry");
    expect(updated?.status).toBe("PENDING");
    expect(updated?.baseVersao).toBe(12);
    expect(updated?.tentativas).toBe(0);
    expect(updated?.ultimaTentativaEm).toBeNull();
    expect(updated?.conflito).toBeNull();
    expect(updated?.criadaNoClienteEm).toBe(
      "2026-07-02T12:00:00.000Z",
    );
    expect(updated?.updatedAt).toBe("2026-07-02T12:00:00.000Z");
  });

  it("mantém bloqueado quando o conflito não informa versão atual válida", () => {
    expect(
      mutationAfterResolvableConflict(
        {
          ...baseMutation,
          conflito: null,
        },
        "2026-07-02T12:00:00.000Z",
      ),
    ).toBeNull();

    expect(
      mutationAfterResolvableConflict(
        {
          ...baseMutation,
          conflito: {
            versaoAtual: "12",
          },
        },
        "2026-07-02T12:00:00.000Z",
      ),
    ).toBeNull();
  });
});
