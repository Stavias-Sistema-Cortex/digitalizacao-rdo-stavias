import { getCortexDb } from "./cortexDb";
import type {
  OutboxMutationRecord,
  OutboxMutationStatus,
} from "./db.types";
import { selectReadyOutboxMutations } from "../sync/outboxDependencies";

export async function getOutboxMutation(
  clientMutationId: string,
): Promise<OutboxMutationRecord | undefined> {
  const database = await getCortexDb();

  return database.get(
    "outbox_mutations",
    clientMutationId,
  );
}

export async function listOutboxMutations(): Promise<
  OutboxMutationRecord[]
> {
  const database = await getCortexDb();

  return database.getAllFromIndex(
    "outbox_mutations",
    "by-created-at",
  );
}

export async function listOutboxMutationsByStatus(
  status: OutboxMutationStatus,
): Promise<OutboxMutationRecord[]> {
  const database = await getCortexDb();

  return database.getAllFromIndex(
    "outbox_mutations",
    "by-status",
    status,
  );
}

export async function listPendingOutboxMutations(
  limit = 100,
): Promise<OutboxMutationRecord[]> {
  const database = await getCortexDb();

  const pending = await database.getAllFromIndex(
    "outbox_mutations",
    "by-status",
    "PENDING",
  );

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
    await listOutboxMutations(),
    limit,
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
