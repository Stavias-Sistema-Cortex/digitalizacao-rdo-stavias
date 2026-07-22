import "fake-indexeddb/auto";

import { deleteDB, openDB } from "idb";
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
  it("removes only the legacy assistant store during the v12 upgrade", async () => {
    const legacy = await openDB(databaseName, 12, {
      upgrade(database) {
        database.createObjectStore("rdos", { keyPath: "id" });
        database.createObjectStore("stavia_snapshots", {
          keyPath: "key",
        });
      },
    });
    await legacy.put("rdos", {
      id: "rdo-preservado",
      statusRdo: "RASCUNHO",
    });
    await legacy.put("stavia_snapshots", {
      key: "default",
      snapshot: { privateAssistantData: true },
    });
    legacy.close();

    const upgraded = await getCortexDb();

    expect(CORTEX_DATABASE_VERSION).toBe(13);
    expect(upgraded.objectStoreNames.contains("stavia_snapshots")).toBe(false);
    expect(await upgraded.get("rdos", "rdo-preservado")).toMatchObject({
      id: "rdo-preservado",
      statusRdo: "RASCUNHO",
    });
  });
});
