import type { LocalSyncStatus } from "./db.types";

export function formatLocalSyncStatus(
  status: LocalSyncStatus,
): string {
  switch (status) {
    case "LOCAL_ONLY":
      return "Salvo apenas neste dispositivo";
    case "PENDING_SYNC":
      return "Pendente de sincronização";
    case "SYNCING":
      return "Sincronizando";
    case "SYNCED":
      return "Sincronizado";
    case "ERROR":
      return "Erro ao sincronizar";
    case "CONFLICT":
      return "Conflito detectado";
  }
}
