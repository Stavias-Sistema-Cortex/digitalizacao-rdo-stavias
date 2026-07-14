import {
  getOutboxMutation,
  listOutboxMutations,
  putOutboxMutation,
} from "../../lib/db/outboxRepository";
import type { OutboxMutationRecord } from "../../lib/db/db.types";
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

export async function queueFinancePurchase(
  draft: FinancePurchaseDraft,
): Promise<QueuedFinancePurchase> {
  const id = crypto.randomUUID();
  const clientMutationId = crypto.randomUUID();
  const timestamp = nowUtc();
  const payload = {
    id,
    clientMutationId,
    solicitacaoId: draft.solicitacaoId ?? null,
    ...draft,
    baseVersao: null,
  };
  const mutation: OutboxMutationRecord = {
    clientMutationId,
    entidadeTipo: "COMPRA",
    entidadeId: id,
    operacao: "CRIAR_COMPRA",
    baseVersao: null,
    payload,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: timestamp,
    updatedAt: timestamp,
    transport: "SYNC_PUSH",
    correlationId: clientMutationId,
  };
  await putOutboxMutation(mutation);
  return { id, clientMutationId };
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
