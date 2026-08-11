import { beforeEach, describe, expect, it, vi } from "vitest";

const apiFetch = vi.hoisted(() => vi.fn());
const contarGeometriasDaObra = vi.hoisted(() => vi.fn());
const listarGeometriasLocais = vi.hoisted(() => vi.fn());
const reconciliarGeometriasDoServidor = vi.hoisted(() => vi.fn());

vi.mock("../../../lib/api/apiClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../../lib/api/apiClient")>()),
  apiFetch,
}));
vi.mock("./obraGeoCacheRepository", () => ({
  contarGeometriasDaObra,
  listarGeometriasLocais,
  reconciliarGeometriasDoServidor,
  featureDoRegistro: (registro: Record<string, unknown>) => registro,
}));

const { carregarMapaObra } = await import("./obraMapApi");

const OBRA = {
  id: "obra-1",
  nome: "Obra Norte",
  latitude: -20.44,
  longitude: -54.64,
};

/** O desenho que o aparelho guarda, porque ele é dono dele. */
const DESENHADA = {
  id: "geo-1",
  categoria: "TRECHO",
  objetoTipo: "RDO",
  objetoId: "rdo-1",
  geometry: {
    type: "LineString",
    coordinates: [
      [-54.65, -20.44],
      [-54.63, -20.42],
    ],
  },
  properties: {},
  fonte: "GESTAO_MAPA",
  status: "ATIVA",
  validoDesde: "2026-08-10T09:00:00",
  validoAte: null,
  versao: 2,
};

/** A linha que o servidor deriva na leitura; o aparelho nunca a guarda. */
const DERIVADA = {
  id: "eixo:execucao-1",
  categoria: "TRECHO",
  objetoTipo: "RDO",
  objetoId: "rdo-2",
  geometry: {
    type: "LineString",
    coordinates: [
      [-54.60, -20.40],
      [-54.58, -20.39],
    ],
  },
  properties: { derivadoDoEixo: true, execucaoId: "execucao-1" },
  fonte: "APONTAMENTO_RDO",
  status: "ATIVA",
  validoDesde: "2026-08-10T00:00:00",
  validoAte: null,
  versao: 0,
};

function respostaDoServidor() {
  return new Response(
    JSON.stringify({
      obra: { id: "obra-1", nome: "Obra Norte" },
      features: [DESENHADA, DERIVADA],
    }),
    { status: 200, headers: { "content-type": "application/json" } },
  );
}

beforeEach(() => {
  apiFetch.mockReset();
  apiFetch.mockResolvedValue(respostaDoServidor());
  contarGeometriasDaObra.mockReset();
  listarGeometriasLocais.mockReset();
  reconciliarGeometriasDoServidor.mockReset();
  reconciliarGeometriasDoServidor.mockResolvedValue(undefined);
});

/*
 * "O que o dispositivo sabe manda" existe para o que ele pode ser dono:
 * geometria gravada, que ele cria, encerra e reconcilia. A linha apoiada no
 * eixo não é nada disso — nasce na leitura, do quilômetro que mora no RDO.
 *
 * Sem separar as duas, bastava o aparelho conhecer uma geometria qualquer da
 * obra para a resposta inteira do servidor ser descartada, e o trecho apontado
 * por quilômetro sumia do mapa. Era o caso de toda obra já usada: a
 * localização da própria obra já é uma geometria conhecida.
 */
describe("carregarMapaObra · linha derivada do eixo", () => {
  it("mantém a derivada mesmo quando o aparelho já conhece geometrias", async () => {
    contarGeometriasDaObra.mockResolvedValue(1);
    listarGeometriasLocais.mockResolvedValue([DESENHADA]);

    const leitura = await carregarMapaObra(OBRA);

    expect(leitura.dados.features.map((f) => f.id)).toEqual([
      "geo-1",
      "eixo:execucao-1",
    ]);
  });

  it("o desenho local continua mandando sobre o mesmo id do servidor", async () => {
    const localCorrigido = {
      ...DESENHADA,
      geometry: {
        type: "LineString",
        coordinates: [
          [-54.70, -20.50],
          [-54.60, -20.40],
        ],
      },
    };
    contarGeometriasDaObra.mockResolvedValue(1);
    listarGeometriasLocais.mockResolvedValue([localCorrigido]);

    const leitura = await carregarMapaObra(OBRA);

    expect(leitura.dados.features[0].geometry.coordinates).toEqual(
      localCorrigido.geometry.coordinates,
    );
  });

  it("sem nada no aparelho, a resposta do servidor vale inteira", async () => {
    contarGeometriasDaObra.mockResolvedValue(0);
    listarGeometriasLocais.mockResolvedValue([]);

    const leitura = await carregarMapaObra(OBRA);

    expect(leitura.dados.features).toHaveLength(2);
  });

  it("não duplica a derivada quando o aparelho não devolve lista", async () => {
    contarGeometriasDaObra.mockResolvedValue(3);
    listarGeometriasLocais.mockResolvedValue(null);

    const leitura = await carregarMapaObra(OBRA);

    expect(leitura.dados.features.map((f) => f.id)).toEqual([
      "geo-1",
      "eixo:execucao-1",
    ]);
  });
});
