import "fake-indexeddb/auto";

import { beforeEach, describe, expect, it, vi } from "vitest";

import { setSession } from "../auth/authSession";
import {
  getOutboxMutation,
  listOutboxMutations,
  putOutboxMutation,
} from "../../lib/db/outboxRepository";
import {
  listPendingFinancePurchases,
  queueFinancePurchase,
  retryFinancePurchase,
} from "./financeiroOfflineRepository";

describe("financeiro offline repository", () => {
  beforeEach(() => {
    vi.stubGlobal("BroadcastChannel", undefined);
    setSession({
      colaboradorId: crypto.randomUUID(),
      nome: "Comprador da obra",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: ["00000000-0000-4000-8000-000000000001"],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
  });

  it("salva a compra otimista na mesma outbox idempotente da sincronização", async () => {
    const queued = await queueFinancePurchase({
      obraId: "00000000-0000-4000-8000-000000000001",
      fornecedorId: "fornecedor-1",
      centroCustoId: "centro-1",
      categoriaId: "categoria-1",
      responsavelCompraId: null,
      statusId: "status-1",
      numeroPedido: "PED-2026-14",
      descricao: "Brita graduada",
      prioridade: "ALTA",
      moeda: "BRL",
      valorPrevisto: 12500,
      valorAprovado: null,
      valorContratado: null,
      valorPago: null,
      pedidoEmitidoEm: "2026-07-14",
      entregaPrevistaEm: "2026-07-20",
      itens: [],
    });

    const outbox = await listOutboxMutations();
    const pending = await listPendingFinancePurchases(
      "00000000-0000-4000-8000-000000000001",
    );

    expect(outbox).toHaveLength(1);
    expect(outbox[0]).toMatchObject({
      clientMutationId: queued.clientMutationId,
      entidadeTipo: "COMPRA",
      operacao: "CRIAR_COMPRA",
      status: "PENDING",
      baseVersao: null,
    });
    expect(pending[0]).toMatchObject({
      id: queued.id,
      clientMutationId: queued.clientMutationId,
      descricao: "Brita graduada",
      syncStatus: "PENDING_SYNC",
    });
  });

  it("recoloca uma compra com erro na fila sem alterar o payload rastreável", async () => {
    const queued = await queueFinancePurchase({
      obraId: "00000000-0000-4000-8000-000000000001",
      fornecedorId: "fornecedor-2",
      centroCustoId: "centro-2",
      categoriaId: "categoria-2",
      responsavelCompraId: null,
      statusId: "status-2",
      numeroPedido: "PED-RETRY",
      descricao: "Compra para reenvio",
      prioridade: "MEDIA",
      moeda: "BRL",
      valorPrevisto: 450,
      valorAprovado: null,
      valorContratado: null,
      valorPago: null,
      pedidoEmitidoEm: null,
      entregaPrevistaEm: null,
      itens: [],
    });
    const original = await getOutboxMutation(queued.clientMutationId);
    await putOutboxMutation({
      ...original!,
      status: "ERROR",
      tentativas: 3,
      ultimoErro: "Referência temporariamente indisponível.",
    });

    await retryFinancePurchase(queued.clientMutationId);

    expect(await getOutboxMutation(queued.clientMutationId)).toMatchObject({
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: "Reenvio solicitado pelo usuário.",
      payload: original?.payload,
    });
  });
});
