import { describe, expect, it } from "vitest";

import type { AuthProfile } from "../auth/authSession";
import { mensagemDeListaVazia } from "./mensagemDeListaVazia";

function perfil(escopoGlobal: boolean): AuthProfile {
  return {
    colaboradorId: "col-1",
    nome: "Apontador",
    papelAcesso: escopoGlobal ? "ALFA" : "BETA",
    escopoGlobal,
    obraIds: [],
    expiraEm: "2026-12-31T00:00:00Z",
  };
}

describe("mensagem da lista de obras vazia", () => {
  /*
   * O caso novo: quem é Beta só enxerga obra vinculada, e entrar sem vínculo
   * nenhum é cadastro incompleto, não defeito. A frase tem de mandar a pessoa
   * pedir o vínculo em vez de procurar suporte.
   */
  it("aponta o vínculo quando o escopo não é global e nada foi carregado", () => {
    expect(mensagemDeListaVazia(0, perfil(false))).toContain("vinculado");
    expect(mensagemDeListaVazia(0, perfil(false))).toContain(
      "Gestão de Obras",
    );
  });

  it("não culpa o vínculo quando a busca é que esvaziou a lista", () => {
    expect(mensagemDeListaVazia(12, perfil(false))).toBe(
      "Nenhuma obra corresponde à busca.",
    );
  });

  it("não culpa o vínculo de quem tem escopo global", () => {
    expect(mensagemDeListaVazia(0, perfil(true))).toBe(
      "Nenhuma obra encontrada.",
    );
  });

  it("volta ao texto neutro sem sessão", () => {
    expect(mensagemDeListaVazia(0, null)).toBe("Nenhuma obra encontrada.");
  });
});
