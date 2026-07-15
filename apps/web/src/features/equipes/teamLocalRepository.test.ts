import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  closeCortexDb,
  CORTEX_DATABASE_NAME,
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../../lib/db/cortexDb";
import type { TeamDto } from "./teamApi";
import {
  getLocalTeam,
  listLocalTeamHistory,
  replaceLocalTeamHistory,
  replaceLocalTeams,
} from "./teamLocalRepository";

async function resetDatabase(): Promise<void> {
  await closeCortexDb();
  await deleteDB(CORTEX_DATABASE_NAME);
}

beforeEach(resetDatabase);
afterEach(resetDatabase);

function team(id: string): TeamDto {
  return {
    id,
    obraPrincipalId: "obra-1",
    obraNome: "Contorno Norte",
    nome: "Drenagem",
    descricao: null,
    status: "ATIVA",
    inicioValidadeEm: "2026-07-15T00:00:00",
    fimValidadeEm: null,
    versaoEntidade: 1,
    criadoEm: "2026-07-15T00:00:00",
    atualizadoEm: "2026-07-15T00:00:00",
    membros: [],
  };
}

describe("cache local de Equipes", () => {
  it("migra stores da projeção operacional sem remover stores anteriores", async () => {
    const database = await getCortexDb();
    expect(database.version).toBe(CORTEX_DATABASE_VERSION);
    expect([...database.objectStoreNames]).toEqual(expect.arrayContaining([
      "teams",
      "operational_roles",
      "team_history",
      "team_worksites",
      "messages",
      "rdos",
    ]));
  });

  it("substitui o escopo completo para revogar equipes que saíram da API", async () => {
    await replaceLocalTeams([team("team-1"), team("team-2")]);
    await replaceLocalTeams([team("team-2")]);

    expect(await getLocalTeam("team-1")).toBeUndefined();
    expect(await getLocalTeam("team-2")).toBeDefined();
  });

  it("mantém histórico estruturado em ordem de commit", async () => {
    await replaceLocalTeamHistory("team-1", [
      {
        commitSeq: 8,
        eventId: "event-8",
        entityType: "EQUIPE",
        entityId: "team-1",
        eventType: "EQUIPE_ATUALIZADA",
        source: "ONLINE",
        worksiteId: "obra-1",
        collaboratorId: "user-1",
        occurredAt: "2026-07-15T12:00:00",
        recordedAt: "2026-07-15T12:00:00",
        entityVersion: 2,
        payload: { changedFields: ["nome"] },
      },
    ]);

    expect(await listLocalTeamHistory("team-1")).toMatchObject([
      { eventId: "event-8", commitSeq: 8, payload: { changedFields: ["nome"] } },
    ]);
  });
});
