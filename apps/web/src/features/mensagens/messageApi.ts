import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

export interface ConversationParticipantDto {
  id: string;
  conversaId: string;
  colaboradorId: string;
  colaboradorNome: string;
  papel: "ADMINISTRADOR" | "MEMBRO";
  status: "ATIVO" | "SAIU" | "REMOVIDO";
  entrouEm: string;
  saiuEm: string | null;
  ultimaLeituraEm: string | null;
  versaoEntidade: number;
}

export interface ConversationDto {
  id: string;
  tipo: "OBRA" | "EQUIPE" | "DIRETA" | "GRUPO";
  titulo: string | null;
  obraId: string | null;
  obraNome: string | null;
  equipeId: string | null;
  equipeNome: string | null;
  criadoPor: string;
  status: "ATIVA" | "ARQUIVADA";
  ultimaAtividadeEm: string;
  criadoEm: string;
  atualizadoEm: string;
  versaoEntidade: number;
  participantes: ConversationParticipantDto[];
}

export interface ConversationSummaryDto {
  id: string;
  tipo: "OBRA" | "EQUIPE" | "DIRETA" | "GRUPO";
  titulo: string | null;
  obraId: string | null;
  obraNome: string | null;
  equipeId: string | null;
  equipeNome: string | null;
  status: "ATIVA" | "ARQUIVADA";
  ultimaAtividadeEm: string;
  ultimaMensagemId: string | null;
  ultimaMensagemPrevia: string | null;
  ultimaMensagemEm: string | null;
  naoLidas: number;
  versaoEntidade: number;
}

export interface MessageReferenceDto {
  id: string;
  mensagemId: string;
  tipoObjeto: string;
  objetoId: string;
  obraId: string | null;
  criadoEm: string;
}

export interface MessageAttachmentDto {
  id: string;
  mensagemId: string;
  clientAttachmentId: string;
  nomeOriginal: string;
  nomeSeguro: string;
  mimeType: string;
  tamanhoBytes: number;
  hashSha256: string;
  status: "PENDENTE" | "DISPONIVEL" | "FALHOU";
  ultimoErro: string | null;
  criadoEm: string;
  atualizadoEm: string;
  disponivelEm: string | null;
  versaoEntidade: number;
}

export interface MessageReceiptDto {
  id: string;
  mensagemId: string;
  colaboradorId: string;
  entregueEm: string | null;
  lidaEm: string | null;
}

export interface MessageDto {
  id: string;
  conversaId: string;
  remetenteId: string;
  remetenteNome: string;
  clientMessageId: string;
  texto: string | null;
  estado: string;
  enviadaClienteEm: string;
  criadaServidorEm: string;
  atualizadaEm: string;
  versaoEntidade: number;
  referencias: MessageReferenceDto[];
  anexos: MessageAttachmentDto[];
  recibos: MessageReceiptDto[];
}

export interface ConversationPageDto {
  items: ConversationSummaryDto[];
  page: number;
  size: number;
  totalElements: number;
}

export interface MessagePageDto {
  items: MessageDto[];
  proximoCursor: {
    enviadaClienteEm: string;
    criadaServidorEm: string;
    id: string;
  } | null;
  hasMore: boolean;
}

export class MessageApiError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "MessageApiError";
    this.status = status;
  }
}

async function requireOkJson<T>(
  path: string,
  options?: RequestInit,
): Promise<T> {
  const response = await apiFetch(path, options);
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new MessageApiError(
      response.status,
      responseErrorMessage(body, response.status),
    );
  }
  return body as T;
}

export async function fetchConversations(
  query: {
    texto?: string;
    obraId?: string;
    equipeId?: string;
    page?: number;
    size?: number;
  } = {},
): Promise<ConversationPageDto> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && String(value).trim()) {
      params.set(key, String(value));
    }
  }
  const suffix = params.size > 0 ? `?${params.toString()}` : "";
  return requireOkJson<ConversationPageDto>(`/conversas${suffix}`);
}

export async function fetchConversation(
  conversationId: string,
): Promise<ConversationDto> {
  return requireOkJson<ConversationDto>(
    `/conversas/${encodeURIComponent(conversationId)}`,
  );
}

export async function fetchMessages(
  conversationId: string,
  query: {
    limit?: number;
    antesDeClienteEm?: string;
    antesDeServidorEm?: string;
    antesDeId?: string;
  } = {},
): Promise<MessagePageDto> {
  const params = new URLSearchParams({
    limit: String(query.limit ?? 50),
  });
  if (query.antesDeClienteEm) {
    params.set("antesDeClienteEm", query.antesDeClienteEm);
  }
  if (query.antesDeServidorEm) {
    params.set("antesDeServidorEm", query.antesDeServidorEm);
  }
  if (query.antesDeId) {
    params.set("antesDeId", query.antesDeId);
  }
  return requireOkJson<MessagePageDto>(
    `/conversas/${encodeURIComponent(conversationId)}/mensagens?${params}`,
  );
}

export async function createConversation(input: {
  id: string;
  tipo: ConversationDto["tipo"];
  titulo: string | null;
  obraId: string | null;
  equipeId: string | null;
  participanteIds: string[];
}): Promise<ConversationDto> {
  return requireOkJson<ConversationDto>("/conversas", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(input),
  });
}

export async function uploadMessageAttachment(
  messageId: string,
  attachmentId: string,
  arquivo: Blob,
  filename: string,
): Promise<MessageAttachmentDto> {
  const form = new FormData();
  form.append("arquivo", arquivo, filename);
  return requireOkJson<MessageAttachmentDto>(
    `/mensagens/${encodeURIComponent(messageId)}/anexos/${encodeURIComponent(
      attachmentId,
    )}/conteudo`,
    {
      method: "PUT",
      body: form,
      timeoutMs: 60_000,
    } as RequestInit,
  );
}

export async function markMessageRead(
  messageId: string,
): Promise<MessageReceiptDto> {
  return requireOkJson<MessageReceiptDto>(
    `/mensagens/${encodeURIComponent(messageId)}/recibos/leitura`,
    { method: "POST" },
  );
}

export async function downloadMessageAttachment(
  messageId: string,
  attachmentId: string,
): Promise<Blob> {
  const response = await apiFetch(
    `/mensagens/${encodeURIComponent(messageId)}/anexos/${encodeURIComponent(
      attachmentId,
    )}/conteudo`,
  );
  if (!response.ok) {
    const body = await readResponseBody(response);
    throw new MessageApiError(
      response.status,
      responseErrorMessage(body, response.status),
    );
  }
  return response.blob();
}
