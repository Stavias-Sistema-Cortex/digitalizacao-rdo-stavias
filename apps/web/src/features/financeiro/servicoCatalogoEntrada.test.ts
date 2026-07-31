import { describe, expect, it } from "vitest";

import {
  normalizarUnidade,
  reconhecerServico,
  sugerirCodigoDoServico,
} from "./servicoCatalogoEntrada";

describe("normalizarUnidade", () => {
  it("escreve metro quadrado no símbolo, venha como vier", () => {
    for (const digitado of ["m2", "M2", "m²", "M²", "m 2", "metro quadrado"]) {
      expect(normalizarUnidade(digitado)).toBe("M²");
    }
  });

  it("escreve metro cúbico no símbolo", () => {
    for (const digitado of ["m3", "M3", "m³", "metro cubico", "metro cúbico"]) {
      expect(normalizarUnidade(digitado)).toBe("M³");
    }
  });

  it("resolve as demais unidades de rodovia", () => {
    expect(normalizarUnidade("ml")).toBe("M");
    expect(normalizarUnidade("metro linear")).toBe("M");
    expect(normalizarUnidade("ton")).toBe("T");
    expect(normalizarUnidade("tonelada")).toBe("T");
    expect(normalizarUnidade("hora")).toBe("H");
    expect(normalizarUnidade("unid")).toBe("UN");
  });

  it("não bloqueia uma unidade que ainda não foi mapeada", () => {
    // A tabela é conveniência, não restrição: uma unidade legítima e
    // desconhecida precisa passar em vez de travar o lançamento.
    expect(normalizarUnidade("cm3/s")).toBe("CM3/S");
  });

  it("devolve vazio para entrada em branco", () => {
    expect(normalizarUnidade("   ")).toBe("");
  });
});

const catalogo = [
  { id: "s-1", code: "FRESAGEM", name: "Fresagem" },
  { id: "s-2", code: "CBUQ-FAIXA-C", name: "Concreto asfáltico faixa C" },
];

describe("reconhecerServico", () => {
  it("reconhece o serviço já existente pelo nome digitado", () => {
    expect(reconhecerServico(catalogo, "fresagem")?.id).toBe("s-1");
    expect(reconhecerServico(catalogo, "  FRESAGEM  ")?.id).toBe("s-1");
  });

  it("ignora acento, como a busca do RDO", () => {
    expect(
      reconhecerServico(catalogo, "concreto asfaltico faixa c")?.id,
    ).toBe("s-2");
  });

  it("reconhece pelo código", () => {
    expect(reconhecerServico(catalogo, "cbuq-faixa-c")?.id).toBe("s-2");
  });

  it("devolve nulo para serviço realmente novo", () => {
    expect(reconhecerServico(catalogo, "microrrevestimento")).toBeNull();
    expect(reconhecerServico(catalogo, "fres")).toBeNull();
  });
});

describe("sugerirCodigoDoServico", () => {
  it("deriva o código do nome, sem acento e sem pontuação", () => {
    expect(sugerirCodigoDoServico("Concreto asfáltico faixa C")).toBe(
      "CONCRETO-ASFALTICO-FAIXA-C",
    );
  });

  it("desvia de um código já ocupado em vez de propor colisão", () => {
    expect(sugerirCodigoDoServico("Fresagem", catalogo)).toBe("FRESAGEM-2");
  });

  it("devolve vazio quando o nome ainda não diz nada", () => {
    expect(sugerirCodigoDoServico("   ")).toBe("");
    expect(sugerirCodigoDoServico("---")).toBe("");
  });
});
