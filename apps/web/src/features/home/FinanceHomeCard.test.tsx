// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
const {
  fetchRevenueCapabilities,
  fetchRevenueTrace,
} = vi.hoisted(() => ({
  fetchRevenueCapabilities: vi.fn(),
  fetchRevenueTrace: vi.fn(),
}));

vi.mock("../financeiro/financeRevenueAccessApi", () => ({
  fetchRevenueCapabilities,
}));

vi.mock("../financeiro/servicePriceApi", () => ({
  fetchRevenueTrace,
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

beforeEach(() => {
  vi.stubGlobal("navigator", { onLine: true });
  fetchRevenueCapabilities.mockResolvedValue({
    obraId: "obra-1",
    permissoes: ["FINANCEIRO_VISUALIZAR"],
  });
  fetchRevenueTrace.mockResolvedValue({
    from: null,
    to: null,
    totalRevenue: "1500.00",
    evidenceCount: 2,
    rows: [],
  });
});

import { FinanceHomeCard } from "./FinanceHomeCard";

describe("FinanceHomeCard: receita comprovada", () => {
  it("resume evidências aceitas sem exibir saldos ou custos contábeis", async () => {
    render(
      <MemoryRouter>
        <FinanceHomeCard obraId="obra-1" />
      </MemoryRouter>,
    );

    expect(await screen.findByText("2 evidências aceitas")).toBeVisible();
    expect(document.body).not.toHaveTextContent(
      /em aberto|vencido|a receber|a pagar|previsto|comprometido|liquidado/i,
    );
    expect(screen.getByRole("link", {
      name: "Abrir rastreio de receita",
    })).toHaveAttribute(
      "href",
      "/financeiro?obra=obra-1&secao=receita",
    );
  });

  it("atualiza a contagem mostrada após a sincronização", async () => {
    fetchRevenueTrace
      .mockResolvedValueOnce({
        from: null,
        to: null,
        totalRevenue: "0.00",
        evidenceCount: 0,
        rows: [],
      })
      .mockResolvedValueOnce({
        from: null,
        to: null,
        totalRevenue: "1250.00",
        evidenceCount: 1,
        rows: [],
      });

    render(
      <MemoryRouter>
        <FinanceHomeCard obraId="obra-1" />
      </MemoryRouter>,
    );

    expect(
      await screen.findByText("Nenhuma evidência de receita aceita nesta obra."),
    ).toBeVisible();

    window.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));

    expect(await screen.findByText("1 evidência aceita")).toBeVisible();
    await waitFor(() => {
      expect(fetchRevenueTrace).toHaveBeenCalledTimes(2);
    });
  });
});
