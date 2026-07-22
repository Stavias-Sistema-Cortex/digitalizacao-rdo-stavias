import { describe, expect, it } from "vitest";

import { determineSyncUiStatus } from "./useSyncStatus";

const baseInput = {
  isOnline: true,
  isSyncing: false,
  pendingCount: 0,
  syncingCount: 0,
  errorCount: 0,
  conflictCount: 0,
  reviewCount: 0,
  lastSyncError: null,
};

describe("determineSyncUiStatus", () => {
  it("requires review for rejected mutations instead of reporting a transient failure or success", () => {
    expect(
      determineSyncUiStatus({
        ...baseInput,
        reviewCount: 1,
        errorCount: 1,
      }),
    ).toBe("REVIEW");
  });

  it("keeps a transient transport error distinct when no review is required", () => {
    expect(
      determineSyncUiStatus({
        ...baseInput,
        errorCount: 1,
      }),
    ).toBe("ERROR");
  });
});
