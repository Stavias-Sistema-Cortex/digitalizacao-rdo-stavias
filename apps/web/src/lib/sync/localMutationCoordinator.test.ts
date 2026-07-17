import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import {
  closeCortexDb,
  getCortexDb,
} from "../db/cortexDb";
import type { LocalRdoRecord } from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import {
  LOCAL_MUTATION_QUEUED_EVENT,
  commitLocalMutation,
  type LocalMutationTransaction,
} from "./localMutationCoordinator";
import {
  buildMutationEnvelope,
  mutationPayloadHash,
} from "./mutationEnvelope";

const OBRA_ID = "00000000-0000-4000-8000-000000000001";
const CREATED_AT = "2026-07-17T12:00:00.000Z";

let databaseName = "";
let ownerId = "";

function fixtureActor() {
  return {
    actorId: ownerId,
    actorName: "Operador de campo",
    deviceId: "00000000-0000-4000-8000-000000000002",
    authorizationScope: [OBRA_ID],
  };
}

function localRdo(): LocalRdoRecord {
  return {
    id: "00000000-0000-4000-8000-000000000003",
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-001",
    dataRdo: "2026-07-17",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: { observacoes: "Registro offline" },
    createdAt: CREATED_AT,
    updatedAt: CREATED_AT,
  };
}

function coordinatorInput(rdo = localRdo()) {
  return {
    stores: ["rdos"] as const,
    entity: {
      type: "RDO" as const,
      id: rdo.id,
      obraId: rdo.obraId,
      rdoId: rdo.id,
    },
    operation: "CRIAR_RDO" as const,
    eventType: "RDO_CRIADO" as const,
    baseVersion: null,
    previousState: {},
    newState: { ...rdo },
    actor: fixtureActor(),
    createdAt: CREATED_AT,
    write: async (tx: LocalMutationTransaction<"rdos">) =>
      tx.objectStore("rdos").put(rdo),
  };
}

beforeEach(async () => {
  vi.stubGlobal("BroadcastChannel", undefined);
  vi.stubGlobal("window", new EventTarget());
  ownerId = crypto.randomUUID();
  setSession({
    colaboradorId: ownerId,
    nome: "Operador de campo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(ownerId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) {
    await deleteDB(databaseName);
  }
  clearSession();
  vi.unstubAllGlobals();
});

describe("canonical mutation hashing", () => {
  it("hashes recursively sorted canonical JSON deterministically", async () => {
    const left = {
      z: [{ b: 2, a: 1 }],
      a: { second: true, first: null },
    };
    const right = {
      a: { first: null, second: true },
      z: [{ a: 1, b: 2 }],
    };

    expect(await mutationPayloadHash(left)).toBe(
      await mutationPayloadHash(right),
    );
    expect(await mutationPayloadHash({ b: 2, a: 1 })).toBe(
      "43258cff783fe7036d8a43033f830adfc60ec037382473548ac742b888292777",
    );
  });

  it.each([
    ["undefined", { invalid: undefined }],
    ["non-finite number", { invalid: Number.NaN }],
    ["function", { invalid: () => undefined }],
  ])("rejects %s values", async (_label, invalid) => {
    await expect(mutationPayloadHash(invalid)).rejects.toThrow();
  });

  it("preserves an inherited correlation and causation chain", async () => {
    const previousState = { statusRdo: "RASCUNHO", unchanged: true };
    const newState = { statusRdo: "ENVIADO", unchanged: true };
    const mutation = await buildMutationEnvelope({
      entity: { type: "RDO", id: localRdo().id },
      operation: "ENVIAR_RDO",
      baseVersion: 4,
      previousState,
      newState,
      actor: fixtureActor(),
      correlationId: "correlation-root",
      causationId: "mutation-parent",
      createdAt: CREATED_AT,
    });

    expect(mutation).toMatchObject({
      contractVersion: 13,
      correlationId: "correlation-root",
      fieldPatch: {
        changed: { statusRdo: "ENVIADO" },
        baseValues: { statusRdo: "RASCUNHO" },
      },
      trace: {
        actorId: ownerId,
        deviceId: fixtureActor().deviceId,
        authorizationScope: [OBRA_ID],
        correlationId: "correlation-root",
        causationId: "mutation-parent",
      },
    });
    expect(mutation.trace.payloadHash).toBe(
      await mutationPayloadHash(newState),
    );
  });
});

describe("local mutation coordinator", () => {
  it("commits record, outbox and event atomically", async () => {
    const db = await getCortexDb();
    let queuedEvents = 0;
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, () => {
      queuedEvents += 1;
    });
    const input = coordinatorInput();

    const result = await commitLocalMutation(input);

    expect(await db.get("rdos", input.entity.id)).toEqual(input.newState);
    expect(
      await db.get("outbox_mutations", result.mutation.clientMutationId),
    ).toEqual(result.mutation);
    expect(await db.get("operational_events", result.event.id)).toEqual(
      result.event,
    );
    expect(result.mutation).toMatchObject({
      contractVersion: 13,
      status: "PENDING",
      correlationId: result.mutation.clientMutationId,
      trace: {
        actorId: ownerId,
        deviceId: fixtureActor().deviceId,
        authorizationScope: [OBRA_ID],
        correlationId: result.mutation.clientMutationId,
        causationId: null,
        ontologyEventId: result.event.id,
      },
    });
    expect(result.event).toMatchObject({
      contractVersion: 13,
      id: result.mutation.trace.ontologyEventId,
      clientMutationId: result.mutation.clientMutationId,
      deviceId: result.mutation.trace.deviceId,
      correlationId: result.mutation.correlationId,
      causationId: null,
      previousState: {},
      newState: input.newState,
      result: "PENDING",
      errorCategory: null,
      entityVersion: null,
      schemaVersion: 1,
    });
    expect(queuedEvents).toBe(1);
  });

  it("rolls back all three records when the domain write throws", async () => {
    const db = await getCortexDb();
    let queuedEvents = 0;
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, () => {
      queuedEvents += 1;
    });
    const rdo = localRdo();
    const input = coordinatorInput(rdo);

    await expect(
      commitLocalMutation({
        ...input,
        write: async (tx) => {
          await tx.objectStore("rdos").put(rdo);
          throw new Error("boom");
        },
      }),
    ).rejects.toThrow("boom");

    expect(await db.getAll("rdos")).toHaveLength(0);
    expect(await db.getAll("outbox_mutations")).toHaveLength(0);
    expect(await db.getAll("operational_events")).toHaveLength(0);
    expect(queuedEvents).toBe(0);
  });
});
