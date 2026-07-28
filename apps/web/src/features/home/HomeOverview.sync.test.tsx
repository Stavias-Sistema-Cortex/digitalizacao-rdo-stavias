// @vitest-environment jsdom

import {
  cleanup,
  render,
  screen,
  within,
} from "@testing-library/react";
import { MemoryRouter } from "react-router";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { SyncStatusSnapshot } from "../../lib/sync/useSyncStatus";
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
  } as SyncStatusSnapshot,
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

vi.mock("./TimeCard", () => ({
  TimeCard: () => <section>Equipe</section>,
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
  Object.assign(syncStatus.snapshot, {
    status: "REVIEW",
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
  } satisfies SyncStatusSnapshot);
});

function renderOverview(
  events: HomeData["events"] = [],
) {
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
    events,
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
  it("shows each global sync fact once and preserves simultaneous exceptions", () => {
    renderOverview([{
      id: "event-1",
      type: "RDO_EDITADO",
      principalEntity: {
        tipo: "RDO",
        id: "rdo-1",
        nome: "RDO-1",
      },
      principalEntityKey: "RDO:rdo-1",
      relatedEntities: [],
      obraId: "OBRA:1",
      rdoId: "rdo-1",
      colaboradorId: null,
      occurredAt: "2026-07-23T12:00:00.000Z",
      syncedAt: null,
      origin: "OFFLINE",
      responsibleUserId: null,
      responsibleUserName: null,
      payload: {},
      syncStatus: "PENDING_SYNC",
      schemaVersion: 13,
    }]);

    const register = screen.getByRole("region", {
      name: "Exceções de sincronização",
    });

    expect(within(register).getAllByText("Fila local")).toHaveLength(1);
    expect(within(register).getAllByText("Conflitos")).toHaveLength(1);
    expect(within(register).getAllByText("Última sincronização")).toHaveLength(1);
    expect(register).toHaveTextContent("Contexto canônico necessário");
    const exceptionFacts = register.querySelector(
      ".home-exception-register__facts",
    );
    expect(exceptionFacts).not.toBeNull();
    expect(exceptionFacts).toHaveTextContent("Falhas");
    expect(exceptionFacts).toHaveTextContent("4");
    expect(exceptionFacts).toHaveTextContent("Revisões");
    expect(exceptionFacts).toHaveTextContent("5");
    expect(exceptionFacts).toHaveTextContent("Aguardando nesta obra");
    expect(exceptionFacts).toHaveTextContent("1");
    expect(exceptionFacts).not.toHaveTextContent("Fila local");
    expect(exceptionFacts).not.toHaveTextContent("Conflitos");
    expect(within(register).queryByText(/Atualização local/)).toBeNull();
    expect(
      within(register).queryByRole("link", { name: "Ver memória" }),
    ).toBeNull();
    expect(
      screen.getByText(
        "Histórico local não equivale à confirmação do servidor.",
      ),
    ).toBeVisible();
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

  it("keeps a long failure separate from the sync facts for assistive technology", () => {
    const failure =
      "Colaborador não está ativo e vinculado à obra do RDO " +
      "porque o identificador operacional precisa ser reconciliado.";
    Object.assign(syncStatus.snapshot, {
      status: "ERROR",
      errorCount: 1,
      reviewCount: 0,
      reviewReason: null,
      lastSyncError: failure,
    } satisfies Partial<SyncStatusSnapshot>);

    renderOverview();

    const strip = screen.getByRole("region", {
      name: "Estado de sincronização",
    });
    const status = within(strip).getByRole("status");
    const facts = within(strip).getByRole("group", {
      name: "Indicadores de sincronização",
    });

    expect(status).toHaveTextContent(failure);
    expect(facts).toHaveTextContent("Fila local");
    expect(facts).toHaveTextContent("Conflitos");
    expect(facts).toHaveTextContent("Última sincronização");
    expect(facts).not.toContainElement(status);
  });
});
