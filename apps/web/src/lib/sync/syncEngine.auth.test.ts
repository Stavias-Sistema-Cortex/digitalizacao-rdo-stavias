import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  hasOnlineSession: vi.fn(),
  updateSyncState: vi.fn(),
  ensureRegisteredDevice: vi.fn(),
  processObjectUploads: vi.fn(),
  refreshMessagingAfterPull: vi.fn(),
  repairRdoCreateMutationsForSync: vi.fn(),
  acknowledgeCurrentCursor: vi.fn(),
  pullEvents: vi.fn(),
  pushOutbox: vi.fn(),
  recoverInterruptedMutations: vi.fn(),
  repairMissingMaoObraReferencesForSync: vi.fn(),
  repairMissingObraReferencesForSync: vi.fn(),
}));

vi.mock("../../features/auth/authSession", () => ({
  hasOnlineSession: mocks.hasOnlineSession,
}));
vi.mock("../db/syncStateRepository", () => ({
  updateSyncState: mocks.updateSyncState,
}));
vi.mock("./registerDevice", () => ({
  ensureRegisteredDevice: mocks.ensureRegisteredDevice,
}));
vi.mock("../../features/mensagens/objectUploadSync", () => ({
  processObjectUploads: mocks.processObjectUploads,
}));
vi.mock("../../features/mensagens/mensagensHydration", () => ({
  refreshMessagingAfterPull: mocks.refreshMessagingAfterPull,
}));
vi.mock("../db/localRdoService", () => ({
  repairRdoCreateMutationsForSync:
    mocks.repairRdoCreateMutationsForSync,
}));
vi.mock("./ackCursor", () => ({
  acknowledgeCurrentCursor: mocks.acknowledgeCurrentCursor,
}));
vi.mock("./pullEvents", () => ({ pullEvents: mocks.pullEvents }));
vi.mock("./pushOutbox", () => ({ pushOutbox: mocks.pushOutbox }));
vi.mock("./syncStorage", () => ({
  recoverInterruptedMutations: mocks.recoverInterruptedMutations,
  repairMissingMaoObraReferencesForSync:
    mocks.repairMissingMaoObraReferencesForSync,
  repairMissingObraReferencesForSync:
    mocks.repairMissingObraReferencesForSync,
}));

vi.stubGlobal("navigator", { onLine: true });

import { syncNow } from "./syncEngine";

describe("syncNow authorization", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.hasOnlineSession.mockReturnValue(false);
    mocks.updateSyncState.mockResolvedValue(undefined);
    mocks.ensureRegisteredDevice.mockResolvedValue("device-1");
    mocks.processObjectUploads.mockResolvedValue({
      pushed: 0,
      applied: 0,
      errors: 0,
    });
    mocks.refreshMessagingAfterPull.mockResolvedValue(undefined);
    mocks.repairRdoCreateMutationsForSync.mockResolvedValue(undefined);
    mocks.acknowledgeCurrentCursor.mockResolvedValue(0);
    mocks.pullEvents.mockResolvedValue({
      pulled: 0,
      messagingConversationIds: [],
    });
    mocks.pushOutbox.mockResolvedValue({
      pushed: 0,
      applied: 0,
      errors: 0,
      retryableErrors: 0,
      conflicts: 0,
    });
    mocks.recoverInterruptedMutations.mockResolvedValue(undefined);
    mocks.repairMissingMaoObraReferencesForSync.mockResolvedValue(undefined);
    mocks.repairMissingObraReferencesForSync.mockResolvedValue(undefined);
  });

  it("recusa antes de tocar o estado ou a outbox sem sessão online", async () => {
    await expect(syncNow()).rejects.toThrow(
      "Faça login novamente para sincronizar com o servidor.",
    );
    expect(mocks.updateSyncState).not.toHaveBeenCalled();
    expect(mocks.ensureRegisteredDevice).not.toHaveBeenCalled();
  });

  it("propaga somente erros de push retornados a PENDING como retentáveis", async () => {
    mocks.hasOnlineSession.mockReturnValue(true);
    mocks.processObjectUploads.mockResolvedValue({
      pushed: 2,
      applied: 0,
      errors: 2,
    });
    mocks.pushOutbox.mockResolvedValue({
      pushed: 2,
      applied: 0,
      errors: 2,
      retryableErrors: 1,
      conflicts: 1,
    });
    mocks.pullEvents.mockResolvedValue({
      pulled: 3,
      messagingConversationIds: [],
    });
    mocks.acknowledgeCurrentCursor.mockResolvedValue(12);

    await expect(syncNow()).resolves.toMatchObject({
      pushed: 4,
      applied: 0,
      errors: 4,
      retryableErrors: 1,
      conflicts: 1,
      pulled: 3,
      acknowledgedCommitSeq: 12,
    });
  });
});
