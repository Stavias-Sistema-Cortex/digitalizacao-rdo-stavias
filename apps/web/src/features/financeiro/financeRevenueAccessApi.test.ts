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

import { fetchRevenueCapabilities } from "./financeRevenueAccessApi";

describe("financeRevenueAccessApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue({
      ok: true,
      status: 200,
    } as Response);
    mocks.readResponseBody.mockResolvedValue({
      obraId: "obra-1",
      permissoes: ["FINANCEIRO_VISUALIZAR"],
    });
  });

  it("consulta somente a capacidade da obra usada pela receita", async () => {
    await expect(fetchRevenueCapabilities("obra/1")).resolves.toMatchObject({
      obraId: "obra-1",
      permissoes: ["FINANCEIRO_VISUALIZAR"],
    });
    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/financeiro/capacidades?obraId=obra%2F1",
    );
  });

  it("rejeita permissões desconhecidas em vez de ampliar a superfície ativa", async () => {
    mocks.readResponseBody.mockResolvedValue({
      obraId: "obra-1",
      permissoes: ["FINANCEIRO_VISUALIZAR", "FINANCEIRO_ROOT"],
    });

    await expect(fetchRevenueCapabilities("obra-1")).rejects.toThrow(
      "capacidades financeiras inválidas",
    );
  });
});
