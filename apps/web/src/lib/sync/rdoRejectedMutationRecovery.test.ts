import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import {
  closeCortexDb,
  getCortexDb,
} from "../db/cortexDb";
import type {
  LocalRdoRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
} from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import { updateSyncState } from "../db/syncStateRepository";
import {
  RdoWorkforceContextUnverifiedError,
  recoverRejectedRdoMutationsForSync,
  rdoDraftFromLocalRecord,
  saveExistingRdoDraftAtomically,
} from "../db/localRdoService";
import {
  commitLocalMutation,
  type LocalMutationDomainWrite,
} from "./localMutationCoordinator";
import { mutationPayloadHash } from "./mutationEnvelope";
import { selectReadyOutboxMutations } from "./outboxDependencies";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
} from "./syncStorage";
import { captureOnlineSyncSession } from "./syncSession";

const OBRA_ID = "00000000-0000-4000-8000-000000000101";
const DEVICE_ID = "00000000-0000-4000-8000-000000000102";
const RDO_ID = "00000000-0000-4000-8000-000000000103";
const ORIGINAL_ID = "00000000-0000-4000-8000-000000000104";
const ORIGINAL_EVENT_ID = "00000000-0000-4000-8000-000000000105";
const REPLACEMENT_ID = "00000000-0000-4000-8000-000000000106";
const REPLACEMENT_EVENT_ID = "00000000-0000-4000-8000-000000000107";
const PENDING_AUDIT_EVENT_ID =
  "00000000-0000-4000-8000-000000000130";
const PENDING_WORKFORCE_EVENT_ID =
  "00000000-0000-4000-8000-000000000131";
const PENDING_REMOVED_ALLOCATION_EVENT_ID =
  "00000000-0000-4000-8000-000000000132";
const CORRECTED_WORKFORCE_EVENT_ID =
  "00000000-0000-4000-8000-000000000133";
const DEPENDENT_ID = "00000000-0000-4000-8000-000000000108";
const VALID_WORKER_ID = "00000000-0000-4000-8000-000000000109";
const INVALID_WORKER_ID = "00000000-0000-4000-8000-000000000110";
const DEPENDENT_RDO_ID = "00000000-0000-4000-8000-000000000120";
const DEPENDENT_MUTATION_ID = "00000000-0000-4000-8000-000000000121";
const DEPENDENT_EVENT_ID = "00000000-0000-4000-8000-000000000122";
const CHAIN_RDO_ID = "00000000-0000-4000-8000-000000000123";
const CHAIN_MUTATION_ID = "00000000-0000-4000-8000-000000000124";
const CHAIN_EVENT_ID = "00000000-0000-4000-8000-000000000125";
const DEPENDENT_REPLACEMENT_ID = "00000000-0000-4000-8000-000000000126";
const DEPENDENT_REPLACEMENT_EVENT_ID =
  "00000000-0000-4000-8000-000000000127";
const CHAIN_REPLACEMENT_ID = "00000000-0000-4000-8000-000000000128";
const CHAIN_REPLACEMENT_EVENT_ID =
  "00000000-0000-4000-8000-000000000129";
const OCCURRED_AT = "2026-07-28T12:00:00.000Z";
const RECOVERED_AT = "2026-07-28T12:05:00.000Z";

let databaseName = "";
let userId = "";
const missingAuthoritativeRdo = async () =>
  ({ kind: "MISSING" as const });

function workforcePayload(): Record<string, unknown> {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    dataRdo: "2026-07-28",
    numeroRdo: "RDO-0002",
    apontadorColaboradorId: INVALID_WORKER_ID,
    apontadorRdo: "Trabalhador nominal",
    alocacoesColaboradores: [
      {
        id: "00000000-0000-4000-8000-000000000113",
        colaboradorId: VALID_WORKER_ID,
        equipe: "Equipe A",
      },
    ],
    maoObra: [
      {
        id: "00000000-0000-4000-8000-000000000111",
        colaboradorId: VALID_WORKER_ID,
        nomeColaborador: "Colaborador válido",
        cargo: "Operador",
      },
      {
        id: "00000000-0000-4000-8000-000000000112",
        colaboradorId: INVALID_WORKER_ID,
        nomeColaborador: "Trabalhador nominal",
        cargo: "Servente",
        origemItemId: "00000000-0000-4000-8000-000000000115",
        sourceRdoId: "00000000-0000-4000-8000-000000000116",
        origin: "PREVIOUS_RDO",
      },
    ],
  };
}

function localRdo(payload: Record<string, unknown>): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0002",
    dataRdo: "2026-07-28",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload,
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function pendingOperationalEvent(input: {
  id: string;
  type: OperationalEventRecord["type"];
  colaboradorId?: string | null;
  principalEntity?: OperationalEventRecord["principalEntity"];
  payload?: Record<string, unknown>;
}): OperationalEventRecord {
  const principalEntity = input.principalEntity ?? {
    tipo: "RDO",
    id: RDO_ID,
    nome: "RDO-0002",
  };
  return {
    id: input.id,
    type: input.type,
    principalEntity,
    principalEntityKey:
      `${principalEntity.tipo}:${principalEntity.id}`,
    relatedEntities: [
      { tipo: "RDO", id: RDO_ID, nome: "RDO-0002" },
      { tipo: "OBRA", id: OBRA_ID, nome: "Obra autorizada" },
    ],
    obraId: OBRA_ID,
    rdoId: RDO_ID,
    colaboradorId: input.colaboradorId ?? null,
    occurredAt: OCCURRED_AT,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: userId,
    responsibleUserName: "Encarregado",
    payload: input.payload ?? { evidence: input.id },
    syncStatus: "PENDING_SYNC",
    schemaVersion: 1,
  };
}

function serializedOperationalEvent(
  event: OperationalEventRecord,
): Record<string, unknown> {
  return {
    id: event.id,
    type: event.type,
    principalEntity: event.principalEntity,
    relatedEntities: event.relatedEntities,
    obraId: event.obraId,
    rdoId: event.rdoId,
    colaboradorId: event.colaboradorId,
    occurredAt: event.occurredAt,
    syncedAt: event.syncedAt,
    origin: event.origin,
    responsibleUserId: event.responsibleUserId,
    responsibleUserName: event.responsibleUserName,
    payload: event.payload,
    syncStatus: event.syncStatus,
    schemaVersion: event.schemaVersion,
  };
}

async function seedCanonicalCreate(
  payload: Record<string, unknown>,
  dependsOnMutationIds: readonly string[] = [],
  causationId: string | null = null,
): Promise<void> {
  const rdo = localRdo(payload);
  const writes: readonly LocalMutationDomainWrite<"rdos">[] = [
    { store: "rdos", value: rdo, principal: true },
  ];
  await commitLocalMutation({
    clientMutationId: ORIGINAL_ID,
    ontologyEventId: ORIGINAL_EVENT_ID,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: RDO_ID,
    entityName: "RDO-0002",
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: OCCURRED_AT,
    previousSnapshot: {},
    nextSnapshot: payload,
    principalSnapshot: rdo,
    eventType: "RDO_CRIADO",
    dependsOnMutationIds,
    causationId,
    write: () => writes,
  });
}

async function seedCanonicalUpdate(
  payload: Record<string, unknown>,
  previousPayload: Record<string, unknown> = {
    ...workforcePayload(),
    observacoes: "estado sincronizado",
  },
  causationId: string | null = null,
): Promise<void> {
  const rdo = {
    ...localRdo(payload),
    versaoEntidade: 7,
  };
  const writes: readonly LocalMutationDomainWrite<"rdos">[] = [
    { store: "rdos", value: rdo, principal: true },
  ];
  await commitLocalMutation({
    clientMutationId: ORIGINAL_ID,
    ontologyEventId: ORIGINAL_EVENT_ID,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: RDO_ID,
    entityName: "RDO-0002",
    operation: "UPDATE",
    transportOperation: "ATUALIZAR_RDO_RASCUNHO",
    baseVersion: 7,
    occurredAt: OCCURRED_AT,
    previousSnapshot: previousPayload,
    nextSnapshot: payload,
    principalSnapshot: rdo,
    eventType: "RDO_EDITADO",
    causationId,
    write: () => writes,
  });
}

async function seedSyncedCanonicalHistory(
  payload: Record<string, unknown>,
  operation: "CREATE" | "TRANSITION" = "CREATE",
): Promise<void> {
  const rdo = {
    ...localRdo(payload),
    versaoEntidade: 7,
    syncStatus: "SYNCED" as const,
  };
  const writes: readonly LocalMutationDomainWrite<"rdos">[] = [
    { store: "rdos", value: rdo, principal: true },
  ];
  await commitLocalMutation({
    clientMutationId: DEPENDENT_MUTATION_ID,
    ontologyEventId: DEPENDENT_EVENT_ID,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: RDO_ID,
    entityName: "RDO-0002",
    operation,
    transportOperation:
      operation === "CREATE" ? "CRIAR_RDO" : "ENVIAR_RDO",
    baseVersion: operation === "CREATE" ? null : 7,
    occurredAt: "2026-07-28T11:00:00.000Z",
    previousSnapshot: operation === "CREATE" ? {} : payload,
    nextSnapshot: payload,
    principalSnapshot: rdo,
    eventType:
      operation === "CREATE" ? "RDO_CRIADO" : "RDO_SINCRONIZADO",
    write: () => writes,
  });
  const database = await getCortexDb();
  const mutation = await database.get(
    "outbox_mutations",
    DEPENDENT_MUTATION_ID,
  );
  const event = await database.get(
    "operational_events",
    DEPENDENT_EVENT_ID,
  );
  await database.put("outbox_mutations", {
    ...mutation!,
    status: "SYNCED",
  });
  await database.put("operational_events", {
    ...event!,
    result: "SYNCED",
    syncStatus: "SYNCED",
    entityVersion: 7,
  });
}

async function seedCanonicalDependent(input: {
  rdoId: string;
  mutationId: string;
  eventId: string;
  previousRdoId: string;
  dependsOnMutationId: string;
  numeroRdo: string;
}): Promise<void> {
  const payload = {
    id: input.rdoId,
    obraId: OBRA_ID,
    dataRdo: "2026-07-29",
    numeroRdo: input.numeroRdo,
    previousRdoId: input.previousRdoId,
    maoObra: [],
  };
  const rdo: LocalRdoRecord = {
    ...localRdo(payload),
    id: input.rdoId,
    numeroRdo: input.numeroRdo,
    dataRdo: "2026-07-29",
  };
  const writes: readonly LocalMutationDomainWrite<"rdos">[] = [
    { store: "rdos", value: rdo, principal: true },
  ];
  await commitLocalMutation({
    clientMutationId: input.mutationId,
    ontologyEventId: input.eventId,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: input.rdoId,
    entityName: input.numeroRdo,
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: OCCURRED_AT,
    previousSnapshot: {},
    nextSnapshot: payload,
    principalSnapshot: rdo,
    eventType: "RDO_CRIADO",
    dependsOnMutationIds: [input.dependsOnMutationId],
    write: () => writes,
  });
}

async function seedStandaloneCanonicalDependency(): Promise<void> {
  const payload = {
    id: DEPENDENT_RDO_ID,
    obraId: OBRA_ID,
    dataRdo: "2026-07-27",
    numeroRdo: "RDO-0001",
    maoObra: [],
  };
  const rdo: LocalRdoRecord = {
    ...localRdo(payload),
    id: DEPENDENT_RDO_ID,
    numeroRdo: "RDO-0001",
    dataRdo: "2026-07-27",
  };
  const writes: readonly LocalMutationDomainWrite<"rdos">[] = [
    { store: "rdos", value: rdo, principal: true },
  ];
  await commitLocalMutation({
    clientMutationId: DEPENDENT_MUTATION_ID,
    ontologyEventId: DEPENDENT_EVENT_ID,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: DEPENDENT_RDO_ID,
    entityName: "RDO-0001",
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: "2026-07-27T12:00:00.000Z",
    previousSnapshot: {},
    nextSnapshot: payload,
    principalSnapshot: rdo,
    eventType: "RDO_CRIADO",
    write: () => writes,
  });
}

async function rejectOriginal(
  safeCode: string,
  message: string,
): Promise<void> {
  await rejectCanonicalMutation({
    mutationId: ORIGINAL_ID,
    eventId: ORIGINAL_EVENT_ID,
    rdoId: RDO_ID,
    safeCode,
    message,
  });
}

async function rejectCanonicalMutation(input: {
  mutationId: string;
  eventId: string;
  rdoId: string;
  safeCode: string;
  message: string;
}): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["outbox_mutations", "operational_events", "rdos"],
    "readwrite",
  );
  const outbox = transaction.objectStore("outbox_mutations");
  const events = transaction.objectStore("operational_events");
  const rdos = transaction.objectStore("rdos");
  const mutation = await outbox.get(input.mutationId);
  const event = await events.get(input.eventId);
  const rdo = await rdos.get(input.rdoId);
  await outbox.put({
    ...mutation!,
    status: "REJECTED",
    lastSafeCode: input.safeCode,
    ultimoErro: input.message,
    updatedAt: RECOVERED_AT,
  });
  await events.put({
    ...event!,
    result: "REJECTED",
    syncStatus: "SYNC_FAILED",
    errorCategory: input.safeCode,
  });
  await rdos.put({
    ...rdo!,
    syncStatus: "ERROR",
    updatedAt: RECOVERED_AT,
  });
  await transaction.done;
}

async function seedDuplicateCanonicalCreate(): Promise<void> {
  const payload = {
    ...workforcePayload(),
    observacoes: "versão local mais recente",
  };
  const rdo = localRdo(payload);
  const writes: readonly LocalMutationDomainWrite<"rdos">[] = [
    { store: "rdos", value: rdo, principal: true },
  ];
  await commitLocalMutation({
    clientMutationId: DEPENDENT_MUTATION_ID,
    ontologyEventId: DEPENDENT_EVENT_ID,
    deviceId: DEVICE_ID,
    userId,
    obraId: OBRA_ID,
    entityType: "RDO",
    entityId: RDO_ID,
    entityName: "RDO-0002",
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: "2026-07-28T12:02:00.000Z",
    previousSnapshot: {},
    nextSnapshot: payload,
    principalSnapshot: rdo,
    eventType: "RDO_CRIADO",
    write: () => writes,
  });
}

function legacyDependent(): OutboxMutationRecord {
  return {
    clientMutationId: DEPENDENT_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: 0,
    payload: { id: RDO_ID, observacoes: "edição posterior" },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    dependsOnMutationIds: [ORIGINAL_ID],
    criadaNoClienteEm: "2026-07-28T12:01:00.000Z",
    updatedAt: "2026-07-28T12:01:00.000Z",
  };
}

function immutableMutationEnvelope(
  mutation: OutboxMutationRecord,
): Record<string, unknown> {
  const envelope: Record<string, unknown> = { ...mutation };
  for (const key of [
    "status",
    "tentativas",
    "ultimaTentativaEm",
    "ultimoErro",
    "conflito",
    "nextAttemptAt",
    "lastSafeCode",
    "blockedReason",
    "updatedAt",
  ]) {
    delete envelope[key];
  }
  return envelope;
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession({
    colaboradorId: userId,
    nome: "Operador",
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
  vi.restoreAllMocks();
});

describe("reparo canônico de RDO rejeitado", () => {
  it("recupera UPDATE rejeitado por vínculo no mesmo contrato canônico", async () => {
    const payload = workforcePayload();
    await seedCanonicalUpdate(payload);
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );
    const database = await getCortexDb();
    const original = await database.get("outbox_mutations", ORIGINAL_ID);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => RECOVERED_AT,
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          loadAuthorizedCollaboratorIds: async () =>
            new Set([VALID_WORKER_ID]),
        },
      ),
    ).toBe(1);

    const replacement = await database.get(
      "outbox_mutations",
      REPLACEMENT_ID,
    );
    expect(replacement).toMatchObject({
      status: "PENDING",
      operation: "UPDATE",
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      baseVersion: 7,
      causationId: ORIGINAL_ID,
      payload: {
        apontadorColaboradorId: null,
        alocacoesColaboradores: [
          expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
        ],
        maoObra: [
          expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
          expect.objectContaining({
            colaboradorId: null,
            nomeColaborador: "Trabalhador nominal",
          }),
        ],
      },
    });
    expect(replacement!.trace.payloadHash)
      .not.toBe(original!.trace.payloadHash);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "SUPERSEDED_BY_WORKFORCE_RECOVERY",
        blockedReason: `SUPERSEDED_BY:${REPLACEMENT_ID}`,
      });
  });

  it("carrega e confirma os eventos pendentes no UPDATE substituto após reparar a mão de obra", async () => {
    const auditEvent = pendingOperationalEvent({
      id: PENDING_AUDIT_EVENT_ID,
      type: "RDO_SALVO_OFFLINE",
    });
    const workforceEvent = pendingOperationalEvent({
      id: PENDING_WORKFORCE_EVENT_ID,
      type: "COLABORADOR_ASSOCIADO_RDO",
      colaboradorId: INVALID_WORKER_ID,
      principalEntity: {
        tipo: "COLABORADOR",
        id: INVALID_WORKER_ID,
        nome: "Trabalhador nominal",
      },
      payload: {
        localId: "00000000-0000-4000-8000-000000000112",
        nomeColaborador: "Trabalhador nominal",
        cargo: "Servente",
      },
    });
    const payload = {
      ...workforcePayload(),
      operationalEvents: [
        serializedOperationalEvent(auditEvent),
        serializedOperationalEvent(workforceEvent),
      ],
    };
    const database = await getCortexDb();
    await database.put("operational_events", auditEvent);
    await database.put("operational_events", workforceEvent);
    await seedCanonicalUpdate(payload);
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );
    const recoveredReplacementIds = new Set<string>();
    const recoveredReplacementByOriginalId =
      new Map<string, string>();

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => RECOVERED_AT,
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          correctiveOperationalEventIdFactory: () =>
            CORRECTED_WORKFORCE_EVENT_ID,
          loadAuthorizedCollaboratorIds: async () =>
            new Set([VALID_WORKER_ID]),
          recoveredReplacementIds,
          recoveredReplacementByOriginalId,
        },
      ),
    ).toBe(1);
    expect(recoveredReplacementIds).toEqual(new Set([REPLACEMENT_ID]));
    expect(recoveredReplacementByOriginalId).toEqual(
      new Map([[ORIGINAL_ID, REPLACEMENT_ID]]),
    );

    const replacement = await database.get(
      "outbox_mutations",
      REPLACEMENT_ID,
    );
    expect(replacement?.payload.operationalEvents).toEqual([
      expect.objectContaining({
        id: PENDING_AUDIT_EVENT_ID,
        payload: { evidence: PENDING_AUDIT_EVENT_ID },
      }),
      expect.objectContaining({
        id: CORRECTED_WORKFORCE_EVENT_ID,
        colaboradorId: null,
        principalEntity: expect.objectContaining({
          tipo: "RDO_MAO_OBRA",
          id: "00000000-0000-4000-8000-000000000112",
          nome: "Trabalhador nominal",
        }),
        payload: expect.objectContaining({
          localId: "00000000-0000-4000-8000-000000000112",
          nomeColaborador: "Trabalhador nominal",
          supersedesEventId: PENDING_WORKFORCE_EVENT_ID,
          causationId: PENDING_WORKFORCE_EVENT_ID,
          recoveryReason: "WORKFORCE_LINK_RECOVERED_AS_NOMINAL",
        }),
      }),
    ]);
    expect(
      (
        replacement?.payload.operationalEvents as
          Record<string, unknown>[]
      )[1],
    ).not.toHaveProperty("causationId");
    expect(
      await database.get(
        "operational_events",
        PENDING_WORKFORCE_EVENT_ID,
      ),
    ).toEqual({
      ...workforceEvent,
      syncStatus: "SYNC_FAILED",
      syncedAt: null,
    });
    const localCorrectiveEvent = await database.get(
      "operational_events",
      CORRECTED_WORKFORCE_EVENT_ID,
    );
    expect(localCorrectiveEvent).toMatchObject({
      id: CORRECTED_WORKFORCE_EVENT_ID,
      type: "COLABORADOR_ASSOCIADO_RDO",
      colaboradorId: null,
      principalEntity: {
        tipo: "RDO_MAO_OBRA",
        id: "00000000-0000-4000-8000-000000000112",
        nome: "Trabalhador nominal",
      },
      principalEntityKey:
        "RDO_MAO_OBRA:00000000-0000-4000-8000-000000000112",
      payload: expect.objectContaining({
        localId: "00000000-0000-4000-8000-000000000112",
        nomeColaborador: "Trabalhador nominal",
        supersedesEventId: PENDING_WORKFORCE_EVENT_ID,
        causationId: PENDING_WORKFORCE_EVENT_ID,
        recoveryReason: "WORKFORCE_LINK_RECOVERED_AS_NOMINAL",
      }),
      causationId: PENDING_WORKFORCE_EVENT_ID,
      occurredAt: RECOVERED_AT,
      syncStatus: "PENDING_SYNC",
      syncedAt: null,
    });
    expect(serializedOperationalEvent(localCorrectiveEvent!)).toEqual(
      (
        replacement?.payload.operationalEvents as
          Record<string, unknown>[]
      )[1],
    );
    await markMutationAsSyncing(replacement!);
    await applyPushResultAtomically({
      clientMutationId: REPLACEMENT_ID,
      status: "APLICADA",
      resultado: { versaoEntidade: 8 },
    });

    expect(
      await database.get("operational_events", PENDING_AUDIT_EVENT_ID),
    ).toMatchObject({
      syncStatus: "SYNCED",
      syncedAt: expect.any(String),
    });
    expect(
      await database.get(
        "operational_events",
        PENDING_WORKFORCE_EVENT_ID,
      ),
    ).toEqual({
      ...workforceEvent,
      syncStatus: "SYNC_FAILED",
      syncedAt: null,
    });
    expect(
      await database.get(
        "operational_events",
        CORRECTED_WORKFORCE_EVENT_ID,
      ),
    ).toMatchObject({
      syncStatus: "SYNCED",
      syncedAt: expect.any(String),
    });
  });

  it.each([
    {
      scenario: "associação com localId divergente",
      type: "COLABORADOR_ASSOCIADO_RDO" as const,
      localId: "00000000-0000-4000-8000-000000000199",
    },
    {
      scenario: "outro tipo de evento",
      type: "RDO_SALVO_OFFLINE" as const,
      localId: "00000000-0000-4000-8000-000000000112",
    },
  ])(
    "terminaliza $scenario sem inventar correção operacional",
    async ({ type, localId }) => {
      const unmatchedAssociationEvent = pendingOperationalEvent({
        id: PENDING_REMOVED_ALLOCATION_EVENT_ID,
        type,
        colaboradorId: INVALID_WORKER_ID,
        principalEntity: {
          tipo: "COLABORADOR",
          id: INVALID_WORKER_ID,
          nome: "Equipe A",
        },
        payload: {
          localId,
          nomeColaborador: "Equipe A",
          equipe: "Equipe A",
        },
      });
      const payload = {
        ...workforcePayload(),
        operationalEvents: [
          serializedOperationalEvent(unmatchedAssociationEvent),
        ],
      };
      const database = await getCortexDb();
      await database.put(
        "operational_events",
        unmatchedAssociationEvent,
      );
      await seedCanonicalUpdate(payload);
      await rejectOriginal(
        "VALIDATION_OR_AUTHORIZATION",
        "Colaborador não está ativo e vinculado à obra do RDO.",
      );

      expect(
        await recoverRejectedRdoMutationsForSync(
          captureOnlineSyncSession(),
          {
            now: () => RECOVERED_AT,
            clientMutationIdFactory: () => REPLACEMENT_ID,
            ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
            correctiveOperationalEventIdFactory: () =>
              CORRECTED_WORKFORCE_EVENT_ID,
            loadAuthorizedCollaboratorIds: async () =>
              new Set([VALID_WORKER_ID]),
          },
        ),
      ).toBe(0);

      expect(
        await database.get("outbox_mutations", ORIGINAL_ID),
      ).toMatchObject({
        status: "REJECTED",
        lastSafeCode:
          "WORKFORCE_OPERATIONAL_EVENT_RECOVERY_REQUIRES_REVIEW",
      });
      expect(
        await database.get(
          "operational_events",
          PENDING_REMOVED_ALLOCATION_EVENT_ID,
        ),
      ).toEqual(unmatchedAssociationEvent);
      expect(
        await database.get("outbox_mutations", REPLACEMENT_ID),
      ).toBeUndefined();
      expect(
        await database.get(
          "operational_events",
          CORRECTED_WORKFORCE_EVENT_ID,
        ),
      ).toBeUndefined();
    },
  );

  it.each([
    { scenario: "evento local já sincronizado", kind: "SYNCED" },
    { scenario: "evento local fora do schema-v1", kind: "SCHEMA_2" },
  ] as const)(
    "terminaliza $scenario antes de criar o corretivo",
    async ({ kind }) => {
      const sourceEvent = pendingOperationalEvent({
        id: PENDING_WORKFORCE_EVENT_ID,
        type: "COLABORADOR_ASSOCIADO_RDO",
        colaboradorId: INVALID_WORKER_ID,
        principalEntity: {
          tipo: "COLABORADOR",
          id: INVALID_WORKER_ID,
          nome: "Trabalhador nominal",
        },
        payload: {
          localId: "00000000-0000-4000-8000-000000000112",
          nomeColaborador: "Trabalhador nominal",
          cargo: "Servente",
        },
      });
      const localEvent: OperationalEventRecord = kind === "SYNCED"
        ? {
            ...sourceEvent,
            syncStatus: "SYNCED",
            syncedAt: OCCURRED_AT,
          }
        : {
            ...sourceEvent,
            schemaVersion: 2,
          };
      const serializedSourceEvent = {
        ...serializedOperationalEvent(sourceEvent),
        ...(kind === "SCHEMA_2" ? { schemaVersion: 2 } : {}),
      };
      const payload = {
        ...workforcePayload(),
        operationalEvents: [serializedSourceEvent],
      };
      const database = await getCortexDb();
      await database.put("operational_events", localEvent);
      await seedCanonicalUpdate(payload);
      await rejectOriginal(
        "VALIDATION_OR_AUTHORIZATION",
        "Colaborador não está ativo e vinculado à obra do RDO.",
      );

      expect(
        await recoverRejectedRdoMutationsForSync(
          captureOnlineSyncSession(),
          {
            now: () => RECOVERED_AT,
            clientMutationIdFactory: () => REPLACEMENT_ID,
            ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
            correctiveOperationalEventIdFactory: () =>
              CORRECTED_WORKFORCE_EVENT_ID,
            loadAuthorizedCollaboratorIds: async () =>
              new Set([VALID_WORKER_ID]),
          },
        ),
      ).toBe(0);

      expect(
        await database.get("operational_events", localEvent.id),
      ).toEqual(localEvent);
      expect(
        await database.get("outbox_mutations", REPLACEMENT_ID),
      ).toBeUndefined();
      expect(
        await database.get(
          "operational_events",
          CORRECTED_WORKFORCE_EVENT_ID,
        ),
      ).toBeUndefined();
      expect(
        await database.get("outbox_mutations", ORIGINAL_ID),
      ).toMatchObject({
        lastSafeCode:
          "WORKFORCE_OPERATIONAL_EVENT_RECOVERY_REQUIRES_REVIEW",
      });
    },
  );

  it("aceita o CREATE canônico SYNCED como ancestral causal do UPDATE", async () => {
    const payload = workforcePayload();
    await seedSyncedCanonicalHistory(payload);
    await seedCanonicalUpdate(
      payload,
      { ...payload, observacoes: "estado sincronizado" },
      DEPENDENT_MUTATION_ID,
    );
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          loadAuthorizedCollaboratorIds: async () =>
            new Set([VALID_WORKER_ID]),
        },
      ),
    ).toBe(1);

    const database = await getCortexDb();
    expect(
      await database.get("outbox_mutations", DEPENDENT_MUTATION_ID),
    ).toMatchObject({ status: "SYNCED" });
    expect(await database.get("outbox_mutations", REPLACEMENT_ID))
      .toMatchObject({
        operation: "UPDATE",
        causationId: ORIGINAL_ID,
      });
  });

  it("não trata transição SYNCED como ancestral seguro do UPDATE", async () => {
    const payload = workforcePayload();
    await seedSyncedCanonicalHistory(payload, "TRANSITION");
    await seedCanonicalUpdate(
      payload,
      { ...payload, observacoes: "estado sincronizado" },
      DEPENDENT_MUTATION_ID,
    );
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          loadAuthorizedCollaboratorIds: async () =>
            new Set([VALID_WORKER_ID]),
        },
      ),
    ).toBe(0);

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "COMPETING_RDO_MUTATION_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", REPLACEMENT_ID))
      .toBeUndefined();
  });

  it("troca uma vez a identidade do UPDATE rejeitado por idempotência", async () => {
    await seedCanonicalUpdate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => RECOVERED_AT,
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          lookupAuthoritativeRdo: lookup,
        },
      ),
    ).toBe(1);
    expect(lookup).not.toHaveBeenCalled();

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", REPLACEMENT_ID))
      .toMatchObject({
        operation: "UPDATE",
        operacao: "ATUALIZAR_RDO_RASCUNHO",
        baseVersion: 7,
        causationId: ORIGINAL_ID,
      });
    const replacement = await database.get(
      "outbox_mutations",
      REPLACEMENT_ID,
    );
    expect(await mutationPayloadHash(replacement!.payload))
      .toBe(replacement!.trace.payloadHash);
    await rejectCanonicalMutation({
      mutationId: REPLACEMENT_ID,
      eventId: REPLACEMENT_EVENT_ID,
      rdoId: RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });
    const nextMutationId = vi.fn(() => DEPENDENT_REPLACEMENT_ID);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { clientMutationIdFactory: nextMutationId },
      ),
    ).toBe(0);
    expect(nextMutationId).not.toHaveBeenCalled();
    expect(await database.get("outbox_mutations", REPLACEMENT_ID))
      .toMatchObject({
        lastSafeCode: "AUTO_RECOVERY_LIMIT_REACHED",
      });
  });

  it("troca a identidade após idempotency mismatch mesmo depois de reabrir o IndexedDB", async () => {
    const payload = workforcePayload();
    await seedCanonicalCreate(payload);
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const originalBefore = await database.get(
      "outbox_mutations",
      ORIGINAL_ID,
    );
    await database.put("outbox_mutations", legacyDependent());

    await closeCortexDb();
    await getCortexDb();

    const repaired = await recoverRejectedRdoMutationsForSync(
      captureOnlineSyncSession(),
      {
        now: () => RECOVERED_AT,
        clientMutationIdFactory: () => REPLACEMENT_ID,
        ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
        loadAuthorizedCollaboratorIds: vi.fn(),
        lookupAuthoritativeRdo: missingAuthoritativeRdo,
      },
    );

    expect(repaired).toBe(1);
    const reopened = await getCortexDb();
    const originalAfter = await reopened.get(
      "outbox_mutations",
      ORIGINAL_ID,
    );
    expect(originalAfter).toMatchObject({
      status: "REJECTED",
      lastSafeCode: "SUPERSEDED_BY_IDEMPOTENCY_RECOVERY",
      blockedReason: `SUPERSEDED_BY:${REPLACEMENT_ID}`,
    });
    expect(immutableMutationEnvelope(originalAfter!)).toEqual(
      immutableMutationEnvelope(originalBefore!),
    );
    expect(await reopened.get("outbox_mutations", REPLACEMENT_ID)).toMatchObject({
      schemaVersion: 13,
      clientMutationId: REPLACEMENT_ID,
      causationId: ORIGINAL_ID,
      correlationId: ORIGINAL_ID,
      payload: expect.objectContaining({
        id: RDO_ID,
        obraId: OBRA_ID,
        maoObra: expect.any(Array),
      }),
      status: "PENDING",
    });
    expect(await reopened.get("operational_events", ORIGINAL_EVENT_ID))
      .toMatchObject({
        result: "REJECTED",
        errorCategory: "SUPERSEDED_BY_IDEMPOTENCY_RECOVERY",
      });
    expect(await reopened.get("operational_events", REPLACEMENT_EVENT_ID))
      .toMatchObject({
        clientMutationId: REPLACEMENT_ID,
        causationId: ORIGINAL_ID,
        newState: expect.objectContaining({
          id: RDO_ID,
          obraId: OBRA_ID,
          maoObra: expect.any(Array),
        }),
        result: "PENDING",
      });
    expect(await reopened.get("outbox_mutations", DEPENDENT_ID)).toMatchObject({
      dependsOnMutationIds: [REPLACEMENT_ID],
    });
    expect(await reopened.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
    });
    expect(
      selectReadyOutboxMutations(
        await reopened.getAll("outbox_mutations"),
        10,
      ).map((mutation) => mutation.clientMutationId),
    ).toEqual([REPLACEMENT_ID]);

    await closeCortexDb();
    await getCortexDb();
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => RECOVERED_AT,
          clientMutationIdFactory: () => crypto.randomUUID(),
          ontologyEventIdFactory: () => crypto.randomUUID(),
          loadAuthorizedCollaboratorIds: vi.fn(),
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
        },
      ),
    ).toBe(0);
  });

  it("reabre filhos e anexos falhos na substituição por RDO ausente", async () => {
    const payload = workforcePayload();
    await seedCanonicalCreate(payload);
    const database = await getCortexDb();
    const worker = (
      payload.maoObra as Array<Record<string, unknown>>
    )[0];
    await database.put("rdoMaoObra", {
      id: `${RDO_ID}:maoObra:${worker.id}`,
      rdoId: RDO_ID,
      localId: worker.id as string,
      syncStatus: "ERROR",
      payload: worker,
      createdAt: OCCURRED_AT,
      updatedAt: RECOVERED_AT,
    });
    const failedAttachmentId = crypto.randomUUID();
    const syncedAttachmentId = crypto.randomUUID();
    const attachment = {
      rdoId: RDO_ID,
      obraId: OBRA_ID,
      tipo: "FOTO" as const,
      nome: "evidencia.jpg",
      nomeOriginal: "evidencia.jpg",
      mimeType: "image/jpeg",
      tamanhoBytes: 1,
      tamanhoOriginalBytes: 1,
      tamanhoComprimidoBytes: 1,
      arquivo: new Blob(["x"], { type: "image/jpeg" }),
      ultimoErro: "falha anterior",
      metadata: {},
      createdAt: OCCURRED_AT,
      updatedAt: RECOVERED_AT,
      removedAt: null,
    };
    await database.put("rdo_attachments", {
      ...attachment,
      id: failedAttachmentId,
      syncStatus: "SYNC_FAILED",
    });
    await database.put("rdo_attachments", {
      ...attachment,
      id: syncedAttachmentId,
      syncStatus: "SYNCED",
      ultimoErro: null,
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => RECOVERED_AT,
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
        },
      ),
    ).toBe(1);
    expect(
      await database.getAllFromIndex(
        "rdoMaoObra",
        "by-rdo-id",
        RDO_ID,
      ),
    ).toEqual([
      expect.objectContaining({ syncStatus: "PENDING_SYNC" }),
      expect.objectContaining({ syncStatus: "PENDING_SYNC" }),
    ]);
    expect(
      await database.get("rdo_attachments", failedAttachmentId),
    ).toMatchObject({
      syncStatus: "PENDING_SYNC",
      ultimoErro: null,
      updatedAt: RECOVERED_AT,
    });
    expect(
      await database.get("rdo_attachments", syncedAttachmentId),
    ).toMatchObject({
      syncStatus: "SYNCED",
      ultimoErro: null,
    });
  });

  it("desvincula somente o colaborador inválido e preserva a pessoa por nome", async () => {
    const payload = workforcePayload();
    await seedCanonicalCreate(payload);
    const database = await getCortexDb();
    for (const worker of payload.maoObra as Array<Record<string, unknown>>) {
      await database.put("rdoMaoObra", {
        id: `${RDO_ID}:maoObra:${worker.id}`,
        rdoId: RDO_ID,
        localId: worker.id as string,
        syncStatus: "ERROR",
        payload: worker,
        createdAt: OCCURRED_AT,
        updatedAt: RECOVERED_AT,
      });
    }
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );

    await closeCortexDb();
    await getCortexDb();

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => RECOVERED_AT,
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          loadAuthorizedCollaboratorIds: async () =>
            new Set([VALID_WORKER_ID]),
        },
      ),
    ).toBe(1);

    const reopened = await getCortexDb();
    const replacement = await reopened.get(
      "outbox_mutations",
      REPLACEMENT_ID,
    );
    expect(replacement?.payload.maoObra).toEqual([
      expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
      expect.objectContaining({
        colaboradorId: null,
        nomeColaborador: "Trabalhador nominal",
        origemItemId: null,
      }),
    ]);
    expect(replacement?.payload).toMatchObject({
      apontadorColaboradorId: null,
      apontadorRdo: "Trabalhador nominal",
      alocacoesColaboradores: [
        expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
      ],
    });
    const recoveredRdo = await reopened.get("rdos", RDO_ID);
    expect(recoveredRdo?.payload.maoObra).toEqual([
      expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
      expect.objectContaining({
        colaboradorId: "",
        nomeColaborador: "Trabalhador nominal",
        origemItemId: "",
        sourceRdoId: "",
        origin: "MANUAL",
        availability: "UNKNOWN",
      }),
    ]);
    expect(recoveredRdo?.payload).toMatchObject({
      apontadorColaboradorId: "",
      apontadorRdo: "Trabalhador nominal",
      alocacoesColaboradores: [
        expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
      ],
    });
    const children = await reopened.getAllFromIndex(
      "rdoMaoObra",
      "by-rdo-id",
      RDO_ID,
    );
    expect(children.map((child) => child.payload)).toEqual([
      expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
      expect.objectContaining({
        colaboradorId: "",
        origemItemId: "",
        sourceRdoId: "",
        origin: "MANUAL",
        availability: "UNKNOWN",
      }),
    ]);
  });

  it("preserva integralmente e bloqueia alocação estruturada sem vínculo para reatribuição explícita", async () => {
    const invalidAllocation = {
      id: "00000000-0000-4000-8000-000000000114",
      colaboradorId: INVALID_WORKER_ID,
      equipe: "Equipe de reparo",
      servicoNome: "Fresagem",
      horaInicio: "07:15",
      horaFim: "16:45",
      percentualDia: 62.5,
      turno: "DIURNO",
      funcao: "Operador",
      centroCusto: "CC-401",
      tipoAlocacao: "TRABALHO",
      fonte: "RDO_OFFLINE",
      status: "REGISTRADA",
      observacoes: "Não descartar esta alocação.",
    };
    const payload = {
      ...workforcePayload(),
      apontadorColaboradorId: VALID_WORKER_ID,
      apontadorRdo: "Colaborador válido",
      alocacoesColaboradores: [
        ...(workforcePayload().alocacoesColaboradores as
          Array<Record<string, unknown>>),
        invalidAllocation,
      ],
      maoObra: [
        {
          id: "00000000-0000-4000-8000-000000000111",
          colaboradorId: VALID_WORKER_ID,
          nomeColaborador: "Colaborador válido",
          cargo: "Operador",
        },
      ],
    };
    await seedCanonicalCreate(payload);
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );

    await closeCortexDb();

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          loadAuthorizedCollaboratorIds: async () =>
            new Set([VALID_WORKER_ID]),
        },
      ),
    ).toBe(0);

    const database = await getCortexDb();
    expect(await database.get(
      "outbox_mutations",
      REPLACEMENT_ID,
    )).toBeUndefined();
    expect(await database.get(
      "outbox_mutations",
      ORIGINAL_ID,
    )).toMatchObject({
      status: "REJECTED",
      lastSafeCode:
        "WORKFORCE_ALLOCATION_REASSIGNMENT_REQUIRED",
      blockedReason:
        "WORKFORCE_ALLOCATION_REASSIGNMENT_REQUIRED",
      payload: {
        alocacoesColaboradores: [
          expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
          invalidAllocation,
        ],
      },
    });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "ERROR",
      payload: {
        alocacoesColaboradores: [
          expect.objectContaining({ colaboradorId: VALID_WORKER_ID }),
          invalidAllocation,
        ],
      },
    });
    expect(
      await database.get("operational_events", ORIGINAL_EVENT_ID),
    ).toMatchObject({
      result: "REJECTED",
      syncStatus: "SYNC_FAILED",
      errorCategory:
        "WORKFORCE_ALLOCATION_REASSIGNMENT_REQUIRED",
    });
  });

  it("gera receipt novo uma vez quando todos os vínculos seguem autorizados", async () => {
    const payload = workforcePayload();
    await seedCanonicalCreate(payload);
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );
    const loadAuthorizedCollaboratorIds = vi.fn(async () =>
      new Set([VALID_WORKER_ID, INVALID_WORKER_ID])
    );

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          loadAuthorizedCollaboratorIds,
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
        },
      ),
    ).toBe(1);
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { loadAuthorizedCollaboratorIds },
      ),
    ).toBe(0);
    expect(loadAuthorizedCollaboratorIds).toHaveBeenCalledTimes(1);
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID)).toMatchObject({
      status: "REJECTED",
      lastSafeCode: "SUPERSEDED_BY_WORKFORCE_RECOVERY",
    });
    expect(await database.get("outbox_mutations", REPLACEMENT_ID))
      .toMatchObject({
        status: "PENDING",
        causationId: ORIGINAL_ID,
      });
  });

  it("substitui dependentes canônicos em cadeia com dependências literais novas após reopen", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await seedCanonicalDependent({
      rdoId: CHAIN_RDO_ID,
      mutationId: CHAIN_MUTATION_ID,
      eventId: CHAIN_EVENT_ID,
      previousRdoId: DEPENDENT_RDO_ID,
      dependsOnMutationId: DEPENDENT_MUTATION_ID,
      numeroRdo: "RDO-0004",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    await closeCortexDb();
    await getCortexDb();
    const mutationIds = [
      REPLACEMENT_ID,
      DEPENDENT_REPLACEMENT_ID,
      CHAIN_REPLACEMENT_ID,
    ];
    const eventIds = [
      REPLACEMENT_EVENT_ID,
      DEPENDENT_REPLACEMENT_EVENT_ID,
      CHAIN_REPLACEMENT_EVENT_ID,
    ];

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: () => mutationIds.shift()!,
          ontologyEventIdFactory: () => eventIds.shift()!,
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
        },
      ),
    ).toBe(1);

    const database = await getCortexDb();
    const all = await database.getAll("outbox_mutations");
    const rootReplacement = all.find(
      (mutation) => mutation.causationId === ORIGINAL_ID,
    );
    const dependentReplacement = all.find(
      (mutation) => mutation.causationId === DEPENDENT_MUTATION_ID,
    );
    const chainReplacement = all.find(
      (mutation) => mutation.causationId === CHAIN_MUTATION_ID,
    );
    expect(rootReplacement?.clientMutationId).toBe(REPLACEMENT_ID);
    expect(dependentReplacement).toMatchObject({
      status: "PENDING",
      dependsOnMutationIds: [REPLACEMENT_ID],
    });
    expect(chainReplacement).toMatchObject({
      status: "PENDING",
      dependsOnMutationIds: [DEPENDENT_REPLACEMENT_ID],
    });
    expect(await database.get("outbox_mutations", DEPENDENT_MUTATION_ID))
      .toMatchObject({
        status: "REJECTED",
        blockedReason: `SUPERSEDED_BY:${DEPENDENT_REPLACEMENT_ID}`,
      });
    expect(await database.get("outbox_mutations", CHAIN_MUTATION_ID))
      .toMatchObject({
        status: "REJECTED",
        blockedReason: `SUPERSEDED_BY:${CHAIN_REPLACEMENT_ID}`,
      });

    await closeCortexDb();
    await getCortexDb();
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
      ),
    ).toBe(0);
  });

  it("rejeita ciclo na DAG sem gravar substituição parcial", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await seedCanonicalDependent({
      rdoId: CHAIN_RDO_ID,
      mutationId: CHAIN_MUTATION_ID,
      eventId: CHAIN_EVENT_ID,
      previousRdoId: DEPENDENT_RDO_ID,
      dependsOnMutationId: DEPENDENT_MUTATION_ID,
      numeroRdo: "RDO-0004",
    });
    const database = await getCortexDb();
    const dependent = await database.get(
      "outbox_mutations",
      DEPENDENT_MUTATION_ID,
    );
    await database.put("outbox_mutations", {
      ...dependent!,
      dependsOnMutationIds: [ORIGINAL_ID, CHAIN_MUTATION_ID],
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );

    const lookup = vi.fn(missingAuthoritativeRdo);
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    const terminalized = await database.getAll("outbox_mutations");
    expect(terminalized).toHaveLength(3);
    expect(terminalized.filter(
      (mutation) => mutation.status === "PENDING",
    )).toHaveLength(2);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(lookup).not.toHaveBeenCalled();
  });

  it.each([
    {
      name: "dependência externa ausente",
      dependencyId: "00000000-0000-4000-8000-000000000199",
    },
    {
      name: "autodependência da raiz",
      dependencyId: ORIGINAL_ID,
    },
  ])("terminaliza $name antes do lookup", async ({ dependencyId }) => {
    await seedCanonicalCreate(workforcePayload(), [dependencyId]);
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("não aceita dependência externa legada mesmo já marcada SYNCED", async () => {
    await seedCanonicalCreate(workforcePayload(), [DEPENDENT_ID]);
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      ...legacyDependent(),
      status: "SYNCED",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_ID))
      .toMatchObject({ status: "SYNCED" });
    expect(lookup).not.toHaveBeenCalled();
  });

  it.each(["userId", "obraId"] as const)(
    "não aceita dependência canônica SYNCED externa com %s divergente",
    async (field) => {
      await seedStandaloneCanonicalDependency();
      const database = await getCortexDb();
      const dependency = await database.get(
        "outbox_mutations",
        DEPENDENT_MUTATION_ID,
      );
      await database.put("outbox_mutations", {
        ...dependency!,
        status: "SYNCED",
        [field]: crypto.randomUUID(),
      });
      await seedCanonicalCreate(workforcePayload(), [
        DEPENDENT_MUTATION_ID,
      ]);
      await rejectOriginal(
        "IDEMPOTENCY_MISMATCH",
        "clientMutationId já foi usado com outro conteúdo ou rastro.",
      );
      const lookup = vi.fn(missingAuthoritativeRdo);

      expect(
        await recoverRejectedRdoMutationsForSync(
          captureOnlineSyncSession(),
          { lookupAuthoritativeRdo: lookup },
        ),
      ).toBe(0);
      expect(await database.get("outbox_mutations", ORIGINAL_ID))
        .toMatchObject({
          lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
        });
      expect(lookup).not.toHaveBeenCalled();
    },
  );

  it("terminaliza ciclo entre raiz e dependente antes do lookup", async () => {
    await seedCanonicalCreate(workforcePayload(), [
      DEPENDENT_MUTATION_ID,
    ]);
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("terminaliza cadeia raiz-legado-canônico antes do lookup", async () => {
    await seedCanonicalCreate(workforcePayload());
    const database = await getCortexDb();
    await database.put("outbox_mutations", legacyDependent());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: DEPENDENT_ID,
      numeroRdo: "RDO-0003",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_ID))
      .toMatchObject({ status: "PENDING" });
    expect(await database.get("outbox_mutations", DEPENDENT_MUTATION_ID))
      .toMatchObject({ status: "PENDING" });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("terminaliza ciclo composto somente por dependentes legados", async () => {
    await seedCanonicalCreate(workforcePayload());
    const database = await getCortexDb();
    const secondLegacyId = crypto.randomUUID();
    await database.put("outbox_mutations", {
      ...legacyDependent(),
      dependsOnMutationIds: [ORIGINAL_ID, secondLegacyId],
    });
    await database.put("outbox_mutations", {
      ...legacyDependent(),
      clientMutationId: secondLegacyId,
      dependsOnMutationIds: [DEPENDENT_ID],
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_ID))
      .toMatchObject({ status: "PENDING" });
    expect(await database.get("outbox_mutations", secondLegacyId))
      .toMatchObject({ status: "PENDING" });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("terminaliza dependente legado com dependência ausente", async () => {
    await seedCanonicalCreate(workforcePayload());
    const database = await getCortexDb();
    await database.put("outbox_mutations", {
      ...legacyDependent(),
      dependsOnMutationIds: [ORIGINAL_ID, crypto.randomUUID()],
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_ID))
      .toMatchObject({ status: "PENDING" });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("terminaliza uma DAG acima de 64 dependentes sem loop de rede", async () => {
    await seedCanonicalCreate(workforcePayload());
    for (let index = 0; index < 65; index += 1) {
      await seedCanonicalDependent({
        rdoId: crypto.randomUUID(),
        mutationId: crypto.randomUUID(),
        eventId: crypto.randomUUID(),
        previousRdoId: RDO_ID,
        dependsOnMutationId: ORIGINAL_ID,
        numeroRdo: `RDO-${String(index + 3).padStart(4, "0")}`,
      });
    }
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(
      (await database.getAll("outbox_mutations")).filter(
        (mutation) => mutation.status === "PENDING",
      ),
    ).toHaveLength(65);
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(lookup).not.toHaveBeenCalled();
  });

  it("adia sem escrever quando um dependente canônico está SYNCING", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const dependent = await database.get(
      "outbox_mutations",
      DEPENDENT_MUTATION_ID,
    );
    await database.put("outbox_mutations", {
      ...dependent!,
      status: "SYNCING",
    });
    const before = await database.getAll("outbox_mutations");
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.getAll("outbox_mutations")).toEqual(before);
    expect(lookup).not.toHaveBeenCalled();
  });

  it.each(["SYNCED", "REJECTED"] as const)(
    "exige revisão quando um dependente canônico está %s",
    async (status) => {
      await seedCanonicalCreate(workforcePayload());
      await seedCanonicalDependent({
        rdoId: DEPENDENT_RDO_ID,
        mutationId: DEPENDENT_MUTATION_ID,
        eventId: DEPENDENT_EVENT_ID,
        previousRdoId: RDO_ID,
        dependsOnMutationId: ORIGINAL_ID,
        numeroRdo: "RDO-0003",
      });
      await rejectOriginal(
        "IDEMPOTENCY_MISMATCH",
        "clientMutationId já foi usado com outro conteúdo ou rastro.",
      );
      const database = await getCortexDb();
      const dependent = await database.get(
        "outbox_mutations",
        DEPENDENT_MUTATION_ID,
      );
      const terminalDependent = { ...dependent!, status };
      await database.put("outbox_mutations", terminalDependent);
      const lookup = vi.fn(missingAuthoritativeRdo);

      expect(
        await recoverRejectedRdoMutationsForSync(
          captureOnlineSyncSession(),
          { lookupAuthoritativeRdo: lookup },
        ),
      ).toBe(0);
      expect(await database.get("outbox_mutations", ORIGINAL_ID))
        .toMatchObject({
          lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
        });
      expect(
        await database.get(
          "outbox_mutations",
          DEPENDENT_MUTATION_ID,
        ),
      ).toEqual(terminalDependent);
      expect(lookup).not.toHaveBeenCalled();
    },
  );

  it.each(["SYNCED", "SYNCING"] as const)(
    "não recupera com descendente legado cross-entity em %s",
    async (status) => {
      await seedCanonicalCreate(workforcePayload());
      const database = await getCortexDb();
      const dependent = {
        ...legacyDependent(),
        entidadeId: DEPENDENT_RDO_ID,
        status,
      };
      await database.put("outbox_mutations", dependent);
      await rejectOriginal(
        "IDEMPOTENCY_MISMATCH",
        "clientMutationId já foi usado com outro conteúdo ou rastro.",
      );
      const lookup = vi.fn(missingAuthoritativeRdo);

      expect(
        await recoverRejectedRdoMutationsForSync(
          captureOnlineSyncSession(),
          { lookupAuthoritativeRdo: lookup },
        ),
      ).toBe(0);
      expect(await database.get("outbox_mutations", ORIGINAL_ID))
        .toMatchObject({
          lastSafeCode: status === "SYNCING"
            ? "IDEMPOTENCY_MISMATCH"
            : "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
        });
      expect(await database.get("outbox_mutations", DEPENDENT_ID))
        .toEqual(dependent);
      expect(lookup).not.toHaveBeenCalled();
    },
  );

  it("não reescreve a DAG quando um dependente perdeu o evento canônico", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    const database = await getCortexDb();
    await database.delete("operational_events", DEPENDENT_EVENT_ID);
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: missingAuthoritativeRdo },
      ),
    ).toBe(0);
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_MUTATION_ID))
      .toMatchObject({ status: "PENDING" });
  });

  it("não re-assina dependente com payload canônico adulterado", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const dependent = await database.get(
      "outbox_mutations",
      DEPENDENT_MUTATION_ID,
    );
    await database.put("outbox_mutations", {
      ...dependent!,
      payload: {
        ...dependent!.payload,
        observacoes: "adulterado sem novo hash",
      },
    });

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: missingAuthoritativeRdo },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
      });
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
  });

  it.each(["MISSING", "DUPLICATE"] as const)(
    "terminaliza evento raiz %s sem repetir lookup",
    async (condition) => {
      await seedCanonicalCreate(workforcePayload());
      await rejectOriginal(
        "IDEMPOTENCY_MISMATCH",
        "clientMutationId já foi usado com outro conteúdo ou rastro.",
      );
      const database = await getCortexDb();
      const originalEvent = await database.get(
        "operational_events",
        ORIGINAL_EVENT_ID,
      );
      if (condition === "MISSING") {
        await database.delete("operational_events", ORIGINAL_EVENT_ID);
      } else {
        await database.add("operational_events", {
          ...originalEvent!,
          id: crypto.randomUUID(),
        });
      }
      const lookup = vi.fn(missingAuthoritativeRdo);

      expect(
        await recoverRejectedRdoMutationsForSync(
          captureOnlineSyncSession(),
          { lookupAuthoritativeRdo: lookup },
        ),
      ).toBe(0);
      expect(await database.get("outbox_mutations", ORIGINAL_ID))
        .toMatchObject({
          lastSafeCode: "RDO_RECOVERY_LOCAL_STATE_INVALID",
        });
      expect(
        await recoverRejectedRdoMutationsForSync(
          captureOnlineSyncSession(),
          { lookupAuthoritativeRdo: lookup },
        ),
      ).toBe(0);
      expect(lookup).not.toHaveBeenCalled();
    },
  );

  it("terminaliza raiz sem RDO local e não repete lookup", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    await database.delete("rdos", RDO_ID);
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "RDO_RECOVERY_LOCAL_STATE_INVALID",
      });
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(lookup).not.toHaveBeenCalled();
  });

  it("não assina recuperação quando o RDO local pertence a outra obra", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const rdo = await database.get("rdos", RDO_ID);
    await database.put("rdos", {
      ...rdo!,
      obraId: "00000000-0000-4000-8000-000000000198",
    });
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "RDO_RECOVERY_LOCAL_STATE_INVALID",
      });
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
    expect(lookup).not.toHaveBeenCalled();
  });

  it("põe todos os CREATEs duplicados da entidade em revisão manual", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedDuplicateCanonicalCreate();
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    await rejectCanonicalMutation({
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      rdoId: RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
        },
      ),
    ).toBe(0);

    const database = await getCortexDb();
    const entityMutations = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    expect(entityMutations.filter(
      (mutation) => mutation.status === "PENDING",
    )).toHaveLength(0);
    expect(
      entityMutations.filter(
        (mutation) =>
          mutation.lastSafeCode ===
          "DUPLICATE_REJECTED_RDO_CREATE_REQUIRES_REVIEW",
      ),
    ).toHaveLength(2);
  });

  it("terminaliza CREATEs duplicados mesmo se um evento local estiver ausente", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedDuplicateCanonicalCreate();
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    await rejectCanonicalMutation({
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      rdoId: RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });
    const database = await getCortexDb();
    await database.delete("operational_events", DEPENDENT_EVENT_ID);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
        },
      ),
    ).toBe(0);

    const entityMutations = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    expect(
      entityMutations.filter(
        (mutation) =>
          mutation.lastSafeCode ===
          "DUPLICATE_REJECTED_RDO_CREATE_REQUIRES_REVIEW",
      ),
    ).toHaveLength(2);
    expect(
      entityMutations.filter((mutation) => mutation.status === "PENDING"),
    ).toHaveLength(0);
  });

  it("não terminaliza CREATEs duplicados se um evento surge após a decisão", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedDuplicateCanonicalCreate();
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    await rejectCanonicalMutation({
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      rdoId: RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });
    const database = await getCortexDb();
    const missingEvent = await database.get(
      "operational_events",
      DEPENDENT_EVENT_ID,
    );
    await database.delete("operational_events", DEPENDENT_EVENT_ID);
    let restored = false;
    const executionLease = {
      ownerToken: "expected-owner",
      assertOwned: vi.fn(async () => {
        if (restored) return;
        restored = true;
        await database.add("operational_events", missingEvent!);
      }),
    };

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { executionLease },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
      });
    expect(
      await database.get("outbox_mutations", DEPENDENT_MUTATION_ID),
    ).toMatchObject({
      lastSafeCode: "IDEMPOTENCY_MISMATCH",
    });
    expect(
      await database.getAllFromIndex(
        "operational_events",
        "by-client-mutation-id",
        DEPENDENT_MUTATION_ID,
      ),
    ).toHaveLength(1);
  });

  it.each(["PENDING", "ERROR", "SYNCING", "CONFLICT"] as const)(
    "não cria substituta quando outro CREATE da entidade está %s",
    async (status) => {
      await seedCanonicalCreate(workforcePayload());
      await seedDuplicateCanonicalCreate();
      await rejectOriginal(
        "IDEMPOTENCY_MISMATCH",
        "clientMutationId já foi usado com outro conteúdo ou rastro.",
      );
      const database = await getCortexDb();
      const competing = await database.get(
        "outbox_mutations",
        DEPENDENT_MUTATION_ID,
      );
      const competingWithStatus = { ...competing!, status };
      await database.put("outbox_mutations", competingWithStatus);
      const lookup = vi.fn(missingAuthoritativeRdo);

      expect(
        await recoverRejectedRdoMutationsForSync(
          captureOnlineSyncSession(),
          { lookupAuthoritativeRdo: lookup },
        ),
      ).toBe(0);
      expect(await database.get("outbox_mutations", ORIGINAL_ID))
        .toMatchObject({
          lastSafeCode: status === "SYNCING"
            ? "IDEMPOTENCY_MISMATCH"
            : "COMPETING_RDO_CREATE_REQUIRES_REVIEW",
        });
      expect(
        await database.get(
          "outbox_mutations",
          DEPENDENT_MUTATION_ID,
        ),
      ).toEqual(competingWithStatus);
      expect(lookup).not.toHaveBeenCalled();
    },
  );

  it("não cria substituta de workforce quando outro CREATE já foi SYNCED", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedDuplicateCanonicalCreate();
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );
    const database = await getCortexDb();
    const competing = await database.get(
      "outbox_mutations",
      DEPENDENT_MUTATION_ID,
    );
    await database.put("outbox_mutations", {
      ...competing!,
      status: "SYNCED",
    });
    const loadContext = vi.fn(async () => new Set([VALID_WORKER_ID]));

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { loadAuthorizedCollaboratorIds: loadContext },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "COMPETING_RDO_CREATE_REQUIRES_REVIEW",
      });
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
    expect(loadContext).not.toHaveBeenCalled();
  });

  it("não cria substituta quando outro CREATE REJECTED não é ancestral causal", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedDuplicateCanonicalCreate();
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const competing = await database.get(
      "outbox_mutations",
      DEPENDENT_MUTATION_ID,
    );
    await database.put("outbox_mutations", {
      ...competing!,
      status: "REJECTED",
      lastSafeCode: "OTHER_REJECTION",
    });
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "COMPETING_RDO_CREATE_REQUIRES_REVIEW",
      });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("não cria substituta de workforce com UPDATE independente já SYNCED", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );
    const database = await getCortexDb();
    const independentUpdate = {
      ...legacyDependent(),
      dependsOnMutationIds: [],
      status: "SYNCED" as const,
    };
    await database.put("outbox_mutations", independentUpdate);
    const loadContext = vi.fn(async () => new Set([VALID_WORKER_ID]));

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { loadAuthorizedCollaboratorIds: loadContext },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "COMPETING_RDO_MUTATION_REQUIRES_REVIEW",
      });
    expect(loadContext).not.toHaveBeenCalled();
  });

  it("bloqueia CREATE legado concorrente sem alterá-lo", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const legacyCreate = {
      ...legacyDependent(),
      operacao: "CRIAR_RDO" as const,
      baseVersao: null,
    };
    await database.put("outbox_mutations", legacyCreate);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: missingAuthoritativeRdo },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "COMPETING_RDO_CREATE_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_ID))
      .toEqual(legacyCreate);
  });

  it("bloqueia UPDATE independente ativo para a mesma entidade", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const independentUpdate = {
      ...legacyDependent(),
      dependsOnMutationIds: [],
    };
    await database.put("outbox_mutations", independentUpdate);
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: vi.fn(() => REPLACEMENT_ID),
          lookupAuthoritativeRdo: lookup,
        },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "COMPETING_RDO_MUTATION_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_ID))
      .toEqual(independentUpdate);
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
    expect(lookup).not.toHaveBeenCalled();
  });

  it("adia sem escrever quando UPDATE dependente já está SYNCING", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const syncingDependent = {
      ...legacyDependent(),
      status: "SYNCING" as const,
    };
    await database.put("outbox_mutations", syncingDependent);
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_ID))
      .toEqual(syncingDependent);
    expect(lookup).not.toHaveBeenCalled();
  });

  it("não terminaliza por CREATE concorrente que mudou durante a decisão", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedDuplicateCanonicalCreate();
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const executionLease = {
      ownerToken: "expected-owner",
      assertOwned: vi.fn(async () => {
        await database.delete(
          "outbox_mutations",
          DEPENDENT_MUTATION_ID,
        );
      }),
    };

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { executionLease },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
      });
    expect(
      await database.get("outbox_mutations", DEPENDENT_MUTATION_ID),
    ).toBeUndefined();
  });

  it("não recupera automaticamente uma cadeia causal com ancestral ausente", async () => {
    const missingAncestorId = crypto.randomUUID();
    await seedCanonicalCreate(
      workforcePayload(),
      [],
      missingAncestorId,
    );
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "CAUSAL_CHAIN_INVALID",
      });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("não exclui CREATE causal ancestral que ainda está PENDING", async () => {
    await seedCanonicalCreate(
      workforcePayload(),
      [],
      DEPENDENT_MUTATION_ID,
    );
    await seedDuplicateCanonicalCreate();
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "COMPETING_RDO_CREATE_REQUIRES_REVIEW",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_MUTATION_ID))
      .toMatchObject({ status: "PENDING" });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("recupera após uma cadeia íntegra de duas edições locais", async () => {
    const payload = {
      ...workforcePayload(),
      creationContextVersion: 8,
    };
    await seedCanonicalCreate(payload);
    const database = await getCortexDb();
    const firstRdo = await database.get("rdos", RDO_ID);
    const firstDraft = rdoDraftFromLocalRecord(firstRdo!);
    firstDraft.observacoes = "primeira edição local";
    const firstEdit = await saveExistingRdoDraftAtomically(firstDraft);

    const secondRdo = await database.get("rdos", RDO_ID);
    const secondDraft = rdoDraftFromLocalRecord(secondRdo!);
    secondDraft.observacoes = "segunda edição local";
    const secondEdit = await saveExistingRdoDraftAtomically(secondDraft);
    const secondEditEvents = await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      secondEdit.mutation.clientMutationId,
    );
    expect(secondEditEvents).toHaveLength(1);
    await rejectCanonicalMutation({
      mutationId: secondEdit.mutation.clientMutationId,
      eventId: secondEditEvents[0].id,
      rdoId: RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
        },
      ),
    ).toBe(1);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "SUPERSEDED_BY_LOCAL_EDIT",
        blockedReason:
          `SUPERSEDED_BY:${firstEdit.mutation.clientMutationId}`,
      });
    expect(
      await database.get(
        "outbox_mutations",
        firstEdit.mutation.clientMutationId,
      ),
    ).toMatchObject({
      status: "REJECTED",
      lastSafeCode: "SUPERSEDED_BY_LOCAL_EDIT",
      blockedReason:
        `SUPERSEDED_BY:${secondEdit.mutation.clientMutationId}`,
    });
    expect(await database.get("outbox_mutations", REPLACEMENT_ID))
      .toMatchObject({
        status: "PENDING",
        causationId: secondEdit.mutation.clientMutationId,
      });
  });

  it("não isenta ancestral de edição local com ponte superseded adulterada", async () => {
    await seedCanonicalCreate({
      ...workforcePayload(),
      creationContextVersion: 8,
    });
    const database = await getCortexDb();
    const rdo = await database.get("rdos", RDO_ID);
    const draft = rdoDraftFromLocalRecord(rdo!);
    draft.observacoes = "edição local";
    const edit = await saveExistingRdoDraftAtomically(draft);
    const original = await database.get("outbox_mutations", ORIGINAL_ID);
    await database.put("outbox_mutations", {
      ...original!,
      blockedReason: `SUPERSEDED_BY:${crypto.randomUUID()}`,
    });
    const editEvents = await database.getAllFromIndex(
      "operational_events",
      "by-client-mutation-id",
      edit.mutation.clientMutationId,
    );
    await rejectCanonicalMutation({
      mutationId: edit.mutation.clientMutationId,
      eventId: editEvents[0].id,
      rdoId: RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(
      await database.get(
        "outbox_mutations",
        edit.mutation.clientMutationId,
      ),
    ).toMatchObject({
      lastSafeCode: "COMPETING_RDO_CREATE_REQUIRES_REVIEW",
    });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("não recupera automaticamente uma cadeia causal cíclica", async () => {
    const payload = workforcePayload();
    await seedCanonicalCreate(
      payload,
      [],
      DEPENDENT_MUTATION_ID,
    );
    const database = await getCortexDb();
    const rdo = await database.get("rdos", RDO_ID);
    const writes: readonly LocalMutationDomainWrite<"rdos">[] = [
      { store: "rdos", value: rdo!, principal: true },
    ];
    await commitLocalMutation({
      clientMutationId: DEPENDENT_MUTATION_ID,
      ontologyEventId: DEPENDENT_EVENT_ID,
      deviceId: DEVICE_ID,
      userId,
      obraId: OBRA_ID,
      entityType: "RDO",
      entityId: RDO_ID,
      entityName: "RDO-0002",
      operation: "UPDATE",
      transportOperation: "ATUALIZAR_RDO_RASCUNHO",
      baseVersion: 0,
      occurredAt: "2026-07-28T12:01:00.000Z",
      previousSnapshot: payload,
      nextSnapshot: payload,
      principalSnapshot: rdo!,
      eventType: "RDO_EDITADO",
      causationId: ORIGINAL_ID,
      write: () => writes,
    });
    const causalAncestor = await database.get(
      "outbox_mutations",
      DEPENDENT_MUTATION_ID,
    );
    await database.put("outbox_mutations", {
      ...causalAncestor!,
      status: "SYNCED",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "CAUSAL_CHAIN_INVALID",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_MUTATION_ID))
      .toMatchObject({ status: "SYNCED" });
    expect(lookup).not.toHaveBeenCalled();
  });

  it("limita a cadeia a uma autorrecuperação e termina a segunda rejeição", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    await recoverRejectedRdoMutationsForSync(
      captureOnlineSyncSession(),
      {
        clientMutationIdFactory: () => REPLACEMENT_ID,
        ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
        lookupAuthoritativeRdo: missingAuthoritativeRdo,
      },
    );
    await rejectCanonicalMutation({
      mutationId: REPLACEMENT_ID,
      eventId: REPLACEMENT_EVENT_ID,
      rdoId: RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });
    const nextMutationId = vi.fn(() => DEPENDENT_REPLACEMENT_ID);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { clientMutationIdFactory: nextMutationId },
      ),
    ).toBe(0);
    expect(nextMutationId).not.toHaveBeenCalled();
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", REPLACEMENT_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "AUTO_RECOVERY_LIMIT_REACHED",
      });
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
  });

  it("limita a autorrecuperação do dependente religado em outro RDO", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const mutationIds = [
      REPLACEMENT_ID,
      DEPENDENT_REPLACEMENT_ID,
    ];
    const eventIds = [
      REPLACEMENT_EVENT_ID,
      DEPENDENT_REPLACEMENT_EVENT_ID,
    ];
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: () => mutationIds.shift()!,
          ontologyEventIdFactory: () => eventIds.shift()!,
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
        },
      ),
    ).toBe(1);
    await rejectCanonicalMutation({
      mutationId: DEPENDENT_REPLACEMENT_ID,
      eventId: DEPENDENT_REPLACEMENT_EVENT_ID,
      rdoId: DEPENDENT_RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });
    const lookup = vi.fn(missingAuthoritativeRdo);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { lookupAuthoritativeRdo: lookup },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(
      await database.get(
        "outbox_mutations",
        DEPENDENT_REPLACEMENT_ID,
      ),
    ).toMatchObject({
      lastSafeCode: "AUTO_RECOVERY_LIMIT_REACHED",
    });
    expect(await database.getAll("outbox_mutations")).toHaveLength(4);
    expect(lookup).not.toHaveBeenCalled();
  });

  it("detecta autorrecuperação em ancestral transitivo", async () => {
    const payload = workforcePayload();
    await seedCanonicalCreate(payload);
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    await recoverRejectedRdoMutationsForSync(
      captureOnlineSyncSession(),
      {
        clientMutationIdFactory: () => REPLACEMENT_ID,
        ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
        lookupAuthoritativeRdo: missingAuthoritativeRdo,
      },
    );
    await rejectCanonicalMutation({
      mutationId: REPLACEMENT_ID,
      eventId: REPLACEMENT_EVENT_ID,
      rdoId: RDO_ID,
      safeCode: "SUPERSEDED_BY_DEPENDENCY_REWIRE",
      message: "dependência substituída",
    });
    const database = await getCortexDb();
    const rdo = await database.get("rdos", RDO_ID);
    const writes: readonly LocalMutationDomainWrite<"rdos">[] = [
      { store: "rdos", value: rdo!, principal: true },
    ];
    await commitLocalMutation({
      clientMutationId: DEPENDENT_REPLACEMENT_ID,
      ontologyEventId: DEPENDENT_REPLACEMENT_EVENT_ID,
      deviceId: DEVICE_ID,
      userId,
      obraId: OBRA_ID,
      entityType: "RDO",
      entityId: RDO_ID,
      entityName: "RDO-0002",
      operation: "CREATE",
      transportOperation: "CRIAR_RDO",
      baseVersion: null,
      occurredAt: "2026-07-28T12:06:00.000Z",
      previousSnapshot: {},
      nextSnapshot: payload,
      principalSnapshot: rdo!,
      eventType: "RDO_CRIADO",
      correlationId: ORIGINAL_ID,
      causationId: REPLACEMENT_ID,
      write: () => writes,
    });
    await rejectCanonicalMutation({
      mutationId: DEPENDENT_REPLACEMENT_ID,
      eventId: DEPENDENT_REPLACEMENT_EVENT_ID,
      rdoId: RDO_ID,
      safeCode: "IDEMPOTENCY_MISMATCH",
      message: "clientMutationId já foi usado com outro conteúdo ou rastro.",
    });
    const nextMutationId = vi.fn(() => CHAIN_REPLACEMENT_ID);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { clientMutationIdFactory: nextMutationId },
      ),
    ).toBe(0);
    expect(nextMutationId).not.toHaveBeenCalled();
    expect(
      await database.get(
        "outbox_mutations",
        DEPENDENT_REPLACEMENT_ID,
      ),
    ).toMatchObject({
      lastSafeCode: "AUTO_RECOVERY_LIMIT_REACHED",
    });
  });

  it("reconcilia entidade existente como UPDATE com a versão autoritativa", async () => {
    const localPayload = {
      ...workforcePayload(),
      observacoes: "edição local preservada",
    };
    await seedCanonicalCreate(localPayload);
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: () => REPLACEMENT_ID,
          ontologyEventIdFactory: () => REPLACEMENT_EVENT_ID,
          lookupAuthoritativeRdo: async () => ({
            kind: "FOUND",
            version: 7,
            rdo: {
              ...workforcePayload(),
              versaoEntidade: 7,
              status: "RASCUNHO",
              clientMutationId: ORIGINAL_ID,
              observacoes: "estado confirmado no servidor",
            },
          }),
        },
      ),
    ).toBe(1);

    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        blockedReason: `SUPERSEDED_BY:${REPLACEMENT_ID}`,
      });
    expect(await database.get("outbox_mutations", REPLACEMENT_ID))
      .toMatchObject({
        operation: "UPDATE",
        operacao: "ATUALIZAR_RDO_RASCUNHO",
        baseVersion: 7,
        baseVersao: 7,
        causationId: ORIGINAL_ID,
        payload: expect.objectContaining({
          observacoes: "edição local preservada",
        }),
      });
    expect(await database.get("operational_events", REPLACEMENT_EVENT_ID))
      .toMatchObject({
        type: "RDO_EDITADO",
        previousState: expect.objectContaining({
          observacoes: "estado confirmado no servidor",
        }),
        newState: expect.objectContaining({
          observacoes: "edição local preservada",
        }),
      });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      versaoEntidade: 7,
      syncStatus: "PENDING_SYNC",
    });
  });

  it("adia sem terminalizar quando o probe autoritativo vem incompleto", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const mutationFactory = vi.fn(() => REPLACEMENT_ID);
    const lookup = vi.fn(async () => ({ kind: "INCOMPLETE" as const }));

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: mutationFactory,
          now: () => RECOVERED_AT,
          lookupAuthoritativeRdo: lookup,
        },
      ),
    ).toBe(0);
    expect(mutationFactory).not.toHaveBeenCalled();
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
        nextAttemptAt: expect.any(String),
      });
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => RECOVERED_AT,
          lookupAuthoritativeRdo: lookup,
        },
      ),
    ).toBe(0);
    expect(lookup).toHaveBeenCalledTimes(1);
  });

  it("deixa falha transitória do probe para a próxima sincronização", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );

    let clock = RECOVERED_AT;
    const lookup = vi.fn(async () => {
      throw new Error("rede indisponível");
    });
    const recover = () =>
      recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => clock,
          lookupAuthoritativeRdo: lookup,
        },
      );

    expect(await recover()).toBe(0);
    const database = await getCortexDb();
    const deferred = await database.get("outbox_mutations", ORIGINAL_ID);
    expect(deferred).toMatchObject({
        status: "REJECTED",
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
      });
    expect(Date.parse(deferred!.nextAttemptAt!))
      .toBeGreaterThan(Date.parse(clock));
    expect(await recover()).toBe(0);
    expect(lookup).toHaveBeenCalledTimes(1);
    clock = "2026-07-28T12:10:00.000Z";
    expect(await recover()).toBe(0);
    expect(lookup).toHaveBeenCalledTimes(2);
  });

  it("exige clientMutationId original e status RASCUNHO no RDO encontrado", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          lookupAuthoritativeRdo: async () => ({
            kind: "FOUND",
            version: 3,
            rdo: {
              ...workforcePayload(),
              clientMutationId: DEPENDENT_MUTATION_ID,
              status: "ENVIADO",
              versaoEntidade: 3,
            },
          }),
        },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "IDEMPOTENCY_RECONCILIATION_REQUIRED",
      });
  });

  it("não interpreta contexto parcial como revogação de colaborador", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );
    const mutationFactory = vi.fn(() => REPLACEMENT_ID);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          clientMutationIdFactory: mutationFactory,
          loadAuthorizedCollaboratorIds: async () => {
            throw new RdoWorkforceContextUnverifiedError();
          },
        },
      ),
    ).toBe(0);
    expect(mutationFactory).not.toHaveBeenCalled();
    const database = await getCortexDb();
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "WORKFORCE_RECOVERY_CONTEXT_UNVERIFIED",
      });
    expect((await database.get("rdos", RDO_ID))?.payload.maoObra)
      .toEqual((workforcePayload().maoObra));
  });

  it("deixa falha transitória do contexto para a próxima sincronização", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );

    let clock = RECOVERED_AT;
    const loadContext = vi.fn(async () => {
      throw new Error("timeout");
    });
    const recover = () =>
      recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          now: () => clock,
          loadAuthorizedCollaboratorIds: loadContext,
        },
      );
    expect(await recover()).toBe(0);
    const database = await getCortexDb();
    const deferred = await database.get("outbox_mutations", ORIGINAL_ID);
    expect(deferred).toMatchObject({
        lastSafeCode: "VALIDATION_OR_AUTHORIZATION",
      });
    expect(Date.parse(deferred!.nextAttemptAt!))
      .toBeGreaterThan(Date.parse(clock));
    expect(await recover()).toBe(0);
    expect(loadContext).toHaveBeenCalledTimes(1);
    clock = "2026-07-28T12:10:00.000Z";
    expect(await recover()).toBe(0);
    expect(loadContext).toHaveBeenCalledTimes(2);
  });

  it("não abre a transação de recuperação se perder o lease durante o lookup", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );
    const leaseError = new Error(
      "A sincronização perdeu a exclusividade para outra aba.",
    );
    const executionLease = {
      ownerToken: "lease-owner",
      assertOwned: vi.fn(async () => {
        throw leaseError;
      }),
    };

    await expect(
      recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          loadAuthorizedCollaboratorIds: async () =>
            new Set([VALID_WORKER_ID]),
          executionLease,
        },
      ),
    ).rejects.toBe(leaseError);
    expect(executionLease.assertOwned).toHaveBeenCalledTimes(1);
    const database = await getCortexDb();
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "VALIDATION_OR_AUTHORIZATION",
      });
  });

  it("não grava se perder o lease depois do lookup", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "Colaborador não está ativo e vinculado à obra do RDO.",
    );
    const leaseError = new Error(
      "A sincronização perdeu a exclusividade para outra aba.",
    );
    const loadContext = vi.fn(async () => new Set([VALID_WORKER_ID]));
    const executionLease = {
      ownerToken: "lease-owner",
      assertOwned: vi.fn()
        .mockResolvedValueOnce(undefined)
        .mockRejectedValueOnce(leaseError),
    };

    await expect(
      recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          loadAuthorizedCollaboratorIds: loadContext,
          executionLease,
        },
      ),
    ).rejects.toBe(leaseError);
    expect(loadContext).toHaveBeenCalledTimes(1);
    const database = await getCortexDb();
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "VALIDATION_OR_AUTHORIZATION",
      });
  });

  it("aborta dentro da transação se o lease durável foi substituído", async () => {
    await seedCanonicalCreate(workforcePayload());
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    await updateSyncState({
      syncExecutionLease: {
        ownerToken: "other-owner",
        acquiredAt: new Date().toISOString(),
        heartbeatAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      },
    });
    const executionLease = {
      ownerToken: "expected-owner",
      assertOwned: vi.fn(async () => undefined),
    };

    await expect(
      recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
          executionLease,
        },
      ),
    ).rejects.toThrow(
      "A sincronização perdeu a exclusividade para outra aba.",
    );
    const database = await getCortexDb();
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
      });
  });

  it("CAS preserva evento dependente alterado por outro writer", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const leaseTimestamp = new Date().toISOString();
    await updateSyncState({
      syncExecutionLease: {
        ownerToken: "expected-owner",
        acquiredAt: leaseTimestamp,
        heartbeatAt: leaseTimestamp,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      },
    });
    let concurrentEvent: OperationalEventRecord | undefined;
    let duplicateInserted = false;
    const executionLease = {
      ownerToken: "expected-owner",
      assertOwned: vi.fn(async () => {
        if (duplicateInserted) return;
        duplicateInserted = true;
        const database = await getCortexDb();
        const current = await database.get(
          "operational_events",
          DEPENDENT_EVENT_ID,
        );
        concurrentEvent = {
          ...current!,
          payload: {
            ...current!.payload,
            eventOnlyWriter: true,
          },
        };
        await database.put("operational_events", concurrentEvent);
      }),
    };

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
          executionLease,
        },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(
      await database.get("operational_events", DEPENDENT_EVENT_ID),
    ).toEqual(concurrentEvent);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
      });
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
  });

  it("CAS detecta segundo evento concorrente do dependente", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const leaseTimestamp = new Date().toISOString();
    await updateSyncState({
      syncExecutionLease: {
        ownerToken: "expected-owner",
        acquiredAt: leaseTimestamp,
        heartbeatAt: leaseTimestamp,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
      },
    });
    let inserted = false;
    const executionLease = {
      ownerToken: "expected-owner",
      assertOwned: vi.fn(async () => {
        if (inserted) return;
        inserted = true;
        const database = await getCortexDb();
        const event = await database.get(
          "operational_events",
          DEPENDENT_EVENT_ID,
        );
        await database.add("operational_events", {
          ...event!,
          id: crypto.randomUUID(),
        });
      }),
    };

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          lookupAuthoritativeRdo: missingAuthoritativeRdo,
          executionLease,
        },
      ),
    ).toBe(0);
    const database = await getCortexDb();
    expect(
      await database.getAllFromIndex(
        "operational_events",
        "by-client-mutation-id",
        DEPENDENT_MUTATION_ID,
      ),
    ).toHaveLength(2);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        status: "REJECTED",
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
      });
    expect(await database.getAll("outbox_mutations")).toHaveLength(2);
  });

  it("não terminaliza com decisão de DAG obsoleta", async () => {
    await seedCanonicalCreate(workforcePayload());
    await seedCanonicalDependent({
      rdoId: DEPENDENT_RDO_ID,
      mutationId: DEPENDENT_MUTATION_ID,
      eventId: DEPENDENT_EVENT_ID,
      previousRdoId: RDO_ID,
      dependsOnMutationId: ORIGINAL_ID,
      numeroRdo: "RDO-0003",
    });
    await rejectOriginal(
      "IDEMPOTENCY_MISMATCH",
      "clientMutationId já foi usado com outro conteúdo ou rastro.",
    );
    const database = await getCortexDb();
    const dependent = await database.get(
      "outbox_mutations",
      DEPENDENT_MUTATION_ID,
    );
    await database.put("outbox_mutations", {
      ...dependent!,
      status: "SYNCED",
    });
    const executionLease = {
      ownerToken: "expected-owner",
      assertOwned: vi.fn(async () => {
        const current = await database.get(
          "outbox_mutations",
          DEPENDENT_MUTATION_ID,
        );
        await database.put("outbox_mutations", {
          ...current!,
          status: "PENDING",
        });
      }),
    };

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        { executionLease },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID))
      .toMatchObject({
        lastSafeCode: "IDEMPOTENCY_MISMATCH",
      });
    expect(await database.get("outbox_mutations", DEPENDENT_MUTATION_ID))
      .toMatchObject({ status: "PENDING" });
  });

  it("não altera outra rejeição de validação", async () => {
    const payload = workforcePayload();
    await seedCanonicalCreate(payload);
    await rejectOriginal(
      "VALIDATION_OR_AUTHORIZATION",
      "A programação selecionada não pertence à data do RDO.",
    );
    const database = await getCortexDb();
    const before = await database.get("outbox_mutations", ORIGINAL_ID);

    expect(
      await recoverRejectedRdoMutationsForSync(
        captureOnlineSyncSession(),
        {
          loadAuthorizedCollaboratorIds: vi.fn(),
        },
      ),
    ).toBe(0);
    expect(await database.get("outbox_mutations", ORIGINAL_ID)).toEqual(before);
    expect(await database.getAll("operational_events")).toHaveLength(1);
  });
});
