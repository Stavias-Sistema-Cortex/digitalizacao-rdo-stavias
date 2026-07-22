import { describe, expect, it } from "vitest";

import type { CanonicalOutboxMutationRecord } from "../db/db.types";
import { mutationPayloadHash } from "./mutationEnvelope";
import { toPushMutationRequest } from "./sync.types";

async function canonicalMutation(): Promise<CanonicalOutboxMutationRecord> {
  const instant = "2026-07-21T12:00:00.000Z";
  const payload = { id: "00000000-0000-4000-8000-000000000005" };
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
    payload,
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
      payloadHash: await mutationPayloadHash(payload),
    },
    nextAttemptAt: null,
    blockedReason: null,
  };
}

describe("toPushMutationRequest canonical boundary", () => {
  it("serializes a coherent canonical mutation", async () => {
    await expect(toPushMutationRequest(await canonicalMutation())).resolves.toMatchObject({
      entidadeTipo: "RDO",
      operacao: "CRIAR_RDO",
      baseVersao: null,
    });
  });

  it("fails closed when canonical aliases diverge", async () => {
    await expect(
      toPushMutationRequest({
        ...await canonicalMutation(),
        operacao: "ATUALIZAR_RDO_RASCUNHO",
      }),
    ).rejects.toThrow(/operation aliases are incoherent/i);
    await expect(
      toPushMutationRequest({
        ...await canonicalMutation(),
        entidadeId: "00000000-0000-4000-8000-000000000007",
      }),
    ).rejects.toThrow(/transport aliases are incoherent/i);
  });

  it("fails closed when the canonical payload no longer matches its SHA-256", async () => {
    const mutation = await canonicalMutation();
    await expect(
      toPushMutationRequest({
        ...mutation,
        payload: { ...mutation.payload, tampered: true },
      }),
    ).rejects.toThrow(/payload hash is incoherent/i);
  });
});
