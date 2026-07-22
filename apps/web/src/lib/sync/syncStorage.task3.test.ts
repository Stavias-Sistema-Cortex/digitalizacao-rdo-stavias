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
import {
  commitLocalMutation,
  type LocalMutationCommand,
} from "./localMutationCoordinator";
import { canonicalMutationJson } from "./mutationEnvelope";
import { captureOnlineSyncSession } from "./syncSession";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
  reconcileCanonicalConflict,
  recoverCanonicalConflictReconciliations,
  recoverInterruptedMutations,
} from "./syncStorage";

const OBRA_ID = "00000000-0000-4000-8000-000000000101";
const DEVICE_ID = "00000000-0000-4000-8000-000000000102";
const RDO_ID = "00000000-0000-4000-8000-000000000103";
const MUTATION_1 = "00000000-0000-4000-8000-000000000104";
const EVENT_1 = "00000000-0000-4000-8000-000000000105";
const MUTATION_2 = "00000000-0000-4000-8000-000000000106";
const EVENT_2 = "00000000-0000-4000-8000-000000000107";

let userId = "";
let databaseName = "";

function session() {
  return {
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

function record(
  title: string,
  responsible = "Ana",
  version = 3,
): LocalRdoRecord & { titulo: string; responsavel: string } {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-101",
    dataRdo: "2026-07-22",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: version,
    payload: {},
    createdAt: "2026-07-22T12:00:00.000Z",
    updatedAt: "2026-07-22T12:00:00.000Z",
    titulo: title,
    responsavel: responsible,
  };
}

function command(
  id: string,
  eventId: string,
  previous: ReturnType<typeof record>,
  next: ReturnType<typeof record>,
): LocalMutationCommand<"rdos"> {
  return {
    clientMutationId: id,
    ontologyEventId: eventId,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: RDO_ID,
    entityName: next.numeroRdo,
    operation: "UPDATE",
    transportOperation: "ATUALIZAR_RDO_RASCUNHO",
    baseVersion: previous.versaoEntidade,
    occurredAt:
      id === MUTATION_1
        ? "2026-07-22T12:00:01.000Z"
        : "2026-07-22T12:00:02.000Z",
    previousSnapshot: { ...previous },
    nextSnapshot: { ...next },
    eventType: "RDO_EDITADO",
    write: () => [{ store: "rdos", value: next, principal: true }],
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession(session());
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("atomic canonical push results", () => {
  it("updates only the event correlated by clientMutationId", async () => {
    const base = record("A");
    const first = await commitLocalMutation(
      command(MUTATION_1, EVENT_1, base, record("B")),
    );
    const second = await commitLocalMutation(
      command(MUTATION_2, EVENT_2, record("B"), record("C")),
    );
    await markMutationAsSyncing(first.mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_1,
      status: "APLICADA",
      eventoServidorCommitSeq: 81,
      resultado: { versaoEntidade: 4 },
    });

    const database = await getCortexDb();
    expect(await database.get("operational_events", EVENT_1)).toMatchObject({
      clientMutationId: MUTATION_1,
      result: "SYNCED",
      syncStatus: "SYNCED",
      serverCommitSequence: 81,
      entityVersion: 4,
    });
    expect(await database.get("operational_events", EVENT_2)).toMatchObject({
      clientMutationId: MUTATION_2,
      result: "PENDING",
      syncStatus: "PENDING_SYNC",
    });
    expect(second.mutation.status).toBe("PENDING");
  });

  it("rolls back a push-result transaction on same-scope session rotation", async () => {
    const committed = await commitLocalMutation(
      command(MUTATION_1, EVENT_1, record("A"), record("B")),
    );
    await markMutationAsSyncing(committed.mutation);
    const guard = captureOnlineSyncSession();
    const database = await getCortexDb();
    const originalSession = getSession()!;
    const databaseWithTransaction = database as unknown as {
      transaction: (...args: unknown[]) => unknown;
    };
    const originalTransaction =
      databaseWithTransaction.transaction.bind(database);
    let startedTransaction: { done: Promise<unknown> } | null = null;
    const transactionSpy = vi
      .spyOn(databaseWithTransaction, "transaction")
      .mockImplementation(
      (...args: unknown[]) => {
        const transaction = originalTransaction(...args);
        startedTransaction = transaction as { done: Promise<unknown> };
        queueMicrotask(() => {
          setSession({
            ...originalSession,
            expiraEm: new Date(
              Date.parse(originalSession.expiraEm) + 60_000,
            ).toISOString(),
          });
        });
        return transaction;
      },
      );

    await expect(
      applyPushResultAtomically(
        {
          clientMutationId: MUTATION_1,
          status: "APLICADA",
          eventoServidorCommitSeq: 99,
          resultado: { versaoEntidade: 4 },
        },
        guard,
      ),
    ).rejects.toBeDefined();
    await startedTransaction?.done.catch(() => undefined);

    transactionSpy.mockRestore();
    setSession(originalSession);
    expect(await database.get("outbox_mutations", MUTATION_1)).toMatchObject({
      status: "SYNCING",
    });
    const event = await database.get("operational_events", EVENT_1);
    expect(event).toMatchObject({ result: "SYNCING" });
    expect(event?.serverCommitSequence).toBeFalsy();
  });

  it("keeps a partial conflict terminal and durable without mutating v13 provenance", async () => {
    const committed = await commitLocalMutation(
      command(MUTATION_1, EVENT_1, record("A"), record("B")),
    );
    const immutableBefore = canonicalMutationJson({
      schemaVersion: committed.mutation.schemaVersion,
      clientMutationId: committed.mutation.clientMutationId,
      payload: committed.mutation.payload,
      trace: committed.mutation.trace,
      fieldPatch: committed.mutation.fieldPatch,
    });
    await markMutationAsSyncing(committed.mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_1,
      status: "DESCARTADA",
      conflito: { versaoAtual: 4 },
      erro: "Conflito de versão.",
    });
    const replacement = await reconcileCanonicalConflict(MUTATION_1);

    await closeCortexDb();
    const database = await getCortexDb();
    const original = await database.get("outbox_mutations", MUTATION_1);
    expect(replacement).toBeNull();
    expect(original).toMatchObject({
      status: "CONFLICT",
      blockedReason: "REMOTE_SNAPSHOT_UNAVAILABLE",
      lastSafeCode: "VERSION_CONFLICT",
      conflito: {
        remoteSnapshotComplete: false,
        fieldConflicts: expect.arrayContaining([
          expect.objectContaining({
            field: "titulo",
            remote: { present: false },
          }),
        ]),
      },
    });
    expect(
      canonicalMutationJson({
        schemaVersion: original?.schemaVersion,
        clientMutationId: original?.clientMutationId,
        payload: original?.payload,
        trace: original?.schemaVersion === 13 ? original.trace : null,
        fieldPatch:
          original?.schemaVersion === 13 ? original.fieldPatch : null,
      }),
    ).toBe(immutableBefore);
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
  });

  it("creates a new traceable canonical mutation for a complete disjoint merge", async () => {
    const base = record("A", "Ana", 3);
    const local = record("B", "Ana", 3);
    const committed = await commitLocalMutation(
      command(MUTATION_1, EVENT_1, base, local),
    );
    await markMutationAsSyncing(committed.mutation);
    await applyPushResultAtomically({
      clientMutationId: MUTATION_1,
      status: "CONFLITO",
      conflito: {
        versaoAtual: 4,
        remoteCompleto: true,
        snapshotRemoto: record("A", "Bia", 4),
      },
      erro: "Conflito de versão.",
    });

    const replacement = await reconcileCanonicalConflict(
      MUTATION_1,
      MUTATION_2,
      EVENT_2,
      "2026-07-22T12:00:03.000Z",
    );

    const database = await getCortexDb();
    expect(replacement).toMatchObject({
      clientMutationId: MUTATION_2,
      causationId: MUTATION_1,
      correlationId: MUTATION_1,
      baseVersion: 4,
      status: "PENDING",
      payload: expect.objectContaining({ titulo: "B", responsavel: "Bia" }),
    });
    expect(await database.get("outbox_mutations", MUTATION_1)).toMatchObject({
      status: "CONFLICT",
      clientMutationId: MUTATION_1,
    });
    expect(await database.get("operational_events", EVENT_2)).toMatchObject({
      clientMutationId: MUTATION_2,
      causationId: MUTATION_1,
      result: "PENDING",
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      titulo: "B",
      responsavel: "Bia",
      versaoEntidade: 4,
      syncStatus: "PENDING_SYNC",
    });
  });

  it("recovers one auto-merge replacement after conflict persistence and reload", async () => {
    const base = record("A", "Ana", 3);
    const local = record("B", "Ana", 3);
    const committed = await commitLocalMutation(
      command(MUTATION_1, EVENT_1, base, local),
    );
    await markMutationAsSyncing(committed.mutation);
    await applyPushResultAtomically({
      clientMutationId: MUTATION_1,
      status: "CONFLITO",
      conflito: {
        versaoAtual: 4,
        remoteCompleto: true,
        snapshotRemoto: record("A", "Bia", 4),
      },
      erro: "Conflito de versão.",
    });

    await closeCortexDb();
    expect(
      await recoverCanonicalConflictReconciliations(undefined, {
        replacementMutationId: () => MUTATION_2,
        replacementEventId: () => EVENT_2,
        occurredAt: () => "2026-07-22T12:00:03.000Z",
      }),
    ).toBe(1);
    expect(await recoverCanonicalConflictReconciliations()).toBe(0);

    await closeCortexDb();
    const database = await getCortexDb();
    const all = await database.getAll("outbox_mutations");
    expect(all).toHaveLength(2);
    expect(await database.get("outbox_mutations", MUTATION_1)).toMatchObject({
      status: "CONFLICT",
    });
    expect(await database.get("outbox_mutations", MUTATION_2)).toMatchObject({
      status: "PENDING",
      causationId: MUTATION_1,
    });
  });

  it("recovers only the exact interrupted event and preserves another terminal conflict", async () => {
    const first = await commitLocalMutation(
      command(MUTATION_1, EVENT_1, record("A"), record("B")),
    );
    const second = await commitLocalMutation(
      command(MUTATION_2, EVENT_2, record("B"), record("C")),
    );
    await markMutationAsSyncing(first.mutation);
    await applyPushResultAtomically({
      clientMutationId: MUTATION_1,
      status: "DESCARTADA",
      conflito: { versaoAtual: 4 },
    });
    await markMutationAsSyncing(second.mutation);

    await recoverInterruptedMutations();

    const database = await getCortexDb();
    expect(await database.get("operational_events", EVENT_1)).toMatchObject({
      result: "CONFLICT",
      syncStatus: "SYNC_FAILED",
    });
    expect(await database.get("operational_events", EVENT_2)).toMatchObject({
      result: "PENDING",
      syncStatus: "PENDING_SYNC",
      errorCategory: "INTERRUPTED_RUN",
    });
  });

  it("persists server validation as terminal and server errors as row-scoped retry", async () => {
    const rejected = await commitLocalMutation(
      command(MUTATION_1, EVENT_1, record("A"), record("B")),
    );
    const transient = await commitLocalMutation(
      command(MUTATION_2, EVENT_2, record("B"), record("C")),
    );
    await markMutationAsSyncing(rejected.mutation);
    await markMutationAsSyncing(transient.mutation);

    await applyPushResultAtomically({
      clientMutationId: MUTATION_1,
      status: "REJEITADA",
      resultado: {
        rejeicao: { categoria: "RELATED_ENTITY_SCOPE" },
      },
    });
    await applyPushResultAtomically({
      clientMutationId: MUTATION_2,
      status: "ERRO",
      erro: "Falha interna.",
    });

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", MUTATION_1)).toMatchObject({
      status: "REJECTED",
      lastSafeCode: "RELATED_ENTITY_SCOPE",
      nextAttemptAt: null,
    });
    expect(await database.get("outbox_mutations", MUTATION_2)).toMatchObject({
      status: "PENDING",
      retryAttempt: 1,
      lastSafeCode: "SERVER_TRANSIENT",
      nextAttemptAt: expect.any(String),
    });
  });
});
