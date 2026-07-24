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

import { fetchAuthorizedRevenueWorksites } from "./financeRevenueWorksiteApi";

describe("financeRevenueWorksiteApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue({
      ok: true,
      status: 200,
    } as Response);
    mocks.readResponseBody.mockResolvedValue([{
      id: "obra-1",
      codigoContrato: "CTR-1",
      nome: "Rodovia SP-123",
      status: "ATIVA",
    }]);
  });

  it("usa a relação operacional de obras sem consultar unidades de custo", async () => {
    const rows = await fetchAuthorizedRevenueWorksites();

    expect(mocks.apiFetch).toHaveBeenCalledWith("/obras/relacionadas");
    expect(rows).toEqual([{
      id: "obra-1",
      codigoContrato: "CTR-1",
      nome: "Rodovia SP-123",
      status: "ATIVA",
    }]);
  });

  it("falha fechado quando o servidor não devolve uma lista de obras", async () => {
    mocks.readResponseBody.mockResolvedValue({ items: [] });

    await expect(fetchAuthorizedRevenueWorksites()).rejects.toThrow(
      "resposta de obras inválida",
    );
  });
});
