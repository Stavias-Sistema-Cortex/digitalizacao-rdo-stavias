import type {
  CanonicalOutboxMutationRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
} from "../../../lib/db/db.types";
import {
  classifyFieldConflict,
  remoteSnapshotEvidence,
} from "../../../lib/sync/fieldConflict";
import type { MemoryGraphCoverage, MemoryServerEvent } from "./memoryApi";

const PROTECTED_ENTITY_TYPES = new Set([
  "ACTOR",
  "COLABORADOR",
  "COLLABORATOR",
  "PERSON",
  "PESSOA",
  "USER",
  "USUARIO",
]);

export type MemoryDocumentStatus =
  | "UPDATED"
  | "LOCAL_PENDING"
  | "SYNCING"
  | "CONFLICT"
  | "REJECTED";

export interface MemoryStructuralKeys {
  eventType: string;
  entityType: string;
  entityId: string | null;
  worksiteId: string | null;
  rdoId: string | null;
  actorId: string | null;
  deviceId: string | null;
  origin: string | null;
  result: string | null;
}

export interface MemoryTraceMetadata {
  clientMutationId: string | null;
  correlationId: string | null;
  causationId: string | null;
  entityVersion: number | null;
}

export type MemoryReviewUnavailableReason =
  | "REJECTED"
  | "LOCAL_EVIDENCE_UNAVAILABLE"
  | "REMOTE_SNAPSHOT_UNAVAILABLE"
  | "UNSUPPORTED_ENTITY"
  | "CREATE_CONFLICT_REQUIRES_REVIEW"
  | "REMOTE_SNAPSHOT_MISMATCH"
  | "FIELD_CONFLICT";

export interface MemoryReviewEvidence {
  status: "CONFLICT" | "REJECTED";
  clientMutationId: string | null;
  baseVersion: number | null;
  eventVersion: number | null;
  remoteVersion: number | null;
  localStateAvailable: boolean;
  remoteStateAvailable: boolean;
  changedFields: string[];
  conflictFields: string[];
  canReconcile: boolean;
  unavailableReason: MemoryReviewUnavailableReason | null;
}

export interface MemorySearchDocument {
  key: string;
  userId: string;
  scopeHash: string;
  eventId: string;
  commitSequence: number | null;
  normalizedText: string;
  structuralKeys: MemoryStructuralKeys;
  syncStatus: MemoryDocumentStatus;
  sourceKind: "SERVER" | "LOCAL";
  occurredAt: string;
  eventType: string;
  source: string | null;
  principalName: string | null;
  worksiteName: string | null;
  rdoNumber: string | null;
  serviceName: string | null;
  errorCategory: string | null;
  trace: MemoryTraceMetadata;
  review: MemoryReviewEvidence | null;
}

export interface MemoryCacheMetadata {
  key?: string;
  userId: string;
  scopeHash: string;
  highWaterMark: number;
  authorizedEventCount: number;
  cachedEventCount: number;
  oldestCommitSequence: number | null;
  newestCommitSequence: number;
  coverageMode: string;
  serverCoverageComplete: boolean;
  graph?: MemoryGraphCoverage;
  complete: boolean;
  cachedAt: string;
}

export type MemoryCoverageCode =
  | "UPDATED"
  | "PARTIAL"
  | "LOCAL_PENDING"
  | "SYNCING"
  | "CONFLICT"
  | "REJECTED";

export interface MemoryCoverageView {
  code: MemoryCoverageCode;
  label:
    | "Atualizado"
    | "Parcial"
    | "Local pendente"
    | "Sincronizando"
    | "Conflito"
    | "Rejeitado";
  detail: string;
}

export function normalizeMemoryText(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLocaleLowerCase("pt-BR")
    .replace(/\s+/g, " ")
    .trim();
}

export function serverEventToSearchDocument(
  userId: string,
  scopeHash: string,
  event: MemoryServerEvent,
): MemorySearchDocument {
  const protectedIdentity = isProtectedIdentity(event.principalEntityType);
  const principalEntityId = protectedIdentity ? null : event.principalEntityId;
  const principalName = protectedIdentity ? null : event.principalName;
  const structuralKeys: MemoryStructuralKeys = {
    eventType: event.eventType,
    entityType: event.principalEntityType,
    entityId: principalEntityId,
    worksiteId: event.worksiteId,
    rdoId: event.rdoId,
    actorId: event.actorId,
    deviceId: event.deviceId,
    origin: event.origin,
    result: event.result,
  };
  const syncStatus = serverStatus(event.result);
  return {
    key: documentKey(userId, scopeHash, event.eventId),
    userId,
    scopeHash,
    eventId: event.eventId,
    commitSequence: event.commitSequence,
    normalizedText: normalizedParts([
      event.eventId,
      String(event.commitSequence),
      event.eventType,
      event.source,
      event.principalEntityType,
      principalEntityId,
      principalName,
      event.worksiteId,
      event.worksiteName,
      event.rdoId,
      event.rdoNumber,
      event.serviceName,
      event.origin,
      event.result,
      event.errorCategory,
    ]),
    structuralKeys,
    syncStatus,
    sourceKind: "SERVER",
    occurredAt: event.occurredAt,
    eventType: event.eventType,
    source: event.source,
    principalName,
    worksiteName: event.worksiteName,
    rdoNumber: event.rdoNumber,
    serviceName: event.serviceName,
    errorCategory: event.errorCategory,
    trace: {
      clientMutationId: event.clientMutationId,
      correlationId: event.correlationId,
      causationId: event.causationId,
      entityVersion: event.entityVersion,
    },
    review: serverReview(event, syncStatus),
  };
}

export function localEventToSearchDocument(
  userId: string,
  scopeHash: string,
  event: OperationalEventRecord,
  mutation?: OutboxMutationRecord | null,
): MemorySearchDocument {
  const protectedIdentity = isProtectedIdentity(event.principalEntity.tipo);
  const principalEntityId = protectedIdentity ? null : event.principalEntity.id;
  const principalName = protectedIdentity ? null : event.principalEntity.nome ?? null;
  const result = event.result ?? null;
  return {
    key: documentKey(userId, scopeHash, event.id),
    userId,
    scopeHash,
    eventId: event.id,
    commitSequence: event.serverCommitSequence ?? null,
    normalizedText: normalizedParts([
      event.id,
      event.type,
      event.principalEntity.tipo,
      principalEntityId,
      principalName,
      event.obraId,
      event.rdoId,
      event.origin,
      result,
      event.errorCategory,
    ]),
    structuralKeys: {
      eventType: event.type,
      entityType: event.principalEntity.tipo,
      entityId: principalEntityId,
      worksiteId: event.obraId,
      rdoId: event.rdoId,
      actorId: event.responsibleUserId,
      deviceId: event.deviceId ?? null,
      origin: event.origin,
      result,
    },
    syncStatus: localStatus(event),
    sourceKind: "LOCAL",
    occurredAt: event.occurredAt,
    eventType: event.type,
    source: event.origin,
    principalName,
    worksiteName: null,
    rdoNumber: null,
    serviceName: null,
    errorCategory: event.errorCategory ?? null,
    trace: {
      clientMutationId: event.clientMutationId ?? mutation?.clientMutationId ?? null,
      correlationId: event.correlationId ?? mutation?.correlationId ?? null,
      causationId: event.causationId ??
        (isCanonicalMutation(mutation) ? mutation.causationId : null),
      entityVersion: safeVersion(event.entityVersion),
    },
    review: localReview(event, mutation),
  };
}

export function memoryCoverage(input: {
  online: boolean;
  metadata: MemoryCacheMetadata | null;
  localStatuses: readonly MemoryDocumentStatus[];
}): MemoryCoverageView {
  const statuses = new Set(input.localStatuses);
  const partialDetail = input.metadata?.complete
    ? ""
    : " Cobertura histórica parcial.";
  const graphDetail = graphLagDetail(input.metadata?.graph);
  if (statuses.has("CONFLICT")) {
    return coverage(
      "CONFLICT",
      "Conflito",
      `Há alterações locais com valores divergentes para revisar.${partialDetail}${graphDetail}`,
    );
  }
  if (statuses.has("REJECTED")) {
    return coverage(
      "REJECTED",
      "Rejeitado",
      `Há alterações recusadas com evidência preservada neste dispositivo.${partialDetail}${graphDetail}`,
    );
  }
  if (statuses.has("SYNCING")) {
    return coverage(
      "SYNCING",
      "Sincronizando",
      `Alterações locais estão sendo confirmadas no registro central.${partialDetail}${graphDetail}`,
    );
  }
  if (statuses.has("LOCAL_PENDING")) {
    return coverage(
      "LOCAL_PENDING",
      "Local pendente",
      `Há alterações preservadas neste dispositivo aguardando confirmação.${partialDetail}${graphDetail}`,
    );
  }
  if (!input.metadata?.complete) {
    return coverage(
      "PARTIAL",
      "Parcial",
      input.online
        ? `O cache ainda não cobre todo o histórico autorizado.${graphDetail}`
        : `Sem conexão: somente a parte já armazenada do histórico está disponível.${graphDetail}`,
    );
  }
  if (graphDetail) {
    return coverage(
      "PARTIAL",
      "Parcial",
      `O histórico autorizado está no cache.${graphDetail}`,
    );
  }
  return coverage(
    "UPDATED",
    "Atualizado",
    `${input.online ? "Histórico autorizado" : "Cache integral"} confirmado até o commit ${input.metadata.highWaterMark}.`,
  );
}

function graphLagDetail(graph: MemoryGraphCoverage | undefined): string {
  if (!graph || graph.fresh || graph.lagEventCount === 0) return "";
  const events = graph.lagEventCount === 1 ? "evento" : "eventos";
  const safeError = graph.lastSafeError
    ? ` Código seguro: ${graph.lastSafeError}.`
    : "";
  return ` O grafo ontológico está ${graph.lagEventCount} ${events} atrás do commit ${graph.targetCommitSequence}; projeção até o commit ${graph.checkpointCommitSequence}.${safeError}`;
}

export function memoryStatusLabel(
  status: MemoryDocumentStatus,
): MemoryCoverageView["label"] {
  const labels: Record<MemoryDocumentStatus, MemoryCoverageView["label"]> = {
    UPDATED: "Atualizado",
    LOCAL_PENDING: "Local pendente",
    SYNCING: "Sincronizando",
    CONFLICT: "Conflito",
    REJECTED: "Rejeitado",
  };
  return labels[status];
}

function localStatus(event: OperationalEventRecord): MemoryDocumentStatus {
  if (event.result === "CONFLICT") return "CONFLICT";
  if (event.result === "REJECTED") return "REJECTED";
  if (event.result === "SYNCING" || event.syncStatus === "SYNCING") {
    return "SYNCING";
  }
  if (
    event.result === "LOCAL" ||
    event.result === "PENDING" ||
    event.syncStatus === "LOCAL_ONLY" ||
    event.syncStatus === "PENDING_SYNC"
  ) {
    return "LOCAL_PENDING";
  }
  return event.syncStatus === "SYNCED" ? "UPDATED" : "REJECTED";
}

function serverStatus(result: string | null): MemoryDocumentStatus {
  const normalized = result?.trim().toLocaleUpperCase("pt-BR");
  if (normalized === "CONFLICT" || normalized === "CONFLITO") return "CONFLICT";
  if (normalized === "REJECTED" || normalized === "REJEITADA") return "REJECTED";
  return "UPDATED";
}

function serverReview(
  event: MemoryServerEvent,
  status: MemoryDocumentStatus,
): MemoryReviewEvidence | null {
  if (status !== "CONFLICT" && status !== "REJECTED") return null;
  return {
    status,
    clientMutationId: event.clientMutationId,
    baseVersion: null,
    eventVersion: safeVersion(event.entityVersion),
    remoteVersion: null,
    localStateAvailable: false,
    remoteStateAvailable: false,
    changedFields: [],
    conflictFields: [],
    canReconcile: false,
    unavailableReason: status === "REJECTED"
      ? "REJECTED"
      : "LOCAL_EVIDENCE_UNAVAILABLE",
  };
}

function localReview(
  event: OperationalEventRecord,
  mutation: OutboxMutationRecord | null | undefined,
): MemoryReviewEvidence | null {
  const status = localStatus(event);
  if (status !== "CONFLICT" && status !== "REJECTED") return null;

  const canonical = isCanonicalMutation(mutation) ? mutation : null;
  const remote = remoteSnapshotEvidence(mutation?.conflito);
  const hasLocalStates = isRecord(event.previousState) && isRecord(event.newState);
  const resolution = canonical && hasLocalStates
    ? classifyFieldConflict(event.previousState!, event.newState!, remote)
    : null;
  const remoteVersion = safeVersion(mutation?.conflito?.versaoAtual);
  const changedFields = safeFieldNames(canonical?.changedFields ?? []);
  const conflictFields = safeFieldNames(
    resolution?.conflicts.map((conflict) => conflict.field) ?? [],
  );
  const unavailableReason = reconciliationUnavailableReason({
    status,
    mutation: canonical,
    event,
    remoteComplete: remote.complete,
    remoteSnapshot: remote.snapshot,
    remoteVersion,
    localStatesAvailable: hasLocalStates,
    hasFieldConflicts: (resolution?.conflicts.length ?? 0) > 0,
  });

  return {
    status,
    clientMutationId: event.clientMutationId ?? mutation?.clientMutationId ?? null,
    baseVersion: safeVersion(canonical?.baseVersion ?? mutation?.baseVersao),
    eventVersion: safeVersion(event.entityVersion),
    remoteVersion,
    localStateAvailable: isRecord(event.newState),
    remoteStateAvailable: remote.complete,
    changedFields,
    conflictFields,
    canReconcile: unavailableReason === null,
    unavailableReason,
  };
}

function reconciliationUnavailableReason(input: {
  status: "CONFLICT" | "REJECTED";
  mutation: CanonicalOutboxMutationRecord | null;
  event: OperationalEventRecord;
  remoteComplete: boolean;
  remoteSnapshot: Record<string, unknown> | null;
  remoteVersion: number | null;
  localStatesAvailable: boolean;
  hasFieldConflicts: boolean;
}): MemoryReviewUnavailableReason | null {
  if (input.status === "REJECTED") return "REJECTED";
  if (
    !input.mutation ||
    input.mutation.status !== "CONFLICT" ||
    !input.localStatesAvailable ||
    input.remoteVersion === null
  ) {
    return "LOCAL_EVIDENCE_UNAVAILABLE";
  }
  if (!RECONCILIABLE_ENTITY_TYPES.has(input.mutation.entityType)) {
    return "UNSUPPORTED_ENTITY";
  }
  if (input.mutation.operation === "CREATE") {
    return "CREATE_CONFLICT_REQUIRES_REVIEW";
  }
  if (!input.remoteComplete || !input.remoteSnapshot) {
    return "REMOTE_SNAPSHOT_UNAVAILABLE";
  }
  if (
    input.remoteSnapshot.id !== input.mutation.entityId ||
    (
      input.mutation.entityType === "RDO" &&
      input.remoteSnapshot.obraId !== input.mutation.obraId
    ) ||
    input.event.clientMutationId !== input.mutation.clientMutationId
  ) {
    return "REMOTE_SNAPSHOT_MISMATCH";
  }
  return input.hasFieldConflicts ? "FIELD_CONFLICT" : null;
}

const RECONCILIABLE_ENTITY_TYPES = new Set([
  "RDO",
  "CONVERSA",
  "MENSAGEM",
  "MENSAGEM_ANEXO",
  "SERVICE",
  "SERVICE_PRICE_VERSION",
]);

function isCanonicalMutation(
  mutation: OutboxMutationRecord | null | undefined,
): mutation is CanonicalOutboxMutationRecord {
  return mutation?.schemaVersion === 13;
}

function safeVersion(value: unknown): number | null {
  return Number.isSafeInteger(value) && (value as number) >= 0
    ? value as number
    : null;
}

function safeFieldNames(fields: readonly string[]): string[] {
  return [...new Set(fields)]
    .filter((field) => /^[A-Za-z][A-Za-z0-9_.-]{0,79}$/.test(field))
    .map((field) =>
      /cpf|email|senha|password|token|secret|telefone|phone|document/i.test(field)
        ? "[campo protegido]"
        : field
    )
    .slice(0, 100);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === "object" && !Array.isArray(value);
}

function normalizedParts(parts: readonly (string | null | undefined)[]): string {
  return normalizeMemoryText(parts.filter((part): part is string => Boolean(part)).join(" "));
}

function isProtectedIdentity(entityType: string): boolean {
  return PROTECTED_ENTITY_TYPES.has(entityType.trim().toLocaleUpperCase("pt-BR"));
}

function documentKey(userId: string, scopeHash: string, eventId: string): string {
  return `${userId}:${scopeHash}:${eventId}`;
}

function coverage(
  code: MemoryCoverageView["code"],
  label: MemoryCoverageView["label"],
  detail: string,
): MemoryCoverageView {
  return { code, label, detail };
}
