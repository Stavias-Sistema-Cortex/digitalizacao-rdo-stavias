import type { MensagemLocalRecord } from "../../lib/db/db.types";

export interface ConversationPreview {
  messageId: string;
  text: string;
  authorName: string;
  at: string;
  syncStatus: MensagemLocalRecord["syncStatus"];
}

export type MessageTimelineEntry<T extends MensagemLocalRecord> =
  | { kind: "date"; key: string; label: string }
  | { kind: "message"; key: string; message: T; showAuthor: boolean };

export function buildMessageTimeline<T extends MensagemLocalRecord>(
  messages: T[],
): MessageTimelineEntry<T>[] {
  const result: MessageTimelineEntry<T>[] = [];
  let previousDate = "";
  let previousAuthor = "";
  for (const message of messages) {
    const date = localDateKey(message.criadaNoClienteEm);
    if (date !== previousDate) {
      result.push({
        kind: "date",
        key: `date:${date}`,
        label: formatDateLabel(message.criadaNoClienteEm),
      });
      previousDate = date;
      previousAuthor = "";
    }
    result.push({
      kind: "message",
      key: message.id,
      message,
      showAuthor: message.autorId !== previousAuthor,
    });
    previousAuthor = message.autorId;
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
      authorName: message.autorNome,
      at: message.criadaNoClienteEm,
      syncStatus: message.syncStatus,
    };
  }
  return previews;
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
