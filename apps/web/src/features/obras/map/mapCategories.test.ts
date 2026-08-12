import { describe, expect, it } from "vitest";

import { rotuloDaCategoria, rotuloDaFonte } from "./mapCategories";

describe("rótulos das camadas do mapa", () => {
  it("escreve a categoria em caixa de sentença", () => {
    // A categoria é persistida como PONTO_OPERACIONAL, que serve ao banco e
    // não ao texto. Nem a caixa alta nem a minúscula corrida servem de rótulo.
    expect(rotuloDaCategoria("PONTO_OPERACIONAL")).toBe("Ponto operacional");
    expect(rotuloDaCategoria("TRECHO")).toBe("Trecho");
    expect(rotuloDaCategoria("FRENTE_TRABALHO")).toBe("Frente trabalho");
  });

  it("capitaliza só a primeira palavra, como se escreve em português", () => {
    expect(rotuloDaCategoria("LOCALIZACAO_OBRA")).toBe("Localizacao obra");
  });

  /*
   * As origens conhecidas têm nome próprio, com o acento que o enum nunca
   * teve — "Gestao mapa" no balão denunciava a grafia do banco. O caminho
   * genérico continua atendendo o que não estiver no dicionário.
   */
  it("escreve as origens conhecidas como gente escreve", () => {
    expect(rotuloDaFonte("GESTAO_MAPA")).toBe("Gestão do mapa");
    expect(rotuloDaFonte("CAPTURA_CAMPO")).toBe("Captura de campo");
  });

  it("uma origem desconhecida ainda sai legível pelo caminho genérico", () => {
    expect(rotuloDaFonte("IMPORTACAO_LEGADA")).toBe("Importacao legada");
  });

  it("nomeia o que chega vazio em vez de devolver texto em branco", () => {
    expect(rotuloDaCategoria("   ")).toBe("Camada operacional");
    expect(rotuloDaCategoria(null)).toBe("Camada operacional");
    expect(rotuloDaFonte(undefined)).toBe("Origem não declarada");
  });
});
