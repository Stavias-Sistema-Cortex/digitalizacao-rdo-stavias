import { describe, expect, it } from "vitest";

import {
  anotarFasesDeExecucao,
  faseDaVigencia,
  fasesPresentes,
  servicoDaFeature,
} from "./execucaoDoTrecho";
import type {
  OperationalFeature,
  OperationalFeatureCollection,
} from "./mapGeometry";

const HOJE = "2026-08-03";

function feature(
  categoria: string,
  properties: Record<string, unknown> = {},
): OperationalFeature {
  return {
    type: "Feature",
    id: `${categoria}-${JSON.stringify(properties)}`,
    geometry: { type: "LineString", coordinates: [[0, 0], [1, 1]] },
    properties: { categoria, ...properties },
  };
}

function colecao(
  ...features: OperationalFeature[]
): OperationalFeatureCollection {
  return { type: "FeatureCollection", features };
}

describe("fases de execução do trecho", () => {
  it("classifica pela idade do registro, na régua do dia", () => {
    expect(faseDaVigencia("2026-08-03T10:00:00", null, HOJE)).toBe("HOJE");
    expect(faseDaVigencia("2026-08-01", null, HOJE)).toBe("RECENTE");
    expect(faseDaVigencia("2026-07-27", null, HOJE)).toBe("RECENTE");
    expect(faseDaVigencia("2026-07-26", null, HOJE)).toBe("CONSOLIDADA");
    expect(faseDaVigencia("2026-01-15", null, HOJE)).toBe("CONSOLIDADA");
  });

  /**
   * Encerramento vence a idade: um segmento desenhado e encerrado hoje está
   * encerrado — pintá-lo como avanço do dia afirmaria execução desfeita.
   */
  it("o encerramento vence qualquer idade", () => {
    expect(faseDaVigencia("2026-08-03", "2026-08-03", HOJE)).toBe("ENCERRADA");
    expect(faseDaVigencia("2026-01-01", "2026-05-10", HOJE)).toBe("ENCERRADA");
  });

  /** Idade desconhecida não pode reivindicar o destaque do dia. */
  it("sem vigência legível, a fase é consolidada", () => {
    expect(faseDaVigencia(null, null, HOJE)).toBe("CONSOLIDADA");
    expect(faseDaVigencia("", null, HOJE)).toBe("CONSOLIDADA");
    expect(faseDaVigencia("ontem", null, HOJE)).toBe("CONSOLIDADA");
  });

  it("anota só as categorias de execução e preserva as demais", () => {
    const trecho = feature("TRECHO", { validoDesde: "2026-08-03" });
    const frente = feature("FRENTE_TRABALHO", { validoDesde: "2026-07-30" });
    const ponto = feature("PONTO_OPERACIONAL", { validoDesde: "2026-08-03" });

    const anotada = anotarFasesDeExecucao(colecao(trecho, frente, ponto), HOJE);

    expect(anotada.features[0].properties.faseExecucao).toBe("HOJE");
    expect(anotada.features[1].properties.faseExecucao).toBe("RECENTE");
    expect(anotada.features[2].properties.faseExecucao).toBeUndefined();
    // O ponto não anotado atravessa por referência: nada nele mudou.
    expect(anotada.features[2]).toBe(ponto);
  });

  /**
   * Sem nada para anotar, a MESMA referência volta: os mapas remontam quando a
   * coleção muda de identidade, e remontar sem mudança real apaga o
   * enquadramento de quem estava olhando.
   */
  it("devolve a mesma coleção quando não há trecho para anotar", () => {
    const soPontos = colecao(feature("PONTO_OPERACIONAL"));
    expect(anotarFasesDeExecucao(soPontos, HOJE)).toBe(soPontos);
  });

  it("lista as fases presentes na ordem da legenda", () => {
    const anotada = anotarFasesDeExecucao(
      colecao(
        feature("TRECHO", { validoDesde: "2026-01-01" }),
        feature("TRECHO", { validoDesde: "2026-08-03" }),
      ),
      HOJE,
    );

    expect(fasesPresentes(anotada)).toEqual(["HOJE", "CONSOLIDADA"]);
    expect(fasesPresentes(colecao(feature("PONTO_OPERACIONAL")))).toEqual([]);
  });

  /** O serviço aparece venha de qual chave vier — e nunca em branco. */
  it("acha o serviço nas chaves conhecidas das propriedades", () => {
    expect(
      servicoDaFeature(feature("TRECHO", { servico: "Pavimentação CBUQ" })),
    ).toBe("Pavimentação CBUQ");
    expect(
      servicoDaFeature(feature("TRECHO", { descricao: "Drenagem lateral" })),
    ).toBe("Drenagem lateral");
    expect(servicoDaFeature(feature("TRECHO", { nome: "  Sinalização " })))
      .toBe("Sinalização");
    expect(servicoDaFeature(feature("TRECHO"))).toBe("");
  });
});
