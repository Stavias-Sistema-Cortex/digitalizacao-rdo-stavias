import {
  getSyncState,
  updateSyncState,
} from "../db/syncStateRepository";
import { getSession } from "../../features/auth/authSession";
import { registerDeviceApi } from "./syncApiClient";

function createDeviceName(): string {
  const platform =
    navigator.platform?.trim() ||
    navigator.userAgent?.trim() ||
    "Navegador";

  return `Córtex Web - ${platform}`;
}

export async function ensureRegisteredDevice(): Promise<string> {
  const currentState = await getSyncState();
  const session = getSession();
  const usuarioId =
    session?.colaboradorId?.trim() || null;

  if (!session?.token || !usuarioId) {
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

  return response.id;
}
