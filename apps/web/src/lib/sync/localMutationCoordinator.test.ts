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
  type CommitLocalMutationInput,
  type LocalMutationTransaction,
} from "./localMutationCoordinator";
import {
  buildMutationEnvelope,
  mutationPayloadHash,
  type BuildMutationEnvelopeInput,
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
    write: (tx: LocalMutationTransaction<"rdos">) => {
      void tx.objectStore("rdos").put(rdo);
      return undefined;
    },
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

  it("snapshots every envelope field before the first async boundary", async () => {
    const entity: BuildMutationEnvelopeInput["entity"] = {
      type: "RDO",
      id: localRdo().id,
    };
    const authorizationScope = [OBRA_ID];
    const actor = {
      ...fixtureActor(),
      authorizationScope,
    };
    const dependencies = ["dependency-original"];
    const input: BuildMutationEnvelopeInput = {
      entity,
      operation: "ENVIAR_RDO",
      baseVersion: 4,
      previousState: { statusRdo: "RASCUNHO" },
      newState: { statusRdo: "ENVIADO" },
      actor,
      correlationId: "correlation-original",
      causationId: "causation-original",
      createdAt: CREATED_AT,
      transport: "SYNC_PUSH",
      dependsOnMutationIds: dependencies,
    };

    const pending = buildMutationEnvelope(input);
    entity.type = "MENSAGEM";
    entity.id = "00000000-0000-4000-8000-000000000099";
    input.operation = "CRIAR_MENSAGEM";
    input.baseVersion = 99;
    input.transport = "OBJECT_UPLOAD";
    input.correlationId = "correlation-mutated";
    input.causationId = "causation-mutated";
    input.createdAt = "2026-07-17T13:00:00.000Z";
    actor.actorId = "00000000-0000-4000-8000-000000000098";
    actor.actorName = "Operador alterado";
    actor.deviceId = "00000000-0000-4000-8000-000000000097";
    authorizationScope[0] =
      "00000000-0000-4000-8000-000000000096";
    dependencies[0] = "dependency-mutated";

    const mutation = await pending;

    expect(mutation).toMatchObject({
      entidadeTipo: "RDO",
      entidadeId: localRdo().id,
      operacao: "ENVIAR_RDO",
      baseVersao: 4,
      transport: "SYNC_PUSH",
      criadaNoClienteEm: CREATED_AT,
      updatedAt: CREATED_AT,
      dependsOnMutationIds: ["dependency-original"],
      correlationId: "correlation-original",
      trace: {
        actorId: ownerId,
        deviceId: fixtureActor().deviceId,
        authorizationScope: [OBRA_ID],
        correlationId: "correlation-original",
        causationId: "causation-original",
      },
    });
  });

  it.each([Number.NaN, Number.POSITIVE_INFINITY])(
    "rejects a non-finite base version (%s)",
    async (baseVersion) => {
      await expect(
        buildMutationEnvelope({
          entity: { type: "RDO", id: localRdo().id },
          operation: "ENVIAR_RDO",
          baseVersion,
          previousState: {},
          newState: { statusRdo: "ENVIADO" },
          actor: fixtureActor(),
        }),
      ).rejects.toThrow("baseVersion must be finite or null.");
    },
  );

  it.each([
    ["blank", [" "]],
    ["sparse", new Array<string>(1)],
  ])("rejects a %s dependency id", async (_label, dependencies) => {
    await expect(
      buildMutationEnvelope({
        entity: { type: "RDO", id: localRdo().id },
        operation: "ENVIAR_RDO",
        baseVersion: null,
        previousState: {},
        newState: { statusRdo: "ENVIADO" },
        actor: fixtureActor(),
        dependsOnMutationIds: dependencies,
      }),
    ).rejects.toThrow(
      "dependsOnMutationIds[0] must be a nonblank string.",
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
        write: (tx) => {
          void tx.objectStore("rdos").put(rdo).catch(() => undefined);
          throw new Error("boom");
        },
      }),
    ).rejects.toThrow("boom");

    expect(await db.getAll("rdos")).toHaveLength(0);
    expect(await db.getAll("outbox_mutations")).toHaveLength(0);
    expect(await db.getAll("operational_events")).toHaveLength(0);
    expect(queuedEvents).toBe(0);
  });

  it("rejects a delayed async writer before a partial domain commit", async () => {
    const db = await getCortexDb();
    let queuedEvents = 0;
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, () => {
      queuedEvents += 1;
    });
    const rdo = localRdo();
    const input = coordinatorInput(rdo);
    const delayedWriter = (async (
      tx: LocalMutationTransaction<"rdos">,
    ) => {
      await tx.objectStore("rdos").put(rdo);
      await new Promise((resolve) => setTimeout(resolve, 0));
      throw new Error("late failure");
    }) as unknown as typeof input.write;

    await expect(
      commitLocalMutation({ ...input, write: delayedWriter }),
    ).rejects.toThrow(
      "Local mutation write must synchronously enqueue IndexedDB requests and return undefined.",
    );

    expect(await db.getAll("rdos")).toHaveLength(0);
    expect(await db.getAll("outbox_mutations")).toHaveLength(0);
    expect(await db.getAll("operational_events")).toHaveLength(0);
    expect(queuedEvents).toBe(0);
  });

  it("rejects a directly returned IndexedDB request promise", async () => {
    const db = await getCortexDb();
    let queuedEvents = 0;
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, () => {
      queuedEvents += 1;
    });
    const rdo = localRdo();
    const input = coordinatorInput(rdo);
    const promiseReturningWriter = ((
      tx: LocalMutationTransaction<"rdos">,
    ) => tx.objectStore("rdos").put(rdo)) as unknown as typeof input.write;

    await expect(
      commitLocalMutation({ ...input, write: promiseReturningWriter }),
    ).rejects.toThrow(
      "Local mutation write must synchronously enqueue IndexedDB requests and return undefined.",
    );

    expect(await db.getAll("rdos")).toHaveLength(0);
    expect(await db.getAll("outbox_mutations")).toHaveLength(0);
    expect(await db.getAll("operational_events")).toHaveLength(0);
    expect(queuedEvents).toBe(0);
  });

  it("persists one immutable state snapshot across async boundaries", async () => {
    const db = await getCortexDb();
    const rdo = localRdo();
    const previousState = {
      statusRdo: "NOVO",
      metadata: { source: "before" },
    };
    const newState = {
      ...rdo,
      payload: { observacoes: "snapshot original" },
    };
    const expectedPreviousState = structuredClone(previousState);
    const expectedNewState = structuredClone(newState);
    const input = {
      ...coordinatorInput(rdo),
      previousState,
      newState,
      write: (tx: LocalMutationTransaction<"rdos">) => {
        void tx.objectStore("rdos").put(rdo);
        newState.payload.observacoes = "mutated inside write";
        previousState.metadata.source = "mutated inside write";
        return undefined;
      },
    };

    const committed = commitLocalMutation(input);
    newState.numeroRdo = "MUTATED-AFTER-INVOKE";
    newState.payload.observacoes = "mutated after invoke";
    previousState.statusRdo = "MUTATED-AFTER-INVOKE";

    const result = await committed;

    expect(result.mutation.payload).toEqual(expectedNewState);
    expect(result.mutation.trace.payloadHash).toBe(
      await mutationPayloadHash(expectedNewState),
    );
    expect(result.mutation.fieldPatch.changed).toMatchObject({
      payload: { observacoes: "snapshot original" },
      numeroRdo: "RDO-001",
    });
    expect(result.event.previousState).toEqual(expectedPreviousState);
    expect(result.event.newState).toEqual(expectedNewState);
    expect(
      await db.get("outbox_mutations", result.mutation.clientMutationId),
    ).toMatchObject({ payload: expectedNewState });
    expect(await db.get("operational_events", result.event.id)).toMatchObject({
      previousState: expectedPreviousState,
      newState: expectedNewState,
    });
  });

  it("keeps pre-await provenance across the coordinator boundary", async () => {
    const rdo = localRdo();
    const authorizationScope = [OBRA_ID];
    const dependencies = ["dependency-original"];
    const input: CommitLocalMutationInput<"rdos"> = {
      ...coordinatorInput(rdo),
      entity: {
        type: "RDO",
        id: rdo.id,
        name: "RDO original",
        obraId: rdo.obraId,
        rdoId: rdo.id,
      },
      actor: {
        ...fixtureActor(),
        actorName: "Operador original",
        authorizationScope,
      },
      correlationId: "correlation-original",
      causationId: "causation-original",
      dependsOnMutationIds: dependencies,
    };

    const pending = commitLocalMutation(input);
    input.entity.id = "00000000-0000-4000-8000-000000000095";
    input.entity.name = "RDO alterado";
    input.entity.obraId =
      "00000000-0000-4000-8000-000000000094";
    input.entity.rdoId = "00000000-0000-4000-8000-000000000093";
    input.actor.actorName = "Operador alterado";
    authorizationScope[0] =
      "00000000-0000-4000-8000-000000000092";
    input.correlationId = "correlation-mutated";
    input.causationId = "causation-mutated";
    input.eventType = "RDO_EDITADO";
    dependencies[0] = "dependency-mutated";

    const result = await pending;

    expect(result.mutation).toMatchObject({
      entidadeId: rdo.id,
      dependsOnMutationIds: ["dependency-original"],
      correlationId: "correlation-original",
      trace: {
        authorizationScope: [OBRA_ID],
        correlationId: "correlation-original",
        causationId: "causation-original",
      },
    });
    expect(result.event).toMatchObject({
      type: "RDO_CRIADO",
      principalEntity: {
        id: rdo.id,
        nome: "RDO original",
      },
      obraId: OBRA_ID,
      rdoId: rdo.id,
      responsibleUserName: "Operador original",
      correlationId: "correlation-original",
      causationId: "causation-original",
    });
  });

  it("rejects an empty authorization scope before opening a write", async () => {
    const db = await getCortexDb();
    let queuedEvents = 0;
    let writeCalls = 0;
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, () => {
      queuedEvents += 1;
    });
    const input = coordinatorInput();

    await expect(
      commitLocalMutation({
        ...input,
        actor: {
          ...input.actor,
          authorizationScope: [],
        },
        write: (tx) => {
          writeCalls += 1;
          void tx.objectStore("rdos").put(localRdo());
          return undefined;
        },
      }),
    ).rejects.toThrow("actor.authorizationScope is required.");

    expect(await db.getAll("rdos")).toHaveLength(0);
    expect(await db.getAll("outbox_mutations")).toHaveLength(0);
    expect(await db.getAll("operational_events")).toHaveLength(0);
    expect(writeCalls).toBe(0);
    expect(queuedEvents).toBe(0);
  });

  it("rejects a sparse authorization scope before opening a write", async () => {
    const db = await getCortexDb();
    let queuedEvents = 0;
    let writeCalls = 0;
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, () => {
      queuedEvents += 1;
    });
    const input = coordinatorInput();
    const sparseScope = new Array<string>(1);

    await expect(
      commitLocalMutation({
        ...input,
        actor: {
          ...input.actor,
          authorizationScope: sparseScope,
        },
        write: (tx) => {
          writeCalls += 1;
          void tx.objectStore("rdos").put(localRdo());
          return undefined;
        },
      }),
    ).rejects.toThrow(
      "actor.authorizationScope[0] must be a nonblank string.",
    );

    expect(await db.getAll("rdos")).toHaveLength(0);
    expect(await db.getAll("outbox_mutations")).toHaveLength(0);
    expect(await db.getAll("operational_events")).toHaveLength(0);
    expect(writeCalls).toBe(0);
    expect(queuedEvents).toBe(0);
  });
});
