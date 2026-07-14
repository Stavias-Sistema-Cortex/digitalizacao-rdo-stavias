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

import {
  fetchSession,
  logoutOnline,
  requestEmailCode,
  verifyEmailCode,
} from "./authApi";

function response(status: number): Response {
  return { ok: status >= 200 && status < 300, status } as Response;
}

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Colaborador Sintético",
  papelAcesso: "BETA",
  expiraEm: "2026-07-14T12:00:00Z",
};

describe("authApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.responseErrorMessage.mockReturnValue("Falha controlada.");
  });

  it("solicita o código usando o identificador genérico do contrato real", async () => {
    mocks.apiFetch.mockResolvedValue(response(202));
    mocks.readResponseBody.mockResolvedValue({
      challengeId: "00000000-0000-4000-8000-000000000002",
      expiresInSeconds: 600,
      message: "Se os dados estiverem aptos, enviaremos um código.",
    });

    await expect(requestEmailCode("11144477735")).resolves.toMatchObject({
      expiresInSeconds: 600,
    });

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/auth/email/challenges",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ identifier: "11144477735" }),
      }),
    );
  });

  it("verifica o código em um caminho codificado e retorna apenas o perfil seguro", async () => {
    mocks.apiFetch.mockResolvedValue(response(200));
    mocks.readResponseBody.mockResolvedValue({
      ...profile,
      token: "não deve atravessar o parser",
      cpfMascarado: "não deve atravessar o parser",
    });

    await expect(
      verifyEmailCode("challenge/with/slash", "123456"),
    ).resolves.toEqual(profile);

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/auth/email/challenges/challenge%2Fwith%2Fslash/verify",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ code: "123456" }),
      }),
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
