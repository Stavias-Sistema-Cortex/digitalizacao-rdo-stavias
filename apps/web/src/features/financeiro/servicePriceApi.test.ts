import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  readResponseBody: vi.fn(),
  responseErrorMessage: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", () => ({
  apiFetch: mocks.apiFetch,
  readResponseBody: mocks.readResponseBody,
  responseErrorMessage: mocks.responseErrorMessage,
}));

import {
  fetchRevenueTrace,
  fetchRevenueTraceEvidence,
  fetchCompleteServiceCatalog,
  fetchServiceCatalog,
} from "./servicePriceApi";

describe("servicePriceApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue({ ok: true, status: 200 } as Response);
    mocks.readResponseBody.mockResolvedValue({ items: [], rows: [] });
  });

  it("usa escopo explícito da obra e datas no rastreio", async () => {
    await fetchRevenueTrace("obra 1", "2026-07-01", "2026-07-31");

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/financeiro/rastreio-receita?obraId=obra+1&de=2026-07-01&ate=2026-07-31",
    );
  });

  it("busca catálogo e evidência por IDs codificados", async () => {
    await fetchServiceCatalog("obra/1", "pavimento");
    await fetchRevenueTraceEvidence("exec/1");

    expect(mocks.apiFetch.mock.calls[0][0]).toBe(
      "/obras/obra%2F1/financeiro/catalogo-servicos?query=pavimento&limit=100",
    );
    expect(mocks.apiFetch.mock.calls[1][0]).toBe(
      "/financeiro/rastreio-receita/exec%2F1",
    );
  });

  it("carrega todas as páginas do mesmo snapshot sem duplicar serviços", async () => {
    mocks.readResponseBody
      .mockResolvedValueOnce({
        items: [{ service: { id: "service-1" }, priceVersions: [] }],
        nextCursor: "cursor-2",
        authorizedItemCount: 2,
        authorizedPriceVersionCount: 0,
        authorizedCancellationCount: 0,
        returnedItemCount: 1,
        returnedPriceVersionCount: 0,
        returnedCancellationCount: 0,
        coverage: "PARTIAL",
        highWaterMark: 7,
      })
      .mockResolvedValueOnce({
        items: [{ service: { id: "service-2" }, priceVersions: [] }],
        nextCursor: null,
        authorizedItemCount: 2,
        authorizedPriceVersionCount: 0,
        authorizedCancellationCount: 0,
        returnedItemCount: 1,
        returnedPriceVersionCount: 0,
        returnedCancellationCount: 0,
        coverage: "COMPLETE",
        highWaterMark: 7,
      });

    const result = await fetchCompleteServiceCatalog("obra/1");

    expect(result.items.map((row) => row.service.id)).toEqual([
      "service-1",
      "service-2",
    ]);
    expect(result.coverage).toBe("COMPLETE");
    expect(result.returnedItemCount).toBe(2);
    expect(mocks.apiFetch.mock.calls[1][0]).toContain("cursor=cursor-2");
  });
});
