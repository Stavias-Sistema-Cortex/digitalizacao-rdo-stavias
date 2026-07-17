import { AUTH_SESSION_CHANGED_EVENT } from "../../features/auth/authSession";

export const AUTOMATIC_SYNC_INTERVAL_MS = 30_000;
export const AUTOMATIC_SYNC_MAX_RETRY_DELAY_MS = 300_000;
export const LOCAL_MUTATION_QUEUED_EVENT =
  "cortex:local-mutation-queued";

const AUTOMATIC_SYNC_INITIAL_RETRY_DELAY_MS = 1_000;

export type AutomaticSyncTrigger =
  | "STARTUP"
  | "LOCAL_WRITE"
  | "ONLINE"
  | "INTERVAL"
  | "VISIBILITY"
  | "AUTH_SESSION"
  | "RETRY";

export interface AutomaticSyncRetryMetadata {
  attempt: number;
  nextAttemptAt: string | null;
}

export interface AutomaticSyncTimers {
  setInterval: (
    callback: () => void,
    delay: number,
  ) => number;
  clearInterval: (timerId: number) => void;
  setTimeout: (
    callback: () => void,
    delay: number,
  ) => number;
  clearTimeout: (timerId: number) => void;
}

interface SchedulerEventTarget {
  addEventListener: (
    type: string,
    listener: EventListener,
  ) => void;
  removeEventListener: (
    type: string,
    listener: EventListener,
  ) => void;
}

export interface AutomaticSyncSchedulerOptions<TResult = unknown> {
  syncNow: () => Promise<TResult>;
  hasOnlineSession: () => boolean;
  loadRetryMetadata: () => Promise<AutomaticSyncRetryMetadata>;
  persistRetryMetadata: (
    metadata: AutomaticSyncRetryMetadata,
  ) => Promise<void>;
  clearRetryMetadata: () => Promise<void>;
  isOnline?: () => boolean;
  now?: () => number;
  random?: () => number;
  timers?: AutomaticSyncTimers;
  eventTarget?: SchedulerEventTarget;
  visibilityTarget?: SchedulerEventTarget;
  getVisibilityState?: () => DocumentVisibilityState;
  intervalMs?: number;
  shouldRetryResult?: (result: TResult) => boolean;
  onSuccess?: (
    trigger: AutomaticSyncTrigger,
    result: TResult,
  ) => void;
  onError?: (
    trigger: AutomaticSyncTrigger,
    error: unknown,
  ) => void;
}

export interface AutomaticSyncScheduler {
  start: () => void;
  request: (trigger: AutomaticSyncTrigger) => void;
  dispose: () => void;
}

function defaultTimers(): AutomaticSyncTimers {
  return {
    setInterval: (callback, delay) =>
      window.setInterval(callback, delay),
    clearInterval: (timerId) => window.clearInterval(timerId),
    setTimeout: (callback, delay) =>
      window.setTimeout(callback, delay),
    clearTimeout: (timerId) => window.clearTimeout(timerId),
  };
}

function safeAttempt(value: number): number {
  return Number.isFinite(value)
    ? Math.max(0, Math.floor(value))
    : 0;
}

function safeRandom(random: () => number): number {
  const value = random();

  if (!Number.isFinite(value)) {
    return 0;
  }

  return Math.min(1, Math.max(0, value));
}

export function nextRetryDelay(
  attempt: number,
  random: () => number = Math.random,
): number {
  const exponent = Math.max(0, safeAttempt(attempt) - 1);
  const baseDelay = Math.min(
    AUTOMATIC_SYNC_MAX_RETRY_DELAY_MS,
    AUTOMATIC_SYNC_INITIAL_RETRY_DELAY_MS * 2 ** exponent,
  );
  const jitter = baseDelay * safeRandom(random);

  return Math.min(
    AUTOMATIC_SYNC_MAX_RETRY_DELAY_MS,
    Math.floor(baseDelay + jitter),
  );
}

function retryTimestamp(value: string | null): number | null {
  if (!value) {
    return null;
  }

  const timestamp = Date.parse(value);

  return Number.isFinite(timestamp) ? timestamp : null;
}

export function createAutomaticSyncScheduler<TResult = unknown>(
  options: AutomaticSyncSchedulerOptions<TResult>,
): AutomaticSyncScheduler {
  const now = options.now ?? Date.now;
  const random = options.random ?? Math.random;
  const timers = options.timers ?? defaultTimers();
  const eventTarget = options.eventTarget ?? window;
  const visibilityTarget = options.visibilityTarget ?? document;
  const getVisibilityState =
    options.getVisibilityState ??
    (() => document.visibilityState);
  const isOnline =
    options.isOnline ?? (() => navigator.onLine);
  const intervalMs =
    options.intervalMs ?? AUTOMATIC_SYNC_INTERVAL_MS;

  let started = false;
  let disposed = false;
  let intervalId: number | null = null;
  let retryTimerId: number | null = null;
  let inFlightPromise: Promise<void> | null = null;
  let pendingTrigger: AutomaticSyncTrigger | null = null;
  let retryMetadataLoaded = false;
  let retryMetadataPromise: Promise<void> | null = null;
  let retryAttempt = 0;
  let nextAttemptAt: number | null = null;

  function canSynchronize(): boolean {
    return isOnline() && options.hasOnlineSession();
  }

  function clearRetryTimer(): void {
    if (retryTimerId === null) {
      return;
    }

    timers.clearTimeout(retryTimerId);
    retryTimerId = null;
  }

  function scheduleRetryTimer(): void {
    if (
      disposed ||
      nextAttemptAt === null ||
      retryTimerId !== null
    ) {
      return;
    }

    retryTimerId = timers.setTimeout(() => {
      retryTimerId = null;
      request("RETRY");
    }, Math.max(0, nextAttemptAt - now()));
  }

  function loadRetryMetadata(): Promise<void> {
    if (retryMetadataLoaded) {
      return Promise.resolve();
    }

    if (retryMetadataPromise) {
      return retryMetadataPromise;
    }

    retryMetadataPromise = options
      .loadRetryMetadata()
      .then((metadata) => {
        retryAttempt = safeAttempt(metadata.attempt);
        nextAttemptAt = retryTimestamp(
          metadata.nextAttemptAt,
        );
      })
      .catch((error: unknown) => {
        retryAttempt = 0;
        nextAttemptAt = null;
        options.onError?.("STARTUP", error);
      })
      .finally(() => {
        retryMetadataLoaded = true;
        retryMetadataPromise = null;
      });

    return retryMetadataPromise;
  }

  async function handleRunFailure(
    trigger: AutomaticSyncTrigger,
    error: unknown,
  ): Promise<void> {
    retryAttempt += 1;
    nextAttemptAt =
      now() + nextRetryDelay(retryAttempt, random);

    try {
      await options.persistRetryMetadata({
        attempt: retryAttempt,
        nextAttemptAt: new Date(nextAttemptAt).toISOString(),
      });
    } catch (persistenceError: unknown) {
      options.onError?.(trigger, persistenceError);
    }

    options.onError?.(trigger, error);
    scheduleRetryTimer();
  }

  function beginRun(trigger: AutomaticSyncTrigger): void {
    clearRetryTimer();
    pendingTrigger = null;
    let runFailed = false;

    inFlightPromise = Promise.resolve()
      .then(() => options.syncNow())
      .then(async (result) => {
        if (options.shouldRetryResult?.(result)) {
          runFailed = true;
          await handleRunFailure(
            trigger,
            new Error(
              "A sincronização deixou operações pendentes para nova tentativa.",
            ),
          );
          return;
        }

        await options.clearRetryMetadata();
        retryAttempt = 0;
        nextAttemptAt = null;

        if (!disposed) {
          options.onSuccess?.(trigger, result);
        }
      })
      .catch(async (error: unknown) => {
        runFailed = true;

        if (!disposed) {
          await handleRunFailure(trigger, error);
        }
      })
      .finally(() => {
        inFlightPromise = null;

        if (disposed || !pendingTrigger) {
          return;
        }

        if (runFailed) {
          scheduleRetryTimer();
          return;
        }

        const nextTrigger = pendingTrigger;
        pendingTrigger = null;
        request(nextTrigger);
      });
  }

  function request(trigger: AutomaticSyncTrigger): void {
    if (disposed || !canSynchronize()) {
      return;
    }

    if (!retryMetadataLoaded) {
      void loadRetryMetadata().then(() => {
        request(trigger);
      });
      return;
    }

    if (inFlightPromise) {
      pendingTrigger = trigger;
      return;
    }

    if (
      nextAttemptAt !== null &&
      nextAttemptAt > now()
    ) {
      pendingTrigger = trigger;
      scheduleRetryTimer();
      return;
    }

    beginRun(trigger);
  }

  const handleLocalMutationQueued: EventListener = () => {
    request("LOCAL_WRITE");
  };

  const handleOnline: EventListener = () => {
    request("ONLINE");
  };

  const handleAuthSessionChanged: EventListener = () => {
    request("AUTH_SESSION");
  };

  const handleVisibilityChange: EventListener = () => {
    if (getVisibilityState() === "visible") {
      request("VISIBILITY");
    }
  };

  function start(): void {
    if (started || disposed) {
      return;
    }

    started = true;

    eventTarget.addEventListener(
      LOCAL_MUTATION_QUEUED_EVENT,
      handleLocalMutationQueued,
    );
    eventTarget.addEventListener("online", handleOnline);
    eventTarget.addEventListener(
      AUTH_SESSION_CHANGED_EVENT,
      handleAuthSessionChanged,
    );
    visibilityTarget.addEventListener(
      "visibilitychange",
      handleVisibilityChange,
    );

    intervalId = timers.setInterval(() => {
      request("INTERVAL");
    }, intervalMs);

    request("STARTUP");
  }

  function dispose(): void {
    if (disposed) {
      return;
    }

    disposed = true;
    pendingTrigger = null;

    eventTarget.removeEventListener(
      LOCAL_MUTATION_QUEUED_EVENT,
      handleLocalMutationQueued,
    );
    eventTarget.removeEventListener("online", handleOnline);
    eventTarget.removeEventListener(
      AUTH_SESSION_CHANGED_EVENT,
      handleAuthSessionChanged,
    );
    visibilityTarget.removeEventListener(
      "visibilitychange",
      handleVisibilityChange,
    );

    if (intervalId !== null) {
      timers.clearInterval(intervalId);
      intervalId = null;
    }

    clearRetryTimer();
  }

  return {
    start,
    request,
    dispose,
  };
}
