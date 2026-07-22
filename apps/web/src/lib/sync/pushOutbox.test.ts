import { beforeEach, describe, expect, it, vi } from "vitest";

import type { OutboxMutationRecord } from "../db/db.types";

const mocks = vi.hoisted(() => ({
  list: vi.fn(),
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
});
