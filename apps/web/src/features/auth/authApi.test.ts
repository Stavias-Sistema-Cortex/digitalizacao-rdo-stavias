import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiError: vi.fn(),
  apiFetch: vi.fn(),
  readResponseBody: vi.fn(),
  responseErrorMessage: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", () => ({
  apiError: mocks.apiError,
  apiFetch: mocks.apiFetch,
  readResponseBody: mocks.readResponseBody,
  responseErrorMessage: mocks.responseErrorMessage,
}));

import {
  fetchOfflineGrant,
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
    mocks.apiFetch.mockResolvedValue(response(200));
    mocks.readResponseBody.mockResolvedValue({
      ...profile,
      token: "não deve atravessar o parser",
      cpfMascarado: "não deve atravessar o parser",
      email: "não deve atravessar o parser",
    });

    await expect(loginWithCpf("11144477735")).resolves.toEqual(profile);

    expect(mocks.apiFetch).toHaveBeenCalledWith(
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

  it("revoga a sessão no servidor e reconhece uma sessão já expirada", async () => {
    mocks.apiFetch.mockResolvedValueOnce(response(204));
    mocks.readResponseBody.mockResolvedValueOnce(null);
    await expect(logoutOnline()).resolves.toBe("revoked");

    mocks.apiFetch.mockResolvedValueOnce(response(401));
    mocks.readResponseBody.mockResolvedValueOnce({ message: "expirada" });
    await expect(logoutOnline()).resolves.toBe("already-expired");

    expect(mocks.apiFetch).toHaveBeenNthCalledWith(1, "/auth/logout", {
      method: "POST",
    });
  });
});
