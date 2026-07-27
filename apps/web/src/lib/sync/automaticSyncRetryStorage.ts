import { getCortexDb } from "../db/cortexDb";
import type { OutboxMutationRecord } from "../db/db.types";
import type { SyncPushMutationResult } from "./sync.types";

const INITIAL_RETRY_DELAY_MS = 1_000;
const MAX_RETRY_DELAY_MS = 300_000;

export interface RetryScheduleInput {
  safeCode: string;
  message: string;
  now?: number;
  random?: () => number;
}

export interface RetryDisposition {
  retryable: boolean;
  safeCode: string;
}

export function mutationAfterRetryScheduled(
  mutation: OutboxMutationRecord,
  input: RetryScheduleInput,
): OutboxMutationRecord {
  const attempt = Math.max(0, mutation.retryAttempt ?? 0) + 1;
  const timestamp = input.now ?? Date.now();
  const random = Math.min(1, Math.max(0, (input.random ?? Math.random)()));
  const baseDelay = Math.min(
    MAX_RETRY_DELAY_MS,
    INITIAL_RETRY_DELAY_MS * 2 ** Math.max(0, attempt - 1),
  );
  const delay = Math.min(
    MAX_RETRY_DELAY_MS,
    Math.floor(baseDelay + baseDelay * random),
  );

  return {
    ...mutation,
    status: "PENDING",
    retryAttempt: attempt,
    nextAttemptAt: new Date(timestamp + delay).toISOString(),
    lastSafeCode: input.safeCode,
    ultimoErro: input.message,
    updatedAt: new Date(timestamp).toISOString(),
  };
}

export function earliestRetryAt(
  mutations: readonly OutboxMutationRecord[],
): string | null {
  return mutations
    .filter((mutation) => mutation.status === "PENDING")
    .map((mutation) => mutation.nextAttemptAt)
    .filter(
      (value): value is string =>
        typeof value === "string" && Number.isFinite(Date.parse(value)),
    )
    .sort((left, right) => Date.parse(left) - Date.parse(right))[0] ?? null;
}

export async function loadEarliestAutomaticRetryAt(): Promise<string | null> {
  const database = await getCortexDb();
  return earliestRetryAt(
    await database.getAllFromIndex(
      "outbox_mutations",
      "by-status",
      "PENDING",
    ),
  );
}

export function retryDispositionForResult(
  result: SyncPushMutationResult,
): RetryDisposition {
  if (result.status === "ERRO") {
    return {
      retryable: true,
      safeCode: resultSafeCode(result) ?? "SERVER_TRANSIENT",
    };
  }
  if (result.status === "REJEITADA") {
    return {
      retryable: false,
      safeCode: resultSafeCode(result) ?? "SERVER_REJECTED",
    };
  }
  if (result.status === "DESCARTADA" || result.status === "CONFLITO") {
    return { retryable: false, safeCode: "VERSION_CONFLICT" };
  }
  return { retryable: false, safeCode: "SYNCED" };
}

function resultSafeCode(result: SyncPushMutationResult): string | null {
  const rejection = result.resultado?.rejeicao;
  if (rejection && typeof rejection === "object" && !Array.isArray(rejection)) {
    const category = (rejection as Record<string, unknown>).categoria;
    if (typeof category === "string" && isSafeCode(category)) {
      return category.trim();
    }
  }
  const category = result.resultado?.categoria;
  return typeof category === "string" && isSafeCode(category)
    ? category.trim()
    : null;
}

function isSafeCode(value: string): boolean {
  return /^[A-Z][A-Z0-9_]{0,63}$/.test(value.trim());
}
