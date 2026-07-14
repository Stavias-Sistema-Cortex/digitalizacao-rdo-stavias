import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  readResponseBody: vi.fn(),
  responseErrorMessage: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", () => ({
  apiFetch: mocks.apiFetch,
  apiUrl: (path: string) => `/api${path}`,
  readResponseBody: mocks.readResponseBody,
  responseErrorMessage: mocks.responseErrorMessage,
}));

import {
  buscarCompras,
  buscarLancamentos,
  buscarNotasFiscais,
  registrarLiquidacao,
} from "./financeiroApi";
import type {
  FinanceFilters,
  FinanceLedgerEntry,
} from "./financeiro.types";

const FILTERS: FinanceFilters = {
  obraId: "obra-1",
  de: "2026-07-01",
  ate: "2026-07-31",
  fornecedorId: "fornecedor-1",
  centroCustoId: "centro-1",
  categoriaId: "categoria-1",
  statusId: "status-1",
  prioridade: "ALTA",
  tipo: "PAGAR",
  moeda: "BRL",
  query: "concreto",
};

function okResponse(): Response {
  return { ok: true, status: 200 } as Response;
}

describe("financeiroApi query contracts", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue(okResponse());
    mocks.readResponseBody.mockResolvedValue([]);
  });

  it("envia prioridade somente ao endpoint de compras", async () => {
    await buscarCompras(FILTERS);
    await buscarNotasFiscais(FILTERS);

    const purchaseUrl = String(mocks.apiFetch.mock.calls[0][0]);
    const invoiceUrl = String(mocks.apiFetch.mock.calls[1][0]);

    expect(purchaseUrl).toContain("prioridade=ALTA");
    expect(purchaseUrl).not.toContain("tipo=");
    expect(purchaseUrl).not.toContain("moeda=");
    expect(invoiceUrl).not.toContain("prioridade=");
    expect(invoiceUrl).not.toContain("tipo=");
    expect(invoiceUrl).not.toContain("moeda=");
  });

  it("envia tipo somente ao endpoint de lançamentos", async () => {
    await buscarLancamentos(FILTERS);

    const ledgerUrl = String(mocks.apiFetch.mock.calls[0][0]);
    expect(ledgerUrl).toContain("tipo=PAGAR");
    expect(ledgerUrl).not.toContain("prioridade=");
    expect(ledgerUrl).not.toContain("moeda=");
  });

  it("traduz o tipo canônico do lançamento ao registrar a liquidação", async () => {
    await registrarLiquidacao({
      id: "lancamento-1",
      obraId: "obra-1",
      tipo: "RECEBER",
      moeda: "BRL",
    } as FinanceLedgerEntry, 150, "2026-07-14", "PIX");

    const request = mocks.apiFetch.mock.calls[0][1] as RequestInit;
    expect(mocks.apiFetch.mock.calls[0][0]).toBe("/financeiro/liquidacoes");
    expect(JSON.parse(String(request.body))).toMatchObject({
      tipo: "RECEBIMENTO",
      valorTotal: 150,
      lancamentos: [{ lancamentoId: "lancamento-1", valorAplicado: 150 }],
    });
  });
});
