import type { TeamDto } from "../equipes/teamApi";
import { normalizeNome } from "./nomeMatcher";

/**
 * Uma aba de equipe na tela de Tarefas.
 *
 * A aba nasceu de duas fontes que não se conhecem: os nomes escritos nas
 * alocações de RDO e nas tarefas (texto solto) e as equipes cadastradas
 * (entidade com id, versão e status). Só a segunda pode ser arquivada; a
 * primeira é um nome que alguém digitou num apontamento e não existe como
 * registro em lugar nenhum.
 */
export interface EquipeNaAba {
  /** Como a equipe aparece para quem lê — o nome cadastrado tem precedência. */
  nome: string;
  /** O registro real, quando existe um com esse nome nesta obra. */
  equipe: TeamDto | null;
  arquivada: boolean;
}

export type FiltroDeEquipe = "ATIVAS" | "ARQUIVADAS" | "TODAS";

export function chaveDaEquipe(nome: string): string {
  return normalizeNome(nome);
}

/**
 * Junta os nomes derivados dos apontamentos com as equipes cadastradas.
 *
 * As equipes cadastradas entram mesmo quando nome nenhum as menciona. Sem
 * isso, arquivar uma equipe que ainda não tem tarefa a apagaria da tela para
 * sempre: ela sairia da lista de ativas por estar arquivada e não estaria na
 * de arquivadas por não ter apontamento — e ninguém conseguiria desarquivá-la.
 */
export function abasDeEquipe(
  nomesDerivados: string[],
  equipesCadastradas: TeamDto[],
): EquipeNaAba[] {
  const porChave = new Map<string, EquipeNaAba>();

  for (const nome of nomesDerivados) {
    const limpo = nome.trim();
    if (!limpo) {
      continue;
    }
    const chave = chaveDaEquipe(limpo);
    if (!chave || porChave.has(chave)) {
      continue;
    }
    porChave.set(chave, { nome: limpo, equipe: null, arquivada: false });
  }

  for (const equipe of equipesCadastradas) {
    const chave = chaveDaEquipe(equipe.nome);
    if (!chave) {
      continue;
    }
    const existente = porChave.get(chave);
    const arquivada = equipe.status === "ARQUIVADA";

    if (!existente) {
      porChave.set(chave, { nome: equipe.nome, equipe, arquivada });
      continue;
    }

    /*
     * Um mesmo nome pode ter duas equipes cadastradas — uma arquivada e a que
     * a substituiu. A ativa manda: é ela que recebe tarefa nova, e é sobre ela
     * que a lixeira deve agir.
     */
    if (existente.equipe && !existente.arquivada) {
      continue;
    }
    porChave.set(chave, { nome: equipe.nome, equipe, arquivada });
  }

  return [...porChave.values()].sort((esquerda, direita) =>
    esquerda.nome.localeCompare(direita.nome, "pt-BR"),
  );
}

export function filtrarAbas(
  abas: EquipeNaAba[],
  filtro: FiltroDeEquipe,
): EquipeNaAba[] {
  if (filtro === "TODAS") {
    return abas;
  }
  const querArquivadas = filtro === "ARQUIVADAS";
  return abas.filter((aba) => aba.arquivada === querArquivadas);
}

/** Quantas equipes cada recorte tem, para o filtro não mentir sobre si. */
export function contagemPorFiltro(abas: EquipeNaAba[]): Record<
  FiltroDeEquipe,
  number
> {
  const arquivadas = abas.filter((aba) => aba.arquivada).length;
  return {
    ATIVAS: abas.length - arquivadas,
    ARQUIVADAS: arquivadas,
    TODAS: abas.length,
  };
}
