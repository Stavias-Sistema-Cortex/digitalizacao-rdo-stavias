import { beforeEach, describe, expect, it, vi } from "vitest";

import type { ObraGeometriaLocalRecord } from "../../../lib/db/db.types";

const OBRA = { id: "obra-1", nome: "Intervias 202", latitude: -22.4, longitude: -47.5 };

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  listarGeometriasLocais: vi.fn(),
  contarGeometriasDaObra: vi.fn(),
  reconciliarGeometriasDoServidor: vi.fn().mockResolvedValue(undefined),
}));

vi.mock("../../../lib/api/apiClient", async (importOriginal) => ({
  ...(await importOriginal<
    typeof import("../../../lib/api/apiClient")
  >()),
  apiFetch: mocks.apiFetch,
  readResponseBody: async (response: Response) => response.json(),
  responseErrorMessage: () => "erro",
}));

vi.mock("./obraGeoCacheRepository", async (importOriginal) => ({
  ...(await importOriginal<
    typeof import("./obraGeoCacheRepository")
  >()),
  listarGeometriasLocais: mocks.listarGeometriasLocais,
  contarGeometriasDaObra: mocks.contarGeometriasDaObra,
  reconciliarGeometriasDoServidor: mocks.reconciliarGeometriasDoServidor,
}));

const { carregarMapaObra } = await import("./obraMapApi");

function respostaDoServidor(ids: string[]) {
  return {
    ok: true,
    status: 200,
    json: async () => ({
      obra: OBRA,
      features: ids.map((id) => ({
        id,
        categoria: "PONTO_OPERACIONAL",
        objetoTipo: "OBRA",
        objetoId: OBRA.id,
        geometry: { type: "Point", coordinates: [-47.56, -22.43] },
        properties: {},
        fonte: "CAPTURA_CAMPO",
        versao: 3,
        validoDesde: "2026-08-04T12:00:00.000Z",
        validoAte: null,
      })),
    }),
  } as unknown as Response;
}

function registroLocal(id: string): ObraGeometriaLocalRecord {
  return {
    id,
    ownerId: "dono",
    obraId: OBRA.id,
    categoria: "PONTO_OPERACIONAL",
    objetoTipo: "OBRA",
    objetoId: OBRA.id,
    geometry: { type: "Point", coordinates: [-47.56, -22.43] },
    properties: {},
    fonte: "CAPTURA_CAMPO",
    status: "ATIVA",
    validoDesde: "2026-08-04T12:00:00.000Z",
    validoAte: null,
    versao: 3,
    syncStatus: "SYNCED",
    fetchedAt: "2026-08-04T12:30:00.000Z",
    updatedAt: "2026-08-04T12:30:00.000Z",
  } as ObraGeometriaLocalRecord;
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.reconciliarGeometriasDoServidor.mockResolvedValue(undefined);
});

describe("leitura do mapa com rede", () => {
  /**
   * O caso que desfazia a remoção.
   *
   * Removido o último ponto, a lista de ativos fica vazia — e o servidor, que
   * ainda não aceitou o encerramento, devolve o ponto. Entregar a resposta
   * crua nessa hora ressuscitava na tela exatamente o que acabara de sair, a
   * cada releitura e a cada F5.
   */
  it("não ressuscita o ponto removido quando não sobra nenhum ativo", async () => {
    mocks.apiFetch.mockResolvedValue(respostaDoServidor(["ponto-1"]));
    mocks.contarGeometriasDaObra.mockResolvedValue(1);
    mocks.listarGeometriasLocais.mockResolvedValue([]);

    const leitura = await carregarMapaObra(OBRA);

    expect(leitura.dados.features).toEqual([]);
    expect(leitura.origem).toBe("REDE");
  });

  /** Aparelho que nunca leu esta obra: a resposta do servidor é tudo o que há. */
  it("entrega o que o servidor mandou quando o dispositivo não conhece a obra", async () => {
    mocks.apiFetch.mockResolvedValue(respostaDoServidor(["ponto-1"]));
    mocks.contarGeometriasDaObra.mockResolvedValue(0);
    mocks.listarGeometriasLocais.mockResolvedValue([]);

    const leitura = await carregarMapaObra(OBRA);

    expect(leitura.dados.features.map((f) => f.id)).toEqual(["ponto-1"]);
  });

  it("prefere os registros locais quando existem ativos", async () => {
    mocks.apiFetch.mockResolvedValue(respostaDoServidor(["ponto-1", "ponto-2"]));
    mocks.contarGeometriasDaObra.mockResolvedValue(2);
    mocks.listarGeometriasLocais.mockResolvedValue([registroLocal("ponto-2")]);

    const leitura = await carregarMapaObra(OBRA);

    expect(leitura.dados.features.map((f) => f.id)).toEqual(["ponto-2"]);
  });

  /**
   * Falha ao ler o dispositivo não é "o dispositivo está vazio". Sem a
   * distinção, um erro de IndexedDB apagaria a tela em vez de mostrar o que o
   * servidor tem.
   */
  it("cai para o servidor quando a leitura local falha", async () => {
    mocks.apiFetch.mockResolvedValue(respostaDoServidor(["ponto-1"]));
    mocks.contarGeometriasDaObra.mockResolvedValue(2);
    mocks.listarGeometriasLocais.mockRejectedValue(new Error("indexeddb"));

    const leitura = await carregarMapaObra(OBRA);

    expect(leitura.dados.features.map((f) => f.id)).toEqual(["ponto-1"]);
  });
});
