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
  repairRdo: vi.fn(async () => 0),
  resolveUploads: vi.fn(async () => 0),
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
}));

vi.mock("./syncSession", () => ({
  captureOnlineSyncSession: mocks.capture,
  assertSyncSession: mocks.assert,
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
}));
vi.mock("../db/localRdoService", () => ({
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

import { syncNow } from "./syncEngine";

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
    await Promise.resolve();
    mocks.currentFingerprint = "session-b";
    const second = syncNow();

    const sharedAcrossSessions = second === first;
    releaseFirst();
    await Promise.allSettled([first, second]);
    expect(sharedAcrossSessions).toBe(false);
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
});
