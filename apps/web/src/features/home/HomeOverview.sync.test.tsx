// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { HomeData } from "./useHomeData";
import { HomeOverview } from "./HomeOverview";

const syncStatus = vi.hoisted(() => ({
  snapshot: {
    status: "REVIEW" as const,
    isOnline: true,
    pendingCount: 2,
    syncingCount: 1,
    errorCount: 4,
    conflictCount: 3,
    reviewCount: 5,
    reviewReason: "Contexto canônico necessário",
    lastSyncCompletedAt: "2026-07-23T12:00:00.000Z",
    lastSyncError: null,
    isLoading: false,
  },
  refresh: vi.fn(),
}));

vi.mock("../../lib/sync/useSyncStatus", () => ({
  useSyncStatus: () => syncStatus,
}));

vi.mock("./ObraFocusCard", () => ({
  ObraFocusCard: () => <section aria-label="Obra ativa" />,
}));

vi.mock("./FinanceHomeCard", () => ({
  FinanceHomeCard: () => <section>Financeiro</section>,
}));

vi.mock("./MensagensCard", () => ({
  MensagensCard: () => <section>Mensagens</section>,
}));

vi.mock("./TimeCard", () => ({
  TimeCard: () => <section>Equipe</section>,
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function renderOverview() {
  const obra: HomeData["focusedObra"] = {
    id: "OBRA:1",
    nome: "Obra real",
    codigoContrato: "OBRA-1",
    cliente: null,
    status: "ATIVA",
    cidade: "São Paulo",
    uf: "SP",
    rodovia: "SP-001",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    updatedAt: "2026-07-23T12:00:00.000Z",
  };
  const data: HomeData = {
    obras: [obra],
    focusedObraId: "OBRA:1",
    focusedObra: obra,
    setFocusedObraId: vi.fn(),
    snapshots: [],
    events: [],
    latestRdo: null,
    isLoading: false,
    hasConfirmedRemoteHydration: true,
    dataUpdatedAt: "2026-07-23T12:00:00.000Z",
    reload: vi.fn(),
  };

  return render(
    <MemoryRouter>
      <HomeOverview
        data={data}
        moreCard={<section>Mais</section>}
      />
    </MemoryRouter>,
  );
}

describe("HomeOverview sync truth", () => {
  it("shows device-wide queue and exception totals from persisted sync state", () => {
    renderOverview();

    const register = screen.getByRole("region", {
      name: "Exceções de sincronização",
    });

    expect(register).toHaveTextContent("3");
    expect(register).toHaveTextContent("4");
    expect(register).toHaveTextContent("5");
    expect(register).toHaveTextContent("Contexto canônico necessário");
  });

  it("places sync exceptions before the focused worksite", () => {
    renderOverview();

    const register = screen.getByRole("region", {
      name: "Exceções de sincronização",
    });
    const worksite = screen.getByRole("region", {
      name: "Obra ativa",
    });

    expect(
      register.compareDocumentPosition(worksite) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });
});
