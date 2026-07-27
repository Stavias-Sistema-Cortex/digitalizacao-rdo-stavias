import { describe, expect, it, vi } from "vitest";

import {
  createAutomaticSyncScheduler,
  type AutomaticSyncTrigger,
} from "./automaticSyncScheduler";
import { SyncLeaseUnavailableError } from "./syncExecutionLease";
import { AUTH_SESSION_CHANGED_EVENT } from "../../features/auth/authSession";
import { LOCAL_MUTATION_QUEUED_EVENT } from "./localMutationCoordinator";

async function flush(): Promise<void> {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
}

describe("automatic sync scheduler", () => {
  it("treats another tab's active lease as expected contention", async () => {
    const target = new EventTarget();
    const onError = vi.fn();
    const onSuccess = vi.fn();
    const scheduler = createAutomaticSyncScheduler({
      syncNow: vi.fn(async () => {
        throw new SyncLeaseUnavailableError();
      }),
      hasOnlineSession: () => true,
      isOnline: () => true,
      eventTarget: target,
      visibilityTarget: target,
      loadNextRetryAt: async () => null,
      onError,
      onSuccess,
    });

    scheduler.start();
    await flush();

    expect(onError).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
    scheduler.dispose();
  });

  it("pushes after an online event without a manual action", async () => {
    const target = new EventTarget();
    let online = false;
    const syncNow = vi.fn(async () => undefined);
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession: () => true,
      isOnline: () => online,
      eventTarget: target,
      visibilityTarget: target,
      loadNextRetryAt: async () => null,
    });

    scheduler.start();
    await flush();
    expect(syncNow).not.toHaveBeenCalled();

    online = true;
    target.dispatchEvent(new Event("online"));
    await flush();

    expect(syncNow).toHaveBeenCalledTimes(1);
    scheduler.dispose();
  });

  it("coalesces concurrent triggers into exactly one follow-up run", async () => {
    const target = new EventTarget();
    let releaseFirst!: () => void;
    const first = new Promise<void>((resolve) => {
      releaseFirst = resolve;
    });
    const syncNow = vi
      .fn<() => Promise<void>>()
      .mockImplementationOnce(() => first)
      .mockResolvedValue(undefined);
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession: () => true,
      isOnline: () => true,
      eventTarget: target,
      visibilityTarget: target,
      loadNextRetryAt: async () => null,
    });

    scheduler.start();
    await flush();
    expect(syncNow).toHaveBeenCalledTimes(1);

    target.dispatchEvent(new Event(LOCAL_MUTATION_QUEUED_EVENT));
    target.dispatchEvent(new Event("online"));
    target.dispatchEvent(new Event(AUTH_SESSION_CHANGED_EVENT));
    releaseFirst();
    await flush();

    expect(syncNow).toHaveBeenCalledTimes(2);
    scheduler.dispose();
  });

  it("restores the earliest persisted row retry timer after reload", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-22T12:00:00.000Z"));
    const target = new EventTarget();
    const triggers: AutomaticSyncTrigger[] = [];
    const syncNow = vi.fn(async () => undefined);
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession: () => true,
      isOnline: () => true,
      eventTarget: target,
      visibilityTarget: target,
      loadNextRetryAt: async () => "2026-07-22T12:00:05.000Z",
      onRun: (trigger) => triggers.push(trigger),
    });

    scheduler.start();
    await flush();
    expect(syncNow).toHaveBeenCalledTimes(1);

    await vi.advanceTimersByTimeAsync(5_000);
    await flush();

    expect(syncNow).toHaveBeenCalledTimes(2);
    expect(triggers).toContain("RETRY");
    scheduler.dispose();
    vi.useRealTimers();
  });

  it("does not let a future retry suppress a new independent local write", async () => {
    const target = new EventTarget();
    const syncNow = vi.fn(async () => undefined);
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession: () => true,
      isOnline: () => true,
      eventTarget: target,
      visibilityTarget: target,
      loadNextRetryAt: async () => "2099-01-01T00:00:00.000Z",
    });

    scheduler.start();
    await flush();
    target.dispatchEvent(new Event(LOCAL_MUTATION_QUEUED_EVENT));
    await flush();

    expect(syncNow).toHaveBeenCalledTimes(2);
    scheduler.dispose();
  });
});
