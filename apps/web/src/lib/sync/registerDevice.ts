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

import { escopoCresceu, marcaDoEscopo } from "./escopoDoCursor";

export async function ensureRegisteredDevice(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<string> {
  assertSyncSession(guard);
  const currentState = await getSyncState(guard);
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

  /*
   * O cursor volta ao começo quando a pessoa passa a alcançar uma obra que
   * antes não alcançava. Sem isso, tudo o que aconteceu naquela obra antes do
   * vínculo fica atrás do cursor para sempre — e tarefa, que só chega por
   * evento, some da tela dela enquanto o resto da equipe a vê.
   */
  const escopoAtual = marcaDoEscopo(session);
  const rebobinar =
    sameUser && escopoCresceu(currentState.escopoDoCursor, escopoAtual);
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
    escopoDoCursor: escopoAtual,
    ...(sameUser && !rebobinar
      ? {}
      : {
          lastPulledCommitSeq: 0,
          lastAckedCommitSeq: 0,
        }),
  }, guard);
  assertSyncSession(guard);

  return response.id;
}
