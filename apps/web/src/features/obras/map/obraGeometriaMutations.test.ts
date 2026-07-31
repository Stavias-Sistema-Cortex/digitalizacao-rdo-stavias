import { beforeEach, describe, expect, it, vi } from "vitest";

const commitLocalMutation = vi.hoisted(() => vi.fn());
const sessionState = vi.hoisted(() => ({
  session: {
    colaboradorId: "10000000-0000-4000-8000-000000000001",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [] as string[],
    expiraEm: "2099-01-01T00:00:00.000Z",
  } as Record<string, unknown> | null,
}));
const geometriaLocal = vi.hoisted(() => ({
  registro: null as Record<string, unknown> | null,
}));

vi.mock("../../../lib/sync/localMutationCoordinator", () => ({
  commitLocalMutation,
}));
vi.mock("../../../lib/db/syncStateRepository", () => ({
  getSyncState: () =>
    Promise.resolve({
      deviceId: "20000000-0000-4000-8000-000000000002",
      usuarioId: "10000000-0000-4000-8000-000000000001",
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    }),
  updateSyncState: vi.fn(() => Promise.resolve()),
}));
vi.mock("../../auth/authSession", () => ({
  getSession: () => sessionState.session,
}));
vi.mock("./obraGeoCacheRepository", () => ({
  lerGeometriaLocal: () => Promise.resolve(geometriaLocal.registro),
}));

const {
  encerrarGeometria,
  registrarPontoDeCampo,
  registrarTrechoDesenhado,
} = await import("./obraGeometriaMutations");

function ultimaMutacao(): Record<string, unknown> {
  return commitLocalMutation.mock.calls.at(-1)?.[0] as Record<string, unknown>;
}

beforeEach(() => {
  commitLocalMutation.mockReset();
  commitLocalMutation.mockResolvedValue(undefined);
  geometriaLocal.registro = null;
  sessionState.session = {
    colaboradorId: "10000000-0000-4000-8000-000000000001",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: "2099-01-01T00:00:00.000Z",
  };
});

describe("registrarTrechoDesenhado", () => {
  it("enfileira o trecho pelo outbox canônico com a linha realmente marcada", async () => {
    const registro = await registrarTrechoDesenhado({
      obraId: "obra-1",
      objetoId: "obra-1",
      pontos: [
        { lat: -22.439459, lng: -47.567267 },
        { lat: -22.449263, lng: -47.558866 },
      ],
    });

    const mutacao = ultimaMutacao();
    expect(mutacao.entityType).toBe("GEOMETRIA_OBRA");
    expect(mutacao.transportOperation).toBe("REGISTRAR_GEOMETRIA_OBRA");
    expect(mutacao.operation).toBe("CREATE");
    expect(mutacao.eventType).toBe("GEOMETRIA_CRIADA");
    expect(mutacao.baseVersion).toBeNull();
    expect(mutacao.expectedPrincipalSnapshot).toBeNull();
    expect(mutacao.nextSnapshot).toMatchObject({
      obraId: "obra-1",
      categoria: "TRECHO",
      geometry: {
        type: "LineString",
        coordinates: [
          [-47.567267, -22.439459],
          [-47.558866, -22.449263],
        ],
      },
    });
    expect(registro.syncStatus).toBe("PENDING_SYNC");
    expect(registro.status).toBe("ATIVA");
  });

  it("grava o registro principal no store de geometrias", async () => {
    await registrarTrechoDesenhado({
      obraId: "obra-1",
      objetoId: "obra-1",
      pontos: [
        { lat: -22.43, lng: -47.56 },
        { lat: -22.44, lng: -47.55 },
      ],
    });

    const write = ultimaMutacao().write as () => Array<{
      store: string;
      principal?: boolean;
    }>;
    const plano = write();
    expect(plano).toHaveLength(1);
    expect(plano[0].store).toBe("obra_geometrias");
    expect(plano[0].principal).toBe(true);
  });

  it("relaciona a geometria à obra e ao objeto ontológico representado", async () => {
    await registrarTrechoDesenhado({
      obraId: "obra-1",
      objetoId: "obra-1",
      pontos: [
        { lat: -22.43, lng: -47.56 },
        { lat: -22.44, lng: -47.55 },
      ],
    });

    expect(ultimaMutacao().relatedEntities).toEqual([
      { tipo: "OBRA", id: "obra-1" },
      { tipo: "TRECHO", id: "obra-1" },
    ]);
  });

  it("recusa um trecho com um único extremo", async () => {
    await expect(
      registrarTrechoDesenhado({
        obraId: "obra-1",
        objetoId: "obra-1",
        pontos: [{ lat: -22.43, lng: -47.56 }],
      }),
    ).rejects.toThrow(/ponto inicial e o final/);
    expect(commitLocalMutation).not.toHaveBeenCalled();
  });

  it("exige sessão válida antes de tocar na fila", async () => {
    sessionState.session = null;

    await expect(
      registrarTrechoDesenhado({
        obraId: "obra-1",
        objetoId: "obra-1",
        pontos: [
          { lat: -22.43, lng: -47.56 },
          { lat: -22.44, lng: -47.55 },
        ],
      }),
    ).rejects.toThrow(/Sessão válida obrigatória/);
    expect(commitLocalMutation).not.toHaveBeenCalled();
  });
});

describe("registrarPontoDeCampo", () => {
  it("usa a operação de campo e carimba a origem CAPTURA_CAMPO", async () => {
    const registro = await registrarPontoDeCampo({
      obraId: "obra-1",
      objetoTipo: "RDO",
      objetoId: "rdo-1",
      latitude: -22.4394591,
      longitude: -47.5672673,
      precisaoM: 4.5,
    });

    const mutacao = ultimaMutacao();
    expect(mutacao.transportOperation).toBe("REGISTRAR_GEOMETRIA_CAMPO");
    expect(registro.fonte).toBe("CAPTURA_CAMPO");
    expect(registro.categoria).toBe("PONTO_OPERACIONAL");
    expect(registro.geometry).toEqual({
      type: "Point",
      coordinates: [-47.567267, -22.439459],
    });
    expect(registro.properties.precisaoM).toBe(4.5);
  });

  it("preserva a ausência de precisão em vez de inventar zero", async () => {
    const registro = await registrarPontoDeCampo({
      obraId: "obra-1",
      objetoTipo: "RDO",
      objetoId: "rdo-1",
      latitude: -22.43,
      longitude: -47.56,
    });

    expect(registro.properties.precisaoM).toBeNull();
  });

  it("recusa coordenada fora do intervalo geográfico", async () => {
    await expect(
      registrarPontoDeCampo({
        obraId: "obra-1",
        objetoTipo: "RDO",
        objetoId: "rdo-1",
        latitude: 120,
        longitude: -47.56,
      }),
    ).rejects.toThrow(/intervalo geográfico/);
    expect(commitLocalMutation).not.toHaveBeenCalled();
  });
});

describe("encerrarGeometria", () => {
  it("encerra a vigência com motivo e versão base confirmada", async () => {
    geometriaLocal.registro = {
      id: "geo-1",
      ownerId: "10000000-0000-4000-8000-000000000001",
      obraId: "obra-1",
      categoria: "TRECHO",
      objetoTipo: "TRECHO",
      objetoId: "obra-1",
      geometry: { type: "LineString", coordinates: [] },
      properties: {},
      fonte: "GESTAO_MAPA",
      status: "ATIVA",
      validoDesde: "2026-03-01T00:00:00.000Z",
      validoAte: null,
      versao: 3,
      syncStatus: "SYNCED",
      fetchedAt: "2026-03-01T00:00:00.000Z",
      updatedAt: "2026-03-01T00:00:00.000Z",
    };

    const registro = await encerrarGeometria("geo-1", "Trecho concluído");

    const mutacao = ultimaMutacao();
    expect(mutacao.transportOperation).toBe("ENCERRAR_GEOMETRIA_OBRA");
    expect(mutacao.operation).toBe("TRANSITION");
    expect(mutacao.baseVersion).toBe(3);
    expect(mutacao.nextSnapshot).toMatchObject({ motivo: "Trecho concluído" });
    expect(registro.status).toBe("ENCERRADA");
    expect(registro.validoAte).not.toBeNull();
  });

  it("não encerra uma geometria que o servidor ainda não confirmou", async () => {
    geometriaLocal.registro = {
      id: "geo-2",
      ownerId: "10000000-0000-4000-8000-000000000001",
      obraId: "obra-1",
      categoria: "TRECHO",
      objetoTipo: "TRECHO",
      objetoId: "obra-1",
      geometry: { type: "LineString", coordinates: [] },
      properties: {},
      fonte: "GESTAO_MAPA",
      status: "ATIVA",
      validoDesde: "2026-03-01T00:00:00.000Z",
      validoAte: null,
      versao: 0,
      syncStatus: "PENDING_SYNC",
      fetchedAt: null,
      updatedAt: "2026-03-01T00:00:00.000Z",
    };

    await expect(encerrarGeometria("geo-2", "Trecho concluído")).rejects.toThrow(
      /ainda não foi confirmada/,
    );
    expect(commitLocalMutation).not.toHaveBeenCalled();
  });

  it("exige motivo declarado", async () => {
    geometriaLocal.registro = {
      id: "geo-3",
      ownerId: "10000000-0000-4000-8000-000000000001",
      obraId: "obra-1",
      categoria: "TRECHO",
      objetoTipo: "TRECHO",
      objetoId: "obra-1",
      geometry: { type: "LineString", coordinates: [] },
      properties: {},
      fonte: "GESTAO_MAPA",
      status: "ATIVA",
      validoDesde: "2026-03-01T00:00:00.000Z",
      validoAte: null,
      versao: 1,
      syncStatus: "SYNCED",
      fetchedAt: "2026-03-01T00:00:00.000Z",
      updatedAt: "2026-03-01T00:00:00.000Z",
    };

    await expect(encerrarGeometria("geo-3", "   ")).rejects.toThrow(
      /Motivo do encerramento/,
    );
    expect(commitLocalMutation).not.toHaveBeenCalled();
  });

  it("recusa encerrar geometria desconhecida no dispositivo", async () => {
    await expect(encerrarGeometria("inexistente", "motivo")).rejects.toThrow(
      /não encontrada neste dispositivo/,
    );
  });
});
