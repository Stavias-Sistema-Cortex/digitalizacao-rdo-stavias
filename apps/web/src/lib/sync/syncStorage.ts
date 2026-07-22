import { getCortexDb } from "../db/cortexDb";
import type {
  LocalRdoChildRecord,
  LocalRdoRecord,
  LocalSyncStatus,
  MensagemAnexoLocalRecord,
  MensagemLocalRecord,
  ObraLocalRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
  ProcessedEventRecord,
  RdoAttachmentRecord,
  TarefaRecord,
} from "../db/db.types";
import {
  mergeObraRecords,
  obraRecordFromPayload,
  snapshotRecordFromPayload,
} from "../db/homeRecordMappers";
import {
  BLOCKED_DEPENDENCY_REVIEW_REASON,
  CYCLIC_DEPENDENCY_REVIEW_REASON,
  isCanonicalOutboxMutation,
  isOutboxMutationBlocked,
  isOutboxMutationInActiveDispatchState,
  LEGACY_TRACE_REVIEW_REASON,
  MISSING_DEPENDENCY_REVIEW_REASON,
} from "../db/outboxContract";
import type {
  SyncPullEvent,
  SyncPushMutationResult,
} from "./sync.types";
import type { AutomaticSyncRetryMetadata } from "./automaticSyncScheduler";
import { analyzeOutboxDependencies } from "./outboxDependencies";

function nowUtc(): string {
  return new Date().toISOString();
}

export function automaticSyncRetryMetadataFromMutations(
  mutations: OutboxMutationRecord[],
): AutomaticSyncRetryMetadata {
  const scheduled = mutations
    .filter(
      (mutation) =>
        mutation.status === "PENDING" &&
        typeof mutation.nextAttemptAt === "string" &&
        Number.isFinite(Date.parse(mutation.nextAttemptAt)),
    )
    .sort(
      (left, right) =>
        Date.parse(left.nextAttemptAt as string) -
        Date.parse(right.nextAttemptAt as string),
    );

  if (scheduled.length === 0) {
    return {
      attempt: 0,
      nextAttemptAt: null,
    };
  }

  return {
    attempt: scheduled.reduce(
      (highest, mutation) =>
        Math.max(
          highest,
          Number.isFinite(mutation.tentativas)
            ? Math.max(0, Math.floor(mutation.tentativas))
            : 0,
        ),
      0,
    ),
    nextAttemptAt: scheduled[0].nextAttemptAt ?? null,
  };
}

export function mutationAfterAutomaticSyncRetryScheduled(
  mutation: OutboxMutationRecord,
  metadata: AutomaticSyncRetryMetadata,
  timestamp: string,
): OutboxMutationRecord {
  return {
    ...mutation,
    tentativas: Math.max(
      mutation.tentativas,
      Math.max(0, Math.floor(metadata.attempt)),
    ),
    nextAttemptAt: metadata.nextAttemptAt,
    updatedAt: timestamp,
  };
}

export function mutationAfterAutomaticSyncRetryCleared(
  mutation: OutboxMutationRecord,
  timestamp: string,
): OutboxMutationRecord {
  return {
    ...mutation,
    tentativas: 0,
    ultimaTentativaEm: null,
    nextAttemptAt: null,
    updatedAt: timestamp,
  };
}

export async function loadAutomaticSyncRetryMetadata(): Promise<AutomaticSyncRetryMetadata> {
  const database = await getCortexDb();
  const pending = await database.getAllFromIndex(
    "outbox_mutations",
    "by-status",
    "PENDING",
  );

  return automaticSyncRetryMetadataFromMutations(pending);
}

export async function persistAutomaticSyncRetryMetadata(
  metadata: AutomaticSyncRetryMetadata,
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    "outbox_mutations",
    "readwrite",
  );
  const store = transaction.objectStore("outbox_mutations");
  const pending = await store
    .index("by-status")
    .getAll("PENDING");
  const timestamp = nowUtc();

  await Promise.all(
    pending.map((mutation) =>
      store.put(
        mutationAfterAutomaticSyncRetryScheduled(
          mutation,
          metadata,
          timestamp,
        ),
      ),
    ),
  );

  await transaction.done;
}

export async function clearAutomaticSyncRetryMetadata(): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    "outbox_mutations",
    "readwrite",
  );
  const store = transaction.objectStore("outbox_mutations");
  const pending = await store
    .index("by-status")
    .getAll("PENDING");
  const timestamp = nowUtc();
  const scheduled = pending.filter(
    (mutation) =>
      mutation.nextAttemptAt !== null &&
      mutation.nextAttemptAt !== undefined,
  );

  await Promise.all(
    scheduled.map((mutation) =>
      store.put(
        mutationAfterAutomaticSyncRetryCleared(
          mutation,
          timestamp,
        ),
      ),
    ),
  );

  await transaction.done;
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

interface OperationalEventSyncTransaction {
  objectStore: (
    name: "operational_events",
  ) => OperationalEventStoreUpdater;
}

interface OutboxEntityMutationStore {
  index: (name: "by-entity-id") => {
    getAll: (query: string) => Promise<OutboxMutationRecord[]>;
  };
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

interface LocalReviewOutboxStore {
  get: (
    clientMutationId: string,
  ) => Promise<OutboxMutationRecord | undefined>;
  getAll: () => Promise<OutboxMutationRecord[]>;
  put: (value: OutboxMutationRecord) => Promise<IDBValidKey>;
}

interface LocalReviewRdoStore {
  get: (rdoId: string) => Promise<LocalRdoRecord | undefined>;
  put: (value: LocalRdoRecord) => Promise<IDBValidKey>;
}

interface LocalReviewEventStore {
  index: (name: "by-client-mutation-id" | "by-rdo-id") => {
    getAll: (query: string) => Promise<OperationalEventRecord[]>;
  };
  put: (value: OperationalEventRecord) => Promise<IDBValidKey>;
}

interface LocalReviewMessageStore {
  get: (messageId: string) => Promise<MensagemLocalRecord | undefined>;
  put: (value: MensagemLocalRecord) => Promise<IDBValidKey>;
}

interface LocalReviewAttachmentStore {
  get: (
    attachmentId: string,
  ) => Promise<MensagemAnexoLocalRecord | undefined>;
  put: (value: MensagemAnexoLocalRecord) => Promise<IDBValidKey>;
}

interface LocalReviewTaskStore {
  get: (taskId: string) => Promise<TarefaRecord | undefined>;
  put: (value: TarefaRecord) => Promise<IDBValidKey>;
}

interface LocalReviewTransaction {
  done: Promise<void>;
  abort: () => void;
  objectStore(name: RdoChildStoreName): RdoChildStoreUpdater;
  objectStore(name: "rdo_attachments"): RdoAttachmentStoreUpdater;
  objectStore(name: "operational_events"): LocalReviewEventStore;
  objectStore(name: "outbox_mutations"): LocalReviewOutboxStore;
  objectStore(name: "rdos"): LocalReviewRdoStore;
  objectStore(name: "tarefas"): LocalReviewTaskStore;
  objectStore(name: "mensagens"): LocalReviewMessageStore;
  objectStore(name: "mensagem_anexos"): LocalReviewAttachmentStore;
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
  ...RDO_CHILD_STORE_NAMES,
] as const;

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
  clientMutationId?: string,
): Promise<void> {
  const store = transaction.objectStore("operational_events");

  const records = clientMutationId
    ? await store
        .index("by-client-mutation-id")
        .getAll(clientMutationId)
    : await store.index("by-rdo-id").getAll(rdoId);

  await Promise.all(
    records
      .filter((record) => record.syncStatus !== "SYNCED")
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

function rdoSyncStatusFromMutations(
  mutations: OutboxMutationRecord[],
): LocalSyncStatus {
  if (mutations.some((mutation) => mutation.status === "CONFLICT")) {
    return "CONFLICT";
  }
  if (mutations.some(
    (mutation) =>
      mutation.status === "REJECTED" ||
      mutation.status === "ERROR",
  )) {
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

async function rdoSyncStatusFromOutbox(
  store: OutboxEntityMutationStore,
  rdoId: string,
): Promise<LocalSyncStatus> {
  return rdoSyncStatusFromMutations(
    await store.index("by-entity-id").getAll(rdoId),
  );
}

async function tarefaSyncStatusFromOutbox(
  store: OutboxEntityMutationStore,
  tarefaId: string,
): Promise<LocalSyncStatus> {
  return rdoSyncStatusFromMutations(
    (await store.index("by-entity-id").getAll(tarefaId))
      .filter((mutation) => mutation.entidadeTipo === "TAREFA"),
  );
}

function attachmentSyncStatusForRdo(
  syncStatus: LocalSyncStatus,
): RdoAttachmentRecord["syncStatus"] {
  if (syncStatus === "SYNCED") {
    return "SYNCED";
  }
  if (syncStatus === "SYNCING") {
    return "SYNCING";
  }
  if (syncStatus === "PENDING_SYNC" || syncStatus === "LOCAL_ONLY") {
    return "PENDING_SYNC";
  }

  return "SYNC_FAILED";
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

export async function recoverInterruptedMutations(): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");

  const syncingMutations =
    await outboxStore.index("by-status").getAll("SYNCING");

  for (const mutation of syncingMutations) {
    const updatedMutation: OutboxMutationRecord = {
      ...mutation,
      status: "PENDING",
      ultimoErro:
        "Sincronização anterior foi interrompida antes da confirmação.",
      updatedAt: nowUtc(),
    };

    await outboxStore.put(updatedMutation);

    const rdo = await rdoStore.get(mutation.entidadeId);

    if (rdo) {
      const syncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await rdoStore.put({
        ...rdo,
        syncStatus,
        updatedAt: nowUtc(),
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        syncStatus,
        nowUtc(),
      );
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        attachmentSyncStatusForRdo(syncStatus),
        nowUtc(),
        mutation.clientMutationId,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        nowUtc(),
      );
    }

    if (mutation.entidadeTipo === "TAREFA") {
      const task = await taskStore.get(mutation.entidadeId);
      if (task) {
        await taskStore.put({
          ...task,
          syncStatus: await tarefaSyncStatusFromOutbox(
            outboxStore,
            mutation.entidadeId,
          ),
          updatedAt: nowUtc(),
        });
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
          updatedAt: nowUtc(),
        });
      }
    }
  }

  await transaction.done;
}

export async function repairMissingObraReferencesForSync(): Promise<number> {
  const database = await getCortexDb();
  const obras = await database.getAll("obras");

  if (obras.length === 0) {
    return 0;
  }

  const timestamp = nowUtc();
  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );
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

  for (const mutation of candidates) {
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

  await transaction.done;

  return repaired;
}

export async function repairMissingMaoObraReferencesForSync(): Promise<number> {
  const database = await getCortexDb();
  const timestamp = nowUtc();
  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );
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

  for (const mutation of candidates) {
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

  await transaction.done;

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

  for (const mutation of erroredMutations) {
    const retryMutation = mutationAfterErroredRetry(
      mutation,
      timestamp,
    );

    await outboxStore.put(retryMutation);

    const rdo = await rdoStore.get(mutation.entidadeId);

    if (rdo) {
      const syncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await rdoStore.put({
        ...rdo,
        syncStatus,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        syncStatus,
        timestamp,
      );
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        attachmentSyncStatusForRdo(syncStatus),
        timestamp,
        mutation.clientMutationId,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        timestamp,
      );
    }

    if (mutation.entidadeTipo === "TAREFA") {
      const task = await taskStore.get(mutation.entidadeId);
      if (task) {
        await taskStore.put({
          ...task,
          syncStatus: await tarefaSyncStatusFromOutbox(
            outboxStore,
            mutation.entidadeId,
          ),
          updatedAt: timestamp,
        });
      }
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
  }

  await transaction.done;

  return erroredMutations.length;
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
): OutboxMutationRecord {
  const serverMissingRdo =
    !isCanonicalOutboxMutation(mutation) &&
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
  if (
    mutation.status !== "CONFLICT" ||
    isCanonicalOutboxMutation(mutation)
  ) {
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
      const syncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await rdoStore.put({
        ...rdo,
        syncStatus,
        versaoEntidade: retryMutation.baseVersao,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        syncStatus,
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        attachmentSyncStatusForRdo(syncStatus),
        timestamp,
      );
    }

    queued += 1;
  }

  await transaction.done;

  return queued;
}

type LocalOutboxBlockCategory =
  | "NONCANONICAL_ENVELOPE"
  | "BLOCKED_DEPENDENCY"
  | "MISSING_DEPENDENCY"
  | "CYCLIC_DEPENDENCY";

interface LocalOutboxBlock {
  reason: string;
  category: LocalOutboxBlockCategory;
}

async function markMutationForLocalReview(
  transaction: LocalReviewTransaction,
  mutation: OutboxMutationRecord,
  block: LocalOutboxBlock,
  timestamp: string,
): Promise<void> {
  const outboxStore = transaction.objectStore("outbox_mutations");
  const eventStore = transaction.objectStore("operational_events");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");
  const messageStore = transaction.objectStore("mensagens");
  const attachmentStore = transaction.objectStore("mensagem_anexos");

  await outboxStore.put({
    ...mutation,
    status: "REJECTED",
    blockedReason: block.reason,
    ultimoErro: block.reason,
    updatedAt: timestamp,
  });

  const correlatedEvents = await eventStore
    .index("by-client-mutation-id")
    .getAll(mutation.clientMutationId);
  const eventsToReject = new Map(
    correlatedEvents.map((event) => [event.id, event]),
  );

  const rdo = mutation.entidadeTipo === "RDO"
    ? await rdoStore.get(mutation.entidadeId)
    : undefined;

  if (rdo) {
    await rdoStore.put({
      ...rdo,
      syncStatus: "ERROR",
      updatedAt: timestamp,
    });
    await updateRdoChildrenSyncStatus(
      transaction,
      mutation.entidadeId,
      "ERROR",
      timestamp,
    );
    await updateRdoAttachmentsSyncStatus(
      transaction,
      mutation.entidadeId,
      "SYNC_FAILED",
      timestamp,
    );

    const uncorrelatedRdoEvents = await eventStore
      .index("by-rdo-id")
      .getAll(mutation.entidadeId);
    for (const event of uncorrelatedRdoEvents) {
      if (
        event.syncStatus !== "SYNCED" &&
        (!event.clientMutationId ||
          event.clientMutationId === mutation.clientMutationId)
      ) {
        eventsToReject.set(event.id, event);
      }
    }
  }

  if (mutation.entidadeTipo === "TAREFA") {
    const task = await taskStore.get(mutation.entidadeId);
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: "ERROR",
        updatedAt: timestamp,
      });
    }
  }

  await Promise.all(
    [...eventsToReject.values()].map((event) =>
      eventStore.put({
        ...event,
        syncStatus: "SYNC_FAILED",
        result: "REJECTED",
        errorCategory: block.category,
      }),
    ),
  );

  if (mutation.entidadeTipo === "MENSAGEM") {
    const message = await messageStore.get(mutation.entidadeId);
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: "FALHOU",
        ultimoErro: block.reason,
        updatedAt: timestamp,
      });
    }
  }

  if (mutation.entidadeTipo === "MENSAGEM_ANEXO") {
    const attachment = await attachmentStore.get(mutation.entidadeId);
    if (attachment) {
      await attachmentStore.put({
        ...attachment,
        syncStatus: "FALHOU",
        ultimoErro: block.reason,
        updatedAt: timestamp,
      });
      const message = await messageStore.get(attachment.mensagemId);
      if (message && message.syncStatus !== "SINCRONIZADO") {
        await messageStore.put({
          ...message,
          syncStatus: "FALHOU",
          ultimoErro: block.reason,
          updatedAt: timestamp,
        });
      }
    }
  }
}

function localReviewBlocks(
  mutations: OutboxMutationRecord[],
): Map<string, LocalOutboxBlock> {
  const blocks = new Map<string, LocalOutboxBlock>();
  const active = (mutation: OutboxMutationRecord): boolean =>
    isOutboxMutationInActiveDispatchState(mutation);
  const isDirectObjectUpload = (mutation: OutboxMutationRecord): boolean =>
    mutation.transport === "OBJECT_UPLOAD";

  for (const mutation of mutations) {
    // Object uploads have a dedicated transport worker and are deliberately
    // not Sync-Push envelopes. Their dependent domain mutation is still
    // required to be canonical, but the upload job itself must remain
    // dispatchable by processObjectUploads().
    if (
      !isDirectObjectUpload(mutation) &&
      !isCanonicalOutboxMutation(mutation) &&
      active(mutation)
    ) {
      blocks.set(mutation.clientMutationId, {
        reason: isOutboxMutationBlocked(mutation)
          ? mutation.blockedReason as string
          : LEGACY_TRACE_REVIEW_REASON,
        category: "NONCANONICAL_ENVELOPE",
      });
    }
  }

  const dependencyAnalysis = analyzeOutboxDependencies(mutations);
  for (const mutationId of dependencyAnalysis.cycles) {
    const mutation = mutations.find(
      (candidate) => candidate.clientMutationId === mutationId,
    );
    if (mutation && active(mutation) && !blocks.has(mutationId)) {
      blocks.set(mutationId, {
        reason: CYCLIC_DEPENDENCY_REVIEW_REASON,
        category: "CYCLIC_DEPENDENCY",
      });
    }
  }
  for (const missing of dependencyAnalysis.missingDependencies) {
    const mutation = mutations.find(
      (candidate) => candidate.clientMutationId === missing.mutationId,
    );
    if (
      mutation &&
      active(mutation) &&
      !blocks.has(missing.mutationId)
    ) {
      blocks.set(missing.mutationId, {
        reason: MISSING_DEPENDENCY_REVIEW_REASON,
        category: "MISSING_DEPENDENCY",
      });
    }
  }

  const byId = new Map(
    mutations.map((mutation) => [
      mutation.clientMutationId,
      mutation,
    ]),
  );
  let changed = true;
  while (changed) {
    changed = false;
    for (const mutation of mutations) {
      if (!active(mutation) || blocks.has(mutation.clientMutationId)) {
        continue;
      }
      const dependencyBlocksDispatch = (mutation.dependsOnMutationIds ?? [])
        .some((dependencyId) => {
          const dependency = byId.get(dependencyId);
          return (
            blocks.has(dependencyId) ||
            dependency?.status === "REJECTED" ||
            dependency?.status === "CONFLICT"
          );
        });
      if (!dependencyBlocksDispatch) {
        continue;
      }
      blocks.set(mutation.clientMutationId, {
        reason: BLOCKED_DEPENDENCY_REVIEW_REASON,
        category: "BLOCKED_DEPENDENCY",
      });
      changed = true;
    }
  }

  return blocks;
}

/**
 * Idempotently materializes local review state before any queue inspection.
 * It never fabricates provenance or alters the persisted domain payload.
 */
export async function classifyLegacyOutboxMutationsForReview(): Promise<
  number
> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  ) as unknown as LocalReviewTransaction;
  const outboxStore = transaction.objectStore("outbox_mutations");
  const mutations = await outboxStore.getAll();
  const blocks = localReviewBlocks(mutations);
  const passiveLegacyMutations = mutations.filter(
    (mutation) =>
      mutation.transport !== "OBJECT_UPLOAD" &&
      !isCanonicalOutboxMutation(mutation) &&
      !isOutboxMutationInActiveDispatchState(mutation) &&
      (!mutation.blockedReason || !mutation.blockedReason.trim()),
  );
  const timestamp = nowUtc();

  for (const mutation of mutations) {
    const block = blocks.get(mutation.clientMutationId);
    if (block) {
      await markMutationForLocalReview(
        transaction,
        mutation,
        block,
        timestamp,
      );
    }
  }

  await Promise.all(
    passiveLegacyMutations.map((mutation) =>
      outboxStore.put({
        ...mutation,
        blockedReason: LEGACY_TRACE_REVIEW_REASON,
        updatedAt: timestamp,
      }),
    ),
  );

  await transaction.done;
  return blocks.size + passiveLegacyMutations.length;
}

export async function rejectNonCanonicalMutation(
  clientMutationId: string,
  blockedReason = LEGACY_TRACE_REVIEW_REASON,
): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  ) as unknown as LocalReviewTransaction;
  const outboxStore = transaction.objectStore("outbox_mutations");
  const mutation = await outboxStore.get(clientMutationId);

  if (!mutation) {
    transaction.abort();
    throw new Error(
      `Mutacao ${clientMutationId} nao encontrada para bloqueio.`,
    );
  }

  if (isOutboxMutationInActiveDispatchState(mutation)) {
    await markMutationForLocalReview(
      transaction,
      mutation,
      {
        reason: blockedReason,
        category: "NONCANONICAL_ENVELOPE",
      },
      nowUtc(),
    );
  }

  await transaction.done;
}

export async function markMutationAsSyncing(
  mutation: OutboxMutationRecord,
): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

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

  await outboxStore.put({
    ...currentMutation,
    status: "SYNCING",
    tentativas: currentMutation.tentativas + 1,
    ultimaTentativaEm: nowUtc(),
    ultimoErro: null,
    updatedAt: nowUtc(),
  });

  const rdo = await rdoStore.get(currentMutation.entidadeId);

  if (rdo) {
    const timestamp = nowUtc();
    const syncStatus = await rdoSyncStatusFromOutbox(
      outboxStore,
      currentMutation.entidadeId,
    );

    await rdoStore.put({
      ...rdo,
      syncStatus,
      updatedAt: timestamp,
    });

    await updateRdoChildrenSyncStatus(
      transaction,
      currentMutation.entidadeId,
      syncStatus,
      timestamp,
    );
    await updateRdoOperationalEventsSyncStatus(
      transaction,
      currentMutation.entidadeId,
      attachmentSyncStatusForRdo(syncStatus),
      timestamp,
      currentMutation.clientMutationId,
    );
    await updateRdoAttachmentsSyncStatus(
      transaction,
      currentMutation.entidadeId,
      "SYNCING",
      timestamp,
    );
  }

  if (currentMutation.entidadeTipo === "TAREFA") {
    const task = await taskStore.get(currentMutation.entidadeId);
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await tarefaSyncStatusFromOutbox(
          outboxStore,
          currentMutation.entidadeId,
        ),
        updatedAt: nowUtc(),
      });
    }
  }

  if (currentMutation.entidadeTipo === "MENSAGEM") {
    const messageStore = transaction.objectStore("mensagens");
    const message = await messageStore.get(currentMutation.entidadeId);
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: "SINCRONIZANDO",
        ultimoErro: null,
        updatedAt: nowUtc(),
      });
    }
  }

  await transaction.done;
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

function serverRejectionCategory(
  result: SyncPushMutationResult,
): string | null {
  const rejection = result.resultado?.rejeicao;

  if (
    rejection === null ||
    typeof rejection !== "object" ||
    Array.isArray(rejection)
  ) {
    return null;
  }

  const category = (rejection as Record<string, unknown>)
    .categoria;

  return typeof category === "string" && category.length > 0
    ? category
    : null;
}

export async function applyPushResultAtomically(
  result: SyncPushMutationResult,
): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");
  const messageStore = transaction.objectStore("mensagens");
  const eventStore = transaction.objectStore("operational_events");

  const mutation = await outboxStore.get(
    result.clientMutationId,
  );

  if (!mutation) {
    transaction.abort();

    throw new Error(
      `Resultado recebido para mutação desconhecida: ${result.clientMutationId}.`,
    );
  }

  const rdo = await rdoStore.get(mutation.entidadeId);
  const message =
    mutation.entidadeTipo === "MENSAGEM"
      ? await messageStore.get(mutation.entidadeId)
      : undefined;
  const task =
    mutation.entidadeTipo === "TAREFA"
      ? await taskStore.get(mutation.entidadeId)
      : undefined;
  const timestamp = nowUtc();
  const correlatedEvents = await eventStore
    .index("by-client-mutation-id")
    .getAll(mutation.clientMutationId);

  if (
    result.status === "APLICADA" ||
    result.status === "CONCILIADA"
  ) {
    await outboxStore.put({
      ...mutation,
      status: "SYNCED",
      ultimoErro: null,
      conflito: null,
      updatedAt: timestamp,
    });

    await Promise.all(
      correlatedEvents.map((event) =>
        eventStore.put({
          ...event,
          syncStatus: "SYNCED",
          syncedAt: timestamp,
          result:
            result.status === "CONCILIADA"
              ? "CONCILIADA"
              : "SYNCED",
          errorCategory: null,
        }),
      ),
    );

    if (rdo) {
      const syncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      const resultVersion =
        result.resultado &&
        typeof result.resultado.versaoEntidade === "number"
          ? result.resultado.versaoEntidade
          : rdo.versaoEntidade;

      await rdoStore.put({
        ...rdo,
        syncStatus,
        versaoEntidade: resultVersion,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        syncStatus,
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        attachmentSyncStatusForRdo(syncStatus),
        timestamp,
      );
    }

    if (task) {
      const resultVersion =
        result.resultado &&
        typeof result.resultado.versaoEntidade === "number"
          ? result.resultado.versaoEntidade
          : task.versaoEntidade;
      await taskStore.put({
        ...task,
        syncStatus: await tarefaSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        versaoEntidade: resultVersion,
        updatedAt: timestamp,
      });
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
  } else if (result.status === "DESCARTADA") {
    await outboxStore.put({
      ...mutation,
      status: "CONFLICT",
      ultimoErro:
        result.erro ?? "Conflito informado pelo servidor.",
      conflito: result.conflito ?? null,
      updatedAt: timestamp,
    });

    await Promise.all(
      correlatedEvents.map((event) =>
        eventStore.put({
          ...event,
          syncStatus: "SYNC_FAILED",
          result: "CONFLICT",
          errorCategory: null,
        }),
      ),
    );

    if (rdo) {
      const syncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await rdoStore.put(
        {
          ...rdoAfterConflict(rdo, result, timestamp),
          syncStatus,
        },
      );

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        syncStatus,
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        attachmentSyncStatusForRdo(syncStatus),
        timestamp,
      );
    }
    if (task) {
      const conflictVersion =
        result.conflito && typeof result.conflito === "object"
          ? (result.conflito as Record<string, unknown>).versaoAtual
          : null;
      await taskStore.put({
        ...task,
        syncStatus: await tarefaSyncStatusFromOutbox(
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
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: "FALHOU",
        ultimoErro:
          result.erro ?? "Conflito informado pelo servidor.",
        updatedAt: timestamp,
      });
    }
  } else if (result.status === "REJEITADA") {
    await outboxStore.put({
      ...mutation,
      status: "REJECTED",
      ultimoErro:
        result.erro ?? "Mutacao rejeitada pelo servidor.",
      conflito: result.conflito ?? null,
      updatedAt: timestamp,
    });

    await Promise.all(
      correlatedEvents.map((event) =>
        eventStore.put({
          ...event,
          syncStatus: "SYNC_FAILED",
          result: "REJECTED",
          errorCategory: serverRejectionCategory(result),
        }),
      ),
    );

    if (rdo) {
      const syncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await rdoStore.put({
        ...rdo,
        syncStatus,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        syncStatus,
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        attachmentSyncStatusForRdo(syncStatus),
        timestamp,
      );
    }
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await tarefaSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        updatedAt: timestamp,
      });
    }
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: "FALHOU",
        ultimoErro:
          result.erro ?? "Mutacao rejeitada pelo servidor.",
        updatedAt: timestamp,
      });
    }
  } else {
    await outboxStore.put({
      ...mutation,
      status: "ERROR",
      ultimoErro:
        result.erro ?? "Erro informado pelo servidor.",
      conflito: result.conflito ?? null,
      updatedAt: timestamp,
    });

    await Promise.all(
      correlatedEvents.map((event) =>
        eventStore.put({
          ...event,
          syncStatus: "SYNC_FAILED",
          result: "PENDING",
          errorCategory: null,
        }),
      ),
    );

    if (rdo) {
      const syncStatus = await rdoSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await rdoStore.put({
        ...rdo,
        syncStatus,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        syncStatus,
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        attachmentSyncStatusForRdo(syncStatus),
        timestamp,
      );
    }
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await tarefaSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        updatedAt: timestamp,
      });
    }
    if (message) {
      await messageStore.put({
        ...message,
        syncStatus: "FALHOU",
        ultimoErro:
          result.erro ?? "Erro informado pelo servidor.",
        updatedAt: timestamp,
      });
    }
  }

  await transaction.done;
}

export async function returnMutationToPending(
  clientMutationId: string,
  errorMessage: string,
): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");
  const taskStore = transaction.objectStore("tarefas");
  const messageStore = transaction.objectStore("mensagens");

  const mutation = await outboxStore.get(clientMutationId);

  if (!mutation || mutation.status !== "SYNCING") {
    await transaction.done;
    return;
  }

  const timestamp = nowUtc();

  await outboxStore.put({
    ...mutation,
    status: "PENDING",
    ultimoErro: errorMessage,
    updatedAt: timestamp,
  });

  const rdo = await rdoStore.get(mutation.entidadeId);

  if (rdo) {
    const syncStatus = await rdoSyncStatusFromOutbox(
      outboxStore,
      mutation.entidadeId,
    );
    await rdoStore.put({
      ...rdo,
      syncStatus,
      updatedAt: timestamp,
    });

    await updateRdoChildrenSyncStatus(
      transaction,
      mutation.entidadeId,
      syncStatus,
      timestamp,
    );
    await updateRdoOperationalEventsSyncStatus(
      transaction,
      mutation.entidadeId,
      attachmentSyncStatusForRdo(syncStatus),
      timestamp,
      mutation.clientMutationId,
    );
    await updateRdoAttachmentsSyncStatus(
      transaction,
      mutation.entidadeId,
      "PENDING_SYNC",
      timestamp,
    );
  }

  if (mutation.entidadeTipo === "TAREFA") {
    const task = await taskStore.get(mutation.entidadeId);
    if (task) {
      await taskStore.put({
        ...task,
        syncStatus: await tarefaSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        updatedAt: timestamp,
      });
    }
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

  await transaction.done;
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
  const parsed = typeof value === "number"
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
): TarefaRecord | null {
  const id = textValue(payload.id) || textValue(event.entidadeId);
  const obraId = textValue(payload.obraId);
  const titulo = textValue(payload.titulo);
  const prioridade = taskPriorityFromPayload(payload.prioridade);

  if (!id || !obraId || !titulo || prioridade === null) {
    return null;
  }

  const payloadVersion = payload.versaoEntidade;
  const version = typeof event.versaoEntidade === "number"
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
  existing: TarefaRecord,
  incoming: TarefaRecord,
): boolean {
  if (
    existing.syncStatus !== undefined &&
    existing.syncStatus !== "SYNCED"
  ) {
    return false;
  }

  const existingVersion = existing.versaoEntidade ?? 0;
  const incomingVersion = incoming.versaoEntidade ?? 0;

  return incomingVersion >= existingVersion;
}

export async function applyPulledEventsAtomically(
  events: SyncPullEvent[],
  nextCommitSeq: number,
): Promise<number> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    [
      "processed_events",
      "rdos",
      "tarefas",
      "sync_state",
      "obras",
      "previsao_snapshots",
    ],
    "readwrite",
  );

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
        const existing = await taskStore.get(incoming.id);

        if (!existing || shouldApplyPulledTask(existing, incoming)) {
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

  await transaction.done;

  return resultingCursor;
}
