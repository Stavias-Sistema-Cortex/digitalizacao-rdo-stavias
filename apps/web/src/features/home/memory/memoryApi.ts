import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";
import type {
  MemoryEntityRef,
  MemoryEvent,
  MemoryFilters,
  MemoryPage,
} from "./memory.types";

interface MemoryApiEvent {
  id?: unknown;
  commitSeq?: unknown;
  type?: unknown;
  source?: unknown;
  principalEntityType?: unknown;
  principalEntityId?: unknown;
  relatedEntities?: unknown;
  obraId?: unknown;
  rdoId?: unknown;
  colaboradorId?: unknown;
  occurredAt?: unknown;
  syncedAt?: unknown;
  origin?: unknown;
  syncStatus?: unknown;
  schemaVersion?: unknown;
  actorId?: unknown;
  actorName?: unknown;
  deviceId?: unknown;
  correlationId?: unknown;
  causationId?: unknown;
  previousState?: unknown;
  newState?: unknown;
  result?: unknown;
  errorCategory?: unknown;
  payload?: unknown;
}

interface MemoryApiPage {
  events?: unknown;
  nextBeforeCommitSeq?: unknown;
  hasMore?: unknown;
  scope?: unknown;
  serverTime?: unknown;
}

const QUERY_FIELDS: (keyof MemoryFilters)[] = [
  "entityType",
  "entityId",
  "obraId",
  "rdoId",
  "actorId",
  "eventType",
  "origin",
  "result",
  "from",
  "to",
  "limit",
];

export function memoryQueryString(
  filters: MemoryFilters,
  beforeCommitSeq?: number | null,
): string {
  const parameters = new URLSearchParams();
  for (const field of QUERY_FIELDS) {
    const value = filters[field];
    if (typeof value === "number" && Number.isFinite(value)) {
      parameters.set(field, String(value));
    } else if (typeof value === "string" && value.trim()) {
      parameters.set(field, value.trim());
    }
  }
  if (
    typeof beforeCommitSeq === "number" &&
    Number.isFinite(beforeCommitSeq)
  ) {
    parameters.set("beforeCommitSeq", String(beforeCommitSeq));
  }
  return parameters.toString();
}

export async function fetchMemoryPage(
  filters: MemoryFilters,
  beforeCommitSeq?: number | null,
): Promise<MemoryPage> {
  const query = memoryQueryString(filters, beforeCommitSeq);
  const response = await apiFetch(
    `/ontology/memory${query ? `?${query}` : ""}`,
  );
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  return memoryPageFromApi(body);
}

export function memoryEventFromApi(input: MemoryApiEvent): MemoryEvent {
  const actorName = nullableString(input.actorName);
  return {
    id: requiredString(input.id, "Evento da Memória sem identificador."),
    commitSeq: nullableNumber(input.commitSeq),
    sourceKind: "SERVER",
    type: requiredString(input.type, "Evento da Memória sem tipo."),
    source: nullableString(input.source),
    actorId: nullableString(input.actorId),
    actorName,
    actorLabel: actorName ?? "Processo do sistema",
    obraId: nullableString(input.obraId),
    rdoId: nullableString(input.rdoId),
    colaboradorId: nullableString(input.colaboradorId),
    principalEntity: {
      type: nullableString(input.principalEntityType) ?? "ENTIDADE",
      id: nullableString(input.principalEntityId) ?? "não informada",
      name: null,
    },
    relatedEntities: entityRefs(input.relatedEntities),
    occurredAt: nullableString(input.occurredAt),
    syncedAt: nullableString(input.syncedAt),
    origin: nullableString(input.origin),
    syncStatus: nullableString(input.syncStatus),
    schemaVersion: nullableNumber(input.schemaVersion),
    deviceId: nullableString(input.deviceId),
    correlationId: nullableString(input.correlationId),
    causationId: nullableString(input.causationId),
    previousState: objectValue(input.previousState),
    newState: objectValue(input.newState),
    payload: objectValue(input.payload),
    result: nullableString(input.result),
    errorCategory: nullableString(input.errorCategory),
  };
}

function memoryPageFromApi(input: unknown): MemoryPage {
  const page = objectValue(input) as MemoryApiPage;
  const events = Array.isArray(page.events)
    ? page.events.map((event) => memoryEventFromApi(objectValue(event)))
    : [];
  return {
    events,
    nextBeforeCommitSeq: nullableNumber(page.nextBeforeCommitSeq),
    hasMore: page.hasMore === true,
    scope: nullableString(page.scope) ?? "UNKNOWN",
    serverTime: nullableString(page.serverTime) ?? new Date().toISOString(),
  };
}

function entityRefs(input: unknown): MemoryEntityRef[] {
  if (!Array.isArray(input)) {
    return [];
  }
  return input.flatMap((candidate) => {
    const entity = objectValue(candidate);
    const type = nullableString(entity.type) ?? nullableString(entity.tipo);
    const id = nullableString(entity.id);
    if (!type || !id) {
      return [];
    }
    return [{
      type,
      id,
      name: nullableString(entity.name) ?? nullableString(entity.nome),
    }];
  });
}

function objectValue(input: unknown): Record<string, unknown> {
  if (input && typeof input === "object" && !Array.isArray(input)) {
    return input as Record<string, unknown>;
  }
  return {};
}

function nullableString(input: unknown): string | null {
  return typeof input === "string" && input.trim()
    ? input.trim()
    : null;
}

function requiredString(input: unknown, message: string): string {
  const value = nullableString(input);
  if (!value) {
    throw new Error(message);
  }
  return value;
}

function nullableNumber(input: unknown): number | null {
  return typeof input === "number" && Number.isFinite(input)
    ? input
    : null;
}
