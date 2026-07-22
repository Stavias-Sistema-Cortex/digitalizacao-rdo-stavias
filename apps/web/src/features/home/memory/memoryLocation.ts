export interface MemoryLocationFilters {
  obraId?: string | null;
  rdoId?: string | null;
  actorId?: string | null;
  entityType?: string | null;
  entityId?: string | null;
  eventType?: string | null;
}

export function memoryHref(filters: MemoryLocationFilters = {}): string {
  const search = new URLSearchParams({ tab: "memory" });
  for (const [key, value] of Object.entries(filters)) {
    if (value?.trim()) {
      search.set(key, value.trim());
    }
  }
  return `/home?${search.toString()}`;
}
