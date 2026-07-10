import { getCortexDb } from "../db/cortexDb";
import type {
  LocalRdoChildRecord,
  LocalRdoRecord,
  LocalSyncStatus,
  ObraLocalRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
  ProcessedEventRecord,
  RdoAttachmentRecord,
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
  index: (name: "by-rdo-id") => {
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
  "operational_events",
  "rdo_attachments",
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
): Promise<void> {
  const store = transaction.objectStore("operational_events");

  const records = await store.index("by-rdo-id").getAll(rdoId);

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
      await rdoStore.put({
        ...rdo,
        syncStatus: "PENDING_SYNC",
        updatedAt: nowUtc(),
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        nowUtc(),
      );
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        nowUtc(),
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "PENDING_SYNC",
        nowUtc(),
      );
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
  if (mutation.status !== "CONFLICT") {
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
): Promise<void> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );

  const outboxStore =
    transaction.objectStore("outbox_mutations");
  const rdoStore = transaction.objectStore("rdos");

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
    await updateRdoOperationalEventsSyncStatus(
      transaction,
      currentMutation.entidadeId,
      "SYNCING",
      timestamp,
    );
    await updateRdoAttachmentsSyncStatus(
      transaction,
      currentMutation.entidadeId,
      "SYNCING",
      timestamp,
    );
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
  const timestamp = nowUtc();

  if (result.status === "APLICADA") {
    await outboxStore.put({
      ...mutation,
      status: "SYNCED",
      ultimoErro: null,
      conflito: null,
      updatedAt: timestamp,
    });

    if (rdo) {
      const resultVersion =
        result.resultado &&
        typeof result.resultado.versaoEntidade === "number"
          ? result.resultado.versaoEntidade
          : rdo.versaoEntidade;

      await rdoStore.put({
        ...rdo,
        syncStatus: "SYNCED",
        versaoEntidade: resultVersion,
        updatedAt: timestamp,
      });

      await updateRdoChildrenSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNCED",
        timestamp,
      );
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNCED",
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNCED",
        timestamp,
      );
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
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNC_FAILED",
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNC_FAILED",
        timestamp,
      );
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
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNC_FAILED",
        timestamp,
      );
      await updateRdoAttachmentsSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNC_FAILED",
        timestamp,
      );
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

  const mutation = await outboxStore.get(clientMutationId);

  if (!mutation) {
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

export async function applyPulledEventsAtomically(
  events: SyncPullEvent[],
  nextCommitSeq: number,
): Promise<number> {
  const database = await getCortexDb();

  const transaction = database.transaction(
    [
      "processed_events",
      "rdos",
      "sync_state",
      "obras",
      "previsao_snapshots",
    ],
    "readwrite",
  );

  const processedStore =
    transaction.objectStore("processed_events");
  const rdoStore = transaction.objectStore("rdos");
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
