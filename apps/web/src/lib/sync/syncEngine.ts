import { processObjectUploads } from "../../features/mensagens/objectUploadSync";
import { refreshMessagingAfterPull } from "../../features/mensagens/mensagensHydration";
import {
  hydrateBlockedRdoCreationContextsForSync,
  repairRdoCreateMutationsForSync,
} from "../db/localRdoService";
import { updateSyncState } from "../db/syncStateRepository";
import { acknowledgeCurrentCursor } from "./ackCursor";
import { pullEvents } from "./pullEvents";
import { pushOutbox } from "./pushOutbox";
import { ensureRegisteredDevice } from "./registerDevice";
import {
  recoverInterruptedMutations,
  recoverCanonicalConflictReconciliations,
  repairMissingMaoObraReferencesForSync,
  repairMissingObraReferencesForSync,
  resolveCanonicalUploadReplacements,
} from "./syncStorage";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "./syncSession";
import {
  runWithSyncExecutionLease,
  type SyncExecutionLease,
} from "./syncExecutionLease";
import type { SyncRunSummary } from "./sync.types";
import { announceSyncCompleted } from "./syncEvents";

export { SYNC_COMPLETED_EVENT } from "./syncEvents";

const activeSyncPromises = new Map<
  string,
  Promise<SyncRunSummary>
>();

async function executeSync(
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<SyncRunSummary> {
  if (!navigator.onLine) {
    throw new Error(
      "O dispositivo está offline. O RDO continua salvo localmente.",
    );
  }
  await assertSyncExecution(guard, lease);
  await updateSyncState({
    isSyncing: true,
    lastSyncStartedAt: new Date().toISOString(),
    lastSyncError: null,
  }, guard);
  await assertSyncExecution(guard, lease);

  try {
    await recoverInterruptedMutations(guard);
    await assertSyncExecution(guard, lease);
    await repairMissingObraReferencesForSync(guard);
    await assertSyncExecution(guard, lease);
    await repairMissingMaoObraReferencesForSync(guard);
    await assertSyncExecution(guard, lease);
    await hydrateBlockedRdoCreationContextsForSync(guard);
    await assertSyncExecution(guard, lease);
    await repairRdoCreateMutationsForSync(guard);
    await assertSyncExecution(guard, lease);

    const deviceId = await ensureRegisteredDevice(guard);
    await assertSyncExecution(guard, lease);
    const uploadSummary = await processObjectUploads(20, guard);
    await assertSyncExecution(guard, lease);
    await resolveCanonicalUploadReplacements(guard);
    await assertSyncExecution(guard, lease);
    await recoverCanonicalConflictReconciliations(guard);
    await assertSyncExecution(guard, lease);
    const pushSummary = await pushOutbox(deviceId, guard);
    await assertSyncExecution(guard, lease);
    const pullSummary = await pullEvents(deviceId, guard);
    await assertSyncExecution(guard, lease);
    await refreshMessagingAfterPull(
      pullSummary.messagingConversationIds,
      guard,
    );
    await assertSyncExecution(guard, lease);
    const acknowledgedCommitSeq =
      await acknowledgeCurrentCursor(deviceId, guard);
    await assertSyncExecution(guard, lease);

    await updateSyncState({
      isSyncing: false,
      lastSyncCompletedAt: new Date().toISOString(),
      lastSyncError: null,
    }, guard);
    await assertSyncExecution(guard, lease);

    const summary = {
      deviceId,
      pushed: uploadSummary.pushed + pushSummary.pushed,
      applied: uploadSummary.applied + pushSummary.applied,
      errors: uploadSummary.errors + pushSummary.errors,
      retryableErrors: pushSummary.retryableErrors,
      conflicts: pushSummary.conflicts,
      pulled: pullSummary.pulled,
      acknowledgedCommitSeq,
    };
    announceSyncCompleted();
    return summary;
  } catch (error: unknown) {
    // Never write the old run's status into a newly active session database.
    try {
      await assertSyncExecution(guard, lease);
      const message =
        error instanceof Error
          ? error.message
          : "Falha desconhecida na sincronização.";
      await updateSyncState({
        isSyncing: false,
        lastSyncCompletedAt: new Date().toISOString(),
        lastSyncError: message,
      }, guard);
      await assertSyncExecution(guard, lease);
    } catch {
      // The original session is gone; its next run recovers SYNCING rows.
    }
    throw error;
  }
}

async function assertSyncExecution(
  guard: SyncSessionGuard,
  lease: SyncExecutionLease,
): Promise<void> {
  assertSyncSession(guard);
  await lease.assertOwned();
  assertSyncSession(guard);
}

export function syncNow(): Promise<SyncRunSummary> {
  let guard: SyncSessionGuard;
  try {
    guard = captureOnlineSyncSession();
  } catch (error: unknown) {
    return Promise.reject(error);
  }
  const active = activeSyncPromises.get(guard.fingerprint);
  if (active) return active;

  const promise = runWithSyncExecutionLease(
    guard,
    (lease) => executeSync(guard, lease),
  ).finally(() => {
    if (activeSyncPromises.get(guard.fingerprint) === promise) {
      activeSyncPromises.delete(guard.fingerprint);
    }
  });
  activeSyncPromises.set(guard.fingerprint, promise);
  return promise;
}
