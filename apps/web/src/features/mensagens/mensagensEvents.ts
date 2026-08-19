export const MESSAGES_CHANGED_EVENT = "cortex-messages-changed";
const MESSAGES_CHANGED_BROADCAST_CHANNEL = "cortex-messages-changed-v1";

interface MessagesChangedBroadcast {
  type: typeof MESSAGES_CHANGED_EVENT;
  remap: ConversationIdRemap | null;
}

let broadcastChannel: BroadcastChannel | null = null;
let broadcastSubscribers = 0;

export interface ConversationIdRemap {
  from: string;
  to: string;
}

/**
 * Extrai o apelido de uma conversa sem confiar em qualquer evento global.
 *
 * Eventos antigos não carregam detalhe e continuam válidos: eles apenas pedem
 * uma releitura. O alias aparece somente quando o servidor reconhece que a
 * conversa direta criada neste aparelho já existia com outro identificador.
 */
export function conversationIdRemapFromEvent(
  event: Event,
): ConversationIdRemap | null {
  const detail = "detail" in event
    ? (event as CustomEvent<unknown>).detail
    : null;
  return normalizeConversationIdRemap(detail);
}

function normalizeConversationIdRemap(
  value: unknown,
): ConversationIdRemap | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return null;
  }
  const { from, to } = value as Partial<ConversationIdRemap>;
  const normalizedFrom = typeof from === "string" ? from.trim() : "";
  const normalizedTo = typeof to === "string" ? to.trim() : "";
  return normalizedFrom && normalizedTo && normalizedFrom !== normalizedTo
    ? { from: normalizedFrom, to: normalizedTo }
    : null;
}

function dispatchMessagesChanged(remap: ConversationIdRemap | null): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(
    remap
      ? new CustomEvent<ConversationIdRemap>(MESSAGES_CHANGED_EVENT, {
          detail: remap,
        })
      : new Event(MESSAGES_CHANGED_EVENT),
  );
}

function broadcastPayload(value: unknown): MessagesChangedBroadcast | null {
  if (!value || typeof value !== "object" || Array.isArray(value)) return null;
  const candidate = value as Partial<MessagesChangedBroadcast>;
  if (candidate.type !== MESSAGES_CHANGED_EVENT) return null;
  if (candidate.remap === null || candidate.remap === undefined) {
    return { type: MESSAGES_CHANGED_EVENT, remap: null };
  }
  const remap = normalizeConversationIdRemap(candidate.remap);
  return remap ? { type: MESSAGES_CHANGED_EVENT, remap } : null;
}

const receiveBroadcast = (event: MessageEvent<unknown>) => {
  const payload = broadcastPayload(event.data);
  if (!payload) return;
  // Evento recebido só invalida este documento. Não volta ao canal e, assim,
  // uma revogação nunca entra em eco entre abas.
  dispatchMessagesChanged(payload.remap);
};

function openBroadcastChannel(): BroadcastChannel | null {
  if (typeof BroadcastChannel === "undefined") return null;
  try {
    const channel = new BroadcastChannel(MESSAGES_CHANGED_BROADCAST_CHANNEL);
    channel.addEventListener("message", receiveBroadcast);
    return channel;
  } catch {
    return null;
  }
}

/** Mantém a escuta cross-tab viva exatamente durante o ciclo da tela. */
export function subscribeToMessagesChangedBroadcast(): () => void {
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

function publishMessagesChanged(remap: ConversationIdRemap | null): void {
  if (typeof BroadcastChannel === "undefined") return;
  const payload: MessagesChangedBroadcast = {
    type: MESSAGES_CHANGED_EVENT,
    remap,
  };
  if (broadcastChannel) {
    broadcastChannel.postMessage(payload);
    return;
  }
  // Escritas podem ocorrer sem a página de Mensagens montada. Um emissor
  // efêmero ainda acorda outras abas e é fechado no mesmo ciclo.
  try {
    const sender = new BroadcastChannel(MESSAGES_CHANGED_BROADCAST_CHANNEL);
    sender.postMessage(payload);
    sender.close();
  } catch {
    // O evento window abaixo preserva o comportamento legado neste documento.
  }
}

export function emitMessagesChanged(remap?: ConversationIdRemap): void {
  const normalized = normalizeConversationIdRemap(remap);
  dispatchMessagesChanged(normalized);
  publishMessagesChanged(normalized);
}
