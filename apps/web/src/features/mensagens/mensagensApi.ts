import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import type { ConversaTipo } from "../../lib/db/db.types";

export interface ParticipantApi {
  colaboradorId: string;
  nome: string;
  papel: "ADMIN" | "MEMBRO";
  status: "ATIVO" | "REMOVIDO";
  adicionadoEm: string;
}

export interface ConversationApi {
  id: string;
  tipo: ConversaTipo;
  titulo: string | null;
  obraId: string | null;
  equipeId: string | null;
  status: string;
  criadaEm: string;
  atualizadaEm: string;
  versao: number;
  participantes: ParticipantApi[];
}

export interface AttachmentApi {
  id: string;
  objetoId: string;
  nome: string;
  mediaType: string;
  tamanhoBytes: number;
  sha256: string;
  ordem: number;
}

export interface MessageApi {
  id: string;
  conversaId: string;
  autorId: string;
  autorNome: string;
  corpo: string | null;
  status: "ATIVA" | "EDITADA" | "EXCLUIDA";
  clientMutationId: string;
  criadaNoClienteEm: string;
  criadaEm: string;
  editadaEm: string | null;
  deletadaEm: string | null;
  versao: number;
  anexos: AttachmentApi[];
}

async function readJson<T>(response: Response): Promise<T> {
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  return body as T;
}

export async function listConversationsApi(
  limit = 100,
): Promise<ConversationApi[]> {
  return readJson<ConversationApi[]>(
    await apiFetch(`/mensagens/conversas?limit=${limit}`),
  );
}

export async function getMessageHistoryApi(
  conversationId: string,
  limit = 100,
): Promise<MessageApi[]> {
  return readJson<MessageApi[]>(
    await apiFetch(
      `/mensagens/conversas/${encodeURIComponent(conversationId)}/mensagens?limit=${limit}`,
    ),
  );
}

export async function searchMessagesApi(
  query: string,
  limit = 50,
): Promise<MessageApi[]> {
  const params = new URLSearchParams({
    q: query.trim(),
    limit: String(limit),
  });
  return readJson<MessageApi[]>(
    await apiFetch(`/mensagens/busca?${params.toString()}`),
  );
}

export async function createConversationApi(input: {
  tipo: ConversaTipo;
  titulo?: string | null;
  obraId?: string | null;
  equipeId?: string | null;
  participanteIds: string[];
}): Promise<ConversationApi> {
  return readJson<ConversationApi>(
    await apiFetch("/mensagens/conversas", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        id: crypto.randomUUID(),
        tipo: input.tipo,
        titulo: input.titulo?.trim() || null,
        obraId: input.obraId || null,
        equipeId: input.equipeId || null,
        participanteIds: input.participanteIds,
      }),
    }),
  );
}

export async function downloadMessageAttachmentApi(
  attachmentId: string,
): Promise<Blob> {
  const response = await apiFetch(
    `/mensagens/anexos/${encodeURIComponent(attachmentId)}`,
  );
  if (!response.ok) {
    const body = await readResponseBody(response);
    throw new Error(responseErrorMessage(body, response.status));
  }
  return response.blob();
}

/**
 * Quem posso procurar para conversar.
 *
 * <p>Separado do catálogo administrativo de propósito: aquele é restrito ao
 * papel Alfa, e usá-lo aqui era o que fazia a busca de participante devolver
 * 403 para todo mundo que não administra o sistema. Este devolve só o
 * necessário para endereçar — quem é a pessoa e o que ela faz.
 */
export interface DiretorioPessoa {
  id: string;
  nome: string | null;
  nomePerfil: string | null;
}

export async function buscarDiretorioDeMensagens(
  query: string,
): Promise<DiretorioPessoa[]> {
  const params = new URLSearchParams();
  if (query.trim()) {
    params.set("query", query.trim());
  }
  const response = await apiFetch(
    `/mensagens/diretorio?${params.toString()}`,
  );
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  if (!Array.isArray(body)) {
    throw new Error("O diretório de pessoas veio incompleto.");
  }
  return body as DiretorioPessoa[];
}
