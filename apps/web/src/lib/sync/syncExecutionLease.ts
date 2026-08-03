import { getCortexDb } from "../db/cortexDb";
import {
  SyncLeaseLostError,
  SyncLeaseUnavailableError,
} from "./syncLeaseContention";
import type { SyncStateRecord } from "../db/db.types";
import { getSyncState } from "../db/syncStateRepository";
import {
  assertSyncSession,
  type SyncSessionGuard,
} from "./syncSession";

interface SyncExecutionLeaseOptions {
  locks?: LockManager | null;
  now?: () => number;
  ownerToken?: () => string;
}

export interface SyncExecutionLease {
  readonly ownerToken: string;
  assertOwned(): Promise<void>;
}

const SYNC_STATE_KEY = "default" as const;
const WEB_LOCK_NAME = "cortex-sync-execution-v1";
const DEFAULT_LEASE_TTL_MS = 45_000;
const DEFAULT_HEARTBEAT_INTERVAL_MS = 15_000;

export {
  isSyncLeaseContentionError,
  isSyncLeaseLostError,
  isSyncLeaseUnavailableError,
  SyncLeaseLostError,
  SyncLeaseUnavailableError,
} from "./syncLeaseContention";

export async function runWithSyncExecutionLease<T>(
  guard: SyncSessionGuard,
  task: (lease: SyncExecutionLease) => Promise<T>,
  options: SyncExecutionLeaseOptions = {},
): Promise<T> {
  const locks =
    options.locks === undefined
      ? getBrowserLockManager()
      : options.locks;
  if (locks) {
    return locks.request(
      WEB_LOCK_NAME,
      {
        ifAvailable: true,
        mode: "exclusive",
      },
      async (lock) => {
        if (!lock) throw new SyncLeaseUnavailableError();
        return runWithIndexedDbLease(guard, task, options);
      },
    );
  }
  return runWithIndexedDbLease(guard, task, options);
}

async function runWithIndexedDbLease<T>(
  guard: SyncSessionGuard,
  task: (lease: SyncExecutionLease) => Promise<T>,
  options: SyncExecutionLeaseOptions,
): Promise<T> {
  const now = options.now ?? Date.now;
  const ownerToken =
    options.ownerToken?.() ?? globalThis.crypto.randomUUID();
  const database = await acquireIndexedDbLease(
    guard,
    ownerToken,
    now(),
  );
  if (!database) {
    throw new SyncLeaseUnavailableError();
  }
  let heartbeatError: unknown;
  let heartbeatChain = Promise.resolve();
  const heartbeat = () => {
    heartbeatChain = heartbeatChain.then(async () => {
      if (heartbeatError) return;
      try {
        const renewed = await renewIndexedDbLease(
          database,
          ownerToken,
          now(),
        );
        if (!renewed) {
          heartbeatError = new SyncLeaseLostError();
        }
      } catch (error) {
        heartbeatError = error;
      }
    });
  };
  const heartbeatTimer = globalThis.setInterval(
    heartbeat,
    DEFAULT_HEARTBEAT_INTERVAL_MS,
  );
  const lease: SyncExecutionLease = {
    ownerToken,
    async assertOwned() {
      await heartbeatChain;
      if (heartbeatError) throw heartbeatError;
      assertSyncSession(guard);
      const owned = await ownsIndexedDbLease(
        database,
        ownerToken,
        now(),
      );
      if (!owned) throw new SyncLeaseLostError();
      assertSyncSession(guard);
    },
  };
  let taskFailed = false;
  let taskError: unknown;
  let result!: T;
  try {
    assertSyncSession(guard);
    result = await task(lease);
  } catch (error) {
    taskFailed = true;
    taskError = error;
  }

  globalThis.clearInterval(heartbeatTimer);
  let cleanupError: unknown;
  try {
    await heartbeatChain;
    await releaseIndexedDbLease(database, ownerToken);
  } catch (error) {
    cleanupError = error;
  }

  if (taskFailed) throw taskError;
  if (cleanupError) throw cleanupError;
  return result;
}

function getBrowserLockManager(): LockManager | null {
  if (
    typeof navigator === "undefined" ||
    !("locks" in navigator)
  ) {
    return null;
  }
  return navigator.locks;
}

async function acquireIndexedDbLease(
  guard: SyncSessionGuard,
  ownerToken: string,
  timestamp: number,
) {
  assertSyncSession(guard);
  await getSyncState(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const transaction = database.transaction("sync_state", "readwrite");
  const store = transaction.objectStore("sync_state");
  const current = (await store.get(
    SYNC_STATE_KEY,
  )) as SyncStateRecord;
  const activeLease = current.syncExecutionLease;
  const activeExpiry = activeLease
    ? Date.parse(activeLease.expiresAt)
    : Number.NaN;
  if (
    activeLease &&
    Number.isFinite(activeExpiry) &&
    activeExpiry > timestamp
  ) {
    await transaction.done;
    return null;
  }
  const instant = new Date(timestamp).toISOString();
  await store.put({
    ...current,
    syncExecutionLease: {
      ownerToken,
      acquiredAt: instant,
      heartbeatAt: instant,
      expiresAt: new Date(
        timestamp + DEFAULT_LEASE_TTL_MS,
      ).toISOString(),
    },
  });
  await transaction.done;
  assertSyncSession(guard);
  return database;
}

async function renewIndexedDbLease(
  database: Awaited<ReturnType<typeof getCortexDb>>,
  ownerToken: string,
  timestamp: number,
): Promise<boolean> {
  const transaction = database.transaction("sync_state", "readwrite");
  const store = transaction.objectStore("sync_state");
  const current = (await store.get(
    SYNC_STATE_KEY,
  )) as SyncStateRecord | undefined;
  const lease = current?.syncExecutionLease;
  const expiry = lease ? Date.parse(lease.expiresAt) : Number.NaN;
  if (
    !current ||
    !lease ||
    lease.ownerToken !== ownerToken ||
    !Number.isFinite(expiry) ||
    expiry <= timestamp
  ) {
    await transaction.done;
    return false;
  }
  const heartbeatAt = new Date(timestamp).toISOString();
  await store.put({
    ...current,
    syncExecutionLease: {
      ...lease,
      heartbeatAt,
      expiresAt: new Date(
        timestamp + DEFAULT_LEASE_TTL_MS,
      ).toISOString(),
    },
  });
  await transaction.done;
  return true;
}

async function ownsIndexedDbLease(
  database: Awaited<ReturnType<typeof getCortexDb>>,
  ownerToken: string,
  timestamp: number,
): Promise<boolean> {
  const current = (await database.get(
    "sync_state",
    SYNC_STATE_KEY,
  )) as SyncStateRecord | undefined;
  const lease = current?.syncExecutionLease;
  const expiry = lease ? Date.parse(lease.expiresAt) : Number.NaN;
  return Boolean(
    lease &&
      lease.ownerToken === ownerToken &&
      Number.isFinite(expiry) &&
      expiry > timestamp,
  );
}

async function releaseIndexedDbLease(
  database: Awaited<ReturnType<typeof getCortexDb>>,
  ownerToken: string,
): Promise<void> {
  const transaction = database.transaction("sync_state", "readwrite");
  const store = transaction.objectStore("sync_state");
  const current = (await store.get(
    SYNC_STATE_KEY,
  )) as SyncStateRecord | undefined;
  if (current?.syncExecutionLease?.ownerToken === ownerToken) {
    await store.put({
      ...current,
      syncExecutionLease: null,
    });
  }
  await transaction.done;
}
