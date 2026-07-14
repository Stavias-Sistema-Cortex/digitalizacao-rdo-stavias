import { describe, expect, it } from "vitest";

import {
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
    });

    expect(params.get("obraId")).toBe("obra-9");
    expect(params.get("ate")).toBe("2026-07-31");
    expect(params.get("centroCustoId")).toBe("cc-2");
    expect(params.get("tipo")).toBe("RECEBER");
    expect(params.get("query")).toBe("concreto");
    expect(params.has("de")).toBe(false);
    expect(params.has("fornecedorId")).toBe(false);
  });
});
