import "fake-indexeddb/auto";

import { deleteDB, openDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
  type AuthProfile,
} from "../../features/auth/authSession";
import {
  closeCortexDb,
  CORTEX_DATABASE_VERSION,
  getCortexDb,
} from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import { captureOnlineSyncSession } from "./syncSession";
import {
  runWithSyncExecutionLease,
  SyncLeaseUnavailableError,
} from "./syncExecutionLease";

const USER_ID = "00000000-0000-4000-8000-000000000801";
const WORKSITE_ID = "00000000-0000-4000-8000-000000000802";
const START = Date.parse("2026-07-25T12:00:00.000Z");

let databaseName = "";

beforeEach(async () => {
  const profile: AuthProfile = {
    colaboradorId: USER_ID,
    nome: "Responsável pelo dispositivo",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
  setSession(profile);
  databaseName = await databaseNameForScope(
    USER_ID,
    `BETA:${WORKSITE_ID}`,
  );
});

afterEach(async () => {
  vi.useRealTimers();
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("durable sync execution lease", () => {
  it("preserves an active lease while upgrading an existing v19 database", async () => {
    const legacy = await openDB(databaseName, 19, {
      upgrade(database) {
        database.createObjectStore("sync_state", {
          keyPath: "key",
        });
      },
    });
    await legacy.put("sync_state", {
      key: "default",
      deviceId: "device-before-upgrade",
      usuarioId: USER_ID,
      lastPulledCommitSeq: 7,
      lastAckedCommitSeq: 7,
      isSyncing: true,
      lastSyncStartedAt: new Date(START).toISOString(),
      lastSyncCompletedAt: null,
      lastSyncError: null,
      syncExecutionLease: {
        ownerToken: "owner-before-upgrade",
        acquiredAt: new Date(START).toISOString(),
        heartbeatAt: new Date(START + 15_000).toISOString(),
        expiresAt: new Date(START + 60_000).toISOString(),
      },
    });
    legacy.close();

    const upgraded = await getCortexDb();

    expect(CORTEX_DATABASE_VERSION).toBe(22);
    expect(upgraded.version).toBe(22);
    expect(
      await upgraded.get("sync_state", "default"),
    ).toMatchObject({
      deviceId: "device-before-upgrade",
      lastPulledCommitSeq: 7,
      syncExecutionLease: {
        ownerToken: "owner-before-upgrade",
        heartbeatAt: new Date(
          START + 15_000,
        ).toISOString(),
        expiresAt: new Date(START + 60_000).toISOString(),
      },
    });
  });

  it("backfills a nullable lease on v19 sync state rows", async () => {
    const legacy = await openDB(databaseName, 19, {
      upgrade(database) {
        database.createObjectStore("sync_state", {
          keyPath: "key",
        });
      },
    });
    await legacy.put("sync_state", {
      key: "default",
      deviceId: "device-before-upgrade",
      usuarioId: USER_ID,
      lastPulledCommitSeq: 7,
      lastAckedCommitSeq: 7,
      isSyncing: false,
      lastSyncStartedAt: null,
      lastSyncCompletedAt: null,
      lastSyncError: null,
    });
    legacy.close();

    const upgraded = await getCortexDb();

    expect(
      await upgraded.get("sync_state", "default"),
    ).toHaveProperty("syncExecutionLease", null);
  });

  it("prefers a non-blocking Web Lock when the browser exposes it", async () => {
    const request = vi.fn(
      async (
        _name: string,
        _options: LockOptions,
        callback: (lock: Lock | null) => Promise<string>,
      ) => callback(null),
    );
    const task = vi.fn(async () => "should-not-run");

    await expect(
      runWithSyncExecutionLease(
        captureOnlineSyncSession(),
        task,
        {
          locks: { request } as unknown as LockManager,
          now: () => START,
          ownerToken: () => "owner-first",
        },
      ),
    ).rejects.toBeInstanceOf(SyncLeaseUnavailableError);

    expect(request).toHaveBeenCalledWith(
      "cortex-sync-execution-v1",
      {
        ifAvailable: true,
        mode: "exclusive",
      },
      expect.any(Function),
    );
    expect(task).not.toHaveBeenCalled();
  });

  it("does not execute a second owner while the first owner's lease is active", async () => {
    let releaseFirst!: () => void;
    let firstEntered!: () => void;
    const entered = new Promise<void>((resolve) => {
      firstEntered = resolve;
    });
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const guard = captureOnlineSyncSession();
    const first = runWithSyncExecutionLease(
      guard,
      async () => {
        firstEntered();
        await firstGate;
        return "first";
      },
      {
        locks: null,
        now: () => START,
        ownerToken: () => "owner-first",
      },
    );
    await entered;

    const secondTask = vi.fn(async () => "second");
    await expect(
      runWithSyncExecutionLease(guard, secondTask, {
        locks: null,
        now: () => START + 1_000,
        ownerToken: () => "owner-second",
      }),
    ).rejects.toBeInstanceOf(SyncLeaseUnavailableError);
    expect(secondTask).not.toHaveBeenCalled();

    releaseFirst();
    await expect(first).resolves.toBe("first");
  });

  it("releases ownership when the protected task completes", async () => {
    const guard = captureOnlineSyncSession();

    await expect(
      runWithSyncExecutionLease(guard, async () => "first", {
        locks: null,
        now: () => START,
        ownerToken: () => "owner-first",
      }),
    ).resolves.toBe("first");

    await expect(
      runWithSyncExecutionLease(guard, async () => "second", {
        locks: null,
        now: () => START + 1_000,
        ownerToken: () => "owner-second",
      }),
    ).resolves.toBe("second");
  });

  it("preserves the task error if session rotation closes the database before release", async () => {
    const expected = new Error(
      "A sessão mudou durante a sincronização.",
    );

    await expect(
      runWithSyncExecutionLease(
        captureOnlineSyncSession(),
        async () => {
          await closeCortexDb();
          throw expected;
        },
        {
          locks: null,
          now: () => START,
          ownerToken: () => "owner-before-rotation",
        },
      ),
    ).rejects.toBe(expected);
  });

  it("allows takeover only after the previous owner's expiry", async () => {
    let releaseFirst!: () => void;
    let firstEntered!: () => void;
    const entered = new Promise<void>((resolve) => {
      firstEntered = resolve;
    });
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const guard = captureOnlineSyncSession();
    const first = runWithSyncExecutionLease(
      guard,
      async () => {
        firstEntered();
        await firstGate;
        return "first";
      },
      {
        locks: null,
        now: () => START,
        ownerToken: () => "owner-first",
      },
    );
    await entered;

    await expect(
      runWithSyncExecutionLease(guard, async () => "takeover", {
        locks: null,
        now: () => START + 45_001,
        ownerToken: () => "owner-after-crash",
      }),
    ).resolves.toBe("takeover");

    releaseFirst();
    await first;
  });

  it("renews the durable expiry while the owner remains active", async () => {
    vi.useFakeTimers({
      toFake: ["setInterval", "clearInterval"],
    });
    let currentTime = START;
    let releaseFirst!: () => void;
    let firstEntered!: () => void;
    const entered = new Promise<void>((resolve) => {
      firstEntered = resolve;
    });
    const firstGate = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const guard = captureOnlineSyncSession();
    const first = runWithSyncExecutionLease(
      guard,
      async () => {
        firstEntered();
        await firstGate;
        return "first";
      },
      {
        locks: null,
        now: () => currentTime,
        ownerToken: () => "owner-first",
      },
    );
    await entered;

    currentTime = START + 20_000;
    await vi.advanceTimersByTimeAsync(20_000);

    const secondTask = vi.fn(async () => "second");
    const secondOutcome = await runWithSyncExecutionLease(
      guard,
      secondTask,
      {
        locks: null,
        now: () => START + 45_001,
        ownerToken: () => "owner-second",
      },
    ).then(
      (value) => value,
      (error: unknown) => error,
    );

    releaseFirst();
    await first;

    expect(secondOutcome).toBeInstanceOf(
      SyncLeaseUnavailableError,
    );
    expect(secondTask).not.toHaveBeenCalled();
  });
});
