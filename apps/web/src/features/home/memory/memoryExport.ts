import type { MemoryFilters } from "./memoryApi";
import type {
  MemoryCacheMetadata,
  MemorySearchDocument,
} from "./memorySearchDocument";

export const MAX_MEMORY_EXPORT_ITEMS = 1_000;

export interface MemoryExportResult {
  filename: string;
  content: string;
  exportedCount: number;
  totalMatches: number;
  truncated: boolean;
}

export function buildMemoryExport(input: {
  items: readonly MemorySearchDocument[];
  totalMatches: number;
  filters: MemoryFilters;
  metadata: MemoryCacheMetadata | null;
  generatedAt?: string;
}): MemoryExportResult {
  const generatedAt = input.generatedAt ?? new Date().toISOString();
  const items = input.items
    .slice(0, MAX_MEMORY_EXPORT_ITEMS)
    .map(exportedEvent);
  const totalMatches = Math.max(0, input.totalMatches);
  const truncated =
    totalMatches > items.length ||
    input.items.length > MAX_MEMORY_EXPORT_ITEMS;
  const content = JSON.stringify({
    schemaVersion: 1,
    generatedAt,
    filters: compactFilters(input.filters),
    coverage: exportedCoverage(input.metadata),
    totalMatches,
    exportedCount: items.length,
    truncated,
    items,
  }, null, 2);

  return {
    filename: `memoria-operacional-${generatedAt.replace(/[:.]/g, "-")}.json`,
    content,
    exportedCount: items.length,
    totalMatches,
    truncated,
  };
}

function exportedEvent(document: MemorySearchDocument) {
  return {
    eventId: document.eventId,
    commitSequence: document.commitSequence,
    occurredAt: document.occurredAt,
    eventType: document.eventType,
    source: document.source,
    sourceKind: document.sourceKind,
    syncStatus: document.syncStatus,
    principalName: document.principalName,
    worksiteName: document.worksiteName,
    rdoNumber: document.rdoNumber,
    serviceName: document.serviceName,
    errorCategory: document.errorCategory,
    structuralKeys: { ...document.structuralKeys },
    trace: { ...document.trace },
    review: document.review
      ? {
        ...document.review,
        changedFields: [...document.review.changedFields],
        conflictFields: [...document.review.conflictFields],
      }
      : null,
  };
}

function compactFilters(filters: MemoryFilters): Partial<MemoryFilters> {
  return Object.fromEntries(
    Object.entries(filters).filter(([, value]) =>
      typeof value === "string" ? Boolean(value.trim()) : value !== undefined
    ),
  ) as Partial<MemoryFilters>;
}

function exportedCoverage(metadata: MemoryCacheMetadata | null) {
  if (!metadata) return null;
  return {
    highWaterMark: metadata.highWaterMark,
    authorizedEventCount: metadata.authorizedEventCount,
    cachedEventCount: metadata.cachedEventCount,
    oldestCommitSequence: metadata.oldestCommitSequence,
    newestCommitSequence: metadata.newestCommitSequence,
    coverageMode: metadata.coverageMode,
    serverCoverageComplete: metadata.serverCoverageComplete,
    graph: metadata.graph ?? null,
    complete: metadata.complete,
    cachedAt: metadata.cachedAt,
  };
}
