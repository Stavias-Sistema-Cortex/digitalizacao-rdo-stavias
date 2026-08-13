import { describe, expect, it } from "vitest";

import type { SegmentoTrecho } from "../trecho/trechoGeometry";
import {
  CATEGORIA_EIXO,
  PROPRIEDADE_DERIVADA,
  apoiarTrechosNoEixo,
  oQueEsperaARegua,
  lerEixoDaColecao,
  rdosJaNoMapa,
  kmDoPonto,
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

  /*
   * O silêncio precisa valer para as duas derivações.
   *
   * A linha calada some da resposta do servidor — é isso que silenciar quer
   * dizer. Só que o aparelho deriva as suas próprias linhas a partir dos
   * apontamentos que guarda, e ele decide o que desenhar perguntando quais
   * RDOs o servidor já mostra. Sem a lista de silêncio, o RDO calado sumia
   * dessa conta e o aparelho redesenhava exatamente a linha que acabara de
   * ser tirada do mapa — para todo mundo que tivesse os apontamentos, que é
   * quase todo mundo que abre a obra.
   */
  it("não redesenha por conta própria a linha que o servidor calou", () => {
    const resultado = apoiarTrechosNoEixo(
      colecao(FEICAO_DO_EIXO),
      [segmento()],
      ["rdo-1"],
    );

    expect(resultado.features).toHaveLength(1);
    expect(resultado.features[0].properties.categoria).toBe("EIXO_OBRA");
  });

  /*
   * Sem rede o servidor não fala, e a tela voltaria a ficar vazia em silêncio.
   * O aparelho apura o mesmo com o que tem: é a única apuração que existe para
   * o RDO preenchido em campo que ainda não subiu.
   */
  it("apura sozinho o que espera a régua quando não há eixo", () => {
    const espera = oQueEsperaARegua(colecao(), [segmento()]);

    expect(espera?.motivo).toBe("SEM_EIXO");
    expect(espera?.total).toBe(1);
    expect(espera?.kmInicial).toBe(102);
    expect(espera?.kmFinal).toBe(104);
    // A data do apontamento, que é a do RDO.
    expect(espera?.primeiraData).toBe("2026-08-10");
    expect(espera?.eixoKmInicial).toBeNull();
  });

  it("com eixo curto demais, diz que ficou fora e mostra a faixa da régua", () => {
    const espera = oQueEsperaARegua(colecao(FEICAO_DO_EIXO), [
      segmento({ kmInicial: 300, kmFinal: 302 }),
    ]);

    expect(espera?.motivo).toBe("FORA_DO_EIXO");
    expect(espera?.eixoKmInicial).toBe(100);
    expect(espera?.eixoKmFinal).toBe(110);
  });

  it("cala quando o apontamento virou linha", () => {
    expect(
      oQueEsperaARegua(colecao(FEICAO_DO_EIXO), [segmento()]),
    ).toBeUndefined();
  });

  /* O que o servidor já desenha não está esperando nada. */
  it("não conta o RDO que já está no mapa nem o silenciado", () => {
    const derivadaDoServidor = {
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

    expect(
      oQueEsperaARegua(colecao(derivadaDoServidor), [segmento()]),
    ).toBeUndefined();
    expect(
      oQueEsperaARegua(colecao(), [segmento()], ["rdo-1"]),
    ).toBeUndefined();
  });

  /* O silêncio é de um RDO, não da obra: o vizinho continua desenhando. */
  it("cala só o RDO silenciado", () => {
    const resultado = apoiarTrechosNoEixo(
      colecao(FEICAO_DO_EIXO),
      [segmento(), segmento({ id: "seg-2", rdoId: "rdo-2" })],
      ["rdo-1"],
    );

    expect(resultado.features).toHaveLength(2);
    expect(resultado.features[1].properties.objetoId).toBe("rdo-2");
  });
});

describe("rdosJaNoMapa", () => {
  /*
   * A mesma derivação roda na API, que é onde ela pertence. Se o aparelho
   * ignorasse a linha que o servidor já mandou, ele desenharia a segunda por
   * cima — o mesmo trabalho duas vezes, levemente deslocado.
   */
  it("conta também a linha que a API já derivou", () => {
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

    expect([...rdosJaNoMapa(colecao(derivada))]).toEqual(["rdo-1"]);
  });

  it("não repete o apontamento que a API já desenhou", () => {
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

    const resultado = apoiarTrechosNoEixo(
      colecao(FEICAO_DO_EIXO, derivada),
      [segmento()],
    );

    expect(resultado.features).toHaveLength(2);
  });
});

/*
 * A volta do caminho: arrastar um extremo no mapa tem de virar quilômetro no
 * apontamento, senão a correção no mapa não corrigiria nada — o traço mudaria
 * e o RDO seguiria afirmando o quilômetro velho.
 */
describe("kmDoPonto", () => {
  it("lê o quilômetro de um ponto sobre o eixo", () => {
    expect(kmDoPonto(EIXO, [0.4, 0]) as number).toBeCloseTo(102, 1);
    expect(kmDoPonto(EIXO, [1.6, 0]) as number).toBeCloseTo(108, 1);
  });

  it("projeta no eixo o ponto solto ao lado dele", () => {
    // Quem arrasta raramente solta em cima do traço; a régua resolve.
    expect(kmDoPonto(EIXO, [0.4, 0.01]) as number).toBeCloseTo(102, 1);
  });

  it("prende nas pontas o que caiu além do eixo", () => {
    expect(kmDoPonto(EIXO, [-1, 0]) as number).toBeCloseTo(100, 1);
    expect(kmDoPonto(EIXO, [5, 0]) as number).toBeCloseTo(110, 1);
  });

  it("fecha o ciclo com o desenho: km vira ponto e o ponto volta ao km", () => {
    const recorte = recortarEixoPorKm(EIXO, 103, 107);
    expect(kmDoPonto(EIXO, recorte![0]) as number).toBeCloseTo(103, 2);
    expect(
      kmDoPonto(EIXO, recorte![recorte!.length - 1]) as number,
    ).toBeCloseTo(107, 2);
  });
});
