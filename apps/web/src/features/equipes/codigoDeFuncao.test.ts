import { describe, expect, it } from "vitest";

import {
  normalizarCodigoDeFuncao,
  problemaNoCodigoDeFuncao,
} from "./codigoDeFuncao";

describe("código da função operacional", () => {
  it("mostra o código como o servidor vai gravá-lo", () => {
    expect(normalizarCodigoDeFuncao(" operador de rolo ")).toBe(
      "OPERADOR_DE_ROLO",
    );
    expect(normalizarCodigoDeFuncao("meio-oficial")).toBe("MEIO_OFICIAL");
  });

  it("aceita o que o servidor aceita", () => {
    expect(problemaNoCodigoDeFuncao("ENCARREGADO")).toBeNull();
    expect(problemaNoCodigoDeFuncao("operador de rolo")).toBeNull();
    expect(problemaNoCodigoDeFuncao("AJUDANTE_2")).toBeNull();
  });

  /*
   * O servidor recusa acento e pontuação depois de normalizar. Repetir a regra
   * aqui é o que permite dizer isso enquanto se digita, em vez de a pessoa
   * descobrir ao salvar.
   */
  it("explica qual regra foi quebrada, não apenas que houve erro", () => {
    expect(problemaNoCodigoDeFuncao("TOPÓGRAFO")).toBe(
      "O código aceita apenas letras sem acento, números e sublinhado.",
    );
    expect(problemaNoCodigoDeFuncao("MOTORISTA/CAMINHÃO")).toBe(
      "O código aceita apenas letras sem acento, números e sublinhado.",
    );
  });

  it("trata o vazio e o só-espaço como código ausente", () => {
    expect(problemaNoCodigoDeFuncao("")).toBe("Informe o código da função.");
    expect(problemaNoCodigoDeFuncao("   ")).toBe("Informe o código da função.");
  });

  it("respeita o limite da coluna", () => {
    expect(problemaNoCodigoDeFuncao("A".repeat(80))).toBeNull();
    expect(problemaNoCodigoDeFuncao("A".repeat(81))).toBe(
      "O código pode ter no máximo 80 caracteres.",
    );
  });
});
