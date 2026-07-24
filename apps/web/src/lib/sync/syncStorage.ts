import { getCortexDb } from "../db/cortexDb";
import type {
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  LocalRdoChildRecord,
  LocalRdoRecord,
  LocalSyncStatus,
  ObraLocalRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
  ProcessedEventRecord,
  RdoAttachmentRecord,
  ServiceCatalogLocalRecord,
  ServicePriceVersionLocalRecord,
  TarefaRecord,
} from "../db/db.types";
import {
  mergeObraRecords,
  obraRecordFromPayload,
  snapshotRecordFromPayload,
} from "../db/homeRecordMappers";
import type {
  SyncPullEvent,
  SyncPushMutationResult,
} from "./sync.types";
import {
  assertCanonicalPayloadHash,
  buildCanonicalMutation,
  canonicalMutationJson,
  isCanonicalOutboxMutation,
} from "./mutationEnvelope";
import {
  classifyFieldConflict,
  remoteSnapshotEvidence,
} from "./fieldConflict";
import {
  mutationAfterRetryScheduled,
  retryDispositionForResult,
} from "./automaticSyncRetryStorage";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "./syncSession";
import { guardSyncTransaction } from "./guardedSyncTransaction";

function nowUtc(): string {
  return new Date().toISOString();
}

function objectValue(value: unknown): Record<string, unknown> {
  if (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value)
  ) {
    return value as Record<string, unknown>;
  }

  return {};
}

function textValue(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function canonicalObraReference(value: unknown): string {
  return textValue(value)
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toUpperCase()
    .replace(/%/g, "PCT")
    .replace(/\bPOR\s*CENTO\b/g, "PCT")
    .replace(/\bPERCENTUAL\b/g, "PCT")
    .replace(/[^A-Z0-9]+/g, "");
}

function extractObraOrigemFromPayload(
  payload: Record<string, unknown>,
): string[] {
  const values: string[] = [];
  const read = (value: unknown) => {
    const text = textValue(value);
    const match = /obra_origem\s*=\s*([^;\n]+)/i.exec(text);

    if (match?.[1]) {
      values.push(match[1].trim());
    }
  };

  read(payload.observacoes);

  for (const key of [
    "materiais",
    "controlesGeometricos",
    "servicosExecutados",
  ]) {
    const items = payload[key];

    if (!Array.isArray(items)) {
      continue;
    }

    for (const item of items) {
      read(objectValue(item).observacoes);
    }
  }

  return values;
}

function obraReferenceMatchesPayload(
  payload: Record<string, unknown>,
  obra: ObraLocalRecord,
): boolean {
  const contrato = canonicalObraReference(payload.contrato);
  const obraCodigo = canonicalObraReference(obra.codigoContrato);
  const obraNome = canonicalObraReference(obra.nome);
  const payloadCliente = canonicalObraReference(payload.cliente);
  const obraCliente = canonicalObraReference(obra.cliente);
  const sameCliente =
    !payloadCliente || !obraCliente || payloadCliente === obraCliente;

  if (contrato && obraCodigo && contrato === obraCodigo) {
    return true;
  }

  if (sameCliente && contrato && obraNome && contrato === obraNome) {
    return true;
  }

  return extractObraOrigemFromPayload(payload).some((origem) => {
    const origemNormalizada = canonicalObraReference(origem);
    return (
      sameCliente &&
      origemNormalizada &&
      obraNome &&
      origemNormalizada === obraNome
    );
  });
}

function resolveObraForRdoPayload(
  payload: Record<string, unknown>,
  obras: ObraLocalRecord[],
): ObraLocalRecord | null {
  const matches = obras.filter((obra) =>
    obraReferenceMatchesPayload(payload, obra),
  );
  const uniqueMatches = new Map(
    matches.map((obra) => [obra.id, obra]),
  );

  return uniqueMatches.size === 1
    ? [...uniqueMatches.values()][0]
    : null;
}

function payloadAfterObraReferenceRepair(
  payload: Record<string, unknown>,
  obra: ObraLocalRecord,
): Record<string, unknown> {
  const attachments = Array.isArray(payload.attachments)
    ? payload.attachments.map((item) => ({
        ...objectValue(item),
        obraId: obra.id,
      }))
    : payload.attachments;

  return {
    ...payload,
    obraId: obra.id,
    programacaoId: null,
    contrato: obra.codigoContrato || payload.contrato,
    attachments,
  };
}

function erroIndicaObraAusente(error: string | null): boolean {
  if (!error) {
    return false;
  }

  const normalized = error
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();

  return (
    normalized.includes("obra") &&
    normalized.includes("nao encontrada")
  );
}

function erroIndicaMaoObraColaboradorAusente(
  error: string | null,
): boolean {
  if (!error) {
    return false;
  }

  const normalized = error
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();

  return (
    normalized.includes("rdo_mao_obra") &&
    normalized.includes("colaborador") &&
    (normalized.includes("foreign key") ||
      normalized.includes("constraint") ||
      normalized.includes("fk_rdo_mao_obra_colaborador"))
  );
}

function payloadAfterMaoObraReferenceRepair(
  payload: Record<string, unknown>,
): Record<string, unknown> | null {
  if (!Array.isArray(payload.maoObra)) {
    return null;
  }

  let changed = false;
  const maoObra = payload.maoObra.map((item) => {
    if (
      item === null ||
      typeof item !== "object" ||
      Array.isArray(item)
    ) {
      return item;
    }

    const record = item as Record<string, unknown>;
    const colaboradorId = textValue(record.colaboradorId);
    const nomeColaborador = textValue(record.nomeColaborador);

    if (!colaboradorId || !nomeColaborador) {
      return record;
    }

    changed = true;

    return {
      ...record,
      colaboradorId: null,
    };
  });

  if (!changed) {
    return null;
  }

  return {
    ...payload,
    maoObra,
  };
}

type RdoChildStoreName =
  | "rdoMaoObra"
  | "rdoEquipamentos"
  | "rdoMateriais"
  | "rdoControlesGeometricos";

interface RdoChildStoreUpdater {
  index: (name: "by-rdo-id") => {
    getAll: (
      query: string,
    ) => Promise<LocalRdoChildRecord[]>;
  };
  put: (
    value: LocalRdoChildRecord,
  ) => Promise<IDBValidKey>;
}

interface RdoChildSyncTransaction {
  objectStore: (
    name: RdoChildStoreName,
  ) => RdoChildStoreUpdater;
}

interface OperationalEventStoreUpdater {
  index: (
    name: "by-rdo-id" | "by-client-mutation-id",
  ) => {
    getAll: (
      query: string,
    ) => Promise<OperationalEventRecord[]>;
  };
  put: (
    value: OperationalEventRecord,
  ) => Promise<IDBValidKey>;
}

async function exactCanonicalEvent(
  transaction: OperationalEventSyncTransaction,
  clientMutationId: string,
): Promise<CanonicalOperationalEventRecord> {
  const events = await transaction
    .objectStore("operational_events")
    .index("by-client-mutation-id")
    .getAll(clientMutationId);
  if (events.length !== 1 || events[0].schemaVersion !== 13) {
    throw new Error(
      `Mutação canônica ${clientMutationId} não possui evento local único.`,
    );
  }
  return events[0] as CanonicalOperationalEventRecord;
}

async function putCanonicalEvent(
  transaction: OperationalEventSyncTransaction,
  event: CanonicalOperationalEventRecord,
): Promise<void> {
  await transaction.objectStore("operational_events").put(event);
}

async function assertCanonicalMutationEventProvenance(
  mutation: CanonicalOutboxMutationRecord,
  event: CanonicalOperationalEventRecord,
): Promise<void> {
  await assertCanonicalPayloadHash(mutation);
  const rebuilt = await buildCanonicalMutation({
    clientMutationId: mutation.clientMutationId,
    ontologyEventId: mutation.trace.ontologyEventId,
    deviceId: mutation.deviceId,
    userId: mutation.userId,
    obraId: mutation.obraId,
    entityType: mutation.entityType,
    entityId: mutation.entityId,
    operation: mutation.operation,
    transportOperation: mutation.operacao,
    baseVersion: mutation.baseVersion,
    changedFields: mutation.changedFields,
    occurredAt: mutation.occurredAt,
    previousSnapshot: event.previousState,
    nextSnapshot: event.newState,
    authorizationScope: mutation.trace.authorizationScope,
    correlationId: mutation.correlationId,
    causationId: mutation.causationId,
    transport: mutation.transport ?? "SYNC_PUSH",
    dependsOnMutationIds: mutation.dependsOnMutationIds ?? [],
    relatedEntities: mutation.relatedEntities ?? [],
  });
  const provenance = (candidate: CanonicalOutboxMutationRecord) => ({
    schemaVersion: candidate.schemaVersion,
    clientMutationId: candidate.clientMutationId,
    deviceId: candidate.deviceId,
    userId: candidate.userId,
    obraId: candidate.obraId,
    entityType: candidate.entityType,
    entityId: candidate.entityId,
    operation: candidate.operation,
    baseVersion: candidate.baseVersion,
    changedFields: candidate.changedFields,
    occurredAt: candidate.occurredAt,
    payload: candidate.payload,
    entidadeTipo: candidate.entidadeTipo,
    entidadeId: candidate.entidadeId,
    operacao: candidate.operacao,
    baseVersao: candidate.baseVersao,
    criadaNoClienteEm: candidate.criadaNoClienteEm,
    transport: candidate.transport ?? "SYNC_PUSH",
    dependsOnMutationIds: candidate.dependsOnMutationIds ?? [],
    correlationId: candidate.correlationId,
    causationId: candidate.causationId,
    fieldPatch: candidate.fieldPatch,
    relatedEntities: candidate.relatedEntities ?? [],
    trace: candidate.trace,
  });
  if (
    canonicalMutationJson(provenance(mutation)) !==
    canonicalMutationJson(provenance(rebuilt.mutation))
  ) {
    throw new TypeError("Canonical mutation provenance is incoherent.");
  }

  const expectedEvent = {
    id: mutation.trace.ontologyEventId,
    principalEntityType: mutation.entityType,
    principalEntityId: mutation.entityId,
    principalEntityKey: `${mutation.entityType}:${mutation.entityId}`,
    relatedEntities: mutation.relatedEntities ?? [],
    obraId: mutation.obraId,
    rdoId: mutation.entityType === "RDO" ? mutation.entityId : null,
    occurredAt: mutation.occurredAt,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: mutation.userId,
    payload: rebuilt.nextSnapshot,
    syncStatus: "PENDING_SYNC",
    schemaVersion: 13,
    clientMutationId: mutation.clientMutationId,
    deviceId: mutation.deviceId,
    correlationId: mutation.correlationId,
    causationId: mutation.causationId,
    previousState: rebuilt.previousSnapshot,
    newState: rebuilt.nextSnapshot,
    result: "PENDING",
    errorCategory: null,
    entityVersion: mutation.baseVersion,
  };
  const actualEvent = {
    id: event.id,
    principalEntityType: event.principalEntity.tipo,
    principalEntityId: event.principalEntity.id,
    principalEntityKey: event.principalEntityKey,
    relatedEntities: event.relatedEntities,
    obraId: event.obraId,
    rdoId: event.rdoId,
    occurredAt: event.occurredAt,
    syncedAt: event.syncedAt,
    origin: event.origin,
    responsibleUserId: event.responsibleUserId,
    payload: event.payload,
    syncStatus: event.syncStatus,
    schemaVersion: event.schemaVersion,
    clientMutationId: event.clientMutationId,
    deviceId: event.deviceId,
    correlationId: event.correlationId,
    causationId: event.causationId,
    previousState: event.previousState,
    newState: event.newState,
    result: event.result,
    errorCategory: event.errorCategory,
    entityVersion: event.entityVersion,
  };
  if (
    canonicalMutationJson(actualEvent) !==
    canonicalMutationJson(expectedEvent)
  ) {
    throw new TypeError("Canonical mutation event provenance is incoherent.");
  }
}

interface OperationalEventSyncTransaction {
  objectStore: (
    name: "operational_events",
  ) => OperationalEventStoreUpdater;
}

interface RdoAttachmentStoreUpdater {
  index: (name: "by-rdo-id") => {
    getAll: (
      query: string,
    ) => Promise<RdoAttachmentRecord[]>;
  };
  put: (
    value: RdoAttachmentRecord,
  ) => Promise<IDBValidKey>;
}

interface RdoAttachmentSyncTransaction {
  objectStore: (
    name: "rdo_attachments",
  ) => RdoAttachmentStoreUpdater;
}

const RDO_CHILD_STORE_NAMES = [
  "rdoMaoObra",
  "rdoEquipamentos",
  "rdoMateriais",
  "rdoControlesGeometricos",
] as const;

const RDO_SYNC_TRANSACTION_STORES = [
  "outbox_mutations",
  "rdos",
  "tarefas",
  "operational_events",
  "rdo_attachments",
  "mensagens",
  "mensagem_anexos",
  "service_catalog",
  "service_price_versions",
  ...RDO_CHILD_STORE_NAMES,
] as const;

type ConvergentTarefaRecord = TarefaRecord;

interface OutboxEntityMutationStore {
  index: (name: "by-entity-id") => {
    getAll: (query: string) => Promise<OutboxMutationRecord[]>;
  };
  put: (mutation: OutboxMutationRecord) => Promise<IDBValidKey>;
}

function localSyncStatusFromMutations(
  mutations: OutboxMutationRecord[],
): LocalSyncStatus {
  if (mutations.some((mutation) => mutation.status === "CONFLICT")) {
    return "CONFLICT";
  }
  if (
    mutations.some(
      (mutation) =>
        mutation.status === "REJECTED" ||
        mutation.status === "ERROR",
    )
  ) {
    return "ERROR";
  }
  if (mutations.some((mutation) => mutation.status === "SYNCING")) {
    return "SYNCING";
  }
  if (mutations.some((mutation) => mutation.status === "PENDING")) {
    return "PENDING_SYNC";
  }

  return "SYNCED";
}

function isTaskMutation(mutation: OutboxMutationRecord): boolean {
  return (mutation.entidadeTipo as string) === "TAREFA";
}

function isRdoMutation(mutation: OutboxMutationRecord): boolean {
  return mutation.entidadeTipo === "RDO";
}

function isDefinitelyNonAppliedSuperseded(
  mutation: OutboxMutationRecord,
): boolean {
  return typeof mutation.blockedReason === "string" &&
    /^NON_APPLIED_SUPERSEDED_BY:[^\s:]+$/.test(
      mutation.blockedReason.trim(),
    );
}

function effectiveRdoMutations(
  mutations: OutboxMutationRecord[],
): OutboxMutationRecord[] {
  const byId = new Map(
    mutations.map((mutation) => [
      mutation.clientMutationId,
      mutation,
    ]),
  );
  const supersededIds = new Set<string>();

  for (const mutation of mutations) {
    const supersededBy = typeof mutation.blockedReason === "string"
      ? /^(?:SUPERSEDED_BY|NON_APPLIED_SUPERSEDED_BY):([^\s:]+)$/.exec(
          mutation.blockedReason.trim(),
        )?.[1]
      : undefined;
    const alias = supersededBy ? byId.get(supersededBy) : undefined;
    if (
      alias &&
      alias.entidadeTipo === mutation.entidadeTipo &&
      alias.entidadeId === mutation.entidadeId
    ) {
      supersededIds.add(mutation.clientMutationId);
    }

    if (
      isCanonicalOutboxMutation(mutation) &&
      mutation.causationId
    ) {
      const predecessor = byId.get(mutation.causationId);
      if (
        predecessor &&
        predecessor.entidadeTipo === mutation.entidadeTipo &&
        predecessor.entidadeId === mutation.entidadeId &&
        (
          predecessor.status === "CONFLICT" ||
          predecessor.status === "REJECTED"
        )
      ) {
        supersededIds.add(predecessor.clientMutationId);
      }
    }
  }

  return mutations.filter(
    (mutation) => !supersededIds.has(mutation.clientMutationId),
  );
}

async function rdoSyncStatusFromOutbox(
  store: OutboxEntityMutationStore,
  rdoId: string,
): Promise<LocalSyncStatus> {
  return localSyncStatusFromMutations(
    effectiveRdoMutations(
      (await store.index("by-entity-id").getAll(rdoId)).filter(
        isRdoMutation,
      ),
    ),
  );
}

async function rebasePendingLegacyRdoDependents(
  store: OutboxEntityMutationStore,
  applied: OutboxMutationRecord,
  resultVersion: number | null,
  timestamp: string,
): Promise<void> {
  if (
    applied.entidadeTipo !== "RDO" ||
    !Number.isSafeInteger(resultVersion) ||
    (resultVersion as number) < 0
  ) {
    return;
  }

  const mutations = await store
    .index("by-entity-id")
    .getAll(applied.entidadeId);
  for (const candidate of mutations) {
    if (
      isCanonicalOutboxMutation(candidate) ||
      candidate.entidadeTipo !== "RDO" ||
      candidate.entidadeId !== applied.entidadeId ||
      candidate.operacao !== "ATUALIZAR_RDO_RASCUNHO" ||
      candidate.status !== "PENDING" ||
      !candidate.dependsOnMutationIds?.includes(
        applied.clientMutationId,
      )
    ) {
      continue;
    }
    await store.put({
      ...candidate,
      baseVersao: resultVersion as number,
      updatedAt: timestamp,
    });
  }
}

async function releaseDefinitelyNonAppliedLegacyRdoDependents(
  store: OutboxEntityMutationStore,
  rejected: OutboxMutationRecord,
  timestamp: string,
): Promise<boolean> {
  if (
    rejected.entidadeTipo !== "RDO" ||
    rejected.operacao !== "ATUALIZAR_RDO_RASCUNHO" ||
    (
      rejected.status !== "REJECTED" &&
      rejected.status !== "ERROR"
    ) ||
    isCanonicalOutboxMutation(rejected)
  ) {
    return false;
  }

  const mutations = await store
    .index("by-entity-id")
    .getAll(rejected.entidadeId);
  const dependents = mutations.filter(
    (candidate) =>
      !isCanonicalOutboxMutation(candidate) &&
      candidate.entidadeTipo === "RDO" &&
      candidate.entidadeId === rejected.entidadeId &&
      candidate.operacao === "ATUALIZAR_RDO_RASCUNHO" &&
      candidate.status === "PENDING" &&
      candidate.dependsOnMutationIds?.includes(
        rejected.clientMutationId,
      ),
  );

  if (dependents.length !== 1) {
    return false;
  }

  const successor = dependents[0];
  await store.put({
    ...successor,
    dependsOnMutationIds: [
      ...new Set(
        (successor.dependsOnMutationIds ?? []).filter(
          (dependencyId) =>
            dependencyId !== rejected.clientMutationId,
        ),
      ),
    ],
    updatedAt: timestamp,
  });
  await store.put({
    ...rejected,
    blockedReason:
      `NON_APPLIED_SUPERSEDED_BY:${successor.clientMutationId}`,
    updatedAt: timestamp,
  });
  return true;
}

async function rewirePendingLegacyRdoDependents(
  store: OutboxEntityMutationStore,
  original: OutboxMutationRecord,
  replacement: OutboxMutationRecord,
  timestamp: string,
): Promise<void> {
  if (
    original.clientMutationId === replacement.clientMutationId ||
    original.entidadeTipo !== "RDO" ||
    replacement.entidadeTipo !== "RDO" ||
    original.entidadeId !== replacement.entidadeId
  ) {
    return;
  }

  const mutations = await store
    .index("by-entity-id")
    .getAll(original.entidadeId);
  for (const candidate of mutations) {
    if (
      isCanonicalOutboxMutation(candidate) ||
      candidate.entidadeTipo !== "RDO" ||
      candidate.entidadeId !== original.entidadeId ||
      candidate.status !== "PENDING" ||
      !candidate.dependsOnMutationIds?.includes(
        original.clientMutationId,
      )
    ) {
      continue;
    }

    await store.put({
      ...candidate,
      dependsOnMutationIds: [
        ...new Set(
          (candidate.dependsOnMutationIds ?? []).map(
            (dependencyId) =>
              dependencyId === original.clientMutationId
                ? replacement.clientMutationId
                : dependencyId,
          ),
        ),
      ],
      updatedAt: timestamp,
    });
  }
}

async function taskSyncStatusFromOutbox(
  store: OutboxEntityMutationStore,
  taskId: string,
): Promise<LocalSyncStatus> {
  return localSyncStatusFromMutations(
    (await store.index("by-entity-id").getAll(taskId)).filter(
      isTaskMutation,
    ),
  );
}

interface CatalogRecordStore<T> {
  get: (key: string) => Promise<T | undefined>;
  put: (value: T) => Promise<IDBValidKey>;
}

interface CatalogSyncTransaction {
  objectStore(name: "service_catalog"): CatalogRecordStore<ServiceCatalogLocalRecord>;
  objectStore(name: "service_price_versions"): CatalogRecordStore<ServicePriceVersionLocalRecord>;
}

async function updateCatalogMutationSyncStatus(
  transaction: CatalogSyncTransaction,
  mutation: OutboxMutationRecord,
  syncStatus: LocalSyncStatus,
  timestamp: string,
  lastError: string | null,
  entityVersion?: number | null,
): Promise<void> {
  if (mutation.entidadeTipo === "SERVICE") {
    const store = transaction.objectStore("service_catalog");
    const service = await store.get(mutation.entidadeId);
    if (service) {
      await store.put({ ...service, syncStatus, updatedAt: timestamp, lastError });
    }
  }
  if (mutation.entidadeTipo === "SERVICE_PRICE_VERSION") {
    const store = transaction.objectStore("service_price_versions");
    const price = await store.get(mutation.entidadeId);
    if (price) {
      await store.put({
        ...price,
        syncStatus,
        entityVersion: typeof entityVersion === "number"
          ? entityVersion
          : price.entityVersion,
        updatedAt: timestamp,
        lastError,
      });
    }
  }
}

async function updateRdoChildrenSyncStatus(
  transaction: RdoChildSyncTransaction,
  rdoId: string,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): Promise<void> {
  await Promise.all(
    RDO_CHILD_STORE_NAMES.map(async (storeName) => {
      const store = transaction.objectStore(storeName);
      const records = await store
        .index("by-rdo-id")
        .getAll(rdoId);

      await Promise.all(
        records.map((record: LocalRdoChildRecord) =>
          store.put({
            ...record,
            syncStatus,
            updatedAt: timestamp,
          }),
        ),
      );
    }),
  );
}

async function updateRdoOperationalEventsSyncStatus(
  transaction: OperationalEventSyncTransaction,
  rdoId: string,
  syncStatus: OperationalEventRecord["syncStatus"],
  timestamp: string,
  exactEventIds?: ReadonlySet<string>,
): Promise<void> {
  const store = transaction.objectStore("operational_events");

  const records = await store.index("by-rdo-id").getAll(rdoId);

  await Promise.all(
    records
      .filter((record) =>
        record.syncStatus !== "SYNCED" &&
        (exactEventIds === undefined || exactEventIds.has(record.id))
      )
      .map((record) =>
        store.put({
          ...record,
          syncStatus,
          syncedAt:
            syncStatus === "SYNCED" ? timestamp : record.syncedAt,
        }),
      ),
  );
}

function mutationOperationalEventIds(
  mutation: OutboxMutationRecord,
): ReadonlySet<string> {
  const events = mutation.payload.operationalEvents;
  if (!Array.isArray(events)) {
    return new Set();
  }
  return new Set(
    events.flatMap((event) => {
      if (!event || typeof event !== "object") {
        return [];
      }
      const id = (event as Record<string, unknown>).id;
      return typeof id === "string" && id.length > 0 ? [id] : [];
    }),
  );
}

function operationalEntityAfterObraReferenceRepair(
  entity: OperationalEventRecord["principalEntity"],
  obra: ObraLocalRecord,
): OperationalEventRecord["principalEntity"] {
  if (entity.tipo !== "OBRA") {
    return entity;
  }

  return {
    ...entity,
    id: obra.id,
    nome: obra.nome,
  };
}

function operationalPayloadAfterObraReferenceRepair(
  payload: Record<string, unknown>,
  obra: ObraLocalRecord,
): Record<string, unknown> {
  return {
    ...payload,
    origemId:
      payload.origemTipo === "OBRA" ? obra.id : payload.origemId,
    destinoId:
      payload.destinoTipo === "OBRA" ? obra.id : payload.destinoId,
  };
}

async function updateRdoOperationalEventsObraReference(
  transaction: OperationalEventSyncTransaction,
  rdoId: string,
  obra: ObraLocalRecord,
): Promise<void> {
  const store = transaction.objectStore("operational_events");
  const records = await store
    .index("by-rdo-id")
    .getAll(rdoId);

  await Promise.all(
    records.map((record) =>
      store.put({
        ...record,
        obraId: obra.id,
        principalEntity:
          operationalEntityAfterObraReferenceRepair(
            record.principalEntity,
            obra,
          ),
        relatedEntities: record.relatedEntities.map((entity) =>
          operationalEntityAfterObraReferenceRepair(entity, obra),
        ),
        payload: operationalPayloadAfterObraReferenceRepair(
          record.payload,
          obra,
        ),
        syncStatus: "PENDING_SYNC",
      }),
    ),
  );
}

function operationalEventAfterMaoObraReferenceRepair(
  record: OperationalEventRecord,
): OperationalEventRecord | null {
  const nomeColaborador = textValue(record.payload.nomeColaborador);

  if (
    record.type !== "COLABORADOR_ASSOCIADO_RDO" ||
    !textValue(record.colaboradorId) ||
    !nomeColaborador
  ) {
    return null;
  }

  const localId =
    textValue(record.payload.localId) || record.principalEntity.id;

  return {
    ...record,
    colaboradorId: null,
    principalEntity: {
      ...record.principalEntity,
      tipo: "RDO_MAO_OBRA",
      id: localId,
      nome: record.principalEntity.nome ?? nomeColaborador,
    },
    syncStatus: "PENDING_SYNC",
  };
}

async function updateRdoOperationalEventsMaoObraReference(
  transaction: OperationalEventSyncTransaction,
  rdoId: string,
): Promise<void> {
  const store = transaction.objectStore("operational_events");
  const records = await store
    .index("by-rdo-id")
    .getAll(rdoId);

  await Promise.all(
    records.map((record) => {
      const repaired =
        operationalEventAfterMaoObraReferenceRepair(record);

      return repaired ? store.put(repaired) : Promise.resolve(0);
    }),
  );
}

async function updateRdoMaoObraColaboradorReference(
  transaction: RdoChildSyncTransaction,
  rdoId: string,
  timestamp: string,
): Promise<void> {
  const store = transaction.objectStore("rdoMaoObra");
  const records = await store.index("by-rdo-id").getAll(rdoId);

  await Promise.all(
    records.map((record) => {
      const payload =
        payloadAfterMaoObraReferenceRepair({
          maoObra: [record.payload],
        })?.maoObra;
      const repairedPayload = Array.isArray(payload)
        ? objectValue(payload[0])
        : null;

      if (!repairedPayload) {
        return Promise.resolve(0);
      }

      return store.put({
        ...record,
        payload: repairedPayload,
        syncStatus: "PENDING_SYNC",
        updatedAt: timestamp,
      });
    }),
  );
}

async function updateRdoAttachmentsSyncStatus(
  transaction: RdoAttachmentSyncTransaction,
  rdoId: string,
  syncStatus: RdoAttachmentRecord["syncStatus"],
  timestamp: string,
): Promise<void> {
  const store = transaction.objectStore("rdo_attachments");

  const records = await store.index("by-rdo-id").getAll(rdoId);

  await Promise.all(
    records
      .filter((record) => record.syncStatus !== "SYNCED")
      .map((record) =>
        store.put({
          ...record,
          syncStatus,
          updatedAt: timestamp,
        }),
      ),
  );
}

async function updateRdoAttachmentsObraReference(
  transaction: RdoAttachmentSyncTransaction,
  rdoId: string,
  obra: ObraLocalRecord,
  timestamp: string,
): Promise<void> {
  const store = transaction.objectStore("rdo_attachments");
  const records = await store
    .index("by-rdo-id")
    .getAll(rdoId);

  await Promise.all(
    records.map((record) =>
      store.put({
        ...record,
        obraId: obra.id,
        syncStatus: "PENDING_SYNC",
        updatedAt: timestamp,
      }),
    ),
  );
}

export async function recoverInterruptedMutations(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<void> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);

  const guardedTransaction = guardSyncTransaction(
    database.transaction(RDO_SYNC_TRANSACTION_STORES, "readwrite"),
    guard,
  );
  const transaction = guardedTransaction.transaction;

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");

  const syncingMutations =
    await outboxStore.index("by-status").getAll("SYNCING");

  for (const mutation of syncingMutations) {
    const timestamp = nowUtc();
    const updatedMutation: OutboxMutationRecord = {
      ...mutation,
      status: "PENDING",
      nextAttemptAt: null,
      lastSafeCode: isCanonicalOutboxMutation(mutation)
        ? "INTERRUPTED_RUN"
        : mutation.lastSafeCode,
      ultimoErro:
        "Sincronização anterior foi interrompida antes da confirmação.",
      updatedAt: timestamp,
    };

    await outboxStore.put(updatedMutation);
    await updateCatalogMutationSyncStatus(
      transaction,
      mutation,
      "PENDING_SYNC",
      timestamp,
      updatedMutation.ultimoErro,
    );
    if (isCanonicalOutboxMutation(mutation)) {
      const event = await exactCanonicalEvent(
        transaction,
        mutation.clientMutationId,
      );
      await putCanonicalEvent(transaction, {
        ...event,
        result: "PENDING",
        syncStatus: "PENDING_SYNC",
        errorCategory: "INTERRUPTED_RUN",
      });
    }

    const rdo = await rdoStore.get(mutation.entidadeId);

    if (rdo) {
      await rdoStore.put({
        ...rdo,
        syncStatus: "PENDING_SYNC",
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
      if (!isCanonicalOutboxMutation(mutation)) {
        await updateRdoOperationalEventsSyncStatus(
          transaction,
          mutation.entidadeId,
          "PENDING_SYNC",
          timestamp,
        );
      }
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
    }

    if (isTaskMutation(mutation)) {
      const task = (await taskStore.get(
        mutation.entidadeId,
      )) as ConvergentTarefaRecord | undefined;

      if (task) {
        const recoveredTask: ConvergentTarefaRecord = {
          ...task,
          syncStatus: await taskSyncStatusFromOutbox(
            outboxStore,
            mutation.entidadeId,
          ),
          updatedAt: timestamp,
        };
        await taskStore.put(recoveredTask);
      }
    }

    if (mutation.entidadeTipo === "MENSAGEM") {
      const messageStore = transaction.objectStore("mensagens");
      const message = await messageStore.get(mutation.entidadeId);
      if (message) {
        await messageStore.put({
          ...message,
          syncStatus: "NA_FILA",
          ultimoErro: updatedMutation.ultimoErro,
          updatedAt: timestamp,
        });
      }
    }
  }

  await guardedTransaction.complete();
}

export async function repairMissingObraReferencesForSync(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const obras = await database.getAll("obras");
  assertSyncSession(guard);

  if (obras.length === 0) {
    return 0;
  }

  const timestamp = nowUtc();
  const guardedTransaction = guardSyncTransaction(
    database.transaction(
      RDO_SYNC_TRANSACTION_STORES,
      "readwrite",
    ),
    guard,
  );
  const transaction = guardedTransaction.transaction;
  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const candidates = [
    ...(await outboxStore
      .index("by-status")
      .getAll("ERROR")),
    ...(await outboxStore
      .index("by-status")
      .getAll("PENDING")),
  ];
  let repaired = 0;

  for (const candidate of candidates) {
    const mutation = await outboxStore.get(
      candidate.clientMutationId,
    );
    if (!mutation) {
      continue;
    }
    const repairedMutation = mutationAfterObraReferenceRepair(
      mutation,
      obras,
      timestamp,
    );

    if (!repairedMutation) {
      continue;
    }

    const repairedObraId = textValue(
      repairedMutation.payload.obraId,
    );
    const repairedObra = obras.find(
      (obra) => obra.id === repairedObraId,
    );

    if (!repairedObra) {
      continue;
    }

    if (
      repairedMutation.clientMutationId !== mutation.clientMutationId
    ) {
      await outboxStore.delete(mutation.clientMutationId);
      await outboxStore.add(repairedMutation);
      await rewirePendingLegacyRdoDependents(
        outboxStore,
        mutation,
        repairedMutation,
        timestamp,
      );
    } else {
      await outboxStore.put(repairedMutation);
    }

    const rdo = await rdoStore.get(mutation.entidadeId);

    if (rdo) {
      await rdoStore.put(
        rdoAfterObraReferenceRepair(
          rdo,
          repairedObra,
          timestamp,
        ),
      );

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
      await updateRdoOperationalEventsObraReference(
        transaction,
        mutation.entidadeId,
        repairedObra,
      );
      await updateRdoAttachmentsObraReference(
        transaction,
        mutation.entidadeId,
        repairedObra,
        timestamp,
      );
    }

    repaired += 1;
  }

  await guardedTransaction.complete();

  return repaired;
}

export async function repairMissingMaoObraReferencesForSync(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const timestamp = nowUtc();
  const guardedTransaction = guardSyncTransaction(
    database.transaction(
      RDO_SYNC_TRANSACTION_STORES,
      "readwrite",
    ),
    guard,
  );
  const transaction = guardedTransaction.transaction;
  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const candidates = [
    ...(await outboxStore
      .index("by-status")
      .getAll("ERROR")),
    ...(await outboxStore
      .index("by-status")
      .getAll("PENDING")),
  ];
  let repaired = 0;

  for (const candidate of candidates) {
    const mutation = await outboxStore.get(
      candidate.clientMutationId,
    );
    if (!mutation) {
      continue;
    }
    const repairedMutation = mutationAfterMaoObraReferenceRepair(
      mutation,
      timestamp,
    );

    if (!repairedMutation) {
      continue;
    }

    if (
      repairedMutation.clientMutationId !== mutation.clientMutationId
    ) {
      await outboxStore.delete(mutation.clientMutationId);
      await outboxStore.add(repairedMutation);
      await rewirePendingLegacyRdoDependents(
        outboxStore,
        mutation,
        repairedMutation,
        timestamp,
      );
    } else {
      await outboxStore.put(repairedMutation);
    }

    const rdo = await rdoStore.get(mutation.entidadeId);
    const repairedRdo = rdo
      ? rdoAfterMaoObraReferenceRepair(rdo, timestamp)
      : null;

    if (repairedRdo) {
      await rdoStore.put(repairedRdo);
      await updateRdoMaoObraColaboradorReference(
        transaction,
        mutation.entidadeId,
        timestamp,
      );
      await updateRdoOperationalEventsMaoObraReference(
        transaction,
        mutation.entidadeId,
      );
    }

    repaired += 1;
  }

  await guardedTransaction.complete();

  return repaired;
}

export async function queueErroredMutationsForRetry(): Promise<number> {
  const database = await getCortexDb();
  const timestamp = nowUtc();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");

  const erroredMutations =
    await outboxStore.index("by-status").getAll("ERROR");
  let queued = 0;

  for (const mutation of erroredMutations) {
    const retryMutation = mutationAfterErroredRetry(
      mutation,
      timestamp,
    );

    if (!retryMutation) {
      continue;
    }

    await outboxStore.put(retryMutation);
    queued += 1;

    const rdo = await rdoStore.get(mutation.entidadeId);

    if (rdo) {
      await rdoStore.put({
        ...rdo,
        syncStatus: "PENDING_SYNC",
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
    }

    if (mutation.entidadeTipo === "MENSAGEM") {
      const messageStore = transaction.objectStore("mensagens");
      const message = await messageStore.get(mutation.entidadeId);
      if (message) {
        await messageStore.put({
          ...message,
          syncStatus: "NA_FILA",
          ultimoErro: retryMutation.ultimoErro,
          updatedAt: timestamp,
        });
      }
    }

    if (isTaskMutation(mutation)) {
      const task = await taskStore.get(mutation.entidadeId);
      if (task) {
        await taskStore.put({
          ...task,
          syncStatus: await taskSyncStatusFromOutbox(
            outboxStore,
            mutation.entidadeId,
          ),
          updatedAt: timestamp,
        });
      }
    }
  }

  await transaction.done;

  return queued;
}

export function mutationAfterObraReferenceRepair(
  mutation: OutboxMutationRecord,
  obras: ObraLocalRecord[],
  timestamp: string,
  clientMutationId = crypto.randomUUID(),
): OutboxMutationRecord | null {
  if (isCanonicalOutboxMutation(mutation)) {
    return null;
  }
  if (
    mutation.entidadeTipo !== "RDO" ||
    isDefinitelyNonAppliedSuperseded(mutation) ||
    (mutation.status !== "ERROR" && mutation.status !== "PENDING")
  ) {
    return null;
  }

  const payload = objectValue(mutation.payload);
  const obraId = textValue(payload.obraId);
  const knownObra = obras.some((obra) => obra.id === obraId);

  if (knownObra && !erroIndicaObraAusente(mutation.ultimoErro)) {
    return null;
  }

  const resolvedObra = resolveObraForRdoPayload(payload, obras);

  if (!resolvedObra || resolvedObra.id === obraId) {
    return null;
  }

  const shouldCreate =
    mutation.operacao === "ATUALIZAR_RDO_RASCUNHO" &&
    mutation.baseVersao === 0;
  const originalContrato = textValue(payload.contrato);
  const repairMessage = originalContrato
    ? `Obra reidentificada antes da sincronização: ${originalContrato} -> ${resolvedObra.codigoContrato || resolvedObra.nome}.`
    : `Obra reidentificada antes da sincronização: ${obraId} -> ${resolvedObra.id}.`;

  return {
    ...mutation,
    clientMutationId:
      mutation.status === "ERROR"
        ? clientMutationId
        : mutation.clientMutationId,
    operacao: shouldCreate ? "CRIAR_RDO" : mutation.operacao,
    baseVersao: shouldCreate ? null : mutation.baseVersao,
    payload: payloadAfterObraReferenceRepair(payload, resolvedObra),
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: repairMessage,
    conflito: null,
    criadaNoClienteEm:
      mutation.status === "ERROR"
        ? timestamp
        : mutation.criadaNoClienteEm,
    updatedAt: timestamp,
  };
}

export function mutationAfterMaoObraReferenceRepair(
  mutation: OutboxMutationRecord,
  timestamp: string,
  clientMutationId = crypto.randomUUID(),
): OutboxMutationRecord | null {
  if (isCanonicalOutboxMutation(mutation)) {
    return null;
  }
  if (
    mutation.entidadeTipo !== "RDO" ||
    isDefinitelyNonAppliedSuperseded(mutation) ||
    (mutation.status !== "ERROR" && mutation.status !== "PENDING") ||
    !erroIndicaMaoObraColaboradorAusente(mutation.ultimoErro)
  ) {
    return null;
  }

  const payload = payloadAfterMaoObraReferenceRepair(
    objectValue(mutation.payload),
  );

  if (!payload) {
    return null;
  }

  return {
    ...mutation,
    clientMutationId:
      mutation.status === "ERROR"
        ? clientMutationId
        : mutation.clientMutationId,
    payload,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro:
      "Mão de obra preservada por nome; ID de colaborador legado removido antes da sincronização.",
    conflito: null,
    criadaNoClienteEm:
      mutation.status === "ERROR"
        ? timestamp
        : mutation.criadaNoClienteEm,
    updatedAt: timestamp,
  };
}

export function rdoAfterObraReferenceRepair(
  rdo: LocalRdoRecord,
  obra: ObraLocalRecord,
  timestamp: string,
): LocalRdoRecord {
  return {
    ...rdo,
    obraId: obra.id,
    programacaoId: null,
    syncStatus: "PENDING_SYNC",
    payload: payloadAfterObraReferenceRepair(rdo.payload, obra),
    updatedAt: timestamp,
  };
}

export function rdoAfterMaoObraReferenceRepair(
  rdo: LocalRdoRecord,
  timestamp: string,
): LocalRdoRecord | null {
  const payload = payloadAfterMaoObraReferenceRepair(rdo.payload);

  if (!payload) {
    return null;
  }

  return {
    ...rdo,
    syncStatus: "PENDING_SYNC",
    payload,
    updatedAt: timestamp,
  };
}

function erroIndicaRdoAusente(error: string | null): boolean {
  if (!error) {
    return false;
  }

  const normalized = error
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase();

  return (
    normalized.includes("rdo") &&
    normalized.includes("nao encontrado")
  );
}

export function mutationAfterErroredRetry(
  mutation: OutboxMutationRecord,
  timestamp: string,
): OutboxMutationRecord | null {
  if (isCanonicalOutboxMutation(mutation)) {
    return null;
  }
  if (isDefinitelyNonAppliedSuperseded(mutation)) {
    return null;
  }
  const serverMissingRdo =
    mutation.status === "ERROR" &&
    mutation.operacao === "ATUALIZAR_RDO_RASCUNHO" &&
    mutation.baseVersao === 0 &&
    erroIndicaRdoAusente(mutation.ultimoErro);

  return {
    ...mutation,
    operacao: serverMissingRdo ? "CRIAR_RDO" : mutation.operacao,
    baseVersao: serverMissingRdo ? null : mutation.baseVersao,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: serverMissingRdo
      ? "Reenviando como criação porque o servidor não possui este RDO."
      : "Tentando novamente após correção da sincronização.",
    conflito: null,
    updatedAt: timestamp,
  };
}

function conflictServerVersion(
  mutation: OutboxMutationRecord,
): number | null {
  const version = mutation.conflito?.versaoAtual;

  if (typeof version === "number" && Number.isFinite(version)) {
    return version;
  }

  return null;
}

export function mutationAfterResolvableConflict(
  mutation: OutboxMutationRecord,
  timestamp: string,
  clientMutationId = crypto.randomUUID(),
): OutboxMutationRecord | null {
  if (isCanonicalOutboxMutation(mutation)) {
    return null;
  }
  if (mutation.status !== "CONFLICT") {
    return null;
  }

  // Um RDO representa um registro operacional completo. Atualizar somente a
  // versão-base e reenviá-lo pode sobrescrever alterações feitas por outra
  // pessoa; esses conflitos precisam de reconciliação explícita na interface.
  if (mutation.entidadeTipo === "RDO") {
    return null;
  }

  const serverVersion = conflictServerVersion(mutation);
  if (serverVersion === null) {
    return null;
  }

  return {
    ...mutation,
    clientMutationId,
    baseVersao: serverVersion,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro:
      "Reenviando após atualizar a versão base do servidor.",
    conflito: null,
    criadaNoClienteEm: timestamp,
    updatedAt: timestamp,
  };
}

export async function queueResolvableConflictsForRetry(): Promise<number> {
  const database = await getCortexDb();
  const timestamp = nowUtc();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");

  const conflictedMutations =
    await outboxStore.index("by-status").getAll("CONFLICT");

  let queued = 0;

  for (const mutation of conflictedMutations) {
    const retryMutation = mutationAfterResolvableConflict(
      mutation,
      timestamp,
    );

    if (!retryMutation) {
      continue;
    }

    await outboxStore.delete(mutation.clientMutationId);
    await outboxStore.add(retryMutation);

    const rdo = await rdoStore.get(mutation.entidadeId);

    if (rdo) {
      await rdoStore.put({
        ...rdo,
        syncStatus: "PENDING_SYNC",
        versaoEntidade: retryMutation.baseVersao,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
    }

    queued += 1;
  }

  await transaction.done;

  return queued;
}

export async function markMutationAsSyncing(
  mutation: OutboxMutationRecord,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<OutboxMutationRecord> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);

  const guardedTransaction = guardSyncTransaction(
    database.transaction(RDO_SYNC_TRANSACTION_STORES, "readwrite"),
    guard,
  );
  const transaction = guardedTransaction.transaction;

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");

  const currentMutation = await outboxStore.get(
    mutation.clientMutationId,
  );

  if (!currentMutation) {
    transaction.abort();

    throw new Error(
      `Mutação ${mutation.clientMutationId} não encontrada.`,
    );
  }

  if (currentMutation.status !== "PENDING") {
    transaction.abort();

    throw new Error(
      `Mutação ${mutation.clientMutationId} não está pendente.`,
    );
  }

  const timestamp = nowUtc();
  const syncingMutation: OutboxMutationRecord = {
    ...currentMutation,
    status: "SYNCING",
    tentativas: currentMutation.tentativas + 1,
    ultimaTentativaEm: timestamp,
    nextAttemptAt: null,
    ultimoErro: null,
    updatedAt: timestamp,
  };
  await outboxStore.put(syncingMutation);
  await updateCatalogMutationSyncStatus(
    transaction,
    currentMutation,
    "SYNCING",
    timestamp,
    null,
  );

  if (isCanonicalOutboxMutation(currentMutation)) {
    const event = await exactCanonicalEvent(
      transaction,
      currentMutation.clientMutationId,
    );
    await putCanonicalEvent(transaction, {
      ...event,
      result: "SYNCING",
      syncStatus: "SYNCING",
      errorCategory: null,
    });
  }

  const rdo = await rdoStore.get(currentMutation.entidadeId);

  if (rdo) {
    await rdoStore.put({
      ...rdo,
      syncStatus: "SYNCING",
      updatedAt: timestamp,
    });

    await updateRdoChildrenSyncStatus(
      transaction,
      currentMutation.entidadeId,
      "SYNCING",
      timestamp,
    );
    if (!isCanonicalOutboxMutation(currentMutation)) {
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        currentMutation.entidadeId,
        "SYNCING",
        timestamp,
        mutationOperationalEventIds(currentMutation),
      );
    }
    await updateRdoAttachmentsSyncStatus(
      transaction,
      currentMutation.entidadeId,
      "SYNCING",
      timestamp,
    );
  }

  if (currentMutation.entidadeTipo === "MENSAGEM") {
    const messageStore = transaction.objectStore("mensagens");
    const message = await messageStore.get(currentMutation.entidadeId);
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: "SINCRONIZANDO",
        ultimoErro: null,
        updatedAt: timestamp,
      });
    }
  }

  if (isTaskMutation(currentMutation)) {
    const task = await taskStore.get(currentMutation.entidadeId);
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await taskSyncStatusFromOutbox(
          outboxStore,
          currentMutation.entidadeId,
        ),
        updatedAt: timestamp,
      });
    }
  }

  await guardedTransaction.complete();
  return syncingMutation;
}

/**
 * Registro local após um conflito de versão: além de marcar CONFLICT, adota
 * a versão atual informada pelo servidor no payload do conflito. Sem isso a
 * versão local fica defasada e todo reenvio da mutação conflita de novo;
 * com ela, a próxima edição do usuário coalesce a mutação com o baseVersao
 * correto e a sincronização se recupera.
 */
export function rdoAfterConflict(
  rdo: LocalRdoRecord,
  result: SyncPushMutationResult,
  timestamp: string,
): LocalRdoRecord {
  const conflito = result.conflito;
  const serverVersion =
    conflito && typeof conflito === "object"
      ? (conflito as Record<string, unknown>).versaoAtual
      : null;

  return {
    ...rdo,
    syncStatus: "CONFLICT",
    versaoEntidade:
      typeof serverVersion === "number" &&
      Number.isFinite(serverVersion)
        ? serverVersion
        : rdo.versaoEntidade,
    updatedAt: timestamp,
  };
}

export async function applyPushResultAtomically(
  result: SyncPushMutationResult,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<void> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);

  const guardedTransaction = guardSyncTransaction(
    database.transaction(RDO_SYNC_TRANSACTION_STORES, "readwrite"),
    guard,
  );
  const transaction = guardedTransaction.transaction;

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");
  const messageStore = transaction.objectStore("mensagens");

  const mutation = await outboxStore.get(
    result.clientMutationId,
  );

  if (!mutation) {
    transaction.abort();

    throw new Error(
      `Resultado recebido para mutação desconhecida: ${result.clientMutationId}.`,
    );
  }
  if (mutation.status !== "SYNCING") {
    transaction.abort();

    throw new Error(
      `A mutação ${result.clientMutationId} não está em sincronização; o resultado não corresponde a uma tentativa ativa.`,
    );
  }

  const rdo = await rdoStore.get(mutation.entidadeId);
  const message =
    mutation.entidadeTipo === "MENSAGEM"
      ? await messageStore.get(mutation.entidadeId)
      : undefined;
  const task = isTaskMutation(mutation)
    ? await taskStore.get(mutation.entidadeId)
    : undefined;
  const timestamp = nowUtc();
  const canonicalEvent = isCanonicalOutboxMutation(mutation)
    ? await exactCanonicalEvent(transaction, mutation.clientMutationId)
    : null;
  const disposition = retryDispositionForResult(result);
  const resultVersion =
    result.resultado &&
    typeof result.resultado.versaoEntidade === "number"
      ? result.resultado.versaoEntidade
      : rdo?.versaoEntidade ??
        task?.versaoEntidade ??
        canonicalEvent?.entityVersion ??
        null;

  if (result.status === "APLICADA") {
    await outboxStore.put({
      ...mutation,
      status: "SYNCED",
      retryAttempt: 0,
      nextAttemptAt: null,
      lastSafeCode: null,
      ultimoErro: null,
      conflito: null,
      blockedReason: null,
      updatedAt: timestamp,
    });
    await rebasePendingLegacyRdoDependents(
      outboxStore,
      mutation,
      resultVersion,
      timestamp,
    );
    await updateCatalogMutationSyncStatus(
      transaction,
      mutation,
      "SYNCED",
      timestamp,
      null,
      resultVersion,
    );

    if (canonicalEvent) {
      await putCanonicalEvent(transaction, {
        ...canonicalEvent,
        result: "SYNCED",
        syncStatus: "SYNCED",
        syncedAt: timestamp,
        errorCategory: null,
        entityVersion: resultVersion,
        serverCommitSequence:
          result.eventoServidorCommitSeq ?? null,
      });
    }

    if (rdo) {
      const aggregateSyncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await rdoStore.put({
        ...rdo,
        syncStatus: aggregateSyncStatus,
        versaoEntidade: resultVersion,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        aggregateSyncStatus,
        timestamp,
      );
      if (!canonicalEvent) {
        await updateRdoOperationalEventsSyncStatus(
          transaction,
          mutation.entidadeId,
          "SYNCED",
          timestamp,
          mutationOperationalEventIds(mutation),
        );
      }
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        aggregateSyncStatus === "SYNCED"
          ? "SYNCED"
          : aggregateSyncStatus === "SYNCING"
            ? "SYNCING"
            : aggregateSyncStatus === "PENDING_SYNC"
              ? "PENDING_SYNC"
              : "SYNC_FAILED",
        timestamp,
      );
    }

    if (message) {
      const resultRecord = result.resultado ?? {};
      await messageStore.put({
        ...message,
        id:
          typeof resultRecord.id === "string"
            ? resultRecord.id
            : message.id,
        criadaEm:
          typeof resultRecord.criadaEm === "string"
            ? resultRecord.criadaEm
            : message.criadaEm,
        versaoEntidade:
          typeof resultRecord.versao === "number"
            ? resultRecord.versao
            : message.versaoEntidade,
        syncStatus: "SINCRONIZADO",
        ultimoErro: null,
        updatedAt: timestamp,
      });
    }
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await taskSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        versaoEntidade: resultVersion,
        updatedAt: timestamp,
      });
    }
  } else if (
    result.status === "DESCARTADA" ||
    result.status === "CONFLITO"
  ) {
    const remote = remoteSnapshotEvidence(result.conflito);
    const fieldResolution = canonicalEvent
      ? classifyFieldConflict(
          canonicalEvent.previousState,
          canonicalEvent.newState,
          remote,
        )
      : null;
    const blockedReason = !remote.complete
      ? "REMOTE_SNAPSHOT_UNAVAILABLE"
      : fieldResolution && !fieldResolution.canAutoMerge
        ? "FIELD_CONFLICT_REQUIRES_REVIEW"
        : null;
    const conflict = canonicalEvent
      ? {
          ...objectValue(result.conflito),
          remoteSnapshotComplete: remote.complete,
          fieldConflicts: fieldResolution?.conflicts ?? [],
        }
      : result.conflito ?? null;
    await outboxStore.put({
      ...mutation,
      status: "CONFLICT",
      nextAttemptAt: null,
      lastSafeCode: disposition.safeCode,
      blockedReason,
      ultimoErro:
        result.erro ?? "Conflito informado pelo servidor.",
      conflito: conflict,
      updatedAt: timestamp,
    });
    await updateCatalogMutationSyncStatus(
      transaction,
      mutation,
      "CONFLICT",
      timestamp,
      result.erro ?? "Conflito informado pelo servidor.",
      resultVersion,
    );

    if (canonicalEvent) {
      await putCanonicalEvent(transaction, {
        ...canonicalEvent,
        result: "CONFLICT",
        syncStatus: "SYNC_FAILED",
        errorCategory: disposition.safeCode,
      });
    }

    if (rdo) {
      await rdoStore.put(
        rdoAfterConflict(rdo, result, timestamp),
      );

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "CONFLICT",
        timestamp,
      );
      if (!canonicalEvent) {
        await updateRdoOperationalEventsSyncStatus(
          transaction,
          mutation.entidadeId,
          "SYNC_FAILED",
          timestamp,
        );
      }
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNC_FAILED",
        timestamp,
      );
    }
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: "FALHOU",
        ultimoErro:
          result.erro ?? "Conflito informado pelo servidor.",
        updatedAt: timestamp,
      });
    }
    if (task) {
      const conflictVersion =
        result.conflito && typeof result.conflito === "object"
          ? (result.conflito as Record<string, unknown>).versaoAtual
          : null;
      await taskStore.put({
        ...task,
        syncStatus: await taskSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        versaoEntidade:
          typeof conflictVersion === "number" &&
            Number.isFinite(conflictVersion)
            ? conflictVersion
            : task.versaoEntidade,
        updatedAt: timestamp,
      });
    }
  } else {
    const messageText = result.erro ?? "Erro informado pelo servidor.";
    const updatedMutation: OutboxMutationRecord =
      isCanonicalOutboxMutation(mutation) && disposition.retryable
        ? mutationAfterRetryScheduled(mutation, {
            safeCode: disposition.safeCode,
            message: messageText,
            now: Date.parse(timestamp),
          })
        : {
            ...mutation,
            status:
              result.status === "REJEITADA" ? "REJECTED" : "ERROR",
            nextAttemptAt: null,
            lastSafeCode: disposition.safeCode,
            ultimoErro: messageText,
            conflito: result.conflito ?? null,
            updatedAt: timestamp,
          };
    await outboxStore.put(updatedMutation);
    const releasedNonAppliedRdoChain =
      (
        result.status === "REJEITADA" ||
        result.status === "ERRO"
      ) &&
      await releaseDefinitelyNonAppliedLegacyRdoDependents(
        outboxStore,
        updatedMutation,
        timestamp,
      );
    await updateCatalogMutationSyncStatus(
      transaction,
      mutation,
      disposition.retryable ? "PENDING_SYNC" : "ERROR",
      timestamp,
      messageText,
      resultVersion,
    );

    if (canonicalEvent) {
      await putCanonicalEvent(transaction, {
        ...canonicalEvent,
        result: disposition.retryable ? "PENDING" : "REJECTED",
        syncStatus: disposition.retryable
          ? "PENDING_SYNC"
          : "SYNC_FAILED",
        errorCategory: disposition.safeCode,
      });
    }

    if (rdo) {
      const aggregateSyncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await rdoStore.put({
        ...rdo,
        syncStatus: aggregateSyncStatus,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        aggregateSyncStatus,
        timestamp,
      );
      if (!canonicalEvent) {
        await updateRdoOperationalEventsSyncStatus(
          transaction,
          mutation.entidadeId,
          disposition.retryable && !releasedNonAppliedRdoChain
            ? "PENDING_SYNC"
            : "SYNC_FAILED",
          timestamp,
          mutationOperationalEventIds(mutation),
        );
      }
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        aggregateSyncStatus === "SYNCED"
          ? "SYNCED"
          : aggregateSyncStatus === "SYNCING"
            ? "SYNCING"
            : aggregateSyncStatus === "PENDING_SYNC"
              ? "PENDING_SYNC"
              : "SYNC_FAILED",
        timestamp,
      );
    }
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: disposition.retryable ? "NA_FILA" : "FALHOU",
        ultimoErro:
          result.erro ?? "Erro informado pelo servidor.",
        updatedAt: timestamp,
      });
    }
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await taskSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        updatedAt: timestamp,
      });
    }
  }

  await guardedTransaction.complete();
}

/**
 * A canonical conflict is never rewritten. When the server provides a full
 * remote snapshot and the three-way merge is disjoint, this creates a new
 * v13 envelope whose causation points at the terminal original.
 */
export async function reconcileCanonicalConflict(
  clientMutationId: string,
  replacementMutationId: string = crypto.randomUUID(),
  replacementEventId: string = crypto.randomUUID(),
  occurredAt: string = nowUtc(),
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<CanonicalOutboxMutationRecord | null> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const original = await database.get(
    "outbox_mutations",
    clientMutationId,
  );
  if (
    !original ||
    !isCanonicalOutboxMutation(original) ||
    original.status !== "CONFLICT"
  ) {
    return null;
  }
  // Task transitions can already have newer dependent local edits. Generic
  // three-way replacement would write the older snapshot over that chain, so
  // task conflicts remain explicit review items instead of auto-merging.
  if (original.entityType === "TAREFA") {
    return null;
  }
  const existingReplacement = (await database.getAll("outbox_mutations"))
    .find(
      (candidate): candidate is CanonicalOutboxMutationRecord =>
        isCanonicalOutboxMutation(candidate) &&
        candidate.causationId === original.clientMutationId,
    );
  if (existingReplacement) {
    return existingReplacement;
  }
  const events = await database.getAllFromIndex(
    "operational_events",
    "by-client-mutation-id",
    clientMutationId,
  );
  if (events.length !== 1 || events[0].schemaVersion !== 13) {
    throw new Error(
      `Mutação canônica ${clientMutationId} não possui evento local único.`,
    );
  }
  const originalEvent = events[0] as CanonicalOperationalEventRecord;
  const remote = remoteSnapshotEvidence(original.conflito);
  const resolution = classifyFieldConflict(
    originalEvent.previousState,
    originalEvent.newState,
    remote,
  );
  const serverVersion = original.conflito?.versaoAtual;
  if (
    !remote.complete ||
    !remote.snapshot ||
    !resolution.canAutoMerge ||
    !Number.isSafeInteger(serverVersion) ||
    (serverVersion as number) < 0 ||
    original.operation === "CREATE"
  ) {
    return null;
  }

  const nextSnapshot = replacementDomainSnapshot(
    original,
    resolution.merged,
    serverVersion as number,
    occurredAt,
  );
  const built = await buildCanonicalMutation({
    clientMutationId: replacementMutationId,
    ontologyEventId: replacementEventId,
    deviceId: original.deviceId,
    userId: original.userId,
    obraId: original.obraId,
    entityType: original.entityType,
    entityId: original.entityId,
    operation: original.operation,
    transportOperation: original.operacao,
    baseVersion: serverVersion as number,
    occurredAt,
    previousSnapshot: remote.snapshot,
    nextSnapshot,
    authorizationScope: original.trace.authorizationScope,
    correlationId: original.correlationId,
    causationId: original.clientMutationId,
    transport: original.transport,
    dependsOnMutationIds: original.dependsOnMutationIds,
    relatedEntities: original.relatedEntities,
  });
  assertSyncSession(guard);

  const replacementEvent: CanonicalOperationalEventRecord = {
    ...originalEvent,
    id: built.mutation.trace.ontologyEventId,
    occurredAt: built.mutation.occurredAt,
    syncedAt: null,
    origin: "OFFLINE",
    payload: built.nextSnapshot,
    syncStatus: "PENDING_SYNC",
    clientMutationId: built.mutation.clientMutationId,
    deviceId: built.mutation.deviceId,
    correlationId: built.mutation.correlationId,
    causationId: built.mutation.causationId,
    previousState: built.previousSnapshot,
    newState: built.nextSnapshot,
    result: "PENDING",
    errorCategory: null,
    entityVersion: built.mutation.baseVersion,
    serverCommitSequence: null,
  };
  const originalSnapshot = canonicalMutationJson(original);
  const guardedTransaction = guardSyncTransaction(
    database.transaction(
      [
        "outbox_mutations",
        "operational_events",
        "rdos",
        "mensagens",
        "mensagem_conversas",
        "mensagem_anexos",
        "service_catalog",
        "service_price_versions",
      ],
      "readwrite",
    ),
    guard,
  );
  const transaction = guardedTransaction.transaction;
  const current = await transaction
    .objectStore("outbox_mutations")
    .get(clientMutationId);
  if (!current || canonicalMutationJson(current) !== originalSnapshot) {
    transaction.abort();
    throw new Error("O conflito mudou durante a reconciliação.");
  }
  const concurrentReplacement = (
    await transaction.objectStore("outbox_mutations").getAll()
  ).find(
    (candidate): candidate is CanonicalOutboxMutationRecord =>
      isCanonicalOutboxMutation(candidate) &&
      candidate.causationId === original.clientMutationId,
  );
  if (concurrentReplacement) {
    await guardedTransaction.complete();
    return concurrentReplacement;
  }
  await transaction.objectStore("outbox_mutations").add(built.mutation);
  await transaction
    .objectStore("operational_events")
    .add(replacementEvent);
  await transaction
    .objectStore(principalStoreFor(original.entityType))
    .put(nextSnapshot as never);
  await guardedTransaction.complete();
  if (typeof window !== "undefined") {
    window.dispatchEvent(
      new Event("cortex:local-mutation-queued"),
    );
  }
  return built.mutation;
}

export interface CanonicalConflictRecoveryOptions {
  replacementMutationId?: () => string;
  replacementEventId?: () => string;
  occurredAt?: () => string;
}

/**
 * A complete terminal conflict is itself the durable reconciliation marker.
 * Scanning it on every automatic run closes the crash window between result
 * persistence and creation of the causally linked replacement.
 */
export async function recoverCanonicalConflictReconciliations(
  guard?: SyncSessionGuard,
  options: CanonicalConflictRecoveryOptions = {},
): Promise<number> {
  const expectedGuard = guard ?? captureOnlineSyncSession();
  assertSyncSession(expectedGuard);
  const database = await getCortexDb();
  assertSyncSession(expectedGuard);
  const all = await database.getAll("outbox_mutations");
  const replacementCauses = new Set(
    all
      .filter(isCanonicalOutboxMutation)
      .map((candidate) => candidate.causationId)
      .filter((value): value is string => typeof value === "string"),
  );
  let created = 0;

  for (const original of all) {
    if (
      !isCanonicalOutboxMutation(original) ||
      original.status !== "CONFLICT" ||
      replacementCauses.has(original.clientMutationId)
    ) {
      continue;
    }
    assertSyncSession(expectedGuard);
    const replacement = await reconcileCanonicalConflict(
      original.clientMutationId,
      options.replacementMutationId?.() ?? crypto.randomUUID(),
      options.replacementEventId?.() ?? crypto.randomUUID(),
      options.occurredAt?.() ?? nowUtc(),
      expectedGuard,
    );
    if (replacement?.causationId === original.clientMutationId) {
      replacementCauses.add(original.clientMutationId);
      created += 1;
    }
  }

  return created;
}

export interface CanonicalUploadReplacementOptions {
  replacementMutationId?: () => string;
  replacementEventId?: () => string;
  occurredAt?: () => string;
}

export async function resolveCanonicalUploadReplacements(
  guard?: SyncSessionGuard,
  options: CanonicalUploadReplacementOptions = {},
): Promise<number> {
  const expectedGuard = guard ?? captureOnlineSyncSession();
  assertSyncSession(expectedGuard);
  const database = await getCortexDb();
  assertSyncSession(expectedGuard);
  const all = await database.getAll("outbox_mutations");
  let created = 0;

  for (const original of all) {
    if (
      !isCanonicalOutboxMutation(original) ||
      original.status !== "PENDING" ||
      original.blockedReason !==
        "CANONICAL_UPLOAD_REFERENCE_REQUIRES_REPLACEMENT" ||
      all.some(
        (candidate) =>
          isCanonicalOutboxMutation(candidate) &&
          candidate.causationId === original.clientMutationId,
      )
    ) {
      continue;
    }
    assertSyncSession(expectedGuard);
    try {
      const resolvedObjects = new Map<
      string,
      { objectId: string; sha256: string }
      >();
      for (const dependencyId of original.dependsOnMutationIds ?? []) {
        const attachment = await database.getFromIndex(
          "mensagem_anexos",
          "by-upload-mutation-id",
          dependencyId,
        );
        if (
          attachment?.syncStatus === "SINCRONIZADO" &&
          typeof attachment.objetoId === "string" &&
          attachment.objetoId &&
          typeof attachment.sha256 === "string" &&
          attachment.sha256
        ) {
          resolvedObjects.set(dependencyId, {
            objectId: attachment.objetoId,
            sha256: attachment.sha256,
          });
        }
      }
      if (
        resolvedObjects.size !==
        (original.dependsOnMutationIds ?? []).length
      ) {
        continue;
      }
      const events = await database.getAllFromIndex(
        "operational_events",
        "by-client-mutation-id",
        original.clientMutationId,
      );
      if (events.length !== 1 || events[0].schemaVersion !== 13) {
        throw new TypeError(
          "A mutação de anexo não possui evento local único.",
        );
      }
      const originalEvent = events[0] as CanonicalOperationalEventRecord;
      await assertCanonicalMutationEventProvenance(original, originalEvent);
      const occurredAt = (options.occurredAt ?? nowUtc)();
      const nextSnapshot = resolveCanonicalUploadReferences(
        original.payload,
        resolvedObjects,
      );
      const replacementMutationId = options.replacementMutationId?.() ??
        crypto.randomUUID();
      const replacementEventId = options.replacementEventId?.() ??
        crypto.randomUUID();
      const built = await buildCanonicalMutation({
      clientMutationId: replacementMutationId,
      ontologyEventId: replacementEventId,
      deviceId: original.deviceId,
      userId: original.userId,
      obraId: original.obraId,
      entityType: original.entityType,
      entityId: original.entityId,
      operation: original.operation,
      transportOperation: original.operacao,
      baseVersion: original.baseVersion,
      occurredAt,
      previousSnapshot: original.payload,
      nextSnapshot,
      authorizationScope: original.trace.authorizationScope,
      correlationId: original.correlationId,
      causationId: original.clientMutationId,
      transport: original.transport,
      dependsOnMutationIds: [],
      relatedEntities: original.relatedEntities,
      });
      assertSyncSession(expectedGuard);
      const replacementEvent: CanonicalOperationalEventRecord = {
      ...originalEvent,
      id: built.mutation.trace.ontologyEventId,
      occurredAt: built.mutation.occurredAt,
      syncedAt: null,
      origin: "OFFLINE",
      payload: built.nextSnapshot,
      syncStatus: "PENDING_SYNC",
      clientMutationId: built.mutation.clientMutationId,
      deviceId: built.mutation.deviceId,
      correlationId: built.mutation.correlationId,
      causationId: built.mutation.causationId,
      previousState: built.previousSnapshot,
      newState: built.nextSnapshot,
      result: "PENDING",
      errorCategory: null,
      entityVersion: built.mutation.baseVersion,
      serverCommitSequence: null,
      };
      const originalSnapshot = canonicalMutationJson(original);
      const guardedTransaction = guardSyncTransaction(
        database.transaction(
          [
            "outbox_mutations",
            "operational_events",
            "rdos",
            "mensagens",
            "mensagem_conversas",
            "mensagem_anexos",
            "service_catalog",
            "service_price_versions",
          ],
          "readwrite",
        ),
        expectedGuard,
      );
      const transaction = guardedTransaction.transaction;
      const current = await transaction
        .objectStore("outbox_mutations")
        .get(original.clientMutationId);
      if (!current || canonicalMutationJson(current) !== originalSnapshot) {
        transaction.abort();
        throw new Error(
          "A dependência canônica mudou durante a resolução do upload.",
        );
      }
      await transaction.objectStore("outbox_mutations").put({
        ...original,
        status: "REJECTED",
        nextAttemptAt: null,
        lastSafeCode: "SUPERSEDED_BY_REPLACEMENT",
        blockedReason: `SUPERSEDED_BY:${built.mutation.clientMutationId}`,
        ultimoErro:
          "Envelope substituído após a resolução rastreável do upload.",
        updatedAt: occurredAt,
      });
      await transaction.objectStore("operational_events").put({
        ...originalEvent,
        result: "REJECTED",
        syncStatus: "SYNC_FAILED",
        errorCategory: "SUPERSEDED_BY_REPLACEMENT",
      });
      await transaction
        .objectStore("outbox_mutations")
        .add(built.mutation);
      await transaction
        .objectStore("operational_events")
        .add(replacementEvent);
      await transaction
        .objectStore(principalStoreFor(original.entityType))
        .put(nextSnapshot as never);
      await guardedTransaction.complete();
      created += 1;
    } catch (error: unknown) {
      assertSyncSession(expectedGuard);
      await rejectMutationLocally(
        original.clientMutationId,
        "LOCAL_CANONICAL_UPLOAD_INVALID",
        error instanceof Error
          ? error.message
          : "A mutação de upload canônica é inválida.",
        expectedGuard,
      );
    }
  }

  if (created > 0 && typeof window !== "undefined") {
    window.dispatchEvent(new Event("cortex:local-mutation-queued"));
  }
  return created;
}

function resolveCanonicalUploadReferences(
  payload: Readonly<Record<string, unknown>>,
  resolved: ReadonlyMap<string, { objectId: string; sha256: string }>,
): Record<string, unknown> {
  function replace(value: unknown): unknown {
    if (Array.isArray(value)) return value.map(replace);
    if (value === null || typeof value !== "object") return value;
    const record = value as Record<string, unknown>;
    const uploadMutationId = record.uploadMutationId;
    if (
      typeof uploadMutationId === "string" &&
      resolved.has(uploadMutationId)
    ) {
      const object = resolved.get(uploadMutationId)!;
      return { objetoId: object.objectId, sha256: object.sha256 };
    }
    return Object.fromEntries(
      Object.entries(record).map(([key, item]) => [key, replace(item)]),
    );
  }
  return replace(payload) as Record<string, unknown>;
}

function replacementDomainSnapshot(
  mutation: CanonicalOutboxMutationRecord,
  merged: Record<string, unknown>,
  serverVersion: number,
  occurredAt: string,
): Record<string, unknown> {
  if (merged.id !== mutation.entityId) {
    throw new Error("O snapshot remoto não corresponde à entidade canônica.");
  }
  if (
    mutation.entityType === "RDO" &&
    merged.obraId !== mutation.obraId
  ) {
    throw new Error("O snapshot remoto não corresponde à obra canônica.");
  }
  if (mutation.entityType === "RDO") {
    return {
      ...merged,
      versaoEntidade: serverVersion,
      syncStatus: "PENDING_SYNC",
      updatedAt: occurredAt,
    };
  }
  if (mutation.entityType === "MENSAGEM") {
    return {
      ...merged,
      versaoEntidade: serverVersion,
      syncStatus: "NA_FILA",
      ultimoErro: null,
      updatedAt: occurredAt,
    };
  }
  if (mutation.entityType === "MENSAGEM_ANEXO") {
    return {
      ...merged,
      syncStatus: "NA_FILA",
      ultimoErro: null,
      updatedAt: occurredAt,
    };
  }
  if (mutation.entityType === "SERVICE") {
    return {
      ...merged,
      syncStatus: "PENDING_SYNC",
      updatedAt: occurredAt,
      lastError: null,
    };
  }
  if (mutation.entityType === "SERVICE_PRICE_VERSION") {
    return {
      ...merged,
      entityVersion: serverVersion,
      syncStatus: "PENDING_SYNC",
      updatedAt: occurredAt,
      lastError: null,
    };
  }
  return { ...merged };
}

function principalStoreFor(
  entityType: string,
): "rdos" | "mensagens" | "mensagem_conversas" | "mensagem_anexos" |
  "service_catalog" | "service_price_versions" {
  if (entityType === "RDO") return "rdos";
  if (entityType === "MENSAGEM") return "mensagens";
  if (entityType === "CONVERSA") return "mensagem_conversas";
  if (entityType === "MENSAGEM_ANEXO") return "mensagem_anexos";
  if (entityType === "SERVICE") return "service_catalog";
  if (entityType === "SERVICE_PRICE_VERSION") return "service_price_versions";
  throw new Error(
    `entityType ${entityType} não possui snapshot local reconciliável.`,
  );
}

/** Quarantines one locally corrupt row without preventing independent work. */
export async function rejectMutationLocally(
  clientMutationId: string,
  safeCode: string,
  message: string,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<void> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const guardedTransaction = guardSyncTransaction(
    database.transaction(
      RDO_SYNC_TRANSACTION_STORES,
      "readwrite",
    ),
    guard,
  );
  const transaction = guardedTransaction.transaction;
  const outbox = transaction.objectStore("outbox_mutations");
  const mutation = await outbox.get(clientMutationId);
  if (!mutation) {
    await guardedTransaction.complete();
    return;
  }
  const timestamp = nowUtc();
  await outbox.put({
    ...mutation,
    status: "REJECTED",
    nextAttemptAt: null,
    lastSafeCode: safeCode,
    blockedReason: safeCode,
    ultimoErro: message,
    updatedAt: timestamp,
  });
  await updateCatalogMutationSyncStatus(
    transaction,
    mutation,
    "ERROR",
    timestamp,
    message,
  );
  if (isCanonicalOutboxMutation(mutation)) {
    const eventStore = transaction.objectStore("operational_events");
    const events = await eventStore
      .index("by-client-mutation-id")
      .getAll(clientMutationId);
    if (events.length === 1 && events[0].schemaVersion === 13) {
      await eventStore.put({
        ...events[0],
        result: "REJECTED",
        syncStatus: "SYNC_FAILED",
        errorCategory: safeCode,
      });
    }
  } else if (mutation.entidadeTipo === "RDO") {
    await updateRdoOperationalEventsSyncStatus(
      transaction,
      mutation.entidadeId,
      "SYNC_FAILED",
      timestamp,
      mutationOperationalEventIds(mutation),
    );
  }
  if (mutation.entidadeTipo === "RDO") {
    const rdoStore = transaction.objectStore("rdos");
    const rdo = await rdoStore.get(mutation.entidadeId);
    if (rdo) {
      const aggregateSyncStatus = await rdoSyncStatusFromOutbox(
        outbox,
        mutation.entidadeId,
      );
      await rdoStore.put({
        ...rdo,
        syncStatus: aggregateSyncStatus,
        updatedAt: timestamp,
      });
      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        aggregateSyncStatus,
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        aggregateSyncStatus === "SYNCED"
          ? "SYNCED"
          : aggregateSyncStatus === "SYNCING"
            ? "SYNCING"
            : aggregateSyncStatus === "PENDING_SYNC"
              ? "PENDING_SYNC"
              : "SYNC_FAILED",
        timestamp,
      );
    }
  }
  if (mutation.entidadeTipo === "MENSAGEM") {
    const messageStore = transaction.objectStore("mensagens");
    const localMessage = await messageStore.get(mutation.entidadeId);
    if (localMessage) {
      await messageStore.put({
        ...localMessage,
        syncStatus: "FALHOU",
        ultimoErro: message,
        updatedAt: timestamp,
      });
    }
  }
  if (isTaskMutation(mutation)) {
    const taskStore = transaction.objectStore("tarefas");
    const task = await taskStore.get(mutation.entidadeId);
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await taskSyncStatusFromOutbox(
          outbox,
          mutation.entidadeId,
        ),
        updatedAt: timestamp,
      });
    }
  }
  await guardedTransaction.complete();
}

export async function returnMutationToPending(
  clientMutationId: string,
  errorMessage: string,
  safeCode = "NETWORK_TRANSIENT",
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<void> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);

  const guardedTransaction = guardSyncTransaction(
    database.transaction(RDO_SYNC_TRANSACTION_STORES, "readwrite"),
    guard,
  );
  const transaction = guardedTransaction.transaction;

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");
  const messageStore = transaction.objectStore("mensagens");

  const mutation = await outboxStore.get(clientMutationId);

  if (!mutation) {
    await guardedTransaction.complete();
    return;
  }

  const timestamp = nowUtc();

  const pending = isCanonicalOutboxMutation(mutation)
    ? mutationAfterRetryScheduled(mutation, {
        safeCode,
        message: errorMessage,
        now: Date.parse(timestamp),
      })
    : {
        ...mutation,
        status: "PENDING" as const,
        ultimoErro: errorMessage,
        updatedAt: timestamp,
      };
  await outboxStore.put(pending);

  if (isCanonicalOutboxMutation(mutation)) {
    const event = await exactCanonicalEvent(
      transaction,
      mutation.clientMutationId,
    );
    await putCanonicalEvent(transaction, {
      ...event,
      result: "PENDING",
      syncStatus: "PENDING_SYNC",
      errorCategory: safeCode,
    });
  }

  const rdo = await rdoStore.get(mutation.entidadeId);

  if (rdo) {
    await rdoStore.put({
      ...rdo,
      syncStatus: "PENDING_SYNC",
      updatedAt: timestamp,
    });

    await updateRdoChildrenSyncStatus(
      transaction,
      mutation.entidadeId,
      "PENDING_SYNC",
      timestamp,
    );
    if (!isCanonicalOutboxMutation(mutation)) {
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
    }
    await updateRdoAttachmentsSyncStatus(
      transaction,
      mutation.entidadeId,
      "PENDING_SYNC",
      timestamp,
    );
  }

  if (mutation.entidadeTipo === "MENSAGEM") {
    const message = await messageStore.get(mutation.entidadeId);
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: "NA_FILA",
        ultimoErro: errorMessage,
        updatedAt: timestamp,
      });
    }
  }

  if (isTaskMutation(mutation)) {
    const task = await taskStore.get(mutation.entidadeId);
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await taskSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        updatedAt: timestamp,
      });
    }
  }

  await guardedTransaction.complete();
}

function applySafeRdoEvent(
  rdo: LocalRdoRecord,
  event: SyncPullEvent,
): LocalRdoRecord {
  const updated: LocalRdoRecord = {
    ...rdo,
    updatedAt: nowUtc(),
  };

  if (
    typeof event.versaoEntidade === "number" &&
    event.versaoEntidade >=
      (rdo.versaoEntidade ?? 0)
  ) {
    updated.versaoEntidade = event.versaoEntidade;
  }

  if (event.tipoEvento === "RDO_ENVIADO") {
    updated.statusRdo = "ENVIADO";
  }

  return updated;
}

function nullableTextValue(value: unknown): string | null {
  const text = textValue(value);
  return text || null;
}

function taskPriorityFromPayload(
  value: unknown,
): TarefaRecord["prioridade"] | null {
  const parsed =
    typeof value === "number"
      ? value
      : Number(textValue(value));

  return parsed === 1 || parsed === 2 || parsed === 3
    ? parsed
    : null;
}

function taskRecordFromSyncPayload(
  payload: Record<string, unknown>,
  event: SyncPullEvent,
  timestamp: string,
): ConvergentTarefaRecord | null {
  const id = textValue(payload.id) || textValue(event.entidadeId);
  const obraId = textValue(payload.obraId);
  const titulo = textValue(payload.titulo);
  const prioridade = taskPriorityFromPayload(payload.prioridade);

  if (!id || !obraId || !titulo || prioridade === null) {
    return null;
  }

  const payloadVersion = payload.versaoEntidade;
  const version =
    typeof event.versaoEntidade === "number"
      ? event.versaoEntidade
      : typeof payloadVersion === "number"
        ? payloadVersion
        : null;

  return {
    id,
    obraId,
    equipe: textValue(payload.equipe),
    titulo,
    observacoes: textValue(payload.observacoes),
    criadaPor: textValue(payload.criadaPor),
    criadaPorColaboradorId: nullableTextValue(
      payload.criadaPorColaboradorId,
    ),
    responsavelEquipe: textValue(payload.responsavelEquipe),
    responsavelColaboradorId: nullableTextValue(
      payload.responsavelColaboradorId,
    ),
    prioridade,
    concluida: payload.concluida === true,
    concluidaEm: nullableTextValue(payload.concluidaEm),
    createdAt: textValue(payload.createdAt) || timestamp,
    updatedAt: textValue(payload.updatedAt) || timestamp,
    versaoEntidade: version,
    syncStatus: "SYNCED",
    deletadaEm: nullableTextValue(payload.deletadaEm),
  };
}

function shouldApplyPulledTask(
  existing: ConvergentTarefaRecord,
  incoming: ConvergentTarefaRecord,
): boolean {
  if (
    existing.syncStatus !== undefined &&
    existing.syncStatus !== "SYNCED"
  ) {
    return false;
  }

  return (
    (incoming.versaoEntidade ?? 0) >=
    (existing.versaoEntidade ?? 0)
  );
}

export async function applyPulledEventsAtomically(
  events: SyncPullEvent[],
  nextCommitSeq: number,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);

  const guardedTransaction = guardSyncTransaction(
    database.transaction(
      [
        "processed_events",
        "rdos",
        "tarefas",
        "sync_state",
        "obras",
        "previsao_snapshots",
      ],
      "readwrite",
    ),
    guard,
  );
  const transaction = guardedTransaction.transaction;

  const processedStore =
    transaction.objectStore("processed_events");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");
  const syncStateStore =
    transaction.objectStore("sync_state");

  const syncState = await syncStateStore.get("default");

  if (!syncState) {
    transaction.abort();

    throw new Error(
      "Estado local de sincronização não encontrado.",
    );
  }

  let highestAppliedCommitSeq =
    syncState.lastPulledCommitSeq;

  const orderedEvents = [...events].sort(
    (left, right) => left.commitSeq - right.commitSeq,
  );

  for (const event of orderedEvents) {
    if (!Number.isSafeInteger(event.commitSeq)) {
      transaction.abort();

      throw new Error(
        "Evento recebido com commitSeq inválido.",
      );
    }

    if (event.commitSeq <= syncState.lastPulledCommitSeq) {
      continue;
    }

    const alreadyProcessed = await processedStore.get(
      event.commitSeq,
    );

    if (alreadyProcessed) {
      highestAppliedCommitSeq = Math.max(
        highestAppliedCommitSeq,
        event.commitSeq,
      );

      continue;
    }

    if (
      event.entidadeTipo === "RDO" &&
      event.entidadeId
    ) {
      const localRdo = await rdoStore.get(
        event.entidadeId,
      );

      if (localRdo) {
        await rdoStore.put(
          applySafeRdoEvent(localRdo, event),
        );
      }
    }

    if (
      event.entidadeTipo === "TAREFA" &&
      event.payload
    ) {
      const incoming = taskRecordFromSyncPayload(
        event.payload,
        event,
        nowUtc(),
      );

      if (incoming) {
        const existing = (await taskStore.get(
          incoming.id,
        )) as ConvergentTarefaRecord | undefined;

        if (
          !existing ||
          shouldApplyPulledTask(existing, incoming)
        ) {
          await taskStore.put(incoming);
        }
      }
    }

    if (
      event.entidadeTipo === "OBRA" &&
      event.tipoEvento === "OBRA_ATUALIZADA" &&
      event.payload
    ) {
      const incoming = obraRecordFromPayload(
        event.payload,
        nowUtc(),
      );

      if (incoming) {
        const obraStore = transaction.objectStore("obras");
        const existing = await obraStore.get(incoming.id);

        // Só atualiza obras já conhecidas localmente: a hidratação REST
        // (escopada por vínculo) é quem decide o que entra no dispositivo.
        if (existing) {
          await obraStore.put(
            mergeObraRecords(existing, incoming),
          );
        }
      }
    }

    if (
      event.entidadeTipo === "PREVISAO_FINANCEIRA" &&
      event.tipoEvento === "PREVISAO_FINANCEIRA_CALCULADA" &&
      event.payload
    ) {
      const snapshot = snapshotRecordFromPayload(
        event.payload,
        nowUtc(),
      );

      if (snapshot) {
        const knownObra = await transaction
          .objectStore("obras")
          .get(snapshot.obraId);

        if (knownObra) {
          await transaction
            .objectStore("previsao_snapshots")
            .put(snapshot);
        }
      }
    }

    const processedRecord: ProcessedEventRecord = {
      commitSeq: event.commitSeq,
      eventoId: event.eventoId,
      tipoEvento: event.tipoEvento,
      entidadeTipo: event.entidadeTipo,
      entidadeId: event.entidadeId,
      aplicadoEm: nowUtc(),
    };

    await processedStore.add(processedRecord);

    highestAppliedCommitSeq = Math.max(
      highestAppliedCommitSeq,
      event.commitSeq,
    );
  }

  if (
    events.length === 0 &&
    nextCommitSeq > syncState.lastPulledCommitSeq
  ) {
    transaction.abort();

    throw new Error(
      "O servidor avançou o cursor sem entregar eventos.",
    );
  }

  const resultingCursor = Math.max(
    highestAppliedCommitSeq,
    events.length > 0
      ? Math.min(nextCommitSeq, highestAppliedCommitSeq)
      : syncState.lastPulledCommitSeq,
  );

  await syncStateStore.put({
    ...syncState,
    lastPulledCommitSeq: resultingCursor,
  });

  await guardedTransaction.complete();

  return resultingCursor;
}
