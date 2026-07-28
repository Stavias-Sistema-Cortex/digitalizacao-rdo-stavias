import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  current: "session-a",
  buscarObrasArquivadas: vi.fn(),
  mergeObraLocal: vi.fn(),
}));

vi.mock("./homeApi", () => ({
  buscarObrasArquivadas: mocks.buscarObrasArquivadas,
  buscarObrasRelacionadas: vi.fn(),
  buscarHistoricoPrevisao: vi.fn(),
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  mergeObraLocal: mocks.mergeObraLocal,
}));

vi.mock("../../lib/sync/syncSession", () => ({
  captureOnlineSyncSession: () => ({
    fingerprint: mocks.current,
    userId: "user-a",
  }),
  assertSyncSession: (guard: { fingerprint: string }) => {
    if (guard.fingerprint !== mocks.current) {
      throw new Error("A sessão mudou durante a sincronização.");
    }
  },
}));

import { hydrateObrasArquivadas } from "./homeHydration";

describe("archived worksite hydration session boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.current = "session-a";
  });

  it("does not persist an Alfa trash response after the session changes", async () => {
    mocks.buscarObrasArquivadas.mockImplementationOnce(async () => {
      mocks.current = "session-b";
      return [{
        id: "obra-1",
        nome: "Obra antiga",
        codigoContrato: "CTR-1",
        arquivadoEm: "2026-07-28T13:00:00.000Z",
      }];
    });

    await expect(hydrateObrasArquivadas()).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(mocks.mergeObraLocal).not.toHaveBeenCalled();
  });
});
