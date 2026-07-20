import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  OperationalRoleDto,
  TeamDto,
  TeamHistoryEventDto,
  TeamWorksiteDto,
} from "./teamApi";

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
  await transaction.store.clear();
  for (const team of teams) await transaction.store.put(team);
  await transaction.done;
}

export async function putLocalTeam(team: TeamDto): Promise<void> {
  await (await getCortexDb()).put("teams", team);
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
