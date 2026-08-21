import { describe, expect, it } from "vitest";

import { usinadosResponseFromApi } from "./usinadosApi";

describe("usinadosResponseFromApi", () => {
  it("preserva ausência como nulo — nunca inventa zero", () => {
    const resposta = usinadosResponseFromApi({
      obraId: "obra-1",
      obraNome: "Obra Teste",
      precosVisiveis: true,
      materiais: [{
        material: "CBUQ Faixa C",
        unidade: "T",
        quantidadePrevista: 150,
        quantidadeUsinada: null,
        quantidadeAplicada: 135,
        quantidadeSobra: 15,
        quantidadeNaoAplicada: 15,
        totalRdos: 2,
        primeiraData: "2026-08-18",
        ultimaData: "2026-08-19",
        precoUnitario: 850,
        precoMotivo: null,
        valorAplicado: 114750,
        valorDesperdicado: 12750,
      }],
      totais: {
        valorAplicado: 114750,
        valorDesperdicado: 12750,
        materiaisSemPreco: 1,
      },
    });

    expect(resposta.materiais).toHaveLength(1);
    const material = resposta.materiais[0];
    expect(material.quantidadeUsinada).toBeNull();
    expect(material.quantidadePrevista).toBe(150);
    expect(material.valorDesperdicado).toBe(12750);
    expect(resposta.totais?.materiaisSemPreco).toBe(1);
  });

  it("aceita o financeiro invisível: totais nulos e sem valores", () => {
    const resposta = usinadosResponseFromApi({
      obraId: "obra-1",
      obraNome: "Obra Teste",
      precosVisiveis: false,
      materiais: [{
        material: "Brita 3/4",
        unidade: "M3",
        quantidadePrevista: null,
        quantidadeUsinada: 40,
        quantidadeAplicada: 38,
        quantidadeSobra: 2,
        quantidadeNaoAplicada: null,
        totalRdos: 1,
        primeiraData: null,
        ultimaData: null,
        precoUnitario: null,
        precoMotivo: null,
        valorAplicado: null,
        valorDesperdicado: null,
      }],
      totais: null,
    });

    expect(resposta.precosVisiveis).toBe(false);
    expect(resposta.totais).toBeNull();
    expect(resposta.materiais[0].precoUnitario).toBeNull();
  });

  it("carrega o motivo do preço quando ele veio", () => {
    const resposta = usinadosResponseFromApi({
      obraId: "obra-1",
      obraNome: "Obra Teste",
      precosVisiveis: true,
      materiais: [
        { material: "Areia", precoMotivo: "PRECO_AMBIGUO", totalRdos: 1 },
        { material: "Brita", precoMotivo: "SEM_PRECO", totalRdos: 1 },
        { material: "CBUQ", precoMotivo: "QUALQUER_COISA", totalRdos: 1 },
      ],
      totais: { materiaisSemPreco: 2 },
    });

    expect(resposta.materiais.map((item) => item.precoMotivo)).toEqual([
      "PRECO_AMBIGUO",
      "SEM_PRECO",
      null,
    ]);
  });

  it("descarta linha sem material e recusa resposta sem obra", () => {
    const resposta = usinadosResponseFromApi({
      obraId: "obra-1",
      obraNome: "Obra Teste",
      precosVisiveis: true,
      materiais: [{ material: "  " }, { material: "CBUQ", totalRdos: 1 }],
      totais: null,
    });
    expect(resposta.materiais).toHaveLength(1);

    expect(() => usinadosResponseFromApi({ materiais: [] })).toThrow(
      /não identifica a obra/,
    );
  });
});
