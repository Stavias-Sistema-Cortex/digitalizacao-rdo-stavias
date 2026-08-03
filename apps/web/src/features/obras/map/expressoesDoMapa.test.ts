import { createPropertyExpression } from "@maplibre/maplibre-gl-style-spec";
import { describe, expect, it } from "vitest";

import {
  corDaLinhaExpression,
  DASH_POR_ZOOM,
  fonteDeTextoDoEstilo,
  larguraDaLinhaExpression,
  ROTULO_DO_SERVICO,
} from "./mapAdapter";

/**
 * As expressões de estilo, conferidas pelo mesmo compilador que o renderizador
 * usa em tempo de execução.
 *
 * Este arquivo existe porque uma expressão inválida não quebra teste nenhum: o
 * TypeScript vê um array de `unknown`, a suíte passa inteira, e a recusa só
 * aparece quando o MapLibre monta a camada no navegador de quem está em campo —
 * com a tarja amarela de erro sobre o mapa e a camada faltando. Foi assim que
 * `["*", rampaDeZoom, fator]` chegou à produção: o `["zoom"]` só pode ser
 * entrada direta de um `step`/`interpolate` no topo, e enterrá-lo num operador
 * invalida o estilo.
 *
 * `createPropertyExpression` é a função que o próprio renderizador chama. Se
 * ela aceitar, o mapa aceita.
 */

interface DefinicaoDePropriedade {
  type: "number" | "color" | "array" | "formatted" | "string";
  value?: string;
  length?: number;
  "property-type": "data-driven" | "data-constant";
  expression: {
    interpolated: boolean;
    parameters: readonly string[];
  };
}

const LINE_WIDTH: DefinicaoDePropriedade = {
  type: "number",
  "property-type": "data-driven",
  expression: { interpolated: true, parameters: ["zoom", "feature"] },
};

const LINE_COLOR: DefinicaoDePropriedade = {
  type: "color",
  "property-type": "data-driven",
  expression: { interpolated: true, parameters: ["zoom", "feature"] },
};

/** `line-dasharray` aceita zoom, mas nunca dado — é assim na especificação. */
const LINE_DASHARRAY: DefinicaoDePropriedade = {
  type: "array",
  value: "number",
  "property-type": "data-constant",
  expression: { interpolated: false, parameters: ["zoom"] },
};

const TEXT_FIELD: DefinicaoDePropriedade = {
  type: "formatted",
  "property-type": "data-driven",
  expression: { interpolated: false, parameters: ["zoom", "feature"] },
};

function compilar(expressao: unknown, definicao: DefinicaoDePropriedade) {
  return createPropertyExpression(
    expressao as never,
    definicao as never,
  );
}

function erros(expressao: unknown, definicao: DefinicaoDePropriedade): string[] {
  const resultado = compilar(expressao, definicao);
  return resultado.result === "error"
    ? resultado.value.map((erro) => `${erro.key}: ${erro.message}`)
    : [];
}

describe("expressões de estilo do mapa operacional", () => {
  it("compila a largura da linha, com e sem o fator do contorno", () => {
    expect(erros(larguraDaLinhaExpression(), LINE_WIDTH)).toEqual([]);
    expect(erros(larguraDaLinhaExpression(1.8), LINE_WIDTH)).toEqual([]);
  });

  /**
   * A regressão exata, escrita como o renderizador a recusou. Se algum dia
   * alguém voltar a multiplicar a rampa inteira, este teste mostra que o
   * compilador continua recusando — e que o teste acima é quem prova o fix.
   */
  it("recusa a rampa de zoom enterrada num operador, como o renderizador faz", () => {
    const invalida = [
      "*",
      ["interpolate", ["linear"], ["zoom"], 10, 2, 18, 12],
      ["case", ["==", ["get", "faseExecucao"], "HOJE"], 1.6, 1],
    ];

    expect(erros(invalida, LINE_WIDTH).join(" ")).toContain(
      '"zoom" expression may only be used as input to a top-level "step" or "interpolate" expression',
    );
  });

  it("compila a cor por fase e o rótulo do serviço", () => {
    expect(erros(corDaLinhaExpression(), LINE_COLOR)).toEqual([]);
    expect(erros(ROTULO_DO_SERVICO, TEXT_FIELD)).toEqual([]);
  });

  it("compila o tracejado por zoom sem depender de dado", () => {
    expect(erros(DASH_POR_ZOOM, LINE_DASHARRAY)).toEqual([]);
  });

  /**
   * O rótulo do serviço precisa pedir uma fonte que o provider sirva. Sem
   * `text-font` explícito vale o padrão da especificação, que o estilo em uso
   * não publica: cada caractere virava um 404 no endpoint de glyphs.
   */
  describe("fonte do rótulo", () => {
    it("reaproveita a pilha de fontes de uma camada de símbolo do estilo", () => {
      expect(fonteDeTextoDoEstilo([
        { type: "background" },
        { type: "line", layout: {} },
        { type: "symbol", layout: { "text-font": ["Noto Sans Regular"] } },
        { type: "symbol", layout: { "text-font": ["Outra Fonte"] } },
      ])).toEqual(["Noto Sans Regular"]);
    });

    /** Estilo sem símbolo é estilo sem glyphs: melhor não pedir texto nenhum. */
    it("devolve nulo quando o estilo não declara fonte utilizável", () => {
      expect(fonteDeTextoDoEstilo(undefined)).toBeNull();
      expect(fonteDeTextoDoEstilo([])).toBeNull();
      expect(fonteDeTextoDoEstilo([{ type: "raster" }])).toBeNull();
      expect(fonteDeTextoDoEstilo([{ type: "symbol", layout: {} }])).toBeNull();
      expect(
        fonteDeTextoDoEstilo([{ type: "symbol", layout: { "text-font": [] } }]),
      ).toBeNull();
      expect(
        fonteDeTextoDoEstilo([
          { type: "symbol", layout: { "text-font": ["  "] } },
        ]),
      ).toBeNull();
    });
  });

  /**
   * O ritmo do traço encolhe conforme a linha engrossa: o dasharray é medido
   * em múltiplos da largura, então manter o mesmo valor faria o pontilhado
   * inchar na aproximação até virar uma sequência de blocos.
   */
  it("encurta o traço conforme o zoom sobe", () => {
    const paradas = DASH_POR_ZOOM.filter(
      (item): item is ["literal", number[]] =>
        Array.isArray(item) && item[0] === "literal",
    ).map(([, valores]) => valores);

    expect(paradas.length).toBeGreaterThanOrEqual(3);
    for (let indice = 1; indice < paradas.length; indice += 1) {
      expect(paradas[indice][0]).toBeLessThan(paradas[indice - 1][0]);
      expect(paradas[indice][1]).toBeLessThan(paradas[indice - 1][1]);
    }
    // Traço sempre maior que o vão: um tracejado com o vão dominando lê como
    // pontilhado solto, não como trecho que existiu e foi encerrado.
    for (const [traco, vao] of paradas) {
      expect(traco).toBeGreaterThan(vao);
    }
  });
});
