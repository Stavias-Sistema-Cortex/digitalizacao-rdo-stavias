import {
  getSyncState,
  updateSyncState,
} from "../db/syncStateRepository";
import { ackCursorApi } from "./syncApiClient";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "./syncSession";

export async function acknowledgeCurrentCursor(
  deviceId: string,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const state = await getSyncState();
  assertSyncSession(guard);
  const requestedCursor = state.lastPulledCommitSeq;

  if (requestedCursor <= state.lastAckedCommitSeq) {
    return state.lastAckedCommitSeq;
  }

  const response = await ackCursorApi({
    dispositivoId: deviceId,
    ultimoEventoRecebidoCommitSeq:
      requestedCursor,
  });
  assertSyncSession(guard);

  const persistedCursor =
    typeof response.ultimoEventoRecebidoCommitSeq ===
    "number"
      ? response.ultimoEventoRecebidoCommitSeq
      : requestedCursor;

  if (persistedCursor < requestedCursor) {
    throw new Error(
      "O servidor confirmou um cursor menor que o aplicado localmente.",
    );
  }

  await updateSyncState({
    lastAckedCommitSeq: persistedCursor,
  });
  assertSyncSession(guard);

  return persistedCursor;
}
