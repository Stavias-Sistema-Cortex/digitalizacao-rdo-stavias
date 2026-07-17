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
  const {
    mutation,
    previousState,
    newState,
    actor,
  } = await buildMutationEnvelopeWithSnapshots({
    ...input,
    entity,
  });
  const event = buildCanonicalEventFromMutation({
    mutation,
    type: input.eventType,
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
      ...input.stores,
      "outbox_mutations",
      "operational_events",
    ]),
  ];
  const transaction = db.transaction(storeNames, "readwrite");

  try {
    const writeResult: unknown = input.write(transaction);
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
  return {
    type: entity.type,
    id: entity.id,
    ...(entity.name === undefined ? {} : { name: entity.name }),
    ...(entity.obraId === undefined ? {} : { obraId: entity.obraId }),
    ...(entity.rdoId === undefined ? {} : { rdoId: entity.rdoId }),
    ...(entity.colaboradorId === undefined
      ? {}
      : { colaboradorId: entity.colaboradorId }),
    ...(entity.relatedEntities === undefined
      ? {}
      : {
          relatedEntities: entity.relatedEntities.map((related) => ({
            tipo: related.tipo,
            id: related.id,
            ...(related.nome === undefined ? {} : { nome: related.nome }),
          })),
        }),
  };
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
  return (
    (typeof value === "object" && value !== null) ||
    typeof value === "function"
  ) && "then" in value && typeof value.then === "function";
}
