import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  clearSession: vi.fn(),
  fetchSession: vi.fn(),
  logoutOnline: vi.fn(),
  purgeLegacyAuthStorage: vi.fn(),
  requestEmailCode: vi.fn(),
  setSession: vi.fn(),
  verifyEmailCode: vi.fn(),
}));

vi.mock("./authApi", () => ({
  fetchSession: mocks.fetchSession,
  logoutOnline: mocks.logoutOnline,
  requestEmailCode: mocks.requestEmailCode,
  verifyEmailCode: mocks.verifyEmailCode,
}));

vi.mock("./authSession", () => ({
  clearSession: mocks.clearSession,
  purgeLegacyAuthStorage: mocks.purgeLegacyAuthStorage,
  setSession: mocks.setSession,
}));

import {
  encerrarSessao,
  initializeAuthSession,
  solicitarCodigo,
  verificarCodigo,
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

  it("solicitar código não cria uma sessão local", async () => {
    const challenge = {
      challengeId: "00000000-0000-4000-8000-000000000002",
      expiresInSeconds: 600,
      message: "Mensagem genérica.",
    };
    mocks.requestEmailCode.mockResolvedValue(challenge);

    await expect(solicitarCodigo("111.444.777-35")).resolves.toEqual(
      challenge,
    );
    expect(mocks.setSession).not.toHaveBeenCalled();
  });

  it("grava somente o perfil validado depois do código correto", async () => {
    mocks.verifyEmailCode.mockResolvedValue(profile);

    await expect(
      verificarCodigo("challenge-sintético", "123 456"),
    ).resolves.toEqual(profile);
    expect(mocks.verifyEmailCode).toHaveBeenCalledWith(
      "challenge-sintético",
      "123456",
    );
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
