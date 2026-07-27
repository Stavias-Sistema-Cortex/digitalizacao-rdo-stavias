import { getCortexDb } from "./cortexDb";
import type { SyncStateRecord } from "./db.types";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "../sync/syncSession";
import { guardSyncTransaction } from "../sync/guardedSyncTransaction";

const SYNC_STATE_KEY = "default" as const;

function createDefaultSyncState(): SyncStateRecord {
  return {
    key: SYNC_STATE_KEY,
    deviceId: null,
    usuarioId: null,
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
    isSyncing: false,
    lastSyncStartedAt: null,
    lastSyncCompletedAt: null,
    lastSyncError: null,
    syncExecutionLease: null,
  };
}

export async function getSyncState(
  guard?: SyncSessionGuard,
): Promise<SyncStateRecord> {
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);

  if (guard) {
    const guardedTransaction = guardSyncTransaction(
      database.transaction("sync_state", "readwrite"),
      guard,
    );
    const store = guardedTransaction.transaction.objectStore("sync_state");
    const existing = await store.get(SYNC_STATE_KEY);
    if (existing) {
      await guardedTransaction.complete();
      return existing;
    }
    const initial = createDefaultSyncState();
    await store.put(initial);
    await guardedTransaction.complete();
    return initial;
  }

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
  guard?: SyncSessionGuard,
): Promise<SyncStateRecord> {
  if (guard) assertSyncSession(guard);
  const database = await getCortexDb();
  if (guard) assertSyncSession(guard);

  if (guard) {
    const guardedTransaction = guardSyncTransaction(
      database.transaction("sync_state", "readwrite"),
      guard,
    );
    const store = guardedTransaction.transaction.objectStore("sync_state");
    const current =
      (await store.get(SYNC_STATE_KEY)) ?? createDefaultSyncState();
    const updated: SyncStateRecord = {
      ...current,
      ...patch,
      key: SYNC_STATE_KEY,
    };
    await store.put(updated);
    await guardedTransaction.complete();
    return updated;
  }

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
