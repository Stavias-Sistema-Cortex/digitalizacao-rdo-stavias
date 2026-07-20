import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

export interface TeamMemberDto {
  id: string;
  equipeId: string;
  colaboradorId: string;
  colaboradorNome: string;
  papelAcesso: "ALFA" | "BETA" | null;
  funcaoOperacionalId: string;
  funcaoCodigo: string;
  funcaoNome: string;
  responsavel: boolean;
  status: "ATIVO" | "ENCERRADO";
  inicioEm: string;
  fimEm: string | null;
  motivoEncerramento: string | null;
  versaoEntidade: number;
  criadoEm: string;
  atualizadoEm: string;
}

export interface TeamDto {
  id: string;
  obraPrincipalId: string;
  obraNome: string;
  nome: string;
  descricao: string | null;
  status: "ATIVA" | "ARQUIVADA";
  inicioValidadeEm: string;
  fimValidadeEm: string | null;
  versaoEntidade: number;
  criadoEm: string;
  atualizadoEm: string;
  membros: TeamMemberDto[];
}

export interface TeamPageDto {
  items: TeamDto[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

export interface OperationalRoleDto {
  id: string;
  codigo: string;
  nome: string;
  descricao: string | null;
  ativo: boolean;
  ordemExibicao: number;
  versaoEntidade: number;
  criadoEm: string;
  atualizadoEm: string;
}

export interface TeamHistoryEventDto {
  commitSeq: number;
  eventId: string;
  entityType: string;
  entityId: string;
  eventType: string;
  source: string;
  worksiteId: string | null;
  collaboratorId: string | null;
  occurredAt: string;
  recordedAt: string;
  entityVersion: number;
  payload: Record<string, unknown>;
}

export interface TeamHistoryPageDto {
  afterCommitSeq: number;
  nextCommitSeq: number;
  hasMore: boolean;
  limit: number;
  events: TeamHistoryEventDto[];
}

export interface TeamWorksiteDto {
  id: string;
  equipeId: string;
  obraId: string;
  obraNome: string;
  status: "ATIVO" | "ENCERRADO";
  inicioEm: string;
  fimEm: string | null;
  motivoEncerramento: string | null;
  versaoEntidade: number;
  criadoEm: string;
  atualizadoEm: string;
}

async function requireOkJson<T>(path: string, options?: RequestInit): Promise<T> {
  const response = await apiFetch(path, options);
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  return body as T;
}

function jsonOptions(method: "POST" | "PUT", body: unknown): RequestInit {
  return {
    method,
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  };
}

export async function fetchTeams(query: {
  obraId?: string;
  texto?: string;
  funcaoOperacionalId?: string;
  status?: string;
  vigenteEm?: string;
  page?: number;
  size?: number;
} = {}): Promise<TeamPageDto> {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && String(value).trim()) params.set(key, String(value));
  }
  const suffix = params.size ? `?${params}` : "";
  return requireOkJson<TeamPageDto>(`/equipes${suffix}`);
}

export async function fetchTeam(teamId: string): Promise<TeamDto> {
  return requireOkJson<TeamDto>(`/equipes/${encodeURIComponent(teamId)}`);
}

export async function fetchOperationalRoles(): Promise<OperationalRoleDto[]> {
  return requireOkJson<OperationalRoleDto[]>("/funcoes-operacionais");
}

export async function fetchTeamHistory(teamId: string): Promise<TeamHistoryPageDto> {
  return requireOkJson<TeamHistoryPageDto>(
    `/equipes/${encodeURIComponent(teamId)}/historico?afterCommitSeq=0&limit=100`,
  );
}

export async function fetchTeamWorksites(teamId: string): Promise<TeamWorksiteDto[]> {
  return requireOkJson<TeamWorksiteDto[]>(
    `/equipes/${encodeURIComponent(teamId)}/obras`,
  );
}

export async function createTeam(input: {
  id: string;
  obraId: string;
  nome: string;
  descricao: string | null;
  inicioValidadeEm: string;
}): Promise<TeamDto> {
  return requireOkJson<TeamDto>("/equipes", jsonOptions("POST", input));
}

export async function updateTeam(teamId: string, input: {
  nome: string;
  descricao: string | null;
  status: TeamDto["status"];
  inicioValidadeEm: string;
  fimValidadeEm: string | null;
  baseVersao: number;
  motivo: string;
}): Promise<TeamDto> {
  return requireOkJson<TeamDto>(
    `/equipes/${encodeURIComponent(teamId)}`,
    jsonOptions("PUT", input),
  );
}

export async function archiveTeam(
  teamId: string,
  baseVersao: number,
  motivo: string,
): Promise<TeamDto> {
  return requireOkJson<TeamDto>(
    `/equipes/${encodeURIComponent(teamId)}/arquivar`,
    jsonOptions("POST", { baseVersao, motivo }),
  );
}

export async function addTeamMember(teamId: string, input: {
  id: string;
  colaboradorId: string;
  funcaoOperacionalId: string;
  responsavel: boolean;
  inicioEm: string;
  baseVersao: null;
  motivo: string;
  concederAcessoObra: boolean;
}): Promise<TeamMemberDto> {
  return requireOkJson<TeamMemberDto>(
    `/equipes/${encodeURIComponent(teamId)}/membros`,
    jsonOptions("POST", input),
  );
}

export async function updateTeamMember(
  teamId: string,
  memberId: string,
  input: {
    id: string;
    colaboradorId: string;
    funcaoOperacionalId: string;
    responsavel: boolean;
    inicioEm: string;
    baseVersao: number;
    motivo: string;
    concederAcessoObra: boolean;
  },
): Promise<TeamMemberDto> {
  return requireOkJson<TeamMemberDto>(
    `/equipes/${encodeURIComponent(teamId)}/membros/${encodeURIComponent(memberId)}`,
    jsonOptions("PUT", input),
  );
}

export async function endTeamMember(
  teamId: string,
  memberId: string,
  baseVersao: number,
  motivo: string,
  encerradoEm: string,
): Promise<TeamMemberDto> {
  return requireOkJson<TeamMemberDto>(
    `/equipes/${encodeURIComponent(teamId)}/membros/${encodeURIComponent(memberId)}/encerrar`,
    jsonOptions("POST", { baseVersao, motivo, encerradoEm }),
  );
}
