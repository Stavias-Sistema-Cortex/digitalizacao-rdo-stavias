import { getCortexDb } from "./cortexDb";
import type { SyncStateRecord } from "./db.types";

const USER_SCOPED_STORES = [
  "teams",
  "operational_roles",
  "team_history",
  "team_worksites",
  "conversations",
  "conversation_participants",
  "messages",
  "message_references",
  "message_attachments",
  "rdos",
  "rdoMaoObra",
  "rdoEquipamentos",
  "rdoMateriais",
  "rdoControlesGeometricos",
  "outbox_mutations",
  "processed_events",
  "operational_events",
  "rdo_attachments",
  "stavia_snapshots",
  "obras",
  "previsao_snapshots",
  "tarefas",
  "colaboradores",
] as const;

const USER_SCOPED_LOCAL_STORAGE_KEYS = [
  "cortex:stavia:chat:operacional",
  "cortex:stavia:last-context",
] as const;

type LocalStorageRemover = Pick<Storage, "removeItem">;

/**
 * A conversa e o último contexto da StavIA são auxiliares de interface, mas
 * podem conter conteúdo operacional. Eles seguem a mesma fronteira de
 * identidade do IndexedDB e não podem sobreviver a uma troca de usuário.
 */
export function clearUserScopedLocalStorage(
  storage?: LocalStorageRemover,
): void {
  const target =
    storage ??
    (typeof window === "undefined" ? null : window.localStorage);

  if (!target) {
    return;
  }

  for (const key of USER_SCOPED_LOCAL_STORAGE_KEYS) {
    target.removeItem(key);
  }
}

function scopedSyncState(usuarioId: string): SyncStateRecord {
  return {
    key: "default",
    deviceId: null,
    usuarioId,
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
    isSyncing: false,
    lastSyncStartedAt: null,
    lastSyncCompletedAt: null,
    lastSyncError: null,
  };
}

/**
 * Vincula o cache local a uma identidade autenticada. Na troca de usuário,
 * todo dado operacional e toda outbox são removidos antes da nova sessão ser
 * exposta. Assim, um dispositivo compartilhado nunca reaproveita o cache
 * privado da pessoa anterior.
 */
export async function bindLocalDataToUser(usuarioId: string): Promise<boolean> {
  const normalizedUserId = usuarioId.trim();
  if (!normalizedUserId) {
    throw new Error("Não foi possível identificar o usuário do cache local.");
  }

  const database = await getCortexDb();
  const current = await database.get("sync_state", "default");
  if (current?.usuarioId === normalizedUserId) {
    return false;
  }

  clearUserScopedLocalStorage();

  const transaction = database.transaction(
    [...USER_SCOPED_STORES, "sync_state"],
    "readwrite",
  );
  for (const storeName of USER_SCOPED_STORES) {
    await transaction.objectStore(storeName).clear();
  }
  await transaction.objectStore("sync_state").put(
    scopedSyncState(normalizedUserId),
  );
  await transaction.done;
  return true;
}
