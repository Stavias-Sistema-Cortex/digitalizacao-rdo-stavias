import { describe, expect, it } from "vitest";

import type { SegmentoTrecho } from "../trecho/trechoGeometry";
import {
  CATEGORIA_EIXO,
  PROPRIEDADE_DERIVADA,
  apoiarTrechosNoEixo,
  lerEixoDaColecao,
  rdosComDesenhoProprio,
  recortarEixoPorKm,
  type EixoDaObra,
} from "./eixoDaObra";
import type { OperationalFeatureCollection } from "./mapGeometry";

/** Eixo reto sobre o equador: 1 grau de longitude por quilômetro declarado. */
const EIXO: EixoDaObra = {
  id: "eixo-1",
  coordenadas: [
    [0, 0],
    [1, 0],
    [2, 0],
  ],
  kmInicial: 100,
  kmFinal: 110,
};

function segmento(
  partial: Partial<SegmentoTrecho> = {},
): SegmentoTrecho {
  return {
    id: "seg-1",
    origem: "EXECUCAO_SERVICO",
    rdoId: "rdo-1",
    numeroRdo: "RDO-0001",
    data: "2026-08-10",
    servicoNome: "Fresagem",
    subtrecho: null,
    sentido: "Norte",
    pista: null,
    faixa: "1",
    kmInicial: 102,
    kmFinal: 104,
    estacaInicial: null,
    estacaFinal: null,
    extensaoM: 2000,
    larguraM: null,
    areaM2: null,
    massaTonelada: null,
    status: null,
    rdoStatus: "ENVIADA",
    procedencia: "SERVIDOR",
    pistaInferida: false,
    ...partial,
  };
}

function colecao(
  ...features: OperationalFeatureCollection["features"]
): OperationalFeatureCollection {
  return { type: "FeatureCollection", features };
}

const FEICAO_DO_EIXO = {
  type: "Feature" as const,
  id: "eixo-1",
  geometry: {
    type: "LineString" as const,
    coordinates: [
      [0, 0],
      [1, 0],
      [2, 0],
    ],
  },
  properties: {
    categoria: CATEGORIA_EIXO,
    kmInicial: 100,
    kmFinal: 110,
  },
};

describe("lerEixoDaColecao", () => {
  it("lê o eixo com quilômetro nas pontas", () => {
    expect(lerEixoDaColecao(colecao(FEICAO_DO_EIXO))).toMatchObject({
      id: "eixo-1",
      kmInicial: 100,
      kmFinal: 110,
    });
  });

  it("aceita quilômetro que voltou como texto do transporte", () => {
    const eixo = lerEixoDaColecao(
      colecao({
        ...FEICAO_DO_EIXO,
        properties: {
          categoria: CATEGORIA_EIXO,
          kmInicial: "100,5",
          kmFinal: "110",
        },
      }),
    );

    expect(eixo?.kmInicial).toBe(100.5);
  });

  it("recusa o eixo sem amplitude, que não serve de régua", () => {
    expect(
      lerEixoDaColecao(
        colecao({
          ...FEICAO_DO_EIXO,
          properties: {
            categoria: CATEGORIA_EIXO,
            kmInicial: 100,
            kmFinal: 100,
          },
        }),
      ),
    ).toBeNull();
  });

  it("devolve nulo quando a obra ainda não tem eixo", () => {
    expect(lerEixoDaColecao(colecao())).toBeNull();
  });
});

describe("recortarEixoPorKm", () => {
  it("apoia o intervalo pedido sobre a linha", () => {
    const recorte = recortarEixoPorKm(EIXO, 102, 104);

    expect(recorte).toHaveLength(2);
    expect(recorte?.[0][0]).toBeCloseTo(0.4, 3);
    expect(recorte?.[1][0]).toBeCloseTo(0.8, 3);
  });

  it("preserva os vértices intermediários para não cortar fora da pista", () => {
    const recorte = recortarEixoPorKm(EIXO, 101, 109);

    expect(recorte).toHaveLength(3);
    expect(recorte?.[1]).toEqual([1, 0]);
  });

  it("desenha no sentido em que o trecho foi apontado", () => {
    const crescente = recortarEixoPorKm(EIXO, 102, 104);
    const decrescente = recortarEixoPorKm(EIXO, 104, 102);

    expect(decrescente?.[0][0]).toBeCloseTo(
      crescente?.[1][0] as number,
      6,
    );
    expect(decrescente?.[1][0]).toBeCloseTo(
      crescente?.[0][0] as number,
      6,
    );
  });

  it("recorta ao que existe quando o trecho passa da ponta do eixo", () => {
    const recorte = recortarEixoPorKm(EIXO, 108, 130);

    expect(recorte?.[1]).toEqual([2, 0]);
  });

  it("devolve nulo quando o intervalo não encosta no eixo", () => {
    expect(recortarEixoPorKm(EIXO, 200, 210)).toBeNull();
    expect(recortarEixoPorKm(EIXO, 10, 20)).toBeNull();
  });

  it("devolve nulo diante de quilômetro não numérico", () => {
    expect(recortarEixoPorKm(EIXO, Number.NaN, 104)).toBeNull();
  });
});

describe("apoiarTrechosNoEixo", () => {
  it("desenha o apontamento que ninguém desenhou à mão", () => {
    const resultado = apoiarTrechosNoEixo(colecao(FEICAO_DO_EIXO), [
      segmento(),
    ]);

    expect(resultado.features).toHaveLength(2);
    const derivada = resultado.features[1];
    expect(derivada.properties[PROPRIEDADE_DERIVADA]).toBe(true);
    expect(derivada.properties.categoria).toBe("TRECHO");
    expect(derivada.properties.validoDesde).toBe("2026-08-10");
    expect(derivada.properties.servico).toBe("Fresagem");
  });

  it("não duplica o trecho que já tem desenho próprio", () => {
    const desenhado = {
      type: "Feature" as const,
      id: "desenho-1",
      geometry: {
        type: "LineString" as const,
        coordinates: [
          [0.4, 0],
          [0.8, 0],
        ],
      },
      properties: {
        categoria: "TRECHO",
        objetoTipo: "RDO",
        objetoId: "rdo-1",
      },
    };

    const resultado = apoiarTrechosNoEixo(
      colecao(FEICAO_DO_EIXO, desenhado),
      [segmento()],
    );

    expect(resultado.features).toHaveLength(2);
  });

  it("ignora programação, que é plano e não execução", () => {
    const resultado = apoiarTrechosNoEixo(colecao(FEICAO_DO_EIXO), [
      segmento({ origem: "PROGRAMACAO" }),
    ]);

    expect(resultado.features).toHaveLength(1);
  });

  it("ignora o apontamento sem os dois quilômetros", () => {
    const resultado = apoiarTrechosNoEixo(colecao(FEICAO_DO_EIXO), [
      segmento({ kmFinal: null }),
    ]);

    expect(resultado.features).toHaveLength(1);
  });

  it("devolve a mesma referência quando não há eixo nem nada a acrescentar", () => {
    const original = colecao();
    expect(apoiarTrechosNoEixo(original, [segmento()])).toBe(original);

    const semSegmentos = colecao(FEICAO_DO_EIXO);
    expect(apoiarTrechosNoEixo(semSegmentos, [])).toBe(semSegmentos);
  });
});

describe("rdosComDesenhoProprio", () => {
  it("não conta a linha que o próprio eixo derivou", () => {
    const derivada = {
      type: "Feature" as const,
      id: "eixo:seg-1",
      geometry: {
        type: "LineString" as const,
        coordinates: [
          [0.4, 0],
          [0.8, 0],
        ],
      },
      properties: {
        categoria: "TRECHO",
        [PROPRIEDADE_DERIVADA]: true,
        objetoTipo: "RDO",
        objetoId: "rdo-1",
      },
    };

    expect(rdosComDesenhoProprio(colecao(derivada)).size).toBe(0);
  });
});
