import { describe, expect, it } from "vitest";

import { escopoCresceu, marcaDoEscopo } from "./escopoDoCursor";

describe("a marca do escopo do cursor", () => {
  it("resume o alcance global numa palavra", () => {
    expect(marcaDoEscopo({ escopoGlobal: true, obraIds: [] })).toBe("GLOBAL");
    expect(marcaDoEscopo({ escopoGlobal: true, obraIds: ["a"] })).toBe(
      "GLOBAL",
    );
  });

  it("ordena e limpa as obras, para a mesma carteira dar a mesma marca", () => {
    expect(
      marcaDoEscopo({ escopoGlobal: false, obraIds: ["b", "a", " a ", ""] }),
    ).toBe("OBRAS:a,b");
  });

  it("sem sessão não há marca", () => {
    expect(marcaDoEscopo(null)).toBe("");
  });

  /*
   * Perfil gravado por uma versão anterior do aplicativo pode não trazer a
   * lista. Marca vazia significa "não sei", e "não sei" nunca rebobina.
   */
  it("perfil antigo, sem a lista de obras, não declara escopo", () => {
    const antigo = { escopoGlobal: false } as unknown as {
      escopoGlobal: boolean;
      obraIds: readonly string[];
    };
    expect(marcaDoEscopo(antigo)).toBe("");
    expect(escopoCresceu("OBRAS:a", marcaDoEscopo(antigo))).toBe(false);
  });
});

describe("quando o cursor precisa rebobinar", () => {
  /*
   * O defeito que isto conserta: a pessoa é vinculada na segunda-feira a uma
   * obra que já existia. Tudo o que aconteceu nela antes disso está atrás do
   * cursor, e o pull só pede o que veio depois. Tarefa não tem reconciliação
   * própria — some da tela dela, e só dela.
   */
  it("rebobina quando uma obra nova entra no alcance", () => {
    expect(escopoCresceu("OBRAS:a", "OBRAS:a,b")).toBe(true);
  });

  it("rebobina quando a pessoa passa a alcançar tudo", () => {
    expect(escopoCresceu("OBRAS:a", "GLOBAL")).toBe(true);
  });

  /*
   * Perder acesso não deixa buraco atrás: não há evento a buscar. Rebobinar
   * aí faria todo desligamento custar um recarregamento inteiro à toa.
   */
  it("não rebobina quando o alcance encolhe", () => {
    expect(escopoCresceu("OBRAS:a,b", "OBRAS:a")).toBe(false);
    expect(escopoCresceu("GLOBAL", "OBRAS:a")).toBe(false);
  });

  it("não rebobina quando nada mudou", () => {
    expect(escopoCresceu("OBRAS:a,b", "OBRAS:a,b")).toBe(false);
    expect(escopoCresceu("GLOBAL", "GLOBAL")).toBe(false);
  });

  /*
   * Registro gravado antes desta capacidade existir não diz com que escopo o
   * cursor andou. Rebobinar no escuro faria todo mundo recarregar o histórico
   * de uma vez na primeira abertura depois da atualização.
   */
  it("não rebobina quando não se sabe o escopo anterior", () => {
    expect(escopoCresceu(null, "OBRAS:a")).toBe(false);
    expect(escopoCresceu(undefined, "GLOBAL")).toBe(false);
    expect(escopoCresceu("", "OBRAS:a")).toBe(false);
  });

  it("troca de obra — uma sai, outra entra — conta como crescimento", () => {
    expect(escopoCresceu("OBRAS:a", "OBRAS:b")).toBe(true);
  });
});
