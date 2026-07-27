// @vitest-environment jsdom

import {
  cleanup,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  authenticateWithPasskey: vi.fn(),
  requestCpfOtpChallenge: vi.fn(),
  setSession: vi.fn(),
  verifyEmailOtpChallenge: vi.fn(),
}));

vi.mock("./passkeyApi", () => ({
  authenticateWithPasskey: mocks.authenticateWithPasskey,
}));

vi.mock("./emailOtpApi", () => ({
  requestCpfOtpChallenge: mocks.requestCpfOtpChallenge,
  verifyEmailOtpChallenge: mocks.verifyEmailOtpChallenge,
}));

vi.mock("./authSession", () => ({
  setSession: mocks.setSession,
}));

import { LoginPage } from "./LoginPage";

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Responsável Córtex",
  papelAcesso: "ALFA" as const,
  escopoGlobal: true,
  obraIds: [],
  expiraEm: "2099-07-14T12:00:00Z",
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

describe("LoginPage access methods", () => {
  it("offers CPF OTP first and passkey as the explicit secondary production action", () => {
    vi.stubEnv("DEV", false);
    vi.stubEnv("PROD", true);

    render(<LoginPage />);

    expect(
      screen.getByRole("button", { name: "Enviar código" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Entrar com passkey" }),
    ).toHaveAttribute("type", "button");
    expect(screen.getByRole("textbox", { name: "CPF" })).toHaveAttribute(
      "inputmode",
      "numeric",
    );
  });

  it("stores the safe OTP profile and navigates after a CPF challenge is confirmed", async () => {
    vi.stubEnv("DEV", false);
    vi.stubEnv("PROD", true);
    mocks.requestCpfOtpChallenge.mockResolvedValue({
      challengeId: "00000000-0000-4000-8000-000000000003",
    });
    mocks.verifyEmailOtpChallenge.mockResolvedValue(profile);
    const user = userEvent.setup();

    render(<LoginPage />);

    await user.type(screen.getByRole("textbox", { name: "CPF" }), "11144477735");
    await user.click(screen.getByRole("button", { name: "Enviar código" }));

    expect(mocks.requestCpfOtpChallenge).toHaveBeenCalledWith("11144477735");

    await user.type(
      await screen.findByRole("textbox", { name: "Código de acesso" }),
      "123456",
    );

    const navigate = vi.fn();
    vi.stubGlobal("location", { assign: navigate });

    await user.click(screen.getByRole("button", { name: "Confirmar acesso" }));

    await waitFor(() => {
      expect(mocks.setSession).toHaveBeenCalledWith(profile);
    });
    expect(mocks.verifyEmailOtpChallenge).toHaveBeenCalledWith(
      "00000000-0000-4000-8000-000000000003",
      "123456",
    );
    expect(navigate).toHaveBeenCalledWith("/");
  });

  it("keeps the same secure CPF-to-code primary flow in local development", () => {
    vi.stubEnv("DEV", true);
    vi.stubEnv("PROD", false);

    render(<LoginPage />);

    expect(screen.getByRole("button", { name: "Enviar código" })).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Entrar com passkey" }),
    ).toBeInTheDocument();
  });
});
