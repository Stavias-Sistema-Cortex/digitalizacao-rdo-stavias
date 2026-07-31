// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const resolveFinanceCapabilities = vi.hoisted(() => vi.fn());
const loadRevenueTraceSnapshot = vi.hoisted(() => vi.fn());

vi.mock("../../financeiro/financeCapabilitiesResolver", () => ({
  resolveFinanceCapabilities,
}));
vi.mock("../../financeiro/revenueTraceCacheRepository", () => ({
  loadRevenueTraceSnapshot,
}));

const { TrechoReceitaSection } = await import("./TrechoReceitaSection");

interface RowOverrides {
  rdoId?: string;
  serviceId?: string;
  serviceName?: string;
  unit?: string;
  currency?: string;
  quantity?: number | string;
  revenue?: number | string;
}

function row(overrides: RowOverrides = {}) {
  return {
    worksiteId: "obra-1",
    worksiteName: "Recapeamento SP-310",
    rdoId: "rdo-1",
    rdoNumber: "0042",
    executionId: "exec-1",
    executionDate: "2026-03-02",
    serviceId: "serv-recape",
    serviceCode: "REC-01",
    serviceName: "Recapeamento CBUQ",
    priceVersionId: "preco-1",
    priceVersion: 3,
    quantity: 1500,
    unit: "m²",
    unitPrice: "42.50",
    currency: "BRL",
    revenue: "63750.00",
    coverageCode: "COMPLETE_ACCEPTED_EXACT",
    revenueEvidenceId: "ev-1",
    revenueEventId: "evt-1",
    eventCommitSequence: 10,
    acceptedAt: "2026-03-02T20:00:00Z",
    ...overrides,
  };
}

function snapshot(
  rows: ReturnType<typeof row>[],
  overrides: Record<string, unknown> = {},
) {
  const { response: responseOverrides, ...raiz } = overrides as {
    response?: Record<string, unknown>;
  };
  return {
    response: {
      from: "2026-03-02",
      to: "2026-03-06",
      totalRevenue: rows
        .reduce((total, item) => total + Number(item.revenue), 0)
        .toFixed(2),
      evidenceCount: rows.length,
      rows,
      nextCursor: null,
      coverage: "COMPLETE",
      highWaterMark: 10,
      ...responseOverrides,
    },
    mode: "ONLINE",
    fetchedAt: "2026-03-06T18:00:00.000Z",
    source: "SERVER_CONFIRMED",
    coverage: "COMPLETE",
    ...raiz,
  };
}

beforeEach(() => {
  resolveFinanceCapabilities.mockReset().mockResolvedValue({
    obraId: "obra-1",
    permissoes: ["FINANCEIRO_VISUALIZAR"],
  });
  loadRevenueTraceSnapshot.mockReset().mockResolvedValue(snapshot([row()]));
});

afterEach(cleanup);

describe("TrechoReceitaSection", () => {
  it("consulta o dia atual quando o trecho está sem recorte", async () => {
    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: null, ate: null }}
        hoje="2026-03-09"
      />,
    );

    await waitFor(() =>
      expect(loadRevenueTraceSnapshot).toHaveBeenCalledWith({
        obraId: "obra-1",
        from: "2026-03-09",
        to: "2026-03-09",
      }),
    );
    expect(await screen.findByText(/hoje · 09\/03\/2026/)).toBeInTheDocument();
  });

  it("segue o período aplicado no esquemático", async () => {
    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: "2026-03-02", ate: "2026-03-06" }}
        hoje="2026-03-09"
      />,
    );

    await waitFor(() =>
      expect(loadRevenueTraceSnapshot).toHaveBeenCalledWith({
        obraId: "obra-1",
        from: "2026-03-02",
        to: "2026-03-06",
      }),
    );
    expect(
      await screen.findByText(/02\/03\/2026 – 06\/03\/2026/),
    ).toBeInTheDocument();
  });

  it("fecha um extremo solto no próprio dia", async () => {
    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: "2026-03-04", ate: null }}
        hoje="2026-03-09"
      />,
    );

    await waitFor(() =>
      expect(loadRevenueTraceSnapshot).toHaveBeenCalledWith({
        obraId: "obra-1",
        from: "2026-03-04",
        to: "2026-03-04",
      }),
    );
  });

  it("agrega a receita por atividade com o total do período", async () => {
    loadRevenueTraceSnapshot.mockResolvedValue(
      snapshot([
        row(),
        row({ rdoId: "rdo-2", quantity: 500, revenue: "21250.00" }),
        row({
          serviceId: "serv-fresagem",
          serviceName: "Fresagem descontínua",
          quantity: 800,
          revenue: "9600.00",
        }),
      ]),
    );

    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: "2026-03-02", ate: "2026-03-06" }}
      />,
    );

    expect(await screen.findByText("Recapeamento CBUQ")).toBeInTheDocument();
    // 1500 + 500 m² somados na mesma atividade, vindos de 2 RDOs.
    expect(screen.getByText(/2\.000\s*m² · 2 RDO\(s\)/)).toBeInTheDocument();
    expect(screen.getByText("Fresagem descontínua")).toBeInTheDocument();
    expect(screen.getByText(/3 evidência\(s\) aceita\(s\)/)).toBeInTheDocument();
    // Total: 63.750 + 21.250 + 9.600 = 94.600.
    expect(screen.getByText(/R\$\s*94\.600,00/)).toBeInTheDocument();
  });

  it("nunca soma moedas diferentes no mesmo total", async () => {
    loadRevenueTraceSnapshot.mockResolvedValue(
      snapshot([
        row(),
        row({
          serviceId: "serv-usd",
          serviceName: "Serviço em dólar",
          currency: "USD",
          revenue: "1000.00",
        }),
      ]),
    );

    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: "2026-03-02", ate: "2026-03-06" }}
      />,
    );

    // O valor em real aparece no total e na linha da atividade; o que não
    // pode existir é um total misturando as duas moedas.
    expect(await screen.findAllByText(/R\$\s*63\.750,00/)).not.toHaveLength(0);
    expect(screen.getAllByText(/US\$\s*1\.000,00/)).not.toHaveLength(0);
    expect(screen.queryByText(/R\$\s*64\.750/)).not.toBeInTheDocument();
  });

  it("bloqueia a consulta de valores para quem não tem acesso financeiro", async () => {
    resolveFinanceCapabilities.mockResolvedValue({
      obraId: "obra-1",
      permissoes: [],
    });

    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: null, ate: null }}
      />,
    );

    expect(
      await screen.findByText(/visíveis para quem tem acesso financeiro/),
    ).toBeInTheDocument();
    expect(loadRevenueTraceSnapshot).not.toHaveBeenCalled();
  });

  it("declara o estado vazio de hoje sem inventar zero monetário", async () => {
    loadRevenueTraceSnapshot.mockResolvedValue(snapshot([]));

    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: null, ate: null }}
        hoje="2026-03-09"
      />,
    );

    expect(
      await screen.findByText(/Nenhuma receita evidenciada hoje/),
    ).toBeInTheDocument();
    expect(screen.queryByText("Total do período")).not.toBeInTheDocument();
  });

  it("rotula a leitura offline com a data de confirmação", async () => {
    loadRevenueTraceSnapshot.mockResolvedValue(
      snapshot([row()], { mode: "OFFLINE_CACHE" }),
    );

    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: "2026-03-02", ate: "2026-03-06" }}
      />,
    );

    expect(
      await screen.findByText(/Dados do dispositivo, confirmados pelo servidor/),
    ).toBeInTheDocument();
  });

  it("avisa quando a cobertura das evidências é parcial", async () => {
    loadRevenueTraceSnapshot.mockResolvedValue(
      snapshot([row()], {
        response: { coverage: "PARTIAL" },
      }),
    );

    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: "2026-03-02", ate: "2026-03-06" }}
      />,
    );

    expect(
      await screen.findByText(/Cobertura parcial/),
    ).toBeInTheDocument();
  });

  it("apresenta a falha como falha, sem fingir receita zero", async () => {
    loadRevenueTraceSnapshot.mockRejectedValue(
      new Error("Rastreio de receita indisponível sem rede."),
    );

    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: null, ate: null }}
      />,
    );

    expect(
      await screen.findByText(/Rastreio de receita indisponível sem rede\./),
    ).toBeInTheDocument();
    expect(screen.queryByText("Total do período")).not.toBeInTheDocument();
  });

  it("relê a receita a cada rodada de sincronização", async () => {
    render(
      <TrechoReceitaSection
        obraId="obra-1"
        periodo={{ de: null, ate: null }}
      />,
    );
    await waitFor(() =>
      expect(loadRevenueTraceSnapshot).toHaveBeenCalledTimes(1),
    );

    window.dispatchEvent(new Event("cortex:sync-completed"));

    await waitFor(() =>
      expect(loadRevenueTraceSnapshot).toHaveBeenCalledTimes(2),
    );
  });
});
