import { processObjectUploads } from "../../features/mensagens/objectUploadSync";
import { refreshMessagingAfterPull } from "../../features/mensagens/mensagensHydration";
import { repairRdoCreateMutationsForSync } from "../db/localRdoService";
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
import type { SyncRunSummary } from "./sync.types";

const activeSyncPromises = new Map<
  string,
  Promise<SyncRunSummary>
>();

async function executeSync(
  guard: SyncSessionGuard,
): Promise<SyncRunSummary> {
  if (!navigator.onLine) {
    throw new Error(
      "O dispositivo está offline. O RDO continua salvo localmente.",
    );
  }
  assertSyncSession(guard);
  await updateSyncState({
    isSyncing: true,
    lastSyncStartedAt: new Date().toISOString(),
    lastSyncError: null,
  }, guard);
  assertSyncSession(guard);

  try {
    await recoverInterruptedMutations(guard);
    assertSyncSession(guard);
    await repairMissingObraReferencesForSync(guard);
    assertSyncSession(guard);
    await repairMissingMaoObraReferencesForSync(guard);
    assertSyncSession(guard);
    await repairRdoCreateMutationsForSync(guard);
    assertSyncSession(guard);

    const deviceId = await ensureRegisteredDevice(guard);
    assertSyncSession(guard);
    const uploadSummary = await processObjectUploads(20, guard);
    assertSyncSession(guard);
    await resolveCanonicalUploadReplacements(guard);
    assertSyncSession(guard);
    await recoverCanonicalConflictReconciliations(guard);
    assertSyncSession(guard);
    const pushSummary = await pushOutbox(deviceId, guard);
    assertSyncSession(guard);
    const pullSummary = await pullEvents(deviceId, guard);
    assertSyncSession(guard);
    await refreshMessagingAfterPull(
      pullSummary.messagingConversationIds,
      guard,
    );
    assertSyncSession(guard);
    const acknowledgedCommitSeq =
      await acknowledgeCurrentCursor(deviceId, guard);
    assertSyncSession(guard);

    await updateSyncState({
      isSyncing: false,
      lastSyncCompletedAt: new Date().toISOString(),
      lastSyncError: null,
    }, guard);
    assertSyncSession(guard);

    return {
      deviceId,
      pushed: uploadSummary.pushed + pushSummary.pushed,
      applied: uploadSummary.applied + pushSummary.applied,
      errors: uploadSummary.errors + pushSummary.errors,
      retryableErrors: pushSummary.retryableErrors,
      conflicts: pushSummary.conflicts,
      pulled: pullSummary.pulled,
      acknowledgedCommitSeq,
    };
  } catch (error: unknown) {
    // Never write the old run's status into a newly active session database.
    try {
      assertSyncSession(guard);
      const message =
        error instanceof Error
          ? error.message
          : "Falha desconhecida na sincronização.";
      await updateSyncState({
        isSyncing: false,
        lastSyncCompletedAt: new Date().toISOString(),
        lastSyncError: message,
      }, guard);
      assertSyncSession(guard);
    } catch {
      // The original session is gone; its next run recovers SYNCING rows.
    }
    throw error;
  }
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

  const promise = executeSync(guard).finally(() => {
    if (activeSyncPromises.get(guard.fingerprint) === promise) {
      activeSyncPromises.delete(guard.fingerprint);
    }
  });
  activeSyncPromises.set(guard.fingerprint, promise);
  return promise;
}
