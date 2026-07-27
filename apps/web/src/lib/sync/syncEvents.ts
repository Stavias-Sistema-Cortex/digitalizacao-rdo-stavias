export const SYNC_COMPLETED_EVENT = "cortex:sync-completed";

export function announceSyncCompleted(): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new Event(SYNC_COMPLETED_EVENT));
}
