import "fake-indexeddb/auto";

import { deleteDB, openDB, type IDBPDatabase } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../../auth/authSession";
import {
  closeCortexDb,
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../../../lib/db/cortexDb";
import { databaseNameForScope } from "../../../lib/db/localDataNamespace";
import type { MemoryPage, MemoryServerEvent } from "./memoryApi";
import { createMemoryRepository } from "./memoryRepository";

const WORKSITE_ID = "00000000-0000-4000-8000-000000000002";
let userId = "";
let databaseName = "";

function event(index: number, text = `Evento ${index}`): MemoryServerEvent {
  return {
    eventId: `event-${String(index).padStart(3, "0")}`,
    commitSequence: index,
    eventType: "RDO_EDITADO",
    source: "SYNC_PUSH",
    principalEntityType: "RDO",
    principalEntityId: `rdo-${index}`,
    principalName: text,
    worksiteId: WORKSITE_ID,
    worksiteName: "Obra BR-262",
    rdoId: `rdo-${index}`,
    rdoNumber: String(index),
    serviceName: null,
    occurredAt: new Date(Date.UTC(2026, 6, 1, 0, index)).toISOString(),
    synchronizedAt: new Date(Date.UTC(2026, 6, 1, 0, index, 1)).toISOString(),
    origin: "SYNC",
    syncStatus: "SYNCED",
    schemaVersion: 13,
    result: "SYNCED",
    errorCategory: null,
    relevance: 0,
  };
}

function page(items: MemoryServerEvent[], scopeHash = "scope-a"): MemoryPage {
  return {
    items,
    nextCursor: null,
    hasMore: false,
    highWaterMark: 150,
    scopeHash,
    coverage: {
      mode: "FULL_HISTORY",
      complete: true,
      authorizedEventCount: 150,
      oldestCommitSequence: 1,
      newestCommitSequence: 150,
    },
    serverTime: "2026-07-22T10:00:00.000Z",
  };
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${WORKSITE_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("Memory v15 migration", () => {
  it("preserves v14 data and adds only the scoped Memory stores", async () => {
    const legacy = await openDB(databaseName, 14, {
      upgrade(database) {
        database.createObjectStore("rdos", { keyPath: "id" });
        database.createObjectStore("untouched_queue", { keyPath: "id" });
      },
    });
    await legacy.put("rdos", { id: "rdo-preservado", numeroRdo: "17" });
    await legacy.put("untouched_queue", { id: "fila-1", state: "pending" });
    legacy.close();

    const upgraded = await getCortexDb();

    expect(CORTEX_DATABASE_VERSION).toBe(15);
    expect(upgraded.version).toBe(15);
    expect(await upgraded.get("rdos", "rdo-preservado")).toMatchObject({
      numeroRdo: "17",
    });
    const untyped = upgraded as unknown as IDBPDatabase;
    expect([...untyped.objectStoreNames]).toEqual(
      expect.arrayContaining([
        "untouched_queue",
        "memory_search_documents",
        "memory_cache_metadata",
      ]),
    );
    expect(await untyped.get("untouched_queue", "fila-1")).toMatchObject({
      state: "pending",
    });
  });
});

describe("Memory repository", () => {
  it("searches all cached history beyond the first rendered page", async () => {
    const repository = createMemoryRepository(await getCortexDb());
    const events = Array.from({ length: 150 }, (_, index) =>
      event(index + 1, index === 136 ? "Compactação profunda" : undefined),
    );
    await repository.putPage(userId, page(events));
    await repository.markComplete(userId, page(events));

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: { q: "compactacao" },
      limit: 20,
    });

    expect(result.items.map((item) => item.eventId)).toContain("event-137");
  });

  it("isolates cached documents by both user and authorization scope", async () => {
    const repository = createMemoryRepository(await getCortexDb());
    await repository.putPage(userId, page([event(1, "Escopo correto")], "scope-a"));
    await repository.putPage(userId, page([event(2, "Escopo anterior")], "scope-b"));
    await repository.putPage(
      "00000000-0000-4000-8000-000000000099",
      page([event(3, "Outro usuário")], "scope-a"),
    );

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: {},
      limit: 20,
    });

    expect(result.items.map((item) => item.eventId)).toEqual(["event-001"]);
  });

  it("purges every stale scope for the current user without touching another user", async () => {
    const repository = createMemoryRepository(await getCortexDb());
    const otherUser = "00000000-0000-4000-8000-000000000099";
    await repository.putPage(userId, page([event(1)], "scope-a"));
    await repository.putPage(userId, page([event(2)], "scope-b"));
    await repository.putPage(otherUser, page([event(3)], "scope-a"));

    await repository.resetAuthorizedCache(userId);

    expect((await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: {},
      limit: 20,
    })).items).toEqual([]);
    expect((await repository.search({
      userId: otherUser,
      scopeHash: "scope-a",
      filters: {},
      limit: 20,
    })).items.map((item) => item.eventId)).toEqual(["event-003"]);
  });

  it("never marks a cache with stale extra rows as complete", async () => {
    const repository = createMemoryRepository(await getCortexDb());
    const stalePage = page([event(1), event(2)], "scope-a");
    await repository.putPage(userId, stalePage);
    const serverNowAuthorizesOne = {
      ...stalePage,
      coverage: { ...stalePage.coverage, authorizedEventCount: 1 },
    };

    const result = await repository.markComplete(userId, serverNowAuthorizesOne);

    expect(result).toMatchObject({ cachedEventCount: 2, complete: false });
  });

  it("merges authorized local evidence and deduplicates a confirmed event", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    await repository.putPage(userId, page([event(5, "Confirmado")], "scope-a"));
    await database.put("operational_events", {
      id: "event-005",
      type: "RDO_EDITADO",
      principalEntity: { tipo: "RDO", id: "rdo-5", nome: "Local duplicado" },
      principalEntityKey: "RDO:rdo-5",
      relatedEntities: [],
      obraId: WORKSITE_ID,
      rdoId: "rdo-5",
      colaboradorId: null,
      occurredAt: "2026-07-22T11:00:00.000Z",
      syncedAt: null,
      origin: "OFFLINE",
      responsibleUserId: userId,
      responsibleUserName: "Encarregado",
      payload: {},
      syncStatus: "PENDING_SYNC",
      schemaVersion: 13,
      result: "PENDING",
    });
    await database.put("operational_events", {
      id: "local-only",
      type: "RDO_CRIADO",
      principalEntity: { tipo: "RDO", id: "rdo-local", nome: "RDO local" },
      principalEntityKey: "RDO:rdo-local",
      relatedEntities: [],
      obraId: WORKSITE_ID,
      rdoId: "rdo-local",
      colaboradorId: null,
      occurredAt: "2026-07-22T12:00:00.000Z",
      syncedAt: null,
      origin: "OFFLINE",
      responsibleUserId: userId,
      responsibleUserName: "Encarregado",
      payload: {},
      syncStatus: "PENDING_SYNC",
      schemaVersion: 13,
      result: "PENDING",
    });

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: {},
      allowedWorksiteIds: [WORKSITE_ID],
      limit: 20,
    });

    expect(result.items.map((item) => item.eventId)).toEqual([
      "local-only",
      "event-005",
    ]);
    expect(result.items[0].syncStatus).toBe("LOCAL_PENDING");
    expect(result.items[1].sourceKind).toBe("SERVER");
  });

  it("keeps user-owned null-worksite evidence in Beta scope and rejects another actor", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    for (const [id, owner] of [
      ["owned-global", userId],
      ["foreign-global", "00000000-0000-4000-8000-000000000099"],
    ] as const) {
      await database.put("operational_events", {
        id,
        type: "TAREFA_CRIADA",
        principalEntity: { tipo: "TAREFA", id, nome: id },
        principalEntityKey: `TAREFA:${id}`,
        relatedEntities: [],
        obraId: null,
        rdoId: null,
        colaboradorId: null,
        occurredAt: "2026-07-22T12:00:00.000Z",
        syncedAt: null,
        origin: "OFFLINE",
        responsibleUserId: owner,
        responsibleUserName: null,
        payload: {},
        syncStatus: "PENDING_SYNC",
        schemaVersion: 13,
        result: "PENDING",
      });
    }

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: {},
      allowedWorksiteIds: [WORKSITE_ID],
      limit: 20,
    });

    expect(result.items.map((item) => item.eventId)).toEqual(["owned-global"]);
  });

  it("keeps a locally confirmed event visible until the server cache contains it", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    await database.put("operational_events", {
      id: "confirmed-locally",
      type: "RDO_SINCRONIZADO",
      principalEntity: { tipo: "RDO", id: "rdo-9", nome: "RDO 9" },
      principalEntityKey: "RDO:rdo-9",
      relatedEntities: [],
      obraId: WORKSITE_ID,
      rdoId: "rdo-9",
      colaboradorId: null,
      occurredAt: "2026-07-22T12:00:00.000Z",
      syncedAt: "2026-07-22T12:00:01.000Z",
      origin: "SYNC",
      responsibleUserId: userId,
      responsibleUserName: null,
      payload: {},
      syncStatus: "SYNCED",
      schemaVersion: 13,
      result: "SYNCED",
      serverCommitSequence: 99,
    });

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: {},
      allowedWorksiteIds: [WORKSITE_ID],
      limit: 20,
    });

    expect(result.items).toEqual([
      expect.objectContaining({
        eventId: "confirmed-locally",
        commitSequence: 99,
        syncStatus: "UPDATED",
        sourceKind: "LOCAL",
      }),
    ]);
    expect(result.localStatuses).toEqual([]);
  });

  it("deduplicates different local/server event IDs by canonical commit", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    const canonical = page([
      event(99, "Servidor canônico"),
    ], "scope-a");
    await repository.putPage(userId, canonical);
    await database.put("operational_events", {
      id: "ontology-event-A",
      type: "RDO_SINCRONIZADO",
      principalEntity: { tipo: "RDO", id: "rdo-99", nome: "Ponte local" },
      principalEntityKey: "RDO:rdo-99",
      relatedEntities: [],
      obraId: WORKSITE_ID,
      rdoId: "rdo-99",
      colaboradorId: null,
      occurredAt: "2026-07-22T12:00:00.000Z",
      syncedAt: "2026-07-22T12:00:01.000Z",
      origin: "SYNC",
      responsibleUserId: userId,
      responsibleUserName: null,
      payload: {},
      syncStatus: "SYNCED",
      schemaVersion: 13,
      result: "SYNCED",
      serverCommitSequence: 99,
    });

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: {},
      allowedWorksiteIds: [WORKSITE_ID],
      limit: 20,
    });

    expect(result.items).toEqual([
      expect.objectContaining({
        eventId: "event-099",
        commitSequence: 99,
        sourceKind: "SERVER",
      }),
    ]);
  });
});
