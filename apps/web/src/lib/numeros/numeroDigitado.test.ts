import { describe, expect, it } from "vitest";

import { decimalDigitado, numeroDigitado } from "./numeroDigitado";

/**
 * O número escrito em português precisa chegar inteiro do outro lado.
 *
 * <p>A conversão antiga era `value.replace(",", ".")`, repetida em quinze
 * lugares. Ela errava dos dois jeitos possíveis: recusava "1.234,56", que está
 * certo, e aceitava "1.234" como 1,234 — mil vezes menor, em silêncio, gravado
 * como preço unitário e propagado até a receita medida.
 */

describe("número digitado no Brasil", () => {
  it("lê a vírgula como decimal", () => {
    expect(numeroDigitado("12,50")).toBe(12.5);
  });

  it("lê o ponto de milhar junto da vírgula decimal", () => {
    expect(numeroDigitado("1.234,56")).toBe(1234.56);
    expect(numeroDigitado("12.345.678,90")).toBe(12345678.9);
  });

  /*
   * O caso caro. Ninguém escreve mil e quinhentos como "1.500" querendo dizer
   * um e meio, e quem escrevia via o sistema aceitar sem reclamar.
   */
  it("lê o ponto de milhar sozinho como milhar", () => {
    expect(numeroDigitado("1.234")).toBe(1234);
    expect(numeroDigitado("1.500")).toBe(1500);
    expect(numeroDigitado("12.345.678")).toBe(12345678);
  });

  /*
   * E o zero à esquerda é o que separa os dois: milhar não começa em zero, e
   * "0.500" só pode ser meio.
   */
  it("não confunde a parte inteira zero com milhar", () => {
    expect(numeroDigitado("0.500")).toBe(0.5);
    expect(numeroDigitado("0,500")).toBe(0.5);
  });

  it("continua lendo o ponto decimal de quem digita em inglês", () => {
    expect(numeroDigitado("12.50")).toBe(12.5);
    expect(numeroDigitado("0.75")).toBe(0.75);
    expect(numeroDigitado("1234.567")).toBe(1234.567);
  });

  /*
   * Planilha exportada em inglês chega com a vírgula no papel de milhar.
   * O último separador é sempre o decimal, e isso resolve os dois formatos
   * com a mesma regra.
   */
  it("aceita o formato inglês com vírgula de milhar", () => {
    expect(numeroDigitado("1,234.56")).toBe(1234.56);
  });

  it("ignora espaço em qualquer lugar", () => {
    expect(numeroDigitado(" 1.234,56 ")).toBe(1234.56);
    expect(numeroDigitado("1 234,56")).toBe(1234.56);
  });

  it("lê o número inteiro sem separador nenhum", () => {
    expect(numeroDigitado("1234")).toBe(1234);
    expect(numeroDigitado("0")).toBe(0);
  });

  it("guarda o sinal de quem é negativo", () => {
    expect(numeroDigitado("-1.234,56")).toBe(-1234.56);
  });

  /*
   * Campo em branco é ausência de medida, não medida zero. `Number("")`
   * devolve 0, e é assim que um trecho pela metade virava quatrocentos
   * quilômetros de extensão medidos contra uma origem que ninguém informou.
   */
  it("devolve ausência para o campo em branco", () => {
    expect(numeroDigitado("")).toBeNull();
    expect(numeroDigitado("   ")).toBeNull();
  });

  it("recusa o que não é número", () => {
    expect(numeroDigitado("abc")).toBeNull();
    expect(numeroDigitado("12a")).toBeNull();
    expect(numeroDigitado("R$ 12,50")).toBeNull();
    expect(numeroDigitado(",")).toBeNull();
    expect(numeroDigitado("1,2,3")).toBeNull();
  });

  it("deixa passar o número que já veio como número", () => {
    expect(numeroDigitado(12.5)).toBe(12.5);
    expect(numeroDigitado(Number.NaN)).toBeNull();
  });

  /*
   * O texto normalizado é o que vai para o servidor, e ele precisa preservar
   * as casas decimais como foram escritas: virar `Number` e voltar a texto
   * perderia o zero final de "12,50", que em contrato tem significado.
   */
  describe("texto normalizado", () => {
    it("preserva as casas decimais escritas", () => {
      expect(decimalDigitado("12,50")).toBe("12.50");
      expect(decimalDigitado("1.234,5600")).toBe("1234.5600");
    });

    it("entrega o milhar sem separador", () => {
      expect(decimalDigitado("1.234")).toBe("1234");
    });

    it("devolve ausência onde não há número", () => {
      expect(decimalDigitado("")).toBeNull();
      expect(decimalDigitado("abc")).toBeNull();
    });
  });
});
