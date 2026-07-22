import {
  getOutboxMutation,
  listOutboxMutations,
  putOutboxMutation,
} from "../../lib/db/outboxRepository";
import {
  getSyncState,
  updateSyncState,
} from "../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../lib/sync/localMutationCoordinator";
import type { MutationActor } from "../../lib/sync/mutationEnvelope";
import { getSession } from "../auth/authSession";
import type {
  FinancePurchase,
  FinancePurchaseDraft,
} from "./financeiro.types";

export interface QueuedFinancePurchase {
  id: string;
  clientMutationId: string;
}

function nowUtc(): string {
  return new Date().toISOString();
}

async function financeMutationActor(
  draft: FinancePurchaseDraft,
): Promise<MutationActor> {
  const session = getSession();
  if (!session) {
    throw new Error(
      "Abra uma sessão protegida antes de registrar uma compra local.",
    );
  }
  if (!session.escopoGlobal && !session.obraIds.includes(draft.obraId)) {
    throw new Error(
      "A sessão atual não possui acesso à obra desta compra.",
    );
  }

  const syncState = await getSyncState();
  const deviceId =
    syncState.usuarioId === session.colaboradorId && syncState.deviceId
      ? syncState.deviceId
      : crypto.randomUUID();
  if (
    syncState.usuarioId !== session.colaboradorId ||
    syncState.deviceId !== deviceId
  ) {
    await updateSyncState({
      usuarioId: session.colaboradorId,
      deviceId,
    });
  }

  return {
    actorId: session.colaboradorId,
    actorName: session.nome,
    deviceId,
    authorizationScope: [draft.obraId],
  };
}

export async function queueFinancePurchase(
  draft: FinancePurchaseDraft,
): Promise<QueuedFinancePurchase> {
  const id = crypto.randomUUID();
  const timestamp = nowUtc();
  const payload = {
    id,
    ...draft,
    solicitacaoId: draft.solicitacaoId ?? null,
  };
  const actor = await financeMutationActor(draft);
  const { mutation } = await commitLocalMutation({
    stores: [],
    entity: {
      type: "COMPRA",
      id,
      name: draft.numeroPedido || draft.descricao,
      obraId: draft.obraId,
      colaboradorId: actor.actorId,
      relatedEntities: [
        { tipo: "OBRA", id: draft.obraId },
      ],
    },
    eventType: "COMPRA_CRIADA",
    operation: "CRIAR_COMPRA",
    baseVersion: null,
    previousState: {},
    newState: payload,
    actor,
    createdAt: timestamp,
    write: () => undefined,
  });

  return { id, clientMutationId: mutation.clientMutationId };
}

export async function listPendingFinancePurchases(
  obraId: string,
): Promise<FinancePurchase[]> {
  const mutations = await listOutboxMutations();
  return mutations.flatMap((mutation) => {
    if (
      mutation.entidadeTipo !== "COMPRA" ||
      mutation.operacao !== "CRIAR_COMPRA" ||
      mutation.status === "SYNCED" ||
      mutation.payload.obraId !== obraId
    ) {
      return [];
    }
    const payload = mutation.payload as unknown as FinancePurchaseDraft & {
      id: string;
    };
    return [{
      ...payload,
      obraNome: null,
      centroCustoNome: null,
      categoriaNome: null,
      fornecedorNome: null,
      responsavelCompraNome: null,
      statusCodigo: null,
      statusNome: null,
      criadoEm: mutation.criadaNoClienteEm,
      atualizadoEm: mutation.updatedAt,
      versao: 0,
      clientMutationId: mutation.clientMutationId,
      syncStatus: mutation.status === "CONFLICT"
        ? "CONFLICT"
        : mutation.status === "ERROR"
          ? "ERROR"
          : "PENDING_SYNC",
      syncError: mutation.ultimoErro,
    }];
  });
}

export async function retryFinancePurchase(
  clientMutationId: string,
): Promise<void> {
  const mutation = await getOutboxMutation(clientMutationId);
  if (!mutation || mutation.entidadeTipo !== "COMPRA") {
    throw new Error("A compra local não foi encontrada na fila de sincronização.");
  }
  if (mutation.status !== "ERROR") {
    throw new Error("Somente compras com falha podem ser reenviadas.");
  }
  await putOutboxMutation({
    ...mutation,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: "Reenvio solicitado pelo usuário.",
    conflito: null,
    updatedAt: nowUtc(),
  });
}

export function mergeFinancePurchases(
  server: FinancePurchase[],
  local: FinancePurchase[],
): FinancePurchase[] {
  const byId = new Map(server.map((purchase) => [purchase.id, purchase]));
  for (const purchase of local) {
    if (!byId.has(purchase.id)) {
      byId.set(purchase.id, purchase);
    }
  }
  return [...byId.values()].sort((left, right) =>
    right.atualizadoEm.localeCompare(left.atualizadoEm),
  );
}
