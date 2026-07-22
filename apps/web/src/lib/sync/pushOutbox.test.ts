import { beforeEach, describe, expect, it, vi } from "vitest";

import type {
  CanonicalOutboxMutationRecord,
  LegacyOutboxMutationRecord,
} from "../db/db.types";

const mocks = vi.hoisted(() => ({
  listReadyPendingOutboxMutations: vi.fn(),
  applyPushResultAtomically: vi.fn(),
  markMutationAsSyncing: vi.fn(),
  rejectNonCanonicalMutation: vi.fn(),
  returnMutationToPending: vi.fn(),
  pushMutationsApi: vi.fn(),
}));

vi.mock("../db/outboxRepository", () => ({
  listReadyPendingOutboxMutations:
    mocks.listReadyPendingOutboxMutations,
}));
vi.mock("./syncStorage", () => ({
  applyPushResultAtomically: mocks.applyPushResultAtomically,
  markMutationAsSyncing: mocks.markMutationAsSyncing,
  rejectNonCanonicalMutation: mocks.rejectNonCanonicalMutation,
  returnMutationToPending: mocks.returnMutationToPending,
}));
vi.mock("./syncApiClient", () => ({
  pushMutationsApi: mocks.pushMutationsApi,
}));

import { pushOutbox } from "./pushOutbox";

function mutation(id: string): CanonicalOutboxMutationRecord {
  return {
    contractVersion: 13,
    clientMutationId: id,
    entidadeTipo: "RDO",
    entidadeId: `rdo-${id}`,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: { id: `rdo-${id}` },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-07-17T12:00:00.000Z",
    updatedAt: "2026-07-17T12:00:00.000Z",
    transport: "SYNC_PUSH",
    dependsOnMutationIds: [],
    correlationId: `correlation-${id}`,
    fieldPatch: {
      changed: { id: `rdo-${id}` },
      baseValues: {},
    },
    trace: {
      actorId: "00000000-0000-4000-8000-000000000001",
      deviceId: "00000000-0000-4000-8000-000000000002",
      authorizationScope: [
        "00000000-0000-4000-8000-000000000003",
      ],
      correlationId: `correlation-${id}`,
      causationId: null,
      ontologyEventId: "00000000-0000-4000-8000-000000000004",
      payloadHash: "a".repeat(64),
    },
    nextAttemptAt: null,
    blockedReason: null,
  };
}

describe("pushOutbox retry classification", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.applyPushResultAtomically.mockResolvedValue(undefined);
    mocks.markMutationAsSyncing.mockResolvedValue(undefined);
    mocks.rejectNonCanonicalMutation.mockResolvedValue(undefined);
    mocks.returnMutationToPending.mockResolvedValue(undefined);
  });

  it("blocks a noncanonical record locally before network dispatch", async () => {
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
      criadaNoClienteEm: "2026-07-17T11:00:00.000Z",
      updatedAt: "2026-07-17T11:00:00.000Z",
    };
    mocks.listReadyPendingOutboxMutations.mockResolvedValue([
      legacyMutation,
    ]);

    await expect(pushOutbox("device-1")).resolves.toEqual({
      pushed: 0,
      applied: 0,
      errors: 1,
      retryableErrors: 0,
      conflicts: 0,
    });
    expect(mocks.pushMutationsApi).not.toHaveBeenCalled();
    expect(mocks.markMutationAsSyncing).not.toHaveBeenCalled();
    expect(mocks.rejectNonCanonicalMutation).toHaveBeenCalledWith(
      legacyMutation.clientMutationId,
      "Rastro canônico ausente; requer revisão.",
    );
  });

  it("does not retry a server rejection while keeping a missing result retryable", async () => {
    const rejected = mutation("rejected");
    const missingResult = mutation("missing-result");
    mocks.listReadyPendingOutboxMutations.mockResolvedValue([
      rejected,
      missingResult,
    ]);
    mocks.pushMutationsApi.mockResolvedValue({
      resultados: [
        {
          clientMutationId: rejected.clientMutationId,
          status: "REJEITADA",
          entidadeTipo: "RDO",
          entidadeId: rejected.entidadeId,
          erro: "Escopo revogado",
        },
      ],
    });

    await expect(pushOutbox("device-1")).resolves.toEqual({
      pushed: 2,
      applied: 0,
      errors: 2,
      retryableErrors: 1,
      conflicts: 0,
    });
    expect(mocks.applyPushResultAtomically).toHaveBeenCalledTimes(1);
    expect(mocks.returnMutationToPending).toHaveBeenCalledWith(
      missingResult.clientMutationId,
      "O servidor não retornou resultado para esta mutação.",
    );
  });

  it.each([
    ["APLICADA", 1, 0, 0, 0],
    ["CONCILIADA", 1, 0, 0, 0],
    ["DESCARTADA", 0, 0, 0, 1],
    ["REJEITADA", 0, 1, 0, 0],
    ["ERRO", 0, 1, 1, 0],
  ] as const)(
    "classifies server status %s without erasing its semantics",
    async (
      status,
      applied,
      errors,
      retryableErrors,
      conflicts,
    ) => {
      const queued = mutation(`result-${status}`);
      mocks.listReadyPendingOutboxMutations.mockResolvedValue([
        queued,
      ]);
      mocks.pushMutationsApi.mockResolvedValue({
        resultados: [{
          clientMutationId: queued.clientMutationId,
          status,
          entidadeTipo: queued.entidadeTipo,
          entidadeId: queued.entidadeId,
        }],
      });

      await expect(pushOutbox("device-1")).resolves.toEqual({
        pushed: 1,
        applied,
        errors,
        retryableErrors,
        conflicts,
      });
    },
  );

  it.each([
    ["successful", "APLICADA"],
    ["conflicted", "DESCARTADA"],
  ] as const)(
    "does not requeue an earlier %s terminal result when a later result fails",
    async (_label, terminalStatus) => {
      const terminal = mutation(`terminal-${terminalStatus}`);
      const laterFailure = mutation(`later-${terminalStatus}`);
      mocks.listReadyPendingOutboxMutations.mockResolvedValue([
        terminal,
        laterFailure,
      ]);
      mocks.pushMutationsApi.mockResolvedValue({
        resultados: [
          {
            clientMutationId: terminal.clientMutationId,
            status: terminalStatus,
            entidadeTipo: "RDO",
            entidadeId: terminal.entidadeId,
          },
          {
            clientMutationId: laterFailure.clientMutationId,
            status: "APLICADA",
            entidadeTipo: "RDO",
            entidadeId: laterFailure.entidadeId,
          },
        ],
      });
      mocks.applyPushResultAtomically.mockImplementation(
        async (result: { clientMutationId: string }) => {
          if (
            result.clientMutationId ===
            laterFailure.clientMutationId
          ) {
            throw new Error("Falha ao persistir resultado posterior");
          }
        },
      );

      await expect(pushOutbox("device-1")).rejects.toThrow(
        "Falha ao persistir resultado posterior",
      );

      expect(mocks.returnMutationToPending).not.toHaveBeenCalledWith(
        terminal.clientMutationId,
        expect.any(String),
      );
      expect(mocks.returnMutationToPending).toHaveBeenCalledTimes(1);
      expect(mocks.returnMutationToPending).toHaveBeenCalledWith(
        laterFailure.clientMutationId,
        "Falha ao persistir resultado posterior",
      );
    },
  );
});
