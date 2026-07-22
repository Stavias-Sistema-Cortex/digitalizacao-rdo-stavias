import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type { LocalRdoRecord } from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import {
  LOCAL_MUTATION_QUEUED_EVENT,
  commitLocalMutation,
  type LocalMutationCommand,
  type LocalMutationTransaction,
} from "./localMutationCoordinator";
import {
  buildCanonicalMutation,
  mutationPayloadHash,
} from "./mutationEnvelope";

const OBRA_ID = "00000000-0000-4000-8000-000000000001";
const DEVICE_ID = "00000000-0000-4000-8000-000000000002";
const RDO_ID = "00000000-0000-4000-8000-000000000003";
const OCCURRED_AT = "2026-07-21T12:00:00.000Z";

let databaseName = "";
let userId = "";

function rdo(): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-001",
    dataRdo: "2026-07-21",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: { observacoes: "Registro offline" },
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function command(record = rdo()): LocalMutationCommand<"rdos"> {
  return {
    clientMutationId: "00000000-0000-4000-8000-000000000004",
    ontologyEventId: "00000000-0000-4000-8000-000000000005",
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: record.id,
    entityName: record.numeroRdo,
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    changedFields: ["numeroRdo", "payload"],
    occurredAt: OCCURRED_AT,
    previousSnapshot: {},
    nextSnapshot: { ...record },
    eventType: "RDO_CRIADO",
    domainStores: ["rdos"],
    authorizationScope: [OBRA_ID],
    actorName: "Operador de campo",
    write: (transaction: LocalMutationTransaction<"rdos">) => {
      void transaction.objectStore("rdos").put(record).catch(() => undefined);
      return undefined;
    },
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Operador de campo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
});

describe("canonical local mutation coordinator", () => {
  it("hashes recursively sorted JSON deterministically", async () => {
    expect(
      await mutationPayloadHash({ z: [{ b: 2, a: 1 }], a: true }),
    ).toBe(
      await mutationPayloadHash({ a: true, z: [{ a: 1, b: 2 }] }),
    );
    await expect(
      mutationPayloadHash({ invalid: Number.NaN }),
    ).rejects.toThrow(/non-finite/i);
    await expect(
      mutationPayloadHash({ invalid: undefined }),
    ).rejects.toThrow(/non-JSON/i);
  });

  it("rejects invalid provenance before producing a canonical write", async () => {
    const input = command();
    await expect(
      buildCanonicalMutation({
        ...input,
        baseVersion: Number.POSITIVE_INFINITY,
      }),
    ).rejects.toThrow(/baseVersion/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        changedFields: ["numeroRdo", " "],
      }),
    ).rejects.toThrow(/changedFields\[1\]/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [crypto.randomUUID()],
      }),
    ).rejects.toThrow(/include obraId/i);
  });

  it("commits domain snapshot, exact v13 envelope and correlated event atomically", async () => {
    const database = await getCortexDb();
    let queuedEvents = 0;
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, () => {
      queuedEvents += 1;
    });

    const result = await commitLocalMutation(command());

    expect(result.mutation).toMatchObject({
      schemaVersion: 13,
      clientMutationId: "00000000-0000-4000-8000-000000000004",
      deviceId: DEVICE_ID,
      userId,
      obraId: OBRA_ID,
      entityType: "RDO",
      entityId: RDO_ID,
      operation: "CREATE",
      baseVersion: null,
      changedFields: ["numeroRdo", "payload"],
      occurredAt: OCCURRED_AT,
      payload: command().nextSnapshot,
      status: "PENDING",
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "CRIAR_RDO",
    });
    expect(result.mutation).not.toHaveProperty("contractVersion");
    expect(result.mutation.trace.payloadHash).toBe(
      await mutationPayloadHash(command().nextSnapshot),
    );
    expect(result.event).toMatchObject({
      id: "00000000-0000-4000-8000-000000000005",
      schemaVersion: 13,
      clientMutationId: result.mutation.clientMutationId,
      deviceId: DEVICE_ID,
      responsibleUserId: userId,
      obraId: OBRA_ID,
      result: "PENDING",
      previousState: {},
      newState: command().nextSnapshot,
    });
    expect(await database.get("rdos", RDO_ID)).toEqual(command().nextSnapshot);
    expect(
      await database.get("outbox_mutations", result.mutation.clientMutationId),
    ).toEqual(result.mutation);
    expect(await database.get("operational_events", result.event.id)).toEqual(
      result.event,
    );
    expect(queuedEvents).toBe(1);
  });

  it("rolls back all stores when the event add fails", async () => {
    const database = await getCortexDb();
    const input = command();
    let queuedEvents = 0;
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, () => {
      queuedEvents += 1;
    });
    await database.add("operational_events", {
      id: input.ontologyEventId,
      type: "RDO_CRIADO",
      principalEntity: { tipo: "RDO", id: "existing" },
      principalEntityKey: "RDO:existing",
      relatedEntities: [],
      obraId: OBRA_ID,
      rdoId: null,
      colaboradorId: null,
      occurredAt: OCCURRED_AT,
      syncedAt: null,
      origin: "OFFLINE",
      responsibleUserId: userId,
      responsibleUserName: "Operador de campo",
      payload: {},
      syncStatus: "PENDING_SYNC",
      schemaVersion: 1,
    });

    await expect(commitLocalMutation(input)).rejects.toThrow();

    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
    expect(
      await database.get("outbox_mutations", input.clientMutationId),
    ).toBeUndefined();
    expect(queuedEvents).toBe(0);
  });

  it("rejects async writers without committing a partial snapshot", async () => {
    const database = await getCortexDb();
    const input = command();
    const asyncWriter = (async (transaction: LocalMutationTransaction<"rdos">) => {
      await transaction.objectStore("rdos").put(rdo());
    }) as unknown as typeof input.write;

    await expect(
      commitLocalMutation({ ...input, write: asyncWriter }),
    ).rejects.toThrow(/synchronously enqueue/i);

    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
  });

  it("snapshots inputs before hashing and rejects an empty authorization scope", async () => {
    const input = command();
    const pending = commitLocalMutation(input);
    input.nextSnapshot.payload = { observacoes: "mutated after call" };
    input.changedFields[0] = "mutated";

    const result = await pending;
    expect(result.mutation.changedFields).toEqual(["numeroRdo", "payload"]);
    expect(result.mutation.payload).toMatchObject({
      payload: { observacoes: "Registro offline" },
    });

    const database = await getCortexDb();
    const unauthorized = command({ ...rdo(), id: crypto.randomUUID() });
    unauthorized.authorizationScope = [];
    await expect(commitLocalMutation(unauthorized)).rejects.toThrow(
      /authorizationScope is required/i,
    );
    expect(await database.get("rdos", unauthorized.entityId)).toBeUndefined();
  });
});
