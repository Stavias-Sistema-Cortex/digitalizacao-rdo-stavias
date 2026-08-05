import { getCortexDb } from "../../lib/db/cortexDb";
import type { OperationalEntityRef } from "../../lib/db/db.types";
import { getSyncState, updateSyncState } from "../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../lib/sync/localMutationCoordinator";
import { isCanonicalOutboxMutation } from "../../lib/sync/mutationEnvelope";
import { getSession } from "../auth/authSession";
import type {
  OperationalRoleDto,
  TeamDto,
  TeamHistoryEventDto,
  TeamMemberDto,
  TeamWorksiteDto,
} from "./teamApi";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export interface QueueCreateTeamInput {
  id: string;
  obraId: string;
  obraNome: string;
  nome: string;
  descricao: string | null;
  inicioValidadeEm: string;
}

interface TeamMutationIdentity {
  userId: string;
  deviceId: string;
}

async function teamMutationIdentity(): Promise<TeamMutationIdentity> {
  const session = getSession();
  if (
    !session ||
    !session.escopoGlobal ||
    session.papelAcesso !== "ALFA"
  ) {
    throw new Error(
      "Alterações de equipe exigem uma sessão Alfa com escopo global.",
    );
  }
  const state = await getSyncState();
  const deviceId =
    state.usuarioId === session.colaboradorId &&
      state.deviceId &&
      UUID_PATTERN.test(state.deviceId)
      ? state.deviceId
      : crypto.randomUUID();
  if (
    state.deviceId !== deviceId ||
    state.usuarioId !== session.colaboradorId
  ) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }
  return { userId: session.colaboradorId, deviceId };
}

async function activeTeamMutations(teamId: string) {
  const mutations = await (await getCortexDb()).getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    teamId,
  );
  return mutations
    .filter(
      (mutation) =>
        mutation.entidadeTipo === "EQUIPE" &&
        ["PENDING", "ERROR", "SYNCING"].includes(mutation.status),
    )
    .map((mutation) => {
      if (!isCanonicalOutboxMutation(mutation)) {
        throw new Error(
          "A equipe possui uma mutação legada que exige revisão.",
        );
      }
      return mutation;
    })
    .sort((left, right) =>
      left.occurredAt.localeCompare(right.occurredAt) ||
      left.clientMutationId.localeCompare(right.clientMutationId)
    );
}

function teamTransportSnapshot(
  team: TeamDto,
  extras: Record<string, unknown> = {},
): Record<string, unknown> {
  return {
    id: team.id,
    obraId: team.obraPrincipalId,
    obraPrincipalId: team.obraPrincipalId,
    nome: team.nome,
    descricao: team.descricao,
    status: team.status,
    inicioValidadeEm: team.inicioValidadeEm,
    fimValidadeEm: team.fimValidadeEm,
    ...extras,
  };
}

export async function queueCreateTeam(
  input: QueueCreateTeamInput,
): Promise<TeamDto> {
  const identity = await teamMutationIdentity();
  const timestamp = new Date().toISOString();
  const clientMutationId = crypto.randomUUID();
  const team: TeamDto = {
    id: input.id,
    obraPrincipalId: input.obraId,
    obraNome: input.obraNome,
    nome: input.nome.trim(),
    descricao: input.descricao?.trim() || null,
    status: "ATIVA",
    inicioValidadeEm: input.inicioValidadeEm,
    fimValidadeEm: null,
    versaoEntidade: 0,
    criadoEm: timestamp,
    atualizadoEm: timestamp,
    membros: [],
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    pendingMutationId: clientMutationId,
  };
  const payload = teamTransportSnapshot(team);

  await commitLocalMutation({
    ...identity,
    clientMutationId,
    obraId: input.obraId,
    entityType: "EQUIPE",
    entityId: input.id,
    entityName: team.nome,
    operation: "CREATE",
    transportOperation: "CRIAR_EQUIPE",
    baseVersion: null,
    occurredAt: timestamp,
    previousSnapshot: {},
    nextSnapshot: payload,
    principalSnapshot: { ...team },
    expectedPrincipalSnapshot: null,
    expectedActiveMutationIds: [],
    eventType: "EQUIPE_CRIADA",
    colaboradorId: identity.userId,
    relatedEntities: [{ tipo: "OBRA", id: input.obraId, nome: input.obraNome }],
    write: () => [{
      store: "teams",
      value: team,
      principal: true,
      insertOnly: true,
    }],
  });
  return team;
}

export async function queueUpdateTeam(
  existing: TeamDto,
  input: {
    nome: string;
    descricao: string | null;
    inicioValidadeEm: string;
    fimValidadeEm: string | null;
    motivo: string;
  },
): Promise<TeamDto> {
  const identity = await teamMutationIdentity();
  const pending = await activeTeamMutations(existing.id);
  const timestamp = new Date().toISOString();
  const clientMutationId = crypto.randomUUID();
  const updated: TeamDto = {
    ...existing,
    nome: input.nome.trim(),
    descricao: input.descricao?.trim() || null,
    inicioValidadeEm: input.inicioValidadeEm,
    fimValidadeEm: input.fimValidadeEm,
    atualizadoEm: timestamp,
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    pendingMutationId: clientMutationId,
  };
  const previousPayload = teamTransportSnapshot(existing);
  const nextPayload = teamTransportSnapshot(updated, {
    motivo: input.motivo.trim(),
  });
  const tail = pending.at(-1) ?? null;

  await commitLocalMutation({
    ...identity,
    clientMutationId,
    obraId: existing.obraPrincipalId,
    entityType: "EQUIPE",
    entityId: existing.id,
    entityName: updated.nome,
    operation: "UPDATE",
    transportOperation: "ATUALIZAR_EQUIPE",
    baseVersion: existing.versaoEntidade + pending.length,
    occurredAt: timestamp,
    previousSnapshot: previousPayload,
    nextSnapshot: nextPayload,
    principalSnapshot: { ...updated },
    expectedPrincipalSnapshot: { ...existing },
    expectedActiveMutationIds: pending.map((mutation) => mutation.clientMutationId),
    eventType: "EQUIPE_ATUALIZADA",
    colaboradorId: identity.userId,
    causationId: tail?.clientMutationId ?? null,
    dependsOnMutationIds: tail ? [tail.clientMutationId] : [],
    relatedEntities: [{
      tipo: "OBRA",
      id: existing.obraPrincipalId,
      nome: existing.obraNome,
    }],
    write: () => [{ store: "teams", value: updated, principal: true }],
  });
  return updated;
}

/**
 * Arquiva a equipe sem exigir justificativa.
 *
 * O `motivo` continua aceito porque a tela de Equipes ainda oferece o campo a
 * quem quiser explicar; o que deixou de existir é a obrigação. Quando não vem,
 * o servidor grava o próprio arquivamento como motivo do encerramento dos
 * vínculos, que é o que de fato aconteceu.
 */
export async function queueArchiveTeam(
  existing: TeamDto,
  motivo?: string | null,
): Promise<TeamDto> {
  const identity = await teamMutationIdentity();
  const pending = await activeTeamMutations(existing.id);
  const timestamp = new Date().toISOString();
  const clientMutationId = crypto.randomUUID();
  const archived: TeamDto = {
    ...existing,
    status: "ARQUIVADA",
    fimValidadeEm: timestamp,
    atualizadoEm: timestamp,
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    pendingMutationId: clientMutationId,
  };
  const tail = pending.at(-1) ?? null;

  await commitLocalMutation({
    ...identity,
    clientMutationId,
    obraId: existing.obraPrincipalId,
    entityType: "EQUIPE",
    entityId: existing.id,
    entityName: existing.nome,
    operation: "TRANSITION",
    transportOperation: "ARQUIVAR_EQUIPE",
    baseVersion: existing.versaoEntidade + pending.length,
    occurredAt: timestamp,
    previousSnapshot: teamTransportSnapshot(existing),
    nextSnapshot: teamTransportSnapshot(archived, {
      motivo: motivo?.trim() ? motivo.trim() : null,
      arquivadaEm: timestamp,
    }),
    principalSnapshot: { ...archived },
    expectedPrincipalSnapshot: { ...existing },
    expectedActiveMutationIds: pending.map((mutation) => mutation.clientMutationId),
    eventType: "EQUIPE_ARQUIVADA",
    colaboradorId: identity.userId,
    causationId: tail?.clientMutationId ?? null,
    dependsOnMutationIds: tail ? [tail.clientMutationId] : [],
    relatedEntities: [{
      tipo: "OBRA",
      id: existing.obraPrincipalId,
      nome: existing.obraNome,
    }],
    write: () => [{ store: "teams", value: archived, principal: true }],
  });
  return archived;
}

/**
 * Devolve a equipe ao trabalho.
 *
 * Só o status volta. Os membros e as associações a obras continuam encerrados
 * — o arquivamento desligou quem participava, e reconstituir isso sozinho
 * inventaria mão de obra que ninguém declarou. Quem desarquiva recompõe a
 * equipe adicionando as pessoas de novo.
 */
export async function queueUnarchiveTeam(
  existing: TeamDto,
): Promise<TeamDto> {
  const identity = await teamMutationIdentity();
  const pending = await activeTeamMutations(existing.id);
  const timestamp = new Date().toISOString();
  const clientMutationId = crypto.randomUUID();
  const restored: TeamDto = {
    ...existing,
    status: "ATIVA",
    fimValidadeEm: null,
    atualizadoEm: timestamp,
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    pendingMutationId: clientMutationId,
  };
  const tail = pending.at(-1) ?? null;

  await commitLocalMutation({
    ...identity,
    clientMutationId,
    obraId: existing.obraPrincipalId,
    entityType: "EQUIPE",
    entityId: existing.id,
    entityName: existing.nome,
    operation: "TRANSITION",
    transportOperation: "DESARQUIVAR_EQUIPE",
    baseVersion: existing.versaoEntidade + pending.length,
    occurredAt: timestamp,
    previousSnapshot: teamTransportSnapshot(existing),
    nextSnapshot: teamTransportSnapshot(restored, {
      desarquivadaEm: timestamp,
    }),
    principalSnapshot: { ...restored },
    expectedPrincipalSnapshot: { ...existing },
    expectedActiveMutationIds: pending.map((mutation) => mutation.clientMutationId),
    eventType: "EQUIPE_DESARQUIVADA",
    colaboradorId: identity.userId,
    causationId: tail?.clientMutationId ?? null,
    dependsOnMutationIds: tail ? [tail.clientMutationId] : [],
    relatedEntities: [{
      tipo: "OBRA",
      id: existing.obraPrincipalId,
      nome: existing.obraNome,
    }],
    write: () => [{ store: "teams", value: restored, principal: true }],
  });
  return restored;
}

export async function queueTeamLinkChange(
  existing: TeamDto,
  input: {
    action:
      | "ADICIONAR_MEMBRO"
      | "ATUALIZAR_MEMBRO"
      | "ENCERRAR_MEMBRO"
      | "ADICIONAR_OBRA"
      | "ENCERRAR_OBRA";
    vinculo: Record<string, unknown>;
    vinculoId?: string;
    projectedMembers: TeamMemberDto[];
    colaboradorId?: string | null;
  },
): Promise<TeamDto> {
  const identity = await teamMutationIdentity();
  const pending = await activeTeamMutations(existing.id);
  const timestamp = new Date().toISOString();
  const clientMutationId = crypto.randomUUID();
  const updated: TeamDto = {
    ...existing,
    membros: input.projectedMembers.map((member) => ({ ...member })),
    atualizadoEm: timestamp,
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    pendingMutationId: clientMutationId,
  };
  const tail = pending.at(-1) ?? null;
  const relatedEntities: OperationalEntityRef[] = [{
    tipo: "OBRA",
    id: existing.obraPrincipalId,
    nome: existing.obraNome,
  }];
  if (input.colaboradorId) {
    relatedEntities.push({
      tipo: "COLABORADOR",
      id: input.colaboradorId,
      nome: null,
    });
  }

  await commitLocalMutation({
    ...identity,
    clientMutationId,
    obraId: existing.obraPrincipalId,
    entityType: "EQUIPE",
    entityId: existing.id,
    entityName: existing.nome,
    operation: "UPDATE",
    transportOperation: "ALTERAR_VINCULO_EQUIPE",
    baseVersion: existing.versaoEntidade + pending.length,
    occurredAt: timestamp,
    previousSnapshot: teamTransportSnapshot(existing),
    nextSnapshot: teamTransportSnapshot(updated, {
      vinculoAcao: input.action,
      vinculoId: input.vinculoId ?? null,
      vinculo: input.vinculo,
    }),
    principalSnapshot: { ...updated },
    expectedPrincipalSnapshot: { ...existing },
    expectedActiveMutationIds: pending.map((mutation) => mutation.clientMutationId),
    eventType: "EQUIPE_VINCULO_ALTERADO",
    colaboradorId: input.colaboradorId ?? identity.userId,
    causationId: tail?.clientMutationId ?? null,
    dependsOnMutationIds: tail ? [tail.clientMutationId] : [],
    relatedEntities,
    write: () => [{ store: "teams", value: updated, principal: true }],
  });
  return updated;
}

export async function listLocalTeams(): Promise<TeamDto[]> {
  const database = await getCortexDb();
  const teams = await database.getAllFromIndex("teams", "by-updated-at");
  return teams.reverse();
}

export async function getLocalTeam(teamId: string): Promise<TeamDto | undefined> {
  return (await getCortexDb()).get("teams", teamId);
}

export async function replaceLocalTeams(teams: TeamDto[]): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction("teams", "readwrite");
  const pending = (await transaction.store.getAll()).filter(
    (team) =>
      team.syncStatus === "PENDING_SYNC" ||
      team.syncStatus === "CONFLICT" ||
      team.syncStatus === "REJECTED",
  );
  const pendingIds = new Set(pending.map((team) => team.id));
  await transaction.store.clear();
  for (const team of teams) {
    if (!pendingIds.has(team.id)) {
      await transaction.store.put(team);
    }
  }
  for (const team of pending) await transaction.store.put(team);
  await transaction.done;
}

export async function putLocalTeam(team: TeamDto): Promise<void> {
  const database = await getCortexDb();
  const existing = await database.get("teams", team.id);
  if (
    existing?.syncStatus === "PENDING_SYNC" ||
    existing?.syncStatus === "CONFLICT" ||
    existing?.syncStatus === "REJECTED"
  ) {
    return;
  }
  await database.put("teams", team);
}

export async function replaceLocalOperationalRoles(
  roles: OperationalRoleDto[],
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction("operational_roles", "readwrite");
  await transaction.store.clear();
  for (const role of roles) await transaction.store.put(role);
  await transaction.done;
}

export async function listLocalOperationalRoles(): Promise<OperationalRoleDto[]> {
  const roles = await (await getCortexDb()).getAll("operational_roles");
  return roles.sort((left, right) =>
    left.ordemExibicao - right.ordemExibicao || left.nome.localeCompare(right.nome),
  );
}

export async function replaceLocalTeamHistory(
  teamId: string,
  events: TeamHistoryEventDto[],
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction("team_history", "readwrite");
  const keys = await transaction.store.index("by-team-commit").getAllKeys(
    IDBKeyRange.bound([teamId, 0], [teamId, Number.MAX_SAFE_INTEGER]),
  );
  for (const key of keys) await transaction.store.delete(key);
  for (const event of events) await transaction.store.put(event);
  await transaction.done;
}

export async function listLocalTeamHistory(
  teamId: string,
): Promise<TeamHistoryEventDto[]> {
  return (await getCortexDb()).getAllFromIndex(
    "team_history",
    "by-team-commit",
    IDBKeyRange.bound([teamId, 0], [teamId, Number.MAX_SAFE_INTEGER]),
  );
}

export async function replaceLocalTeamWorksites(
  teamId: string,
  worksites: TeamWorksiteDto[],
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction("team_worksites", "readwrite");
  const keys = await transaction.store.index("by-team-id").getAllKeys(teamId);
  for (const key of keys) await transaction.store.delete(key);
  for (const worksite of worksites) await transaction.store.put(worksite);
  await transaction.done;
}

export async function listLocalTeamWorksites(
  teamId: string,
): Promise<TeamWorksiteDto[]> {
  return (await getCortexDb()).getAllFromIndex(
    "team_worksites",
    "by-team-id",
    teamId,
  );
}
