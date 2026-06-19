import {
  getSyncState,
  updateSyncState,
} from "../db/syncStateRepository";
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

  if (currentState.deviceId) {
    return currentState.deviceId;
  }

  const generatedDeviceId = crypto.randomUUID();

  const response = await registerDeviceApi({
    id: generatedDeviceId,
    nome: createDeviceName(),
    tipo: "WEB",
    usuarioId: null,
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
  });

  return response.id;
}
