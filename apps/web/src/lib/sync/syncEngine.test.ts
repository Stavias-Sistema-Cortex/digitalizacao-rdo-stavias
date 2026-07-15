import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getSession: vi.fn(),
  repairRdoCreateMutationsForSync: vi.fn(),
  updateSyncState: vi.fn(),
  acknowledgeCurrentCursor: vi.fn(),
  pullEvents: vi.fn(),
  pushOutbox: vi.fn(),
  ensureRegisteredDevice: vi.fn(),
  syncPendingMessageAttachments: vi.fn(),
  queueErroredMutationsForRetry: vi.fn(),
  queueResolvableConflictsForRetry: vi.fn(),
  recoverInterruptedMutations: vi.fn(),
  repairMissingMaoObraReferencesForSync: vi.fn(),
  repairMissingObraReferencesForSync: vi.fn(),
}));

vi.mock("../../features/auth/authSession", () => ({
  getSession: mocks.getSession,
}));
vi.mock("../../features/mensagens/messageAttachmentSync", () => ({
  syncPendingMessageAttachments: mocks.syncPendingMessageAttachments,
}));
vi.mock("../db/localRdoService", () => ({
  repairRdoCreateMutationsForSync: mocks.repairRdoCreateMutationsForSync,
}));
vi.mock("../db/syncStateRepository", () => ({
  updateSyncState: mocks.updateSyncState,
}));
vi.mock("./ackCursor", () => ({
  acknowledgeCurrentCursor: mocks.acknowledgeCurrentCursor,
}));
vi.mock("./pullEvents", () => ({ pullEvents: mocks.pullEvents }));
vi.mock("./pushOutbox", () => ({ pushOutbox: mocks.pushOutbox }));
vi.mock("./registerDevice", () => ({
  ensureRegisteredDevice: mocks.ensureRegisteredDevice,
}));
vi.mock("./syncStorage", () => ({
  queueErroredMutationsForRetry: mocks.queueErroredMutationsForRetry,
  queueResolvableConflictsForRetry: mocks.queueResolvableConflictsForRetry,
  recoverInterruptedMutations: mocks.recoverInterruptedMutations,
  repairMissingMaoObraReferencesForSync:
    mocks.repairMissingMaoObraReferencesForSync,
  repairMissingObraReferencesForSync: mocks.repairMissingObraReferencesForSync,
}));

import { syncNow } from "./syncEngine";

beforeEach(() => {
  vi.clearAllMocks();
  vi.stubGlobal("navigator", { onLine: true });
  mocks.getSession.mockReturnValue({ token: "token" });
  mocks.updateSyncState.mockResolvedValue(undefined);
  mocks.repairRdoCreateMutationsForSync.mockResolvedValue(undefined);
  mocks.queueErroredMutationsForRetry.mockResolvedValue(0);
  mocks.queueResolvableConflictsForRetry.mockResolvedValue(0);
  mocks.recoverInterruptedMutations.mockResolvedValue(undefined);
  mocks.repairMissingMaoObraReferencesForSync.mockResolvedValue(0);
  mocks.repairMissingObraReferencesForSync.mockResolvedValue(0);
  mocks.ensureRegisteredDevice.mockResolvedValue("device-1");
  mocks.pushOutbox.mockResolvedValue({
    pushed: 1,
    applied: 1,
    errors: 0,
    conflicts: 0,
  });
  mocks.syncPendingMessageAttachments.mockResolvedValue({
    attempted: 1,
    uploaded: 1,
    deferred: 0,
    errors: 0,
  });
  mocks.pullEvents.mockResolvedValue({
    pulled: 2,
    lastAppliedCommitSeq: 12,
  });
  mocks.acknowledgeCurrentCursor.mockResolvedValue(12);
});

describe("syncNow", () => {
  it("reconcilia a mensagem antes de enviar seus anexos e só então faz pull", async () => {
    const summary = await syncNow();

    expect(
      mocks.pushOutbox.mock.invocationCallOrder[0],
    ).toBeLessThan(
      mocks.syncPendingMessageAttachments.mock.invocationCallOrder[0],
    );
    expect(
      mocks.syncPendingMessageAttachments.mock.invocationCallOrder[0],
    ).toBeLessThan(mocks.pullEvents.mock.invocationCallOrder[0]);
    expect(summary).toMatchObject({
      pushed: 1,
      applied: 1,
      attachmentsUploaded: 1,
      attachmentErrors: 0,
      pulled: 2,
      acknowledgedCommitSeq: 12,
    });
  });
});
