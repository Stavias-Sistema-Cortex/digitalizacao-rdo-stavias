import {
  apiError,
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

export interface ConversationPreferenceApi {
  conversationId: string;
  limpoAte: string | null;
}

export interface ConversationAuthorizationSnapshotApi {
  authorizedConversationIds: string[];
  preferences: ConversationPreferenceApi[];
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
    throw apiError(body, response.status);
  }
  return body as T;
}

export async function listConversationsApi(
  limit = 100,
  arquivadas = false,
): Promise<ConversationApi[]> {
  return readJson<ConversationApi[]>(
    await apiFetch(
      `/mensagens/conversas?limit=${limit}&arquivadas=${arquivadas}`,
    ),
  );
}

/**
 * Snapshot completo das conversas que a sessão ainda pode ler.
 *
 * A listagem visual é paginada e separa ativas de arquivadas; por isso ela
 * nunca pode ser usada como prova de revogação. Este endpoint pequeno traz só
 * as identidades e é o único retrato apto a autorizar a poda do cache local.
 */
export async function listAuthorizedConversationIdsApi(): Promise<string[]> {
  const body = await readJson<unknown>(
    await apiFetch("/mensagens/conversas/autorizadas/ids"),
  );
  if (
    !Array.isArray(body) ||
    body.some((value) => typeof value !== "string" || !value.trim())
  ) {
    throw new Error("O retrato de conversas autorizadas veio incompleto.");
  }
  return [...new Set(body.map((value) => value.trim()))];
}

/** Gate completo de acesso e preferencias pessoais do mesmo snapshot. */
export async function getConversationAuthorizationSnapshotApi(): Promise<
  ConversationAuthorizationSnapshotApi
> {
  const body = await readJson<unknown>(
    await apiFetch("/mensagens/conversas/autorizadas/snapshot"),
  );
  if (!body || typeof body !== "object") {
    throw new Error("O retrato de preferencias pessoais veio incompleto.");
  }
  const candidate = body as Record<string, unknown>;
  if (
    !Array.isArray(candidate.authorizedConversationIds) ||
    !Array.isArray(candidate.preferences)
  ) {
    throw new Error("O retrato de preferencias pessoais veio incompleto.");
  }
  const authorizedConversationIds = candidate.authorizedConversationIds;
  if (
    authorizedConversationIds.some(
      (value) => typeof value !== "string" || !value.trim(),
    )
  ) {
    throw new Error("O retrato de preferencias pessoais veio incompleto.");
  }
  const normalizedIds = [
    ...new Set(authorizedConversationIds.map((value) => value.trim())),
  ];
  const preferences: ConversationPreferenceApi[] = [];
  const preferenceIds = new Set<string>();
  for (const raw of candidate.preferences) {
    if (!raw || typeof raw !== "object") {
      throw new Error("O retrato de preferencias pessoais veio incompleto.");
    }
    const preference = raw as Record<string, unknown>;
    const conversationId = typeof preference.conversationId === "string"
      ? preference.conversationId.trim()
      : "";
    const limpoAte = preference.limpoAte;
    if (
      !conversationId ||
      preferenceIds.has(conversationId) ||
      (
        limpoAte !== null &&
        (
          typeof limpoAte !== "string" ||
          !/(?:Z|[+-]\d{2}:\d{2})$/i.test(limpoAte) ||
          !Number.isFinite(Date.parse(limpoAte))
        )
      )
    ) {
      throw new Error("O retrato de preferencias pessoais veio incompleto.");
    }
    preferenceIds.add(conversationId);
    preferences.push({ conversationId, limpoAte });
  }
  if (
    preferenceIds.size !== normalizedIds.length ||
    normalizedIds.some((id) => !preferenceIds.has(id))
  ) {
    throw new Error("O retrato de preferencias pessoais veio incompleto.");
  }
  return { authorizedConversationIds: normalizedIds, preferences };
}

/**
 * Arruma a caixa de quem está pedindo — e só a dela.
 *
 * <p>Chamadas diretas, fora da fila do aparelho: são preferência de leitura,
 * não conteúdo de obra. Se não houver rede, o gesto falha na hora e a tela diz;
 * enfileirá-lo faria a conversa sumir aqui e voltar depois, sem explicação.
 */
export async function arquivarConversaApi(
  conversationId: string,
  arquivar: boolean,
): Promise<void> {
  const acao = arquivar ? "arquivar" : "desarquivar";
  const resposta = await apiFetch(
    `/mensagens/conversas/${encodeURIComponent(conversationId)}/${acao}`,
    { method: "POST" },
  );
  if (!resposta.ok) {
    throw new Error(
      responseErrorMessage(await readResponseBody(resposta), resposta.status),
    );
  }
}

/**
 * Fecha (ou reabre) a cortina sobre o histórico, para quem pediu.
 *
 * <p>Nada é apagado: as mensagens seguem no servidor e na tela de quem estava
 * junto. É por isso que reabrir existe.
 */
export async function limparConversaApi(
  conversationId: string,
  limpar: boolean,
  limpoAte?: string,
): Promise<void> {
  const acao = limpar ? "limpar" : "reabrir-historico";
  const options: RequestInit = limpar && limpoAte
    ? {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ limpoAte }),
    }
    : { method: "POST" };
  const resposta = await apiFetch(
    `/mensagens/conversas/${encodeURIComponent(conversationId)}/${acao}`,
    options,
  );
  if (!resposta.ok) {
    throw new Error(
      responseErrorMessage(await readResponseBody(resposta), resposta.status),
    );
  }
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
