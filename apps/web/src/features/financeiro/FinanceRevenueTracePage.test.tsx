// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AUTH_SESSION_CHANGED_EVENT } from "../auth/authSession";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import { FinanceRevenueTracePage } from "./FinanceRevenueTracePage";
import type { RevenueTraceRow } from "./servicePriceApi";

const loadRevenueTraceSnapshot = vi.hoisted(() => vi.fn());

vi.mock("./revenueTraceCacheRepository", async (importOriginal) => {
  const actual = await importOriginal<
    typeof import("./revenueTraceCacheRepository")
  >();
  return { ...actual, loadRevenueTraceSnapshot };
});

afterEach(() => {
  cleanup();
  loadRevenueTraceSnapshot.mockReset();
});

const ROW: RevenueTraceRow = {
  worksiteId: "obra-1",
  worksiteName: "BR-101",
  rdoId: "rdo-1",
  rdoNumber: "RDO-014",
  executionId: "execution-1",
  executionDate: "2026-07-22",
  serviceId: "service-1",
  serviceCode: "PAV.CBUQ",
  serviceName: "Aplicação de CBUQ",
  priceVersionId: "price-1",
  priceVersion: 3,
  quantity: "10.000",
  unit: "T",
  unitPrice: "125.0000",
  currency: "BRL",
  revenue: "1250.00",
  coverageCode: "ACCEPTED_EXACT",
  revenueEvidenceId: "evidence-1",
  revenueEventId: "event-1",
  eventCommitSequence: 812,
  acceptedAt: "2026-07-22T15:00:00Z",
};

function cachedSnapshot(totalRevenue: string) {
  return {
    response: {
      from: "2026-07-22",
      to: "2026-07-22",
      totalRevenue,
      evidenceCount: 0,
      rows: [],
      nextCursor: null,
      coverage: "COMPLETE",
      highWaterMark: 812,
    },
    mode: "OFFLINE_CACHE" as const,
    fetchedAt: "2026-07-23T15:00:00.000Z",
    source: "SERVER_CONFIRMED" as const,
    coverage: {
      status: "COMPLETE_ACCEPTED_EXACT" as const,
      from: "2026-07-22",
      to: "2026-07-22",
      evidenceCount: 0,
    },
  };
}

describe("FinanceRevenueTracePage", () => {
  it("mostra quantidade vezes o preço congelado e soma apenas evidências visíveis", () => {
    const html = renderToStaticMarkup(
      <FinanceRevenueTracePage obraId="obra-1" rows={[ROW]} />,
    );

    expect(html).toContain("10,000 × R$ 125,0000");
    expect(html).toContain("R$ 1.250,00");
    expect(html).toContain("RDO-014");
    expect(html).toContain("22/07/2026");
    expect(html).not.toContain("23/07/2026");
    expect(html).toContain("ACEITA EXATA");
    expect(html).not.toMatch(/margem|custo previsto|receita estimada/i);
  });

  it("exibe o total autoritativo do backend sem perder centavos acima de Number.MAX_SAFE_INTEGER", () => {
    const html = renderToStaticMarkup(
      <FinanceRevenueTracePage
        obraId="obra-1"
        rows={[ROW]}
        totalRevenue="9007199254740993.99"
      />,
    );

    expect(html).toContain(
      "<span>Total confirmado pelo servidor</span><strong>R$ 9.007.199.254.740.993,99</strong>",
    );
  });

  it("shows the cached snapshot instant in Brasília", async () => {
    loadRevenueTraceSnapshot.mockResolvedValue({
      response: {
        from: "2026-07-22",
        to: "2026-07-22",
        totalRevenue: "1250.00",
        evidenceCount: 1,
        rows: [ROW],
        nextCursor: null,
        coverage: "COMPLETE",
        highWaterMark: 812,
      },
      mode: "OFFLINE_CACHE",
      fetchedAt: "2026-07-23T15:00:00.000Z",
      source: "SERVER_CONFIRMED",
      coverage: {
        status: "COMPLETE_ACCEPTED_EXACT",
        from: "2026-07-22",
        to: "2026-07-22",
        evidenceCount: 1,
      },
    });

    render(<FinanceRevenueTracePage obraId="obra-1" />);

    expect(await screen.findByText(/Atualizado em/)).toHaveTextContent(
      "Atualizado em 23/07/2026, 12:00",
    );
  });

  it("recarrega a receita visível após sync, conectividade e troca de sessão", async () => {
    loadRevenueTraceSnapshot
      .mockResolvedValueOnce(cachedSnapshot("100.00"))
      .mockResolvedValueOnce(cachedSnapshot("200.00"))
      .mockResolvedValueOnce(cachedSnapshot("300.00"))
      .mockResolvedValueOnce(cachedSnapshot("400.00"))
      .mockResolvedValueOnce(cachedSnapshot("500.00"));

    render(<FinanceRevenueTracePage obraId="obra-1" />);

    expect(await screen.findByText("R$ 100,00")).toBeVisible();

    for (const [eventName, expectedTotal] of [
      [SYNC_COMPLETED_EVENT, "R$ 200,00"],
      ["online", "R$ 300,00"],
      ["offline", "R$ 400,00"],
      [AUTH_SESSION_CHANGED_EVENT, "R$ 500,00"],
    ] as const) {
      window.dispatchEvent(new Event(eventName));
      expect(await screen.findByText(expectedTotal)).toBeVisible();
    }

    await waitFor(() => {
      expect(loadRevenueTraceSnapshot).toHaveBeenCalledTimes(5);
    });
  });
});
