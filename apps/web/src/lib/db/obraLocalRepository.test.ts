import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import type { ObraLocalRecord } from "./db.types";
import { databaseNameForScope } from "./localDataNamespace";
import { listObrasLocais } from "./obraLocalRepository";

const USER_ID = "00000000-0000-4000-8000-000000000001";
let databaseName = "";

function obra(
  id: string,
  arquivadoEm: string | null,
): ObraLocalRecord {
  return {
    id,
    codigoContrato: id,
    nome: id === "ativa" ? "Obra ativa" : "Obra arquivada",
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    status: "ATIVA",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    arquivadoEm,
    syncStatus: "SYNCED",
    updatedAt: "2026-07-28T12:00:00.000Z",
  };
}

beforeEach(async () => {
  setSession({
    colaboradorId: USER_ID,
    nome: "Administradora",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(USER_ID, "ALFA:GLOBAL");
  const database = await getCortexDb();
  await database.put("obras", obra("ativa", null));
  await database.put(
    "obras",
    obra("arquivada", "2026-07-28T13:00:00.000Z"),
  );
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("operational worksite cache boundary", () => {
  it("excludes archived worksites by default and requires an explicit Trash opt-in", async () => {
    await expect(listObrasLocais()).resolves.toEqual([
      expect.objectContaining({ id: "ativa" }),
    ]);
    await expect(
      listObrasLocais({ includeArchived: true }),
    ).resolves.toEqual(expect.arrayContaining([
      expect.objectContaining({ id: "ativa" }),
      expect.objectContaining({ id: "arquivada" }),
    ]));
  });
});
