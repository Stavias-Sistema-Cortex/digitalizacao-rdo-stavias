export type MemoryEventSource = "SERVER" | "DEVICE";

export interface MemoryEntityRef {
  type: string;
  id: string;
  name: string | null;
}

export interface MemoryEvent {
  id: string;
  commitSeq: number | null;
  sourceKind: MemoryEventSource;
  type: string;
  source: string | null;
  actorId: string | null;
  actorName: string | null;
  actorLabel: string;
  obraId: string | null;
  rdoId: string | null;
  colaboradorId: string | null;
  principalEntity: MemoryEntityRef;
  relatedEntities: MemoryEntityRef[];
  occurredAt: string | null;
  syncedAt: string | null;
  origin: string | null;
  syncStatus: string | null;
  schemaVersion: number | null;
  deviceId: string | null;
  correlationId: string | null;
  causationId: string | null;
  previousState: Record<string, unknown>;
  newState: Record<string, unknown>;
  payload: Record<string, unknown>;
  result: string | null;
  errorCategory: string | null;
}

export interface MemoryPage {
  events: MemoryEvent[];
  nextBeforeCommitSeq: number | null;
  hasMore: boolean;
  scope: string;
  serverTime: string;
}

export interface MemoryFilters {
  entityType?: string;
  entityId?: string;
  obraId?: string;
  rdoId?: string;
  actorId?: string;
  eventType?: string;
  origin?: string;
  result?: string;
  from?: string;
  to?: string;
  limit?: number;
}

export interface MemoryDiffRow {
  field: string;
  previous: unknown;
  next: unknown;
}
