import type { IDBPTransaction } from "idb";

import {
  getCortexDb,
  type CortexDbSchema,
  type CortexStoreName,
} from "../db/cortexDb";
import type {
  CanonicalMutationOperation,
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  OperationalEntityRef,
  OperationalEventType,
  OutboxTransport,
  SyncOperation,
} from "../db/db.types";
import {
  buildCanonicalMutation,
  type BuildCanonicalMutationInput,
} from "./mutationEnvelope";

export const LOCAL_MUTATION_QUEUED_EVENT = "cortex:local-mutation-queued";

type CanonicalWriteStore = "outbox_mutations" | "operational_events";

export type LocalMutationTransaction<TStore extends CortexStoreName> =
  IDBPTransaction<
    CortexDbSchema,
    ArrayLike<TStore | CanonicalWriteStore>,
    "readwrite"
  >;

export interface LocalMutationCommand<TStore extends CortexStoreName> {
  clientMutationId?: string;
  ontologyEventId?: string;
  deviceId: string;
  userId: string;
  obraId: string;
  entityType: string;
  entityId: string;
  entityName?: string | null;
  operation: CanonicalMutationOperation;
  transportOperation: SyncOperation;
  baseVersion: number | null;
  changedFields: string[];
  occurredAt?: string;
  previousSnapshot: Record<string, unknown>;
  nextSnapshot: Record<string, unknown>;
  eventType: OperationalEventType;
  domainStores: readonly TStore[];
  authorizationScope: string[];
  actorName: string;
  relatedEntities?: readonly OperationalEntityRef[];
  colaboradorId?: string | null;
  correlationId?: string;
  causationId?: string | null;
  transport?: OutboxTransport;
  dependsOnMutationIds?: readonly string[];
  write: (transaction: LocalMutationTransaction<TStore>) => undefined;
}

export interface CommittedLocalMutation {
  mutation: CanonicalOutboxMutationRecord;
  event: CanonicalOperationalEventRecord;
}

export async function commitLocalMutation<TStore extends CortexStoreName>(
  command: LocalMutationCommand<TStore>,
): Promise<CommittedLocalMutation> {
  const prepared = prepareCoordinatorInput(command);
  const built = await buildCanonicalMutation(prepared.envelope);
  const event = canonicalEvent(prepared, built);
  const database = await getCortexDb();
  const storeNames = [
    ...new Set<TStore | CanonicalWriteStore>([
      ...prepared.domainStores,
      "outbox_mutations",
      "operational_events",
    ]),
  ];
  const transaction = database.transaction(storeNames, "readwrite");

  try {
    const writeResult: unknown = prepared.write(transaction);
    if (writeResult !== undefined) {
      if (isThenable(writeResult)) {
        void Promise.resolve(writeResult).catch(() => undefined);
      }
      throw new TypeError(
        "Local mutation write must synchronously enqueue IndexedDB requests and return undefined.",
      );
    }

    void transaction
      .objectStore("outbox_mutations")
      .add(built.mutation)
      .catch(() => undefined);
    void transaction
      .objectStore("operational_events")
      .add(event)
      .catch(() => undefined);
    await transaction.done;
  } catch (error) {
    try {
      transaction.abort();
    } catch {
      // A failed IndexedDB request may already have aborted the transaction.
    }
    await transaction.done.catch(() => undefined);
    throw error;
  }

  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(LOCAL_MUTATION_QUEUED_EVENT));
  }

  return { mutation: built.mutation, event };
}

interface PreparedCoordinatorInput<TStore extends CortexStoreName> {
  envelope: BuildCanonicalMutationInput;
  eventType: OperationalEventType;
  entityName: string | null;
  actorName: string;
  relatedEntities: OperationalEntityRef[];
  colaboradorId: string | null;
  domainStores: TStore[];
  write: LocalMutationCommand<TStore>["write"];
}

function prepareCoordinatorInput<TStore extends CortexStoreName>(
  command: LocalMutationCommand<TStore>,
): PreparedCoordinatorInput<TStore> {
  const domainStores = requiredTextArray(
    command.domainStores,
    "domainStores",
    true,
  ) as TStore[];
  const eventType = requiredText(
    command.eventType,
    "eventType",
  ) as OperationalEventType;
  const entityName = optionalText(command.entityName, "entityName") ?? null;
  const actorName = requiredText(command.actorName, "actorName");
  const relatedEntities = (command.relatedEntities ?? []).map(
    (entity, index) => ({
      tipo: requiredText(entity.tipo, `relatedEntities[${index}].tipo`),
      id: requiredText(entity.id, `relatedEntities[${index}].id`),
      nome: optionalText(
        entity.nome,
        `relatedEntities[${index}].nome`,
      ) ?? null,
    }),
  );
  const colaboradorId = optionalText(
    command.colaboradorId,
    "colaboradorId",
  ) ?? null;

  return {
    envelope: {
      clientMutationId: command.clientMutationId,
      ontologyEventId: command.ontologyEventId,
      deviceId: command.deviceId,
      userId: command.userId,
      obraId: command.obraId,
      entityType: command.entityType,
      entityId: command.entityId,
      operation: command.operation,
      transportOperation: command.transportOperation,
      baseVersion: command.baseVersion,
      changedFields: [...command.changedFields],
      occurredAt: command.occurredAt,
      previousSnapshot: command.previousSnapshot,
      nextSnapshot: command.nextSnapshot,
      authorizationScope: [...command.authorizationScope],
      correlationId: command.correlationId,
      causationId: command.causationId,
      transport: command.transport,
      dependsOnMutationIds: command.dependsOnMutationIds === undefined
        ? undefined
        : [...command.dependsOnMutationIds],
    },
    eventType,
    entityName,
    actorName,
    relatedEntities,
    colaboradorId,
    domainStores,
    write: command.write,
  };
}

function canonicalEvent<TStore extends CortexStoreName>(
  prepared: PreparedCoordinatorInput<TStore>,
  built: Awaited<ReturnType<typeof buildCanonicalMutation>>,
): CanonicalOperationalEventRecord {
  const { mutation } = built;

  return {
    id: mutation.trace.ontologyEventId,
    type: prepared.eventType,
    principalEntity: {
      tipo: mutation.entityType,
      id: mutation.entityId,
      nome: prepared.entityName,
    },
    principalEntityKey: `${mutation.entityType}:${mutation.entityId}`,
    relatedEntities: prepared.relatedEntities,
    obraId: mutation.obraId,
    rdoId: mutation.entityType === "RDO" ? mutation.entityId : null,
    colaboradorId: prepared.colaboradorId,
    occurredAt: mutation.occurredAt,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: mutation.userId,
    responsibleUserName: prepared.actorName,
    payload: built.nextSnapshot,
    syncStatus: "PENDING_SYNC",
    schemaVersion: 13,
    clientMutationId: mutation.clientMutationId,
    deviceId: mutation.deviceId,
    correlationId: mutation.correlationId,
    causationId: mutation.causationId,
    previousState: built.previousSnapshot,
    newState: built.nextSnapshot,
    result: "PENDING",
    errorCategory: null,
    entityVersion: mutation.baseVersion,
  };
}

function requiredText(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new TypeError(`${field} must be a nonblank string.`);
  }
  return value;
}

function optionalText(
  value: unknown,
  field: string,
): string | null | undefined {
  if (value === undefined || value === null) return value;
  return requiredText(value, field);
}

function requiredTextArray(
  values: readonly string[],
  field: string,
  requireValue: boolean,
): string[] {
  if (!Array.isArray(values) || (requireValue && values.length === 0)) {
    throw new TypeError(`${field} is required.`);
  }
  return Array.from({ length: values.length }, (_unused, index) =>
    requiredText(values[index], `${field}[${index}]`),
  );
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
  return (
    ((typeof value === "object" && value !== null) ||
      typeof value === "function") &&
    "then" in value &&
    typeof value.then === "function"
  );
}
