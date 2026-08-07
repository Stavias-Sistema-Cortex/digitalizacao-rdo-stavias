// @vitest-environment jsdom

import {
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import {
  afterEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import type { SyncStatusSnapshot } from "../lib/sync/useSyncStatus";

const syncStatusMocks = vi.hoisted(() => ({
  refresh: vi.fn(),
  useSyncStatus: vi.fn(),
}));

vi.mock("../lib/sync/useSyncStatus", async (importOriginal) => {
  const original =
    await importOriginal<
      typeof import("../lib/sync/useSyncStatus")
    >();

  return {
    ...original,
    useSyncStatus: syncStatusMocks.useSyncStatus,
  };
});

import { SyncStatusBanner } from "./SyncStatusBanner";

function snapshot(
  overrides: Partial<SyncStatusSnapshot>,
): SyncStatusSnapshot {
  return {
    status: "PENDING",
    isOnline: true,
    pendingCount: 1,
    syncingCount: 0,
    errorCount: 0,
    conflictCount: 0,
    reviewCount: 0,
    insistindoCount: 0,
    reenviaveisCount: 0,
    travadasCount: 0,
    travaMotivo: null,
    reviewReason: null,
    lastSyncCompletedAt: "2026-08-07T12:00:50.000Z",
    lastSyncError: null,
    isLoading: false,
    ...overrides,
  };
}

function renderComSnapshot(
  overrides: Partial<SyncStatusSnapshot>,
): void {
  syncStatusMocks.useSyncStatus.mockReturnValue({
    snapshot: snapshot(overrides),
    refresh: syncStatusMocks.refresh,
  });
  render(<SyncStatusBanner />);
  fireEvent.click(
    screen.getByRole("button", { name: /^Sincronização: / }),
  );
}

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

/*
 * A tarja amarela que não morria. A fila local marcava 1, a tela prometia que o
 * sistema tentaria sincronizar automaticamente, e a aba de rede não registrava
 * um único push — porque o motor de envio descarta a linha travada antes de
 * qualquer requisição existir. Prometer envio para o que ninguém vai buscar é a
 * parte cara: manda quem está em campo esperar por uma janela que nunca vem.
 */
describe("pendência que o envio nunca vai buscar", () => {
  it("para de prometer envio automático e diz o impasse", () => {
    renderComSnapshot({
      travadasCount: 1,
      travaMotivo:
        "Um RDO espera o contexto da obra, que o servidor ainda não devolveu.",
    });

    expect(
      screen.getByRole("button", {
        name: "Sincronização: Sincronização parada",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(/1 alteração não vai subir sozinha/),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        /Um RDO espera o contexto da obra, que o servidor ainda não devolveu\./,
      ),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(
        /O sistema tentará sincronizar automaticamente/,
      ),
    ).not.toBeInTheDocument();
  });

  /*
   * A promessa continua verdadeira para quem ela descreve: a linha que o motor
   * de fato vai buscar na próxima janela.
   */
  it("mantém a promessa para a pendência que ainda tem janela", () => {
    renderComSnapshot({ travadasCount: 0 });

    expect(
      screen.getByRole("button", {
        name: "Sincronização: Aguardando sincronização",
      }),
    ).toBeInTheDocument();
    expect(
      screen.getByText(
        /1 alteração está pendente\. O sistema tentará sincronizar automaticamente\./,
      ),
    ).toBeInTheDocument();
  });

  it("o impasse fala mais alto que a insistência", () => {
    renderComSnapshot({
      pendingCount: 2,
      insistindoCount: 1,
      travadasCount: 2,
      travaMotivo:
        "Duas alterações esperam uma pela outra, e nenhuma consegue subir primeiro.",
    });

    expect(
      screen.getByText(/2 alterações não vão subir sozinhas/),
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/vêm tentando subir/),
    ).not.toBeInTheDocument();
  });
});
