import { beforeEach, describe, expect, it, vi } from "vitest";

const commitLocalMutation = vi.hoisted(() => vi.fn());
const sessionState = vi.hoisted(() => ({
  session: {
    colaboradorId: "10000000-0000-4000-8000-000000000001",
    nome: "Alfa",
    papelAcesso: "ALFA" as const,
    escopoGlobal: true,
    obraIds: [] as string[],
    expiraEm: "2099-01-01T00:00:00.000Z",
  },
}));
const geometriaLocal = vi.hoisted(() => ({
  registro: null as Record<string, unknown> | null,
}));

vi.mock("../../../lib/sync/localMutationCoordinator", () => ({
  commitLocalMutation,
}));
vi.mock("../../../lib/db/syncStateRepository", () => ({
  getSyncState: () => Promise.resolve({ deviceId: "device-1" }),
  updateSyncState: () => Promise.resolve(),
}));
vi.mock("../../auth/authSession", () => ({
  getSession: () => sessionState.session,
  requireDataScope: () => ({ ownerId: "owner", scopeMaterial: "ALFA" }),
}));
vi.mock("./obraGeoCacheRepository", () => ({
  lerGeometriaLocal: () => Promise.resolve(geometriaLocal.registro),
}));

const { redesenharTrecho } = await import("./obraGeometriaMutations");

/**
 * Corrigir o traçado sem trocar a identidade do desenho.
 *
 * <p>Apagar e desenhar de novo já resolvia a linha torta, mas contava outra
 * história: o histórico via um desenho morto e outro nascido, em vez de uma
 * correção. Redesenhar mantém o mesmo registro.
 *
 * <p>O que a geometria descreve não entra nesta conversa — e o apontamento do
 * RDO menos ainda: o quilômetro mora lá, e acertar o traço no mapa não muda
 * medida nenhuma.
 */

const TRECHO_SINCRONIZADO = {
  id: "geo-1",
  ownerId: "10000000-0000-4000-8000-000000000001",
  obraId: "obra-1",
  categoria: "TRECHO",
  objetoTipo: "RDO",
  objetoId: "rdo-1",
  geometry: { type: "LineString", coordinates: [[-47.4, -22.0], [-47.3, -22.0]] },
  properties: { rodovia: "SP-330", sentido: "Sul" },
  fonte: "GESTAO_MAPA",
  status: "ATIVA",
  validoDesde: "2026-08-01T00:00:00.000Z",
  validoAte: null,
  versao: 3,
  syncStatus: "SYNCED",
  fetchedAt: null,
  updatedAt: "2026-08-01T00:00:00.000Z",
};

const NOVOS_PONTOS = [
  { lat: -22.02, lng: -47.45 },
  { lat: -22.02, lng: -47.44 },
];

beforeEach(() => {
  commitLocalMutation.mockReset();
  geometriaLocal.registro = { ...TRECHO_SINCRONIZADO };
});

describe("redesenhar um trecho", () => {
  it("troca a forma mantendo o mesmo registro", async () => {
    const proximo = await redesenharTrecho({
      featureId: "geo-1",
      pontos: NOVOS_PONTOS,
      motivo: "Traçado corrigido",
    });

    expect(proximo.id).toBe("geo-1");
    expect(proximo.geometry).toEqual({
      type: "LineString",
      coordinates: [[-47.45, -22.02], [-47.44, -22.02]],
    });
    expect(proximo.syncStatus).toBe("PENDING_SYNC");
  });

  it("sobe como atualização, sobre a versão que o servidor tem", async () => {
    await redesenharTrecho({
      featureId: "geo-1",
      pontos: NOVOS_PONTOS,
      motivo: "Traçado corrigido",
    });

    expect(commitLocalMutation).toHaveBeenCalledWith(
      expect.objectContaining({
        operation: "UPDATE",
        transportOperation: "ATUALIZAR_GEOMETRIA_OBRA",
        baseVersion: 3,
        entityId: "geo-1",
      }),
    );
  });

  /*
   * O servidor exige o motivo da alteração geográfica, e ele viaja no
   * envelope: sem isso a mutação subiria para ser recusada.
   */
  it("leva o motivo junto da forma nova", async () => {
    await redesenharTrecho({
      featureId: "geo-1",
      pontos: NOVOS_PONTOS,
      motivo: "Traçado corrigido",
    });

    const [payload] = commitLocalMutation.mock.calls.at(-1)!;
    expect(payload.nextSnapshot).toMatchObject({ motivo: "Traçado corrigido" });
  });

  it("não mexe no que a geometria descreve", async () => {
    const proximo = await redesenharTrecho({
      featureId: "geo-1",
      pontos: NOVOS_PONTOS,
      motivo: "Traçado corrigido",
    });

    expect(proximo.categoria).toBe("TRECHO");
    expect(proximo.objetoTipo).toBe("RDO");
    expect(proximo.objetoId).toBe("rdo-1");
    expect(proximo.properties).toEqual({ rodovia: "SP-330", sentido: "Sul" });
  });

  it("recusa sem motivo, que o servidor exige", async () => {
    await expect(
      redesenharTrecho({ featureId: "geo-1", pontos: NOVOS_PONTOS, motivo: "  " }),
    ).rejects.toThrow(/motivo/i);
    expect(commitLocalMutation).not.toHaveBeenCalled();
  });

  it("recusa um traçado que não é linha", async () => {
    await expect(
      redesenharTrecho({
        featureId: "geo-1",
        pontos: [{ lat: -22.02, lng: -47.45 }],
        motivo: "Traçado corrigido",
      }),
    ).rejects.toThrow(/ponto inicial e o final/i);
  });

  /*
   * Sem versão do outro lado não há o que atualizar. O caminho para o desenho
   * que nunca subiu é a lixeira, que o descarta no próprio aparelho — e a
   * mensagem precisa dizer isso, senão a pessoa fica esperando uma
   * sincronização que não resolve nada.
   */
  it("manda apagar o desenho que nunca chegou ao servidor", async () => {
    geometriaLocal.registro = { ...TRECHO_SINCRONIZADO, versao: 0 };

    await expect(
      redesenharTrecho({
        featureId: "geo-1",
        pontos: NOVOS_PONTOS,
        motivo: "Traçado corrigido",
      }),
    ).rejects.toThrow(/Apague-o e desenhe de novo/i);
    expect(commitLocalMutation).not.toHaveBeenCalled();
  });

  it("não redesenha o que já foi encerrado", async () => {
    geometriaLocal.registro = { ...TRECHO_SINCRONIZADO, status: "ENCERRADA" };

    await expect(
      redesenharTrecho({
        featureId: "geo-1",
        pontos: NOVOS_PONTOS,
        motivo: "Traçado corrigido",
      }),
    ).rejects.toThrow(/encerrado/i);
  });
});
