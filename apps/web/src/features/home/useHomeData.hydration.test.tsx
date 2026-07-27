// @vitest-environment jsdom

import { renderHook, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  hydrateObrasRelacionadas: vi.fn(),
  listObrasLocais: vi.fn(),
}));

vi.mock("../auth/authSession", () => ({
  getSession: () => null,
}));

vi.mock("./homeHydration", () => ({
  hydrateHistoricoObra: vi.fn(),
  hydrateObrasRelacionadas: mocks.hydrateObrasRelacionadas,
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
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
});
