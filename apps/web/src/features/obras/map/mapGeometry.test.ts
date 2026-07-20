import { describe, expect, it } from "vitest";

import {
  buildOperationalFeatureCollection,
  isValidWorksiteCoordinate,
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
