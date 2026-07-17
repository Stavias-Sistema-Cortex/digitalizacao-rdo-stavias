import { getCortexDb } from "./cortexDb";
import type {
  CanonicalMutationResult,
  CanonicalOperationalEventRecord,
  LegacyOperationalEventRecord,
  OperationalEntityRef,
  OperationalEventOrigin,
  OperationalEventRecord,
  OperationalEventSyncStatus,
  OperationalEventType,
} from "./db.types";

export interface LegacyOperationalEventInput {
  id?: string;
  type: OperationalEventType;
  principalEntity: OperationalEntityRef;
  relatedEntities?: OperationalEntityRef[];
  obraId?: string | null;
  rdoId?: string | null;
  colaboradorId?: string | null;
  occurredAt?: string;
  syncedAt?: string | null;
  origin?: OperationalEventOrigin;
  responsibleUserId?: string | null;
  responsibleUserName?: string | null;
  payload?: Record<string, unknown>;
  syncStatus?: OperationalEventSyncStatus;
  schemaVersion?: number;
}

export type OperationalEventInput = LegacyOperationalEventInput;

export interface CanonicalOperationalEventInput
  extends LegacyOperationalEventInput {
  clientMutationId: string;
  deviceId: string;
  correlationId: string;
  causationId: string | null;
  previousState: Record<string, unknown>;
  newState: Record<string, unknown>;
  result: CanonicalMutationResult;
  errorCategory: string | null;
  entityVersion: number | null;
}

export interface OperationalEventFilter {
  entityType?: string;
  entityId?: string;
  obraId?: string;
  rdoId?: string;
  colaboradorId?: string;
  from?: string;
  to?: string;
  limit?: number;
}

export function operationalEntityKey(
  entity: OperationalEntityRef,
): string {
  return `${entity.tipo}:${entity.id}`;
}

export function buildLegacyOperationalEvent(
  input: LegacyOperationalEventInput,
): LegacyOperationalEventRecord {
  const occurredAt = input.occurredAt ?? new Date().toISOString();

  return {
    id: input.id ?? crypto.randomUUID(),
    type: input.type,
    principalEntity: input.principalEntity,
    principalEntityKey: operationalEntityKey(input.principalEntity),
    relatedEntities: input.relatedEntities ?? [],
    obraId: input.obraId ?? null,
    rdoId: input.rdoId ?? null,
    colaboradorId: input.colaboradorId ?? null,
    occurredAt,
    syncedAt: input.syncedAt ?? null,
    origin: input.origin ?? "OFFLINE",
    responsibleUserId: input.responsibleUserId ?? null,
    responsibleUserName: input.responsibleUserName ?? null,
    payload: input.payload ?? {},
    syncStatus: input.syncStatus ?? "PENDING_SYNC",
    schemaVersion: input.schemaVersion ?? 1,
  };
}

/**
 * @deprecated Compatibility alias for v12 writers. New mutation flows must use
 * buildCanonicalOperationalEvent.
 */
export const buildOperationalEvent = buildLegacyOperationalEvent;

export function buildCanonicalOperationalEvent(
  input: CanonicalOperationalEventInput,
): CanonicalOperationalEventRecord {
  return {
    ...buildLegacyOperationalEvent(input),
    contractVersion: 13,
    clientMutationId: input.clientMutationId,
    deviceId: input.deviceId,
    correlationId: input.correlationId,
    causationId: input.causationId,
    previousState: input.previousState,
    newState: input.newState,
    result: input.result,
    errorCategory: input.errorCategory,
    entityVersion: input.entityVersion,
  };
}

export async function putOperationalEvent(
  event: OperationalEventRecord,
): Promise<void> {
  const database = await getCortexDb();

  await database.put("operational_events", event);
}

export async function addOperationalEvent(
  input: OperationalEventInput,
): Promise<OperationalEventRecord> {
  const event = buildOperationalEvent(input);
  await putOperationalEvent(event);
  return event;
}

export async function listOperationalEvents(): Promise<
  OperationalEventRecord[]
> {
  const database = await getCortexDb();
  const events = await database.getAllFromIndex(
    "operational_events",
    "by-occurred-at",
  );

  return newestFirst(events);
}

export async function listPendingOperationalEvents(): Promise<
  OperationalEventRecord[]
> {
  const database = await getCortexDb();
  const pending = await database.getAllFromIndex(
    "operational_events",
    "by-sync-status",
    "PENDING_SYNC",
  );

  return pending.sort((left, right) =>
    left.occurredAt.localeCompare(right.occurredAt),
  );
}

export async function listOperationalEventsForRdo(
  rdoId: string,
): Promise<OperationalEventRecord[]> {
  if (!rdoId.trim()) {
    return [];
  }

  const database = await getCortexDb();
  const events = await database.getAllFromIndex(
    "operational_events",
    "by-rdo-id",
    rdoId,
  );

  return newestFirst(events);
}

export async function listOperationalEventsForObra(
  obraId: string,
): Promise<OperationalEventRecord[]> {
  if (!obraId.trim()) {
    return [];
  }

  const database = await getCortexDb();
  const events = await database.getAllFromIndex(
    "operational_events",
    "by-obra-id",
    obraId,
  );

  return newestFirst(events);
}

export async function listOperationalEventsForColaborador(
  colaboradorId: string,
): Promise<OperationalEventRecord[]> {
  if (!colaboradorId.trim()) {
    return [];
  }

  const database = await getCortexDb();
  const events = await database.getAllFromIndex(
    "operational_events",
    "by-colaborador-id",
    colaboradorId,
  );

  return newestFirst(events);
}

export async function queryOperationalEvents(
  filter: OperationalEventFilter,
): Promise<OperationalEventRecord[]> {
  const entityType = filter.entityType?.trim();
  const entityId = filter.entityId?.trim();

  let candidates: OperationalEventRecord[];

  if (filter.rdoId?.trim()) {
    candidates = await listOperationalEventsForRdo(filter.rdoId);
  } else if (filter.colaboradorId?.trim()) {
    candidates = await listOperationalEventsForColaborador(
      filter.colaboradorId,
    );
  } else if (filter.obraId?.trim()) {
    candidates = await listOperationalEventsForObra(filter.obraId);
  } else if (entityType && entityId) {
    const database = await getCortexDb();
    candidates = await database.getAllFromIndex(
      "operational_events",
      "by-principal-entity",
      `${entityType}:${entityId}`,
    );
    candidates = newestFirst(candidates);
  } else {
    candidates = await listOperationalEvents();
  }

  const fromTime = filter.from ? Date.parse(filter.from) : null;
  const toTime = filter.to ? Date.parse(filter.to) : null;
  const limit = filter.limit ?? 200;

  return candidates
    .filter((event) => {
      if (entityType && entityId) {
        const key = `${entityType}:${entityId}`;
        const related = event.relatedEntities.some(
          (entity) => operationalEntityKey(entity) === key,
        );
        if (event.principalEntityKey !== key && !related) {
          return false;
        }
      }

      const occurredAt = Date.parse(event.occurredAt);
      if (
        fromTime !== null &&
        Number.isFinite(fromTime) &&
        occurredAt < fromTime
      ) {
        return false;
      }

      if (
        toTime !== null &&
        Number.isFinite(toTime) &&
        occurredAt > toTime
      ) {
        return false;
      }

      return true;
    })
    .slice(0, limit);
}

export async function markOperationalEventsSynced(
  eventIds: string[],
  syncedAt = new Date().toISOString(),
): Promise<void> {
  if (eventIds.length === 0) {
    return;
  }

  const database = await getCortexDb();
  const transaction = database.transaction(
    "operational_events",
    "readwrite",
  );
  const store = transaction.objectStore("operational_events");

  await Promise.all(
    eventIds.map(async (id) => {
      const event = await store.get(id);
      if (!event) {
        return;
      }

      await store.put({
        ...event,
        syncedAt,
        origin: event.origin === "OFFLINE" ? "SYNC" : event.origin,
        syncStatus: "SYNCED",
      });
    }),
  );

  await transaction.done;
}

function newestFirst(
  events: OperationalEventRecord[],
): OperationalEventRecord[] {
  return events.sort((left, right) =>
    right.occurredAt.localeCompare(left.occurredAt),
  );
}
