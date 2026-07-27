import type { TeamDto, TeamHistoryEventDto } from "./teamApi";

export interface TeamFilters {
  search: string;
  obraId: string;
  roleId: string;
  status: string;
  activeOn: string;
}

export function filterTeams(teams: TeamDto[], filters: TeamFilters): TeamDto[] {
  const search = filters.search.trim().toLocaleLowerCase("pt-BR");
  const activeAt = filters.activeOn ? new Date(`${filters.activeOn}T23:59:59`) : null;
  return teams.filter((team) => {
    if (filters.obraId && team.obraPrincipalId !== filters.obraId) return false;
    if (filters.status && team.status !== filters.status) return false;
    if (filters.roleId && !team.membros.some(
      (member) => member.funcaoOperacionalId === filters.roleId,
    )) return false;
    if (activeAt) {
      const start = new Date(team.inicioValidadeEm);
      const end = team.fimValidadeEm ? new Date(team.fimValidadeEm) : null;
      if (start > activeAt || (end && end < activeAt)) return false;
    }
    if (search && ![
      team.nome,
      team.obraNome,
      team.descricao,
      ...team.membros.flatMap((member) => [member.colaboradorNome, member.funcaoNome]),
    ].some((value) => value?.toLocaleLowerCase("pt-BR").includes(search))) {
      return false;
    }
    return true;
  });
}

const EVENT_LABELS: Record<string, string> = {
  EQUIPE_CRIADA: "Equipe criada",
  EQUIPE_ATUALIZADA: "Equipe atualizada",
  EQUIPE_ARQUIVADA: "Equipe arquivada",
  MEMBRO_EQUIPE_ADICIONADO: "Membro adicionado",
  MEMBRO_EQUIPE_ATUALIZADO: "Participação atualizada",
  MEMBRO_EQUIPE_ENCERRADO: "Participação encerrada",
  EQUIPE_OBRA_ASSOCIADA: "Obra associada",
  EQUIPE_OBRA_DESASSOCIADA: "Obra desassociada",
};

export function teamHistoryLabel(event: TeamHistoryEventDto): string {
  return EVENT_LABELS[event.eventType] ?? event.eventType.replaceAll("_", " ").toLocaleLowerCase("pt-BR");
}
