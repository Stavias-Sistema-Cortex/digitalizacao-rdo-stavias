import { describe, expect, it } from "vitest";

import {
  alternarCategoria,
  categoriasDaColecao,
  FILTRO_VAZIO,
  filtrarColecao,
  vigenteEm,
} from "./filtrosDoMapa";
import type {
  OperationalFeature,
  OperationalFeatureCollection,
} from "./mapGeometry";

function feature(
  id: string,
  categoria: string,
  validoDesde: string | null = null,
  validoAte: string | null = null,
): OperationalFeature {
  return {
    type: "Feature",
    id,
    geometry: { type: "Point", coordinates: [-47.5, -22.4] },
    properties: { categoria, validoDesde, validoAte },
  };
}

const colecao: OperationalFeatureCollection = {
  type: "FeatureCollection",
  features: [
    feature("obra", "LOCALIZACAO_OBRA"),
    feature("t1", "TRECHO", "2026-07-20T10:00:00Z", null),
    feature("t2", "TRECHO", "2026-07-25T10:00:00Z", "2026-07-27T18:00:00Z"),
    feature("p1", "PONTO_OPERACIONAL", "2026-07-28T08:00:00Z", null),
  ],
};

describe("categoriasDaColecao", () => {
  it("lista cada categoria uma vez, em ordem estável", () => {
    expect(categoriasDaColecao(colecao)).toEqual([
      "LOCALIZACAO_OBRA",
      "PONTO_OPERACIONAL",
      "TRECHO",
    ]);
  });
});

describe("vigenteEm", () => {
  const encerrada = colecao.features[2];

  it("sem data observada, nada é escondido", () => {
    expect(vigenteEm(encerrada, "")).toBe(true);
  });

  it("esconde o que ainda não existia no dia observado", () => {
    expect(vigenteEm(encerrada, "2026-07-24")).toBe(false);
    expect(vigenteEm(encerrada, "2026-07-25")).toBe(true);
  });

  it("mantém vigente o próprio dia do encerramento", () => {
    expect(vigenteEm(encerrada, "2026-07-27")).toBe(true);
    expect(vigenteEm(encerrada, "2026-07-28")).toBe(false);
  });

  it("trata geometria sem vigência como sempre presente", () => {
    // É o caso do ponto da própria obra: não tem vigência e não pode sumir
    // por causa de um filtro de tempo.
    expect(vigenteEm(colecao.features[0], "2020-01-01")).toBe(true);
  });
});

describe("filtrarColecao", () => {
  it("devolve a mesma referência quando nada foi pedido", () => {
    // Remontar o mapa à toa apaga o enquadramento de quem está olhando.
    expect(filtrarColecao(colecao, FILTRO_VAZIO)).toBe(colecao);
  });

  it("esconde a categoria desmarcada", () => {
    const filtrada = filtrarColecao(colecao, {
      ...FILTRO_VAZIO,
      categoriasOcultas: new Set(["TRECHO"]),
    });

    expect(filtrada.features.map((item) => item.id)).toEqual(["obra", "p1"]);
  });

  it("mostra a obra como ela estava no dia observado", () => {
    const filtrada = filtrarColecao(colecao, {
      ...FILTRO_VAZIO,
      data: "2026-07-26",
    });

    expect(filtrada.features.map((item) => item.id)).toEqual([
      "obra",
      "t1",
      "t2",
    ]);
  });

  it("combina categoria e dia no mesmo recorte", () => {
    const filtrada = filtrarColecao(colecao, {
      categoriasOcultas: new Set(["LOCALIZACAO_OBRA"]),
      data: "2026-07-21",
    });

    expect(filtrada.features.map((item) => item.id)).toEqual(["t1"]);
  });
});

describe("alternarCategoria", () => {
  it("desmarca e remarca sem alterar o conjunto recebido", () => {
    const inicial: ReadonlySet<string> = new Set<string>();
    const ocultaTrecho = alternarCategoria(inicial, "TRECHO");

    expect(inicial.size).toBe(0);
    expect([...ocultaTrecho]).toEqual(["TRECHO"]);
    expect([...alternarCategoria(ocultaTrecho, "TRECHO")]).toEqual([]);
  });
});
