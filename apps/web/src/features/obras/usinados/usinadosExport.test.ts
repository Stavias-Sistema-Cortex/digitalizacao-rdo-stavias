import { describe, expect, it } from "vitest";

import type { MaterialUsinado, ObraUsinados } from "./usinadosApi";
import { buildUsinadosCsv } from "./usinadosExport";

function material(partes: Partial<MaterialUsinado>): MaterialUsinado {
  return {
    material: "CBUQ Faixa C",
    unidade: "T",
    quantidadePrevista: 150,
    quantidadeUsinada: 150,
    quantidadeAplicada: 135.5,
    quantidadeSobra: 14.5,
    quantidadeNaoAplicada: 14.5,
    totalRdos: 2,
    primeiraData: "2026-08-18",
    ultimaData: "2026-08-19",
    precoUnitario: 850,
    precoMotivo: null,
    valorAplicado: 115175,
    valorDesperdicado: 12325,
    ...partes,
  };
}

function usinados(partes: Partial<ObraUsinados>): ObraUsinados {
  return {
    obraId: "obra-1",
    obraNome: "Obra Teste",
    precosVisiveis: true,
    materiais: [material({})],
    totais: {
      valorAplicado: 115175,
      valorDesperdicado: 12325,
      materiaisSemPreco: 0,
    },
    ...partes,
  };
}

describe("buildUsinadosCsv", () => {
  it("escreve pt-BR: ponto e vírgula, vírgula decimal, BOM e CRLF", () => {
    const resultado = buildUsinadosCsv(
      usinados({}),
      "2026-08-21T12:00:00.000Z",
    );

    expect(resultado.content.startsWith("\ufeff")).toBe(true);
    const linhas = resultado.content.slice(1).split("\r\n");
    expect(linhas[0]).toContain("Material;Unidade;Previsto");
    expect(linhas[1]).toContain("CBUQ Faixa C;T;150;150;135,5;14,5;14,5;2");
    expect(linhas[1]).toContain("850;115175;12325");
    expect(resultado.filename).toBe("usinados-obra-teste-2026-08-21.csv");
  });

  it("célula ausente fica vazia — vazio é não declarado, não zero", () => {
    const resultado = buildUsinadosCsv(
      usinados({
        materiais: [material({
          quantidadePrevista: null,
          quantidadeNaoAplicada: null,
          precoUnitario: null,
          precoMotivo: "SEM_PRECO",
          valorAplicado: null,
          valorDesperdicado: null,
        })],
      }),
      "2026-08-21T12:00:00.000Z",
    );

    const linha = resultado.content.split("\r\n")[1];
    expect(linha).toContain("CBUQ Faixa C;T;;150;135,5;;14,5;2");
    expect(linha).toContain("sem preço no catálogo da obra");
  });

  it("protege o Excel: aspas, fórmula e teto de caracteres", () => {
    const resultado = buildUsinadosCsv(
      usinados({
        materiais: [
          material({ material: 'Massa; tipo "especial"' }),
          material({ material: "=SOMA(A1:A9)" }),
          material({ material: `CBUQ ${"x".repeat(40_000)}` }),
        ],
      }),
      "2026-08-21T12:00:00.000Z",
    );

    const linhas = resultado.content.split("\r\n");
    expect(linhas[1].startsWith('"Massa; tipo ""especial"""')).toBe(true);
    // A fórmula vira texto: o apóstrofo impede a execução ao abrir.
    expect(linhas[2].startsWith("'=SOMA(A1:A9)")).toBe(true);
    // Nenhuma célula estoura o limite do Excel; o corte é declarado.
    expect(linhas[3].length).toBeLessThan(32_100);
    expect(linhas[3]).toContain("…");
  });

  it("nome de arquivo tem teto: obra comprida não quebra no Windows", () => {
    const resultado = buildUsinadosCsv(
      usinados({
        obraNome:
          "Restauração e Manutenção Rodoviária da Rodovia Estadual " +
          "Extremamente Comprida Entre Municípios de Nome Longo LTDA",
      }),
      "2026-08-21T12:00:00.000Z",
    );

    expect(resultado.filename.length).toBeLessThanOrEqual(75);
    expect(resultado.filename.startsWith("usinados-restauracao-e-")).toBe(
      true,
    );
    expect(resultado.filename.endsWith("-2026-08-21.csv")).toBe(true);
  });

  it("fecha com o total só das linhas com preço, avisando o que ficou fora", () => {
    const resultado = buildUsinadosCsv(
      usinados({
        totais: {
          valorAplicado: 115175,
          valorDesperdicado: 12325,
          materiaisSemPreco: 2,
        },
      }),
      "2026-08-21T12:00:00.000Z",
    );

    const rodape = resultado.content.trimEnd().split("\r\n").at(-1);
    expect(rodape).toContain("TOTAL (linhas com preço)");
    expect(rodape).toContain("115175;12325");
    expect(rodape).toContain("2 material(is) sem preço fora do total");
  });

  it("sem financeiro visível o CSV diz o porquê em cada linha", () => {
    const resultado = buildUsinadosCsv(
      usinados({
        precosVisiveis: false,
        materiais: [material({
          precoUnitario: null,
          valorAplicado: null,
          valorDesperdicado: null,
        })],
        totais: null,
      }),
      "2026-08-21T12:00:00.000Z",
    );

    expect(resultado.content).toContain(
      "financeiro não visível para este acesso",
    );
    expect(resultado.content).not.toContain("TOTAL");
  });
});
