import { describe, expect, it } from "vitest";

import { janelaDoGrafico } from "./evolucaoDaObra";
import { recortarPorJanela } from "../obras/map/filtrosDoMapa";
import type {
  OperationalFeature,
  OperationalFeatureCollection,
} from "../obras/map/mapGeometry";
import type { MonthlyPoint } from "./progressSeries";

function ponto(month: string): MonthlyPoint {
  return { month, fisicoPct: 10, pdorPct: 10 };
}

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

const HOJE = new Date("2026-08-31T12:00:00Z");

describe("janelaDoGrafico", () => {
  it("ancora no primeiro mês que o gráfico está exibindo", () => {
    // A janela nasce dos pontos visíveis: é isso que garante que gráfico e
    // mapa mostram o mesmo período mesmo com histórico defasado.
    const visiveis = [ponto("2025-12"), ponto("2026-01"), ponto("2026-02")];

    expect(janelaDoGrafico(visiveis, "3M", HOJE).inicio).toBe("2025-12-01");
  });

  it("sem histórico, usa a mesma conta do gráfico sobre o mês corrente", () => {
    // Granularidade de mês e N-1 para trás, como filterByPeriod — e sem
    // aritmética de Date, que estoura no dia 31.
    expect(janelaDoGrafico([], "3M", HOJE).inicio).toBe("2026-06-01");
    expect(janelaDoGrafico([], "6M", HOJE).inicio).toBe("2026-03-01");
    expect(janelaDoGrafico([], "12M", HOJE).inicio).toBe("2025-09-01");
  });

  it("não limita o passado quando o gráfico mostra tudo", () => {
    expect(janelaDoGrafico([ponto("2026-01")], "ALL", HOJE).inicio)
      .toBeNull();
  });
});

describe("recortarPorJanela", () => {
  const colecao: OperationalFeatureCollection = {
    type: "FeatureCollection",
    features: [
      feature("obra"),
      feature("antigo", "2025-01-10", "2025-11-30"),
      feature("vigente", "2025-03-01", null),
      feature("do-periodo", "2026-04-02", "2026-06-20"),
    ],
  };

  it("devolve a mesma referência quando não há recorte", () => {
    expect(recortarPorJanela(colecao, null)).toBe(colecao);
  });

  it("mantém o que participou do período: vigente ou encerrado dentro dele", () => {
    const recortada = recortarPorJanela(colecao, "2026-02-01");

    expect(recortada.features.map((item) => item.id)).toEqual([
      "obra",
      "vigente",
      "do-periodo",
    ]);
  });

  it("nunca esconde a geometria sem vigência, como o ponto da obra", () => {
    expect(
      recortarPorJanela(colecao, "2030-01-01").features.map((f) => f.id),
    ).toEqual(["obra", "vigente"]);
  });
});
