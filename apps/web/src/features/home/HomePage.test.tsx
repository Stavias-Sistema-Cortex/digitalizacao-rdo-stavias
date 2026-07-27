// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import { HomePage } from "./HomePage";

const homeData = vi.hoisted(() => ({
  obras: [],
  focusedObraId: null,
  focusedObra: null,
  setFocusedObraId: vi.fn(),
  snapshots: [],
  events: [],
  latestRdo: null,
  isLoading: false,
  dataUpdatedAt: null,
  reload: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: PropsWithChildren) => <>{children}</>,
}));

vi.mock("./useHomeData", () => ({
  useHomeData: () => homeData,
}));

vi.mock("./HomeOverview", () => ({
  HomeOverview: () => <div>Resumo operacional</div>,
}));

vi.mock("./memory/MemoryLedger", () => ({
  MemoryLedger: () => (
    <label>
      Pesquisa integral
      <input
        type="search"
        aria-label="Pesquisar em toda a Memória armazenada"
      />
    </label>
  ),
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderHome(route: string) {
  return render(
    <MemoryRouter initialEntries={[route]}>
      <HomePage />
    </MemoryRouter>,
  );
}

describe("HomePage", () => {
  it("mantém o workspace operacional e abre a visão geral por padrão", () => {
    renderHome("/home");

    expect(screen.getByRole("main")).toHaveAttribute(
      "data-workspace",
      "operational",
    );
    expect(screen.getByRole("heading", {
      level: 1,
      name: "Visão do empreendimento",
    })).toBeVisible();
    expect(screen.getByRole("tab", { name: "Visão geral" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("tabpanel")).toHaveAttribute(
      "id",
      "home-panel-overview",
    );
  });

  it("abre a pesquisa acessível da Memória pela rota", () => {
    renderHome("/home?tab=memory");

    expect(screen.getByRole("searchbox", {
      name: "Pesquisar em toda a Memória armazenada",
    })).toBeVisible();
  });
});
