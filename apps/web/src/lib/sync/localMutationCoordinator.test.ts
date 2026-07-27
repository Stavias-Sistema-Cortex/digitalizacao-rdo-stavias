import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  getSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type { LocalRdoRecord } from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { repairRdoCreateMutationsForSync } from "../db/localRdoService";
import {
  LOCAL_MUTATION_QUEUED_EVENT,
  commitLocalMutation,
  type LocalMutationCommand,
  type LocalMutationDomainWrite,
} from "./localMutationCoordinator";
import {
  buildCanonicalMutation,
  mutationPayloadHash,
} from "./mutationEnvelope";
import { captureOnlineSyncSession } from "./syncSession";

const OBRA_ID = "00000000-0000-4000-8000-000000000001";
const FOREIGN_OBRA_ID = "00000000-0000-4000-8000-000000000006";
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

function writePlan(
  record: LocalRdoRecord,
): readonly LocalMutationDomainWrite<"rdos">[] {
  return [{ store: "rdos", value: record, principal: true }];
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
    occurredAt: OCCURRED_AT,
    previousSnapshot: {},
    nextSnapshot: { ...record },
    eventType: "RDO_CRIADO",
    write: () => writePlan(record),
  };
}

function betaSession(id = userId, obraIds = [OBRA_ID]) {
  return {
    colaboradorId: id,
    nome: "Operador de campo",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds,
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession(betaSession());
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
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

  it("validates UUIDs, UTC instants, enums, base version and transport coherence", async () => {
    const input = command();
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        clientMutationId: "not-a-uuid",
      }),
    ).rejects.toThrow(/clientMutationId.*UUID/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        occurredAt: "2026-07-21T09:00:00-03:00",
      }),
    ).rejects.toThrow(/UTC ISO/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        operation: "UPDATE",
      }),
    ).rejects.toThrow(/does not represent UPDATE/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        baseVersion: 0,
      }),
    ).rejects.toThrow(/CREATE requires baseVersion null/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        transport: "EMAIL" as never,
      }),
    ).rejects.toThrow(/transport EMAIL/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        transportOperation: "DROP_DATABASE" as never,
      }),
    ).rejects.toThrow(/transportOperation DROP_DATABASE/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        operation: "UPSERT" as never,
      }),
    ).rejects.toThrow(/operation UPSERT/i);
  });

  it("derives changedFields from the exact sorted snapshot delta", async () => {
    const input = command();
    const previousSnapshot = { kept: 1, removed: true, updated: "old" };
    const nextSnapshot = { kept: 1, added: true, updated: "new" };
    const built = await buildCanonicalMutation({
      ...input,
      operation: "UPDATE",
      transportOperation: "ATUALIZAR_RDO_RASCUNHO",
      baseVersion: 7,
      previousSnapshot,
      nextSnapshot,
      changedFields: ["added", "removed", "updated"],
      authorizationScope: [OBRA_ID],
    });

    expect(built.mutation.changedFields).toEqual([
      "added",
      "removed",
      "updated",
    ]);
    expect(built.mutation.fieldPatch).toEqual({
      changed: { added: true, updated: "new" },
      baseValues: { removed: true, updated: "old" },
    });
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        changedFields: ["payload"],
      }),
    ).rejects.toThrow(/exact snapshot delta/i);
    await expect(
      buildCanonicalMutation({
        ...input,
        authorizationScope: [OBRA_ID],
        changedFields: [
          ...Object.keys(input.nextSnapshot).sort(),
          Object.keys(input.nextSnapshot).sort()[0],
        ],
      }),
    ).rejects.toThrow(/exact snapshot delta/i);
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
      occurredAt: OCCURRED_AT,
      payload: command().nextSnapshot,
      status: "PENDING",
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "CRIAR_RDO",
    });
    expect(result.mutation.changedFields).toEqual(
      Object.keys(command().nextSnapshot).sort(),
    );
    expect(result.mutation.trace.authorizationScope).toEqual([OBRA_ID]);
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
      responsibleUserName: "Operador de campo",
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
    const asyncWriter = async () => writePlan(rdo());

    await expect(
      commitLocalMutation({ ...input, write: asyncWriter as never }),
    ).rejects.toThrow(/write plan must be synchronous/i);

    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
  });

  it("rejects a principal domain write that differs from nextSnapshot", async () => {
    const database = await getCortexDb();
    const input = command();
    await expect(
      commitLocalMutation({
        ...input,
        write: () => writePlan({ ...rdo(), numeroRdo: "DIVERGENTE" }),
      }),
    ).rejects.toThrow(/must equal nextSnapshot/i);
    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
  });

  it("binds entityId to the principal record and forbids a second write in its store", async () => {
    await expect(
      commitLocalMutation({
        ...command(),
        entityId: FOREIGN_OBRA_ID,
      }),
    ).rejects.toThrow(/write id must equal entityId/i);
    await expect(
      commitLocalMutation({
        ...command(),
        write: () => [
          ...writePlan(rdo()),
          {
            store: "rdos",
            value: { ...rdo(), numeroRdo: "SOBRESCRITO" },
          },
        ],
      }),
    ).rejects.toThrow(/principal domain store/i);
    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
  });

  it("binds an RDO envelope to the rdos store and the same authorized worksite", async () => {
    const wrongStoreValue = {
      id: RDO_ID,
      codigoContrato: "C-1",
      nome: "Obra indevida",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      updatedAt: OCCURRED_AT,
    };
    await expect(
      commitLocalMutation({
        ...command(),
        nextSnapshot: wrongStoreValue,
        write: () => [{
          store: "obras",
          value: wrongStoreValue,
          principal: true,
        }],
      } as unknown as LocalMutationCommand<"obras">),
    ).rejects.toThrow(/RDO requires principal store rdos/i);

    const foreignRecord = { ...rdo(), obraId: FOREIGN_OBRA_ID };
    await expect(
      commitLocalMutation({
        ...command(foreignRecord),
        obraId: OBRA_ID,
      }),
    ).rejects.toThrow(/RDO obraId must equal envelope obraId/i);
  });

  it("clones the declarative domain write before hashing", async () => {
    const record = rdo();
    const input = command(record);
    const pending = commitLocalMutation(input);
    record.numeroRdo = "MUTADO";
    record.payload = { observacoes: "mutated after call" };
    input.nextSnapshot.numeroRdo = "MUTADO";

    const result = await pending;
    expect(result.mutation.payload).toMatchObject({
      numeroRdo: "RDO-001",
      payload: { observacoes: "Registro offline" },
    });
    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toEqual(result.mutation.payload);
    expect(result.event.newState).toEqual(result.mutation.payload);
  });

  it("rejects mismatched users, foreign BETA worksites and absent sessions", async () => {
    const database = await getCortexDb();
    await expect(
      commitLocalMutation({ ...command(), userId: crypto.randomUUID() }),
    ).rejects.toThrow(/não pertence à sessão ativa/i);
    await expect(
      commitLocalMutation({ ...command(), obraId: FOREIGN_OBRA_ID }),
    ).rejects.toThrow(/obra.*escopo/i);
    clearSession();
    await expect(commitLocalMutation(command())).rejects.toThrow(/sessão válida/i);
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
  });

  it("rejects unknown event types and non-UUID related entity IDs", async () => {
    await expect(
      commitLocalMutation({
        ...command(),
        eventType: "RDO_INVENTADO" as never,
      }),
    ).rejects.toThrow(/eventType RDO_INVENTADO/i);
    await expect(
      commitLocalMutation({
        ...command(),
        relatedEntities: [{ tipo: "OBRA", id: "obra-estrangeira" }],
      }),
    ).rejects.toThrow(/relatedEntities\[0\]\.id.*UUID/i);
  });

  it("derives ALFA global authorization without trusting the command", async () => {
    setSession({
      colaboradorId: userId,
      nome: "Administrador",
      papelAcesso: "ALFA",
      escopoGlobal: true,
      obraIds: [],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    const result = await commitLocalMutation({
      ...command({ ...rdo(), obraId: FOREIGN_OBRA_ID }),
      obraId: FOREIGN_OBRA_ID,
      nextSnapshot: { ...rdo(), obraId: FOREIGN_OBRA_ID },
      write: () => writePlan({ ...rdo(), obraId: FOREIGN_OBRA_ID }),
    });
    expect(result.mutation.trace.authorizationScope).toEqual(["ALFA:GLOBAL"]);
    expect(result.event.responsibleUserName).toBe("Administrador");
  });

  it("aborts when the active session changes while the payload hash is pending", async () => {
    let releaseDigest: ((value: ArrayBuffer) => void) | undefined;
    vi.spyOn(crypto.subtle, "digest").mockImplementationOnce(
      () => new Promise<ArrayBuffer>((resolve) => {
        releaseDigest = resolve;
      }),
    );
    const pending = commitLocalMutation(command());
    await vi.waitFor(() => expect(releaseDigest).toBeTypeOf("function"));
    setSession(betaSession(crypto.randomUUID()));
    releaseDigest?.(new Uint8Array(32).buffer);

    await expect(pending).rejects.toThrow(/sessão.*mudou|não pertence/i);
    setSession(betaSession());
    const database = await getCortexDb();
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
  });

  it("aborts on an exact auth rotation even when the profile fields are equal", async () => {
    let releaseDigest: ((value: ArrayBuffer) => void) | undefined;
    vi.spyOn(crypto.subtle, "digest").mockImplementationOnce(
      () => new Promise<ArrayBuffer>((resolve) => {
        releaseDigest = resolve;
      }),
    );
    const pending = commitLocalMutation(command());
    await vi.waitFor(() => expect(releaseDigest).toBeTypeOf("function"));
    setSession(betaSession());
    releaseDigest?.(new Uint8Array(32).buffer);

    await expect(pending).rejects.toThrow(/sessão mudou/i);
    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
    expect(await database.getAll("operational_events")).toHaveLength(0);
  });

  it("aborts the IndexedDB transaction when the active session changes during its writes", async () => {
    const originalPut = IDBObjectStore.prototype.put;
    let switched = false;
    vi.spyOn(IDBObjectStore.prototype, "put").mockImplementation(function (
      this: IDBObjectStore,
      value: unknown,
      key?: IDBValidKey,
    ) {
      if (!switched) {
        switched = true;
        setSession(betaSession(crypto.randomUUID()));
      }
      return key === undefined
        ? originalPut.call(this, value)
        : originalPut.call(this, value, key);
    });

    await expect(commitLocalMutation(command())).rejects.toThrow(/sessão mudou/i);
    setSession(betaSession());
    const database = await getCortexDb();
    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
    expect(await database.getAll("operational_events")).toHaveLength(0);
  });

  it("keeps canonical RDO creates byte-for-byte intact in the legacy pre-push repair", async () => {
    const result = await commitLocalMutation(command());
    expect(await repairRdoCreateMutationsForSync()).toBe(0);
    const database = await getCortexDb();
    expect(
      await database.get("outbox_mutations", result.mutation.clientMutationId),
    ).toEqual(result.mutation);
  });

  it("keeps a pre-V48 RDO create pending with a literal context block instead of terminal error", async () => {
    const database = await getCortexDb();
    const localRdo = rdo();
    const mutation = {
      clientMutationId: "00000000-0000-4000-8000-000000000018",
      entidadeTipo: "RDO" as const,
      entidadeId: RDO_ID,
      operacao: "CRIAR_RDO" as const,
      baseVersao: null,
      payload: { stale: true },
      status: "ERROR" as const,
      tentativas: 3,
      ultimaTentativaEm: OCCURRED_AT,
      ultimoErro: "payload legado",
      conflito: null,
      criadaNoClienteEm: OCCURRED_AT,
      updatedAt: OCCURRED_AT,
    };
    await database.put("rdos", localRdo);
    await database.put("outbox_mutations", mutation);

    expect(await repairRdoCreateMutationsForSync()).toBe(1);

    const mutations = await database.getAll("outbox_mutations");
    expect(mutations).toHaveLength(2);
    expect(
      mutations.find(
        (candidate) =>
          candidate.clientMutationId === mutation.clientMutationId,
      ),
    ).toMatchObject({
      status: "REJECTED",
      blockedReason: expect.stringMatching(/^SUPERSEDED_BY:/),
    });
    expect(
      mutations.find(
        (candidate) =>
          candidate.clientMutationId !== mutation.clientMutationId,
      ),
    ).toMatchObject({
      schemaVersion: 13,
      status: "PENDING",
      blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
      causationId: mutation.clientMutationId,
    });
  });

  it("enriches and unblocks a pre-V48 queue when the local RDO has a complete context receipt", async () => {
    const database = await getCortexDb();
    const localRdo = {
      ...rdo(),
      payload: {
        previousRdoId: "00000000-0000-4000-8000-000000000021",
        creationContextVersion: 48,
        apontadorColaboradorId: userId,
        maoObra: [{
          id: "00000000-0000-4000-8000-000000000022",
          colaboradorId: userId,
          nomeColaborador: "Operador de campo",
          cargo: "Apontador",
          tipoVinculo: "CONTRATADO",
          quantidade: 1,
          origemItemId: "00000000-0000-4000-8000-000000000023",
        }],
      },
    };
    const mutation = {
      clientMutationId: "00000000-0000-4000-8000-000000000028",
      entidadeTipo: "RDO" as const,
      entidadeId: RDO_ID,
      operacao: "CRIAR_RDO" as const,
      baseVersao: null,
      payload: { stale: true },
      status: "PENDING" as const,
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: null,
      conflito: null,
      blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
      criadaNoClienteEm: OCCURRED_AT,
      updatedAt: OCCURRED_AT,
    };
    await database.put("rdos", localRdo);
    await database.put("outbox_mutations", mutation);

    expect(await repairRdoCreateMutationsForSync()).toBe(1);

    expect(
      await database.get("outbox_mutations", mutation.clientMutationId),
    ).toMatchObject({
      status: "PENDING",
      blockedReason: null,
      payload: {
        previousRdoId: "00000000-0000-4000-8000-000000000021",
        creationContextVersion: 48,
        apontadorColaboradorId: userId,
        maoObra: [{
          id: "00000000-0000-4000-8000-000000000022",
          origemItemId: "00000000-0000-4000-8000-000000000023",
        }],
      },
    });
  });

  it("rolls back legacy RDO-create preflight repair on same-scope session rotation", async () => {
    const database = await getCortexDb();
    const localRdo = rdo();
    const mutation = {
      clientMutationId: "00000000-0000-4000-8000-000000000008",
      entidadeTipo: "RDO" as const,
      entidadeId: RDO_ID,
      operacao: "CRIAR_RDO" as const,
      baseVersao: null,
      payload: { stale: true },
      status: "PENDING" as const,
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: "payload legado",
      conflito: null,
      criadaNoClienteEm: OCCURRED_AT,
      updatedAt: OCCURRED_AT,
    };
    await database.put("rdos", localRdo);
    await database.put("outbox_mutations", mutation);
    const guard = captureOnlineSyncSession();
    const originalSession = getSession()!;
    const originalPut = IDBObjectStore.prototype.put;
    let rotated = false;
    const putSpy = vi
      .spyOn(IDBObjectStore.prototype, "put")
      .mockImplementation(function (
        this: IDBObjectStore,
        value: unknown,
        key?: IDBValidKey,
      ) {
        if (!rotated) {
          rotated = true;
          setSession({
            ...originalSession,
            expiraEm: new Date(
              Date.parse(originalSession.expiraEm) + 60_000,
            ).toISOString(),
          });
        }
        return key === undefined
          ? originalPut.call(this, value)
          : originalPut.call(this, value, key);
      });

    await expect(
      repairRdoCreateMutationsForSync(guard),
    ).rejects.toBeDefined();

    putSpy.mockRestore();
    setSession(originalSession);
    expect(await database.get("outbox_mutations", mutation.clientMutationId))
      .toEqual(mutation);
    expect(await database.get("rdos", RDO_ID)).toEqual(localRdo);
  });
});
