import type { SyncStatusSnapshot } from "../lib/sync/useSyncStatus";

export interface ManualSyncPresentationError {
  message: string;
  occurredAt: string;
}

export function shouldClearManualSyncPresentationError(
  error: ManualSyncPresentationError,
  snapshot: Pick<
    SyncStatusSnapshot,
    "status" | "lastSyncCompletedAt" | "lastSyncError"
  >,
): boolean {
  if (
    snapshot.status !== "SYNCED" ||
    snapshot.lastSyncError !== null ||
    !snapshot.lastSyncCompletedAt
  ) {
    return false;
  }

  const occurredAt = Date.parse(error.occurredAt);
  const completedAt = Date.parse(snapshot.lastSyncCompletedAt);

  return (
    Number.isFinite(occurredAt) &&
    Number.isFinite(completedAt) &&
    completedAt > occurredAt
  );
}
