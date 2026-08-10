import { describe, expect, it } from "vitest";

import { numeroDigitado } from "./numeroDigitado";
import { quilometroDigitado } from "./quilometroDigitado";

/**
 * O quilômetro é o número que o leitor geral lê ao contrário.
 *
 * <p>`decimalDigitado` trata ponto como milhar, porque é assim que se escreve
 * dinheiro e quantidade em português. Aplicada ao quilômetro, a mesma regra
 * lê `206.822` como duzentos e seis mil — e é assim que a base inteira guarda
 * quilômetro, em `varchar`, desde sempre. O erro não é de arredondamento: é
 * de mil vezes, e ele sai daqui direto para a extensão do RDO e para a posição
 * do trecho no mapa.
 */
describe("leitura de quilômetro", () => {
  it("lê o ponto como decimal, e não como milhar", () => {
    expect(quilometroDigitado("206.822")).toBe(206.822);
    // O leitor geral, no mesmo texto, diz outra coisa — de propósito.
    expect(numeroDigitado("206.822")).toBe(206822);
  });

  it("lê a vírgula como decimal", () => {
    expect(quilometroDigitado("206,822")).toBe(206.822);
  });

  /*
   * Com os dois separadores o texto resolve a ambiguidade sozinho: o último a
   * aparecer é o decimal. É o caso que a troca ingênua de vírgula por ponto
   * quebrava — "1.206,5" virava "1.206.5", que não é número nenhum, e o
   * cadastro recusava o trecho por falta de quilômetro.
   */
  it("aceita o milhar quando ele vem acompanhado do decimal", () => {
    expect(quilometroDigitado("1.206,5")).toBe(1206.5);
    expect(quilometroDigitado("1,206.5")).toBe(1206.5);
  });

  it("aceita a notação rodoviária de quilômetro mais metros", () => {
    expect(quilometroDigitado("309+400")).toBe(309.4);
  });

  it("aceita o prefixo km e os espaços de quem digita com pressa", () => {
    expect(quilometroDigitado(" km 172 ")).toBe(172);
    expect(quilometroDigitado("KM206,8")).toBe(206.8);
  });

  it("aceita o número que já veio pronto", () => {
    expect(quilometroDigitado(206.822)).toBe(206.822);
  });

  /*
   * Ausência precisa continuar distinguível do km 0, que é uma marcação real:
   * é o começo da rodovia.
   */
  it("devolve nulo para ausência e mantém o km zero", () => {
    expect(quilometroDigitado("")).toBeNull();
    expect(quilometroDigitado("   ")).toBeNull();
    expect(quilometroDigitado(null)).toBeNull();
    expect(quilometroDigitado(undefined)).toBeNull();
    expect(quilometroDigitado("0")).toBe(0);
  });

  it("recusa o texto que não marca quilômetro nenhum", () => {
    expect(quilometroDigitado("estaca 12")).toBeNull();
    expect(quilometroDigitado("-")).toBeNull();
    expect(quilometroDigitado("206,8225")).toBeNull();
  });
});
