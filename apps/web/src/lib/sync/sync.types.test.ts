import { describe, expect, it } from "vitest";

import type { CanonicalOutboxMutationRecord } from "../db/db.types";
import { toPushMutationRequest } from "./sync.types";

function canonicalMutation(): CanonicalOutboxMutationRecord {
  const instant = "2026-07-21T12:00:00.000Z";
  return {
    schemaVersion: 13,
    clientMutationId: "00000000-0000-4000-8000-000000000001",
    deviceId: "00000000-0000-4000-8000-000000000002",
    userId: "00000000-0000-4000-8000-000000000003",
    obraId: "00000000-0000-4000-8000-000000000004",
    entityType: "RDO",
    entityId: "00000000-0000-4000-8000-000000000005",
    operation: "CREATE",
    baseVersion: null,
    changedFields: ["id"],
    occurredAt: instant,
    payload: { id: "00000000-0000-4000-8000-000000000005" },
    entidadeTipo: "RDO",
    entidadeId: "00000000-0000-4000-8000-000000000005",
    operacao: "CRIAR_RDO",
    baseVersao: null,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: instant,
    updatedAt: instant,
    transport: "SYNC_PUSH",
    dependsOnMutationIds: [],
    correlationId: "00000000-0000-4000-8000-000000000001",
    causationId: null,
    fieldPatch: { changed: { id: "value" }, baseValues: {} },
    trace: {
      actorId: "00000000-0000-4000-8000-000000000003",
      deviceId: "00000000-0000-4000-8000-000000000002",
      authorizationScope: ["00000000-0000-4000-8000-000000000004"],
      correlationId: "00000000-0000-4000-8000-000000000001",
      causationId: null,
      ontologyEventId: "00000000-0000-4000-8000-000000000006",
      payloadHash: "0".repeat(64),
    },
    nextAttemptAt: null,
    blockedReason: null,
  };
}

describe("toPushMutationRequest canonical boundary", () => {
  it("serializes a coherent canonical mutation", () => {
    expect(toPushMutationRequest(canonicalMutation())).toMatchObject({
      entidadeTipo: "RDO",
      operacao: "CRIAR_RDO",
      baseVersao: null,
    });
  });

  it("fails closed when canonical aliases diverge", () => {
    expect(() =>
      toPushMutationRequest({
        ...canonicalMutation(),
        operacao: "ATUALIZAR_RDO_RASCUNHO",
      }),
    ).toThrow(/operation aliases are incoherent/i);
    expect(() =>
      toPushMutationRequest({
        ...canonicalMutation(),
        entidadeId: "00000000-0000-4000-8000-000000000007",
      }),
    ).toThrow(/transport aliases are incoherent/i);
  });
});
