import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../../lib/api/apiClient";

export interface MemoryFilters {
  q?: string;
  entityType?: string;
  entityId?: string;
  worksiteId?: string;
  rdoId?: string;
  eventType?: string;
  origin?: string;
  result?: string;
  from?: string;
  to?: string;
  limit?: number;
}

export interface MemoryCursor {
  token: string;
}

export interface MemoryServerEvent {
  eventId: string;
  commitSequence: number;
  eventType: string;
  source: string | null;
  principalEntityType: string;
  principalEntityId: string | null;
  principalName: string | null;
  worksiteId: string | null;
  worksiteName: string | null;
  rdoId: string | null;
  rdoNumber: string | null;
  serviceName: string | null;
  occurredAt: string;
  synchronizedAt: string | null;
  origin: string | null;
  syncStatus: string | null;
  schemaVersion: number;
  result: string | null;
  errorCategory: string | null;
  relevance: number;
}

export interface MemoryServerCoverage {
  mode: string;
  complete: boolean;
  authorizedEventCount: number;
  oldestCommitSequence: number | null;
  newestCommitSequence: number;
  graph: MemoryGraphCoverage;
}

export interface MemoryGraphCoverage {
  checkpointCommitSequence: number;
  targetCommitSequence: number;
  lagEventCount: number;
  fresh: boolean;
  lastSafeError: string | null;
}

export interface MemoryPage {
  items: MemoryServerEvent[];
  nextCursor: MemoryCursor | null;
  hasMore: boolean;
  highWaterMark: number;
  scopeHash: string;
  coverage: MemoryServerCoverage;
  serverTime: string;
}

const QUERY_FIELDS: readonly [keyof MemoryFilters, string][] = [
  ["q", "q"],
  ["entityType", "entityType"],
  ["entityId", "entityId"],
  ["worksiteId", "obraId"],
  ["rdoId", "rdoId"],
  ["eventType", "eventType"],
  ["origin", "origin"],
  ["result", "result"],
  ["from", "from"],
  ["to", "to"],
  ["limit", "limit"],
];

export function memoryQueryString(
  filters: MemoryFilters,
  cursor: MemoryCursor | null = null,
): string {
  const query = new URLSearchParams();
  for (const [field, parameter] of QUERY_FIELDS) {
    const value = filters[field];
    if (typeof value === "number" && Number.isFinite(value)) {
      query.set(parameter, String(value));
    } else if (typeof value === "string" && value.trim()) {
      query.set(parameter, value.trim());
    }
  }
  if (cursor) {
    query.set("cursor", cursor.token);
  }
  return query.toString();
}

export async function fetchMemoryPage(
  filters: MemoryFilters,
  cursor: MemoryCursor | null = null,
): Promise<MemoryPage> {
  const query = memoryQueryString(filters, cursor);
  const response = await apiFetch(
    `/ontology/memory${query ? `?${query}` : ""}`,
  );
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  return parseMemoryPage(body);
}

function parseMemoryPage(value: unknown): MemoryPage {
  const page = object(value, "Resposta da Memória inválida.");
  const highWaterMark = integer(page.highWaterMark, "high-water inválido.");
  const scopeHash = text(page.scopeHash, "Escopo da Memória inválido.");
  const serverTime = text(page.serverTime, "Horário da Memória inválido.");
  const coverageValue = object(page.coverage, "Cobertura da Memória inválida.");
  const graphValue = object(
    coverageValue.graph,
    "Cobertura do grafo ontológico inválida.",
  );
  const nextCursor = page.nextCursor === null || page.nextCursor === undefined
    ? null
    : parseCursor(page.nextCursor);
  if (!Array.isArray(page.items) || typeof page.hasMore !== "boolean") {
    throw new Error("Página da Memória inválida.");
  }
  const graph: MemoryGraphCoverage = {
    checkpointCommitSequence: integer(
      graphValue.checkpointCommitSequence,
      "Checkpoint do grafo inválido.",
    ),
    targetCommitSequence: integer(
      graphValue.targetCommitSequence,
      "Alvo do grafo inválido.",
    ),
    lagEventCount: integer(
      graphValue.lagEventCount,
      "Atraso do grafo inválido.",
    ),
    fresh: boolean(graphValue.fresh, "Atualidade do grafo inválida."),
    lastSafeError: nullableSafeCode(graphValue.lastSafeError),
  };
  if (graph.fresh !== (graph.lagEventCount === 0)
      || graph.targetCommitSequence !== highWaterMark) {
    throw new Error("Cobertura do grafo ontológico incoerente.");
  }
  const coverage: MemoryServerCoverage = {
    mode: text(coverageValue.mode, "Modo de cobertura inválido."),
    complete: boolean(coverageValue.complete, "Cobertura inválida."),
    authorizedEventCount: integer(
      coverageValue.authorizedEventCount,
      "Contagem autorizada inválida.",
    ),
    oldestCommitSequence: nullableInteger(coverageValue.oldestCommitSequence),
    newestCommitSequence: integer(
      coverageValue.newestCommitSequence,
      "Último commit inválido.",
    ),
    graph,
  };
  return {
    items: page.items.map(parseMemoryEvent),
    nextCursor,
    hasMore: page.hasMore,
    highWaterMark,
    scopeHash,
    coverage,
    serverTime,
  };
}

function parseMemoryEvent(value: unknown): MemoryServerEvent {
  const event = object(value, "Evento da Memória inválido.");
  return {
    eventId: text(event.eventId, "Evento sem ID."),
    commitSequence: integer(event.commitSequence, "Commit inválido."),
    eventType: text(event.eventType, "Evento sem tipo."),
    source: nullableText(event.source),
    principalEntityType: text(event.principalEntityType, "Entidade sem tipo."),
    principalEntityId: nullableText(event.principalEntityId),
    principalName: nullableText(event.principalName),
    worksiteId: nullableText(event.worksiteId),
    worksiteName: nullableText(event.worksiteName),
    rdoId: nullableText(event.rdoId),
    rdoNumber: nullableText(event.rdoNumber),
    serviceName: nullableText(event.serviceName),
    occurredAt: text(event.occurredAt, "Evento sem horário."),
    synchronizedAt: nullableText(event.synchronizedAt),
    origin: nullableText(event.origin),
    syncStatus: nullableText(event.syncStatus),
    schemaVersion: integer(event.schemaVersion, "Schema do evento inválido."),
    result: nullableText(event.result),
    errorCategory: nullableText(event.errorCategory),
    relevance: finiteNumber(event.relevance, "Relevância inválida."),
  };
}

function parseCursor(value: unknown): MemoryCursor {
  const cursor = object(value, "Cursor da Memória inválido.");
  return {
    token: text(cursor.token, "Token do cursor inválido."),
  };
}

function object(value: unknown, message: string): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error(message);
  }
  return value as Record<string, unknown>;
}

function text(value: unknown, message: string): string {
  if (typeof value !== "string" || !value.trim()) throw new Error(message);
  return value.trim();
}

function nullableText(value: unknown): string | null {
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function nullableSafeCode(value: unknown): string | null {
  if (value === null || value === undefined) return null;
  const code = text(value, "Código seguro do grafo inválido.");
  if (!/^[A-Z][A-Z0-9_]{0,119}$/.test(code)) {
    throw new Error("Código seguro do grafo inválido.");
  }
  return code;
}

function integer(value: unknown, message: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new Error(message);
  }
  return value as number;
}

function nullableInteger(value: unknown): number | null {
  return value === null || value === undefined
    ? null
    : integer(value, "Commit inicial inválido.");
}

function finiteNumber(value: unknown, message: string): number {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new Error(message);
  }
  return value;
}

function boolean(value: unknown, message: string): boolean {
  if (typeof value !== "boolean") throw new Error(message);
  return value;
}
