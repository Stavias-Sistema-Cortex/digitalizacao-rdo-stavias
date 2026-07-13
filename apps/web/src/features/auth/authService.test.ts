import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  cpfPodeEstarNoFiltro: vi.fn(),
  getCachedFilter: vi.fn(),
  loginOnline: vi.fn(),
  setSession: vi.fn(),
}));

vi.mock("./authApi", () => ({
  fetchCpfFilter: vi.fn(),
  loginOnline: mocks.loginOnline,
}));

vi.mock("./cpfFilter", () => ({
  cpfPodeEstarNoFiltro: mocks.cpfPodeEstarNoFiltro,
  getCachedFilter: mocks.getCachedFilter,
  setCachedFilter: vi.fn(),
}));

vi.mock("./authSession", () => ({
  setSession: mocks.setSession,
}));

import { autenticar } from "./authService";

describe("autenticar", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("exibe o encerramento do login legado sem cair no Bloom cache", async () => {
    mocks.loginOnline.mockResolvedValue({
      ok: false,
      status: 410,
      message:
        "Login por CPF desativado. Use a verificação por e-mail.",
    });
    mocks.getCachedFilter.mockReturnValue({
      algoritmo: "sha256-double/v1",
      m: 8,
      k: 1,
      tamanho: 1,
      bits: "/w==",
      geradoEm: "2026-07-13T00:00:00Z",
    });
    mocks.cpfPodeEstarNoFiltro.mockResolvedValue(true);

    await expect(autenticar("111.444.777-35")).resolves.toEqual({
      ok: false,
      message:
        "Login por CPF desativado. Use a verificação por e-mail.",
    });
    expect(mocks.cpfPodeEstarNoFiltro).not.toHaveBeenCalled();
    expect(mocks.setSession).not.toHaveBeenCalled();
  });
});
