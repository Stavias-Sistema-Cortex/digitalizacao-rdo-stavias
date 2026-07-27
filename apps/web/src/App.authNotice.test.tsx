// @vitest-environment jsdom

import {
  cleanup,
  render,
  screen,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  autenticarPorCpf: vi.fn(),
  hasCollaborativeOfflineGrantMetadata: vi.fn(),
  loadOfflineVaultMetadata: vi.fn(),
}));

vi.mock("./features/auth/authService", () => ({
  autenticarPorCpf: mocks.autenticarPorCpf,
}));

vi.mock("./features/auth/offlineVaultRepository", () => ({
  hasCollaborativeOfflineGrantMetadata:
    mocks.hasCollaborativeOfflineGrantMetadata,
  loadOfflineVaultMetadata: mocks.loadOfflineVaultMetadata,
}));

vi.mock("./appAutomaticSync", () => ({
  useAppAutomaticSync: () => undefined,
}));

vi.mock("./features/home/HomePage", () => ({
  HomePage: () => <main data-testid="authenticated-home">Área autenticada</main>,
}));

import App from "./App";
import { clearSession, setSession } from "./features/auth/authSession";

const profile = {
  colaboradorId: "00000000-0000-4000-8000-000000000001",
  nome: "Responsável Córtex",
  papelAcesso: "ALFA" as const,
  escopoGlobal: true,
  obraIds: [],
  expiraEm: "2099-07-14T12:00:00Z",
};

beforeEach(() => {
  clearSession();
  sessionStorage.clear();
  window.history.replaceState({}, "", "/home");
  mocks.hasCollaborativeOfflineGrantMetadata.mockResolvedValue(false);
  mocks.loadOfflineVaultMetadata.mockResolvedValue(null);
  mocks.autenticarPorCpf.mockImplementation(async () => {
    setSession(profile);
    return { profile, offlineGrant: "UNAVAILABLE" };
  });
});

afterEach(() => {
  cleanup();
  clearSession();
  sessionStorage.clear();
  vi.clearAllMocks();
  vi.unstubAllGlobals();
});

describe("App direct CPF authentication notice", () => {
  it("keeps a generic offline-cache status after the login screen unmounts and the app reloads", async () => {
    const assign = vi.fn();
    vi.stubGlobal("location", {
      assign,
      hash: "",
      href: "http://localhost/home",
      origin: "http://localhost",
      pathname: "/home",
      protocol: "http:",
      search: "",
    });
    const user = userEvent.setup();
    const firstApp = render(<App />);

    await user.type(await screen.findByRole("textbox", { name: "CPF" }), "11144477735");
    await user.click(screen.getByRole("button", { name: "Entrar" }));

    expect(await screen.findByTestId("authenticated-home"))
      .toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Entrar no sistema" }))
      .not.toBeInTheDocument();
    expect(assign).toHaveBeenCalledWith("/");

    const persistedValues = Array.from(
      { length: sessionStorage.length },
      (_, index) => sessionStorage.getItem(sessionStorage.key(index) ?? ""),
    ).join("\\n");
    expect(persistedValues).not.toContain("11144477735");
    expect(persistedValues).not.toContain(profile.nome);
    expect(persistedValues).not.toContain(profile.colaboradorId);

    firstApp.unmount();
    clearSession();
    setSession(profile);
    render(<App />);

    expect(await screen.findByRole("status")).toHaveTextContent(
      "Acesso realizado. O acesso offline deste dispositivo não foi atualizado.",
    );
    expect(screen.getByTestId("authenticated-home")).toBeInTheDocument();
  });
});
