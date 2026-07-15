import {
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
    naoLidas: conversation.naoLidas,
    criadoEm: conversation.criadoEm,
    atualizadoEm: conversation.atualizadoEm,
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
  await putLocalConversation(
    toLocalConversation(conversation),
    toLocalParticipants(conversation),
  );
  if (conversation.ultimaMensagem) {
    await hydrateMessage(conversation.ultimaMensagem);
  }
}

export async function hydrateConversationPage(
  page: ConversationPageDto,
): Promise<void> {
  for (const conversation of page.items) {
    await hydrateConversation(conversation);
  }
}

export async function hydrateMessagePage(page: MessagePageDto): Promise<void> {
  for (const message of page.items) {
    await hydrateMessage(message);
  }
}
