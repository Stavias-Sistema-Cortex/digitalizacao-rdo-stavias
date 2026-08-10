import { describe, expect, it } from "vitest";

import {
  comQuantidadeMedida,
  dimensaoDaUnidade,
  quantidadeDoServico,
  rotuloDaQuantidade,
} from "./quantidadeDoServico";

/**
 * A quantidade deixou de ser digitada ao lado das medidas que a compõem.
 *
 * <p>O bloco de serviço pedia km inicial, km final, largura, espessura — e
 * depois a quantidade, que é o que essas quatro já dizem. Os dois relatos
 * divergiam, e nada no formulário conferia um contra o outro: o Financeiro
 * media por um número e a obra por outro.
 */
const base = {
  trechoInicial: "206,822",
  trechoFinal: "207,022",
  larguraM: 7,
  espessuraM: 0.05,
};

describe("quantidade medida do serviço", () => {
  it("usa o comprimento quando o serviço é medido em metro", () => {
    expect(quantidadeDoServico({ ...base, unidade: "M" })).toBe(200);
  });

  it("usa a área quando o serviço é medido em metro quadrado", () => {
    expect(quantidadeDoServico({ ...base, unidade: "M²" })).toBe(1400);
  });

  it("usa o volume quando o serviço é medido em metro cúbico", () => {
    expect(quantidadeDoServico({ ...base, unidade: "M³" })).toBe(70);
  });

  /*
   * O catálogo aceita a unidade escrita de vários jeitos, e a quantidade não
   * pode depender de qual deles foi gravado.
   */
  it("reconhece a unidade escrita sem o símbolo", () => {
    expect(dimensaoDaUnidade("m2")).toBe("AREA");
    expect(dimensaoDaUnidade("M 3")).toBe("VOLUME");
    expect(dimensaoDaUnidade("metro linear")).toBe("COMPRIMENTO");
    expect(dimensaoDaUnidade("ML")).toBe("COMPRIMENTO");
  });

  /*
   * Tonelada, hora e verba não saem do trecho. Devolver zero ali afirmaria
   * que nada foi executado, e o mês fecharia a menos sem ninguém saber por quê.
   */
  it("não inventa quantidade para unidade que não é geométrica", () => {
    expect(quantidadeDoServico({ ...base, unidade: "T" })).toBeNull();
    expect(quantidadeDoServico({ ...base, unidade: "H" })).toBeNull();
    expect(quantidadeDoServico({ ...base, unidade: "" })).toBeNull();
    expect(rotuloDaQuantidade("T")).toBeNull();
  });

  it("fica em branco enquanto falta a parcela que fecha a conta", () => {
    expect(
      quantidadeDoServico({ ...base, larguraM: "", unidade: "M²" }),
    ).toBeNull();
    expect(
      quantidadeDoServico({ ...base, espessuraM: "", unidade: "M³" }),
    ).toBeNull();
    expect(
      quantidadeDoServico({ ...base, trechoFinal: "", unidade: "M" }),
    ).toBeNull();
  });

  /*
   * A pista Sul tem quilometragem decrescente: começa no 400 e termina no 398.
   * Distância entre dois pontos não tem sinal.
   */
  it("mede o trecho decrescente como mede o crescente", () => {
    expect(
      quantidadeDoServico({
        ...base,
        trechoInicial: "400",
        trechoFinal: "398",
        unidade: "M",
      }),
    ).toBe(2000);
  });

  describe("reescrita do bloco", () => {
    it("grava a quantidade que as medidas afirmam", () => {
      const reescrito = comQuantidadeMedida({
        ...base,
        unidade: "M²",
        quantidadeExecutada: "" as const,
      });
      expect(reescrito.quantidadeExecutada).toBe(1400);
    });

    it("apaga a quantidade quando a medida deixa de fechar", () => {
      const reescrito = comQuantidadeMedida({
        ...base,
        larguraM: "" as const,
        unidade: "M²",
        quantidadeExecutada: 1400,
      });
      expect(reescrito.quantidadeExecutada).toBe("");
    });

    /*
     * Devolver o mesmo objeto quando nada muda evita que a lista inteira de
     * serviços repinte a cada tecla digitada em outro campo.
     */
    it("devolve o mesmo objeto quando não há o que reescrever", () => {
      const item = { ...base, unidade: "M²", quantidadeExecutada: 1400 };
      expect(comQuantidadeMedida(item)).toBe(item);
    });
  });
});
