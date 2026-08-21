export const SYNC_COMPLETED_EVENT = "cortex:sync-completed";
const SYNC_COMPLETED_BROADCAST_CHANNEL = "cortex-sync-completed-v1";

interface SyncCompletedBroadcast {
  type: typeof SYNC_COMPLETED_EVENT;
}

let broadcastChannel: BroadcastChannel | null = null;
let broadcastSubscribers = 0;

function dispatchSyncCompleted(): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));
}

function broadcastPayload(value: unknown): SyncCompletedBroadcast | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  return (value as Partial<SyncCompletedBroadcast>).type ===
      SYNC_COMPLETED_EVENT
    ? { type: SYNC_COMPLETED_EVENT }
    : null;
}

const receiveBroadcast = (event: MessageEvent<unknown>) => {
  if (!broadcastPayload(event.data)) return;
  // Evento recebido só reapresenta as telas deste documento. Não volta ao
  // canal — sem eco entre abas.
  dispatchSyncCompleted();
};

function openBroadcastChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === "undefined") return null;
  try {
    const channel = new BroadcastChannel(SYNC_COMPLETED_BROADCAST_CHANNEL);
    channel.addEventListener("message", receiveBroadcast);
    return channel;
  } catch {
    return null;
  }
}

/**
 * Mantém a escuta cross-tab viva enquanto a aba estiver com o app montado.
 *
 * <p>Só uma aba executa a sincronização — o lease garante isso —, e o aviso de
 * fim de ciclo era um evento de `window`, que morre na aba onde nasceu. As
 * outras abas da mesma máquina, lendo o mesmo IndexedDB recém-atualizado,
 * continuavam mostrando o retrato velho até o próprio intervalo delas vencer.
 * Numa apresentação com duas janelas abertas, o trecho removido numa demorava
 * a sumir da outra exatamente por isso.
 */
export function subscribeToSyncCompletedBroadcast(): () => void {
  broadcastSubscribers += 1;
  if (!broadcastChannel) broadcastChannel = openBroadcastChannel();
  let active = true;
  return () => {
    if (!active) return;
    active = false;
    broadcastSubscribers = Math.max(0, broadcastSubscribers - 1);
    if (broadcastSubscribers === 0 && broadcastChannel) {
      broadcastChannel.removeEventListener("message", receiveBroadcast);
      broadcastChannel.close();
      broadcastChannel = null;
    }
  };
}

function publishSyncCompleted(): void {
  if (typeof BroadcastChannel === "undefined") return;
  const payload: SyncCompletedBroadcast = { type: SYNC_COMPLETED_EVENT };
  if (broadcastChannel) {
    broadcastChannel.postMessage(payload);
    return;
  }
  // O ciclo pode terminar sem nenhuma tela inscrita nesta aba. Um emissor
  // efêmero ainda acorda as outras e é fechado no mesmo ciclo.
  try {
    const sender = new BroadcastChannel(SYNC_COMPLETED_BROADCAST_CHANNEL);
    sender.postMessage(payload);
    sender.close();
  } catch {
    // O evento window abaixo preserva o comportamento nesta aba.
  }
}

export function announceSyncCompleted(): void {
  dispatchSyncCompleted();
  publishSyncCompleted();
}
