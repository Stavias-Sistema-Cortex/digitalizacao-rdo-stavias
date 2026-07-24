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
import { getSession } from "../auth/authSession";
import type {
  FinancePurchase,
  FinancePurchaseDraft,
} from "./financeiro.types";

export interface QueuedFinancePurchase {
  id: string;
  clientMutationId: string;
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function nowUtc(): string {
  return new Date().toISOString();
}

async function financeMutationIdentity(obraId: string): Promise<{
  obraId: string;
  userId: string;
  deviceId: string;
}> {
  const worksiteId = obraId.trim().toLowerCase();
  if (!UUID_PATTERN.test(worksiteId)) {
    throw new Error("A obra da compra deve possuir um ID canônico.");
  }
  const session = getSession();
  if (!session) {
    throw new Error(
      "Abra uma sessão protegida antes de registrar uma compra local.",
    );
  }
  if (!session.escopoGlobal && !session.obraIds.includes(worksiteId)) {
    throw new Error(
      "A sessão atual não possui acesso à obra desta compra.",
    );
  }

  const syncState = await getSyncState();
  const deviceId =
    syncState.usuarioId === session.colaboradorId &&
      syncState.deviceId &&
      UUID_PATTERN.test(syncState.deviceId)
      ? syncState.deviceId
      : crypto.randomUUID();
  if (
    syncState.usuarioId !== session.colaboradorId ||
    syncState.deviceId !== deviceId
  ) {
    await updateSyncState({
      usuarioId: session.colaboradorId,
      deviceId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }
  return {
    obraId: worksiteId,
    userId: session.colaboradorId,
    deviceId,
  };
}

export async function queueFinancePurchase(
  draft: FinancePurchaseDraft,
): Promise<QueuedFinancePurchase> {
  const id = crypto.randomUUID();
  const timestamp = nowUtc();
  const identity = await financeMutationIdentity(draft.obraId);
  const payload = {
    id,
    ...draft,
    obraId: identity.obraId,
    solicitacaoId: draft.solicitacaoId ?? null,
  };
  const committed = await commitLocalMutation({
    ...identity,
    entityType: "COMPRA",
    entityId: id,
    entityName: draft.numeroPedido || draft.descricao,
    operation: "CREATE",
    transportOperation: "CRIAR_COMPRA",
    baseVersion: null,
    occurredAt: timestamp,
    previousSnapshot: {},
    nextSnapshot: payload,
    eventType: "COMPRA_CRIADA",
    relatedEntities: [
      { tipo: "OBRA", id: identity.obraId },
    ],
    colaboradorId: identity.userId,
    write: () => [],
  });

  return {
    id,
    clientMutationId: committed.mutation.clientMutationId,
  };
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
