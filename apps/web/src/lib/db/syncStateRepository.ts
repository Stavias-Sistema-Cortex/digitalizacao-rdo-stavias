import { getCortexDb } from "./cortexDb";
import type { SyncStateRecord } from "./db.types";

const SYNC_STATE_KEY = "default" as const;

function createDefaultSyncState(): SyncStateRecord {
  return {
    key: SYNC_STATE_KEY,
    deviceId: null,
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
    isSyncing: false,
    lastSyncStartedAt: null,
    lastSyncCompletedAt: null,
    lastSyncError: null,
  };
}

export async function getSyncState(): Promise<SyncStateRecord> {
  const database = await getCortexDb();

  const existing = await database.get(
    "sync_state",
    SYNC_STATE_KEY,
  );

  if (existing) {
    return existing;
  }

  const initial = createDefaultSyncState();

  await database.put("sync_state", initial);

  return initial;
}

export async function updateSyncState(
  patch: Partial<Omit<SyncStateRecord, "key">>,
): Promise<SyncStateRecord> {
  const database = await getCortexDb();

  const current =
    (await database.get(
      "sync_state",
      SYNC_STATE_KEY,
    )) ?? createDefaultSyncState();

  const updated: SyncStateRecord = {
    ...current,
    ...patch,
    key: SYNC_STATE_KEY,
  };

  await database.put("sync_state", updated);

  return updated;
}
