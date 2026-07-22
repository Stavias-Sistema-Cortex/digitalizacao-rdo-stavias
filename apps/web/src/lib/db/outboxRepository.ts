import { getCortexDb } from "./cortexDb";
import type {
  OutboxMutationRecord,
  OutboxMutationStatus,
} from "./db.types";
import {
  isCanonicalOutboxMutation,
  isOutboxMutationBlocked,
} from "./outboxContract";
import { selectReadyOutboxMutations } from "../sync/outboxDependencies";
import { classifyLegacyOutboxMutationsForReview } from "../sync/syncStorage";

async function listClassifiedOutboxMutations(): Promise<
  OutboxMutationRecord[]
> {
  await classifyLegacyOutboxMutationsForReview();
  const database = await getCortexDb();

  return database.getAllFromIndex(
    "outbox_mutations",
    "by-created-at",
  );
}

export async function getOutboxMutation(
  clientMutationId: string,
): Promise<OutboxMutationRecord | undefined> {
  await classifyLegacyOutboxMutationsForReview();
  const database = await getCortexDb();

  return database.get(
    "outbox_mutations",
    clientMutationId,
  );
}

export async function listOutboxMutations(): Promise<
  OutboxMutationRecord[]
> {
  return listClassifiedOutboxMutations();
}

export async function listOutboxMutationsByStatus(
  status: OutboxMutationStatus,
): Promise<OutboxMutationRecord[]> {
  return (await listClassifiedOutboxMutations())
    .filter((mutation) => mutation.status === status);
}

export async function listPendingOutboxMutations(
  limit = 100,
): Promise<OutboxMutationRecord[]> {
  const pending = (await listClassifiedOutboxMutations())
    .filter((mutation) => mutation.status === "PENDING");

  return pending
    .sort((left, right) =>
      left.criadaNoClienteEm.localeCompare(
        right.criadaNoClienteEm,
      ),
    )
    .slice(0, limit);
}

export async function listReadyPendingOutboxMutations(
  limit = 100,
): Promise<OutboxMutationRecord[]> {
  return selectReadyOutboxMutations(
    await listClassifiedOutboxMutations(),
    limit,
  ).filter(
    (mutation) =>
      isCanonicalOutboxMutation(mutation) &&
      !isOutboxMutationBlocked(mutation),
  );
}

/**
 * This is the storage-facing review queue consumed by Home > Memória. No
 * payload is transformed; callers receive the original record and its reason.
 */
export async function listOutboxMutationsRequiringReview(): Promise<
  OutboxMutationRecord[]
> {
  return (await listClassifiedOutboxMutations())
    .filter(
      (mutation) =>
        mutation.status === "REJECTED" ||
        mutation.status === "CONFLICT" ||
        isOutboxMutationBlocked(mutation),
    );
}

export async function putOutboxMutation(
  mutation: OutboxMutationRecord,
): Promise<void> {
  const database = await getCortexDb();

  await database.put("outbox_mutations", mutation);
}

export async function deleteOutboxMutation(
  clientMutationId: string,
): Promise<void> {
  const database = await getCortexDb();

  await database.delete(
    "outbox_mutations",
    clientMutationId,
  );
}
