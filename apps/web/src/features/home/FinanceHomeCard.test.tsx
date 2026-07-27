// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

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
});
