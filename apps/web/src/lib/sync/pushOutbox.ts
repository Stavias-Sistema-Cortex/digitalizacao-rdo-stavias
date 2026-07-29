import { listReadyPendingOutboxMutations } from "../db/outboxRepository";
import type { OutboxMutationRecord } from "../db/db.types";
import {
  applyPushResultAtomically,
  markMutationAsSyncing,
  reconcileCanonicalConflict,
  rejectMutationLocally,
  returnMutationToPending,
} from "./syncStorage";
import { pushMutationsApi } from "./syncApiClient";
import {
  toPushMutationRequest,
  type SyncPushMutationRequest,
  type SyncPushMutationResult,
} from "./sync.types";
import { retryDispositionForResult } from "./automaticSyncRetryStorage";
import { classifyAutomaticRequestFailure } from "./automaticRequestFailure";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "./syncSession";

export interface PushOutboxSummary {
  pushed: number;
  applied: number;
  errors: number;
  retryableErrors: number;
  conflicts: number;
  appliedMutationIds: readonly string[];
  handledMutationIds: readonly string[];
  errorMutationIds: readonly string[];
}

interface PreparedMutation {
  row: OutboxMutationRecord;
  request: SyncPushMutationRequest;
}

export async function pushOutbox(
  deviceId: string,
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<PushOutboxSummary> {
  assertSyncSession(guard);
  const pendingMutations = await listReadyPendingOutboxMutations(100);
  assertSyncSession(guard);
  const prepared: PreparedMutation[] = [];
  const handledMutationIds: string[] = [];
  const errorMutationIds: string[] = [];
  let localErrors = 0;

  for (const row of pendingMutations) {
    assertSyncSession(guard);
    try {
      const lockedRow = await markMutationAsSyncing(row, guard);
      assertSyncSession(guard);
      const request = await toPushMutationRequest(lockedRow);
      assertSyncSession(guard);
      prepared.push({ row: lockedRow, request });
    } catch (error: unknown) {
      assertSyncSession(guard);
      const message = errorMessage(error);
      await rejectMutationLocally(
        row.clientMutationId,
        "LOCAL_CANONICAL_INVALID",
        message,
        guard,
      );
      handledMutationIds.push(row.clientMutationId);
      errorMutationIds.push(row.clientMutationId);
      localErrors += 1;
    }
  }

  if (prepared.length === 0) {
    return {
      pushed: 0,
      applied: 0,
      errors: localErrors,
      retryableErrors: 0,
      conflicts: 0,
      appliedMutationIds: [],
      handledMutationIds,
      errorMutationIds,
    };
  }

  const handled = new Set<string>();
  try {
    const response = await pushMutationsApi({
      dispositivoId: deviceId,
      mutacoes: prepared.map((item) => item.request),
    });
    assertSyncSession(guard);
    const resultsById = new Map<string, SyncPushMutationResult>();
    for (const result of response.resultados) {
      if (resultsById.has(result.clientMutationId)) {
        throw new Error(
          `O servidor repetiu o resultado ${result.clientMutationId}.`,
        );
      }
      resultsById.set(result.clientMutationId, result);
    }

    let applied = 0;
    let errors = localErrors;
    let retryableErrors = 0;
    let conflicts = 0;
    const appliedMutationIds: string[] = [];

    for (const { row } of prepared) {
      assertSyncSession(guard);
      const result = resultsById.get(row.clientMutationId);
      if (!result) {
        await returnMutationToPending(
          row.clientMutationId,
          "O servidor não retornou resultado para esta mutação.",
          "SERVER_RESULT_MISSING",
          guard,
        );
        handled.add(row.clientMutationId);
        handledMutationIds.push(row.clientMutationId);
        errorMutationIds.push(row.clientMutationId);
        errors += 1;
        retryableErrors += 1;
        continue;
      }

      try {
        await applyPushResultAtomically(result, guard);
        if (
          result.status === "DESCARTADA" ||
          result.status === "CONFLITO"
        ) {
          await reconcileCanonicalConflict(
            row.clientMutationId,
            undefined,
            undefined,
            undefined,
            guard,
          );
        }
      } catch (error: unknown) {
        assertSyncSession(guard);
        await rejectMutationLocally(
          row.clientMutationId,
          "LOCAL_RESULT_APPLY_INVALID",
          errorMessage(error),
          guard,
        );
        handled.add(row.clientMutationId);
        handledMutationIds.push(row.clientMutationId);
        errorMutationIds.push(row.clientMutationId);
        errors += 1;
        continue;
      }
      handled.add(row.clientMutationId);
      handledMutationIds.push(row.clientMutationId);

      if (result.status === "APLICADA") {
        applied += 1;
        appliedMutationIds.push(row.clientMutationId);
      } else if (
        result.status === "DESCARTADA" ||
        result.status === "CONFLITO"
      ) {
        conflicts += 1;
      } else {
        errorMutationIds.push(row.clientMutationId);
        errors += 1;
        if (retryDispositionForResult(result).retryable) {
          retryableErrors += 1;
        }
      }
    }

    return {
      pushed: prepared.length,
      applied,
      errors,
      retryableErrors,
      conflicts,
      appliedMutationIds,
      handledMutationIds,
      errorMutationIds,
    };
  } catch (error: unknown) {
    assertSyncSession(guard);
    const failure = classifyAutomaticRequestFailure(error);
    for (const { row } of prepared) {
      if (handled.has(row.clientMutationId)) continue;
      if (failure.retryable) {
        await returnMutationToPending(
          row.clientMutationId,
          failure.message,
          failure.safeCode,
          guard,
        );
      } else {
        await rejectMutationLocally(
          row.clientMutationId,
          failure.safeCode,
          failure.message,
          guard,
        );
      }
    }
    throw error;
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error
    ? error.message
    : "Falha desconhecida durante o push.";
}
