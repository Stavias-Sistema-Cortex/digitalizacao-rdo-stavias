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
  repairObra: vi.fn(async () => 0),
  repairMaoObra: vi.fn(async () => 0),
  hydrateRdo: vi.fn(async () => 0),
  repairRdo: vi.fn(async () => 0),
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
  recoverInterruptedMutations: mocks.recover,
  repairMissingMaoObraReferencesForSync: mocks.repairMaoObra,
  repairMissingObraReferencesForSync: mocks.repairObra,
  resolveCanonicalUploadReplacements: mocks.resolveUploads,
  recoverCanonicalConflictReconciliations: mocks.recoverConflicts,
}));
vi.mock("../db/localRdoService", () => ({
  hydrateBlockedRdoCreationContextsForSync: mocks.hydrateRdo,
  repairRdoCreateMutationsForSync: mocks.repairRdo,
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
