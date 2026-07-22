import {
  getSyncState,
  updateSyncState,
} from "../db/syncStateRepository";
import {
  getSession,
  hasOnlineSession,
} from "../../features/auth/authSession";
import { registerDeviceApi } from "./syncApiClient";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "./syncSession";

function createDeviceName(): string {
  const platform =
    navigator.platform?.trim() ||
    navigator.userAgent?.trim() ||
    "Navegador";

  return `Córtex Web - ${platform}`;
}

export async function ensureRegisteredDevice(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<string> {
  assertSyncSession(guard);
  const currentState = await getSyncState();
  assertSyncSession(guard);
  const session = getSession();
  const usuarioId =
    session?.colaboradorId?.trim() || null;

  if (!hasOnlineSession() || !usuarioId) {
    throw new Error(
      "Faça login novamente para sincronizar com o servidor.",
    );
  }

  const sameUser =
    currentState.usuarioId === usuarioId;
  const deviceId =
    sameUser && currentState.deviceId
      ? currentState.deviceId
      : crypto.randomUUID();

  const response = await registerDeviceApi({
    id: deviceId,
    nome: createDeviceName(),
    tipo: "WEB",
    usuarioId,
  });
  assertSyncSession(guard);

  if (
    typeof response.id !== "string" ||
    !response.id.trim()
  ) {
    throw new Error(
      "Backend não retornou um ID de dispositivo válido.",
    );
  }

  await updateSyncState({
    deviceId: response.id,
    usuarioId,
    ...(sameUser
      ? {}
      : {
          lastPulledCommitSeq: 0,
          lastAckedCommitSeq: 0,
        }),
  });
  assertSyncSession(guard);

  return response.id;
}
