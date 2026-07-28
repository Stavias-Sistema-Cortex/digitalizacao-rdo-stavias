// @vitest-environment jsdom

import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  alfa: false,
  hydrateObrasArquivadas: vi.fn(),
  hydrateObrasRelacionadas: vi.fn(),
  listObrasLocais: vi.fn(),
}));

vi.mock("../auth/authSession", () => ({
  getSession: () => mocks.alfa
    ? {
        colaboradorId: "00000000-0000-4000-8000-000000000001",
        papelAcesso: "ALFA",
        escopoGlobal: true,
        obraIds: [],
        expiraEm: "2099-01-01T00:00:00.000Z",
      }
    : {
        colaboradorId: "00000000-0000-4000-8000-000000000002",
        papelAcesso: "BETA",
        escopoGlobal: false,
        obraIds: ["obra-1"],
        expiraEm: "2099-01-01T00:00:00.000Z",
      },
  isAlfa: (session: { papelAcesso?: string } | null) =>
    session?.papelAcesso === "ALFA",
}));

vi.mock("./homeHydration", () => ({
  hydrateHistoricoObra: vi.fn(),
  hydrateObrasArquivadas: mocks.hydrateObrasArquivadas,
  hydrateObrasRelacionadas: mocks.hydrateObrasRelacionadas,
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  filterOperationalObras: (
    obras: Array<{ arquivadoEm?: string | null }>,
  ) => obras.filter((obra) => obra.arquivadoEm == null),
  listObrasLocais: mocks.listObrasLocais,
}));

vi.mock("../../lib/db/previsaoSnapshotRepository", () => ({
  listSnapshotsByObra: vi.fn(),
}));

vi.mock("../../lib/db/operationalEventRepository", () => ({
  listOperationalEventsForObra: vi.fn(),
}));

vi.mock("../../lib/db/rdoRepository", () => ({
  listLocalRdos: vi.fn(),
}));

vi.mock("./lastAccessedObra", () => ({
  colaboradorStorageKey: () => "anonymous",
  getLastAccessedObraId: () => null,
  setLastAccessedObraId: vi.fn(),
}));

import { useHomeData } from "./useHomeData";

beforeEach(() => {
  vi.clearAllMocks();
  mocks.alfa = false;
  mocks.hydrateObrasArquivadas.mockResolvedValue(0);
  mocks.hydrateObrasRelacionadas.mockResolvedValue(0);
  mocks.listObrasLocais.mockResolvedValue([]);
});

function confirmedHydration(
  result: { current: ReturnType<typeof useHomeData> },
): boolean | undefined {
  return (
    result.current as ReturnType<typeof useHomeData> & {
      hasConfirmedRemoteHydration?: boolean;
    }
  ).hasConfirmedRemoteHydration;
}

describe("useHomeData remote hydration truth", () => {
  it("keeps cached data local when the remote hydration fails", async () => {
    mocks.hydrateObrasRelacionadas.mockRejectedValueOnce(
      new Error("API indisponível"),
    );

    const { result } = renderHook(() => useHomeData());

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(confirmedHydration(result)).toBe(false);
  });

  it("confirms synced data only after remote hydration succeeds", async () => {
    mocks.hydrateObrasRelacionadas.mockResolvedValueOnce(0);

    const { result } = renderHook(() => useHomeData());

    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(confirmedHydration(result)).toBe(true);
  });

  it("hydrates Alfa trash before reading an includeArchived clean cache", async () => {
    mocks.alfa = true;

    const { result } = renderHook(() =>
      useHomeData({ includeArchived: true })
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(mocks.hydrateObrasRelacionadas).toHaveBeenCalledOnce();
    expect(mocks.hydrateObrasArquivadas).toHaveBeenCalledOnce();
    expect(mocks.listObrasLocais).toHaveBeenCalledWith({
      includeArchived: true,
    });
    expect(confirmedHydration(result)).toBe(true);
  });

  it("never requests or mounts the archived catalog for Beta", async () => {
    mocks.listObrasLocais.mockResolvedValueOnce([{
      id: "archived",
      codigoContrato: "CTR-ARQ",
      nome: "Obra arquivada",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "INATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      arquivadoEm: "2026-07-28T13:00:00.000Z",
      updatedAt: "2026-07-28T13:00:00.000Z",
    }]);
    const { result } = renderHook(() =>
      useHomeData({ includeArchived: true })
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(mocks.hydrateObrasArquivadas).not.toHaveBeenCalled();
    expect(mocks.listObrasLocais).toHaveBeenCalledWith({
      includeArchived: false,
    });
    expect(result.current.obras).toEqual([]);
  });
});
