// @vitest-environment jsdom

import {
  cleanup,
  render,
  screen,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { RdoLocalList } from "./RdoLocalList";

const mocks = vi.hoisted(() => ({
  listWorksites: vi.fn().mockResolvedValue([]),
}));

vi.mock("./rdoCreationContextRepository", () => ({
  listCachedAuthorizedRdoWorksites: mocks.listWorksites,
}));

vi.mock("../programacoes/ProgramacaoSemanalImport", () => ({
  ProgramacaoSemanalImport: () => null,
}));

vi.mock("../../lib/sync/useSyncStatus", () => ({
  useSyncStatus: () => ({
    snapshot: {
      status: "SYNCED",
      isOnline: true,
      pendingCount: 0,
      syncingCount: 0,
      errorCount: 0,
      conflictCount: 0,
      reviewCount: 0,
      reviewReason: null,
      lastSyncCompletedAt: "2026-07-28T12:00:00.000Z",
      lastSyncError: null,
      isLoading: false,
    },
    refresh: vi.fn(),
  }),
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("RdoLocalList filter layout contract", () => {
  it("renders the six accessible filters inside the responsive filter grid", () => {
    render(
      <RdoLocalList
        records={[]}
        events={[]}
        attachments={[]}
        isLoading={false}
        error=""
        onCreate={vi.fn()}
        onImportRdoFile={vi.fn()}
        isImporting={false}
        onOpen={vi.fn()}
        onRefresh={vi.fn()}
      />,
    );

    const region = screen.getByRole("region", {
      name: "Filtros de RDO",
    });
    const grid = region.querySelector<HTMLElement>(".rdo-filter-grid");

    expect(grid).not.toBeNull();
    expect(region).toContainElement(grid);
    expect(grid?.querySelectorAll("label")).toHaveLength(6);
    expect(grid?.querySelectorAll("input, select")).toHaveLength(6);

    const scoped = within(grid!);
    const controls = [
      scoped.getByRole("textbox", { name: "Obra" }),
      scoped.getByRole("combobox", { name: "Período" }),
      scoped.getByRole("combobox", { name: "Status" }),
      scoped.getByRole("textbox", { name: "Colaborador" }),
      scoped.getByRole("textbox", { name: "Trecho" }),
      scoped.getByRole("combobox", { name: "Sync" }),
    ];

    expect(new Set(controls).size).toBe(6);
  });
});
