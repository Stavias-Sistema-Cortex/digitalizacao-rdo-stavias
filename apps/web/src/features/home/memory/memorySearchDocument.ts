import type { OperationalEventRecord } from "../../../lib/db/db.types";
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
  origin: string | null;
  result: string | null;
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
    origin: event.origin,
    result: event.result,
  };
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
    syncStatus: "UPDATED",
    sourceKind: "SERVER",
    occurredAt: event.occurredAt,
    eventType: event.eventType,
    source: event.source,
    principalName,
    worksiteName: event.worksiteName,
    rdoNumber: event.rdoNumber,
    serviceName: event.serviceName,
    errorCategory: event.errorCategory,
  };
}

export function localEventToSearchDocument(
  userId: string,
  scopeHash: string,
  event: OperationalEventRecord,
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
