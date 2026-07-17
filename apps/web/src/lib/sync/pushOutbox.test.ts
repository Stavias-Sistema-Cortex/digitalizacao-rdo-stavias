import { beforeEach, describe, expect, it, vi } from "vitest";

import type { OutboxMutationRecord } from "../db/db.types";

const mocks = vi.hoisted(() => ({
  listReadyPendingOutboxMutations: vi.fn(),
  applyPushResultAtomically: vi.fn(),
  markMutationAsSyncing: vi.fn(),
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
  returnMutationToPending: mocks.returnMutationToPending,
}));
vi.mock("./syncApiClient", () => ({
  pushMutationsApi: mocks.pushMutationsApi,
}));

import { pushOutbox } from "./pushOutbox";

function mutation(id: string): OutboxMutationRecord {
  return {
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
  };
}

describe("pushOutbox retry classification", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.applyPushResultAtomically.mockResolvedValue(undefined);
    mocks.markMutationAsSyncing.mockResolvedValue(undefined);
    mocks.returnMutationToPending.mockResolvedValue(undefined);
  });

  it("counts only missing server results returned to PENDING as retryable", async () => {
    const permanentError = mutation("permanent-error");
    const missingResult = mutation("missing-result");
    mocks.listReadyPendingOutboxMutations.mockResolvedValue([
      permanentError,
      missingResult,
    ]);
    mocks.pushMutationsApi.mockResolvedValue({
      resultados: [
        {
          clientMutationId: permanentError.clientMutationId,
          status: "ERRO",
          entidadeTipo: "RDO",
          entidadeId: permanentError.entidadeId,
          erro: "Validação permanente",
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
