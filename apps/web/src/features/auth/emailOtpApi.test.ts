import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiError: vi.fn(),
  publicAuthFetch: vi.fn(),
  readPublicAuthResponse: vi.fn(),
}));

vi.mock("../../lib/api/apiError", () => ({
  apiError: mocks.apiError,
}));

vi.mock("./emailOtpTransport", () => ({
  publicAuthFetch: mocks.publicAuthFetch,
  readPublicAuthResponse: mocks.readPublicAuthResponse,
}));

import {
  requestCpfOtpChallenge,
  requestEmailOtpChallenge,
  verifyEmailOtpChallenge,
} from "./emailOtpApi";

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Responsável Córtex",
  papelAcesso: "ALFA",
  escopoGlobal: true,
  obraIds: [],
  expiraEm: "2099-07-14T12:00:00Z",
};

function response(status: number): Response {
  return { ok: status >= 200 && status < 300, status } as Response;
}

describe("emailOtpApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiError.mockReturnValue(new Error("Falha controlada."));
  });

  it("solicita o desafio com os dígitos canônicos do CPF e conserva somente o identificador opaco", async () => {
    mocks.publicAuthFetch.mockResolvedValue(response(202));
    mocks.readPublicAuthResponse.mockResolvedValue({
      challengeId: "00000000-0000-4000-8000-000000000003",
      expiresInSeconds: 600,
      message: "Conteúdo do servidor não é apresentado diretamente.",
    });

    await expect(
      requestCpfOtpChallenge("111.444.777-35"),
    ).resolves.toEqual({
      challengeId: "00000000-0000-4000-8000-000000000003",
    });

    expect(mocks.publicAuthFetch).toHaveBeenCalledWith(
      "/auth/email/challenges",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ identifier: "11144477735" }),
      },
    );
  });

  it("preserva o desafio de e-mail para o fluxo de ativação", async () => {
    mocks.publicAuthFetch.mockResolvedValue(response(202));
    mocks.readPublicAuthResponse.mockResolvedValue({
      challengeId: "00000000-0000-4000-8000-000000000003",
      expiresInSeconds: 600,
    });

    await requestEmailOtpChallenge(" alfas@stavias.example ");

    expect(mocks.publicAuthFetch).toHaveBeenCalledWith(
      "/auth/email/challenges",
      expect.objectContaining({
        body: JSON.stringify({ identifier: "alfas@stavias.example" }),
      }),
    );
  });

  it("envia somente o código na verificação e devolve o perfil seguro", async () => {
    mocks.publicAuthFetch.mockResolvedValue(response(200));
    mocks.readPublicAuthResponse.mockResolvedValue({
      ...profile,
      email: "não deve atravessar o parser",
      token: "não deve atravessar o parser",
    });

    await expect(
      verifyEmailOtpChallenge(
        "00000000-0000-4000-8000-000000000003",
        "123456",
      ),
    ).resolves.toEqual(profile);

    expect(mocks.publicAuthFetch).toHaveBeenCalledWith(
      "/auth/email/challenges/00000000-0000-4000-8000-000000000003/verify",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ code: "123456" }),
      },
    );
  });

  it("recusa uma resposta que não contenha um identificador opaco válido", async () => {
    mocks.publicAuthFetch.mockResolvedValue(response(202));
    mocks.readPublicAuthResponse.mockResolvedValue({
      challengeId: "email-alfa@stavias.example",
      expiresInSeconds: 600,
    });

    await expect(
      requestCpfOtpChallenge("11144477735"),
    ).rejects.toThrow("Desafio de autenticação inválido");
  });
});
