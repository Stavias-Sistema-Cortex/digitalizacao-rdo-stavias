import { describe, expect, it } from "vitest";

import type { SyncStatusSnapshot } from "../lib/sync/useSyncStatus";
import {
  shouldClearManualSyncPresentationError,
  type ManualSyncPresentationError,
} from "./syncPresentation";

const manualFailure: ManualSyncPresentationError = {
  message: "A sincronização manual expirou.",
  occurredAt: "2026-07-17T15:30:00.000Z",
};

function snapshot(
  overrides: Partial<SyncStatusSnapshot> = {},
): SyncStatusSnapshot {
  return {
    status: "SYNCED",
    isOnline: true,
    pendingCount: 0,
    syncingCount: 0,
    errorCount: 0,
    conflictCount: 0,
    reviewCount: 0,
    reviewReason: null,
    lastSyncCompletedAt: "2026-07-17T15:31:00.000Z",
    lastSyncError: null,
    isLoading: false,
    ...overrides,
  };
}

describe("shouldClearManualSyncPresentationError", () => {
  it("clears a manual failure after a newer confirmed success", () => {
    expect(
      shouldClearManualSyncPresentationError(
        manualFailure,
        snapshot(),
      ),
    ).toBe(true);
  });

  it("keeps a manual failure when the snapshot has a fresh error", () => {
    expect(
      shouldClearManualSyncPresentationError(
        manualFailure,
        snapshot({ lastSyncError: "Servidor indisponível." }),
      ),
    ).toBe(false);
  });

  it.each([
    "2026-07-17T15:30:00.000Z",
    "2026-07-17T15:29:59.999Z",
  ])("keeps a manual failure when completion is not newer (%s)", (completedAt) => {
    expect(
      shouldClearManualSyncPresentationError(
        manualFailure,
        snapshot({ lastSyncCompletedAt: completedAt }),
      ),
    ).toBe(false);
  });

  it("keeps a manual failure when the current snapshot is an error", () => {
    expect(
      shouldClearManualSyncPresentationError(
        manualFailure,
        snapshot({ status: "ERROR" }),
      ),
    ).toBe(false);
  });
});
