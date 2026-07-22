import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  hasOnlineSession: vi.fn(),
  updateSyncState: vi.fn(),
  ensureRegisteredDevice: vi.fn(),
}));

vi.mock("../../features/auth/authSession", () => ({
  hasOnlineSession: mocks.hasOnlineSession,
}));
vi.mock("./syncSession", () => ({
  captureOnlineSyncSession: () => {
    if (!mocks.hasOnlineSession()) {
      throw new Error("Faça login novamente para sincronizar com o servidor.");
    }
    return { fingerprint: "session", userId: "user" };
  },
  assertSyncSession: vi.fn(),
}));
vi.mock("../db/syncStateRepository", () => ({
  updateSyncState: mocks.updateSyncState,
}));
vi.mock("./registerDevice", () => ({
  ensureRegisteredDevice: mocks.ensureRegisteredDevice,
}));
vi.mock("../db/localRdoService", () => ({
  repairRdoCreateMutationsForSync: vi.fn(),
}));
vi.mock("./ackCursor", () => ({ acknowledgeCurrentCursor: vi.fn() }));
vi.mock("./pullEvents", () => ({ pullEvents: vi.fn() }));
vi.mock("./pushOutbox", () => ({ pushOutbox: vi.fn() }));
vi.mock("./syncStorage", () => ({
  queueErroredMutationsForRetry: vi.fn(),
  queueResolvableConflictsForRetry: vi.fn(),
  recoverInterruptedMutations: vi.fn(),
  recoverCanonicalConflictReconciliations: vi.fn(),
  repairMissingMaoObraReferencesForSync: vi.fn(),
  repairMissingObraReferencesForSync: vi.fn(),
  resolveCanonicalUploadReplacements: vi.fn(),
}));

vi.stubGlobal("navigator", { onLine: true });

import { syncNow } from "./syncEngine";

describe("syncNow authorization", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.hasOnlineSession.mockReturnValue(false);
  });

  it("recusa antes de tocar o estado ou a outbox sem sessão online", async () => {
    await expect(syncNow()).rejects.toThrow(
      "Faça login novamente para sincronizar com o servidor.",
    );
    expect(mocks.updateSyncState).not.toHaveBeenCalled();
    expect(mocks.ensureRegisteredDevice).not.toHaveBeenCalled();
  });
});
