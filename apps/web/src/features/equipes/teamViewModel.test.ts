import { describe, expect, it } from "vitest";

import type { TeamDto } from "./teamApi";
import { filterTeams, teamHistoryLabel } from "./teamViewModel";

function team(): TeamDto {
  return {
    id: "team-1",
    obraPrincipalId: "obra-1",
    obraNome: "Contorno Norte",
    nome: "Drenagem",
    descricao: "Dispositivos profundos",
    status: "ATIVA",
    inicioValidadeEm: "2026-07-01T00:00:00",
    fimValidadeEm: null,
    versaoEntidade: 2,
    criadoEm: "2026-07-01T00:00:00",
    atualizadoEm: "2026-07-15T00:00:00",
    membros: [{
      id: "member-1",
      equipeId: "team-1",
      colaboradorId: "user-1",
      colaboradorNome: "Ana Costa",
      papelAcesso: "BETA",
      funcaoOperacionalId: "role-1",
      funcaoCodigo: "ENC",
      funcaoNome: "Encarregada",
      responsavel: true,
      status: "ATIVO",
      inicioEm: "2026-07-01T00:00:00",
      fimEm: null,
      motivoEncerramento: null,
      versaoEntidade: 1,
      criadoEm: "2026-07-01T00:00:00",
      atualizadoEm: "2026-07-01T00:00:00",
    }],
  };
}

describe("teamViewModel", () => {
  it("busca por equipe, obra, membro e função usando dados reais", () => {
    for (const search of ["drenagem", "contorno", "ana", "encarregada"]) {
      expect(filterTeams([team()], {
        search,
        obraId: "",
        roleId: "",
        status: "",
        activeOn: "",
      })).toHaveLength(1);
    }
  });

  it("aplica obra, função, status e vigência sem inventar resultados", () => {
    expect(filterTeams([team()], {
      search: "",
      obraId: "obra-1",
      roleId: "role-1",
      status: "ATIVA",
      activeOn: "2026-07-10",
    })).toHaveLength(1);
    expect(filterTeams([team()], {
      search: "",
      obraId: "obra-2",
      roleId: "",
      status: "",
      activeOn: "",
    })).toHaveLength(0);
  });

  it("traduz eventos conhecidos e preserva tipo desconhecido consultável", () => {
    const base = {
      commitSeq: 1,
      eventId: "event-1",
      entityType: "EQUIPE",
      entityId: "team-1",
      source: "ONLINE",
      worksiteId: "obra-1",
      collaboratorId: "user-1",
      occurredAt: "2026-07-15T12:00:00",
      recordedAt: "2026-07-15T12:00:00",
      entityVersion: 1,
      payload: {},
    };
    expect(teamHistoryLabel({ ...base, eventType: "EQUIPE_CRIADA" })).toBe("Equipe criada");
    expect(teamHistoryLabel({ ...base, eventType: "CONFLITO_DETECTADO" })).toBe("conflito detectado");
  });
});
