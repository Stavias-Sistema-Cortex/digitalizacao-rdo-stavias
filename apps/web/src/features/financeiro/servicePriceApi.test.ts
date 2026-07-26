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

const REVENUE_ROW = {
  worksiteId: "00000000-0000-4000-8000-000000000101",
  worksiteName: "Obra",
  rdoId: "00000000-0000-4000-8000-000000000201",
  rdoNumber: "RDO-1",
  executionId: "00000000-0000-4000-8000-000000000301",
  executionDate: "2026-07-01",
  serviceId: "00000000-0000-4000-8000-000000000401",
  serviceCode: "SERV",
  serviceName: "Serviço",
  priceVersionId: "00000000-0000-4000-8000-000000000501",
  priceVersion: 1,
  quantity: "1.000",
  unit: "UN",
  unitPrice: "1.0000",
  currency: "BRL",
  revenue: "1.00",
  coverageCode: "ACCEPTED_EXACT",
  revenueEvidenceId: "00000000-0000-4000-8000-000000000601",
  revenueEventId: "00000000-0000-4000-8000-000000000701",
  eventCommitSequence: 1,
  acceptedAt: "2026-07-01T12:00:00Z",
};

describe("servicePriceApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue({ ok: true, status: 200 } as Response);
    mocks.readResponseBody.mockResolvedValue({
      from: "2026-07-01",
      to: "2026-07-31",
      totalRevenue: "0.00",
      evidenceCount: 0,
      rows: [],
      nextCursor: null,
      coverage: "COMPLETE",
      highWaterMark: 0,
    });
  });

  it("usa escopo explícito da obra e datas no rastreio", async () => {
    await fetchRevenueTrace("obra 1", "2026-07-01", "2026-07-31");

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/financeiro/rastreio-receita?obraId=obra+1&de=2026-07-01&ate=2026-07-31&limit=500",
    );
  });

  it("carrega mais de uma página do mesmo snapshot sem truncar o rastreio", async () => {
    const secondRow = {
      ...REVENUE_ROW,
      executionId: "00000000-0000-4000-8000-000000000302",
      quantity: "2.000",
      revenue: "2.00",
      revenueEvidenceId: "00000000-0000-4000-8000-000000000602",
      revenueEventId: "00000000-0000-4000-8000-000000000702",
      eventCommitSequence: 2,
    };
    mocks.readResponseBody
      .mockResolvedValueOnce({
        from: "2026-07-01",
        to: "2026-07-31",
        totalRevenue: "1.00",
        evidenceCount: 1,
        rows: [REVENUE_ROW],
        nextCursor: "cursor-2",
        coverage: "PARTIAL",
        highWaterMark: 7,
      })
      .mockResolvedValueOnce({
        from: "2026-07-01",
        to: "2026-07-31",
        totalRevenue: "2.00",
        evidenceCount: 1,
        rows: [secondRow],
        nextCursor: null,
        coverage: "COMPLETE",
        highWaterMark: 7,
      });

    const result = await fetchRevenueTrace(
      REVENUE_ROW.worksiteId,
      "2026-07-01",
      "2026-07-31",
    );

    expect(result.rows.map((row) => row.executionId)).toEqual([
      REVENUE_ROW.executionId,
      secondRow.executionId,
    ]);
    expect(result.totalRevenue).toBe("3.00");
    expect(result.evidenceCount).toBe(2);
    expect(result.coverage).toBe("COMPLETE");
    expect(result.highWaterMark).toBe(7);
    expect(mocks.apiFetch.mock.calls[1][0]).toContain("cursor=cursor-2");
  });

  it("busca catálogo e evidência por IDs codificados", async () => {
    mocks.readResponseBody
      .mockResolvedValueOnce({ items: [], nextCursor: null })
      .mockResolvedValueOnce({
        row: REVENUE_ROW,
        ontologyLinks: [],
      });
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

  it("rejeita total monetário malformado em vez de convertê-lo para zero", async () => {
    mocks.readResponseBody.mockResolvedValue({
      from: "2026-07-01",
      to: "2026-07-31",
      totalRevenue: "não-é-dinheiro",
      evidenceCount: 0,
      rows: [],
    });

    await expect(
      fetchRevenueTrace(
        "00000000-0000-4000-8000-000000000001",
        "2026-07-01",
        "2026-07-31",
      ),
    ).rejects.toThrow(/totalRevenue/i);
  });

  it("rejeita números monetários inseguros que já perderam precisão no JSON", async () => {
    mocks.readResponseBody.mockResolvedValue({
      from: "2026-07-01",
      to: "2026-07-31",
      totalRevenue: 9007199254740994,
      evidenceCount: 0,
      rows: [],
    });

    await expect(
      fetchRevenueTrace(
        "00000000-0000-4000-8000-000000000001",
        "2026-07-01",
        "2026-07-31",
      ),
    ).rejects.toThrow(/totalRevenue/i);
  });

  it("rejeita data de execução nula em vez de deixar a renderização quebrar", async () => {
    mocks.readResponseBody.mockResolvedValue({
      from: "2026-07-01",
      to: "2026-07-31",
      totalRevenue: "1.00",
      evidenceCount: 1,
      rows: [{ ...REVENUE_ROW, executionDate: null }],
    });

    await expect(
      fetchRevenueTrace(
        REVENUE_ROW.worksiteId,
        "2026-07-01",
        "2026-07-31",
      ),
    ).rejects.toThrow(/executionDate/i);
  });

  it("rejeita total autoritativo incompatível com as linhas completas", async () => {
    mocks.readResponseBody.mockResolvedValue({
      from: "2026-07-01",
      to: "2026-07-31",
      totalRevenue: "2.00",
      evidenceCount: 1,
      rows: [REVENUE_ROW],
    });

    await expect(
      fetchRevenueTrace(
        REVENUE_ROW.worksiteId,
        "2026-07-01",
        "2026-07-31",
      ),
    ).rejects.toThrow(/totalRevenue.*linhas/i);
  });
});
