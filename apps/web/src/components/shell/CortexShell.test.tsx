// @vitest-environment jsdom

import type { ReactNode } from "react";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  encerrarSessao: vi.fn(),
}));

vi.mock("../SyncStatusBanner", () => ({
  SyncStatusBanner: () => <span>Sincronização ao vivo</span>,
}));

vi.mock("../../features/auth/authSession", () => ({
  getSession: () => ({
    colaboradorId: "00000000-0000-4000-8000-000000000001",
    nome: "Ana da Obra",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: ["00000000-0000-4000-8000-000000000002"],
    expiraEm: "2099-01-01T00:00:00.000Z",
  }),
  isAlfa: () => false,
}));

vi.mock("../../features/auth/authService", () => ({
  encerrarSessao: mocks.encerrarSessao,
}));

import { CortexShell } from "./CortexShell";
import { CortexShellHeaderControls } from "./CortexShellChromeContext";

function HeaderSlot({ children }: { children: ReactNode }) {
  return <header data-testid="page-header">{children}</header>;
}

function ShellWithHeaderSlot() {
  return (
    <CortexShell active="home">
      <HeaderSlot>
        <CortexShellHeaderControls />
      </HeaderSlot>
      <main>Conteúdo da página</main>
    </CortexShell>
  );
}

function LocationProbe() {
  const location = useLocation();
  return <output>{location.pathname}</output>;
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  localStorage.clear();
});

describe("CortexShell chrome", () => {
  it("mantém a semântica acessível ao recolher e expandir o menu", async () => {
    const user = userEvent.setup();

    render(
      <MemoryRouter>
        <ShellWithHeaderSlot />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole("button", { name: "Recolher menu" }));
    expect(screen.getByRole("button", { name: "Expandir menu" })).toHaveAttribute(
      "aria-expanded",
      "false",
    );

    await user.click(screen.getByRole("button", { name: "Expandir menu" }));
    expect(screen.getByRole("button", { name: "Recolher menu" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
  });

  it("posiciona a alavanca fora da área rolável da sidebar", () => {
    render(
      <MemoryRouter>
        <ShellWithHeaderSlot />
      </MemoryRouter>,
    );

    const toggle = screen.getByRole("button", { name: "Recolher menu" });
    const sidebar = document.querySelector(".cortex-sidebar");

    expect(toggle.parentElement).toHaveClass("cortex-shell");
    expect(sidebar).not.toContainElement(toggle);
  });

  it("fornece os controles globais apenas pelo slot descendente do cabeçalho", () => {
    render(
      <MemoryRouter>
        <ShellWithHeaderSlot />
      </MemoryRouter>,
    );

    const header = screen.getByTestId("page-header");
    expect(within(header).getByRole("group", {
      name: "Controles globais",
    })).toHaveTextContent("Sincronização ao vivo");
    expect(within(header).getByRole("button", { name: "AO" }))
      .toHaveTextContent("AO");
    expect(document.querySelector(".floating-controls")).toBeNull();
  });

  it("mantém o perfil, a segurança e o erro de logout no slot do cabeçalho", async () => {
    const user = userEvent.setup();
    mocks.encerrarSessao.mockRejectedValueOnce(new Error("offline"));

    render(
      <MemoryRouter>
        <ShellWithHeaderSlot />
        <LocationProbe />
      </MemoryRouter>,
    );

    await user.click(screen.getByRole("button", { name: "AO" }));
    const profile = screen.getByRole("dialog", {
      name: "Opções do perfil",
    });
    expect(within(profile).getByText("Ana da Obra")).toBeVisible();

    await user.click(within(profile).getByRole("button", {
      name: "Segurança do dispositivo",
    }));
    expect(screen.getByRole("status")).toHaveTextContent("/seguranca");

    await user.click(screen.getByRole("button", { name: "AO" }));
    await user.click(screen.getByRole("button", { name: "Sair" }));

    await waitFor(() => expect(mocks.encerrarSessao).toHaveBeenCalledOnce());
    expect(screen.getByRole("alert")).toHaveTextContent(
      "Não foi possível encerrar a sessão no servidor.",
    );
  });
});
