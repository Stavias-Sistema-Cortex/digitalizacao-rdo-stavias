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
  buildMutationEnvelope,
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
  write: (
    transaction: LocalMutationTransaction<TStore>,
  ) => Promise<unknown> | unknown;
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
  const mutation = await buildMutationEnvelope(input);
  const event = buildCanonicalEventFromMutation({
    mutation,
    type: input.eventType,
    principalEntity: {
      tipo: input.entity.type,
      id: input.entity.id,
      nome: input.entity.name,
    },
    relatedEntities: input.entity.relatedEntities ?? [],
    obraId: input.entity.obraId ?? null,
    rdoId: input.entity.rdoId ?? null,
    colaboradorId: input.entity.colaboradorId ?? null,
    responsibleUserName: input.actor.actorName,
    previousState: input.previousState,
    newState: input.newState,
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
    await input.write(transaction);
    await transaction.objectStore("outbox_mutations").add(mutation);
    await transaction.objectStore("operational_events").add(event);
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
