import { describe, expect, it } from "vitest";

import type { LocalServiceCatalogRow } from "../financeiro/servicePriceRepository";
import type { MaterialDraft } from "./rdo.types";
import {
  precoLocalDoMaterial,
  valorLocalDosMateriais,
} from "./precoDosMateriais";

const HOJE = "2026-08-21";

function material(partes: Partial<MaterialDraft>): MaterialDraft {
  return {
    localId: "material-1",
    materialNome: "CBUQ Faixa C",
    unidade: "T",
    quantidadePrevista: "",
    quantidadeUsinada: "",
    quantidadeAplicada: "",
    quantidadeSobra: "",
    notaFiscal: "",
    fornecedor: "",
    observacoes: "",
    ...partes,
  };
}

function linhaDoCatalogo(input: {
  nome: string;
  unidade?: string;
  preco?: string;
  statusServico?: string;
  statusPreco?: string;
  validFrom?: string;
  effectiveValidTo?: string | null;
  precos?: Array<{
    unidade?: string;
    preco: string;
    status?: string;
    effectiveValidTo?: string | null;
  }>;
}): LocalServiceCatalogRow {
  const versoes = input.precos ?? [{
    unidade: input.unidade ?? "T",
    preco: input.preco ?? "850.0000",
    status: input.statusPreco ?? "ACTIVE",
    effectiveValidTo: input.effectiveValidTo ?? null,
  }];
  return {
    service: {
      id: `service-${input.nome}`,
      code: "SVC-1",
      name: input.nome,
      description: null,
      status: input.statusServico ?? "ACTIVE",
      syncStatus: "SYNCED",
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z",
      lastError: null,
    },
    priceVersions: versoes.map((versao, indice) => ({
      id: `price-${input.nome}-${indice}`,
      obraId: "11111111-1111-4111-8111-111111111111",
      serviceId: `service-${input.nome}`,
      unit: versao.unidade ?? "T",
      currency: "BRL",
      version: indice + 1,
      unitPrice: versao.preco,
      contractedQuantity: null,
      validFrom: input.validFrom ?? "2026-01-01",
      validTo: null,
      source: null,
      supersedesId: null,
      status: versao.status ?? "ACTIVE",
      effectiveValidTo: versao.effectiveValidTo ?? null,
      entityVersion: 1,
      syncStatus: "SYNCED",
      createdAt: "2026-01-01T00:00:00.000Z",
      updatedAt: "2026-01-01T00:00:00.000Z",
      lastError: null,
    })),
  } as LocalServiceCatalogRow;
}

describe("precoLocalDoMaterial", () => {
  it("casa nome e unidade sem se importar com caixa e grafia da unidade", () => {
    const catalogo = [linhaDoCatalogo({ nome: "CBUQ Faixa C" })];

    expect(
      precoLocalDoMaterial(
        { materialNome: "cbuq faixa c", unidade: "t" },
        catalogo,
        HOJE,
      ),
    ).toBe(850);
  });

  it("recusa o ambíguo, o vencido, o cancelado e o serviço inativo", () => {
    expect(precoLocalDoMaterial(
      { materialNome: "Areia", unidade: "M3" },
      [
        linhaDoCatalogo({ nome: "Areia", unidade: "M3", preco: "120" }),
        linhaDoCatalogo({ nome: "Areia", unidade: "M3", preco: "110" }),
      ],
      HOJE,
    )).toBeNull();

    expect(precoLocalDoMaterial(
      { materialNome: "CBUQ Faixa C", unidade: "T" },
      [linhaDoCatalogo({
        nome: "CBUQ Faixa C",
        effectiveValidTo: "2026-07-31",
      })],
      HOJE,
    )).toBeNull();

    expect(precoLocalDoMaterial(
      { materialNome: "CBUQ Faixa C", unidade: "T" },
      [linhaDoCatalogo({ nome: "CBUQ Faixa C", statusPreco: "CANCELLED" })],
      HOJE,
    )).toBeNull();

    expect(precoLocalDoMaterial(
      { materialNome: "CBUQ Faixa C", unidade: "T" },
      [linhaDoCatalogo({ nome: "CBUQ Faixa C", statusServico: "DELETED" })],
      HOJE,
    )).toBeNull();
  });

  it("a versão substituída cede ao preço ativo em vez de criar ambiguidade", () => {
    const catalogo = [linhaDoCatalogo({
      nome: "CBUQ Faixa C",
      precos: [
        { preco: "800.0000", status: "SUPERSEDED" },
        { preco: "850.0000", status: "ACTIVE" },
      ],
    })];

    expect(
      precoLocalDoMaterial(
        { materialNome: "CBUQ Faixa C", unidade: "T" },
        catalogo,
        HOJE,
      ),
    ).toBe(850);
  });
});

describe("valorLocalDosMateriais", () => {
  const catalogo = [linhaDoCatalogo({ nome: "CBUQ Faixa C" })];

  it("soma aplicado e sobra ao preço, e conta quem ficou sem preço", () => {
    const resultado = valorLocalDosMateriais(
      [
        material({
          quantidadeAplicada: 100,
          quantidadeUsinada: 110,
        }),
        material({
          localId: "material-2",
          materialNome: "Brita 3/4",
          unidade: "M3",
          quantidadeAplicada: 40,
        }),
      ],
      catalogo,
      HOJE,
    );

    expect(resultado.valorAplicado).toBe(85_000);
    // Sobra calculada (110 − 100 = 10) ao mesmo preço.
    expect(resultado.valorSobra).toBe(8_500);
    expect(resultado.materiaisComPreco).toBe(1);
    expect(resultado.materiaisSemPreco).toBe(1);
  });

  it("quantidade não declarada não vira zero: sem números, valores nulos", () => {
    const resultado = valorLocalDosMateriais(
      [material({})],
      catalogo,
      HOJE,
    );

    expect(resultado.valorAplicado).toBeNull();
    expect(resultado.valorSobra).toBeNull();
    expect(resultado.materiaisComPreco).toBe(1);
  });

  it("sobra negativa (aplicou mais do que usinou) não vira desperdício", () => {
    const resultado = valorLocalDosMateriais(
      [material({
        quantidadeAplicada: 120,
        quantidadeUsinada: 100,
      })],
      catalogo,
      HOJE,
    );

    expect(resultado.valorAplicado).toBe(102_000);
    expect(resultado.valorSobra).toBeNull();
  });

  it("material em branco não conta nem como sem preço", () => {
    const resultado = valorLocalDosMateriais(
      [material({ materialNome: "   " })],
      catalogo,
      HOJE,
    );

    expect(resultado.materiaisComPreco).toBe(0);
    expect(resultado.materiaisSemPreco).toBe(0);
    expect(resultado.valorAplicado).toBeNull();
  });
});
