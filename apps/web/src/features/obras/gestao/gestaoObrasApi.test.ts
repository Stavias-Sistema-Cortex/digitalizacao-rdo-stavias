import { describe, expect, it } from "vitest";

import {
  filtrarObrasOperacionais,
  validarNovaObra,
  type ObraAdminApi,
} from "./gestaoObrasApi";

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

describe("filtrarObrasOperacionais", () => {
  it("remove arquivadas do seletor sem apagar ou alterar a resposta", () => {
    const base: ObraAdminApi = {
      id: "obra-1",
      codigoContrato: "CTR-1",
      codigoCw: null,
      codigoInterno: null,
      nome: "Obra 1",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
      atualizadoEm: "2026-07-28T12:00:00.000Z",
      arquivadoEm: null,
      versaoLinha: 3,
    };
    const archived: ObraAdminApi = {
      ...base,
      id: "obra-2",
      arquivadoEm: "2026-07-28T13:00:00.000Z",
    };
    const response = [base, archived];

    expect(filtrarObrasOperacionais(response)).toEqual([base]);
    expect(response).toEqual([base, archived]);
  });
});
