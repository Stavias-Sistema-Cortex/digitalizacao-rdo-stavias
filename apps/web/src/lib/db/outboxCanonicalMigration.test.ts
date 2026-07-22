import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import { clearSession, setSession } from "../../features/auth/authSession";
import {
  closeCortexDb,
  getCortexDb,
} from "./cortexDb";
import type {
  CanonicalOutboxMutationRecord,
  LegacyOperationalEventRecord,
  LegacyOutboxMutationRecord,
  LocalRdoRecord,
} from "./db.types";
import { currentDataDatabaseName } from "./localDataNamespace";
import {
  BLOCKED_DEPENDENCY_REVIEW_REASON,
  CYCLIC_DEPENDENCY_REVIEW_REASON,
  LEGACY_TRACE_REVIEW_REASON,
  MISSING_DEPENDENCY_REVIEW_REASON,
} from "./outboxContract";
import {
  getOutboxMutation,
  listOutboxMutationsRequiringReview,
  listReadyPendingOutboxMutations,
} from "./outboxRepository";

const OBRA_ID = "00000000-0000-4000-8000-000000000001";

let databaseName = "";
let ownerId = "";

function legacyMutation(): LegacyOutboxMutationRecord {
  return {
    clientMutationId: "legacy-mutation",
    entidadeTipo: "RDO",
    entidadeId: "legacy-rdo",
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: {
      id: "legacy-rdo",
      observacoes: "Mantém o conteúdo originalmente salvo.",
    },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-07-20T09:00:00.000Z",
    updatedAt: "2026-07-20T09:00:00.000Z",
  };
}

function canonicalMutation(): CanonicalOutboxMutationRecord {
  return {
    contractVersion: 13,
    clientMutationId: "00000000-0000-4000-8000-000000000010",
    entidadeTipo: "RDO",
    entidadeId: "00000000-0000-4000-8000-000000000011",
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: {
      id: "00000000-0000-4000-8000-000000000011",
      observacoes: "Envelope verificável.",
    },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: "2026-07-20T10:00:00.000Z",
    updatedAt: "2026-07-20T10:00:00.000Z",
    transport: "SYNC_PUSH",
    dependsOnMutationIds: [],
    correlationId: "00000000-0000-4000-8000-000000000010",
    fieldPatch: {
      changed: { observacoes: "Envelope verificável." },
      baseValues: {},
    },
    trace: {
      actorId: ownerId,
      deviceId: "00000000-0000-4000-8000-000000000002",
      authorizationScope: [OBRA_ID],
      correlationId: "00000000-0000-4000-8000-000000000010",
      causationId: null,
      ontologyEventId: "00000000-0000-4000-8000-000000000012",
      payloadHash: "1339f281732923cc80f9ef63858591ee04e90efba0c6228110bad50c48f7028a",
    },
    nextAttemptAt: null,
    blockedReason: null,
  };
}

function legacyEvent(
  mutation: LegacyOutboxMutationRecord,
): LegacyOperationalEventRecord {
  return {
    id: "legacy-event",
    clientMutationId: mutation.clientMutationId,
    type: "RDO_CRIADO",
    principalEntity: { tipo: "RDO", id: mutation.entidadeId },
    principalEntityKey: `RDO:${mutation.entidadeId}`,
    relatedEntities: [],
    obraId: OBRA_ID,
    rdoId: mutation.entidadeId,
    colaboradorId: ownerId,
    occurredAt: mutation.criadaNoClienteEm,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: ownerId,
    responsibleUserName: "Operador de campo",
    payload: mutation.payload,
    syncStatus: "PENDING_SYNC",
    schemaVersion: 1,
  };
}

function canonicalMutationWith(
  clientMutationId: string,
  options: Partial<CanonicalOutboxMutationRecord> = {},
): CanonicalOutboxMutationRecord {
  const base = canonicalMutation();

  return {
    ...base,
    ...options,
    clientMutationId,
    correlationId: clientMutationId,
    trace: {
      ...base.trace,
      correlationId: clientMutationId,
      ontologyEventId: clientMutationId,
      ...(options.trace ?? {}),
    },
  };
}

describe("legacy outbox canonical classification", () => {
  beforeEach(async () => {
    ownerId = crypto.randomUUID();
    setSession({
      colaboradorId: ownerId,
      nome: "Operador de campo",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    databaseName = await currentDataDatabaseName();
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) {
      await deleteDB(databaseName);
    }
    clearSession();
  });

  it("preserves unsafe legacy evidence for review and dispatches only a complete envelope", async () => {
    const database = await getCortexDb();
    const legacy = legacyMutation();
    const canonical = canonicalMutation();
    const event = legacyEvent(legacy);
    const legacyPayload = structuredClone(legacy.payload);

    await Promise.all([
      database.put("outbox_mutations", legacy),
      database.put("outbox_mutations", canonical),
      database.put("operational_events", event),
    ]);

    expect(await listReadyPendingOutboxMutations()).toEqual([
      canonical,
    ]);

    const classifiedLegacy = await getOutboxMutation(
      legacy.clientMutationId,
    );
    expect(classifiedLegacy).toMatchObject({
      clientMutationId: legacy.clientMutationId,
      entidadeId: legacy.entidadeId,
      status: "REJECTED",
      payload: legacyPayload,
      blockedReason: LEGACY_TRACE_REVIEW_REASON,
      ultimoErro: LEGACY_TRACE_REVIEW_REASON,
    });
    expect(classifiedLegacy).not.toHaveProperty("trace");
    expect(classifiedLegacy).not.toHaveProperty("correlationId");
    expect(await listOutboxMutationsRequiringReview()).toEqual([
      classifiedLegacy,
    ]);
    expect(await database.get("operational_events", event.id)).toMatchObject({
      clientMutationId: legacy.clientMutationId,
      syncStatus: "SYNC_FAILED",
      result: "REJECTED",
      errorCategory: "NONCANONICAL_ENVELOPE",
    });
    expect(await getOutboxMutation(canonical.clientMutationId)).toEqual(
      canonical,
    );

    const firstClassification = await getOutboxMutation(
      legacy.clientMutationId,
    );
    await listReadyPendingOutboxMutations();
    expect(await getOutboxMutation(legacy.clientMutationId)).toEqual(
      firstClassification,
    );
  });

  it("propagates a legacy block through local dependencies without leaving the RDO pending", async () => {
    const database = await getCortexDb();
    const legacy = legacyMutation();
    const dependent: CanonicalOutboxMutationRecord = {
      ...canonicalMutation(),
      clientMutationId: "dependent-mutation",
      entidadeId: legacy.entidadeId,
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      baseVersao: 1,
      dependsOnMutationIds: [legacy.clientMutationId],
      criadaNoClienteEm: "2026-07-20T10:01:00.000Z",
      updatedAt: "2026-07-20T10:01:00.000Z",
    };
    const rdo: LocalRdoRecord = {
      id: legacy.entidadeId,
      obraId: OBRA_ID,
      programacaoId: null,
      numeroRdo: "RDO-LEGACY",
      dataRdo: "2026-07-20",
      statusRdo: "RASCUNHO",
      syncStatus: "PENDING_SYNC",
      versaoEntidade: 1,
      payload: legacy.payload,
      createdAt: legacy.criadaNoClienteEm,
      updatedAt: legacy.updatedAt,
    };
    const event = legacyEvent(legacy);
    delete event.clientMutationId;

    await Promise.all([
      database.put("outbox_mutations", legacy),
      database.put("outbox_mutations", dependent),
      database.put("rdos", rdo),
      database.put("operational_events", event),
    ]);

    expect(await listReadyPendingOutboxMutations()).toEqual([]);
    expect(await getOutboxMutation(legacy.clientMutationId)).toMatchObject({
      status: "REJECTED",
      blockedReason: LEGACY_TRACE_REVIEW_REASON,
    });
    expect(await getOutboxMutation(dependent.clientMutationId)).toMatchObject({
      status: "REJECTED",
      blockedReason: BLOCKED_DEPENDENCY_REVIEW_REASON,
    });
    expect(await database.get("rdos", rdo.id)).toMatchObject({
      syncStatus: "ERROR",
    });
    expect(await database.get("operational_events", event.id)).toMatchObject({
      syncStatus: "SYNC_FAILED",
      result: "REJECTED",
      errorCategory: "NONCANONICAL_ENVELOPE",
    });
    expect(await listOutboxMutationsRequiringReview()).toHaveLength(2);
  });

  it("keeps a terminal legacy outcome literal while documenting its missing trace", async () => {
    const database = await getCortexDb();
    const legacy: LegacyOutboxMutationRecord = {
      ...legacyMutation(),
      status: "SYNCED",
      ultimoErro: null,
    };

    await database.put("outbox_mutations", legacy);

    expect(await listReadyPendingOutboxMutations()).toEqual([]);
    expect(await getOutboxMutation(legacy.clientMutationId)).toMatchObject({
      status: "SYNCED",
      payload: legacy.payload,
      blockedReason: LEGACY_TRACE_REVIEW_REASON,
      ultimoErro: null,
    });
  });

  it("keeps a direct object-upload job pending without pretending it is a sync-push envelope", async () => {
    const database = await getCortexDb();
    const upload: LegacyOutboxMutationRecord = {
      ...legacyMutation(),
      clientMutationId: "legacy-object-upload",
      entidadeTipo: "MENSAGEM_ANEXO",
      entidadeId: "attachment-local-1",
      operacao: "ADICIONAR_MENSAGEM_ANEXO",
      payload: {
        attachmentId: "attachment-local-1",
        mensagemId: "message-local-1",
        conversaId: "conversation-local-1",
      },
      transport: "OBJECT_UPLOAD",
      dependsOnMutationIds: [],
    };

    await database.put("outbox_mutations", upload);

    await expect(getOutboxMutation(upload.clientMutationId)).resolves
      .toMatchObject({
        clientMutationId: upload.clientMutationId,
        status: "PENDING",
        transport: "OBJECT_UPLOAD",
      });
    expect(await listOutboxMutationsRequiringReview()).toEqual([]);
  });

  it("turns missing and cyclic dependencies into explicit review outcomes", async () => {
    const database = await getCortexDb();
    const missing = canonicalMutationWith(
      "00000000-0000-4000-8000-000000000020",
      {
        dependsOnMutationIds: [
          "00000000-0000-4000-8000-000000000099",
        ],
      },
    );
    const cycleA = canonicalMutationWith(
      "00000000-0000-4000-8000-000000000021",
      {
        dependsOnMutationIds: [
          "00000000-0000-4000-8000-000000000022",
        ],
      },
    );
    const cycleB = canonicalMutationWith(
      "00000000-0000-4000-8000-000000000022",
      {
        dependsOnMutationIds: [cycleA.clientMutationId],
      },
    );

    await Promise.all([
      database.put("outbox_mutations", missing),
      database.put("outbox_mutations", cycleA),
      database.put("outbox_mutations", cycleB),
    ]);

    expect(await listReadyPendingOutboxMutations()).toEqual([]);
    expect(await getOutboxMutation(missing.clientMutationId)).toMatchObject({
      status: "REJECTED",
      blockedReason: MISSING_DEPENDENCY_REVIEW_REASON,
    });
    await expect(getOutboxMutation(cycleA.clientMutationId)).resolves
      .toMatchObject({
        status: "REJECTED",
        blockedReason: CYCLIC_DEPENDENCY_REVIEW_REASON,
      });
    await expect(getOutboxMutation(cycleB.clientMutationId)).resolves
      .toMatchObject({
        status: "REJECTED",
        blockedReason: CYCLIC_DEPENDENCY_REVIEW_REASON,
      });
  });
});
