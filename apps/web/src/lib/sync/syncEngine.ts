import { hasOnlineSession } from "../../features/auth/authSession";
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
  repairMissingMaoObraReferencesForSync,
  repairMissingObraReferencesForSync,
} from "./syncStorage";
import type { SyncRunSummary } from "./sync.types";

let activeSyncPromise: Promise<SyncRunSummary> | null =
  null;

async function executeSync(): Promise<SyncRunSummary> {
  if (!navigator.onLine) {
    throw new Error(
      "O dispositivo está offline. O RDO continua salvo localmente.",
    );
  }

  if (!hasOnlineSession()) {
    throw new Error(
      "Faça login novamente para sincronizar com o servidor.",
    );
  }

  await updateSyncState({
    isSyncing: true,
    lastSyncStartedAt: new Date().toISOString(),
    lastSyncError: null,
  });

  try {
    await recoverInterruptedMutations();
    await repairMissingObraReferencesForSync();
    await repairMissingMaoObraReferencesForSync();
    await repairRdoCreateMutationsForSync();

    const deviceId = await ensureRegisteredDevice();
    const uploadSummary = await processObjectUploads();
    const pushSummary = await pushOutbox(deviceId);
    const pullSummary = await pullEvents(deviceId);
    await refreshMessagingAfterPull(
      pullSummary.messagingConversationIds,
    );

    const acknowledgedCommitSeq =
      await acknowledgeCurrentCursor(deviceId);

    await updateSyncState({
      isSyncing: false,
      lastSyncCompletedAt: new Date().toISOString(),
      lastSyncError: null,
    });

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
