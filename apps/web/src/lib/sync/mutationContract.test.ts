import "fake-indexeddb/auto";

import { deleteDB, openDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import {
  closeCortexDb,
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";

const SCOPE_MATERIAL =
  "BETA:00000000-0000-4000-8000-000000000001";

describe("canonical mutation IndexedDB contract", () => {
  let databaseName = "";

  beforeEach(async () => {
    const ownerId = crypto.randomUUID();
    setSession({
      colaboradorId: ownerId,
      nome: "Operador de campo",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: ["00000000-0000-4000-8000-000000000001"],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    databaseName = await databaseNameForScope(ownerId, SCOPE_MATERIAL);

    const legacyDatabase = await openDB(databaseName, 12, {
      upgrade(database) {
        const outbox = database.createObjectStore("outbox_mutations", {
          keyPath: "clientMutationId",
        });
        outbox.createIndex("by-status", "status");
        outbox.createIndex("by-created-at", "criadaNoClienteEm");
        outbox.createIndex("by-entity-id", "entidadeId");

        const events = database.createObjectStore("operational_events", {
          keyPath: "id",
        });
        events.createIndex("by-principal-entity", "principalEntityKey");
        events.createIndex("by-rdo-id", "rdoId");
        events.createIndex("by-obra-id", "obraId");
        events.createIndex("by-colaborador-id", "colaboradorId");
        events.createIndex("by-type", "type");
        events.createIndex("by-sync-status", "syncStatus");
        events.createIndex("by-occurred-at", "occurredAt");
      },
    });
    await legacyDatabase.put("outbox_mutations", {
      clientMutationId: "queued-before-v13",
      entidadeId: "rdo-1",
      status: "PENDING",
      criadaNoClienteEm: "2026-07-17T00:00:00.000Z",
      payload: { preserved: true },
    });
    legacyDatabase.close();
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) {
      await deleteDB(databaseName);
    }
    clearSession();
  });

  it("opens schema v13 with trace indexes and preserves queued data", async () => {
    const db = await getCortexDb();
    expect(CORTEX_DATABASE_VERSION).toBe(13);
    expect(db.version).toBe(13);
    expect([...db.transaction("outbox_mutations").store.indexNames]).toContain(
      "by-next-attempt-at",
    );
    expect([...db.transaction("operational_events").store.indexNames]).toEqual(
      expect.arrayContaining(["by-client-mutation-id", "by-result"]),
    );
    expect([...db.objectStoreNames]).toContain("obra_geometries");
    const geometryStore = db.transaction("obra_geometries").store;
    expect(geometryStore.keyPath).toBe("obraId");
    expect([...geometryStore.indexNames]).toEqual(
      expect.arrayContaining(["by-updated-at", "by-sync-status"]),
    );
    expect(
      await db.get("outbox_mutations", "queued-before-v13"),
    ).toMatchObject({ payload: { preserved: true } });
  });
});
