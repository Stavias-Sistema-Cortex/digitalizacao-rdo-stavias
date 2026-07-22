import type { IDBPDatabase } from "idb";

import type { CortexDbSchema } from "../../../lib/db/cortexDb";
import type { OperationalEventRecord } from "../../../lib/db/db.types";
import type { MemoryFilters, MemoryPage } from "./memoryApi";
import {
  localEventToSearchDocument,
  normalizeMemoryText,
  serverEventToSearchDocument,
  type MemoryCacheMetadata,
  type MemorySearchDocument,
} from "./memorySearchDocument";

export interface MemorySearchRequest {
  userId: string;
  scopeHash: string;
  filters: MemoryFilters;
  allowedWorksiteIds?: readonly string[] | null;
  offset?: number;
  limit: number;
}

export interface MemorySearchResult {
  items: MemorySearchDocument[];
  totalMatches: number;
  localStatuses: MemorySearchDocument["syncStatus"][];
}

export interface MemoryRepository {
  putPage(userId: string, page: MemoryPage): Promise<void>;
  resetAuthorizedCache(userId: string): Promise<void>;
  markComplete(userId: string, page: MemoryPage): Promise<MemoryCacheMetadata>;
  latestMetadata(userId: string): Promise<MemoryCacheMetadata | null>;
  search(request: MemorySearchRequest): Promise<MemorySearchResult>;
}

export function createMemoryRepository(
  database: IDBPDatabase<CortexDbSchema>,
): MemoryRepository {
  return {
    async putPage(userId, page) {
      const transaction = database.transaction(
        ["memory_search_documents", "memory_cache_metadata"],
        "readwrite",
      );
      const documentStore = transaction.objectStore("memory_search_documents");
      await Promise.all(
        page.items.map((event) =>
          documentStore.put(
            serverEventToSearchDocument(userId, page.scopeHash, event),
          ),
        ),
      );
      const cachedEventCount = await documentStore.index("by-user-scope").count(
        [userId, page.scopeHash],
      );
      await transaction.objectStore("memory_cache_metadata").put({
        key: metadataKey(userId, page.scopeHash),
        userId,
        scopeHash: page.scopeHash,
        highWaterMark: page.highWaterMark,
        authorizedEventCount: page.coverage.authorizedEventCount,
        cachedEventCount,
        oldestCommitSequence: page.coverage.oldestCommitSequence,
        newestCommitSequence: page.coverage.newestCommitSequence,
        coverageMode: page.coverage.mode,
        serverCoverageComplete: page.coverage.complete,
        complete: false,
        cachedAt: page.serverTime,
      });
      await transaction.done;
    },

    async resetAuthorizedCache(userId) {
      const transaction = database.transaction(
        ["memory_search_documents", "memory_cache_metadata"],
        "readwrite",
      );
      const documentStore = transaction.objectStore("memory_search_documents");
      const metadataStore = transaction.objectStore("memory_cache_metadata");
      const [documents, metadata] = await Promise.all([
        documentStore.getAll(),
        metadataStore.index("by-user").getAll(userId),
      ]);
      await Promise.all([
        ...documents
          .filter((document) => document.userId === userId)
          .map((document) => documentStore.delete(document.key)),
        ...metadata.map((entry) => metadataStore.delete(entry.key)),
      ]);
      await transaction.done;
    },

    async markComplete(userId, page) {
      const transaction = database.transaction(
        ["memory_search_documents", "memory_cache_metadata"],
        "readwrite",
      );
      const cachedEventCount = await transaction
        .objectStore("memory_search_documents")
        .index("by-user-scope")
        .count([userId, page.scopeHash]);
      const metadata: MemoryCacheMetadata & { key: string } = {
        key: metadataKey(userId, page.scopeHash),
        userId,
        scopeHash: page.scopeHash,
        highWaterMark: page.highWaterMark,
        authorizedEventCount: page.coverage.authorizedEventCount,
        cachedEventCount,
        oldestCommitSequence: page.coverage.oldestCommitSequence,
        newestCommitSequence: page.coverage.newestCommitSequence,
        coverageMode: page.coverage.mode,
        serverCoverageComplete: page.coverage.complete,
        complete:
          !page.hasMore &&
          page.coverage.complete &&
          cachedEventCount === page.coverage.authorizedEventCount,
        cachedAt: page.serverTime,
      };
      await transaction.objectStore("memory_cache_metadata").put(metadata);
      await transaction.done;
      return metadata;
    },

    async latestMetadata(userId) {
      const values = await database.getAllFromIndex(
        "memory_cache_metadata",
        "by-user",
        userId,
      );
      return values.sort((left, right) =>
        right.cachedAt.localeCompare(left.cachedAt),
      )[0] ?? null;
    },

    async search(request) {
      const [serverDocuments, localEvents] = await Promise.all([
        database.getAllFromIndex(
          "memory_search_documents",
          "by-user-scope",
          [request.userId, request.scopeHash],
        ),
        database.getAll("operational_events"),
      ]);
      const allowed = request.allowedWorksiteIds === null ||
          request.allowedWorksiteIds === undefined
        ? null
        : new Set(request.allowedWorksiteIds);
      const localDocuments = localEvents
        .filter((event) => authorizedLocalEvent(event, request.userId, allowed))
        .map((event) =>
          localEventToSearchDocument(request.userId, request.scopeHash, event),
        );
      const merged = new Map<string, MemorySearchDocument>();
      for (const document of serverDocuments) merged.set(document.eventId, document);
      for (const document of localDocuments) {
        if (!merged.has(document.eventId)) merged.set(document.eventId, document);
      }
      const matches = [...merged.values()]
        .filter((document) => matchesFilters(document, request.filters))
        .sort(compareDocuments);
      const offset = Math.max(0, request.offset ?? 0);
      return {
        items: matches.slice(offset, offset + request.limit),
        totalMatches: matches.length,
        localStatuses: localDocuments
          .map((document) => document.syncStatus)
          .filter((status) => status !== "UPDATED"),
      };
    },
  };
}

function authorizedLocalEvent(
  event: OperationalEventRecord,
  userId: string,
  allowed: ReadonlySet<string> | null,
): boolean {
  if (event.responsibleUserId && event.responsibleUserId !== userId) return false;
  return allowed === null ||
    (event.obraId !== null && allowed.has(event.obraId)) ||
    (event.obraId === null && event.responsibleUserId === userId);
}

function matchesFilters(
  document: MemorySearchDocument,
  filters: MemoryFilters,
): boolean {
  const queryTokens = normalizeMemoryText(filters.q ?? "").split(" ").filter(Boolean);
  if (queryTokens.some((token) => !document.normalizedText.includes(token))) return false;
  const structural = document.structuralKeys;
  if (!same(structural.eventType, filters.eventType)) return false;
  if (!same(structural.entityType, filters.entityType)) return false;
  if (!same(structural.entityId, filters.entityId)) return false;
  if (!same(structural.worksiteId, filters.worksiteId)) return false;
  if (!same(structural.rdoId, filters.rdoId)) return false;
  if (!same(structural.origin, filters.origin)) return false;
  if (!same(structural.result, filters.result)) return false;
  const occurredAt = Date.parse(document.occurredAt);
  const from = filters.from ? Date.parse(filters.from) : Number.NaN;
  const to = filters.to ? Date.parse(filters.to) : Number.NaN;
  if (Number.isFinite(from) && occurredAt < from) return false;
  if (Number.isFinite(to) && occurredAt > to) return false;
  return true;
}

function same(actual: string | null, expected: string | undefined): boolean {
  return !expected?.trim() || actual?.toLocaleUpperCase("pt-BR") ===
    expected.trim().toLocaleUpperCase("pt-BR");
}

function compareDocuments(left: MemorySearchDocument, right: MemorySearchDocument): number {
  if (left.sourceKind !== right.sourceKind) return left.sourceKind === "LOCAL" ? -1 : 1;
  if (left.commitSequence !== null && right.commitSequence !== null) {
    const byCommit = right.commitSequence - left.commitSequence;
    if (byCommit !== 0) return byCommit;
  }
  const byTime = right.occurredAt.localeCompare(left.occurredAt);
  return byTime !== 0 ? byTime : right.eventId.localeCompare(left.eventId);
}

function metadataKey(userId: string, scopeHash: string): string {
  return `${userId}:${scopeHash}`;
}
