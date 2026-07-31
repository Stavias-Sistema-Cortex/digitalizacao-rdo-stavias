import { describe, expect, it } from "vitest";

import {
  buildOperationalFeatureCollection,
  comprimentoAproximadoM,
  isValidWorksiteCoordinate,
  limitesDaColecao,
} from "./mapGeometry";

describe("buildOperationalFeatureCollection", () => {
  it("combines the real worksite point with authoritative API geometries", () => {
    const collection = buildOperationalFeatureCollection(
      {
        id: "obra-1",
        nome: "Duplicação BR-262",
        latitude: -20.4428,
        longitude: -54.6464,
      },
      [
        {
          id: "geo-1",
          categoria: "TRECHO",
          objetoTipo: "TRECHO",
          objetoId: "trecho-9",
          geometry: {
            type: "LineString",
            coordinates: [
              [-54.65, -20.44],
              [-54.63, -20.43],
            ],
          },
          properties: { nome: "Trecho norte" },
          fonte: "LEVANTAMENTO_CAMPO",
          versao: 2,
          validoDesde: "2026-07-15T09:00:00Z",
          validoAte: null,
        },
      ],
    );

    expect(collection.features).toHaveLength(2);
    expect(collection.features[0]).toMatchObject({
      id: "obra-1:localizacao",
      geometry: {
        type: "Point",
        coordinates: [-54.6464, -20.4428],
      },
      properties: {
        categoria: "LOCALIZACAO_OBRA",
        nome: "Duplicação BR-262",
      },
    });
    expect(collection.features[1]).toMatchObject({
      id: "geo-1",
      properties: {
        categoria: "TRECHO",
        objetoId: "trecho-9",
        fonte: "LEVANTAMENTO_CAMPO",
        versao: 2,
      },
    });
  });

  it("does not fabricate a marker when coordinates are absent or invalid", () => {
    const collection = buildOperationalFeatureCollection(
      {
        id: "obra-1",
        nome: "Obra sem coordenadas",
        latitude: null,
        longitude: null,
      },
      [],
    );

    expect(collection.features).toEqual([]);
    expect(isValidWorksiteCoordinate(91, -54)).toBe(false);
    expect(isValidWorksiteCoordinate(-20, -181)).toBe(false);
  });
});

describe("limitesDaColecao", () => {
  it("descreve a extensão real ocupada pelas geometrias", () => {
    const limites = limitesDaColecao({
      type: "FeatureCollection",
      features: [
        {
          type: "Feature",
          id: "trecho",
          geometry: {
            type: "LineString",
            coordinates: [
              [-47.567267, -22.439459],
              [-47.558866, -22.449263],
            ],
          },
          properties: {},
        },
        {
          type: "Feature",
          id: "ponto",
          geometry: { type: "Point", coordinates: [-47.5645, -22.4425] },
          properties: {},
        },
      ],
    });

    expect(limites).toEqual({
      oeste: -47.567267,
      sul: -22.449263,
      leste: -47.558866,
      norte: -22.439459,
    });
  });

  it("ignora coordenadas fora do intervalo geográfico", () => {
    const limites = limitesDaColecao({
      type: "FeatureCollection",
      features: [
        {
          type: "Feature",
          id: "invalida",
          geometry: { type: "Point", coordinates: [999, 999] },
          properties: {},
        },
      ],
    });

    expect(limites).toBeNull();
  });

  it("devolve nulo para uma coleção vazia", () => {
    expect(
      limitesDaColecao({ type: "FeatureCollection", features: [] }),
    ).toBeNull();
  });
});

describe("comprimentoAproximadoM", () => {
  it("mede a linha pela distância real entre os vértices", () => {
    // Um grau de latitude equivale a cerca de 111 km.
    const comprimento = comprimentoAproximadoM({
      type: "LineString",
      coordinates: [
        [-47.5, -22.5],
        [-47.5, -22.49],
      ],
    });

    expect(comprimento).toBeGreaterThan(1050);
    expect(comprimento).toBeLessThan(1170);
  });

  it("soma todos os ramos de uma multilinha", () => {
    const simples = comprimentoAproximadoM({
      type: "LineString",
      coordinates: [
        [-47.5, -22.5],
        [-47.5, -22.49],
      ],
    }) as number;
    const dupla = comprimentoAproximadoM({
      type: "MultiLineString",
      coordinates: [
        [
          [-47.5, -22.5],
          [-47.5, -22.49],
        ],
        [
          [-47.4, -22.5],
          [-47.4, -22.49],
        ],
      ],
    }) as number;

    expect(dupla).toBeCloseTo(simples * 2, 3);
  });

  it("não mede o que não é linha", () => {
    expect(
      comprimentoAproximadoM({ type: "Point", coordinates: [-47.5, -22.5] }),
    ).toBeNull();
  });

  it("devolve nulo para uma linha degenerada", () => {
    expect(
      comprimentoAproximadoM({
        type: "LineString",
        coordinates: [[-47.5, -22.5]],
      }),
    ).toBeNull();
  });
});
