import {
  getLocalConversation,
  putLocalConversation,
} from "../../lib/db/conversationLocalRepository";
import type {
  LocalConversationParticipantRecord,
  LocalConversationRecord,
  LocalMessageAttachmentRecord,
  LocalMessageRecord,
  LocalMessageReferenceRecord,
} from "../../lib/db/db.types";
import { putLocalMessageProjection } from "../../lib/db/messageLocalRepository";
import type {
  ConversationDto,
  ConversationPageDto,
  ConversationSummaryDto,
  MessageDto,
  MessagePageDto,
} from "./messageApi";

function toLocalMessage(message: MessageDto): LocalMessageRecord {
  return {
    id: message.id,
    conversaId: message.conversaId,
    remetenteId: message.remetenteId,
    remetenteNome: message.remetenteNome,
    clientMessageId: message.clientMessageId,
    texto: message.texto,
    estado: message.estado,
    enviadaClienteEm: message.enviadaClienteEm,
    criadaServidorEm: message.criadaServidorEm,
    atualizadoEm: message.atualizadaEm,
    versaoEntidade: message.versaoEntidade,
    recibos: message.recibos.map((receipt) => ({ ...receipt })),
    syncStatus: "SENT",
    ultimoErro: null,
  };
}

function toLocalReferences(
  message: MessageDto,
): LocalMessageReferenceRecord[] {
  return message.referencias.map((reference) => ({ ...reference }));
}

function toLocalAttachments(
  message: MessageDto,
): LocalMessageAttachmentRecord[] {
  return message.anexos.map((attachment) => ({
    ...attachment,
    arquivo: null,
    syncStatus:
      attachment.status === "DISPONIVEL"
        ? "UPLOADED"
        : attachment.status === "FALHOU"
          ? "ERROR"
          : "WAITING_MESSAGE",
    tentativas: 0,
    ultimaTentativaEm: null,
    proximaTentativaEm: null,
  }));
}

function toLocalConversation(
  conversation: ConversationDto,
  existing?: LocalConversationRecord,
): LocalConversationRecord {
  return {
    id: conversation.id,
    tipo: conversation.tipo,
    titulo: conversation.titulo,
    obraId: conversation.obraId,
    obraNome: conversation.obraNome,
    equipeId: conversation.equipeId,
    equipeNome: conversation.equipeNome,
    criadoPor: conversation.criadoPor,
    status: conversation.status,
    ultimaAtividadeEm: conversation.ultimaAtividadeEm,
    ultimaMensagemId: existing?.ultimaMensagemId ?? null,
    ultimaMensagemPrevia: existing?.ultimaMensagemPrevia ?? null,
    ultimaMensagemEm: existing?.ultimaMensagemEm ?? null,
    naoLidas: existing?.naoLidas ?? 0,
    criadoEm: conversation.criadoEm,
    atualizadoEm: conversation.atualizadoEm,
    versaoEntidade: conversation.versaoEntidade,
  };
}

function toLocalConversationSummary(
  conversation: ConversationSummaryDto,
  existing?: LocalConversationRecord,
): LocalConversationRecord {
  return {
    id: conversation.id,
    tipo: conversation.tipo,
    titulo: conversation.titulo,
    obraId: conversation.obraId,
    obraNome: conversation.obraNome,
    equipeId: conversation.equipeId,
    equipeNome: conversation.equipeNome,
    criadoPor: existing?.criadoPor ?? null,
    status: conversation.status,
    ultimaAtividadeEm: conversation.ultimaAtividadeEm,
    ultimaMensagemId: conversation.ultimaMensagemId,
    ultimaMensagemPrevia: conversation.ultimaMensagemPrevia,
    ultimaMensagemEm: conversation.ultimaMensagemEm,
    naoLidas: conversation.naoLidas,
    criadoEm: existing?.criadoEm ?? null,
    atualizadoEm: conversation.ultimaAtividadeEm,
    versaoEntidade: conversation.versaoEntidade,
  };
}

function toLocalParticipants(
  conversation: ConversationDto,
): LocalConversationParticipantRecord[] {
  return conversation.participantes.map((participant) => ({
    ...participant,
    atualizadoEm: conversation.atualizadoEm,
  }));
}

export async function hydrateMessage(message: MessageDto): Promise<void> {
  await putLocalMessageProjection(
    toLocalMessage(message),
    toLocalReferences(message),
    toLocalAttachments(message),
  );
}

export async function hydrateConversation(
  conversation: ConversationDto,
): Promise<void> {
  const existing = await getLocalConversation(conversation.id);
  await putLocalConversation(
    toLocalConversation(conversation, existing),
    toLocalParticipants(conversation),
  );
}

export async function hydrateConversationPage(
  page: ConversationPageDto,
): Promise<void> {
  for (const conversation of page.items) {
    const existing = await getLocalConversation(conversation.id);
    await putLocalConversation(
      toLocalConversationSummary(conversation, existing),
    );
  }
}

export async function hydrateMessagePage(page: MessagePageDto): Promise<void> {
  for (const message of page.items) {
    await hydrateMessage(message);
  }
}
