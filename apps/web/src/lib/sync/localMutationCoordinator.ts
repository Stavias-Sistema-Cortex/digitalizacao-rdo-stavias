import type { IDBPTransaction } from "idb";

import {
  getCortexDb,
  type CortexDbSchema,
  type CortexStoreName,
} from "../db/cortexDb";
import type {
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  OperationalEntityRef,
  OperationalEventType,
} from "../db/db.types";
import { buildCanonicalEventFromMutation } from "../db/operationalEventRepository";
import {
  buildMutationEnvelopeWithSnapshots,
  type BuildMutationEnvelopeInput,
  type MutationEntity,
} from "./mutationEnvelope";

export const LOCAL_MUTATION_QUEUED_EVENT =
  "cortex:local-mutation-queued";

type CanonicalWriteStore =
  | "outbox_mutations"
  | "operational_events";

export type LocalMutationTransaction<
  TStore extends CortexStoreName,
> = IDBPTransaction<
  CortexDbSchema,
  ArrayLike<TStore | CanonicalWriteStore>,
  "readwrite"
>;

export interface LocalMutationEntity
  extends MutationEntity {
  name?: string | null;
  obraId?: string | null;
  rdoId?: string | null;
  colaboradorId?: string | null;
  relatedEntities?: OperationalEntityRef[];
}

export interface CommitLocalMutationInput<
  TStore extends CortexStoreName,
> extends Omit<BuildMutationEnvelopeInput, "entity"> {
  stores: readonly TStore[];
  entity: LocalMutationEntity;
  eventType: OperationalEventType;
  /**
   * Synchronously enqueue IndexedDB requests on this transaction and return
   * exactly undefined. Do not mark this callback async, await timers/network,
   * or return an idb request Promise; transaction.done is the commit boundary.
   *
   * @example
   * write: (tx) => {
   *   void tx.objectStore("rdos").put(record).catch(() => undefined);
   *   return undefined;
   * }
   */
  write: (
    transaction: LocalMutationTransaction<TStore>,
  ) => undefined;
}

export interface CommittedLocalMutation {
  mutation: CanonicalOutboxMutationRecord;
  event: CanonicalOperationalEventRecord;
}

export async function commitLocalMutation<
  TStore extends CortexStoreName,
>(
  input: CommitLocalMutationInput<TStore>,
): Promise<CommittedLocalMutation> {
  const entity = snapshotLocalMutationEntity(input.entity);
  const eventType = snapshotRequiredText(
    input.eventType,
    "eventType",
  ) as OperationalEventType;
  const stores = snapshotRequiredTextArray(
    input.stores,
    "stores",
  ) as TStore[];
  const write = input.write;
  const envelopeInput: BuildMutationEnvelopeInput = {
    entity: {
      type: entity.type,
      id: entity.id,
    },
    operation: input.operation,
    baseVersion: input.baseVersion,
    previousState: input.previousState,
    newState: input.newState,
    actor: input.actor,
    ...(input.correlationId === undefined
      ? {}
      : { correlationId: input.correlationId }),
    ...(input.causationId === undefined
      ? {}
      : { causationId: input.causationId }),
    ...(input.createdAt === undefined
      ? {}
      : { createdAt: input.createdAt }),
    ...(input.transport === undefined
      ? {}
      : { transport: input.transport }),
    ...(input.dependsOnMutationIds === undefined
      ? {}
      : { dependsOnMutationIds: input.dependsOnMutationIds }),
  };
  const {
    mutation,
    previousState,
    newState,
    actor,
  } = await buildMutationEnvelopeWithSnapshots(envelopeInput);
  const event = buildCanonicalEventFromMutation({
    mutation,
    type: eventType,
    principalEntity: {
      tipo: entity.type,
      id: entity.id,
      nome: entity.name,
    },
    relatedEntities: entity.relatedEntities ?? [],
    obraId: entity.obraId ?? null,
    rdoId: entity.rdoId ?? null,
    colaboradorId: entity.colaboradorId ?? null,
    responsibleUserName: actor.actorName,
    previousState,
    newState,
  });
  const db = await getCortexDb();
  const storeNames: Array<TStore | CanonicalWriteStore> = [
    ...new Set<TStore | CanonicalWriteStore>([
      ...stores,
      "outbox_mutations",
      "operational_events",
    ]),
  ];
  const transaction = db.transaction(storeNames, "readwrite");

  try {
    const writeResult: unknown = write(transaction);
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
      .add(mutation)
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
      // The browser may already have aborted the transaction after a request error.
    }
    await transaction.done.catch(() => undefined);
    throw error;
  }

  if (typeof window !== "undefined") {
    window.dispatchEvent(new CustomEvent(LOCAL_MUTATION_QUEUED_EVENT));
  }

  return { mutation, event };
}

function snapshotLocalMutationEntity(
  entity: LocalMutationEntity,
): LocalMutationEntity {
  const type = snapshotRequiredText(entity.type, "entity.type");
  const id = snapshotRequiredText(entity.id, "entity.id");
  const name = snapshotOptionalText(entity.name, "entity.name");
  const obraId = snapshotOptionalText(entity.obraId, "entity.obraId");
  const rdoId = snapshotOptionalText(entity.rdoId, "entity.rdoId");
  const colaboradorId = snapshotOptionalText(
    entity.colaboradorId,
    "entity.colaboradorId",
  );
  const relatedEntities = entity.relatedEntities === undefined
    ? undefined
    : snapshotRelatedEntities(entity.relatedEntities);

  return {
    type: type as LocalMutationEntity["type"],
    id,
    ...(name === undefined ? {} : { name }),
    ...(obraId === undefined ? {} : { obraId }),
    ...(rdoId === undefined ? {} : { rdoId }),
    ...(colaboradorId === undefined ? {} : { colaboradorId }),
    ...(relatedEntities === undefined ? {} : { relatedEntities }),
  };
}

function snapshotRelatedEntities(
  entities: readonly OperationalEntityRef[],
): OperationalEntityRef[] {
  const snapshot: OperationalEntityRef[] = [];

  for (let index = 0; index < entities.length; index += 1) {
    const entity = entities[index];
    if (entity === undefined || entity === null) {
      throw new TypeError(
        `entity.relatedEntities[${index}] is required.`,
      );
    }
    const tipo = snapshotRequiredText(
      entity.tipo,
      `entity.relatedEntities[${index}].tipo`,
    );
    const id = snapshotRequiredText(
      entity.id,
      `entity.relatedEntities[${index}].id`,
    );
    const nome = snapshotOptionalText(
      entity.nome,
      `entity.relatedEntities[${index}].nome`,
    );

    snapshot.push({
      tipo,
      id,
      ...(nome === undefined ? {} : { nome }),
    });
  }

  return snapshot;
}

function snapshotRequiredTextArray(
  values: readonly string[],
  field: string,
): string[] {
  const snapshot: string[] = [];

  for (let index = 0; index < values.length; index += 1) {
    const value = snapshotRequiredText(
      values[index],
      `${field}[${index}]`,
    );
    snapshot.push(value);
  }

  return snapshot;
}

function snapshotRequiredText(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new TypeError(`${field} must be a nonblank string.`);
  }

  return value;
}

function snapshotOptionalText(
  value: unknown,
  field: string,
): string | null | undefined {
  if (value === undefined || value === null) {
    return value;
  }

  return snapshotRequiredText(value, field);
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
  return (
    (typeof value === "object" && value !== null) ||
    typeof value === "function"
  ) && "then" in value && typeof value.then === "function";
}
