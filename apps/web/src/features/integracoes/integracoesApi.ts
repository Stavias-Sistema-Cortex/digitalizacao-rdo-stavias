import type {
  IntegracaoStatus,
} from "./integracoes.types";
import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import { getSession } from "../auth/authSession";
import { getCortexDb } from "../../lib/db/cortexDb";
import {
  getSyncState,
  updateSyncState,
} from "../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../lib/sync/localMutationCoordinator";
import { isCanonicalOutboxMutation } from "../../lib/sync/mutationEnvelope";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export type IntegracaoRequestAction = "TESTAR" | "SINCRONIZAR";

export interface IntegracaoPendingRequest {
  id: string;
  integracaoId: string;
  acao: IntegracaoRequestAction;
  estado: "PENDENTE";
  /** Código de fila do protocolo; o servidor o exige literal. Não é frase. */
  motivo: "AGUARDANDO_REDE";
  requestedAt: string;
}

/**
 * O que dizer a quem espera, conferido contra o estado real do aparelho.
 *
 * O código `motivo` é constante de protocolo — o servidor recusa a solicitação
 * se vier outra coisa — e nunca foi uma leitura de conectividade. Exibi-lo cru
 * fazia a tela anunciar "aguardando rede... após a reconexão" com o computador
 * conectado: uma afirmação falsa sobre o sistema, que manda quem lê procurar um
 * problema de conexão inexistente. Com rede, a solicitação só espera a mesma
 * janela de sincronização de qualquer lançamento.
 */
export function descricaoDaEspera(online: boolean): string {
  return online
    ? "na fila; sobe na próxima sincronização"
    : "sem rede agora; sobe sozinha na reconexão";
}

async function readJson<T>(
  response: Response,
  invalidMessage: string,
): Promise<T> {
  const body = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(
      responseErrorMessage(body, response.status),
    );
  }

  if (body === null) {
    throw new Error(invalidMessage);
  }

  return body as T;
}

export async function listarIntegracoes(): Promise<
  IntegracaoStatus[]
> {
  const response = await apiFetch("/integracoes");

  return readJson<IntegracaoStatus[]>(
    response,
    "O backend retornou uma lista de integrações inválida.",
  );
}

async function globalMutationIdentity(): Promise<{
  userId: string;
  deviceId: string;
}> {
  const session = getSession();
  if (
    !session ||
    session.papelAcesso !== "ALFA" ||
    !session.escopoGlobal
  ) {
    throw new Error(
      "Solicitações de integração exigem uma sessão Alfa com escopo global.",
    );
  }
  const state = await getSyncState();
  const deviceId =
    state.usuarioId === session.colaboradorId &&
      state.deviceId &&
      UUID_PATTERN.test(state.deviceId)
      ? state.deviceId
      : crypto.randomUUID();
  if (
    state.deviceId !== deviceId ||
    state.usuarioId !== session.colaboradorId
  ) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }
  return { userId: session.colaboradorId, deviceId };
}

export async function solicitarIntegracao(
  integracaoId: string,
  acao: IntegracaoRequestAction,
): Promise<IntegracaoPendingRequest> {
  if (!["academy", "zeladoria"].includes(integracaoId)) {
    throw new Error("Integração desconhecida.");
  }
  const identity = await globalMutationIdentity();
  const request: IntegracaoPendingRequest = {
    id: crypto.randomUUID(),
    integracaoId,
    acao,
    estado: "PENDENTE",
    motivo: "AGUARDANDO_REDE",
    requestedAt: new Date().toISOString(),
  };

  await commitLocalMutation({
    ...identity,
    obraId: null,
    entityType: "SOLICITACAO_INTEGRACAO",
    entityId: request.id,
    entityName: integracaoId,
    operation: "CREATE",
    transportOperation: "SOLICITAR_INTEGRACAO",
    baseVersion: null,
    occurredAt: request.requestedAt,
    previousSnapshot: {},
    nextSnapshot: { ...request },
    eventType: "SOLICITACAO_INTEGRACAO_CRIADA",
    colaboradorId: identity.userId,
    relatedEntities: [],
    write: () => [],
  });
  return request;
}

export async function listarSolicitacoesIntegracaoPendentes(): Promise<
  IntegracaoPendingRequest[]
> {
  const mutations = await (await getCortexDb()).getAll("outbox_mutations");
  return mutations
    .filter(
      (mutation) =>
        isCanonicalOutboxMutation(mutation) &&
        mutation.entityType === "SOLICITACAO_INTEGRACAO" &&
        ["PENDING", "SYNCING", "ERROR"].includes(mutation.status),
    )
    .map((mutation) => mutation.payload as unknown as IntegracaoPendingRequest)
    .sort((left, right) => right.requestedAt.localeCompare(left.requestedAt));
}

export async function testarConexaoIntegracao(
  id: string,
): Promise<IntegracaoPendingRequest> {
  return solicitarIntegracao(id, "TESTAR");
}

export async function sincronizarIntegracao(
  id: string,
): Promise<IntegracaoPendingRequest> {
  return solicitarIntegracao(id, "SINCRONIZAR");
}
