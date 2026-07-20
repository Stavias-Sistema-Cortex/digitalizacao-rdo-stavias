import { describe, expect, it } from "vitest";

import { obraMapResponseFromApi } from "./obraMapApi";

describe("obraMapResponseFromApi", () => {
  it("keeps only authoritative features with supported GeoJSON", () => {
    const result = obraMapResponseFromApi({
      obra: {
        id: "obra-1",
        nome: "Obra Norte",
        latitude: "-20.4428000",
        longitude: -54.6464,
      },
      features: [
        {
          id: "geo-1",
          categoria: "PERIMETRO_OBRA",
          objetoTipo: "OBRA",
          objetoId: "obra-1",
          geometry: {
            type: "Polygon",
            coordinates: [[
              [-54.65, -20.44],
              [-54.63, -20.44],
              [-54.63, -20.42],
              [-54.65, -20.44],
            ]],
          },
          properties: { levantamento: "GNSS" },
          fonte: "TOPOGRAFIA",
          status: "ATIVA",
          validoDesde: "2026-07-15T09:00:00",
          validoAte: null,
          versao: 3,
        },
        {
          id: "invalid",
          categoria: "EVENTO",
          geometry: { type: "GeometryCollection", geometries: [] },
        },
      ],
    });

    expect(result.obra.latitude).toBe(-20.4428);
    expect(result.features).toHaveLength(1);
    expect(result.features[0].geometry.type).toBe("Polygon");
  });

  it("never turns absent coordinates into zero", () => {
    const result = obraMapResponseFromApi({
      obra: {
        id: "obra-1",
        nome: "Obra sem coordenadas",
        latitude: null,
        longitude: null,
      },
      features: [],
    });

    expect(result.obra.latitude).toBeNull();
    expect(result.obra.longitude).toBeNull();
  });
});
