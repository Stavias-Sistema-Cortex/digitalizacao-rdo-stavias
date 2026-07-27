import "fake-indexeddb/auto";

import { deleteDB, openDB, type IDBPDatabase } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  closeCortexDb,
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "./cortexDb";
import { databaseNameForScope } from "./localDataNamespace";
import { clearSession, setSession } from "../../features/auth/authSession";

const WORKSITE_ID = "00000000-0000-4000-8000-000000000001";

let databaseName = "";

beforeEach(async () => {
  const ownerId = crypto.randomUUID();
  setSession({
    colaboradorId: ownerId,
    nome: "Operador de campo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(
    ownerId,
    `BETA:${WORKSITE_ID}`,
  );
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) {
    await deleteDB(databaseName);
  }
  clearSession();
});

describe("IndexedDB assistant cleanup", () => {
  it("creates a fresh v20 database with confirmed revenue caches and without the legacy assistant store", async () => {
    const fresh = await getCortexDb();

    expect(fresh.version).toBe(20);
    expect(fresh.objectStoreNames.contains("finance_revenue_trace_cache")).toBe(true);
    expect(fresh.objectStoreNames.contains("finance_pdor_revenue_cache")).toBe(true);
    expect(fresh.objectStoreNames.contains("stavia_snapshots")).toBe(false);
  });

  it("removes only the legacy assistant store and preserves stores, data and indexes during the v12 upgrade", async () => {
    const legacy = await openDB(databaseName, 12, {
      upgrade(database) {
        const rdoStore = database.createObjectStore("rdos", { keyPath: "id" });
        rdoStore.createIndex("by-obra-id", "obraId");
        const obraStore = database.createObjectStore("obras", { keyPath: "id" });
        obraStore.createIndex("by-status", "status");
        const customStore = database.createObjectStore("future_domain_cache", {
          keyPath: "id",
        });
        customStore.createIndex("by-scope", "scope");
        database.createObjectStore("stavia_snapshots", {
          keyPath: "key",
        });
      },
    });
    await legacy.put("rdos", {
      id: "rdo-preservado",
      obraId: WORKSITE_ID,
      statusRdo: "RASCUNHO",
    });
    await legacy.put("obras", {
      id: WORKSITE_ID,
      status: "ATIVA",
      nome: "Obra preservada",
    });
    await legacy.put("future_domain_cache", {
      id: "cache-preservado",
      scope: "operacional",
      value: 42,
    });
    await legacy.put("stavia_snapshots", {
      key: "default",
      snapshot: { privateAssistantData: true },
    });
    legacy.close();

    const upgraded = await getCortexDb();

    expect(CORTEX_DATABASE_VERSION).toBe(20);
    expect(
      upgraded.objectStoreNames.contains("finance_revenue_trace_cache"),
    ).toBe(true);
    expect(
      upgraded.objectStoreNames.contains("finance_pdor_revenue_cache"),
    ).toBe(true);
    expect(upgraded.objectStoreNames.contains("stavia_snapshots")).toBe(false);
    expect(await upgraded.get("rdos", "rdo-preservado")).toMatchObject({
      id: "rdo-preservado",
      statusRdo: "RASCUNHO",
    });
    const untypedDatabase = upgraded as unknown as IDBPDatabase;
    expect([...untypedDatabase.objectStoreNames]).toEqual(
      expect.arrayContaining(["rdos", "obras", "future_domain_cache"]),
    );

    const verification = untypedDatabase.transaction(
      ["rdos", "obras", "future_domain_cache"],
      "readonly",
    );
    expect(
      verification.objectStore("rdos").indexNames.contains("by-obra-id"),
    ).toBe(true);
    expect(
      verification.objectStore("obras").indexNames.contains("by-status"),
    ).toBe(true);
    expect(
      verification.objectStore("future_domain_cache").indexNames.contains("by-scope"),
    ).toBe(true);
    expect(await verification.objectStore("obras").get(WORKSITE_ID)).toMatchObject({
      id: WORKSITE_ID,
      nome: "Obra preservada",
    });
    expect(
      await verification.objectStore("future_domain_cache").get("cache-preservado"),
    ).toMatchObject({ id: "cache-preservado", value: 42 });
    await verification.done;
  });
});
