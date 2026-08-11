import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn() }));

vi.mock("../../lib/api/apiClient", async (importActual) => ({
  ...await importActual<typeof import("../../lib/api/apiClient")>(),
  apiFetch: mocks.apiFetch,
}));

import {
  buscarHistoricoPrevisao,
  buscarObrasArquivadas,
} from "./homeApi";

describe("Home worksite API", () => {
  beforeEach(() => {
    mocks.apiFetch.mockReset();
  });

  it("requests the Alfa archived-worksite endpoint exactly", async () => {
    mocks.apiFetch.mockResolvedValue(
      new Response(JSON.stringify([{
        id: "obra-1",
        nome: "Obra arquivada",
        arquivadoEm: "2026-07-28T13:00:00.000Z",
      }]), { status: 200 }),
    );

    await expect(buscarObrasArquivadas()).resolves.toEqual([
      expect.objectContaining({ id: "obra-1" }),
    ]);
    expect(mocks.apiFetch).toHaveBeenCalledWith("/obras/arquivadas");
  });

  it("abre o envelope paginado do histórico de previsão", async () => {
    mocks.apiFetch.mockResolvedValue(
      new Response(JSON.stringify({
        items: [
          { id: "snap-1", obra: { id: "obra-1" } },
          { id: "snap-2", obra: { id: "obra-1" } },
        ],
        page: 0,
        size: 100,
        totalElements: 2,
      }), { status: 200 }),
    );

    await expect(
      buscarHistoricoPrevisao("obra-1"),
    ).resolves.toEqual([
      expect.objectContaining({ id: "snap-1" }),
      expect.objectContaining({ id: "snap-2" }),
    ]);
  });

  it("devolve lista vazia quando o envelope não traz itens", async () => {
    mocks.apiFetch.mockResolvedValue(
      new Response(JSON.stringify({ page: 0, size: 100 }), {
        status: 200,
      }),
    );

    await expect(
      buscarHistoricoPrevisao("obra-1"),
    ).resolves.toEqual([]);
  });
});
