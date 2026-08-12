import { beforeEach, describe, expect, it, vi } from "vitest";

import type { OutboxMutationRecord } from "../db/db.types";
import { ApiError } from "../api/apiError";

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
  get: vi.fn(),
  serialize: vi.fn(),
  api: vi.fn(),
  mark: vi.fn(),
  apply: vi.fn(),
  retry: vi.fn(),
  reject: vi.fn(),
  reconcile: vi.fn(),
  capture: vi.fn(() => ({ fingerprint: "session", userId: "user" })),
  assert: vi.fn(),
}));

vi.mock("../db/outboxRepository", () => ({
  listReadyPendingOutboxMutations: mocks.list,
  getOutboxMutation: mocks.get,
}));
vi.mock("./sync.types", () => ({
  toPushMutationRequest: mocks.serialize,
}));
vi.mock("./syncApiClient", () => ({ pushMutationsApi: mocks.api }));
vi.mock("./syncStorage", () => ({
  applyPushResultAtomically: mocks.apply,
  markMutationAsSyncing: mocks.mark,
  returnMutationToPending: mocks.retry,
  rejectMutationLocally: mocks.reject,
  reconcileCanonicalConflict: mocks.reconcile,
}));
vi.mock("./syncSession", () => ({
  captureOnlineSyncSession: mocks.capture,
  assertSyncSession: mocks.assert,
}));

import { pushOutbox } from "./pushOutbox";

function mutation(id: string, schemaVersion?: 13): OutboxMutationRecord {
  return {
    clientMutationId: id,
    entidadeTipo: "RDO",
    entidadeId: `entity-${id}`,
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
    ...(schemaVersion === 13 ? { schemaVersion } : {}),
  } as OutboxMutationRecord;
}

describe("pushOutbox row isolation", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.reconcile.mockResolvedValue(null);
    mocks.get.mockImplementation(async (id: string) => ({
      clientMutationId: id,
      status: "SYNCING",
    }));
    mocks.mark.mockImplementation(
      async (row: OutboxMutationRecord) => ({
        ...row,
        status: "SYNCING",
      }),
    );
  });

  it("serializes only the exact snapshot atomically transitioned to SYNCING", async () => {
    const listedA = {
      ...mutation("same-id"),
      payload: { observacoes: "payload-A-listed" },
    };
    const coalescedB = {
      ...listedA,
      payload: { observacoes: "payload-B-before-mark" },
      updatedAt: "2026-07-22T12:00:01.000Z",
    };
    const lockedB = {
      ...coalescedB,
      status: "SYNCING" as const,
    };
    mocks.list.mockResolvedValue([listedA]);
    mocks.mark.mockResolvedValue(lockedB);
    mocks.serialize.mockImplementation(
      async (row: OutboxMutationRecord) => ({
        clientMutationId: row.clientMutationId,
        payload: row.payload,
      }),
    );
    mocks.api.mockResolvedValue({
      resultados: [{ clientMutationId: "same-id", status: "APLICADA" }],
    });

    await pushOutbox("device-1");

    expect(mocks.mark).toHaveBeenCalledWith(
      listedA,
      expect.any(Object),
    );
    expect(mocks.serialize).toHaveBeenCalledWith(lockedB);
    expect(mocks.mark.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.serialize.mock.invocationCallOrder[0],
    );
    expect(mocks.api).toHaveBeenCalledWith({
      dispositivoId: "device-1",
      mutacoes: [
        {
          clientMutationId: "same-id",
          payload: { observacoes: "payload-B-before-mark" },
        },
      ],
    });
  });

  it("quarantines one corrupt canonical row and still pushes an independent row", async () => {
    const corrupt = mutation("corrupt", 13);
    const valid = mutation("valid");
    mocks.list.mockResolvedValue([corrupt, valid]);
    mocks.serialize.mockImplementation(async (row: OutboxMutationRecord) => {
      if (row.clientMutationId === "corrupt") {
        throw new TypeError("Canonical mutation payload hash is incoherent.");
      }
      return { clientMutationId: row.clientMutationId };
    });
    mocks.api.mockResolvedValue({
      resultados: [{ clientMutationId: "valid", status: "APLICADA" }],
    });

    const summary = await pushOutbox("device-1");

    expect(mocks.reject).toHaveBeenCalledWith(
      "corrupt",
      "LOCAL_CANONICAL_INVALID",
      expect.stringContaining("payload hash"),
      expect.any(Object),
    );
    expect(mocks.api).toHaveBeenCalledWith({
      dispositivoId: "device-1",
      mutacoes: [{ clientMutationId: "valid" }],
    });
    expect(mocks.apply).toHaveBeenCalledTimes(1);
    expect(summary).toMatchObject({ pushed: 1, applied: 1, errors: 1 });
  });

  it("continues after a terminal rejection and counts only transient errors for retry", async () => {
    const rejected = mutation("rejected", 13);
    const transient = mutation("transient", 13);
    mocks.list.mockResolvedValue([rejected, transient]);
    mocks.serialize.mockImplementation(async (row: OutboxMutationRecord) => ({
      clientMutationId: row.clientMutationId,
    }));
    mocks.api.mockResolvedValue({
      resultados: [
        {
          clientMutationId: "rejected",
          status: "REJEITADA",
          resultado: { rejeicao: { categoria: "RELATED_ENTITY_SCOPE" } },
        },
        { clientMutationId: "transient", status: "ERRO" },
      ],
    });

    const summary = await pushOutbox("device-1");

    expect(mocks.apply).toHaveBeenCalledTimes(2);
    expect(summary).toMatchObject({
      pushed: 2,
      applied: 0,
      errors: 2,
      retryableErrors: 1,
    });
  });

  it("quarantines a row whose local result application is corrupt and continues", async () => {
    const broken = mutation("broken", 13);
    const valid = mutation("valid", 13);
    mocks.list.mockResolvedValue([broken, valid]);
    mocks.serialize.mockImplementation(async (row: OutboxMutationRecord) => ({
      clientMutationId: row.clientMutationId,
    }));
    mocks.api.mockResolvedValue({
      resultados: [
        { clientMutationId: "broken", status: "APLICADA" },
        { clientMutationId: "valid", status: "APLICADA" },
      ],
    });
    mocks.apply.mockImplementationOnce(async () => {
      throw new Error("evento local duplicado");
    });

    const summary = await pushOutbox("device-1");

    expect(mocks.reject).toHaveBeenCalledWith(
      "broken",
      "LOCAL_RESULT_APPLY_INVALID",
      "evento local duplicado",
      expect.any(Object),
    );
    expect(mocks.apply).toHaveBeenCalledTimes(2);
    expect(summary).toMatchObject({ pushed: 2, applied: 1, errors: 1 });
  });

  it("reports the exact mutation ids whose applied result was persisted locally", async () => {
    const applied = mutation("applied", 13);
    const rejected = mutation("rejected", 13);
    const corrupt = mutation("corrupt-result", 13);
    mocks.list.mockResolvedValue([applied, rejected, corrupt]);
    mocks.serialize.mockImplementation(async (row: OutboxMutationRecord) => ({
      clientMutationId: row.clientMutationId,
    }));
    mocks.api.mockResolvedValue({
      resultados: [
        { clientMutationId: "applied", status: "APLICADA" },
        { clientMutationId: "rejected", status: "REJEITADA" },
        { clientMutationId: "corrupt-result", status: "APLICADA" },
      ],
    });
    mocks.apply.mockImplementation(async (result: {
      clientMutationId: string;
    }) => {
      if (result.clientMutationId === "corrupt-result") {
        throw new Error("resultado local inválido");
      }
    });

    const summary = await pushOutbox("device-1");

    expect(summary.appliedMutationIds).toEqual(["applied"]);
    expect(summary.errorMutationIds).toEqual([
      "rejected",
      "corrupt-result",
    ]);
    expect(summary.handledMutationIds).toEqual([
      "applied",
      "rejected",
      "corrupt-result",
    ]);
    expect(summary).toMatchObject({
      pushed: 3,
      applied: 1,
      errors: 2,
    });
  });

  it.each([
    { status: 403, code: "ACCESS_DENIED" },
    { status: 422, code: "CANONICAL_INVALID" },
  ])(
    "keeps request-level HTTP $status terminal until user or session action",
    async ({ status, code }) => {
      const blocked = mutation(`terminal-${status}`, 13);
      mocks.list.mockResolvedValue([blocked]);
      mocks.serialize.mockResolvedValue({
        clientMutationId: blocked.clientMutationId,
      });
      mocks.api.mockRejectedValue(
        new ApiError("Falha segura.", status, code),
      );

      await expect(pushOutbox("device-1")).rejects.toBeInstanceOf(ApiError);

      expect(mocks.reject).toHaveBeenCalledWith(
        blocked.clientMutationId,
        code,
        "Falha segura.",
        expect.any(Object),
      );
      expect(mocks.retry).not.toHaveBeenCalled();
    },
  );

  it.each([429, 503])(
    "schedules request-level HTTP %i as transient",
    async (status) => {
      const pending = mutation(`transient-${status}`, 13);
      mocks.list.mockResolvedValue([pending]);
      mocks.serialize.mockResolvedValue({
        clientMutationId: pending.clientMutationId,
      });
      mocks.api.mockRejectedValue(
        new ApiError("Falha segura.", status, null),
      );

      await expect(pushOutbox("device-1")).rejects.toBeInstanceOf(ApiError);

      expect(mocks.retry).toHaveBeenCalledWith(
        pending.clientMutationId,
        "Falha segura.",
        `HTTP_${status}_TRANSIENT`,
        expect.any(Object),
      );
      expect(mocks.reject).not.toHaveBeenCalled();
    },
  );
  /*
   * O defeito que este teste prende: um 403 sem código não é veredito sobre
   * mutação nenhuma — é o filtro de CSRF diante de um cookie duplicado, ou um
   * proxy que comeu o cabeçalho. Antes, ele recusava terminalmente o lote
   * inteiro, e o trabalho do aparelho morria ali: recusa terminal não é
   * retentada pelo ciclo nem alcançada pelo botão de reenviar.
   */
  it("devolve o lote à fila quando o 403 vem sem código do servidor", async () => {
    const bloqueada = mutation("sem-veredito", 13);
    mocks.list.mockResolvedValue([bloqueada]);
    mocks.serialize.mockResolvedValue({
      clientMutationId: bloqueada.clientMutationId,
    });
    mocks.api.mockRejectedValue(
      new ApiError("Token CSRF inválido.", 403, null),
    );

    await expect(pushOutbox("device-1")).rejects.toBeInstanceOf(ApiError);

    expect(mocks.retry).toHaveBeenCalledWith(
      bloqueada.clientMutationId,
      "Token CSRF inválido.",
      "HTTP_403_TERMINAL",
      expect.any(Object),
    );
    expect(mocks.reject).not.toHaveBeenCalled();
  });

  it("devolve à fila também quando a rota some no meio de um deploy", async () => {
    const bloqueada = mutation("rota-sumiu", 13);
    mocks.list.mockResolvedValue([bloqueada]);
    mocks.serialize.mockResolvedValue({
      clientMutationId: bloqueada.clientMutationId,
    });
    mocks.api.mockRejectedValue(new ApiError("Não encontrado.", 404, null));

    await expect(pushOutbox("device-1")).rejects.toBeInstanceOf(ApiError);

    expect(mocks.retry).toHaveBeenCalled();
    expect(mocks.reject).not.toHaveBeenCalled();
  });

  /*
   * Falhar ao trancar a linha é armazenamento cheio, transação abortada, ou a
   * tela tendo superado a mutação entre a listagem e a trava. Nada disso julga
   * o conteúdo, e recusar aqui apagava até a marca de superação recém-escrita.
   */
  it("devolve à fila a linha que não pôde ser trancada", async () => {
    const disputada = mutation("sem-trava", 13);
    mocks.list.mockResolvedValue([disputada]);
    mocks.mark.mockRejectedValue(new Error("Armazenamento sem espaço."));

    const summary = await pushOutbox("device-1");

    expect(mocks.retry).toHaveBeenCalledWith(
      disputada.clientMutationId,
      "Armazenamento sem espaço.",
      "LOCAL_LOCK_FAILED",
      expect.any(Object),
    );
    expect(mocks.reject).not.toHaveBeenCalled();
    expect(summary.pushed).toBe(0);
  });

  it("não revive a linha que a tela já tinha superado", async () => {
    const superada = mutation("superada", 13);
    mocks.list.mockResolvedValue([superada]);
    mocks.mark.mockRejectedValue(new Error("A mutação não está pendente."));
    mocks.get.mockResolvedValue({
      clientMutationId: superada.clientMutationId,
      status: "SUPERSEDED",
    });

    await pushOutbox("device-1");

    expect(mocks.retry).not.toHaveBeenCalled();
    expect(mocks.reject).not.toHaveBeenCalled();
  });
});
