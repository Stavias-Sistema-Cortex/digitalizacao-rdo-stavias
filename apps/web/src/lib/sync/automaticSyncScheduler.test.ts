import { describe, expect, it, vi } from "vitest";

import {
  AUTOMATIC_SYNC_HIDDEN_INTERVAL_MS,
  AUTOMATIC_SYNC_INTERVAL_MS,
  createAutomaticSyncScheduler,
  type AutomaticSyncTrigger,
} from "./automaticSyncScheduler";
import {
  SyncLeaseLostError,
  SyncLeaseUnavailableError,
} from "./syncLeaseContention";
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

  /**
   * Perder o lease é a mesma disputa que não conseguir assumi-lo, com o
   * resultado invertido: outra aba assumiu e segue sincronizando. Só a primeira
   * metade era reconhecida, e a segunda chegava à tela como falha.
   */
  it("treats losing the lease mid-run as the same contention", async () => {
    const target = new EventTarget();
    const onError = vi.fn();
    const onSuccess = vi.fn();
    const scheduler = createAutomaticSyncScheduler({
      syncNow: vi.fn(async () => {
        throw new SyncLeaseLostError();
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

  it("still reports a real sync failure", async () => {
    const target = new EventTarget();
    const onError = vi.fn();
    const scheduler = createAutomaticSyncScheduler({
      syncNow: vi.fn(async () => {
        throw new Error("rede indisponível");
      }),
      hasOnlineSession: () => true,
      isOnline: () => true,
      eventTarget: target,
      visibilityTarget: target,
      loadNextRetryAt: async () => null,
      onError,
    });

    scheduler.start();
    await flush();

    expect(onError).toHaveBeenCalledTimes(1);
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

  /**
   * A cadência visível é o teto da propagação entre aparelhos: sem canal de
   * tempo real, o que outro dispositivo apagou só some daqui no próximo pull.
   * Dez segundos com gente olhando; oculta, a aba desacelera — e a volta ao
   * primeiro plano dispara uma rodada na hora, sem esperar intervalo nenhum.
   */
  it("polls fast while visible and slows down when the tab hides", async () => {
    vi.useFakeTimers();
    const target = new EventTarget();
    let visibility: DocumentVisibilityState = "visible";
    const syncNow = vi.fn(async () => undefined);
    const scheduler = createAutomaticSyncScheduler({
      syncNow,
      hasOnlineSession: () => true,
      isOnline: () => true,
      eventTarget: target,
      visibilityTarget: target,
      getVisibilityState: () => visibility,
      loadNextRetryAt: async () => null,
    });

    scheduler.start();
    await flush();
    expect(syncNow).toHaveBeenCalledTimes(1); // STARTUP

    await vi.advanceTimersByTimeAsync(AUTOMATIC_SYNC_INTERVAL_MS);
    await flush();
    expect(syncNow).toHaveBeenCalledTimes(2); // INTERVAL visível

    visibility = "hidden";
    target.dispatchEvent(new Event("visibilitychange"));
    await vi.advanceTimersByTimeAsync(AUTOMATIC_SYNC_HIDDEN_INTERVAL_MS - 1);
    await flush();
    expect(syncNow).toHaveBeenCalledTimes(2); // nada na cadência visível

    await vi.advanceTimersByTimeAsync(1);
    await flush();
    expect(syncNow).toHaveBeenCalledTimes(3); // INTERVAL oculto

    visibility = "visible";
    target.dispatchEvent(new Event("visibilitychange"));
    await flush();
    expect(syncNow).toHaveBeenCalledTimes(4); // VISIBILITY imediato

    await vi.advanceTimersByTimeAsync(AUTOMATIC_SYNC_INTERVAL_MS);
    await flush();
    expect(syncNow).toHaveBeenCalledTimes(5); // cadência rápida de volta

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
