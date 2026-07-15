import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  clearSession: vi.fn(),
  fetchSession: vi.fn(),
  loginWithCpf: vi.fn(),
  logoutOnline: vi.fn(),
  purgeLegacyAuthStorage: vi.fn(),
  setSession: vi.fn(),
}));

vi.mock("./authApi", () => ({
  fetchSession: mocks.fetchSession,
  loginWithCpf: mocks.loginWithCpf,
  logoutOnline: mocks.logoutOnline,
}));

vi.mock("./authSession", () => ({
  clearSession: mocks.clearSession,
  purgeLegacyAuthStorage: mocks.purgeLegacyAuthStorage,
  setSession: mocks.setSession,
}));

import {
  autenticarPorCpf,
  encerrarSessao,
  initializeAuthSession,
} from "./authService";

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Colaborador Sintético",
  papelAcesso: "BETA" as const,
  escopoGlobal: false,
  obraIds: ["00000000-0000-4000-8000-000000000002"],
  expiraEm: "2099-07-14T12:00:00Z",
};

describe("authService", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("purga credenciais legadas antes de consultar a sessão online", async () => {
    const order: string[] = [];
    mocks.purgeLegacyAuthStorage.mockImplementation(() => order.push("purge"));
    mocks.fetchSession.mockImplementation(async () => {
      order.push("fetch");
      return profile;
    });

    await expect(initializeAuthSession()).resolves.toEqual(profile);
    expect(order).toEqual(["purge", "fetch"]);
    expect(mocks.setSession).toHaveBeenCalledWith(profile);
  });

  it("normaliza o CPF e grava somente o perfil validado", async () => {
    mocks.loginWithCpf.mockResolvedValue(profile);

    await expect(autenticarPorCpf("111.444.777-35"))
      .resolves.toEqual(profile);
    expect(mocks.loginWithCpf).toHaveBeenCalledWith("11144477735");
    expect(mocks.setSession).toHaveBeenCalledWith(profile);
  });

  it("não limpa a memória quando a rede impede revogar a sessão", async () => {
    mocks.logoutOnline.mockRejectedValue(new TypeError("offline"));

    await expect(encerrarSessao()).rejects.toThrow("offline");
    expect(mocks.clearSession).not.toHaveBeenCalled();
  });

  it("limpa a memória após revogação ou sessão já expirada", async () => {
    mocks.logoutOnline.mockResolvedValueOnce("revoked");
    await encerrarSessao();
    mocks.logoutOnline.mockResolvedValueOnce("already-expired");
    await encerrarSessao();

    expect(mocks.clearSession).toHaveBeenCalledTimes(2);
  });
});
