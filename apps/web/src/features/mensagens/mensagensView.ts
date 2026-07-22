import type {
  ConversaLocalRecord,
  ConversaTipo,
  MensagemLocalRecord,
} from "../../lib/db/db.types";

export interface ConversationPreview {
  messageId: string;
  text: string;
  authorId: string;
  authorName: string;
  at: string;
  syncStatus: MensagemLocalRecord["syncStatus"];
}

export type MessageTimelineEntry<T extends MensagemLocalRecord> =
  | { kind: "date"; key: string; label: string }
  | { kind: "message"; key: string; message: T; startsRun: boolean };

/** Mesmo autor depois desta pausa começa um novo run, como em apps de chat. */
const RUN_GAP_MS = 15 * 60 * 1000;

export function buildMessageTimeline<T extends MensagemLocalRecord>(
  messages: T[],
): MessageTimelineEntry<T>[] {
  const result: MessageTimelineEntry<T>[] = [];
  let previousDate = "";
  let previousAuthor = "";
  let previousAt = Number.NaN;
  for (const message of messages) {
    const date = localDateKey(message.criadaNoClienteEm);
    const at = new Date(message.criadaNoClienteEm).getTime();
    if (date !== previousDate) {
      result.push({
        kind: "date",
        key: `date:${date}`,
        label: formatDateLabel(message.criadaNoClienteEm),
      });
      previousDate = date;
      previousAuthor = "";
      previousAt = Number.NaN;
    }
    const gap =
      Number.isNaN(previousAt) || Number.isNaN(at)
        ? Number.POSITIVE_INFINITY
        : at - previousAt;
    result.push({
      kind: "message",
      key: message.id,
      message,
      startsRun: message.autorId !== previousAuthor || gap > RUN_GAP_MS,
    });
    previousAuthor = message.autorId;
    previousAt = at;
  }
  return result;
}

export function buildConversationPreviews(
  messages: MensagemLocalRecord[],
  messageIdsWithAttachments: Set<string>,
): Record<string, ConversationPreview> {
  const previews: Record<string, ConversationPreview> = {};
  for (const message of [...messages].sort((left, right) =>
    left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
  )) {
    previews[message.conversaId] = {
      messageId: message.id,
      text: previewText(message, messageIdsWithAttachments.has(message.id)),
      authorId: message.autorId,
      authorName: message.autorNome,
      at: message.criadaNoClienteEm,
      syncStatus: message.syncStatus,
    };
  }
  return previews;
}

/** Prefixa "Você:" comparando por id — nomes homônimos enganariam. */
export function previewLabel(
  preview: ConversationPreview | undefined,
  currentUserId: string,
  fallback: string,
): string {
  if (!preview) {
    return fallback;
  }
  return preview.authorId === currentUserId
    ? `Você: ${preview.text}`
    : preview.text;
}

export function hasPendingMessage(
  preview: ConversationPreview | undefined,
): boolean {
  return preview !== undefined && preview.syncStatus !== "SINCRONIZADO";
}

export function conversationName(
  conversation: ConversaLocalRecord,
  currentUserId?: string,
): string {
  if (conversation.titulo) return conversation.titulo;
  const others = conversation.participantes
    .filter(activeParticipant)
    .filter((participant) => participant.colaboradorId !== currentUserId)
    .map((participant) => participant.nome);
  return others.join(", ") || "Conversa direta";
}

export function conversationScope(conversation: ConversaLocalRecord): string {
  const labels: Record<ConversaTipo, string> = {
    DIRETA: "Conversa direta",
    GRUPO: "Grupo",
    EQUIPE: "Equipe da obra",
    OBRA: "Conversa da obra",
  };
  return labels[conversation.tipo];
}

export function activeParticipant(
  participant: ConversaLocalRecord["participantes"][number],
): boolean {
  return participant.status === "ATIVO";
}

function previewText(message: MensagemLocalRecord, hasAttachment: boolean): string {
  if (message.status === "EXCLUIDA") return "Mensagem excluída";
  if (message.corpo?.trim()) return message.corpo.trim();
  return hasAttachment ? "Documento anexado" : "Mensagem sem texto";
}

function localDateKey(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const parts = new Intl.DateTimeFormat("pt-BR", {
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(date);
  const part = (type: Intl.DateTimeFormatPartTypes) =>
    parts.find((item) => item.type === type)?.value ?? "";
  return `${part("year")}-${part("month")}-${part("day")}`;
}

function formatDateLabel(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime())
    ? value
    : new Intl.DateTimeFormat("pt-BR", {
        weekday: "long",
        day: "2-digit",
        month: "long",
      }).format(date);
}
