import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  cpfPodeEstarNoFiltro: vi.fn(),
  fetchCpfFilter: vi.fn(),
  getCachedFilter: vi.fn(),
  loginOnline: vi.fn(),
  setCachedFilter: vi.fn(),
  setSession: vi.fn(),
}));

vi.mock("./authApi", () => ({
  fetchCpfFilter: mocks.fetchCpfFilter,
  loginOnline: mocks.loginOnline,
}));

vi.mock("./cpfFilter", () => ({
  cpfPodeEstarNoFiltro: mocks.cpfPodeEstarNoFiltro,
  getCachedFilter: mocks.getCachedFilter,
  setCachedFilter: mocks.setCachedFilter,
}));

vi.mock("./authSession", () => ({
  setSession: mocks.setSession,
}));

import { autenticar, sincronizarFiltroOffline } from "./authService";

const positiveCachedFilter = {
  algoritmo: "sha256-double/v1",
  m: 8,
  k: 1,
  tamanho: 1,
  bits: "/w==",
  geradoEm: "2026-07-13T00:00:00Z",
};

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
    mocks.getCachedFilter.mockReturnValue(positiveCachedFilter);
    mocks.cpfPodeEstarNoFiltro.mockResolvedValue(true);

    await expect(autenticar("111.444.777-35")).resolves.toEqual({
      ok: false,
      message:
        "Login por CPF desativado. Use a verificação por e-mail.",
    });
    expect(mocks.getCachedFilter).not.toHaveBeenCalled();
    expect(mocks.cpfPodeEstarNoFiltro).not.toHaveBeenCalled();
    expect(mocks.setSession).not.toHaveBeenCalled();
  });

  it("falha sem criar sessão quando a rede está indisponível", async () => {
    mocks.loginOnline.mockRejectedValue(new TypeError("network down"));
    mocks.getCachedFilter.mockReturnValue(positiveCachedFilter);
    mocks.cpfPodeEstarNoFiltro.mockResolvedValue(true);

    await expect(autenticar("111.444.777-35")).resolves.toEqual({
      ok: false,
      message:
        "Não foi possível autenticar agora. Verifique a conexão e tente novamente.",
    });
    expect(mocks.getCachedFilter).not.toHaveBeenCalled();
    expect(mocks.cpfPodeEstarNoFiltro).not.toHaveBeenCalled();
    expect(mocks.setSession).not.toHaveBeenCalled();
  });

  it("falha em 5xx mesmo com um Bloom cache positivo", async () => {
    mocks.loginOnline.mockResolvedValue({
      ok: false,
      status: 503,
      message: "Serviço temporariamente indisponível.",
    });
    mocks.getCachedFilter.mockReturnValue(positiveCachedFilter);
    mocks.cpfPodeEstarNoFiltro.mockResolvedValue(true);

    await expect(autenticar("111.444.777-35")).resolves.toEqual({
      ok: false,
      message: "Serviço temporariamente indisponível.",
    });
    expect(mocks.getCachedFilter).not.toHaveBeenCalled();
    expect(mocks.cpfPodeEstarNoFiltro).not.toHaveBeenCalled();
    expect(mocks.setSession).not.toHaveBeenCalled();
  });

  it("mantém o sincronizador legado como no-op seguro", async () => {
    await sincronizarFiltroOffline();

    expect(mocks.fetchCpfFilter).not.toHaveBeenCalled();
    expect(mocks.setCachedFilter).not.toHaveBeenCalled();
  });
});
