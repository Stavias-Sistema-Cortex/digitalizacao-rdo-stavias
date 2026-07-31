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
import { buildCanonicalMutation } from "./mutationEnvelope";

const OBRA_ID = "00000000-0000-4000-8000-000000000001";
let databaseName = "";

beforeEach(async () => {
  const userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Operador de campo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);

  const legacy = await openDB(databaseName, 20, {
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

      const obras = database.createObjectStore("obras", {
        keyPath: "id",
      });
      obras.createIndex("by-updated-at", "updatedAt");
      obras.createIndex("by-status", "status");
    },
  });
  await legacy.put("outbox_mutations", {
    clientMutationId: "queued-before-v14",
    entidadeTipo: "RDO",
    entidadeId: "rdo-existing",
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: { preserved: true },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-07-21T12:00:00.000Z",
    updatedAt: "2026-07-21T12:00:00.000Z",
  });
  await legacy.put("obras", {
    id: OBRA_ID,
    codigoContrato: "CT-LEGADO",
    nome: "Obra preservada",
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    status: "ATIVA",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    updatedAt: "2026-07-21T12:00:00.000Z",
  });
  legacy.close();
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("canonical mutation IndexedDB contract", () => {
  it("upgrades v20 to v21 without deleting obra cache or queued data", async () => {
    const database = await getCortexDb();

    expect(CORTEX_DATABASE_VERSION).toBe(22);
    expect(database.version).toBe(22);
    expect(
      [...database.transaction("outbox_mutations").store.indexNames],
    ).toContain("by-next-attempt-at");
    expect(
      [...database.transaction("operational_events").store.indexNames],
    ).toEqual(
      expect.arrayContaining(["by-client-mutation-id", "by-result"]),
    );
    expect(
      await database.get("outbox_mutations", "queued-before-v14"),
    ).toMatchObject({ payload: { preserved: true }, status: "PENDING" });
    expect(await database.get("obras", OBRA_ID)).toMatchObject({
      codigoContrato: "CT-LEGADO",
      versaoEntidade: null,
      arquivadoEm: null,
      syncStatus: "SYNCED",
      ultimoErro: null,
    });
    expect(database.objectStoreNames.contains("stavia_snapshots")).toBe(false);
    expect(database.objectStoreNames.contains("obra_geometries")).toBe(false);
  });

  it.each([
    ["ATUALIZAR_OBRA", "UPDATE"],
    ["DESATIVAR_OBRA", "TRANSITION"],
    ["ARQUIVAR_OBRA", "DELETE"],
    ["RESTAURAR_OBRA", "TRANSITION"],
  ] as const)("registra %s como operação canônica %s", async (
    transportOperation,
    operation,
  ) => {
    const mutation = await buildCanonicalMutation({
      clientMutationId: crypto.randomUUID(),
      ontologyEventId: crypto.randomUUID(),
      deviceId: crypto.randomUUID(),
      userId: crypto.randomUUID(),
      obraId: OBRA_ID,
      entityType: "OBRA",
      entityId: OBRA_ID,
      operation,
      transportOperation,
      baseVersion: 3,
      occurredAt: "2026-07-21T12:00:00.000Z",
      previousSnapshot: { id: OBRA_ID, obraId: OBRA_ID, status: "ATIVA" },
      nextSnapshot: { id: OBRA_ID, obraId: OBRA_ID, status: "INATIVA" },
      authorizationScope: ["ALFA:GLOBAL"],
    });

    expect(mutation.mutation).toMatchObject({
      entidadeTipo: "OBRA",
      operacao: transportOperation,
      operation,
      baseVersion: 3,
    });
  });
});
