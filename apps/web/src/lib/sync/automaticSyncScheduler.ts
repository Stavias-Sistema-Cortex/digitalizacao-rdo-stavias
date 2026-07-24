import { AUTH_SESSION_CHANGED_EVENT } from "../../features/auth/authSession";
import { LOCAL_MUTATION_QUEUED_EVENT } from "./localMutationCoordinator";

export const AUTOMATIC_SYNC_INTERVAL_MS = 30_000;
const MAX_TIMER_DELAY_MS = 2_147_483_647;

export type AutomaticSyncTrigger =
  | "STARTUP"
  | "LOCAL_WRITE"
  | "ONLINE"
  | "INTERVAL"
  | "VISIBILITY"
  | "AUTH_SESSION"
  | "RETRY";

interface SchedulerEventTarget {
  addEventListener(type: string, listener: EventListener): void;
  removeEventListener(type: string, listener: EventListener): void;
}

interface SchedulerTimers {
  setInterval(callback: () => void, delay: number): number;
  clearInterval(timerId: number): void;
  setTimeout(callback: () => void, delay: number): number;
  clearTimeout(timerId: number): void;
}

export interface AutomaticSyncSchedulerOptions {
  syncNow: () => Promise<unknown>;
  hasOnlineSession: () => boolean;
  loadNextRetryAt: () => Promise<string | null>;
  isOnline?: () => boolean;
  now?: () => number;
  timers?: SchedulerTimers;
  eventTarget?: SchedulerEventTarget;
  visibilityTarget?: SchedulerEventTarget;
  getVisibilityState?: () => DocumentVisibilityState;
  intervalMs?: number;
  onRun?: (trigger: AutomaticSyncTrigger) => void;
  onSuccess?: (trigger: AutomaticSyncTrigger, result: unknown) => void;
  onError?: (trigger: AutomaticSyncTrigger, error: unknown) => void;
}

export interface AutomaticSyncScheduler {
  start(): void;
  request(trigger: AutomaticSyncTrigger): void;
  dispose(): void;
}

export function createAutomaticSyncScheduler(
  options: AutomaticSyncSchedulerOptions,
): AutomaticSyncScheduler {
  const eventTarget = options.eventTarget ?? window;
  const visibilityTarget = options.visibilityTarget ?? document;
  const timers = options.timers ?? browserTimers();
  const now = options.now ?? Date.now;
  const isOnline = options.isOnline ?? (() => navigator.onLine);
  const getVisibilityState =
    options.getVisibilityState ?? (() => document.visibilityState);
  const intervalMs = options.intervalMs ?? AUTOMATIC_SYNC_INTERVAL_MS;

  let started = false;
  let disposed = false;
  let intervalId: number | null = null;
  let retryTimerId: number | null = null;
  let retryRefreshVersion = 0;
  let inFlight = false;
  let followUpRequested = false;
  let followUpTrigger: AutomaticSyncTrigger = "INTERVAL";

  function canRun(): boolean {
    return !disposed && isOnline() && options.hasOnlineSession();
  }

  function clearRetryTimer(): void {
    if (retryTimerId !== null) {
      timers.clearTimeout(retryTimerId);
      retryTimerId = null;
    }
  }

  async function refreshRetryTimer(): Promise<void> {
    const refreshVersion = ++retryRefreshVersion;
    clearRetryTimer();
    if (!canRun()) return;
    try {
      const value = await options.loadNextRetryAt();
      if (disposed || refreshVersion !== retryRefreshVersion || !value) return;
      const timestamp = Date.parse(value);
      // A due row is handled by the current/just-finished run. Avoid a tight
      // loop if a corrupt or terminal row retained an old timestamp.
      if (!Number.isFinite(timestamp) || timestamp <= now()) return;
      retryTimerId = timers.setTimeout(() => {
        retryTimerId = null;
        if (timestamp > now()) {
          void refreshRetryTimer();
        } else {
          request("RETRY");
        }
      }, Math.min(MAX_TIMER_DELAY_MS, timestamp - now()));
    } catch (error: unknown) {
      options.onError?.("RETRY", error);
    }
  }

  async function run(trigger: AutomaticSyncTrigger): Promise<void> {
    inFlight = true;
    options.onRun?.(trigger);
    try {
      const result = await options.syncNow();
      if (!disposed) options.onSuccess?.(trigger, result);
    } catch (error: unknown) {
      if (!disposed) options.onError?.(trigger, error);
    } finally {
      inFlight = false;
      await refreshRetryTimer();
      if (!disposed && followUpRequested) {
        const next = followUpTrigger;
        followUpRequested = false;
        request(next);
      }
    }
  }

  function request(trigger: AutomaticSyncTrigger): void {
    if (!canRun()) return;
    if (inFlight) {
      followUpRequested = true;
      followUpTrigger = trigger;
      return;
    }
    void run(trigger);
  }

  const onLocalWrite: EventListener = () => request("LOCAL_WRITE");
  const onOnline: EventListener = () => request("ONLINE");
  const onAuthSession: EventListener = () => {
    retryRefreshVersion += 1;
    clearRetryTimer();
    request("AUTH_SESSION");
  };
  const onVisibility: EventListener = () => {
    if (getVisibilityState() === "visible") request("VISIBILITY");
  };

  function start(): void {
    if (started || disposed) return;
    started = true;
    eventTarget.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, onLocalWrite);
    eventTarget.addEventListener("online", onOnline);
    eventTarget.addEventListener(AUTH_SESSION_CHANGED_EVENT, onAuthSession);
    visibilityTarget.addEventListener("visibilitychange", onVisibility);
    intervalId = timers.setInterval(() => request("INTERVAL"), intervalMs);
    request("STARTUP");
    void refreshRetryTimer();
  }

  function dispose(): void {
    if (disposed) return;
    disposed = true;
    retryRefreshVersion += 1;
    followUpRequested = false;
    eventTarget.removeEventListener(LOCAL_MUTATION_QUEUED_EVENT, onLocalWrite);
    eventTarget.removeEventListener("online", onOnline);
    eventTarget.removeEventListener(AUTH_SESSION_CHANGED_EVENT, onAuthSession);
    visibilityTarget.removeEventListener("visibilitychange", onVisibility);
    if (intervalId !== null) timers.clearInterval(intervalId);
    intervalId = null;
    clearRetryTimer();
  }

  return { start, request, dispose };
}

function browserTimers(): SchedulerTimers {
  return {
    setInterval: (callback, delay) =>
      globalThis.setInterval(callback, delay) as unknown as number,
    clearInterval: (timerId) => globalThis.clearInterval(timerId),
    setTimeout: (callback, delay) =>
      globalThis.setTimeout(callback, delay) as unknown as number,
    clearTimeout: (timerId) => globalThis.clearTimeout(timerId),
  };
}
