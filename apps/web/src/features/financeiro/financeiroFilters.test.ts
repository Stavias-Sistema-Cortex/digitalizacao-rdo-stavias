import { describe, expect, it } from "vitest";

import {
  applyManualFilters,
  financeQueryParams,
  filtersFromSearchParams,
  filtersToSearchParams,
} from "./financeiroFilters";

describe("financeiroFilters", () => {
  it("preserva filtros reconhecíveis na URL sem inventar valores", () => {
    const filters = filtersFromSearchParams(new URLSearchParams(
      "obra=obra-1&de=2026-07-01&ate=2026-07-31&fornecedor=forn-1" +
      "&centro=cc-1&categoria=cat-1&status=st-1&prioridade=ALTA" +
      "&tipo=PAGAR&moeda=BRL&q=brita",
    ));

    expect(filters).toEqual({
      obraId: "obra-1",
      de: "2026-07-01",
      ate: "2026-07-31",
      fornecedorId: "forn-1",
      centroCustoId: "cc-1",
      categoriaId: "cat-1",
      statusId: "st-1",
      prioridade: "ALTA",
      tipo: "PAGAR",
      moeda: "BRL",
      query: "brita",
      manualMode: "ALL",
      manualRules: [],
    });

    expect(filtersToSearchParams(filters).toString()).toContain(
      "fornecedor=forn-1",
    );
  });

  it("envia ao backend somente filtros preenchidos e o escopo exato", () => {
    const params = financeQueryParams({
      obraId: "obra-9",
      de: "",
      ate: "2026-07-31",
      fornecedorId: "",
      centroCustoId: "cc-2",
      categoriaId: "",
      statusId: "",
      prioridade: "",
      tipo: "RECEBER",
      moeda: "BRL",
      query: "  concreto  ",
      manualMode: "ALL",
      manualRules: [],
    });

    expect(params.get("obraId")).toBe("obra-9");
    expect(params.get("ate")).toBe("2026-07-31");
    expect(params.get("centroCustoId")).toBe("cc-2");
    expect(params.get("tipo")).toBe("RECEBER");
    expect(params.get("query")).toBe("concreto");
    expect(params.has("de")).toBe(false);
    expect(params.has("fornecedorId")).toBe(false);
  });

  it("serializa regras manuais canônicas, tipadas e limitadas", () => {
    const filters = filtersFromSearchParams(new URLSearchParams(
      "obra=obra-1&fm=ANY" +
      "&f=descricao%7CCONTAINS%7Cponte%7C" +
      "&f=valor%7CBETWEEN%7C1000.00%7C2500.00" +
      "&f=campo_sql%7CEQUALS%7Cx%7C",
    ));

    expect(filters.manualMode).toBe("ANY");
    expect(filters.manualRules).toEqual([
      {
        id: "manual-0",
        field: "descricao",
        operator: "CONTAINS",
        value: "ponte",
        secondValue: "",
      },
      {
        id: "manual-1",
        field: "valor",
        operator: "BETWEEN",
        value: "1000.00",
        secondValue: "2500.00",
      },
    ]);

    const serialized = filtersToSearchParams(filters);
    expect(serialized.get("fm")).toBe("ANY");
    expect(serialized.getAll("f")).toEqual([
      "descricao|CONTAINS|ponte|",
      "valor|BETWEEN|1000.00|2500.00",
    ]);
    expect(financeQueryParams(filters).has("f")).toBe(false);
  });

  it("preserva o rascunho de uma regra manual enquanto o usuário preenche", () => {
    const filters = filtersFromSearchParams(new URLSearchParams(
      "obra=obra-1&f=descricao%7CCONTAINS%7C%7C",
    ));

    expect(filters.manualRules).toEqual([
      {
        id: "manual-0",
        field: "descricao",
        operator: "CONTAINS",
        value: "",
        secondValue: "",
      },
    ]);
    expect(filtersToSearchParams(filters).getAll("f")).toEqual([
      "descricao|CONTAINS||",
    ]);
  });

  it("aplica todos ou qualquer filtro somente por campos allowlisted", () => {
    const rows = [
      {
        descricao: "Concreto da ponte",
        numero: "NF-101",
        fornecedor: "Pedreira Horizonte",
        status: "APROVADA",
        tipo: "PAGAR",
        valor: 2500,
        data: "2026-07-14",
      },
      {
        descricao: "Locação de escavadeira",
        numero: "NF-202",
        fornecedor: "Máquinas Sul",
        status: "PENDENTE",
        tipo: "PAGAR",
        valor: 9000,
        data: "2026-07-20",
      },
    ];
    const rules = [
      {
        id: "one",
        field: "descricao" as const,
        operator: "CONTAINS" as const,
        value: "ponte",
        secondValue: "",
      },
      {
        id: "two",
        field: "valor" as const,
        operator: "GREATER_THAN" as const,
        value: "5000",
        secondValue: "",
      },
    ];

    expect(applyManualFilters(rows, rules, "ALL")).toEqual([]);
    expect(applyManualFilters(rows, rules, "ANY")).toEqual(rows);
  });
});
