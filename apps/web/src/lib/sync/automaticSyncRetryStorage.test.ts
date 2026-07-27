import { describe, expect, it } from "vitest";

import type { OutboxMutationRecord } from "../db/db.types";
import {
  earliestRetryAt,
  mutationAfterRetryScheduled,
  retryDispositionForResult,
} from "./automaticSyncRetryStorage";

function pending(id: string): OutboxMutationRecord {
  return {
    clientMutationId: id,
    entidadeTipo: "RDO",
    entidadeId: `rdo-${id}`,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: {},
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-07-22T12:00:00.000Z",
    updatedAt: "2026-07-22T12:00:00.000Z",
  };
}

describe("per-row automatic retry metadata", () => {
  it("persists attempt, nextAttemptAt and safe code on the failed row only", () => {
    const left = mutationAfterRetryScheduled(
      pending("left"),
      {
        safeCode: "NETWORK_TRANSIENT",
        message: "offline",
        now: Date.parse("2026-07-22T12:00:00.000Z"),
        random: () => 0,
      },
    );
    const right = pending("right");

    expect(left).toMatchObject({
      status: "PENDING",
      retryAttempt: 1,
      nextAttemptAt: "2026-07-22T12:00:01.000Z",
      lastSafeCode: "NETWORK_TRANSIENT",
    });
    expect(right).not.toHaveProperty("nextAttemptAt");
  });

  it("loads the earliest valid future retry across independent rows", () => {
    expect(
      earliestRetryAt([
        {
          ...pending("late"),
          nextAttemptAt: "2026-07-22T12:00:10.000Z",
        },
        {
          ...pending("terminal"),
          status: "REJECTED",
          nextAttemptAt: "2026-07-22T12:00:01.000Z",
        },
        {
          ...pending("early"),
          nextAttemptAt: "2026-07-22T12:00:05.000Z",
        },
      ]),
    ).toBe("2026-07-22T12:00:05.000Z");
  });

  it("classifies validation/auth rejection as terminal and server error as transient", () => {
    expect(
      retryDispositionForResult({
        clientMutationId: "m-1",
        status: "REJEITADA",
        resultado: {
          rejeicao: { categoria: "RELATED_ENTITY_SCOPE" },
        },
      }),
    ).toEqual({ retryable: false, safeCode: "RELATED_ENTITY_SCOPE" });
    expect(
      retryDispositionForResult({
        clientMutationId: "m-2",
        status: "ERRO",
        erro: "Falha interna.",
      }),
    ).toEqual({ retryable: true, safeCode: "SERVER_TRANSIENT" });
  });

  it("does not persist an unbounded or display-oriented server category as a safe code", () => {
    expect(
      retryDispositionForResult({
        clientMutationId: "m-3",
        status: "REJEITADA",
        resultado: {
          rejeicao: { categoria: "<script>alert(1)</script>" },
        },
      }),
    ).toEqual({ retryable: false, safeCode: "SERVER_REJECTED" });
  });
});
