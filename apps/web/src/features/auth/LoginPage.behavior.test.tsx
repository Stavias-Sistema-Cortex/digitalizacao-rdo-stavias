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
  it("keeps one action instruction and one security note without marketing copy", () => {
    const view = render(<LoginPage />);

    expect(screen.getByText("Use seu CPF ou uma passkey para entrar."))
      .toBeVisible();
    expect(
      screen.getByText(
        "Apenas colaboradores autorizados. Ações vinculadas à sua identidade.",
      ),
    ).toBeVisible();
    expect(view.container.querySelectorAll(".login__subtitle")).toHaveLength(1);
    expect(view.container.querySelectorAll(".login__hint")).toHaveLength(1);
    expect(view.container.querySelector(".login__identity-copy")).toBeNull();
    expect(view.container.querySelector(".login__security-note")).toBeNull();
    expect(view.container.querySelector(".login__footer")).toBeNull();
  });

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

  it("keeps the live document after direct CPF authentication so a memory-only lease survives", async () => {
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
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.queryByText(/código|e-mail/i)).not.toBeInTheDocument();
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
