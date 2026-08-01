import { describe, expect, it } from "vitest";

import {
  janelaDoPeriodo,
  participouDaJanela,
  recortarEvolucao,
} from "./evolucaoDaObra";
import type {
  OperationalFeature,
  OperationalFeatureCollection,
} from "../obras/map/mapGeometry";

function feature(
  id: string,
  validoDesde: string | null = null,
  validoAte: string | null = null,
): OperationalFeature {
  return {
    type: "Feature",
    id,
    geometry: { type: "Point", coordinates: [-47.5, -22.4] },
    properties: { categoria: "TRECHO", validoDesde, validoAte },
  };
}

const HOJE = new Date("2026-08-01T12:00:00Z");

describe("janelaDoPeriodo", () => {
  it("converte o período do gráfico na mesma janela de meses", () => {
    expect(janelaDoPeriodo("3M", HOJE).inicio).toBe("2026-05-01");
    expect(janelaDoPeriodo("6M", HOJE).inicio).toBe("2026-02-01");
    expect(janelaDoPeriodo("12M", HOJE).inicio).toBe("2025-08-01");
  });

  it("não limita o passado quando o gráfico mostra tudo", () => {
    expect(janelaDoPeriodo("ALL", HOJE).inicio).toBeNull();
  });
});

describe("participouDaJanela", () => {
  const janela = janelaDoPeriodo("6M", HOJE);

  it("mantém o que segue vigente, mesmo começado antes do período", () => {
    // A evolução do semestre inclui o trecho aberto em janeiro que ainda
    // vale: ele participou do período inteiro.
    expect(participouDaJanela(feature("t", "2025-01-10", null), janela))
      .toBe(true);
  });

  it("descarta o que foi encerrado antes do início do período", () => {
    expect(
      participouDaJanela(feature("t", "2025-01-10", "2025-12-01"), janela),
    ).toBe(false);
  });

  it("mantém o que foi encerrado dentro do período", () => {
    expect(
      participouDaJanela(feature("t", "2025-01-10", "2026-03-15"), janela),
    ).toBe(true);
  });

  it("nunca esconde a geometria sem vigência, como o ponto da obra", () => {
    expect(participouDaJanela(feature("obra"), janela)).toBe(true);
  });
});

describe("recortarEvolucao", () => {
  const colecao: OperationalFeatureCollection = {
    type: "FeatureCollection",
    features: [
      feature("obra"),
      feature("antigo", "2025-01-10", "2025-11-30"),
      feature("vigente", "2025-03-01", null),
      feature("do-semestre", "2026-04-02", "2026-06-20"),
    ],
  };

  it("devolve a mesma referência quando o gráfico mostra tudo", () => {
    expect(recortarEvolucao(colecao, janelaDoPeriodo("ALL", HOJE)))
      .toBe(colecao);
  });

  it("recorta a coleção pela mesma janela do gráfico", () => {
    const recortada = recortarEvolucao(colecao, janelaDoPeriodo("6M", HOJE));

    expect(recortada.features.map((item) => item.id)).toEqual([
      "obra",
      "vigente",
      "do-semestre",
    ]);
  });
});
