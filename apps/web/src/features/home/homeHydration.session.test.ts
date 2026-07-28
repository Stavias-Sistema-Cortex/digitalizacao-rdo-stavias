import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  alfa: true,
  current: "session-a",
  buscarHistoricoPrevisao: vi.fn(),
  buscarObrasArquivadas: vi.fn(),
  buscarObrasRelacionadas: vi.fn(),
  mergeObraLocal: vi.fn(),
  putPrevisaoSnapshot: vi.fn(),
}));

vi.mock("./homeApi", () => ({
  buscarObrasArquivadas: mocks.buscarObrasArquivadas,
  buscarObrasRelacionadas: mocks.buscarObrasRelacionadas,
  buscarHistoricoPrevisao: mocks.buscarHistoricoPrevisao,
}));

vi.mock("../auth/authSession", () => ({
  getSession: () => ({
    papelAcesso: mocks.alfa ? "ALFA" : "BETA",
  }),
  isAlfa: (session: { papelAcesso?: string } | null) =>
    session?.papelAcesso === "ALFA",
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  mergeObraLocal: mocks.mergeObraLocal,
}));

vi.mock("../../lib/db/previsaoSnapshotRepository", () => ({
  putPrevisaoSnapshot: mocks.putPrevisaoSnapshot,
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

import {
  hydrateHistoricoObra,
  hydrateObrasArquivadas,
  hydrateObrasRelacionadas,
} from "./homeHydration";

describe("worksite hydration session boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.alfa = true;
    mocks.current = "session-a";
    mocks.buscarHistoricoPrevisao.mockResolvedValue([]);
    mocks.buscarObrasArquivadas.mockResolvedValue([]);
    mocks.buscarObrasRelacionadas.mockResolvedValue([]);
  });

  it("does not persist a related-worksite response after the session changes", async () => {
    mocks.buscarObrasRelacionadas.mockImplementationOnce(async () => {
      mocks.current = "session-b";
      return [{
        id: "obra-related",
        codigoContrato: "CTR-RELATED",
        nome: "Obra relacionada antiga",
        cliente: null,
        cidade: null,
        uf: null,
        rodovia: null,
        status: "ATIVA",
        observacoes: null,
        latitude: null,
        longitude: null,
        valorContratual: null,
        atualizadoEm: "2026-07-28T13:00:00.000Z",
      }];
    });

    await expect(hydrateObrasRelacionadas()).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(mocks.mergeObraLocal).not.toHaveBeenCalled();
  });

  it("never calls the archived endpoint for a Beta session", async () => {
    mocks.alfa = false;

    await expect(hydrateObrasArquivadas()).rejects.toThrow(
      "A Lixeira de obras está disponível apenas para perfis Alfa.",
    );
    expect(mocks.buscarObrasArquivadas).not.toHaveBeenCalled();
    expect(mocks.mergeObraLocal).not.toHaveBeenCalled();
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

  it("does not persist forecast history after the session changes", async () => {
    mocks.buscarHistoricoPrevisao.mockImplementationOnce(async () => {
      mocks.current = "session-b";
      return [{
        id: "snapshot-old-session",
        obraId: "obra-1",
        dataReferencia: "2026-07-28",
        statusExecucao: "CALCULADO",
        producaoPlanejada: 10,
        producaoRealizada: 8,
        custoRealizado: null,
        custoPrevistoFinal: null,
        receitaPrevistaFinal: 1200,
      }];
    });

    await expect(hydrateHistoricoObra("obra-1")).rejects.toThrow(
      "A sessão mudou durante a sincronização.",
    );
    expect(mocks.putPrevisaoSnapshot).not.toHaveBeenCalled();
  });
});
