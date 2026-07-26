import "fake-indexeddb/auto";

import { deleteDB, openDB, type IDBPDatabase } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
  type AuthProfile,
} from "../../auth/authSession";
import {
  closeCortexDb,
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../../../lib/db/cortexDb";
import { databaseNameForScope } from "../../../lib/db/localDataNamespace";
import type { MemoryPage, MemoryServerEvent } from "./memoryApi";
import { createMemoryRepository } from "./memoryRepository";
import {
  captureMemorySessionGuard,
  type MemorySessionGuard,
} from "./memorySessionGuard";
import { serverEventToSearchDocument } from "./memorySearchDocument";

const WORKSITE_ID = "00000000-0000-4000-8000-000000000002";
let userId = "";
let databaseName = "";
let currentProfile: AuthProfile;
let guard: MemorySessionGuard;

vi.stubGlobal("window", new EventTarget());

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
    actorId: userId,
    deviceId: "device-primary",
    clientMutationId: `mutation-${index}`,
    correlationId: `correlation-${index}`,
    causationId: null,
    entityVersion: index,
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
      graph: {
        checkpointCommitSequence: 147,
        targetCommitSequence: 150,
        lagEventCount: 3,
        fresh: false,
        lastSafeError: null,
      },
    },
    serverTime: "2026-07-22T10:00:00.000Z",
  };
}

function conflictMutation() {
  return {
    clientMutationId: "mutation-conflict",
    entidadeTipo: "RDO" as const,
    entidadeId: "rdo-conflict",
    operacao: "ATUALIZAR_RDO_RASCUNHO" as const,
    baseVersao: 3,
    payload: { id: "rdo-conflict", obraId: WORKSITE_ID, titulo: "Local" },
    status: "CONFLICT" as const,
    tentativas: 1,
    ultimaTentativaEm: "2026-07-22T12:00:01.000Z",
    ultimoErro: "Conflito de versão.",
    conflito: { versaoAtual: 4 },
    criadaNoClienteEm: "2026-07-22T12:00:00.000Z",
    updatedAt: "2026-07-22T12:00:01.000Z",
    transport: "SYNC_PUSH" as const,
    schemaVersion: 13 as const,
    deviceId: "device-primary",
    userId,
    obraId: WORKSITE_ID,
    entityType: "RDO" as const,
    entityId: "rdo-conflict",
    operation: "UPDATE" as const,
    baseVersion: 3,
    changedFields: ["titulo"],
    occurredAt: "2026-07-22T12:00:00.000Z",
    correlationId: "correlation-conflict",
    causationId: null,
    fieldPatch: {
      changed: { titulo: "Local" },
      baseValues: { titulo: "Base" },
    },
    relatedEntities: [],
    trace: {
      actorId: userId,
      deviceId: "device-primary",
      authorizationScope: [WORKSITE_ID],
      correlationId: "correlation-conflict",
      causationId: null,
      ontologyEventId: "event-conflict",
      payloadHash: "b".repeat(64),
    },
    nextAttemptAt: null,
    blockedReason: "REMOTE_SNAPSHOT_UNAVAILABLE",
    lastSafeCode: "VERSION_CONFLICT",
  };
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  currentProfile = {
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
  setSession(currentProfile);
  guard = captureMemorySessionGuard();
  databaseName = await databaseNameForScope(userId, `BETA:${WORKSITE_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("Memory v15 through sync lease v20 migration", () => {
  it("preserves v14 data while adding the scoped Memory and RDO context stores", async () => {
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

    expect(CORTEX_DATABASE_VERSION).toBe(20);
    expect(upgraded.version).toBe(20);
    expect(await upgraded.get("rdos", "rdo-preservado")).toMatchObject({
      numeroRdo: "17",
    });
    const untyped = upgraded as unknown as IDBPDatabase;
    expect([...untyped.objectStoreNames]).toEqual(
      expect.arrayContaining([
        "untouched_queue",
        "memory_search_documents",
        "memory_cache_metadata",
        "rdo_creation_contexts",
      ]),
    );
    expect(await untyped.get("untouched_queue", "fila-1")).toMatchObject({
      state: "pending",
    });
  });
});

describe("Memory repository", () => {
  it("aborts putPage when an identical same-user same-scope session replaces its guard", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    const write = repository.putPage(guard, page([event(1)]));

    setSession({ ...currentProfile, obraIds: [...currentProfile.obraIds] });

    await expect(write).rejects.toThrow(/autorização mudou/i);
    expect(await database.count("memory_search_documents")).toBe(0);
    expect(await database.count("memory_cache_metadata")).toBe(0);
  });

  it("aborts resetAuthorizedCache before a rotated guard can delete its scope", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    await repository.putPage(guard, page([event(1)]));
    const reset = repository.resetAuthorizedCache(guard);

    setSession({ ...currentProfile, obraIds: [...currentProfile.obraIds] });

    await expect(reset).rejects.toThrow(/autorização mudou/i);
    expect(await database.count("memory_search_documents")).toBe(1);
    expect(await database.count("memory_cache_metadata")).toBe(1);
  });

  it("aborts markComplete before a rotated guard can publish stale completeness", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    const serverPage = page([event(1)]);
    await repository.putPage(guard, serverPage);
    const completion = repository.markComplete(guard, serverPage);

    setSession({ ...currentProfile, obraIds: [...currentProfile.obraIds] });

    await expect(completion).rejects.toThrow(/autorização mudou/i);
    expect(await database.get(
      "memory_cache_metadata",
      `${userId}:scope-a`,
    )).toMatchObject({ complete: false });
  });

  it("persists graph checkpoint evidence with the authorized cache metadata", async () => {
    const repository = createMemoryRepository(await getCortexDb());
    const serverPage = page([event(150)]);

    await repository.putPage(guard, serverPage);
    await repository.markComplete(guard, serverPage);

    expect(await repository.latestMetadata(userId)).toMatchObject({
      graph: serverPage.coverage.graph,
    });
  });

  it("searches all cached history beyond the first rendered page", async () => {
    const repository = createMemoryRepository(await getCortexDb());
    const events = Array.from({ length: 150 }, (_, index) =>
      event(index + 1, index === 136 ? "Compactação profunda" : undefined),
    );
    await repository.putPage(guard, page(events));
    await repository.markComplete(guard, page(events));

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: { q: "compactacao" },
      limit: 20,
    });

    expect(result.items.map((item) => item.eventId)).toContain("event-137");
  });

  it("applies authorized actor and device filters to cached structural metadata", async () => {
    const repository = createMemoryRepository(await getCortexDb());
    await repository.putPage(guard, page([
      event(1),
      {
        ...event(2),
        actorId: "00000000-0000-4000-8000-000000000099",
        deviceId: "device-other",
      },
    ]));

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: {
        actorId: userId,
        deviceId: "device-primary",
      },
      limit: 20,
    });

    expect(result.items.map((item) => item.eventId)).toEqual(["event-001"]);
  });

  it("joins terminal local evidence to its canonical outbox review without raw state", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    await database.put("outbox_mutations", conflictMutation());
    await database.put("operational_events", {
      id: "event-conflict",
      type: "RDO_EDITADO",
      principalEntity: {
        tipo: "RDO",
        id: "rdo-conflict",
        nome: "RDO em conflito",
      },
      principalEntityKey: "RDO:rdo-conflict",
      relatedEntities: [],
      obraId: WORKSITE_ID,
      rdoId: "rdo-conflict",
      colaboradorId: null,
      occurredAt: "2026-07-22T12:00:00.000Z",
      syncedAt: null,
      origin: "OFFLINE",
      responsibleUserId: userId,
      responsibleUserName: "Encarregado",
      payload: { segredo: "não exportar" },
      syncStatus: "SYNC_FAILED",
      schemaVersion: 13,
      clientMutationId: "mutation-conflict",
      deviceId: "device-primary",
      correlationId: "correlation-conflict",
      causationId: null,
      previousState: {
        id: "rdo-conflict",
        obraId: WORKSITE_ID,
        titulo: "Base privada",
      },
      newState: {
        id: "rdo-conflict",
        obraId: WORKSITE_ID,
        titulo: "Local privado",
      },
      result: "CONFLICT",
      errorCategory: "VERSION_CONFLICT",
      entityVersion: 3,
    });

    const result = await repository.search({
      userId,
      scopeHash: "scope-a",
      filters: { actorId: userId, deviceId: "device-primary" },
      allowedWorksiteIds: [WORKSITE_ID],
      limit: 20,
    });

    expect(result.items[0].review).toMatchObject({
      remoteVersion: 4,
      canReconcile: false,
      unavailableReason: "REMOTE_SNAPSHOT_UNAVAILABLE",
    });
    expect(JSON.stringify(result.items[0])).not.toContain("privad");
    expect(JSON.stringify(result.items[0])).not.toContain("não exportar");
  });

  it("isolates cached documents by both user and authorization scope", async () => {
    const repository = createMemoryRepository(await getCortexDb());
    const database = await getCortexDb();
    await repository.putPage(guard, page([event(1, "Escopo correto")], "scope-a"));
    await repository.putPage(guard, page([event(2, "Escopo anterior")], "scope-b"));
    await database.put(
      "memory_search_documents",
      serverEventToSearchDocument(
        "00000000-0000-4000-8000-000000000099",
        "scope-a",
        event(3, "Outro usuário"),
      ),
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
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    const otherUser = "00000000-0000-4000-8000-000000000099";
    await repository.putPage(guard, page([event(1)], "scope-a"));
    await repository.putPage(guard, page([event(2)], "scope-b"));
    await database.put(
      "memory_search_documents",
      serverEventToSearchDocument(otherUser, "scope-a", event(3)),
    );

    await repository.resetAuthorizedCache(guard);

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
    await repository.putPage(guard, stalePage);
    const serverNowAuthorizesOne = {
      ...stalePage,
      coverage: { ...stalePage.coverage, authorizedEventCount: 1 },
    };

    const result = await repository.markComplete(guard, serverNowAuthorizesOne);

    expect(result).toMatchObject({ cachedEventCount: 2, complete: false });
  });

  it("merges authorized local evidence and deduplicates a confirmed event", async () => {
    const database = await getCortexDb();
    const repository = createMemoryRepository(database);
    await repository.putPage(guard, page([event(5, "Confirmado")], "scope-a"));
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
    await repository.putPage(guard, canonical);
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
