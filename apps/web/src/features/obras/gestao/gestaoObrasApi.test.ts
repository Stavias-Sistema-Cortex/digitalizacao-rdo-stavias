import { describe, expect, it } from "vitest";

import { validarNovaObra } from "./gestaoObrasApi";

describe("validarNovaObra", () => {
  it("aceita uma obra mínima válida", () => {
    expect(
      validarNovaObra({ codigoContrato: "CW38386", nome: "4ª Intervenção" }),
    ).toEqual([]);
  });

  it("exige código do contrato e nome", () => {
    const erros = validarNovaObra({ codigoContrato: "  ", nome: "" });
    expect(erros).toContain("Informe o código do contrato.");
    expect(erros).toContain("Informe o nome da obra.");
  });

  it("rejeita UF com tamanho diferente de 2", () => {
    const erros = validarNovaObra({
      codigoContrato: "CW1",
      nome: "Obra",
      uf: "SPX",
    });
    expect(erros).toContain("UF deve ter exatamente 2 caracteres.");
  });

  it("aceita UF vazia (opcional)", () => {
    expect(
      validarNovaObra({ codigoContrato: "CW1", nome: "Obra", uf: "" }),
    ).toEqual([]);
  });
});
