import { listReadyPendingOutboxMutations } from "../db/outboxRepository";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
  returnMutationToPending,
} from "./syncStorage";
import { pushMutationsApi } from "./syncApiClient";
import {
  toPushMutationRequest,
  type SyncPushMutationResult,
} from "./sync.types";

export interface PushOutboxSummary {
  pushed: number;
  applied: number;
  errors: number;
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
      conflicts: 0,
    };
  }

  const pushMutations = await Promise.all(
    pendingMutations.map(toPushMutationRequest),
  );

  for (const mutation of pendingMutations) {
    await markMutationAsSyncing(mutation);
  }

  try {
    const response = await pushMutationsApi({
      dispositivoId: deviceId,
      mutacoes: pushMutations,
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
    let errors = 0;
    let conflicts = 0;

    for (const mutation of pendingMutations) {
      const result = resultsById.get(
        mutation.clientMutationId,
      );

      if (!result) {
        await returnMutationToPending(
          mutation.clientMutationId,
          "O servidor não retornou resultado para esta mutação.",
        );

        errors += 1;
        continue;
      }

      await applyPushResultAtomically(result);

      if (result.status === "APLICADA") {
        applied += 1;
      } else if (result.status === "DESCARTADA") {
        conflicts += 1;
      } else {
        errors += 1;
      }
    }

    return {
      pushed: pendingMutations.length,
      applied,
      errors,
      conflicts,
    };
  } catch (error: unknown) {
    const message =
      error instanceof Error
        ? error.message
        : "Falha desconhecida durante o push.";

    for (const mutation of pendingMutations) {
      await returnMutationToPending(
        mutation.clientMutationId,
        message,
      );
    }

    throw error;
  }
}
