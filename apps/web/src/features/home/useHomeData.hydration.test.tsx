// @vitest-environment jsdom

import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  alfa: false,
  hydrateHistoricoObra: vi.fn(),
  hydrateObrasArquivadas: vi.fn(),
  hydrateObrasRelacionadas: vi.fn(),
  listLocalRdos: vi.fn(),
  listObrasLocais: vi.fn(),
  listOperationalEventsForObra: vi.fn(),
  listSnapshotsByObra: vi.fn(),
}));

vi.mock("../auth/authSession", () => ({
  getSession: () => mocks.alfa
    ? {
        colaboradorId: "00000000-0000-4000-8000-000000000001",
        nome: "Administradora Alfa",
        papelAcesso: "ALFA",
        escopoGlobal: true,
        obraIds: [],
        expiraEm: "2099-01-01T00:00:00.000Z",
      }
    : {
        colaboradorId: "00000000-0000-4000-8000-000000000002",
        nome: "Operadora Beta",
        papelAcesso: "BETA",
        escopoGlobal: false,
        obraIds: ["obra-1"],
        expiraEm: "2099-01-01T00:00:00.000Z",
      },
  isAlfa: (session: { papelAcesso?: string } | null) =>
    session?.papelAcesso === "ALFA",
}));

vi.mock("./homeHydration", () => ({
  hydrateHistoricoObra: mocks.hydrateHistoricoObra,
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
  listSnapshotsByObra: mocks.listSnapshotsByObra,
}));

vi.mock("../../lib/db/operationalEventRepository", () => ({
  listOperationalEventsForObra:
    mocks.listOperationalEventsForObra,
}));

vi.mock("../../lib/db/rdoRepository", () => ({
  listLocalRdos: mocks.listLocalRdos,
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
  mocks.hydrateHistoricoObra.mockResolvedValue(0);
  mocks.hydrateObrasArquivadas.mockResolvedValue(0);
  mocks.hydrateObrasRelacionadas.mockResolvedValue(0);
  mocks.listLocalRdos.mockResolvedValue([]);
  mocks.listObrasLocais.mockResolvedValue([]);
  mocks.listOperationalEventsForObra.mockResolvedValue([]);
  mocks.listSnapshotsByObra.mockResolvedValue([]);
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

function cachedWorksite() {
  return {
    id: "obra-1",
    codigoContrato: "CTR-001",
    nome: "Obra do escopo anterior",
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    status: "ATIVA",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    arquivadoEm: null,
    updatedAt: "2026-07-28T13:00:00.000Z",
  };
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

  it("clears the old catalog when Alfa changes to Beta during related hydration", async () => {
    mocks.alfa = true;
    mocks.listObrasLocais.mockResolvedValueOnce([{
      id: "obra-from-old-scope",
      codigoContrato: "CTR-OLD",
      nome: "Obra do escopo anterior",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      arquivadoEm: null,
      updatedAt: "2026-07-28T13:00:00.000Z",
    }]);
    mocks.hydrateObrasRelacionadas.mockImplementationOnce(async () => {
      mocks.alfa = false;
      return 0;
    });

    const { result } = renderHook(() =>
      useHomeData({ includeArchived: true })
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(mocks.hydrateObrasArquivadas).not.toHaveBeenCalled();
    expect(mocks.listObrasLocais).not.toHaveBeenCalled();
    expect(result.current.obras).toEqual([]);
    expect(result.current.snapshots).toEqual([]);
    expect(result.current.events).toEqual([]);
    expect(result.current.latestRdo).toBeNull();
    expect(confirmedHydration(result)).toBe(false);
  });

  it("hydrates Alfa Trash even when related-worksite hydration fails", async () => {
    mocks.alfa = true;
    mocks.hydrateObrasRelacionadas.mockRejectedValueOnce(
      new Error("Catálogo relacionado indisponível."),
    );

    const { result } = renderHook(() =>
      useHomeData({ includeArchived: true })
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(mocks.hydrateObrasArquivadas).toHaveBeenCalledOnce();
    expect(mocks.listObrasLocais).toHaveBeenCalledWith({
      includeArchived: true,
    });
    expect(confirmedHydration(result)).toBe(false);
  });

  it("does not mount an Alfa cache response when the session becomes Beta during the local read", async () => {
    mocks.alfa = true;
    mocks.listObrasLocais.mockImplementationOnce(async () => {
      mocks.alfa = false;
      return [{
        id: "archived-from-alfa",
        codigoContrato: "CTR-ARQ",
        nome: "Obra arquivada Alfa",
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
      }];
    });

    const { result } = renderHook(() =>
      useHomeData({ includeArchived: true })
    );

    await waitFor(() => expect(result.current.isLoading).toBe(false));

    expect(result.current.obras).toEqual([]);
    expect(confirmedHydration(result)).toBe(false);
  });

  it("clears catalog and details when the session changes during the local detail read", async () => {
    mocks.alfa = true;
    mocks.listObrasLocais.mockResolvedValueOnce([
      cachedWorksite(),
    ]);
    mocks.listSnapshotsByObra.mockImplementationOnce(async () => {
      mocks.alfa = false;
      return [{
        id: "snapshot-old",
        obraId: "obra-1",
        dataReferencia: "2026-07-28",
        statusExecucao: "CALCULADO",
        producaoPlanejada: 10,
        producaoRealizada: 8,
        custoRealizado: null,
        custoPrevistoFinal: null,
        receitaPrevistaFinal: 1200,
        updatedAt: "2026-07-28T13:00:00.000Z",
      }];
    });

    const { result } = renderHook(() => useHomeData());

    await waitFor(() => {
      expect(mocks.listSnapshotsByObra).toHaveBeenCalled();
    });
    await waitFor(() => expect(result.current.obras).toEqual([]));

    expect(result.current.snapshots).toEqual([]);
    expect(result.current.events).toEqual([]);
    expect(result.current.latestRdo).toBeNull();
    expect(mocks.hydrateHistoricoObra).not.toHaveBeenCalled();
  });

  it("clears catalog and details when a session-closing detail read rejects", async () => {
    mocks.alfa = true;
    mocks.listObrasLocais.mockResolvedValueOnce([
      cachedWorksite(),
    ]);
    mocks.listSnapshotsByObra.mockImplementationOnce(async () => {
      mocks.alfa = false;
      throw new Error("A conexão local foi fechada.");
    });

    const { result } = renderHook(() => useHomeData());

    await waitFor(() => {
      expect(mocks.listSnapshotsByObra).toHaveBeenCalled();
    });
    await waitFor(() => expect(result.current.obras).toEqual([]));

    expect(result.current.snapshots).toEqual([]);
    expect(result.current.events).toEqual([]);
    expect(result.current.latestRdo).toBeNull();
    expect(mocks.hydrateHistoricoObra).not.toHaveBeenCalled();
  });

  it("clears local details when the session changes during history hydration", async () => {
    mocks.alfa = true;
    mocks.listObrasLocais.mockResolvedValueOnce([
      cachedWorksite(),
    ]);
    mocks.listSnapshotsByObra.mockResolvedValueOnce([{
      id: "snapshot-local",
      obraId: "obra-1",
      dataReferencia: "2026-07-28",
      statusExecucao: "CALCULADO",
      producaoPlanejada: 10,
      producaoRealizada: 8,
      custoRealizado: null,
      custoPrevistoFinal: null,
      receitaPrevistaFinal: 1200,
      updatedAt: "2026-07-28T13:00:00.000Z",
    }]);
    mocks.hydrateHistoricoObra.mockImplementationOnce(async () => {
      mocks.alfa = false;
      return 0;
    });

    const { result } = renderHook(() => useHomeData());

    await waitFor(() => {
      expect(mocks.hydrateHistoricoObra).toHaveBeenCalledWith(
        "obra-1",
      );
    });
    await waitFor(() => expect(result.current.obras).toEqual([]));

    expect(result.current.snapshots).toEqual([]);
    expect(result.current.events).toEqual([]);
    expect(result.current.latestRdo).toBeNull();
    expect(confirmedHydration(result)).toBe(false);
  });
});
