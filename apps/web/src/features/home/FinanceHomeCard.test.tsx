// @vitest-environment jsdom

import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
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
  vi.useRealTimers();
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

  it("recupera 502 transitório automaticamente sem mostrar HTML do proxy", async () => {
    vi.useFakeTimers();
    const proxyHtml = "<!DOCTYPE HTML><title>502 Proxy Error</title>Apache";
    fetchRevenueCapabilities
      .mockRejectedValueOnce(new Error(proxyHtml))
      .mockRejectedValueOnce(new Error(proxyHtml))
      .mockResolvedValueOnce({
        obraId: "obra-1",
        permissoes: ["FINANCEIRO_VISUALIZAR"],
      });

    render(
      <MemoryRouter>
        <FinanceHomeCard obraId="obra-1" />
      </MemoryRouter>,
    );

    await act(async () => {
      await Promise.resolve();
    });
    expect(document.body).not.toHaveTextContent(/doctype|proxy error|apache/i);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(250);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(500);
    });

    expect(screen.getByText("2 evidências aceitas")).toBeVisible();
    expect(fetchRevenueCapabilities).toHaveBeenCalledTimes(3);
    expect(document.body).not.toHaveTextContent(/doctype|proxy error|apache/i);
  });
});
