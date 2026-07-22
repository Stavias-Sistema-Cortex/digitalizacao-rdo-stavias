import { describe, expect, it } from "vitest";

import type {
  CanonicalOutboxMutationRecord,
  LegacyOutboxMutationRecord,
} from "../db/db.types";
import { mutationPayloadHash } from "./mutationEnvelope";
import { toPushMutationRequest } from "./sync.types";

const canonicalMutation: CanonicalOutboxMutationRecord = {
  contractVersion: 13,
  clientMutationId: "00000000-0000-4000-8000-000000000010",
  entidadeTipo: "RDO",
  entidadeId: "00000000-0000-4000-8000-000000000011",
  operacao: "ATUALIZAR_RDO_RASCUNHO",
  baseVersao: 4,
  payload: {
    id: "00000000-0000-4000-8000-000000000011",
    titulo: "Versao local",
  },
  status: "PENDING",
  tentativas: 0,
  ultimaTentativaEm: null,
  ultimoErro: null,
  conflito: null,
  criadaNoClienteEm: "2026-07-20T10:00:00.000Z",
  updatedAt: "2026-07-20T10:00:00.000Z",
  transport: "SYNC_PUSH",
  dependsOnMutationIds: ["00000000-0000-4000-8000-000000000012"],
  correlationId: "00000000-0000-4000-8000-000000000010",
  fieldPatch: {
    changed: { titulo: "Versao local" },
    baseValues: { titulo: "Versao base" },
  },
  trace: {
    actorId: "00000000-0000-4000-8000-000000000013",
    deviceId: "00000000-0000-4000-8000-000000000014",
    authorizationScope: [
      "00000000-0000-4000-8000-000000000015",
    ],
    correlationId: "00000000-0000-4000-8000-000000000010",
    causationId: "00000000-0000-4000-8000-000000000016",
    ontologyEventId: "00000000-0000-4000-8000-000000000017",
    payloadHash: "91b438891eaabe9fc19ae7962ba1d54aafb3239253abcb521204360e8d348d45",
  },
  nextAttemptAt: null,
  blockedReason: null,
};

describe("canonical sync push wire contract", () => {
  it("serializes a server-valid canonical envelope with exact API property names", async () => {
    const request = toPushMutationRequest(canonicalMutation);
    const json = JSON.parse(JSON.stringify(request));

    expect(await mutationPayloadHash(canonicalMutation.payload)).toBe(
      canonicalMutation.trace.payloadHash,
    );

    expect(json).toEqual({
      clientMutationId: canonicalMutation.clientMutationId,
      entidadeTipo: canonicalMutation.entidadeTipo,
      entidadeId: canonicalMutation.entidadeId,
      operacao: canonicalMutation.operacao,
      baseVersao: canonicalMutation.baseVersao,
      payload: canonicalMutation.payload,
      criadaNoClienteEm: canonicalMutation.criadaNoClienteEm,
      correlacaoId: canonicalMutation.correlationId,
      fieldPatch: canonicalMutation.fieldPatch,
      actorId: canonicalMutation.trace.actorId,
      authorizationScope:
        canonicalMutation.trace.authorizationScope,
      ontologyEventId: canonicalMutation.trace.ontologyEventId,
      payloadHash: canonicalMutation.trace.payloadHash,
      causationId: canonicalMutation.trace.causationId,
      dependsOnMutationIds:
        canonicalMutation.dependsOnMutationIds,
    });
  });

  it("rejects a legacy record instead of emitting a partial envelope", () => {
    const legacyMutation: LegacyOutboxMutationRecord = {
      clientMutationId: "legacy-mutation",
      entidadeTipo: "RDO",
      entidadeId: "legacy-rdo",
      operacao: "CRIAR_RDO",
      baseVersao: null,
      payload: { id: "legacy-rdo" },
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: null,
      conflito: null,
      criadaNoClienteEm: "2026-07-20T09:00:00.000Z",
      updatedAt: "2026-07-20T09:00:00.000Z",
    };

    expect(() =>
      toPushMutationRequest(
        legacyMutation as CanonicalOutboxMutationRecord,
      ),
    ).toThrowError(
      "Mutacao legacy-mutation bloqueada: envelope canonico v13 incompleto.",
    );
  });
});
