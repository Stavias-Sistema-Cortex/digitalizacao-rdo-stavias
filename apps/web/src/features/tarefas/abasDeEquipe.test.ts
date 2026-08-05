import { describe, expect, it } from "vitest";

import type { TeamDto } from "../equipes/teamApi";
import {
  abasDeEquipe,
  contagemPorFiltro,
  filtrarAbas,
} from "./abasDeEquipe";

function equipe(
  nome: string,
  status: TeamDto["status"] = "ATIVA",
  id = nome,
): TeamDto {
  return {
    id,
    obraPrincipalId: "obra-1",
    obraNome: "Intervias 202",
    nome,
    descricao: null,
    status,
    inicioValidadeEm: "2026-01-01T00:00:00.000Z",
    fimValidadeEm: status === "ARQUIVADA" ? "2026-08-01T00:00:00.000Z" : null,
    versaoEntidade: 1,
    criadoEm: "2026-01-01T00:00:00.000Z",
    atualizadoEm: "2026-01-01T00:00:00.000Z",
    membros: [],
  };
}

describe("abas de equipe", () => {
  it("casa o nome do apontamento com a equipe cadastrada apesar de acento e caixa", () => {
    const abas = abasDeEquipe(
      ["Manutencao NOTURNA"],
      [equipe("Manutenção Noturna")],
    );

    expect(abas).toHaveLength(1);
    // O nome cadastrado é o que a pessoa escolheu; o do apontamento é digitação.
    expect(abas[0].nome).toBe("Manutenção Noturna");
    expect(abas[0].equipe?.id).toBe("Manutenção Noturna");
  });

  /**
   * Sem isto, arquivar uma equipe sem tarefa a apagaria da tela para sempre:
   * fora das ativas por estar arquivada, fora das arquivadas por não ter
   * apontamento — e ninguém conseguiria desarquivá-la.
   */
  it("mostra a equipe cadastrada que nenhum apontamento menciona", () => {
    const abas = abasDeEquipe([], [equipe("Sinalização", "ARQUIVADA")]);

    expect(abas.map((aba) => aba.nome)).toEqual(["Sinalização"]);
    expect(abas[0].arquivada).toBe(true);
    expect(filtrarAbas(abas, "ARQUIVADAS")).toHaveLength(1);
    expect(filtrarAbas(abas, "ATIVAS")).toHaveLength(0);
  });

  /** Nome vindo só de RDO não é entidade: não há o que arquivar. */
  it("deixa sem registro o nome que só existe no apontamento", () => {
    const abas = abasDeEquipe(["Equipe do Zé"], []);

    expect(abas[0].equipe).toBeNull();
    expect(abas[0].arquivada).toBe(false);
  });

  it("prefere a equipe ativa quando duas cadastradas dividem o nome", () => {
    const abas = abasDeEquipe(
      [],
      [
        equipe("Drenagem", "ARQUIVADA", "velha"),
        equipe("Drenagem", "ATIVA", "nova"),
      ],
    );

    expect(abas).toHaveLength(1);
    expect(abas[0].equipe?.id).toBe("nova");
    expect(abas[0].arquivada).toBe(false);
  });

  it("mantém a preferência pela ativa independentemente da ordem de leitura", () => {
    const abas = abasDeEquipe(
      [],
      [
        equipe("Drenagem", "ATIVA", "nova"),
        equipe("Drenagem", "ARQUIVADA", "velha"),
      ],
    );

    expect(abas[0].equipe?.id).toBe("nova");
  });

  it("conta cada recorte para o filtro não mentir sobre si", () => {
    const abas = abasDeEquipe(
      ["Terraplenagem"],
      [equipe("Sinalização", "ARQUIVADA"), equipe("Drenagem")],
    );

    expect(contagemPorFiltro(abas)).toEqual({
      ATIVAS: 2,
      ARQUIVADAS: 1,
      TODAS: 3,
    });
  });

  it("ordena por nome e ignora vazios", () => {
    const abas = abasDeEquipe(["  ", "Zeladoria", "Asfalto"], []);

    expect(abas.map((aba) => aba.nome)).toEqual(["Asfalto", "Zeladoria"]);
  });
});
