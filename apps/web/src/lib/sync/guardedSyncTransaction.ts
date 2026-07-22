import { AUTH_SESSION_CHANGED_EVENT } from "../../features/auth/authSession";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "./syncSession";

interface AbortableTransaction {
  abort(): void;
  readonly done: Promise<unknown>;
}

export interface GuardedSyncTransaction<
  TTransaction extends AbortableTransaction,
> {
  readonly transaction: TTransaction;
  complete(): Promise<void>;
  dispose(): void;
}

/** Keeps an IndexedDB write transaction bound to one exact auth fingerprint. */
export function guardSyncTransaction<
  TTransaction extends AbortableTransaction,
>(
  transaction: TTransaction,
  guard: SyncSessionGuard,
): GuardedSyncTransaction<TTransaction> {
  assertSyncSession(guard);
  let invalidated = false;
  let disposed = false;

  const abortForSessionChange = () => {
    try {
      assertSyncSession(guard);
    } catch {
      invalidated = true;
      try {
        transaction.abort();
      } catch {
        // The transaction may already have completed or aborted.
      }
    }
  };
  if (typeof window !== "undefined") {
    window.addEventListener(
      AUTH_SESSION_CHANGED_EVENT,
      abortForSessionChange,
    );
  }

  const dispose = () => {
    if (disposed) return;
    disposed = true;
    if (typeof window !== "undefined") {
      window.removeEventListener(
        AUTH_SESSION_CHANGED_EVENT,
        abortForSessionChange,
      );
    }
  };
  void transaction.done.then(dispose, dispose);

  return {
    transaction,
    async complete(): Promise<void> {
      try {
        abortForSessionChange();
        if (invalidated) {
          await transaction.done.catch(() => undefined);
          throw new Error("A sessão mudou durante a sincronização.");
        }
        await transaction.done;
        assertSyncSession(guard);
      } catch (error: unknown) {
        if (invalidated) {
          throw new Error(
            "A sessão mudou durante a sincronização.",
            { cause: error },
          );
        }
        throw error;
      } finally {
        dispose();
      }
    },
    dispose,
  };
}
