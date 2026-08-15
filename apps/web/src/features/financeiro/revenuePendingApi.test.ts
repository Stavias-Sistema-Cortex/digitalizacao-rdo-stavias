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
  decidePendingRevenueExecution,
  fetchPendingRevenueExecutions,
} from "./revenuePendingApi";

const OBRA_ID = "00000000-0000-4000-8000-000000000101";
const RDO_ID = "00000000-0000-4000-8000-000000000102";
const EXECUTION_ID = "00000000-0000-4000-8000-000000000103";
const SERVICE_ID = "00000000-0000-4000-8000-000000000104";
const MUTATION_ID = "00000000-0000-4000-8000-000000000105";

const PENDING_ROW = {
  worksiteId: OBRA_ID,
  worksiteName: "Obra real",
  rdoId: RDO_ID,
  rdoNumber: "RDO-0030",
  executionId: EXECUTION_ID,
  serviceId: SERVICE_ID,
  serviceCode: "FRES.001",
  serviceName: "Fresagem funcional",
  executionDate: "2026-08-14",
  quantity: "2.500",
  unit: "M2",
  rdoEntityVersion: 19,
  approvalState: "REGISTERED",
  legacyEvidenceState: "NONE",
  priceState: "EXACT_ACTIVE",
  priceReason: "EXACT_ACTIVE_PRICE",
  currentUnitPrice: "500.0000",
  currency: "BRL",
  evidenceUnitPrice: null,
  evidenceCurrency: null,
};

describe("revenuePendingApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue({ ok: true, status: 200 } as Response);
    mocks.readResponseBody.mockResolvedValue({ rows: [PENDING_ROW] });
  });

  it("lê apenas a fila server-side da obra solicitada", async () => {
    await expect(fetchPendingRevenueExecutions(OBRA_ID)).resolves.toEqual([
      expect.objectContaining({
        executionId: EXECUTION_ID,
        approvalState: "REGISTERED",
        legacyEvidenceState: "NONE",
        priceState: "EXACT_ACTIVE",
        currentUnitPrice: "500.0000",
      }),
    ]);
    expect(mocks.apiFetch).toHaveBeenCalledWith(
      `/financeiro/rastreio-receita/pendentes?obraId=${OBRA_ID}`,
    );
  });

  it("recusa uma linha cujo preço não possa ser comprovado como exato", async () => {
    mocks.readResponseBody.mockResolvedValue({
      rows: [{
        ...PENDING_ROW,
        priceState: "EXACT_ACTIVE",
        priceReason: "EXACT_ACTIVE_PRICE",
        currentUnitPrice: null,
        currency: null,
      }],
    });

    await expect(fetchPendingRevenueExecutions(OBRA_ID)).rejects.toThrow(
      /preço pendente.*inválido/i,
    );
  });

  it("preserva a evidência legada verificável sem tratá-la como preço atual", async () => {
    mocks.readResponseBody.mockResolvedValue({
      rows: [{
        ...PENDING_ROW,
        approvalState: "LEGACY_UNVERIFIED",
        legacyEvidenceState: "VERIFIABLE",
        priceState: "UNAVAILABLE",
        priceReason: "EXACT_PRICE_NOT_FOUND",
        currentUnitPrice: null,
        currency: null,
        evidenceUnitPrice: "487.5000",
        evidenceCurrency: "BRL",
      }],
    });

    await expect(fetchPendingRevenueExecutions(OBRA_ID)).resolves.toEqual([
      expect.objectContaining({
        approvalState: "LEGACY_UNVERIFIED",
        legacyEvidenceState: "VERIFIABLE",
        evidenceUnitPrice: "487.5000",
        evidenceCurrency: "BRL",
        currentUnitPrice: null,
      }),
    ]);
  });

  it("recusa evidência legada declarada verificável sem seu preço persistido", async () => {
    mocks.readResponseBody.mockResolvedValue({
      rows: [{
        ...PENDING_ROW,
        approvalState: "LEGACY_UNVERIFIED",
        legacyEvidenceState: "VERIFIABLE",
        priceState: "UNAVAILABLE",
        priceReason: "EXACT_PRICE_NOT_FOUND",
        currentUnitPrice: null,
        currency: null,
        evidenceUnitPrice: null,
        evidenceCurrency: null,
      }],
    });

    await expect(fetchPendingRevenueExecutions(OBRA_ID)).rejects.toThrow(
      /evidência legada.*inválid/i,
    );
  });

  it("envia somente a decisão, a versão canônica e uma mutação idempotente", async () => {
    mocks.readResponseBody.mockResolvedValue({ id: RDO_ID });

    await decidePendingRevenueExecution({
      rdoId: RDO_ID,
      executionId: EXECUTION_ID,
      decision: "VALIDAR",
      justification: null,
      baseVersion: 19,
      clientMutationId: MUTATION_ID,
    });

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      `/rdos/${RDO_ID}/execucoes/${EXECUTION_ID}/decisoes`,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          "X-Correlation-Id": MUTATION_ID,
        },
        body: JSON.stringify({
          decisao: "VALIDAR",
          justificativa: null,
          baseVersao: 19,
          clientMutationId: MUTATION_ID,
        }),
      },
    );
  });
});
