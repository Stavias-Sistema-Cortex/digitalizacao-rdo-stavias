import {
  getSyncState,
  updateSyncState,
} from "../db/syncStateRepository";
import { ackCursorApi } from "./syncApiClient";

export async function acknowledgeCurrentCursor(
  deviceId: string,
): Promise<number> {
  const state = await getSyncState();
  const requestedCursor = state.lastPulledCommitSeq;

  if (requestedCursor <= state.lastAckedCommitSeq) {
    return state.lastAckedCommitSeq;
  }

  const response = await ackCursorApi({
    dispositivoId: deviceId,
    ultimoEventoRecebidoCommitSeq:
      requestedCursor,
  });

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

  return persistedCursor;
}
