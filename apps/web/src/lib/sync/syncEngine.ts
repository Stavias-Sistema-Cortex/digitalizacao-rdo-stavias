import { updateSyncState } from "../db/syncStateRepository";
import { acknowledgeCurrentCursor } from "./ackCursor";
import { pullEvents } from "./pullEvents";
import { pushOutbox } from "./pushOutbox";
import { ensureRegisteredDevice } from "./registerDevice";
import { recoverInterruptedMutations } from "./syncStorage";
import type { SyncRunSummary } from "./sync.types";

let activeSyncPromise: Promise<SyncRunSummary> | null =
  null;

async function executeSync(): Promise<SyncRunSummary> {
  if (!navigator.onLine) {
    throw new Error(
      "O dispositivo está offline. O RDO continua salvo localmente.",
    );
  }

  await updateSyncState({
    isSyncing: true,
    lastSyncStartedAt: new Date().toISOString(),
    lastSyncError: null,
  });

  try {
    await recoverInterruptedMutations();

    const deviceId = await ensureRegisteredDevice();
    const pushSummary = await pushOutbox(deviceId);
    const pullSummary = await pullEvents();

    const acknowledgedCommitSeq =
      await acknowledgeCurrentCursor(deviceId);

    await updateSyncState({
      isSyncing: false,
      lastSyncCompletedAt: new Date().toISOString(),
      lastSyncError: null,
    });

    return {
      deviceId,
      pushed: pushSummary.pushed,
      applied: pushSummary.applied,
      errors: pushSummary.errors,
      conflicts: pushSummary.conflicts,
      pulled: pullSummary.pulled,
      acknowledgedCommitSeq,
    };
  } catch (error: unknown) {
    const message =
      error instanceof Error
        ? error.message
        : "Falha desconhecida na sincronização.";

    await updateSyncState({
      isSyncing: false,
      lastSyncCompletedAt: new Date().toISOString(),
      lastSyncError: message,
    });

    throw error;
  }
}

export function syncNow(): Promise<SyncRunSummary> {
  if (activeSyncPromise) {
    return activeSyncPromise;
  }

  activeSyncPromise = executeSync().finally(() => {
    activeSyncPromise = null;
  });

  return activeSyncPromise;
}
