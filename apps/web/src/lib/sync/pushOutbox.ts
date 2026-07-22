import { listReadyPendingOutboxMutations } from "../db/outboxRepository";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
  rejectNonCanonicalMutation,
  returnMutationToPending,
} from "./syncStorage";
import { pushMutationsApi } from "./syncApiClient";
import {
  toPushMutationRequest,
  isCanonicalPushMutation,
  NONCANONICAL_MUTATION_BLOCKED_REASON,
  type SyncPushMutationResult,
} from "./sync.types";

export interface PushOutboxSummary {
  pushed: number;
  applied: number;
  errors: number;
  retryableErrors: number;
  conflicts: number;
}

export async function pushOutbox(
  deviceId: string,
): Promise<PushOutboxSummary> {
  const pendingMutations =
    await listReadyPendingOutboxMutations(100);

  if (pendingMutations.length === 0) {
    return {
      pushed: 0,
      applied: 0,
      errors: 0,
      retryableErrors: 0,
      conflicts: 0,
    };
  }

  const dispatchableMutations = [];
  let blockedErrors = 0;

  for (const mutation of pendingMutations) {
    if (isCanonicalPushMutation(mutation)) {
      dispatchableMutations.push(mutation);
      continue;
    }

    await rejectNonCanonicalMutation(
      mutation.clientMutationId,
      NONCANONICAL_MUTATION_BLOCKED_REASON,
    );
    blockedErrors += 1;
  }

  if (dispatchableMutations.length === 0) {
    return {
      pushed: 0,
      applied: 0,
      errors: blockedErrors,
      retryableErrors: 0,
      conflicts: 0,
    };
  }

  for (const mutation of dispatchableMutations) {
    await markMutationAsSyncing(mutation);
  }

  const handledMutationIds = new Set<string>();

  try {
    const response = await pushMutationsApi({
      dispositivoId: deviceId,
      mutacoes: dispatchableMutations.map(
        toPushMutationRequest,
      ),
    });

    const resultsById = new Map<
      string,
      SyncPushMutationResult
    >(
      response.resultados.map((result) => [
        result.clientMutationId,
        result,
      ]),
    );

    let applied = 0;
    let errors = blockedErrors;
    let retryableErrors = 0;
    let conflicts = 0;

    for (const mutation of dispatchableMutations) {
      const result = resultsById.get(
        mutation.clientMutationId,
      );

      if (!result) {
        await returnMutationToPending(
          mutation.clientMutationId,
          "O servidor não retornou resultado para esta mutação.",
        );
        handledMutationIds.add(mutation.clientMutationId);

        errors += 1;
        retryableErrors += 1;
        continue;
      }

      await applyPushResultAtomically(result);
      handledMutationIds.add(mutation.clientMutationId);

      if (
        result.status === "APLICADA" ||
        result.status === "CONCILIADA"
      ) {
        applied += 1;
      } else if (result.status === "DESCARTADA") {
        conflicts += 1;
      } else {
        errors += 1;
        if (result.status === "ERRO") {
          retryableErrors += 1;
        }
      }
    }

    return {
      pushed: dispatchableMutations.length,
      applied,
      errors,
      retryableErrors,
      conflicts,
    };
  } catch (error: unknown) {
    const message =
      error instanceof Error
        ? error.message
        : "Falha desconhecida durante o push.";

    for (const mutation of dispatchableMutations) {
      if (handledMutationIds.has(mutation.clientMutationId)) {
        continue;
      }

      await returnMutationToPending(
        mutation.clientMutationId,
        message,
      );
    }

    throw error;
  }
}
