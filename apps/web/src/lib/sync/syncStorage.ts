import { getCortexDb } from "../db/cortexDb";
import {
  foiSubstituida,
  MARCA_DE_SUPERACAO,
  vaiAdiantarReenviar,
} from "./superacaoDeMutacao";
import type {
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  LocalRdoChildRecord,
  LocalRdoRecord,
  LocalSyncStatus,
  ObraGeometriaLocalRecord,
  ObraLocalRecord,
  OperationalEntityRef,
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

export async function assertCanonicalMutationEventProvenance(
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
  "obras",
  "obra_geometrias",
  // A equipe faltava aqui, e a ausência era a causa raiz de uma enxurrada de
  // conflitos: fora da transação, a versão devolvida pelo servidor não tinha
  // onde ser gravada, então o registro local nunca saía do zero com que nasce.
  "teams",
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

function isObraMutation(mutation: OutboxMutationRecord): boolean {
  return mutation.entidadeTipo === "OBRA";
}

function isGeometriaMutation(mutation: OutboxMutationRecord): boolean {
  return mutation.entidadeTipo === "GEOMETRIA_OBRA";
}

function isTeamMutation(mutation: OutboxMutationRecord): boolean {
  return (mutation.entidadeTipo as string) === "EQUIPE";
}

/**
 * A solicitação de integração é aplicada mesmo quando o servidor recusa a ação.
 *
 * O handler responde APLICADA com `estado: "DISABLED"` no corpo — a mutação foi
 * processada, a sincronização é que não aconteceu. Por isso o recibo precisa
 * sobreviver ao SYNCED: é a única prova do desfecho que a tela tem.
 */
function isIntegracaoMutation(mutation: OutboxMutationRecord): boolean {
  return (mutation.entidadeTipo as string) === "SOLICITACAO_INTEGRACAO";
}

/**
 * Substitui o rascunho local de geometria pela versão que o servidor confirmou.
 *
 * O identificador autoritativo é gerado no servidor, então o registro desenhado
 * offline é removido e regravado sob o id confirmado. Sem isso o mesmo trecho
 * apareceria duas vezes no mapa: uma como rascunho pendente e outra como camada
 * confirmada.
 */
async function applyGeometriaPushResult(
  transaction: {
    objectStore: (name: "obra_geometrias") => {
      get: (key: string) => Promise<ObraGeometriaLocalRecord | undefined>;
      put: (value: ObraGeometriaLocalRecord) => Promise<IDBValidKey>;
      delete: (key: string) => Promise<void>;
    };
  },
  mutation: OutboxMutationRecord,
  result: SyncPushMutationResult,
  timestamp: string,
): Promise<void> {
  const store = transaction.objectStore("obra_geometrias");
  const local = await store.get(mutation.entidadeId);
  if (!local) {
    return;
  }

  const authoritative = objectValue(result.resultado);
  const serverId =
    typeof authoritative.id === "string" && authoritative.id
      ? authoritative.id
      : local.id;
  const status =
    authoritative.status === "ENCERRADA" ? "ENCERRADA" : local.status;

  if (serverId !== local.id) {
    await store.delete(local.id);
  }

  await store.put({
    ...local,
    id: serverId,
    status,
    geometry: authoritative.geometry ?? local.geometry,
    fonte:
      typeof authoritative.fonte === "string"
        ? authoritative.fonte
        : local.fonte,
    validoDesde:
      typeof authoritative.validoDesde === "string"
        ? authoritative.validoDesde
        : local.validoDesde,
    validoAte:
      typeof authoritative.validoAte === "string"
        ? authoritative.validoAte
        : status === "ENCERRADA"
          ? local.validoAte ?? timestamp
          : null,
    versao:
      typeof authoritative.versao === "number"
        ? authoritative.versao
        : local.versao,
    syncStatus: "SYNCED",
    fetchedAt: timestamp,
    updatedAt: timestamp,
  });
}

/** Marca o rascunho local quando o servidor recusou ou conflitou a geometria. */
async function markGeometriaMutationFailed(
  transaction: {
    objectStore: (name: "obra_geometrias") => {
      get: (key: string) => Promise<ObraGeometriaLocalRecord | undefined>;
      put: (value: ObraGeometriaLocalRecord) => Promise<IDBValidKey>;
    };
  },
  mutation: OutboxMutationRecord,
  syncStatus: "CONFLICT" | "ERROR" | "PENDING_SYNC",
  timestamp: string,
): Promise<void> {
  const store = transaction.objectStore("obra_geometrias");
  const local = await store.get(mutation.entidadeId);
  if (!local) {
    return;
  }
  await store.put({ ...local, syncStatus, updatedAt: timestamp });
}

/**
 * O estado de sincronização de uma equipe, somado a partir do que resta na
 * fila. Espelha o de obra de propósito: quem decide o estado é o conjunto de
 * mutações pendentes, não a última que voltou.
 */
async function teamSyncStatusFromOutbox(
  store: OutboxEntityMutationStore,
  teamId: string,
): Promise<LocalSyncStatus> {
  return localSyncStatusFromMutations(
    (await store.index("by-entity-id").getAll(teamId)).filter(
      (mutation) => (mutation.entidadeTipo as string) === "EQUIPE",
    ),
  );
}

async function obraSyncStatusFromOutbox(
  store: OutboxEntityMutationStore,
  obraId: string,
): Promise<LocalSyncStatus> {
  return localSyncStatusFromMutations(
    effectiveObraMutations(
      (await store.index("by-entity-id").getAll(obraId)).filter(
        (mutation) => mutation.entidadeTipo === "OBRA",
      ),
    ),
  );
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

export function effectiveObraMutations(
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
    const replacementId =
      mutation.status === "REJECTED" &&
        typeof mutation.blockedReason === "string"
      ? /^SUPERSEDED_BY:([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/i.exec(
          mutation.blockedReason.trim(),
        )?.[1]
      : undefined;
    const replacement = replacementId
      ? byId.get(replacementId)
      : undefined;
    if (
      replacement &&
      isCanonicalOutboxMutation(mutation) &&
      isCanonicalOutboxMutation(replacement) &&
      replacement.entidadeTipo === "OBRA" &&
      replacement.entidadeId === mutation.entidadeId &&
      replacement.causationId === mutation.clientMutationId
    ) {
      supersededIds.add(mutation.clientMutationId);
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

/**
 * Reapoia na versão nova as edições da mesma entidade que ainda estão na fila.
 *
 * <p>Sem isso, uma sequência de gestos sobre a mesma coisa se envenena. Três
 * pessoas adicionadas à mesma equipe antes de qualquer sincronização produzem
 * três mutações com a <em>mesma</em> versão-base: a primeira aplica e leva a
 * equipe adiante, e as outras duas chegam com base vencida e voltam como
 * conflito. O painel mostra três membros, o cartão mostra zero, e nada na fila
 * dá sinal de que o segundo gesto nunca teve chance.
 *
 * <p>Reapoiar aqui é seguro pelo lugar de onde isto é chamado: o recibo de uma
 * mutação <em>nossa</em> que acabou de aplicar. A versão nova é consequência do
 * gesto anterior desta mesma pessoa, neste mesmo aparelho — quem fez a segunda
 * edição já sabia da primeira. Conflito de verdade é outro caso: vem de outro
 * aparelho, chega pelo pull, e continua exigindo decisão.
 *
 * <p>CREATE fica de fora porque criar exige base nula; dar-lhe uma versão
 * tornaria o envelope inválido. E os dois apelidos da versão são escritos
 * juntos: o contrato canônico exige `baseVersao === baseVersion`, e mexer em um
 * só faria a mutação ser recusada localmente como incoerente. O hash não entra
 * nessa conta — ele cobre o payload, não a versão-base.
 */
async function rebasePendingSiblings(
  store: OutboxEntityMutationStore,
  applied: OutboxMutationRecord,
  resultVersion: number | null,
  timestamp: string,
): Promise<void> {
  if (
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
      candidate.clientMutationId === applied.clientMutationId ||
      candidate.entidadeTipo !== applied.entidadeTipo ||
      candidate.entidadeId !== applied.entidadeId ||
      candidate.status !== "PENDING" ||
      candidate.baseVersao === null ||
      candidate.baseVersao === undefined
    ) {
      continue;
    }
    // Já está na versão nova ou adiante dela: reescrever seria retroceder.
    if (candidate.baseVersao >= (resultVersion as number)) {
      continue;
    }
    await store.put({
      ...candidate,
      baseVersao: resultVersion as number,
      ...(isCanonicalOutboxMutation(candidate)
        ? { baseVersion: resultVersion as number }
        : {}),
      updatedAt: timestamp,
    });
  }
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

const LEGACY_RDO_CREATION_PROVENANCE_ERROR =
  "A proveniência de criação do RDO não pode ser alterada.";

function isRecoverableLegacyRdoProvenanceFailure(
  mutation: OutboxMutationRecord,
): boolean {
  return (
    !isCanonicalOutboxMutation(mutation) &&
    !isDefinitelyNonAppliedSuperseded(mutation) &&
    mutation.entidadeTipo === "RDO" &&
    mutation.operacao === "ATUALIZAR_RDO_RASCUNHO" &&
    mutation.status === "ERROR" &&
    mutation.ultimoErro?.trim() ===
      LEGACY_RDO_CREATION_PROVENANCE_ERROR
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
  const obraStore = transaction.objectStore("obras");

  const syncingMutations =
    await outboxStore.index("by-status").getAll("SYNCING");
  const legacyProvenanceFailures = (
    await outboxStore.index("by-status").getAll("ERROR")
  ).filter(isRecoverableLegacyRdoProvenanceFailure);

  for (const mutation of [
    ...syncingMutations,
    ...legacyProvenanceFailures,
  ]) {
    const timestamp = nowUtc();
    const repairsLegacyProvenance =
      isRecoverableLegacyRdoProvenanceFailure(mutation);
    const updatedMutation: OutboxMutationRecord = {
      ...mutation,
      status: "PENDING",
      tentativas: repairsLegacyProvenance
        ? 0
        : mutation.tentativas,
      ultimaTentativaEm: repairsLegacyProvenance
        ? null
        : mutation.ultimaTentativaEm,
      nextAttemptAt: null,
      lastSafeCode: repairsLegacyProvenance
        ? "RDO_CREATION_PROVENANCE_REPAIRED"
        : isCanonicalOutboxMutation(mutation)
          ? "INTERRUPTED_RUN"
          : mutation.lastSafeCode,
      ultimoErro: repairsLegacyProvenance
        ? "Reenviando o RDO com a proveniência de criação preservada pelo servidor."
        : "Sincronização anterior foi interrompida antes da confirmação.",
      conflito: repairsLegacyProvenance
        ? null
        : mutation.conflito,
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
    if (isObraMutation(mutation)) {
      const obra = await obraStore.get(mutation.entidadeId);
      if (obra) {
        await obraStore.put({
          ...obra,
          syncStatus: await obraSyncStatusFromOutbox(
            outboxStore,
            mutation.entidadeId,
          ),
          ultimoErro: updatedMutation.ultimoErro,
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
          ultimoErro: updatedMutation.ultimoErro,
          updatedAt: timestamp,
        });
      }
    }
  }

  await guardedTransaction.complete();
}

/**
 * Tipos que o servidor sabe resolver como entidade relacionada da geometria.
 *
 * Espelha a lista canônica do sync: só entram tipos que têm tabela e obra
 * própria para o servidor conferir o escopo.
 */
const RELACAO_CANONICA_DA_GEOMETRIA: ReadonlySet<string> = new Set([
  "OBRA",
  "RDO",
  "TAREFA",
]);

/**
 * Recusas do servidor causadas por lacuna de contrato que já foi fechada.
 *
 * A geometria foi recusada duas vezes por listas que o servidor mantinha à mão
 * e não conheciam o tipo (`GEOMETRIA_OBRA`) nem as operações de geometria —
 * com o handler pronto dos dois lados. As mensagens abaixo identificam
 * exatamente essas recusas; uma mutação rejeitada com uma delas está correta
 * no dispositivo e volta à fila, porque o servidor atual a aceita.
 *
 * A comparação é por texto exato de propósito: qualquer outra rejeição segue
 * parada em revisão, já que reenviar às cegas repetiria a recusa a cada ciclo.
 */
const REJEICOES_DE_CONTRATO_CORRIGIDAS: ReadonlySet<string> = new Set([
  "entityType canônico não suportado.",
  "operacao não corresponde à operação canônica.",
]);

export function isRejeicaoDeContratoJaCorrigida(
  ultimoErro: string | null,
): boolean {
  return ultimoErro !== null &&
    REJEICOES_DE_CONTRATO_CORRIGIDAS.has(ultimoErro.trim());
}

export function geometryRelatedEntitiesAfterRepair(
  mutation: Pick<
    CanonicalOutboxMutationRecord,
    "obraId" | "relatedEntities"
  >,
): OperationalEntityRef[] {
  const obraId = mutation.obraId;
  const aceitas: OperationalEntityRef[] = [];
  for (const relacao of mutation.relatedEntities) {
    if (!RELACAO_CANONICA_DA_GEOMETRIA.has(relacao.tipo)) {
      continue;
    }
    if (aceitas.some((atual) => atual.tipo === relacao.tipo &&
      atual.id === relacao.id)) {
      continue;
    }
    aceitas.push({ ...relacao });
  }
  if (obraId && !aceitas.some((atual) => atual.tipo === "OBRA")) {
    aceitas.unshift({ tipo: "OBRA", id: obraId, nome: null });
  }
  return aceitas;
}

/**
 * Devolve à fila as geometrias recusadas por um contrato que o servidor não
 * reconhecia.
 *
 * O trecho desenhado viajava com o assunto do desenho ("TRECHO") como entidade
 * relacionada, e o servidor só resolve entidades que existem como tabela. O
 * push inteiro era recusado, e cada trecho marcado virava um registro em
 * revisão que jamais subiria — travando junto todo o resto da fila.
 *
 * A recusa é do envelope, não do desenho: as coordenadas seguem íntegras no
 * dispositivo. Aqui o envelope é reescrito no formato aceito e a mutação volta
 * a PENDING, preservando o trabalho de quem já marcou o trecho em campo. O hash
 * canônico cobre apenas o payload, que não é tocado.
 */
export async function recoverRejectedGeometryMutationsForSync(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);

  const guardedTransaction = guardSyncTransaction(
    database.transaction(RDO_SYNC_TRANSACTION_STORES, "readwrite"),
    guard,
  );
  const transaction = guardedTransaction.transaction;
  const outboxStore = transaction.objectStore("outbox_mutations");
  const geometryStore = transaction.objectStore("obra_geometrias");

  const rejeitadas = await outboxStore
    .index("by-status")
    .getAll("REJECTED");
  let recuperadas = 0;

  for (const mutation of rejeitadas) {
    if (
      mutation.entidadeTipo !== "GEOMETRIA_OBRA" ||
      !isCanonicalOutboxMutation(mutation)
    ) {
      continue;
    }
    const relacoes = geometryRelatedEntitiesAfterRepair(mutation);
    const envelopeJaAceito =
      relacoes.length === mutation.relatedEntities.length &&
      relacoes.every((relacao, indice) =>
        relacao.tipo === mutation.relatedEntities[indice].tipo &&
        relacao.id === mutation.relatedEntities[indice].id);
    if (
      envelopeJaAceito &&
      !isRejeicaoDeContratoJaCorrigida(mutation.ultimoErro)
    ) {
      // O envelope já está no formato aceito e a recusa não é uma das
      // lacunas de contrato conhecidas: reenviar às cegas só repetiria a
      // rejeição.
      continue;
    }

    const timestamp = nowUtc();
    // Cada ramo declara o que realmente aconteceu: rotular a volta à fila
    // pós-correção do servidor como "entidades reparadas" afirmaria um
    // conserto de envelope que não houve.
    const safeCode = envelopeJaAceito
      ? "GEOMETRY_SERVER_CONTRACT_REPAIRED"
      : "GEOMETRY_RELATED_ENTITY_REPAIRED";
    const updatedMutation: CanonicalOutboxMutationRecord = {
      ...mutation,
      relatedEntities: relacoes,
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      nextAttemptAt: null,
      blockedReason: null,
      retryAttempt: 0,
      lastSafeCode: safeCode,
      ultimoErro: envelopeJaAceito
        ? "Reenviando a geometria depois da correção do contrato no servidor."
        : "Reenviando a geometria com as entidades relacionadas aceitas pelo servidor.",
      conflito: null,
      updatedAt: timestamp,
    };
    await outboxStore.put(updatedMutation);

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

    const geometria = await geometryStore.get(mutation.entidadeId);
    if (geometria) {
      await geometryStore.put({
        ...geometria,
        syncStatus: "PENDING_SYNC",
        updatedAt: timestamp,
      });
    }
    recuperadas += 1;
  }

  await guardedTransaction.complete();
  return recuperadas;
}

/**
 * Recusa do servidor quando a obra alvo está arquivada.
 *
 * Texto exato, como as demais recusas reconhecidas: qualquer outra continua
 * parada em revisão, porque reenviar às cegas repetiria a negativa a cada
 * ciclo.
 */
const REJEICAO_DE_OBRA_ARQUIVADA = "Obra não encontrada ou arquivada.";

export function isRejeicaoDeObraArquivada(
  ultimoErro: string | null | undefined,
): boolean {
  return typeof ultimoErro === "string" &&
    ultimoErro.trim() === REJEICAO_DE_OBRA_ARQUIVADA;
}

function obraAlvoDaMutacao(mutation: OutboxMutationRecord): string | null {
  if (mutation.entidadeTipo === "OBRA") {
    return mutation.entidadeId;
  }
  const obraId = isCanonicalOutboxMutation(mutation)
    ? mutation.obraId
    : null;
  return obraId && obraId.trim() ? obraId : null;
}

/**
 * Devolve à fila o que foi recusado porque a obra estava arquivada.
 *
 * Arquivar esconde a obra da operação; não desfaz o que já foi vivido em
 * campo. O servidor aceita a convergência desses lançamentos mesmo com a obra
 * arquivada — recusá-los prenderia no dispositivo a única cópia do que
 * aconteceu. A recusa por arquivamento, portanto, nunca é terminal aqui:
 * enquanto a obra existir neste dispositivo, o lançamento volta a PENDING e
 * sobe de novo, sem exigir restauração nem gesto de ninguém.
 *
 * Servidores antigos ainda recusam; nesse caso o registro apenas volta para
 * revisão e o ciclo seguinte tenta outra vez, até o servidor acompanhar.
 *
 * A recusa "Obra não encontrada neste servidor." fica deliberadamente de
 * fora: ela é permanente — o identificador não existe do outro lado — e
 * reenviá-la a cada ciclo era um laço infinito que nunca drenava. Esse caso
 * espera a reidentificação da obra, não a repetição da mesma negativa.
 */
export async function recoverRejectedArchivedObraMutationsForSync(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);

  const guardedTransaction = guardSyncTransaction(
    database.transaction(RDO_SYNC_TRANSACTION_STORES, "readwrite"),
    guard,
  );
  const transaction = guardedTransaction.transaction;
  const outboxStore = transaction.objectStore("outbox_mutations");
  const obraStore = transaction.objectStore("obras");

  const todas = await outboxStore.getAll();
  const timestamp = nowUtc();
  let recuperadas = 0;

  for (const mutation of todas) {
    if (
      mutation.status !== "REJECTED" ||
      !isCanonicalOutboxMutation(mutation) ||
      !isRejeicaoDeObraArquivada(mutation.ultimoErro) ||
      // Uma mutação substituída por outra foi trocada de propósito;
      // ressuscitá-la duplicaria o lançamento.
      (mutation.blockedReason !== null &&
        SUPERSEDIDA_POR.test(mutation.blockedReason.trim()))
    ) {
      continue;
    }
    const obraId = obraAlvoDaMutacao(mutation);
    if (!obraId) {
      continue;
    }
    // Obra presente no dispositivo é o suficiente: arquivada ou não, o
    // servidor aceita a convergência. Só a obra ausente segue parada — obra
    // nenhuma nasce no cliente, então ausência local é anomalia, não estado.
    const obra = await obraStore.get(obraId);
    if (!obra) {
      continue;
    }

    // O evento é lido antes da escrita: um registro sem evento único não pode
    // derrubar a recuperação dos demais, porque esta rotina existe justamente
    // para destravar uma fila já parada. Ele fica onde está, visível em
    // revisão, que é a leitura verdadeira do que aconteceu com ele.
    let event: CanonicalOperationalEventRecord;
    try {
      event = await exactCanonicalEvent(
        transaction,
        mutation.clientMutationId,
      );
    } catch {
      continue;
    }

    await outboxStore.put({
      ...mutation,
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      nextAttemptAt: null,
      blockedReason: null,
      retryAttempt: 0,
      lastSafeCode: "CONVERGENCIA_DE_OBRA_ARQUIVADA",
      ultimoErro:
        "Reenviando: o registro de campo converge mesmo com a obra arquivada.",
      conflito: null,
      updatedAt: timestamp,
    });
    await putCanonicalEvent(transaction, {
      ...event,
      result: "PENDING",
      syncStatus: "PENDING_SYNC",
      errorCategory: "CONVERGENCIA_DE_OBRA_ARQUIVADA",
    });
    recuperadas += 1;
  }

  await guardedTransaction.complete();
  return recuperadas;
}

const SUPERSEDIDA_POR = /^SUPERSEDED_BY:/i;


/**
 * Devolve à fila, a pedido explícito de quem opera, tudo o que está em revisão.
 *
 * As recuperações automáticas cobrem as recusas que o cliente sabe reconhecer.
 * O que elas não cobrem ficava sem saída nenhuma: a tela dizia "revisão
 * necessária" e o único botão à mão — "Sincronizar agora" — não toca em
 * registro recusado. Quem estava em campo não tinha o que fazer.
 *
 * Reenviar pode encontrar a mesma recusa, e isso é aceitável: quem pediu sabe
 * o motivo exibido e escolheu tentar. O que não é aceitável é a fila não ter
 * porta de saída. Uma mutação substituída por outra não volta — ela foi
 * trocada de propósito, e reenviá-la duplicaria o lançamento.
 */
export async function requeueMutationsInReview(): Promise<number> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );
  const outboxStore = transaction.objectStore("outbox_mutations");
  const timestamp = nowUtc();
  const identidadesTrocadas = new Map<string, string>();
  let devolvidas = 0;

  // A regra de superação precisa enxergar a fila inteira, não só as recusadas:
  // a substituta que salva uma original está justamente fora deste conjunto.
  const emRevisao = await outboxStore.getAll();

  for (const mutation of await outboxStore.index("by-status").getAll(
    "REJECTED",
  )) {
    if (
      !isCanonicalOutboxMutation(mutation) ||
      (mutation.blockedReason !== null &&
        SUPERSEDIDA_POR.test(mutation.blockedReason.trim())) ||
      // Recusa sobre a versão-base não muda com reenvio, porque o reenvio
      // preserva a versão-base. Reapresentá-la só devolveria a mesma recusa e
      // recontaria a mesma pilha — o laço que prendia a tela.
      !vaiAdiantarReenviar(mutation, emRevisao)
    ) {
      continue;
    }

    let event: CanonicalOperationalEventRecord;
    try {
      event = await exactCanonicalEvent(
        transaction,
        mutation.clientMutationId,
      );
    } catch {
      continue;
    }

    /*
     * O reenvio troca a identidade da mutação, e é isso que o faz funcionar.
     *
     * O servidor decide cada clientMutationId uma vez só e arquiva o veredito.
     * Reapresentar o mesmo identificador não repete o julgamento: a segunda
     * chegada colide na chave, e a resposta vem do arquivo — a regra de negócio
     * sequer é consultada. Mutações recusadas sob uma regra que depois mudou
     * ficavam presas por isso, recebendo para sempre a recusa de uma regra que
     * não existe mais, por mais vezes que se pedisse o reenvio.
     *
     * A idempotência existe para impedir que a *mesma entrega* seja aplicada
     * duas vezes. Um reenvio pedido por quem opera não é a mesma entrega: é uma
     * tentativa nova, e tentativa nova merece identidade nova.
     *
     * É seguro exatamente aqui, e só aqui, porque esta função toca apenas
     * linhas em REJECTED — que por definição não foram aplicadas. Não há efeito
     * no servidor para duplicar. O payload e seu hash seguem intactos: a
     * identidade não entra no hash canônico.
     */
    const identidadeAnterior = mutation.clientMutationId;
    const identidadeNova = crypto.randomUUID();

    await outboxStore.delete(identidadeAnterior);
    await outboxStore.put({
      ...mutation,
      clientMutationId: identidadeNova,
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      nextAttemptAt: null,
      blockedReason: null,
      retryAttempt: 0,
      lastSafeCode: "REVISAO_LIBERADA_MANUALMENTE",
      ultimoErro: "Reenviando a pedido de quem opera o dispositivo.",
      conflito: null,
      updatedAt: timestamp,
    });
    // O evento canônico conserva a própria chave e a correlação: o que mudou
    // foi a tentativa, não o fato registrado. Sem reapontá-lo, a busca por
    // clientMutationId deixaria de achar o evento desta mutação.
    await putCanonicalEvent(transaction, {
      ...event,
      clientMutationId: identidadeNova,
      result: "PENDING",
      syncStatus: "PENDING_SYNC",
      errorCategory: "REVISAO_LIBERADA_MANUALMENTE",
    });
    identidadesTrocadas.set(identidadeAnterior, identidadeNova);
    // O crachá de sincronização do registro é derivado da fila; sem recalcular
    // aqui, a lista continuaria mostrando falha para algo que voltou a subir.
    if (mutation.entidadeTipo === "RDO") {
      const rdoStore = transaction.objectStore("rdos");
      const rdo = await rdoStore.get(mutation.entidadeId);
      if (rdo) {
        await rdoStore.put({
          ...rdo,
          syncStatus: await rdoSyncStatusFromOutbox(
            outboxStore,
            mutation.entidadeId,
          ),
          updatedAt: timestamp,
        });
      }
    }
    if (mutation.entidadeTipo === "OBRA") {
      const obraStore = transaction.objectStore("obras");
      const obra = await obraStore.get(mutation.entidadeId);
      if (obra) {
        await obraStore.put({
          ...obra,
          syncStatus: await obraSyncStatusFromOutbox(
            outboxStore,
            mutation.entidadeId,
          ),
          ultimoErro: null,
          updatedAt: timestamp,
        });
      }
    }
    devolvidas += 1;
  }

  /*
   * Quem esperava por uma das mutações renomeadas precisa passar a esperar pela
   * identidade nova. Uma dependência apontando para um identificador que deixou
   * de existir nunca é satisfeita: o servidor responde DEPENDENCY_NOT_APPLIED
   * indefinidamente, e o reenvio que deveria destravar a fila travaria o que
   * vinha atrás dela.
   */
  if (identidadesTrocadas.size > 0) {
    for (const row of await outboxStore.getAll()) {
      const dependencias = row.dependsOnMutationIds;
      if (
        !dependencias?.length ||
        !dependencias.some((id) => identidadesTrocadas.has(id))
      ) {
        continue;
      }
      await outboxStore.put({
        ...row,
        dependsOnMutationIds: dependencias.map(
          (id) => identidadesTrocadas.get(id) ?? id,
        ),
        updatedAt: timestamp,
      });
    }
  }

  await transaction.done;
  return devolvidas;
}

const REJEICAO_DE_OBRA_INEXISTENTE_NO_SERVIDOR =
  "Obra não encontrada neste servidor.";

export function isRejeicaoDeObraInexistenteNoServidor(
  ultimoErro: string | null | undefined,
): boolean {
  return typeof ultimoErro === "string" &&
    ultimoErro.trim() === REJEICAO_DE_OBRA_INEXISTENTE_NO_SERVIDOR;
}

const OBRA_REIDENTIFICADA = "OBRA_REIDENTIFICADA_NO_CLIENTE";

/**
 * Duas obras são a mesma obra quando carregam a mesma identidade de contrato.
 *
 * O identificador técnico não serve de identidade aqui — é justamente ele que
 * morreu. O que sobrevive à perda de um banco é o que está no papel: o código
 * do contrato, e na falta dele o nome com o cliente.
 */
function identidadeDeObraCoincide(
  esta: ObraLocalRecord,
  outra: ObraLocalRecord,
): boolean {
  const contratoUm = canonicalObraReference(esta.codigoContrato);
  const contratoOutro = canonicalObraReference(outra.codigoContrato);
  if (contratoUm && contratoOutro) {
    return contratoUm === contratoOutro;
  }
  const nomeUm = canonicalObraReference(esta.nome);
  const nomeOutro = canonicalObraReference(outra.nome);
  const clienteUm = canonicalObraReference(esta.cliente);
  const clienteOutro = canonicalObraReference(outra.cliente);
  const mesmoCliente =
    !clienteUm || !clienteOutro || clienteUm === clienteOutro;
  return Boolean(
    nomeUm && nomeOutro && nomeUm === nomeOutro && mesmoCliente,
  );
}

function obraGemeaDaFantasma(
  fantasma: ObraLocalRecord,
  obras: ObraLocalRecord[],
): ObraLocalRecord | null {
  const candidatas = obras.filter(
    (obra) =>
      obra.id !== fantasma.id &&
      identidadeDeObraCoincide(fantasma, obra),
  );
  return candidatas.length === 1 ? candidatas[0] : null;
}

/**
 * Troca toda referência exata ao identificador morto pelo da obra gêmea.
 *
 * A troca é por igualdade completa do valor, nunca por substring: um UUID só
 * aparece inteiro, e os textos livres (observações, marcadores de origem)
 * carregam nomes, não identificadores.
 */
function substituirReferenciaProfunda(
  value: unknown,
  de: string,
  para: string,
): unknown {
  if (typeof value === "string") {
    return value === de ? para : value;
  }
  if (Array.isArray(value)) {
    return value.map((item) =>
      substituirReferenciaProfunda(item, de, para),
    );
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(
        ([key, item]) => [
          key,
          substituirReferenciaProfunda(item, de, para),
        ],
      ),
    );
  }
  return value;
}

function payloadCanonicoReidentificado(
  payload: Record<string, unknown>,
  obraMortaId: string,
  alvo: ObraLocalRecord,
): Record<string, unknown> {
  const trocado = substituirReferenciaProfunda(
    payload,
    obraMortaId,
    alvo.id,
  ) as Record<string, unknown>;
  // O contrato acompanha a obra nova; a programação apontava para uma agenda
  // que só existia no banco perdido e não tem correspondente para onde migrar.
  if ("contrato" in trocado && alvo.codigoContrato) {
    trocado.contrato = alvo.codigoContrato;
  }
  if ("programacaoId" in trocado) {
    trocado.programacaoId = null;
  }
  return trocado;
}

interface ReidentificacaoPlanejada {
  original: CanonicalOutboxMutationRecord;
  originalJson: string;
  parqueiaParaContexto: boolean;
  substituta: CanonicalOutboxMutationRecord | null;
  substitutaEvento: CanonicalOperationalEventRecord | null;
}

/**
 * Reidentifica, para a obra gêmea viva, os envelopes presos numa obra que o
 * servidor diz não existir.
 *
 * É o desfecho da recusa "Obra não encontrada neste servidor.": o identificador
 * que esses envelopes carregam morreu do outro lado — um banco recriado, uma
 * obra recadastrada — e nenhum reenvio o ressuscita. Mas a obra, como fato de
 * contrato, continua existindo: se este dispositivo conhece exatamente uma
 * outra obra com a mesma identidade (código de contrato, ou nome e cliente),
 * os lançamentos de campo pertencem a ela.
 *
 * Vale para o que carrega dado de campo preso a uma obra: o RDO e a geometria
 * marcada sobre o mapa. O trecho desenhado é tão insubstituível quanto o
 * lançamento — quem o marcou estava lá, e o desenho só existe neste
 * dispositivo.
 *
 * Envelopes canônicos são imutáveis; a correção nasce como substituição
 * rastreável (SUPERSEDED_BY), nunca como edição. A criação de RDO é a única
 * exceção: ela volta à fila bloqueada por RDO_CREATION_CONTEXT_REQUIRED e o
 * pipeline de hidratação de contexto — que já sabe buscar o recibo novo e
 * cunhar a substituição — faz o resto, agora contra a obra certa. As demais
 * operações da cadeia são substituídas aqui mesmo, para que nenhuma continue
 * subindo com o identificador morto.
 *
 * Uma entidade que já viveu sob a obra de destino não volta a trocar de obra:
 * sem essa trava, duas obras mortas com a mesma identidade fariam os envelopes
 * quicarem de uma para a outra a cada ciclo, para sempre.
 *
 * As mutações de ciclo de vida da própria OBRA morta (arquivar, restaurar)
 * ficam de fora de propósito: não carregam dado de campo, e transplantá-las
 * aplicaria à obra viva um gesto feito sobre a morta.
 */
const ENTIDADES_REIDENTIFICAVEIS: ReadonlySet<string> = new Set([
  "RDO",
  "GEOMETRIA_OBRA",
]);

export async function reidentificarObrasInexistentesForSync(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const todas = await database.getAll("outbox_mutations");
  const obras = await database.getAll("obras");
  assertSyncSession(guard);

  const recusadas = todas.filter(
    (mutation): mutation is CanonicalOutboxMutationRecord =>
      isCanonicalOutboxMutation(mutation) &&
      ENTIDADES_REIDENTIFICAVEIS.has(mutation.entidadeTipo) &&
      mutation.status === "REJECTED" &&
      isRejeicaoDeObraInexistenteNoServidor(mutation.ultimoErro) &&
      !(
        mutation.blockedReason !== null &&
        SUPERSEDIDA_POR.test(mutation.blockedReason.trim())
      ),
  );
  if (recusadas.length === 0) {
    return 0;
  }

  const porEntidade = new Map<string, CanonicalOutboxMutationRecord[]>();
  for (const mutation of recusadas) {
    const chave = `${mutation.entidadeTipo}:${mutation.entidadeId}`;
    const lista = porEntidade.get(chave) ?? [];
    lista.push(mutation);
    porEntidade.set(chave, lista);
  }

  let reidentificadas = 0;
  const baseInstante = Date.parse(nowUtc());
  let deslocamento = 0;

  for (const rejeitadasDaEntidade of porEntidade.values()) {
    assertSyncSession(guard);
    try {
      const entidadeTipo = rejeitadasDaEntidade[0].entidadeTipo;
      const entidadeId = rejeitadasDaEntidade[0].entidadeId;
      const obraMortaId = rejeitadasDaEntidade[0].obraId;
      if (
        !obraMortaId ||
        rejeitadasDaEntidade.some(
          (mutation) => mutation.obraId !== obraMortaId,
        )
      ) {
        continue;
      }
      const fantasma = obras.find((obra) => obra.id === obraMortaId);
      if (!fantasma) {
        continue;
      }
      const alvo = obraGemeaDaFantasma(fantasma, obras);
      if (!alvo) {
        continue;
      }

      const cadeia = todas
        .filter(
          (mutation): mutation is CanonicalOutboxMutationRecord =>
            isCanonicalOutboxMutation(mutation) &&
            mutation.entidadeTipo === entidadeTipo &&
            mutation.entidadeId === entidadeId,
        )
        .sort((esquerda, direita) =>
          esquerda.criadaNoClienteEm.localeCompare(
            direita.criadaNoClienteEm,
          ),
        );

      // A trava contra o pingue-pongue: se qualquer envelope desta entidade já
      // carregou a obra de destino, esta cadeia já foi reidentificada uma vez
      // e a nova recusa significa que o destino também não existe. Trocar de
      // volta só reiniciaria o circuito.
      if (cadeia.some((mutation) => mutation.obraId === alvo.id)) {
        continue;
      }

      const ativas = cadeia.filter(
        (mutation) =>
          !(
            mutation.blockedReason !== null &&
            SUPERSEDIDA_POR.test(mutation.blockedReason.trim())
          ) &&
          (
            mutation.status === "PENDING" ||
            mutation.status === "ERROR" ||
            (
              mutation.status === "REJECTED" &&
              isRejeicaoDeObraInexistenteNoServidor(mutation.ultimoErro)
            )
          ),
      );
      if (ativas.length === 0) {
        continue;
      }

      const registroLocal = entidadeTipo === "RDO"
        ? await database.get("rdos", entidadeId)
        : await database.get("obra_geometrias", entidadeId);
      if (!registroLocal || registroLocal.obraId !== obraMortaId) {
        continue;
      }

      // Primeira fase, fora de transação: montar as substitutas — o hash de
      // proveniência é assíncrono e uma transação IndexedDB não sobrevive a
      // um await que não seja dela.
      const substituicaoPorOriginal = new Map<string, string>();
      const planos: ReidentificacaoPlanejada[] = [];
      let plausivel = true;

      for (const original of ativas) {
        if (original.operacao === "CRIAR_RDO") {
          planos.push({
            original,
            originalJson: canonicalMutationJson(original),
            parqueiaParaContexto: true,
            substituta: null,
            substitutaEvento: null,
          });
          continue;
        }

        const eventos = await database.getAllFromIndex(
          "operational_events",
          "by-client-mutation-id",
          original.clientMutationId,
        );
        if (eventos.length !== 1 || eventos[0].schemaVersion !== 13) {
          plausivel = false;
          break;
        }
        const eventoOriginal =
          eventos[0] as CanonicalOperationalEventRecord;

        deslocamento += 1;
        const occurredAt = new Date(
          baseInstante + deslocamento,
        ).toISOString();
        const construida = await buildCanonicalMutation({
          deviceId: original.deviceId,
          userId: original.userId,
          obraId: alvo.id,
          entityType: original.entityType,
          entityId: original.entityId,
          operation: original.operation,
          transportOperation: original.operacao,
          baseVersion: original.baseVersion,
          occurredAt,
          previousSnapshot: original.payload,
          nextSnapshot: payloadCanonicoReidentificado(
            original.payload,
            obraMortaId,
            alvo,
          ),
          authorizationScope: original.trace.authorizationScope.map(
            (escopo) => (escopo === obraMortaId ? alvo.id : escopo),
          ),
          correlationId: original.correlationId,
          causationId: original.clientMutationId,
          transport: original.transport,
          dependsOnMutationIds: (
            original.dependsOnMutationIds ?? []
          ).map(
            (dependencia) =>
              substituicaoPorOriginal.get(dependencia) ?? dependencia,
          ),
          relatedEntities: substituirReferenciaProfunda(
            original.relatedEntities ?? [],
            obraMortaId,
            alvo.id,
          ) as OperationalEntityRef[],
        });
        substituicaoPorOriginal.set(
          original.clientMutationId,
          construida.mutation.clientMutationId,
        );
        planos.push({
          original,
          originalJson: canonicalMutationJson(original),
          parqueiaParaContexto: false,
          substituta: construida.mutation,
          substitutaEvento: {
            ...eventoOriginal,
            id: construida.mutation.trace.ontologyEventId,
            obraId: alvo.id,
            principalEntity: substituirReferenciaProfunda(
              eventoOriginal.principalEntity,
              obraMortaId,
              alvo.id,
            ) as OperationalEventRecord["principalEntity"],
            relatedEntities: [
              ...construida.mutation.relatedEntities,
            ],
            occurredAt: construida.mutation.occurredAt,
            syncedAt: null,
            origin: "OFFLINE",
            payload: construida.nextSnapshot,
            syncStatus: "PENDING_SYNC",
            clientMutationId: construida.mutation.clientMutationId,
            deviceId: construida.mutation.deviceId,
            correlationId: construida.mutation.correlationId,
            causationId: construida.mutation.causationId,
            previousState: construida.previousSnapshot,
            newState: construida.nextSnapshot,
            result: "PENDING",
            errorCategory: null,
            entityVersion: construida.mutation.baseVersion,
            serverCommitSequence: null,
          },
        });
      }
      if (!plausivel || planos.length === 0) {
        continue;
      }

      // Segunda fase: aplicar tudo de uma vez, conferindo que nada mudou
      // durante a montagem. Cada entidade é atômica por si; uma que falhe não
      // pode segurar a recuperação das demais.
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
      const outboxStore = transaction.objectStore("outbox_mutations");
      const eventStore = transaction.objectStore("operational_events");
      let coerente = true;
      for (const plano of planos) {
        const atual = await outboxStore.get(
          plano.original.clientMutationId,
        );
        if (!atual || canonicalMutationJson(atual) !== plano.originalJson) {
          coerente = false;
          break;
        }
      }
      const rdoAtual = entidadeTipo === "RDO"
        ? await transaction.objectStore("rdos").get(entidadeId)
        : null;
      const geometriaAtual = entidadeTipo === "GEOMETRIA_OBRA"
        ? await transaction.objectStore("obra_geometrias").get(entidadeId)
        : null;
      const principalAtual = rdoAtual ?? geometriaAtual;
      if (
        !coerente ||
        !principalAtual ||
        principalAtual.obraId !== obraMortaId
      ) {
        transaction.abort();
        await guardedTransaction.complete().catch(() => undefined);
        continue;
      }

      for (const plano of planos) {
        if (plano.parqueiaParaContexto) {
          // A criação volta à fila bloqueada pelo recibo de contexto: o
          // pipeline de hidratação busca o recibo da obra viva e cunha a
          // substituição com a proveniência completa.
          await outboxStore.put({
            ...plano.original,
            status: "PENDING",
            tentativas: 0,
            ultimaTentativaEm: null,
            nextAttemptAt: null,
            retryAttempt: 0,
            conflito: null,
            blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
            lastSafeCode: OBRA_REIDENTIFICADA,
            ultimoErro:
              "Obra reidentificada: aguardando o recibo de contexto da obra atual.",
            updatedAt: timestamp,
          });
          const eventosDaCriacao = await eventStore
            .index("by-client-mutation-id")
            .getAll(plano.original.clientMutationId);
          if (
            eventosDaCriacao.length === 1 &&
            eventosDaCriacao[0].schemaVersion === 13
          ) {
            await eventStore.put({
              ...eventosDaCriacao[0],
              result: "PENDING",
              syncStatus: "PENDING_SYNC",
              errorCategory: OBRA_REIDENTIFICADA,
            });
          }
          continue;
        }

        await outboxStore.put({
          ...plano.original,
          status: "REJECTED",
          nextAttemptAt: null,
          lastSafeCode: OBRA_REIDENTIFICADA,
          blockedReason:
            `SUPERSEDED_BY:${plano.substituta!.clientMutationId}`,
          ultimoErro:
            "Envelope substituído após a reidentificação da obra.",
          conflito: null,
          updatedAt: timestamp,
        });
        const eventosDoOriginal = await eventStore
          .index("by-client-mutation-id")
          .getAll(plano.original.clientMutationId);
        if (
          eventosDoOriginal.length === 1 &&
          eventosDoOriginal[0].schemaVersion === 13
        ) {
          await eventStore.put({
            ...eventosDoOriginal[0],
            result: "REJECTED",
            syncStatus: "SYNC_FAILED",
            errorCategory: OBRA_REIDENTIFICADA,
          });
        }
        await outboxStore.add(plano.substituta!);
        await eventStore.add(plano.substitutaEvento!);
      }

      if (rdoAtual) {
        await transaction.objectStore("rdos").put(
          rdoAfterObraReferenceRepair(rdoAtual, alvo, timestamp),
        );
        await updateRdoChildrenSyncStatus(
          transaction,
          entidadeId,
          "PENDING_SYNC",
          timestamp,
        );
        await updateRdoAttachmentsObraReference(
          transaction,
          entidadeId,
          alvo,
          timestamp,
        );
      }
      if (geometriaAtual) {
        // O desenho não muda: só a obra a que ele pertence. As coordenadas
        // marcadas em campo são o dado, e nenhum ponto é recalculado aqui.
        await transaction.objectStore("obra_geometrias").put({
          ...geometriaAtual,
          obraId: alvo.id,
          syncStatus: "PENDING_SYNC",
          updatedAt: timestamp,
        });
      }
      await guardedTransaction.complete();
      reidentificadas += planos.length;
    } catch {
      // Esta entidade fica como está — visível em revisão — e as demais seguem.
      continue;
    }
  }

  if (reidentificadas > 0 && typeof window !== "undefined") {
    window.dispatchEvent(new Event("cortex:local-mutation-queued"));
  }
  return reidentificadas;
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

export async function queueErroredMutationsForRetry(
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

  await guardedTransaction.complete();

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

/**
 * O bloqueio que nenhuma retentativa resolve: um vínculo de mão de obra que o
 * servidor recusa e que o nome registrado não substitui. Quem opera decide.
 */
export const MAO_OBRA_SEM_VINCULO_EXIGE_DECISAO =
  "MAO_OBRA_SEM_VINCULO_EXIGE_DECISAO";

/**
 * Devolve à fila o envelope legado que parou em `ERROR`.
 *
 * `ERROR` é o destino de toda recusa de mutação legada, e nada o lia: a fila de
 * saída só considera `PENDING` e o botão de revisão só enxerga `REJECTED`. O
 * registro ficava parado sem retentativa e sem ação possível na tela, segurando
 * atrás de si a fila inteira daquela entidade.
 *
 * O reenvio é escalonado pelo mesmo relógio das demais retentativas — sem isso,
 * uma recusa determinística voltaria à rede a cada ciclo de trinta segundos,
 * para sempre.
 */
export function mutationAfterErroredRetry(
  mutation: OutboxMutationRecord,
  timestamp: string,
  random?: () => number,
): OutboxMutationRecord | null {
  if (isCanonicalOutboxMutation(mutation)) {
    return null;
  }
  if (isDefinitelyNonAppliedSuperseded(mutation)) {
    return null;
  }
  // Reenviar não resolve o que depende de uma decisão de quem opera; insistir
  // só substituiria o motivo já explicado por um ciclo mudo de retentativas.
  if (mutation.blockedReason === MAO_OBRA_SEM_VINCULO_EXIGE_DECISAO) {
    return null;
  }
  const serverMissingRdo =
    mutation.status === "ERROR" &&
    mutation.operacao === "ATUALIZAR_RDO_RASCUNHO" &&
    mutation.baseVersao === 0 &&
    erroIndicaRdoAusente(mutation.ultimoErro);
  const parsedTimestamp = Date.parse(timestamp);

  return mutationAfterRetryScheduled(
    {
      ...mutation,
      operacao: serverMissingRdo ? "CRIAR_RDO" : mutation.operacao,
      baseVersao: serverMissingRdo ? null : mutation.baseVersao,
      tentativas: 0,
      ultimaTentativaEm: null,
      conflito: null,
    },
    {
      safeCode: serverMissingRdo
        ? "LEGACY_RDO_RECREATED"
        : "LEGACY_ERROR_REQUEUED",
      message: serverMissingRdo
        ? "Reenviando como criação porque o servidor não possui este RDO."
        : "Tentando novamente após correção da sincronização.",
      now: Number.isFinite(parsedTimestamp) ? parsedTimestamp : undefined,
      random,
    },
  );
}

export function conflictServerVersion(
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
  const obraStore = transaction.objectStore("obras");

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
  if (isObraMutation(currentMutation)) {
    const obra = await obraStore.get(currentMutation.entidadeId);
    if (obra) {
      await obraStore.put({
        ...obra,
        syncStatus: await obraSyncStatusFromOutbox(
          outboxStore,
          currentMutation.entidadeId,
        ),
        ultimoErro: null,
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

export function obraAfterConflict(
  obra: ObraLocalRecord,
  result: SyncPushMutationResult,
  timestamp: string,
): ObraLocalRecord {
  const candidateVersion = result.conflito?.versaoAtual;
  const serverVersion =
    typeof candidateVersion === "number" &&
      Number.isFinite(candidateVersion)
      ? candidateVersion
      : null;
  return {
    ...obra,
    versaoEntidade: serverVersion ?? obra.versaoEntidade,
    syncStatus: "CONFLICT",
    ultimoErro: result.erro ?? "Conflito informado pelo servidor.",
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
  const obraStore = transaction.objectStore("obras");
  const teamStore = transaction.objectStore("teams");

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
  const obra = isObraMutation(mutation)
    ? await obraStore.get(mutation.entidadeId)
    : undefined;
  const team = isTeamMutation(mutation)
    ? await teamStore.get(mutation.entidadeId)
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
      : result.resultado &&
          typeof result.resultado.versaoLinha === "number"
        ? result.resultado.versaoLinha
      : rdo?.versaoEntidade ??
        task?.versaoEntidade ??
        obra?.versaoEntidade ??
        team?.versaoEntidade ??
        canonicalEvent?.entityVersion ??
        null;
  const authoritativeRdoNumber =
    isRdoMutation(mutation) &&
    typeof result.resultado?.numeroRdo === "string"
      ? result.resultado.numeroRdo.trim()
      : "";

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
      ...(isIntegracaoMutation(mutation)
        ? { resultadoServidor: result.resultado ?? null }
        : {}),
      updatedAt: timestamp,
    });
    await rebasePendingLegacyRdoDependents(
      outboxStore,
      mutation,
      resultVersion,
      timestamp,
    );
    await rebasePendingSiblings(
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
        ...(authoritativeRdoNumber
          ? {
              numeroRdo: authoritativeRdoNumber,
              payload: {
                ...rdo.payload,
                numeroRdo: authoritativeRdoNumber,
              },
            }
          : {}),
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
      await updateRdoOperationalEventsSyncStatus(
        transaction,
        mutation.entidadeId,
        "SYNCED",
        timestamp,
        mutationOperationalEventIds(mutation),
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
    if (obra) {
      const aggregateSyncStatus = await obraSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      const authoritative = result.resultado
        ? obraRecordFromPayload(
            {
              ...result.resultado,
              id: result.resultado.id ?? mutation.entidadeId,
              obraId: mutation.entidadeId,
              versaoEntidade: resultVersion,
            },
            timestamp,
          )
        : null;
      await obraStore.put(
        aggregateSyncStatus === "SYNCED" && authoritative
          ? {
              ...authoritative,
              valorContratual:
                authoritative.valorContratual ?? obra.valorContratual,
              syncStatus: "SYNCED",
              ultimoErro: null,
            }
          : {
              ...obra,
              versaoEntidade: resultVersion,
              syncStatus: aggregateSyncStatus,
              ultimoErro: null,
              updatedAt: timestamp,
            },
      );
    }
    if (isTeamMutation(mutation) && team) {
      /*
       * A equipe aprende a versão que o servidor devolveu.
       *
       * Sem esta gravação — e a loja `teams` nem entrava na transação, então
       * ela era impossível — o registro local ficava para sempre no zero com
       * que nasce. Como toda mutação de equipe sobe com
       * `baseVersion = versaoEntidade + pendentes`, o servidor recebia zero
       * enquanto já estava em três, recusava por versão, e a recusa virava um
       * cartão na tela de conflitos. Repetir o gesto repetia o cartão: era um
       * laço que só terminava quando alguém desistia.
       *
       * `pendingMutationId` é limpo junto, e por um motivo próprio: ele é o que
       * diz à tela que há escrita em voo. Deixá-lo apontando para uma mutação já
       * aplicada manteria a equipe eternamente "sincronizando".
       */
      /*
       * A equipe tem um vocabulário de estado mais estreito que o genérico —
       * não conhece LOCAL_ONLY, LOCAL_PENDING, SYNCING nem ERROR. Traduzir aqui
       * é melhor que alargar o tipo dela: são estados que a tela de equipes não
       * sabe desenhar, e alargar só empurraria o problema para lá.
       */
      const agregado = await teamSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      const teamSyncStatus =
        agregado === "SYNCED"
          ? "SYNCED"
          : agregado === "CONFLICT"
            ? "CONFLICT"
            : agregado === "ERROR"
              ? "REJECTED"
              : "PENDING_SYNC";
      await teamStore.put({
        ...team,
        versaoEntidade: resultVersion ?? team.versaoEntidade,
        syncStatus: teamSyncStatus,
        ultimoErro: null,
        pendingMutationId:
          teamSyncStatus === "SYNCED" ? null : team.pendingMutationId,
        atualizadoEm: timestamp,
      });
    }
    if (isGeometriaMutation(mutation)) {
      await applyGeometriaPushResult(
        transaction,
        mutation,
        result,
        timestamp,
      );
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
    if (team) {
      /*
       * O conflito também ensina — e não ensinava. `versaoAtual` é a versão
       * corrente do servidor, entregue junto com a recusa, e era jogada fora:
       * a equipe local ficava com a versão velha e toda edição seguinte
       * nascia condenada ao mesmo conflito. Da cadeira do usuário isso era
       * "não sincroniza de jeito nenhum" — cada gesto novo herdava o veneno
       * do anterior.
       *
       * As irmãs pendentes são rebaseadas na mesma transação, pelo mesmo
       * motivo: elas foram enfileiradas sobre a versão velha, e esperar cada
       * uma descobrir o conflito por conta própria seria repetir a recusa uma
       * vez por mutação.
       */
      const versaoDoConflito =
        result.conflito && typeof result.conflito === "object"
          ? (result.conflito as Record<string, unknown>).versaoAtual
          : null;
      const aprendida =
        typeof versaoDoConflito === "number" &&
          Number.isFinite(versaoDoConflito)
          ? versaoDoConflito
          : null;
      const agregadoAposConflito = await teamSyncStatusFromOutbox(
        outboxStore,
        mutation.entidadeId,
      );
      await teamStore.put({
        ...team,
        versaoEntidade: aprendida ?? team.versaoEntidade,
        syncStatus:
          agregadoAposConflito === "SYNCED"
            ? "SYNCED"
            : agregadoAposConflito === "CONFLICT"
              ? "CONFLICT"
              : agregadoAposConflito === "ERROR"
                ? "REJECTED"
                : "PENDING_SYNC",
        atualizadoEm: timestamp,
      });
      if (aprendida !== null) {
        await rebasePendingSiblings(
          outboxStore,
          mutation,
          aprendida,
          timestamp,
        );
      }
    }
    if (obra) {
      await obraStore.put(
        obraAfterConflict(obra, result, timestamp),
      );
    }
    if (isGeometriaMutation(mutation)) {
      /*
       * DESCARTADA é conflito de versão, não reentrega bem-sucedida.
       *
       * O comentário anterior aqui dizia o contrário, e por isso todo
       * encerramento recusado era gravado como aplicado. A API só emite
       * DESCARTADA em `registrarConflitoEmNovaTransacao`, com resultado vazio;
       * a reentrega idempotente devolve o recibo guardado, que para uma
       * mutação aplicada tem status APLICADA. E "CONFLITO" nunca é emitido
       * como status de push — o ramo que o testava era inalcançável, então o
       * caminho abaixo recebia 100% dos conflitos e os chamava de sucesso.
       *
       * O efeito era duplo: a remoção sumia da fila sem nunca ter acontecido,
       * e o registro local, agora marcado SYNCED, era sobrescrito pela leitura
       * seguinte — o ponto voltava ao mapa como ativo, e nada explicava por
       * quê.
       */
      await markGeometriaMutationFailed(
        transaction,
        mutation,
        "CONFLICT",
        timestamp,
      );
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
    if (obra) {
      await obraStore.put({
        ...obra,
        syncStatus: await obraSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        ultimoErro: messageText,
        updatedAt: timestamp,
      });
    }
    if (isGeometriaMutation(mutation)) {
      await markGeometriaMutationFailed(
        transaction,
        mutation,
        disposition.retryable ? "PENDING_SYNC" : "ERROR",
        timestamp,
      );
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
  /*
   * Sem loja principal não há onde gravar o snapshot reconciliado — e isso não
   * é defeito da mutação.
   *
   * O conflito de equipe atravessava tudo: remoto completo, mescla possível,
   * substituta construída. Só no fim, na hora de gravar o snapshot local,
   * `principalStoreFor` não encontrava loja para EQUIPE e estourava. Quem
   * chama a reconciliação trata exceção como mutação corrompida, então
   * carimbava LOCAL_RESULT_APPLY_INVALID com uma mensagem interna sobre
   * "snapshot local reconciliável". Cada conflito de equipe virava dois
   * cartões vermelhos: o conflito de versão que o servidor recusou e, atrás
   * dele, uma recusa local que só descrevia a limitação do reconciliador.
   *
   * Registrar `teams` como loja não resolveria. O que a mescla produz está na
   * forma de transporte, e o registro local precisa de `obraNome`,
   * `versaoEntidade` e as marcas de sincronização, que o transporte não
   * carrega; gravar a mescla crua apagaria o nome da obra do cartão.
   * Reconstruir o registro exigiria ler o local aqui dentro, e esta função
   * monta snapshot sem tocar no banco.
   *
   * O desfecho honesto é o de TAREFA: não há saída automática, então o
   * conflito permanece item de revisão explícita — com o descarte individual e
   * o descarte em lote como saída — em vez de ser reclassificado como
   * corrupção. Vale para qualquer tipo futuro sem loja: devolver à revisão
   * humana, não inventar recusa.
   */
  if (!lojaPrincipalDe(original.entityType)) {
    return null;
  }
  const allMutations = await database.getAll("outbox_mutations");
  const aliasedReplacementId = original.entityType === "OBRA" &&
      typeof original.blockedReason === "string"
    ? /^SUPERSEDED_BY:([^\s:]+)$/.exec(
        original.blockedReason.trim(),
      )?.[1]
    : undefined;
  const existingReplacement = allMutations.find(
    (candidate): candidate is CanonicalOutboxMutationRecord =>
      isCanonicalOutboxMutation(candidate) &&
      (
        original.entityType === "OBRA"
          ? candidate.clientMutationId === aliasedReplacementId
          : candidate.causationId === original.clientMutationId
      ),
  );
  if (existingReplacement) {
    return existingReplacement;
  }
  if (
    original.entityType === "OBRA" &&
    allMutations.some(
      (candidate) =>
        isCanonicalOutboxMutation(candidate) &&
        candidate.entidadeTipo === "OBRA" &&
        candidate.entidadeId === original.entidadeId &&
        ["PENDING", "ERROR", "SYNCING"].includes(candidate.status) &&
        (
          candidate.causationId === original.clientMutationId ||
          candidate.dependsOnMutationIds?.includes(
            original.clientMutationId,
          )
        ),
    )
  ) {
    // Replacing the ancestor without rebasing immutable descendants would
    // either overwrite their optimistic projection or send a stale base.
    // Keep the complete chain terminal for explicit recovery instead.
    return null;
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
  const transportSnapshot =
    original.entityType === "OBRA"
      ? { ...resolution.merged }
      : nextSnapshot;
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
    nextSnapshot: transportSnapshot,
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
        "obras",
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
      (
        original.entityType === "OBRA"
          ? candidate.clientMutationId === aliasedReplacementId
          : candidate.causationId === original.clientMutationId
      ),
  );
  if (concurrentReplacement) {
    await guardedTransaction.complete();
    return concurrentReplacement;
  }
  // A original cedeu lugar, e isso vale para toda entidade.
  //
  // A marcação vivia dentro de um `if (entityType === "OBRA")`, sem que nada
  // justificasse a exceção: para RDO — e para as demais — a original ficava em
  // `CONFLICT` mesmo depois de a substituta reconciliada ter sido construída e
  // enfileirada. Um conflito resolvido continuava se apresentando como conflito
  // aberto, indefinidamente, porque nada jamais tirava a original desse estado.
  //
  // Marcar aqui é o que torna o desfecho legível: a original não falhou, foi
  // sucedida, e o `SUPERSEDED_BY` diz por quem. As rotinas de reenvio já leem
  // essa marca para não ressuscitar quem foi sucedido, e o laço de
  // reconciliação pula quem não está mais em `CONFLICT` — de modo que nenhuma
  // segunda substituta nasce do mesmo conflito.
  await transaction.objectStore("outbox_mutations").put({
    ...original,
    status: "REJECTED",
    nextAttemptAt: null,
    lastSafeCode: "SUPERSEDED_BY_CONFLICT_REPLACEMENT",
    blockedReason: `SUPERSEDED_BY:${built.mutation.clientMutationId}`,
    ultimoErro:
      "Conflito substituído por envelope canônico reconciliado.",
    updatedAt: occurredAt,
  });
  // Coerente com o parágrafo acima: a original não falhou, foi sucedida. O
  // evento afirmava o contrário e deixava o conflito resolvido parecendo
  // recusa aberta na Memória.
  await transaction.objectStore("operational_events").put({
    ...originalEvent,
    result: "SUPERSEDED",
    syncStatus: "LOCAL_ONLY",
    errorCategory: "SUPERSEDED_BY_CONFLICT_REPLACEMENT",
  });
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
            "obras",
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
        result: "SUPERSEDED",
        syncStatus: "LOCAL_ONLY",
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
  if (mutation.entityType === "OBRA") {
    const record = obraRecordFromPayload(
      {
        ...merged,
        id: mutation.entityId,
        obraId: mutation.entityId,
        versaoEntidade: serverVersion,
      },
      occurredAt,
    );
    if (!record) {
      throw new Error(
        "O snapshot remoto não corresponde à obra canônica.",
      );
    }
    return {
      ...record,
      syncStatus: "PENDING_SYNC",
      ultimoErro: null,
      updatedAt: occurredAt,
    };
  }
  return { ...merged };
}

type LojaPrincipal =
  | "rdos"
  | "mensagens"
  | "mensagem_conversas"
  | "mensagem_anexos"
  | "service_catalog"
  | "service_price_versions"
  | "obras";

/**
 * A loja onde mora o snapshot de um tipo, ou nada quando ele não tem uma.
 *
 * <p>Separado de `principalStoreFor` porque "não tem loja" é uma resposta
 * legítima em um dos dois chamadores: a reconciliação de conflito pode
 * desistir e devolver o caso à revisão humana. Só o caminho de upload
 * canônico exige a loja, e lá a ausência é mesmo defeito.
 */
function lojaPrincipalDe(entityType: string): LojaPrincipal | null {
  if (entityType === "RDO") return "rdos";
  if (entityType === "MENSAGEM") return "mensagens";
  if (entityType === "CONVERSA") return "mensagem_conversas";
  if (entityType === "MENSAGEM_ANEXO") return "mensagem_anexos";
  if (entityType === "SERVICE") return "service_catalog";
  if (entityType === "SERVICE_PRICE_VERSION") return "service_price_versions";
  if (entityType === "OBRA") return "obras";
  return null;
}

function principalStoreFor(entityType: string): LojaPrincipal {
  const loja = lojaPrincipalDe(entityType);
  if (!loja) {
    throw new Error(
      `entityType ${entityType} não possui snapshot local reconciliável.`,
    );
  }
  return loja;
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
  if (isObraMutation(mutation)) {
    const obraStore = transaction.objectStore("obras");
    const obra = await obraStore.get(mutation.entidadeId);
    if (obra) {
      await obraStore.put({
        ...obra,
        syncStatus: await obraSyncStatusFromOutbox(
          outbox,
          mutation.entidadeId,
        ),
        ultimoErro: message,
        updatedAt: timestamp,
      });
    }
  }
  await guardedTransaction.complete();
}

/**
 * Descarta a edição local presa em conflito, mantendo a versão do servidor.
 *
 * <p>Quando os dois lados alteram o mesmo campo, não existe fusão automática
 * possível e o registro fica travado indefinidamente — com o marcador de
 * conflito fixo na tela e nenhuma ação capaz de apagá-lo. Esta é a decisão que
 * faltava poder tomar: abrir mão da própria versão.</p>
 *
 * <p>O que sai é a intenção de escrita, não a evidência dela. A mutação deixa
 * a fila, mas o evento que a registrou permanece na Memória marcado como
 * descartado — quem for auditar depois continua vendo que a edição existiu,
 * quando foi feita e que foi abandonada em favor do servidor. Apagar o rastro
 * para limpar um aviso seria trocar um incômodo de tela por um buraco no
 * histórico.</p>
 *
 * <p>O registro local volta a acompanhar o servidor: sem mutação pendente, a
 * próxima descida de eventos reescreve o estado por cima.</p>
 *
 * @returns a entidade cuja edição foi descartada, ou null se não havia
 *          conflito com esse identificador.
 */
export async function descartarEdicaoEmConflito(
  clientMutationId: string,
): Promise<{ entidadeTipo: string; entidadeId: string } | null> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    RDO_SYNC_TRANSACTION_STORES,
    "readwrite",
  );
  const outbox = transaction.objectStore("outbox_mutations");
  const mutation = await outbox.get(clientMutationId);
  /*
   * REJECTED entra aqui junto de CONFLICT, e a ausência dela era metade do
   * beco. Um conflito tem duas versões e pede decisão; uma recusa definitiva
   * não pede nada — e mesmo assim era o único estado sem saída na tela, porque
   * o descarte se recusava a tocá-la. Reenviar também não resolvia: o reenvio
   * troca a identidade da mutação mas preserva `baseVersao`, então a mesma
   * recusa voltava.
   */
  if (
    !mutation ||
    (mutation.status !== "CONFLICT" && mutation.status !== "REJECTED")
  ) {
    await transaction.done;
    return null;
  }
  const timestamp = nowUtc();
  await outbox.delete(clientMutationId);

  const eventStore = transaction.objectStore("operational_events");
  const events = await eventStore
    .index("by-client-mutation-id")
    .getAll(clientMutationId);
  for (const event of events) {
    await eventStore.put({
      ...event,
      result: "DISCARDED",
      syncStatus: "SYNC_FAILED",
      errorCategory: "VERSION_CONFLICT_DISCARDED",
    });
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
    }
  }

  /*
   * Devolve a equipe ao estado em que a reidratação pode alcançá-la.
   *
   * É a outra metade do beco. `replaceLocalTeams` e `putLocalTeam` recusam-se a
   * sobrescrever equipe marcada como PENDING_SYNC, CONFLICT ou REJECTED —
   * proteção correta, para não apagar edição local que ainda não subiu. Só que
   * nada nunca limpava a marca quando a edição deixava de existir. Descartada a
   * mutação, o registro local não tem mais nada de próprio a preservar, e
   * continuar protegendo-o só o impedia de aprender a versão do servidor, que é
   * justamente o número cuja defasagem o mantinha travado.
   *
   * Só libera quando não sobra mutação nenhuma para a equipe: havendo outra, a
   * marca ainda descreve trabalho real, e apagá-la perderia esse trabalho na
   * próxima reidratação.
   */
  if ((mutation.entidadeTipo as string) === "EQUIPE") {
    const restantes = await outbox
      .index("by-entity-id")
      .getAll(mutation.entidadeId);
    if (restantes.length === 0) {
      const teamStore = transaction.objectStore("teams");
      const team = await teamStore.get(mutation.entidadeId);
      if (team) {
        await teamStore.put({
          ...team,
          syncStatus: "SYNCED",
          ultimoErro: null,
          pendingMutationId: null,
          atualizadoEm: timestamp,
        });
      }
    }
  }

  await transaction.done;
  return {
    entidadeTipo: mutation.entidadeTipo,
    entidadeId: mutation.entidadeId,
  };
}

/**
 * Descarta de uma vez tudo o que não tem mais como subir.
 *
 * <p>O descarte individual resolve um cartão; não resolve vinte e três. E o
 * gesto que a tela oferecia para o volume — reenviar — não podia funcionar:
 * `requeueMutationsInReview` troca a identidade da mutação mas preserva
 * `baseVersao`, então a recusa por versão volta idêntica. Quem tinha uma pilha
 * de recusas ficava girando entre dois botões que não a diminuíam.
 *
 * <p>Pula quem tem substituta viva, e essa é a única regra delicada aqui: a
 * reconciliação de conflito cria uma mutação nova e deixa a original marcada.
 * Varrer `REJECTED` cegamente apagaria o ancestral de trabalho que ainda vai
 * subir — o oposto de "nada se perde". A regra de superação é a mesma que a
 * tarja usa para contar, importada em vez de reescrita.
 *
 * <p>Devolve quantas foram descartadas.
 */
export async function descartarMutacoesMortas(): Promise<number> {
  const database = await getCortexDb();
  const todas = await database.getAll("outbox_mutations");
  const mortas = todas.filter(
    (mutation) =>
      (mutation.status === "REJECTED" || mutation.status === "CONFLICT") &&
      !foiSubstituida(mutation, todas),
  );

  let descartadas = 0;
  for (const mutation of mortas) {
    // Uma transação por mutação, e não uma para todas: descartar é irreversível,
    // e uma falha no meio de um lote atômico devolveria tudo — inclusive o que
    // já tinha saído do caminho de quem opera. Parcial e honesto é melhor que
    // tudo-ou-nada aqui.
    if (await descartarEdicaoEmConflito(mutation.clientMutationId)) {
      descartadas += 1;
    }
  }
  return descartadas;
}

/**
 * Todo identificador que alguma mutação cita, por qualquer um dos três laços.
 *
 * <p>Uma linha da outbox pode ser apontada de três maneiras, e as três contam:
 * `dependsOnMutationIds` (a fila de dependências), `SUPERSEDED_BY:` em
 * `blockedReason` (o apelido que liga a original à substituta) e `causationId`
 * (a substituta que aponta para trás). Apagar quem é citado por qualquer uma
 * delas transforma a citação em referência morta — e uma dependência que some
 * não vira "satisfeita", vira `missingDependencies`, que bloqueia o dependente
 * para sempre.
 */
function identificadoresCitadosPor(
  mutations: readonly OutboxMutationRecord[],
): Set<string> {
  const citados = new Set<string>();
  for (const mutation of mutations) {
    if (Array.isArray(mutation.dependsOnMutationIds)) {
      for (const id of mutation.dependsOnMutationIds) {
        if (typeof id === "string" && id.trim()) citados.add(id.trim());
      }
    }
    const marca = mutation.blockedReason?.trim();
    if (marca && MARCA_DE_SUPERACAO.test(marca)) {
      const apelido = marca.slice(marca.indexOf(":") + 1).trim();
      if (apelido) citados.add(apelido);
    }
    if ("causationId" in mutation && typeof mutation.causationId === "string") {
      const causa = mutation.causationId.trim();
      if (causa) citados.add(causa);
    }
  }
  return citados;
}

/**
 * Apaga da outbox o que já subiu e não serve mais a ninguém.
 *
 * <p>A fila nunca podava: uma mutação aplicada virava `SYNCED` e ficava ali
 * para sempre. Num aparelho em uso há semanas isso são centenas de linhas que
 * só ocupam espaço e atrasam toda varredura da própria fila — e toda varredura
 * da fila é `getAll`, então o custo é de cada ciclo de sincronização, não só do
 * armazenamento.
 *
 * <p>Duas classes ficam, e as duas por motivo concreto:
 *
 * <p>A primeira é quem ainda é citado. Ver `identificadoresCitadosPor`.
 *
 * <p>A segunda é o recibo de integração. `listarDesfechosIntegracao` lê
 * justamente as `SOLICITACAO_INTEGRACAO` já sincronizadas para mostrar o
 * desfecho de cada pedido depois de recarregar a página — a linha da outbox é
 * onde esse recibo mora. Podá-la apagaria a tela de desfechos, e o que essa
 * classe faz crescer é o número de integrações executadas, não o de gestos do
 * dia a dia.
 *
 * <p>Uma transação só para todas: diferente do descarte, aqui não se destrói
 * trabalho de ninguém — o que se apaga já está no servidor. Tudo-ou-nada é
 * então o lado seguro, porque uma poda parcial poderia deixar um citador vivo
 * apontando para um citado já apagado.
 *
 * <p>Os eventos operacionais correspondentes <em>não</em> são tocados: são eles
 * que sustentam a Memória, e sobreviver ao `SYNCED` é a razão de existirem.
 *
 * <p>Devolve quantas linhas saíram.
 */
/**
 * Tira das mutações vivas as citações a mutações que já não existem.
 *
 * <p>O servidor recusa qualquer mutação cujo `dependsOnMutationIds` cite algo
 * que ele não tenha como APLICADA — e recusa para sempre, porque uma mutação
 * descartada nunca vai ser aplicada. Foi assim que uma adição de membro chegou
 * a 164 tentativas: ela citava uma mutação que um descarte levou embora, e
 * cada reenvio repetia a mesma citação morta. O arquivado atrapalhando a fila
 * dos vivos, do lado do servidor.
 *
 * <p>Fantasma é toda citação que o servidor nunca vai poder satisfazer: a que
 * aponta para uma mutação que já sumiu da fila, e a que aponta para uma que
 * continua aqui mas está morta — recusada ou em conflito. As duas condenam o
 * dependente ao mesmo reenvio eterno.
 *
 * <p>Tirar a citação é seguro em todos os casos. Se o citado subiu e foi
 * podado, o servidor já o tem e a citação era redundante. Se foi descartado ou
 * morreu, ela era só a sentença. E enquanto o citado está vivo na fila, a
 * citação fica de pé — é o único caso em que ela ainda significa alguma
 * coisa.
 *
 * <p>Só PENDING e ERROR. As citações de uma mutação morta são a memória de
 * quem a substituiu, e mexer nelas mudaria o veredito de `foiSubstituida`.
 *
 * <p>As tentativas voltam a zero no que foi reparado: a mutação que vai subir
 * não é mais a que falhou 164 vezes, e herdar o backoff dela adiaria à toa o
 * primeiro envio que tem chance real.
 */
export async function desamarrarCitacoesFantasmas(): Promise<number> {
  const database = await getCortexDb();
  const transaction = database.transaction("outbox_mutations", "readwrite");
  const store = transaction.objectStore("outbox_mutations");
  const todas = await store.getAll();
  /*
   * O que conta é o que o servidor ainda pode marcar como APLICADA. Uma
   * mutação recusada ou em conflito nunca mais vai ser — ela está aqui só
   * como memória de quem a substituiu. Citá-la é a mesma sentença de reenvio
   * eterno que citar uma que já sumiu, e foi por não ver isso na primeira
   * passada que sobrou gente presa depois do conserto.
   */
  const aplicaveis = new Set(
    todas
      .filter((m) => m.status !== "REJECTED" && m.status !== "CONFLICT")
      .map((m) => m.clientMutationId),
  );
  const agora = new Date().toISOString();

  let reparadas = 0;
  for (const mutation of todas) {
    if (mutation.status !== "PENDING" && mutation.status !== "ERROR") continue;
    const declaradas = Array.isArray(mutation.dependsOnMutationIds)
      ? mutation.dependsOnMutationIds
      : [];
    const vivas = declaradas.filter((id) => aplicaveis.has(id));
    if (vivas.length === declaradas.length) continue;

    await store.put({
      ...mutation,
      dependsOnMutationIds: vivas,
      tentativas: 0,
      ultimoErro: null,
      lastSafeCode: null,
      updatedAt: agora,
    });
    reparadas += 1;
  }
  await transaction.done;
  return reparadas;
}

export async function podarMutacoesJaAplicadas(): Promise<number> {
  const database = await getCortexDb();
  const todas = await database.getAll("outbox_mutations");

  const candidatas = todas.filter(
    (mutation) =>
      mutation.status === "SYNCED" &&
      !(
        (mutation.entidadeTipo as string) === "SOLICITACAO_INTEGRACAO" &&
        (mutation as { resultadoServidor?: unknown }).resultadoServidor != null
      ),
  );
  if (candidatas.length === 0) return 0;

  // As citações que importam são as de quem fica. Uma candidata citada só por
  // outra candidata pode sair: as duas saem na mesma transação.
  const identificadoresPodados = new Set(
    candidatas.map((mutation) => mutation.clientMutationId),
  );
  const citadosPorQuemFica = identificadoresCitadosPor(
    todas.filter(
      (mutation) =>
        !identificadoresPodados.has(mutation.clientMutationId),
    ),
  );

  const podaveis = candidatas.filter(
    (mutation) => !citadosPorQuemFica.has(mutation.clientMutationId),
  );
  if (podaveis.length === 0) return 0;

  const transaction = database.transaction("outbox_mutations", "readwrite");
  const store = transaction.objectStore("outbox_mutations");
  for (const mutation of podaveis) {
    await store.delete(mutation.clientMutationId);
  }
  await transaction.done;
  return podaveis.length;
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
  const obraStore = transaction.objectStore("obras");

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
  if (isObraMutation(mutation)) {
    const obra = await obraStore.get(mutation.entidadeId);
    if (obra) {
      await obraStore.put({
        ...obra,
        syncStatus: await obraSyncStatusFromOutbox(
          outboxStore,
          mutation.entidadeId,
        ),
        ultimoErro: errorMessage,
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

  if (event.tipoEvento === "RDO_CANCELADO") {
    updated.statusRdo = "CANCELADA";
    updated.canceladoEm =
      textValue(event.ocorridoEmUtc) || updated.updatedAt;
  }

  if (event.tipoEvento === "RDO_RESTAURADO") {
    // O estado de volta é o do servidor, derivado de `enviado_em`. O palpite
    // que o dispositivo tinha gravado ao pedir a recuperação vale só até aqui.
    updated.canceladoEm = null;
    updated.statusRdo =
      textValue(event.payload?.status) === "ENVIADO"
        ? "ENVIADO"
        : "RASCUNHO";
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
      [
        "OBRA_ATUALIZADA",
        "OBRA_DESATIVADA",
        "OBRA_ATIVADA",
        "OBRA_ARQUIVADA",
        "OBRA_RESTAURADA",
      ].includes(event.tipoEvento) &&
      event.payload
    ) {
      const incoming = obraRecordFromPayload(
        {
          ...event.payload,
          versaoEntidade:
            event.versaoEntidade ??
            event.payload.versaoEntidade ??
            event.payload.versaoLinha,
        },
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
