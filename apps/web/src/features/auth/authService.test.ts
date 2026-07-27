import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => {
  class OfflineGrantOwnerMismatchError extends Error {}
  return {
    fetchOfflineGrantAfterFreshCpfLogin: vi.fn(),
    fetchSession: vi.fn(),
    loginWithCpf: vi.fn(),
    logoutOnline: vi.fn(),
    saveCollaborativeOfflineGrant: vi.fn(),
    OfflineGrantOwnerMismatchError,
  };
});

vi.mock("./authApi", () => ({
  fetchOfflineGrantAfterFreshCpfLogin:
    mocks.fetchOfflineGrantAfterFreshCpfLogin,
  fetchSession: mocks.fetchSession,
  loginWithCpf: mocks.loginWithCpf,
  logoutOnline: mocks.logoutOnline,
}));

vi.mock("./collaborativeOfflineGrant", () => ({
  OfflineGrantOwnerMismatchError: mocks.OfflineGrantOwnerMismatchError,
  saveCollaborativeOfflineGrant: mocks.saveCollaborativeOfflineGrant,
}));

import {
  autenticarPorCpf,
  encerrarSessao,
  initializeAuthSession,
} from "./authService";
import { clearSession, getSession, setSession } from "./authSession";
import {
  clearRemoteSessionIsolation,
  hasRemoteSessionIsolation,
} from "./remoteSessionIsolation";

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Colaborador Sintético",
  papelAcesso: "BETA" as const,
  escopoGlobal: false,
  obraIds: ["00000000-0000-4000-8000-000000000002"],
  expiraEm: "2099-07-14T12:00:00Z",
};
const REMOTE_SESSION_ISOLATION_KEY = "cortex.auth.remote-session-isolation";

describe("authService", () => {
  const storage = new Map<string, string>();
  let rejectIsolationMarkerWrite = false;

  beforeEach(() => {
    vi.clearAllMocks();
    mocks.logoutOnline.mockReset();
    mocks.logoutOnline.mockResolvedValue("revoked");
    storage.clear();
    rejectIsolationMarkerWrite = false;
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => {
        if (
          key === REMOTE_SESSION_ISOLATION_KEY &&
          rejectIsolationMarkerWrite
        ) {
          throw new Error("storage unavailable");
        }
        storage.set(key, value);
      },
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear(),
    });
    localStorage.clear();
    clearSession();
  });

  afterEach(() => {
    rejectIsolationMarkerWrite = false;
    clearRemoteSessionIsolation();
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

  it("does not rehydrate a stale cookie principal during bootstrap while remote isolation is persisted", async () => {
    localStorage.setItem(REMOTE_SESSION_ISOLATION_KEY, "1");
    mocks.fetchSession.mockResolvedValue(profile);

    await expect(initializeAuthSession()).resolves.toBeNull();

    expect(mocks.fetchSession).not.toHaveBeenCalled();
    expect(getSession()).toBeNull();
    expect(localStorage.getItem(REMOTE_SESSION_ISOLATION_KEY)).toBe("1");
  });

  it("normaliza o CPF, grava o grant e retorna seu estado", async () => {
    mocks.loginWithCpf.mockResolvedValue(profile);
    mocks.fetchOfflineGrantAfterFreshCpfLogin.mockResolvedValue({ signed: "grant" });

    await expect(autenticarPorCpf("111.444.777-35"))
      .resolves.toEqual({ profile, offlineGrant: "READY" });
    expect(mocks.loginWithCpf).toHaveBeenCalledWith("11144477735");
    expect(mocks.saveCollaborativeOfflineGrant).toHaveBeenCalledWith(
      "11144477735",
      { signed: "grant" },
      profile.colaboradorId,
    );
    expect(getSession()).toEqual(profile);
  });

  it("clears the resident session and keeps this tab isolated when the marker cannot persist", async () => {
    setSession(profile);
    rejectIsolationMarkerWrite = true;

    await expect(autenticarPorCpf("11144477735"))
      .rejects.toThrow("isolar a sessão remota");

    expect(getSession()).toBeNull();
    expect(hasRemoteSessionIsolation()).toBe(true);
    expect(mocks.loginWithCpf).not.toHaveBeenCalled();
  });

  it("keeps remote isolation and no local profile when the fresh grant cannot confirm the cookie owner", async () => {
    mocks.loginWithCpf.mockResolvedValue(profile);
    mocks.fetchOfflineGrantAfterFreshCpfLogin.mockRejectedValue(
      new TypeError("offline"),
    );

    await expect(autenticarPorCpf("11144477735"))
      .rejects.toThrow("confirmar a sessão recém-autenticada");

    expect(getSession()).toBeNull();
    expect(localStorage.getItem(REMOTE_SESSION_ISOLATION_KEY)).toBe("1");
  });

  it("releases remote isolation only after a new direct CPF login succeeds", async () => {
    localStorage.setItem(REMOTE_SESSION_ISOLATION_KEY, "1");
    mocks.loginWithCpf.mockResolvedValue(profile);
    mocks.fetchOfflineGrantAfterFreshCpfLogin.mockResolvedValue({
      signed: "grant",
    });

    await expect(autenticarPorCpf("11144477735")).resolves.toEqual({
      profile,
      offlineGrant: "READY",
    });

    expect(localStorage.getItem(REMOTE_SESSION_ISOLATION_KEY)).toBeNull();
    expect(getSession()).toEqual(profile);
  });

  it("fails closed when a concurrent login returns another collaborator's offline grant", async () => {
    mocks.loginWithCpf.mockResolvedValue(profile);
    mocks.fetchOfflineGrantAfterFreshCpfLogin.mockResolvedValue({
      signed: "grant-b",
    });
    mocks.saveCollaborativeOfflineGrant.mockRejectedValue(
      new mocks.OfflineGrantOwnerMismatchError("owner mismatch"),
    );

    await expect(autenticarPorCpf("11144477735")).rejects.toThrow(
      "sessão foi alterada",
    );

    expect(getSession()).toBeNull();
    expect(localStorage.getItem(REMOTE_SESSION_ISOLATION_KEY)).toBe("1");
    expect(mocks.logoutOnline).not.toHaveBeenCalled();
  });

  it("bloqueia a sessão local mesmo quando a rede impede a revogação", async () => {
    mocks.logoutOnline.mockRejectedValue(new TypeError("offline"));

    await expect(encerrarSessao()).rejects.toThrow("offline");
    expect(getSession()).toBeNull();
    expect(localStorage.getItem(REMOTE_SESSION_ISOLATION_KEY)).toBe("1");
    expect(localStorage.getItem("cortex.auth.logoutPending")).toBeNull();
  });

  it("clears local state before a failed isolation-marker write during logout", async () => {
    setSession(profile);
    rejectIsolationMarkerWrite = true;

    await expect(encerrarSessao()).rejects.toThrow("isolar a sessão remota");

    expect(getSession()).toBeNull();
    expect(hasRemoteSessionIsolation()).toBe(true);
    expect(mocks.logoutOnline).not.toHaveBeenCalled();
  });

  it("limpa a memória após revogação ou sessão já expirada", async () => {
    mocks.logoutOnline.mockResolvedValueOnce("revoked");
    await encerrarSessao();
    mocks.logoutOnline.mockResolvedValueOnce("already-expired");
    await encerrarSessao();

    expect(getSession()).toBeNull();
    expect(localStorage.getItem("cortex.auth.logoutPending")).toBeNull();
  });

  it("does not revoke an unknown shared cookie when an existing isolation marker is logged out", async () => {
    localStorage.setItem(REMOTE_SESSION_ISOLATION_KEY, "1");
    setSession(profile);

    await encerrarSessao();

    expect(getSession()).toBeNull();
    expect(mocks.logoutOnline).not.toHaveBeenCalled();
    expect(localStorage.getItem(REMOTE_SESSION_ISOLATION_KEY)).toBe("1");
  });
});
