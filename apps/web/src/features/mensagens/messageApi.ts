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
  ultimaMensagem: MessageDto | null;
  naoLidas: number;
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
  items: ConversationDto[];
  page: number;
  size: number;
  total: number;
  hasMore: boolean;
}

export interface MessagePageDto {
  items: MessageDto[];
  nextCursor: {
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
  limit = 50,
): Promise<MessagePageDto> {
  const params = new URLSearchParams({ limit: String(limit) });
  return requireOkJson<MessagePageDto>(
    `/conversas/${encodeURIComponent(conversationId)}/mensagens?${params}`,
  );
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
