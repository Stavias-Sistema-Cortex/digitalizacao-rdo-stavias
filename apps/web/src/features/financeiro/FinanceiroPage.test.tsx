// @vitest-environment jsdom

import type { PropsWithChildren, ReactNode } from "react";
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const {
  buscarCobrancas,
  buscarCompras,
  buscarLancamentos,
  buscarNotasFiscais,
  buscarRelatorio,
  buscarVisaoGeral,
  fetchAuthorizedRevenueWorksites,
  listObrasLocais,
  pdorSections,
  revenueMounts,
  resolveFinanceCapabilities,
} = vi.hoisted(() => ({
  buscarCobrancas: vi.fn(),
  buscarCompras: vi.fn(),
  buscarLancamentos: vi.fn(),
  buscarNotasFiscais: vi.fn(),
  buscarRelatorio: vi.fn(),
  buscarVisaoGeral: vi.fn(),
  fetchAuthorizedRevenueWorksites: vi.fn(),
  listObrasLocais: vi.fn(),
  pdorSections: vi.fn(),
  revenueMounts: vi.fn(),
  resolveFinanceCapabilities: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({
    children,
    onRefresh,
  }: PropsWithChildren<{ onRefresh?: () => void }>) => (
    <>
      <button type="button" onClick={onRefresh}>
        Atualizar Financeiro
      </button>
      {children}
    </>
  ),
}));

vi.mock("../../components/workspace/OperationalWorkspace", () => ({
  OperationalWorkspace: ({
    title,
    description,
    actions,
    children,
  }: {
    title: string;
    description: string;
    actions?: ReactNode;
    children: ReactNode;
  }) => (
    <main>
      <h1>{title}</h1>
      <p>{description}</p>
      {actions}
      {children}
    </main>
  ),
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  filterOperationalObras: (
    obras: Array<{ arquivadoEm?: string | null }>,
  ) => obras.filter((obra) => obra.arquivadoEm == null),
  listObrasLocais,
}));

vi.mock("./financeiroApi", () => ({
  buscarCobrancas,
  buscarCompras,
  buscarLancamentos,
  buscarNotasFiscais,
  buscarRelatorio,
  buscarVisaoGeral,
}));

vi.mock("./financeRevenueWorksiteApi", () => ({
  fetchAuthorizedRevenueWorksites,
}));

vi.mock("./financeCapabilitiesResolver", () => ({
  resolveFinanceCapabilities,
}));

vi.mock("./FinanceRevenueTracePage", async () => {
  const { useEffect } = await import("react");
  return {
    FinanceRevenueTracePage: ({ obraId }: { obraId: string }) => {
      useEffect(() => {
        revenueMounts();
      }, []);
      return (
        <section data-testid="revenue-trace">
          Rastreio de receita ativo · {obraId || "consolidado autorizado"}
        </section>
      );
    },
  };
});

vi.mock("./ServicePriceCatalogPage", () => ({
  ServicePriceCatalogPage: () => <section>Catálogo de preços versionados</section>,
}));

vi.mock("../obras/obrasApi", () => ({
  buscarPdorAtual: vi.fn().mockResolvedValue(null),
}));

vi.mock("../obras/PdorPanel", () => ({
  PdorPanel: () => <section>PDOR de receita</section>,
}));

vi.mock("./FinancePdorSection", () => ({
  FinancePdorSection: (props: {
    obraId: string;
    refreshVersion?: number;
  }) => {
    pdorSections(props);
    return <section>PDOR offline scoped</section>;
  },
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

beforeEach(() => {
  Object.defineProperty(navigator, "onLine", {
    configurable: true,
    value: true,
  });
  fetchAuthorizedRevenueWorksites.mockResolvedValue([]);
  listObrasLocais.mockResolvedValue([]);
  resolveFinanceCapabilities.mockResolvedValue({
    obraId: "obra-1",
    permissoes: ["FINANCEIRO_VISUALIZAR"],
  });
});

function renderFinanceiro(route = "/financeiro") {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <FinanceiroPage />
    </MemoryRouter>,
  );
}

import { FinanceiroPage } from "./FinanceiroPage";

describe("FinanceiroPage: superfície de receita", () => {
  it("abre o rastreio consolidado e não expõe módulos de custo ou contabilidade", () => {
    renderFinanceiro();

    expect(screen.getByRole("heading", {
      level: 1,
      name: "Financeiro",
    })).toBeVisible();
    expect(screen.getByTestId("revenue-trace")).toHaveTextContent(
      "consolidado autorizado",
    );

    const navigation = screen.getByRole("navigation", {
      name: "Áreas do Financeiro",
    });
    expect(
      within(navigation).getByRole("button", { name: "Rastreio de receita" }),
    ).toHaveAttribute("aria-current", "page");
    expect(within(navigation).getAllByRole("button")).toHaveLength(3);
    expect(document.body).not.toHaveTextContent(
      /previsto|comprometido|liquidado|a pagar|compras|notas fiscais|pagamentos|rateios|centros de custo/i,
    );
  });

  it("trata seções contábeis antigas como rastreio de receita", () => {
    renderFinanceiro("/financeiro?secao=compras");

    expect(screen.getByTestId("revenue-trace")).toBeVisible();
    expect(
      screen.getByRole("button", { name: "Rastreio de receita" }),
    ).toHaveAttribute("aria-current", "page");
    expect(buscarCompras).not.toHaveBeenCalled();
    expect(buscarNotasFiscais).not.toHaveBeenCalled();
    expect(buscarLancamentos).not.toHaveBeenCalled();
    expect(buscarCobrancas).not.toHaveBeenCalled();
    expect(buscarRelatorio).not.toHaveBeenCalled();
    expect(buscarVisaoGeral).not.toHaveBeenCalled();
  });

  it("mantém preços versionados como suporte explícito à receita", async () => {
    const user = userEvent.setup();
    renderFinanceiro("/financeiro?obra=obra-1");

    await user.click(
      screen.getByRole("button", { name: "Serviços e preços" }),
    );

    expect(await screen.findByText(
      "Catálogo de preços versionados",
    )).toBeVisible();
    expect(screen.queryByTestId("revenue-trace")).not.toBeInTheDocument();
  });

  it("revalida o demonstrativo quando a atualização do header é acionada", async () => {
    const user = userEvent.setup();
    renderFinanceiro();
    await waitFor(() => expect(revenueMounts).toHaveBeenCalledTimes(1));

    await user.click(screen.getByRole("button", {
      name: "Atualizar Financeiro",
    }));

    await waitFor(() => expect(revenueMounts).toHaveBeenCalledTimes(2));
  });

  it("omite obras arquivadas do seletor no fallback local offline", async () => {
    Object.defineProperty(navigator, "onLine", {
      configurable: true,
      value: false,
    });
    listObrasLocais.mockResolvedValue([
      {
        id: "obra-ativa",
        codigoContrato: "CTR-ATIVA",
        nome: "Obra ativa",
        cliente: null,
        cidade: null,
        uf: null,
        rodovia: null,
        status: "ATIVA",
        observacoes: null,
        latitude: null,
        longitude: null,
        valorContratual: null,
        arquivadoEm: null,
        updatedAt: "2026-07-28T12:00:00.000Z",
      },
      {
        id: "obra-arquivada",
        codigoContrato: "CTR-ARQ",
        nome: "Obra arquivada",
        cliente: null,
        cidade: null,
        uf: null,
        rodovia: null,
        status: "INATIVA",
        observacoes: null,
        latitude: null,
        longitude: null,
        valorContratual: null,
        arquivadoEm: "2026-07-28T13:00:00.000Z",
        updatedAt: "2026-07-28T13:00:00.000Z",
      },
    ]);

    renderFinanceiro();

    const selector = await screen.findByRole("combobox", {
      name: "Obra em análise",
    });
    expect(selector).toHaveTextContent("Obra ativa");
    expect(selector).not.toHaveTextContent("Obra arquivada");
  });

  it("reconciles the online selector with a pending local archive tombstone", async () => {
    fetchAuthorizedRevenueWorksites.mockResolvedValue([
      {
        id: "obra-ativa",
        codigoContrato: "CTR-ATIVA",
        nome: "Obra ativa",
        status: "ATIVA",
      },
      {
        id: "obra-arquivada",
        codigoContrato: "CTR-ARQ",
        nome: "Obra ainda ativa no servidor",
        status: "ATIVA",
      },
    ]);
    listObrasLocais.mockResolvedValue([
      {
        id: "obra-arquivada",
        codigoContrato: "CTR-ARQ",
        nome: "Obra arquivada localmente",
        cliente: null,
        cidade: null,
        uf: null,
        rodovia: null,
        status: "ATIVA",
        observacoes: null,
        latitude: null,
        longitude: null,
        valorContratual: null,
        arquivadoEm: "2026-07-28T13:00:00.000Z",
        syncStatus: "PENDING_SYNC",
        updatedAt: "2026-07-28T13:00:00.000Z",
      },
    ]);

    renderFinanceiro();

    const selector = await screen.findByRole("combobox", {
      name: "Obra em análise",
    });
    expect(selector).toHaveTextContent("Obra ativa");
    expect(selector).not.toHaveTextContent(
      "Obra ainda ativa no servidor",
    );
    expect(listObrasLocais).toHaveBeenCalledWith({
      includeArchived: true,
    });
  });

  it("lets a current remote restore replace a stale synchronized archive tombstone", async () => {
    fetchAuthorizedRevenueWorksites.mockResolvedValue([
      {
        id: "obra-restaurada",
        codigoContrato: "CTR-REST",
        nome: "Obra restaurada no servidor",
        status: "ATIVA",
      },
    ]);
    listObrasLocais.mockResolvedValue([
      {
        id: "obra-restaurada",
        codigoContrato: "CTR-REST",
        nome: "Tombstone sincronizado antigo",
        cliente: null,
        cidade: null,
        uf: null,
        rodovia: null,
        status: "INATIVA",
        observacoes: null,
        latitude: null,
        longitude: null,
        valorContratual: null,
        arquivadoEm: "2026-07-28T13:00:00.000Z",
        syncStatus: "SYNCED",
        updatedAt: "2026-07-28T13:00:00.000Z",
      },
    ]);

    renderFinanceiro();

    const selector = await screen.findByRole("combobox", {
      name: "Obra em análise",
    });
    expect(selector).toHaveTextContent("Obra restaurada no servidor");
  });

  it("consulta o PDOR somente por obra, sem fabricar uma janela a partir dos filtros da receita", async () => {
    const user = userEvent.setup();
    renderFinanceiro(
      "/financeiro?obra=obra-1&de=2026-07-01&ate=2026-07-31",
    );

    fireEvent.change(await screen.findByLabelText("De"), {
      target: { value: "2026-06-01" },
    });
    fireEvent.change(screen.getByLabelText("Até"), {
      target: { value: "2026-06-30" },
    });
    await user.click(screen.getByRole("button", { name: "PDOR" }));

    expect(await screen.findByText("PDOR offline scoped")).toBeVisible();
    expect(pdorSections).toHaveBeenLastCalledWith({
      obraId: "obra-1",
      refreshVersion: 0,
    });

    await user.click(screen.getByRole("button", {
      name: "Atualizar Financeiro",
    }));
    await waitFor(() => {
      expect(pdorSections).toHaveBeenLastCalledWith({
        obraId: "obra-1",
        refreshVersion: 1,
      });
    });
  });
});
