import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  currentFingerprint: "session-a",
  capture: vi.fn(() => ({
    fingerprint: mocks.currentFingerprint,
    userId: mocks.currentFingerprint,
  })),
  assert: vi.fn((guard: { fingerprint: string }) => {
    if (guard.fingerprint !== mocks.currentFingerprint) {
      throw new Error("A sessão mudou durante a sincronização.");
    }
  }),
  updateSyncState: vi.fn(async () => undefined),
  recover: vi.fn(async () => undefined),
  reidentificarObras: vi.fn(async () => 0),
  repairObra: vi.fn(async () => 0),
  repairMaoObra: vi.fn(async () => 0),
  hydrateRdo: vi.fn(async () => 0),
  repairRdo: vi.fn(async () => 0),
  recoverRejectedRdo: vi.fn(async () => 0),
  recoverErroredWorkforceRdo: vi.fn(async () => 0),
  queueErroredRetry: vi.fn(async () => 0),
  recoverRejectedGeometry: vi.fn(async () => 0),
  recoverRejectedArchivedObra: vi.fn(async () => 0),
  resolveUploads: vi.fn(async () => 0),
  recoverConflicts: vi.fn(async () => 0),
  ensureDevice: vi.fn(async () => "device"),
  uploads: vi.fn(async () => ({ pushed: 0, applied: 0, errors: 0 })),
  push: vi.fn(async () => ({
    pushed: 0,
    applied: 0,
    errors: 0,
    retryableErrors: 0,
    conflicts: 0,
    appliedMutationIds: [],
    handledMutationIds: [],
    errorMutationIds: [],
  })),
  pull: vi.fn(async () => ({ pulled: 0, messagingConversationIds: [] })),
  refresh: vi.fn(async () => undefined),
  ack: vi.fn(async () => 0),
  assertLease: vi.fn(async () => undefined),
  runWithLease: vi.fn(
    async (
      _guard: { fingerprint: string },
      task: (lease: {
        ownerToken: string;
        assertOwned(): Promise<void>;
      }) => Promise<unknown>,
    ) =>
      task({
        ownerToken: "owner-test",
        assertOwned: mocks.assertLease,
      }),
  ),
}));

vi.mock("./syncSession", () => ({
  captureOnlineSyncSession: mocks.capture,
  assertSyncSession: mocks.assert,
}));
vi.mock("./syncExecutionLease", () => ({
  runWithSyncExecutionLease: mocks.runWithLease,
}));
vi.mock("../../features/auth/authSession", () => ({
  hasOnlineSession: () => true,
}));
vi.mock("../db/syncStateRepository", () => ({
  updateSyncState: mocks.updateSyncState,
}));
vi.mock("./syncStorage", () => ({
  queueErroredMutationsForRetry: mocks.queueErroredRetry,
  recoverInterruptedMutations: mocks.recover,
  reidentificarObrasInexistentesForSync: mocks.reidentificarObras,
  repairMissingMaoObraReferencesForSync: mocks.repairMaoObra,
  repairMissingObraReferencesForSync: mocks.repairObra,
  resolveCanonicalUploadReplacements: mocks.resolveUploads,
  recoverCanonicalConflictReconciliations: mocks.recoverConflicts,
  recoverRejectedGeometryMutationsForSync: mocks.recoverRejectedGeometry,
  recoverRejectedArchivedObraMutationsForSync: mocks.recoverRejectedArchivedObra,
}));
vi.mock("../db/localRdoService", () => ({
  hydrateBlockedRdoCreationContextsForSync: mocks.hydrateRdo,
  repairRdoCreateMutationsForSync: mocks.repairRdo,
  recoverErroredWorkforceRdoMutationsForSync:
    mocks.recoverErroredWorkforceRdo,
  recoverRejectedRdoMutationsForSync: mocks.recoverRejectedRdo,
}));
vi.mock("./registerDevice", () => ({
  ensureRegisteredDevice: mocks.ensureDevice,
}));
vi.mock("../../features/mensagens/objectUploadSync", () => ({
  processObjectUploads: mocks.uploads,
}));
vi.mock("./pushOutbox", () => ({ pushOutbox: mocks.push }));
vi.mock("./pullEvents", () => ({ pullEvents: mocks.pull }));
vi.mock("../../features/mensagens/mensagensHydration", () => ({
  refreshMessagingAfterPull: mocks.refresh,
}));
vi.mock("./ackCursor", () => ({ acknowledgeCurrentCursor: mocks.ack }));
vi.stubGlobal("navigator", { onLine: true });
vi.stubGlobal("window", new EventTarget());

import { SYNC_COMPLETED_EVENT, syncNow } from "./syncEngine";

describe("session-scoped sync single flight", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.currentFingerprint = "session-a";
  });

  it("never shares an in-flight promise across two sessions", async () => {
    let releaseFirst!: () => void;
    mocks.updateSyncState.mockImplementationOnce(
      () =>
        new Promise<void>((resolve) => {
          releaseFirst = resolve;
        }),
    );
    const first = syncNow();
    await vi.waitFor(() => {
      expect(releaseFirst).toBeTypeOf("function");
    });
    mocks.currentFingerprint = "session-b";
    const second = syncNow();

    const sharedAcrossSessions = second === first;
    releaseFirst();
    await Promise.allSettled([first, second]);
    expect(sharedAcrossSessions).toBe(false);
  });

  it("does not recover or resend rows when another tab owns the durable lease", async () => {
    const unavailable = new Error(
      "A sincronização já está ativa em outra aba.",
    );
    mocks.runWithLease.mockRejectedValueOnce(unavailable);

    await expect(syncNow()).rejects.toBe(unavailable);

    expect(mocks.updateSyncState).not.toHaveBeenCalled();
    expect(mocks.recover).not.toHaveBeenCalled();
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it("fails closed before the next stage when the session changes", async () => {
    mocks.recover.mockImplementationOnce(async () => {
      mocks.currentFingerprint = "session-b";
    });

    await expect(syncNow()).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(mocks.ensureDevice).not.toHaveBeenCalled();
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it("recovers durable canonical conflicts before selecting rows to push", async () => {
    await syncNow();

    expect(mocks.recoverConflicts).toHaveBeenCalledTimes(1);
    expect(mocks.recoverConflicts.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.push.mock.invocationCallOrder[0],
    );
  });

  it("passes the captured session guard to every preflight repair", async () => {
    await syncNow();

    const expectedGuard = expect.objectContaining({
      fingerprint: "session-a",
    });
    expect(mocks.repairObra).toHaveBeenCalledWith(expectedGuard);
    expect(mocks.repairMaoObra).toHaveBeenCalledWith(expectedGuard);
    expect(mocks.repairRdo).toHaveBeenCalledWith(expectedGuard);
    expect(mocks.recoverRejectedRdo).toHaveBeenCalledWith(
      expectedGuard,
      {
        executionLease: expect.objectContaining({
          ownerToken: "owner-test",
        }),
      },
    );
    expect(mocks.recoverRejectedGeometry).toHaveBeenCalledWith(expectedGuard);
    expect(
      mocks.recoverRejectedRdo.mock.invocationCallOrder[0],
    ).toBeLessThan(mocks.push.mock.invocationCallOrder[0]);
    // A geometria recusada precisa voltar à fila antes do push, senão o ciclo
    // sobe sem ela e o registro segue travando a revisão.
    expect(
      mocks.recoverRejectedGeometry.mock.invocationCallOrder[0],
    ).toBeLessThan(mocks.push.mock.invocationCallOrder[0]);
  });

  it("recupera a primeira rejeição e faz no máximo um novo push no mesmo ciclo", async () => {
    mocks.recoverRejectedRdo
      .mockResolvedValueOnce(0)
      .mockImplementationOnce(
        async (
          _guard: unknown,
          options: {
            recoveredReplacementIds?: Set<string>;
            recoveredReplacementByOriginalId?: Map<string, string>;
          },
        ) => {
          options.recoveredReplacementIds?.add("recovered-rdo");
          options.recoveredReplacementByOriginalId?.set(
            "original-rdo",
            "recovered-rdo",
          );
          return 1;
        },
      );
    mocks.push
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 0,
        errors: 1,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: [],
        handledMutationIds: ["original-rdo"],
        errorMutationIds: ["original-rdo"],
      })
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 1,
        errors: 0,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: ["recovered-rdo"],
        handledMutationIds: ["recovered-rdo"],
        errorMutationIds: [],
      });

    await expect(syncNow()).resolves.toMatchObject({
      pushed: 2,
      applied: 1,
      errors: 0,
      retryableErrors: 0,
      conflicts: 0,
    });

    expect(mocks.recoverRejectedRdo).toHaveBeenCalledTimes(2);
    expect(mocks.push).toHaveBeenCalledTimes(2);
    expect(mocks.push.mock.invocationCallOrder[0]).toBeLessThan(
      mocks.recoverRejectedRdo.mock.invocationCallOrder[1],
    );
    expect(mocks.recoverRejectedRdo.mock.invocationCallOrder[1]).toBeLessThan(
      mocks.push.mock.invocationCallOrder[1],
    );
  });

  it("não entra em loop quando a substituta também é rejeitada", async () => {
    mocks.recoverRejectedRdo
      .mockResolvedValueOnce(0)
      .mockImplementationOnce(
        async (
          _guard: unknown,
          options: {
            recoveredReplacementIds?: Set<string>;
            recoveredReplacementByOriginalId?: Map<string, string>;
          },
        ) => {
          options.recoveredReplacementIds?.add("recovered-rdo");
          options.recoveredReplacementByOriginalId?.set(
            "original-rdo",
            "recovered-rdo",
          );
          return 1;
        },
      );
    mocks.push
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 0,
        errors: 1,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: [],
        handledMutationIds: ["original-rdo"],
        errorMutationIds: ["original-rdo"],
      })
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 0,
        errors: 1,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: [],
        handledMutationIds: ["recovered-rdo"],
        errorMutationIds: ["recovered-rdo"],
      });

    await expect(syncNow()).resolves.toMatchObject({
      pushed: 2,
      applied: 0,
      errors: 1,
    });

    expect(mocks.recoverRejectedRdo).toHaveBeenCalledTimes(2);
    expect(mocks.push).toHaveBeenCalledTimes(2);
  });

  it("não oculta a rejeição quando a substituta não ficou pronta para envio", async () => {
    mocks.recoverRejectedRdo
      .mockResolvedValueOnce(0)
      .mockImplementationOnce(
        async (
          _guard: unknown,
          options: {
            recoveredReplacementIds?: Set<string>;
            recoveredReplacementByOriginalId?: Map<string, string>;
          },
        ) => {
          options.recoveredReplacementIds?.add("recovered-rdo");
          options.recoveredReplacementByOriginalId?.set(
            "original-rdo",
            "recovered-rdo",
          );
          return 1;
        },
      );
    mocks.push
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 0,
        errors: 1,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: [],
        handledMutationIds: ["original-rdo"],
        errorMutationIds: ["original-rdo"],
      })
      .mockResolvedValueOnce({
        pushed: 0,
        applied: 0,
        errors: 0,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: [],
        handledMutationIds: [],
        errorMutationIds: [],
      });

    await expect(syncNow()).resolves.toMatchObject({
      pushed: 1,
      applied: 0,
      errors: 1,
    });
    expect(mocks.push).toHaveBeenCalledTimes(2);
  });

  it("não atribui a uma substituta o sucesso de outra mutação enviada no mesmo retry", async () => {
    mocks.recoverRejectedRdo
      .mockResolvedValueOnce(0)
      .mockImplementationOnce(
        async (
          _guard: unknown,
          options: {
            recoveredReplacementIds?: Set<string>;
            recoveredReplacementByOriginalId?: Map<string, string>;
          },
        ) => {
          options.recoveredReplacementIds?.add("recovered-rdo");
          options.recoveredReplacementByOriginalId?.set(
            "original-rdo",
            "recovered-rdo",
          );
          return 1;
        },
      );
    mocks.push
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 0,
        errors: 1,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: [],
        handledMutationIds: ["original-rdo"],
        errorMutationIds: ["original-rdo"],
      })
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 1,
        errors: 0,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: ["unrelated-old-mutation"],
        handledMutationIds: ["unrelated-old-mutation"],
        errorMutationIds: [],
      });

    await expect(syncNow()).resolves.toMatchObject({
      pushed: 2,
      applied: 1,
      errors: 1,
    });
  });

  it("não oculta erro atual quando uma rejeição antiga vence entre os dois recoveries", async () => {
    mocks.recoverRejectedRdo
      .mockResolvedValueOnce(0)
      .mockImplementationOnce(
        async (
          _guard: unknown,
          options: {
            recoveredReplacementIds?: Set<string>;
            recoveredReplacementByOriginalId?: Map<string, string>;
          },
        ) => {
          options.recoveredReplacementIds?.add("old-replacement");
          options.recoveredReplacementByOriginalId?.set(
            "old-rejected-original",
            "old-replacement",
          );
          return 1;
        },
      );
    mocks.push
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 0,
        errors: 1,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: [],
        handledMutationIds: ["current-unrelated-error"],
        errorMutationIds: ["current-unrelated-error"],
      })
      .mockResolvedValueOnce({
        pushed: 1,
        applied: 1,
        errors: 0,
        retryableErrors: 0,
        conflicts: 0,
        appliedMutationIds: ["old-replacement"],
        handledMutationIds: ["old-replacement"],
        errorMutationIds: [],
      });

    await expect(syncNow()).resolves.toMatchObject({
      pushed: 2,
      applied: 1,
      errors: 1,
    });
  });

  it("announces completion once only after the guarded durable state is written", async () => {
    const listener = vi.fn();
    window.addEventListener(SYNC_COMPLETED_EVENT, listener);

    await syncNow();

    expect(listener).toHaveBeenCalledTimes(1);
    expect(mocks.ack.mock.invocationCallOrder[0]).toBeLessThan(
      listener.mock.invocationCallOrder[0],
    );
    expect(mocks.updateSyncState.mock.invocationCallOrder.at(-1)).toBeLessThan(
      listener.mock.invocationCallOrder[0],
    );
    window.removeEventListener(SYNC_COMPLETED_EVENT, listener);
  });

  it("does not announce completion when the guarded session rotates", async () => {
    const listener = vi.fn();
    window.addEventListener(SYNC_COMPLETED_EVENT, listener);
    mocks.recover.mockImplementationOnce(async () => {
      mocks.currentFingerprint = "session-b";
    });

    await expect(syncNow()).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );

    expect(listener).not.toHaveBeenCalled();
    window.removeEventListener(SYNC_COMPLETED_EVENT, listener);
  });
});
