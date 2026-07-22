import { describe, expect, it, vi } from "vitest";

import { SYNC_COMPLETED_EVENT } from "../../../lib/sync/syncEngine";
import {
  bindMemoryReconnectRefresh,
  createMemoryRefreshState,
  runCoalescedMemoryRefresh,
} from "./memoryReconnect";

describe("Memory reconnect refresh", () => {
  it("refreshes after browser reconnect and after a completed canonical sync", () => {
    const target = new EventTarget();
    const refresh = vi.fn();
    const dispose = bindMemoryReconnectRefresh(target, refresh);

    target.dispatchEvent(new Event("online"));
    target.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));

    expect(refresh).toHaveBeenCalledTimes(2);
    dispose();
    target.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));
    expect(refresh).toHaveBeenCalledTimes(2);
  });

  it("coalesces a completion event during hydration into one immediate follow-up", async () => {
    const state = createMemoryRefreshState();
    let release!: () => void;
    const refresh = vi.fn()
      .mockImplementationOnce(() => new Promise<void>((resolve) => {
        release = resolve;
      }))
      .mockResolvedValue(undefined);

    const first = runCoalescedMemoryRefresh(state, refresh);
    await Promise.resolve();
    await runCoalescedMemoryRefresh(state, refresh);
    await runCoalescedMemoryRefresh(state, refresh);
    expect(refresh).toHaveBeenCalledTimes(1);

    release();
    await first;
    await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(2));
  });
});
