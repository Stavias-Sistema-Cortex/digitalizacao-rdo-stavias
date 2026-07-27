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
  autenticarPorCpf: vi.fn(),
}));

vi.mock("./passkeyApi", () => ({
  authenticateWithPasskey: mocks.authenticateWithPasskey,
}));

vi.mock("./authService", () => ({
  autenticarPorCpf: mocks.autenticarPorCpf,
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
  it("offers direct CPF first and passkey as the explicit secondary production action", () => {
    vi.stubEnv("DEV", false);
    vi.stubEnv("PROD", true);

    render(<LoginPage />);

    expect(
      screen.getByRole("button", { name: "Entrar" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Entrar com passkey" }),
    ).toHaveAttribute("type", "button");
    expect(screen.getByRole("textbox", { name: "CPF" })).toHaveAttribute(
      "inputmode",
      "numeric",
    );
  });

  it("authenticates the active collaborator directly with the canonical CPF", async () => {
    vi.stubEnv("DEV", false);
    vi.stubEnv("PROD", true);
    mocks.autenticarPorCpf.mockResolvedValue({
      profile,
      offlineGrant: "READY",
    });
    const user = userEvent.setup();
    const navigate = vi.fn();
    vi.stubGlobal("location", { assign: navigate });

    render(<LoginPage />);

    await user.type(screen.getByRole("textbox", { name: "CPF" }), "11144477735");
    await user.click(screen.getByRole("button", { name: "Entrar" }));

    await waitFor(() => {
      expect(mocks.autenticarPorCpf).toHaveBeenCalledWith("11144477735");
    });
    expect(navigate).toHaveBeenCalledWith("/");
    expect(screen.queryByText(/código|e-mail/i)).not.toBeInTheDocument();
  });

  it("reports a nonfatal offline-cache warning without blocking the online session", async () => {
    mocks.autenticarPorCpf.mockResolvedValue({
      profile,
      offlineGrant: "UNAVAILABLE",
    });
    const navigate = vi.fn();
    vi.stubGlobal("location", { assign: navigate });
    const user = userEvent.setup();

    render(<LoginPage />);

    await user.type(screen.getByRole("textbox", { name: "CPF" }), "111.444.777-35");
    await user.click(screen.getByRole("button", { name: "Entrar" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Acesso realizado, mas o acesso offline não pôde ser atualizado neste dispositivo.",
    );
    expect(navigate).toHaveBeenCalledWith("/");
  });

  it("keeps the same direct CPF primary flow in local development", () => {
    vi.stubEnv("DEV", true);
    vi.stubEnv("PROD", false);

    render(<LoginPage />);

    expect(screen.getByRole("button", { name: "Entrar" })).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Entrar com passkey" }),
    ).toBeInTheDocument();
  });
});
