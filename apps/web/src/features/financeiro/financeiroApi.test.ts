import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  readResponseBody: vi.fn(),
  responseErrorMessage: vi.fn(),
  ensureRegisteredDevice: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", () => ({
  apiFetch: mocks.apiFetch,
  apiUrl: (path: string) => `/api${path}`,
  readResponseBody: mocks.readResponseBody,
  responseErrorMessage: mocks.responseErrorMessage,
}));

vi.mock("../../lib/sync/registerDevice", () => ({
  ensureRegisteredDevice: mocks.ensureRegisteredDevice,
}));

import {
  buscarRateios,
  buscarUnidadesFinanceiras,
  buscarCompras,
  buscarLancamentos,
  buscarNotasFiscais,
  buscarRastreioReceita,
  enviarDocumentoFiscal,
  registrarLiquidacao,
  vincularDocumentoNota,
} from "./financeiroApi";
import type {
  FinanceFiscalExtraction,
  FinanceFilters,
  FinanceInvoice,
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
  manualMode: "ALL",
  manualRules: [],
};

function okResponse(): Response {
  return { ok: true, status: 200 } as Response;
}

describe("financeiroApi query contracts", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue(okResponse());
    mocks.readResponseBody.mockResolvedValue([]);
    mocks.ensureRegisteredDevice.mockResolvedValue("device-1");
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

  it("envia arquivo fiscal com hash, mutação estável e dispositivo", async () => {
    const extraction = {
      id: "job-1",
      storedObjectId: "object-1",
      serverSha256: "a".repeat(64),
    } as FinanceFiscalExtraction;
    mocks.readResponseBody.mockResolvedValue(extraction);
    const file = new File(["<NFe/>"] , "nota.xml", {
      type: "application/xml",
    });

    const result = await enviarDocumentoFiscal(
      file,
      "obra-1",
      "upload-mutation-1",
    );

    const url = String(mocks.apiFetch.mock.calls[0][0]);
    const request = mocks.apiFetch.mock.calls[0][1] as RequestInit;
    expect(url).toContain("/financeiro/notas-fiscais/extracoes?");
    expect(url).toContain("obraId=obra-1");
    expect(url).toContain("clientMutationId=upload-mutation-1");
    expect(url).toContain("dispositivoId=device-1");
    expect(url).toMatch(/sha256Cliente=[0-9a-f]{64}/);
    expect(request.method).toBe("POST");
    expect((request.body as FormData).get("arquivo")).toBe(file);
    expect(result).toBe(extraction);
  });

  it("vincula o job confirmado sem perder hashes ou autoria do dispositivo", async () => {
    mocks.readResponseBody.mockResolvedValue({ id: "document-1" });
    const extraction = {
      id: "job-1",
      storedObjectId: "object-1",
      clientSha256: "a".repeat(64),
    } as FinanceFiscalExtraction;

    await vincularDocumentoNota(
      { id: "invoice-1", obraId: "obra-1" } as FinanceInvoice,
      extraction,
      "XML",
      true,
      "confirmation-mutation-1",
    );

    const request = mocks.apiFetch.mock.calls[0][1] as RequestInit;
    expect(mocks.apiFetch.mock.calls[0][0])
      .toContain("/financeiro/notas-fiscais/invoice-1/anexos?obraId=obra-1");
    expect(JSON.parse(String(request.body))).toMatchObject({
      storedObjectId: "object-1",
      tipoDocumento: "XML",
      principal: true,
      clientMutationId: "confirmation-mutation-1",
      extracaoJobId: "job-1",
      sha256Cliente: "a".repeat(64),
      dispositivoId: "device-1",
    });
  });

  it("lista unidades e rateios no escopo explícito sem obra sintética", async () => {
    await buscarUnidadesFinanceiras("ATIVO", "escavadeira");
    await buscarRateios("unit-asset-1");

    expect(mocks.apiFetch.mock.calls[0][0]).toBe(
      "/financeiro/unidades?tipo=ATIVO&status=ATIVA&busca=escavadeira",
    );
    expect(mocks.apiFetch.mock.calls[1][0]).toBe(
      "/financeiro/rateios?unidadeId=unit-asset-1",
    );
  });

  it("consulta o rastreio canônico sem inventar receita indisponível", async () => {
    const trace = {
      consolidado: {
        producao: 12,
        custo: 400,
        receitaEstimada: 0,
        margem: -400,
        receitaMedida: 0,
        receitaAprovada: 0,
        receitaFaturada: 0,
        receitaRecebida: 0,
      },
      obras: [],
      tiposServico: [{
        nome: "Terraplenagem",
        unidade: "m³",
        totais: {
          producao: 12,
          custo: 400,
          receitaEstimada: 0,
          margem: -400,
          receitaMedida: 0,
          receitaAprovada: 0,
          receitaFaturada: 0,
          receitaRecebida: 0,
        },
        obras: [],
        quantidadeRdos: 1,
        receitaDisponivel: false,
      }],
    };
    mocks.readResponseBody.mockResolvedValue(trace);

    const result = await buscarRastreioReceita({
      obraId: "obra-1",
      de: "2026-07-01",
      ate: "2026-07-31",
    });

    expect(mocks.apiFetch.mock.calls[0][0]).toBe(
      "/financeiro/rastreio-receita?obraId=obra-1&de=2026-07-01&ate=2026-07-31",
    );
    expect(mocks.apiFetch.mock.calls[0][1]).toMatchObject({
      cache: "no-store",
    });
    expect(result.tiposServico[0].receitaDisponivel).toBe(false);
  });
});
