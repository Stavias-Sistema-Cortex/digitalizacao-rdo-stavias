import { SYNC_COMPLETED_EVENT } from "../../../lib/sync/syncEvents";

export interface MemoryRefreshState {
  inFlight: boolean;
  queued: boolean;
}

export function createMemoryRefreshState(): MemoryRefreshState {
  return { inFlight: false, queued: false };
}

export async function runCoalescedMemoryRefresh(
  state: MemoryRefreshState,
  refresh: () => Promise<void>,
): Promise<void> {
  if (state.inFlight) {
    state.queued = true;
    return;
  }
  state.inFlight = true;
  try {
    await refresh();
  } finally {
    const repeat = state.queued;
    state.queued = false;
    state.inFlight = false;
    if (repeat) {
      queueMicrotask(() => {
        void runCoalescedMemoryRefresh(state, refresh);
      });
    }
  }
}

export function bindMemoryReconnectRefresh(
  target: EventTarget,
  refresh: () => void,
): () => void {
  const requestRefresh = () => refresh();
  target.addEventListener("online", requestRefresh);
  target.addEventListener(SYNC_COMPLETED_EVENT, requestRefresh);
  return () => {
    target.removeEventListener("online", requestRefresh);
    target.removeEventListener(SYNC_COMPLETED_EVENT, requestRefresh);
  };
}
