import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  fetchOfflineGrant: vi.fn(),
  fetchSession: vi.fn(),
  loginWithCpf: vi.fn(),
  logoutOnline: vi.fn(),
  saveCollaborativeOfflineGrant: vi.fn(),
}));

vi.mock("./authApi", () => ({
  fetchOfflineGrant: mocks.fetchOfflineGrant,
  fetchSession: mocks.fetchSession,
  loginWithCpf: mocks.loginWithCpf,
  logoutOnline: mocks.logoutOnline,
}));

vi.mock("./collaborativeOfflineGrant", () => ({
  saveCollaborativeOfflineGrant: mocks.saveCollaborativeOfflineGrant,
}));

import {
  autenticarPorCpf,
  encerrarSessao,
  initializeAuthSession,
} from "./authService";
import { clearSession, getSession, setSession } from "./authSession";

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Colaborador Sintético",
  papelAcesso: "BETA" as const,
  escopoGlobal: false,
  obraIds: ["00000000-0000-4000-8000-000000000002"],
  expiraEm: "2099-07-14T12:00:00Z",
};

describe("authService", () => {
  const storage = new Map<string, string>();

  beforeEach(() => {
    vi.clearAllMocks();
    storage.clear();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    });
    localStorage.clear();
    clearSession();
  });

  afterEach(() => {
    clearSession();
    vi.unstubAllGlobals();
  });

  it("purga credenciais legadas antes de consultar a sessão online", async () => {
    localStorage.setItem("cortex.auth.sessao", "legado");
    localStorage.setItem("cortex.auth.cpfFilter", "legado");
    mocks.fetchSession.mockResolvedValue(profile);

    await expect(initializeAuthSession()).resolves.toEqual(profile);
    expect(localStorage.getItem("cortex.auth.sessao")).toBeNull();
    expect(localStorage.getItem("cortex.auth.cpfFilter")).toBeNull();
    expect(getSession()).toEqual(profile);
  });

  it("normaliza o CPF, grava o grant e retorna seu estado", async () => {
    mocks.loginWithCpf.mockResolvedValue(profile);
    mocks.fetchOfflineGrant.mockResolvedValue({ signed: "grant" });

    await expect(autenticarPorCpf("111.444.777-35"))
      .resolves.toEqual({ profile, offlineGrant: "READY" });
    expect(mocks.loginWithCpf).toHaveBeenCalledWith("11144477735");
    expect(mocks.saveCollaborativeOfflineGrant).toHaveBeenCalledWith(
      "11144477735",
      { signed: "grant" },
    );
    expect(getSession()).toEqual(profile);
  });

  it("preserva a sessão online quando a atualização do grant falha", async () => {
    mocks.loginWithCpf.mockResolvedValue(profile);
    mocks.fetchOfflineGrant.mockRejectedValue(new TypeError("offline"));

    await expect(autenticarPorCpf("11144477735"))
      .resolves.toEqual({ profile, offlineGrant: "UNAVAILABLE" });

    expect(getSession()).toEqual(profile);
  });

  it("bloqueia a sessão local mesmo quando a rede impede a revogação", async () => {
    mocks.logoutOnline.mockRejectedValue(new TypeError("offline"));

    await expect(encerrarSessao()).rejects.toThrow("offline");
    expect(getSession()).toBeNull();
    expect(localStorage.getItem("cortex.auth.logoutPending")).toBe("1");
  });

  it("limpa a memória após revogação ou sessão já expirada", async () => {
    mocks.logoutOnline.mockResolvedValueOnce("revoked");
    await encerrarSessao();
    mocks.logoutOnline.mockResolvedValueOnce("already-expired");
    await encerrarSessao();

    expect(getSession()).toBeNull();
    expect(localStorage.getItem("cortex.auth.logoutPending")).toBeNull();
  });

  it("não restaura o cookie enquanto uma revogação pendente estiver offline", async () => {
    localStorage.setItem("cortex.auth.logoutPending", "1");
    setSession(profile);
    mocks.logoutOnline.mockRejectedValue(new TypeError("offline"));

    await expect(initializeAuthSession()).resolves.toBeNull();

    expect(getSession()).toBeNull();
    expect(mocks.fetchSession).not.toHaveBeenCalled();
  });
});
