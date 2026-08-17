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
  completePasswordSetup: vi.fn(),
}));

vi.mock("./passkeyApi", () => ({
  authenticateWithPasskey: mocks.authenticateWithPasskey,
}));

vi.mock("./authService", () => ({
  autenticarPorCpf: mocks.autenticarPorCpf,
}));

vi.mock("./authApi", () => ({
  completePasswordSetup: mocks.completePasswordSetup,
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

    expect(screen.getByText("Use seu CPF e sua senha para entrar."))
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

  it("offers CPF plus password first and passkey as the secondary action", () => {
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
    expect(screen.getByLabelText("Senha")).toHaveAttribute(
      "autocomplete",
      "current-password",
    );
  });

  it("explains first access through an accessible info control", async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    const info = screen.getByRole("button", {
      name: "Informação sobre primeiro acesso",
    });
    const guidance = screen.getByRole("tooltip");

    expect(info).toHaveAttribute("aria-expanded", "false");
    expect(info).toHaveAttribute("aria-describedby", guidance.id);
    expect(guidance).toHaveTextContent(
      "Se este é seu primeiro login, adicione o código de acesso.",
    );

    await user.hover(info);
    expect(info).toHaveAttribute("aria-expanded", "true");

    await user.unhover(info);
    expect(info).toHaveAttribute("aria-expanded", "false");

    await user.click(info);

    expect(info).toHaveAttribute("aria-expanded", "true");

    await user.keyboard("{Escape}");

    expect(info).toHaveAttribute("aria-expanded", "false");
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
    await user.type(
      screen.getByLabelText("Senha"),
      "Frase secreta individual!",
    );
    await user.click(screen.getByRole("button", { name: "Entrar" }));

    await waitFor(() => {
      expect(mocks.autenticarPorCpf).toHaveBeenCalledWith(
        "11144477735",
        "Frase secreta individual!",
      );
    });
    expect(navigate).not.toHaveBeenCalled();
    expect(screen.queryByText(/e-mail/i)).not.toBeInTheDocument();
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

  it("recognizes an eight-digit code in the password field and opens password definition", async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    await user.type(screen.getByRole("textbox", { name: "CPF" }), "11144477735");
    await user.type(
      screen.getByLabelText("Senha"),
      "12345678",
    );
    await user.click(screen.getByRole("button", { name: "Entrar" }));

    expect(mocks.autenticarPorCpf).not.toHaveBeenCalled();
    expect(screen.getByRole("heading", { name: "Definir sua senha" }))
      .toBeVisible();
    expect(screen.queryByLabelText("Código temporário")).not.toBeInTheDocument();
    expect(screen.queryByDisplayValue("12345678")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Nova senha")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Definir senha" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Voltar para entrar com senha" }))
      .toBeVisible();
    expect(screen.queryByRole("button", {
      name: "Primeiro acesso ou esqueci minha senha",
    })).not.toBeInTheDocument();
  });

  it("closes first-access guidance while switching through password setup", async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    const info = screen.getByRole("button", {
      name: "Informação sobre primeiro acesso",
    });
    await user.click(info);
    expect(info).toHaveAttribute("aria-expanded", "true");

    await user.type(screen.getByRole("textbox", { name: "CPF" }), "11144477735");
    await user.type(screen.getByLabelText("Senha"), "12345678");
    await user.click(screen.getByRole("button", { name: "Entrar" }));
    await user.click(screen.getByRole("button", {
      name: "Voltar para entrar com senha",
    }));

    expect(screen.getByRole("button", {
      name: "Informação sobre primeiro acesso",
    })).toHaveAttribute("aria-expanded", "false");
  });

  it("uses the recognized code only with the same CPF to define the new password", async () => {
    mocks.completePasswordSetup.mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<LoginPage />);

    await user.type(screen.getByRole("textbox", { name: "CPF" }), "11144477735");
    await user.type(
      screen.getByLabelText("Senha"),
      "12345678",
    );
    await user.click(screen.getByRole("button", { name: "Entrar" }));
    await user.type(
      screen.getByLabelText("Nova senha"),
      "Frase secreta individual!",
    );
    await user.click(screen.getByRole("button", { name: "Definir senha" }));

    await waitFor(() => {
      expect(mocks.completePasswordSetup).toHaveBeenCalledWith(
        "11144477735",
        "12345678",
        "Frase secreta individual!",
      );
    });
    expect(screen.getByText("Senha definida. Agora entre com sua nova senha."))
      .toBeVisible();
  });
});
