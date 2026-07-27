import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiError: vi.fn(),
  apiFetch: vi.fn(),
  fetchFreshCpfOfflineGrant: vi.fn(),
  freshAuthenticationFetch: vi.fn(),
  readResponseBody: vi.fn(),
  revokeRemoteSessionCookie: vi.fn(),
  responseErrorMessage: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", () => ({
  apiError: mocks.apiError,
  apiFetch: mocks.apiFetch,
  fetchFreshCpfOfflineGrant: mocks.fetchFreshCpfOfflineGrant,
  freshAuthenticationFetch: mocks.freshAuthenticationFetch,
  readResponseBody: mocks.readResponseBody,
  revokeRemoteSessionCookie: mocks.revokeRemoteSessionCookie,
  responseErrorMessage: mocks.responseErrorMessage,
}));

import {
  fetchOfflineGrant,
  fetchOfflineGrantAfterFreshCpfLogin,
  fetchSession,
  loginWithCpf,
  logoutOnline,
} from "./authApi";

function response(status: number): Response {
  return { ok: status >= 200 && status < 300, status } as Response;
}

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Colaborador Sintético",
  papelAcesso: "BETA",
  escopoGlobal: false,
  obraIds: ["00000000-0000-4000-8000-000000000002"],
  expiraEm: "2099-07-14T12:00:00Z",
};

describe("authApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.responseErrorMessage.mockReturnValue("Falha controlada.");
    mocks.apiError.mockReturnValue(new Error("Falha controlada."));
  });

  it("entra com CPF e retorna somente o perfil seguro", async () => {
    mocks.freshAuthenticationFetch.mockResolvedValue(response(200));
    mocks.readResponseBody.mockResolvedValue({
      ...profile,
      token: "não deve atravessar o parser",
      cpfMascarado: "não deve atravessar o parser",
      email: "não deve atravessar o parser",
    });

    await expect(loginWithCpf("11144477735")).resolves.toEqual(profile);

    expect(mocks.freshAuthenticationFetch).toHaveBeenCalledWith(
      "/auth/login",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cpf: "11144477735" }),
      },
    );
  });

  it("uses the explicit fresh CPF transition instead of ordinary api fetch", async () => {
    mocks.freshAuthenticationFetch.mockResolvedValue(response(200));
    mocks.readResponseBody.mockResolvedValue(profile);

    await expect(loginWithCpf("11144477735")).resolves.toEqual(profile);

    expect(mocks.apiFetch).not.toHaveBeenCalled();
    expect(mocks.freshAuthenticationFetch).toHaveBeenCalledWith(
      "/auth/login",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ cpf: "11144477735" }),
      },
    );
  });

  it("obtém somente o envelope assinado exato do grant offline", async () => {
    const grant = {
      keyId: "offline-test-v1",
      payload: "payload",
      signature: "signature",
      publicKeySpki: "public-key",
    };
    mocks.apiFetch.mockResolvedValue(response(200));
    mocks.readResponseBody.mockResolvedValue(grant);

    await expect(fetchOfflineGrant()).resolves.toEqual(grant);

    expect(mocks.apiFetch).toHaveBeenCalledWith("/auth/offline-grant", {
      method: "POST",
    });
  });

  it("obtém o grant de CPF somente pelo follow-up isolado de autenticação fresca", async () => {
    const grant = {
      keyId: "offline-test-v1",
      payload: "payload",
      signature: "signature",
      publicKeySpki: "public-key",
    };
    mocks.fetchFreshCpfOfflineGrant.mockResolvedValue(response(200));
    mocks.readResponseBody.mockResolvedValue(grant);

    await expect(fetchOfflineGrantAfterFreshCpfLogin()).resolves.toEqual(grant);

    expect(mocks.apiFetch).not.toHaveBeenCalled();
    expect(mocks.fetchFreshCpfOfflineGrant).toHaveBeenCalledTimes(1);
  });

  it("rejeita um envelope de grant offline com campos extras", async () => {
    mocks.apiFetch.mockResolvedValue(response(200));
    mocks.readResponseBody.mockResolvedValue({
      keyId: "offline-test-v1",
      payload: "payload",
      signature: "signature",
      publicKeySpki: "public-key",
      cpf: "11144477735",
    });

    await expect(fetchOfflineGrant()).rejects.toThrow(
      "Envelope do grant offline inválido.",
    );
  });

  it("nega perfis cujo papel não seja ALFA ou BETA canônico", async () => {
    mocks.apiFetch.mockResolvedValue(response(200));
    mocks.readResponseBody.mockResolvedValue({
      ...profile,
      papelAcesso: "beta",
    });

    await expect(fetchSession()).rejects.toThrow(
      "Perfil de autenticação inválido",
    );
  });

  it("converte somente 401 da consulta de sessão em ausência de sessão", async () => {
    mocks.apiFetch.mockResolvedValueOnce(response(401));
    mocks.readResponseBody.mockResolvedValueOnce({ message: "expirada" });
    await expect(fetchSession()).resolves.toBeNull();

    mocks.apiFetch.mockResolvedValueOnce(response(503));
    mocks.readResponseBody.mockResolvedValueOnce({ message: "indisponível" });
    await expect(fetchSession()).rejects.toThrow("Falha controlada.");
  });

  it("revoga a sessão no servidor somente quando o servidor confirma", async () => {
    mocks.revokeRemoteSessionCookie.mockResolvedValue(response(204));
    mocks.readResponseBody.mockResolvedValue(null);

    await expect(logoutOnline()).resolves.toBe("revoked");

    expect(mocks.revokeRemoteSessionCookie).toHaveBeenCalledOnce();
  });

  it("não trata um logout 401 ligado a outra aba como revogação confirmada", async () => {
    const body = { message: "Autenticação necessária ou sessão expirada." };
    mocks.revokeRemoteSessionCookie.mockResolvedValue(response(401));
    mocks.readResponseBody.mockResolvedValue(body);

    await expect(logoutOnline()).rejects.toThrow("Falha controlada.");

    expect(mocks.apiError).toHaveBeenCalledWith(body, 401);
  });
});
