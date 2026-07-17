import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

import {
  createAutomaticSyncScheduler,
  nextRetryDelay,
  type AutomaticSyncRetryMetadata,
  type AutomaticSyncTimers,
} from "./automaticSyncScheduler";
import type { OutboxMutationRecord } from "../db/db.types";
import {
  automaticSyncRetryMetadataFromMutations,
  mutationAfterAutomaticSyncRetryCleared,
  mutationAfterAutomaticSyncRetryScheduled,
} from "./syncStorage";

const STARTED_AT = Date.parse("2026-07-17T12:00:00.000Z");

async function flushPromises(): Promise<void> {
  for (let index = 0; index < 10; index += 1) {
    await Promise.resolve();
  }
}

function fakeTimers(): AutomaticSyncTimers {
  return {
    setInterval: (callback, delay) =>
      globalThis.setInterval(callback, delay) as unknown as number,
    clearInterval: (timerId) =>
      globalThis.clearInterval(timerId),
    setTimeout: (callback, delay) =>
      globalThis.setTimeout(callback, delay) as unknown as number,
    clearTimeout: (timerId) => globalThis.clearTimeout(timerId),
  };
}

function schedulerFixture(
  overrides: Partial<
    Parameters<typeof createAutomaticSyncScheduler>[0]
  > = {},
) {
  const eventTarget = new EventTarget();
  const visibilityTarget = new EventTarget();
  let visibilityState: DocumentVisibilityState = "visible";
  const syncNow = vi.fn().mockResolvedValue({ pushed: 0 });
  const loadRetryMetadata = vi
    .fn<() => Promise<AutomaticSyncRetryMetadata>>()
    .mockResolvedValue({
      attempt: 0,
      nextAttemptAt: null,
    });
  const persistRetryMetadata = vi
    .fn<
      (
        metadata: AutomaticSyncRetryMetadata,
      ) => Promise<void>
    >()
    .mockResolvedValue(undefined);
  const clearRetryMetadata = vi
    .fn<() => Promise<void>>()
    .mockResolvedValue(undefined);

  const scheduler = createAutomaticSyncScheduler({
    syncNow,
    hasOnlineSession: () => true,
    isOnline: () => true,
    now: () => Date.now(),
    random: () => 0,
    timers: fakeTimers(),
    eventTarget,
    visibilityTarget,
    getVisibilityState: () => visibilityState,
    loadRetryMetadata,
    persistRetryMetadata,
    clearRetryMetadata,
    ...overrides,
  });

  return {
    scheduler,
    eventTarget,
    visibilityTarget,
    syncNow,
    loadRetryMetadata,
    persistRetryMetadata,
    clearRetryMetadata,
    setVisibilityState(value: DocumentVisibilityState) {
      visibilityState = value;
    },
  };
}

describe("automatic sync scheduling", () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(STARTED_AT);
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("runs at startup and after a committed local mutation", async () => {
    const fixture = schedulerFixture();

    fixture.scheduler.start();
    await flushPromises();

    expect(fixture.syncNow).toHaveBeenCalledTimes(1);

    fixture.eventTarget.dispatchEvent(
      new Event("cortex:local-mutation-queued"),
    );
    await flushPromises();

    expect(fixture.syncNow).toHaveBeenCalledTimes(2);

    fixture.scheduler.dispose();
  });

  it("runs on reconnection, foreground return and each 30-second tick", async () => {
    const fixture = schedulerFixture();

    fixture.scheduler.start();
    await flushPromises();

    fixture.eventTarget.dispatchEvent(new Event("online"));
    await flushPromises();
    expect(fixture.syncNow).toHaveBeenCalledTimes(2);

    fixture.setVisibilityState("hidden");
    fixture.visibilityTarget.dispatchEvent(
      new Event("visibilitychange"),
    );
    await flushPromises();
    expect(fixture.syncNow).toHaveBeenCalledTimes(2);

    fixture.setVisibilityState("visible");
    fixture.visibilityTarget.dispatchEvent(
      new Event("visibilitychange"),
    );
    await flushPromises();
    expect(fixture.syncNow).toHaveBeenCalledTimes(3);

    await vi.advanceTimersByTimeAsync(30_000);
    await flushPromises();
    expect(fixture.syncNow).toHaveBeenCalledTimes(4);

    fixture.scheduler.dispose();
  });

  it("does not touch retry rows or authenticated APIs while offline or without an online session", async () => {
    let online = false;
    let onlineSession = true;
    const fixture = schedulerFixture({
      isOnline: () => online,
      hasOnlineSession: () => onlineSession,
    });

    fixture.scheduler.start();
    fixture.scheduler.request("LOCAL_WRITE");
    await flushPromises();

    expect(fixture.syncNow).not.toHaveBeenCalled();
    expect(fixture.loadRetryMetadata).not.toHaveBeenCalled();
    expect(fixture.persistRetryMetadata).not.toHaveBeenCalled();
    expect(fixture.clearRetryMetadata).not.toHaveBeenCalled();

    online = true;
    onlineSession = false;
    fixture.eventTarget.dispatchEvent(new Event("online"));
    await flushPromises();

    expect(fixture.syncNow).not.toHaveBeenCalled();
    expect(fixture.loadRetryMetadata).not.toHaveBeenCalled();

    fixture.scheduler.dispose();
  });

  it("coalesces concurrent triggers into one follow-up run", async () => {
    let finishFirstRun!: () => void;
    const firstRun = new Promise<void>((resolve) => {
      finishFirstRun = resolve;
    });
    const syncNow = vi
      .fn()
      .mockImplementationOnce(() => firstRun)
      .mockResolvedValue({ pushed: 0 });
    const fixture = schedulerFixture({ syncNow });

    fixture.scheduler.start();
    await flushPromises();
    expect(syncNow).toHaveBeenCalledTimes(1);

    fixture.scheduler.request("LOCAL_WRITE");
    fixture.eventTarget.dispatchEvent(new Event("online"));
    fixture.visibilityTarget.dispatchEvent(
      new Event("visibilitychange"),
    );
    await flushPromises();

    expect(syncNow).toHaveBeenCalledTimes(1);

    finishFirstRun();
    await flushPromises();

    expect(syncNow).toHaveBeenCalledTimes(2);

    fixture.scheduler.dispose();
  });

  it("persists exponential backoff and retries only when it becomes due", async () => {
    const transientFailure = new Error("Serviço indisponível");
    const syncNow = vi
      .fn()
      .mockRejectedValueOnce(transientFailure)
      .mockResolvedValue({ pushed: 1 });
    const fixture = schedulerFixture({ syncNow });

    fixture.scheduler.start();
    await flushPromises();

    expect(syncNow).toHaveBeenCalledTimes(1);
    expect(fixture.persistRetryMetadata).toHaveBeenCalledWith({
      attempt: 1,
      nextAttemptAt: "2026-07-17T12:00:01.000Z",
    });

    fixture.scheduler.request("LOCAL_WRITE");
    await vi.advanceTimersByTimeAsync(999);
    await flushPromises();
    expect(syncNow).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(1);
    await flushPromises();

    expect(syncNow).toHaveBeenCalledTimes(2);
    expect(fixture.clearRetryMetadata).toHaveBeenCalled();

    fixture.scheduler.dispose();
  });

  it("hydrates a persisted retry window after reload", async () => {
    const fixture = schedulerFixture({
      loadRetryMetadata: vi.fn().mockResolvedValue({
        attempt: 4,
        nextAttemptAt: "2026-07-17T12:00:05.000Z",
      }),
    });

    fixture.scheduler.start();
    await flushPromises();
    expect(fixture.syncNow).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(4_999);
    await flushPromises();
    expect(fixture.syncNow).not.toHaveBeenCalled();

    await vi.advanceTimersByTimeAsync(1);
    await flushPromises();
    expect(fixture.syncNow).toHaveBeenCalledTimes(1);

    fixture.scheduler.dispose();
  });

  it("removes event and timer triggers when disposed", async () => {
    const fixture = schedulerFixture();

    fixture.scheduler.start();
    await flushPromises();
    expect(fixture.syncNow).toHaveBeenCalledTimes(1);

    fixture.scheduler.dispose();
    fixture.eventTarget.dispatchEvent(
      new Event("cortex:local-mutation-queued"),
    );
    fixture.eventTarget.dispatchEvent(new Event("online"));
    fixture.visibilityTarget.dispatchEvent(
      new Event("visibilitychange"),
    );
    await vi.advanceTimersByTimeAsync(30_000);
    await flushPromises();

    expect(fixture.syncNow).toHaveBeenCalledTimes(1);
  });
});

describe("nextRetryDelay", () => {
  it("starts at one second, applies injected jitter and caps at five minutes", () => {
    expect(nextRetryDelay(1, () => 0)).toBe(1_000);
    expect(nextRetryDelay(2, () => 0.5)).toBe(3_000);
    expect(nextRetryDelay(20, () => 1)).toBe(300_000);

    expect(nextRetryDelay(1)).toBeGreaterThanOrEqual(1_000);
    expect(nextRetryDelay(8)).toBeLessThanOrEqual(300_000);
  });
});

const pendingMutation = {
  clientMutationId: "mutation-1",
  entidadeTipo: "RDO",
  entidadeId: "rdo-1",
  operacao: "CRIAR_RDO",
  baseVersao: null,
  payload: { id: "rdo-1" },
  status: "PENDING",
  tentativas: 2,
  ultimaTentativaEm: "2026-07-17T11:59:00.000Z",
  ultimoErro: "Serviço indisponível",
  conflito: null,
  criadaNoClienteEm: "2026-07-17T11:00:00.000Z",
  updatedAt: "2026-07-17T11:59:00.000Z",
  nextAttemptAt: "2026-07-17T12:00:05.000Z",
} as OutboxMutationRecord;

describe("automatic sync retry persistence", () => {
  it("hydrates the persisted attempt and earliest retry date from pending rows", () => {
    expect(
      automaticSyncRetryMetadataFromMutations([
        pendingMutation,
        {
          ...pendingMutation,
          clientMutationId: "mutation-2",
          tentativas: 4,
          nextAttemptAt: "2026-07-17T12:00:10.000Z",
        },
        {
          ...pendingMutation,
          clientMutationId: "mutation-synced",
          status: "SYNCED",
          tentativas: 9,
        },
      ]),
    ).toEqual({
      attempt: 4,
      nextAttemptAt: "2026-07-17T12:00:05.000Z",
    });
  });

  it("persists a scheduler attempt without lowering an existing item attempt", () => {
    expect(
      mutationAfterAutomaticSyncRetryScheduled(
        pendingMutation,
        {
          attempt: 1,
          nextAttemptAt: "2026-07-17T12:00:08.000Z",
        },
        "2026-07-17T12:00:00.000Z",
      ),
    ).toMatchObject({
      status: "PENDING",
      tentativas: 2,
      nextAttemptAt: "2026-07-17T12:00:08.000Z",
      updatedAt: "2026-07-17T12:00:00.000Z",
      payload: { id: "rdo-1" },
    });
  });

  it("clears the persisted backoff after a successful run", () => {
    expect(
      mutationAfterAutomaticSyncRetryCleared(
        pendingMutation,
        "2026-07-17T12:00:00.000Z",
      ),
    ).toMatchObject({
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      nextAttemptAt: null,
      updatedAt: "2026-07-17T12:00:00.000Z",
      payload: { id: "rdo-1" },
    });
  });
});
