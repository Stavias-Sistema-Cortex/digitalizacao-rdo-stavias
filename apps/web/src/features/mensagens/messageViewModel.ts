import type {
  LocalConversationRecord,
  LocalMessageRecord,
} from "../../lib/db/db.types";

export interface MessageDayGroup {
  key: string;
  label: string;
  messages: LocalMessageRecord[];
}

export function conversationStaviaTarget(
  conversation: LocalConversationRecord | null,
): { obraId: string } {
  return { obraId: conversation?.obraId ?? "" };
}

export function conversationDisplayName(
  conversation: LocalConversationRecord,
): string {
  return (
    conversation.titulo?.trim() ||
    conversation.equipeNome?.trim() ||
    conversation.obraNome?.trim() ||
    "Conversa operacional"
  );
}

export function participantInitials(name: string | null): string {
  const parts = name?.trim().split(/\s+/).filter(Boolean) ?? [];
  if (parts.length === 0) {
    return "—";
  }
  return `${parts[0][0]}${parts.length > 1 ? parts.at(-1)?.[0] : ""}`
    .toUpperCase();
}

function localDateKey(value: string): string {
  const date = new Date(value);
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
}

function dateLabel(value: string): string {
  return new Intl.DateTimeFormat("pt-BR", {
    day: "2-digit",
    month: "long",
    year: "numeric",
  }).format(new Date(value));
}

export function groupMessagesByDay(
  messages: LocalMessageRecord[],
): MessageDayGroup[] {
  const groups = new Map<string, MessageDayGroup>();
  for (const message of messages) {
    const key = localDateKey(message.enviadaClienteEm);
    const group = groups.get(key) ?? {
      key,
      label: dateLabel(message.enviadaClienteEm),
      messages: [],
    };
    group.messages.push(message);
    groups.set(key, group);
  }
  return [...groups.values()];
}

export function messageDeliveryLabel(message: LocalMessageRecord): string {
  if (message.syncStatus === "ERROR") {
    return "Falha no envio";
  }
  if (message.syncStatus === "PENDING") {
    return "Pendente";
  }
  if (message.syncStatus === "SYNCING") {
    return "Enviando";
  }
  if (message.recibos.some((receipt) => Boolean(receipt.lidaEm))) {
    return "Lida";
  }
  if (message.recibos.some((receipt) => Boolean(receipt.entregueEm))) {
    return "Entregue";
  }
  return "Enviada";
}
