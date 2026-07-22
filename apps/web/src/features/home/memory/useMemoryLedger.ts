import { useCallback, useEffect, useRef, useState } from "react";

import {
  AUTH_SESSION_CHANGED_EVENT,
  getSession,
  hasOnlineSession,
} from "../../auth/authSession";
import { getCortexDb } from "../../../lib/db/cortexDb";
import { LOCAL_MUTATION_QUEUED_EVENT } from "../../../lib/sync/localMutationCoordinator";
import { fetchMemoryPage, type MemoryFilters, type MemoryPage } from "./memoryApi";
import { createMemoryRepository } from "./memoryRepository";
import {
  assertMemorySessionGuard,
  captureMemorySessionGuard,
  commitIfMemorySessionCurrent,
} from "./memorySessionGuard";
import {
  memoryCoverage,
  type MemoryCacheMetadata,
  type MemoryCoverageView,
  type MemorySearchDocument,
} from "./memorySearchDocument";

const PAGE_SIZE = 50;
const SERVER_PAGE_SIZE = 100;
const MEMORY_REFRESH_REQUEST_EVENT = "cortex:memory-refresh-requested";

interface HydrateAuthorizedMemoryCacheOptions {
  userId: string;
  previousMetadata: MemoryCacheMetadata | null;
  fetchPage: typeof fetchMemoryPage;
  putPage: (userId: string, page: MemoryPage) => Promise<void>;
  markComplete: (
    userId: string,
    page: MemoryPage,
  ) => Promise<MemoryCacheMetadata>;
  resetAuthorizedCache: (userId: string) => Promise<void>;
  onCacheReset?: () => void;
  assertSession: () => void;
}

export async function hydrateAuthorizedMemoryCache({
  userId,
  previousMetadata,
  fetchPage: loadPage,
  putPage,
  markComplete,
  resetAuthorizedCache,
  onCacheReset,
  assertSession,
}: HydrateAuthorizedMemoryCacheOptions): Promise<MemoryCacheMetadata> {
  let cursor: MemoryPage["nextCursor"] = null;
  let firstPage: MemoryPage | null = null;
  let finalPage: MemoryPage;
  const seenCursors = new Set<string>();

  do {
    assertSession();
    const page = await loadPage({ limit: SERVER_PAGE_SIZE }, cursor);
    assertSession();
    if (firstPage) assertStableSnapshot(firstPage, page);
    firstPage ??= page;
    finalPage = page;
    if (page === firstPage && !canReuseCache(previousMetadata, page)) {
      onCacheReset?.();
      await resetAuthorizedCache(userId);
      assertSession();
    }
    await putPage(userId, page);
    assertSession();

    const crossedExistingHighWater =
      canReuseCache(previousMetadata, page) &&
      page.items.some(
        (item) => item.commitSequence <= previousMetadata.highWaterMark,
      );
    const unchangedCompleteSnapshot =
      canReuseCache(previousMetadata, page) &&
      previousMetadata.highWaterMark === page.highWaterMark;
    if (crossedExistingHighWater || unchangedCompleteSnapshot) {
      finalPage = { ...page, hasMore: false, nextCursor: null };
      break;
    }

    cursor = page.nextCursor;
    if (page.hasMore && !cursor) {
      throw new Error("O servidor não forneceu o cursor assinado da próxima página da Memória.");
    }
    if (cursor) {
      if (seenCursors.has(cursor.token)) {
        throw new Error("A paginação assinada da Memória não avançou.");
      }
      seenCursors.add(cursor.token);
    }
  } while (finalPage?.hasMore);

  if (!firstPage) {
    throw new Error("A Memória não retornou cobertura.");
  }
  assertSession();
  const metadata = await markComplete(userId, {
    ...firstPage,
    hasMore: finalPage.hasMore,
    nextCursor: finalPage.nextCursor,
    serverTime: finalPage.serverTime,
  });
  assertSession();
  return metadata;
}

export interface MemoryLedgerError {
  source: "CACHE" | "SERVER";
  message: string;
}

export interface MemoryLedgerViewModel {
  items: MemorySearchDocument[];
  totalMatches: number;
  hasMoreLocal: boolean;
  filters: MemoryFilters;
  coverage: MemoryCoverageView;
  metadata: MemoryCacheMetadata | null;
  isLoading: boolean;
  isRefreshing: boolean;
  error: MemoryLedgerError | null;
  setFilters: (patch: Partial<MemoryFilters>) => void;
  clearFilters: () => void;
  loadMore: () => void;
  refresh: () => void;
}

export function useMemoryLedger(): MemoryLedgerViewModel {
  const [filters, setFilterState] = useState<MemoryFilters>({});
  const [items, setItems] = useState<MemorySearchDocument[]>([]);
  const [totalMatches, setTotalMatches] = useState(0);
  const [visibleLimit, setVisibleLimit] = useState(PAGE_SIZE);
  const [metadata, setMetadata] = useState<MemoryCacheMetadata | null>(null);
  const [coverage, setCoverage] = useState<MemoryCoverageView>(() =>
    memoryCoverage({ online: navigator.onLine, metadata: null, localStatuses: [] }),
  );
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<MemoryLedgerError | null>(null);
  const mounted = useRef(true);
  const refreshInFlight = useRef(false);
  const refreshQueued = useRef(false);

  const readLedger = useCallback(async () => {
    const session = getSession();
    if (!session) return;
    const guard = captureMemorySessionGuard();
    try {
      const database = await getCortexDb();
      assertMemorySessionGuard(guard);
      const repository = createMemoryRepository(database);
      const currentMetadata = await repository.latestMetadata(session.colaboradorId);
      assertMemorySessionGuard(guard);
      const scopeHash = currentMetadata?.scopeHash ?? localScopeMarker(session);
      const result = await repository.search({
        userId: session.colaboradorId,
        scopeHash,
        filters,
        allowedWorksiteIds: session.escopoGlobal ? null : session.obraIds,
        limit: visibleLimit,
      });
      assertMemorySessionGuard(guard);
      if (!mounted.current) return;
      commitIfMemorySessionCurrent(guard, () => {
        setItems(result.items);
        setTotalMatches(result.totalMatches);
        setMetadata(currentMetadata);
        setCoverage(memoryCoverage({
          online: navigator.onLine && hasOnlineSession(),
          metadata: currentMetadata,
          localStatuses: result.localStatuses,
        }));
      });
    } catch (cause: unknown) {
      if (mounted.current) {
        commitIfMemorySessionCurrent(guard, () => {
          setError({
            source: "CACHE",
            message: cause instanceof Error
              ? `Não foi possível ler o cache da Memória: ${cause.message}`
              : "Não foi possível ler o cache da Memória.",
          });
        });
      }
    } finally {
      if (mounted.current) {
        commitIfMemorySessionCurrent(guard, () => setIsLoading(false));
      }
    }
  }, [filters, visibleLimit]);

  const readLedgerRef = useRef(readLedger);
  useEffect(() => {
    readLedgerRef.current = readLedger;
  }, [readLedger]);

  const synchronizeCache = useCallback(async (): Promise<void> => {
    const session = getSession();
    if (!session || !navigator.onLine || !hasOnlineSession()) {
      await readLedgerRef.current();
      return;
    }
    const guard = captureMemorySessionGuard();
    if (refreshInFlight.current) {
      refreshQueued.current = true;
      return;
    }
    refreshInFlight.current = true;
    commitIfMemorySessionCurrent(guard, () => {
      setIsRefreshing(true);
      setError(null);
    });
    try {
      const database = await getCortexDb();
      assertMemorySessionGuard(guard);
      const repository = createMemoryRepository(database);
      const previous = await repository.latestMetadata(session.colaboradorId);
      assertMemorySessionGuard(guard);
      await hydrateAuthorizedMemoryCache({
        userId: session.colaboradorId,
        previousMetadata: previous,
        fetchPage: fetchMemoryPage,
        putPage: repository.putPage,
        markComplete: repository.markComplete,
        resetAuthorizedCache: repository.resetAuthorizedCache,
        onCacheReset: () => {
          if (!mounted.current) return;
          commitIfMemorySessionCurrent(guard, () => {
            setItems([]);
            setTotalMatches(0);
            setMetadata(null);
            setCoverage(memoryCoverage({
              online: true,
              metadata: null,
              localStatuses: [],
            }));
          });
        },
        assertSession: () => assertMemorySessionGuard(guard),
      });
      assertMemorySessionGuard(guard);
    } catch (cause: unknown) {
      if (mounted.current) {
        commitIfMemorySessionCurrent(guard, () => {
          setError({
            source: "SERVER",
            message: cause instanceof Error
              ? cause.message
              : "Não foi possível atualizar o registro central.",
          });
        });
      }
    } finally {
      const repeat = refreshQueued.current;
      refreshQueued.current = false;
      refreshInFlight.current = false;
      if (mounted.current) {
        commitIfMemorySessionCurrent(guard, () => setIsRefreshing(false));
      }
      await readLedgerRef.current();
      if (repeat && mounted.current) {
        queueMicrotask(() => {
          window.dispatchEvent(new Event(MEMORY_REFRESH_REQUEST_EVENT));
        });
      }
    }
  }, []);

  useEffect(() => {
    mounted.current = true;
    queueMicrotask(() => {
      if (mounted.current && navigator.onLine && hasOnlineSession()) {
        void synchronizeCache();
      }
    });
    return () => {
      mounted.current = false;
    };
  }, [synchronizeCache]);

  useEffect(() => {
    queueMicrotask(() => {
      if (mounted.current) void readLedger();
    });
  }, [readLedger]);

  useEffect(() => {
    const localRefresh = () => void readLedger();
    const onlineRefresh = () => void synchronizeCache();
    const authorizationRefresh = () => {
      setItems([]);
      setTotalMatches(0);
      setMetadata(null);
      setError(null);
      setIsLoading(true);
      setIsRefreshing(false);
      setCoverage(memoryCoverage({
        online: navigator.onLine && hasOnlineSession(),
        metadata: null,
        localStatuses: [],
      }));
      void synchronizeCache();
    };
    window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, localRefresh);
    window.addEventListener("offline", localRefresh);
    window.addEventListener("online", onlineRefresh);
    window.addEventListener(AUTH_SESSION_CHANGED_EVENT, authorizationRefresh);
    window.addEventListener(MEMORY_REFRESH_REQUEST_EVENT, onlineRefresh);
    const localInterval = window.setInterval(localRefresh, 5_000);
    const serverInterval = window.setInterval(() => {
      if (document.visibilityState === "visible") onlineRefresh();
    }, 30_000);
    return () => {
      window.removeEventListener(LOCAL_MUTATION_QUEUED_EVENT, localRefresh);
      window.removeEventListener("offline", localRefresh);
      window.removeEventListener("online", onlineRefresh);
      window.removeEventListener(AUTH_SESSION_CHANGED_EVENT, authorizationRefresh);
      window.removeEventListener(MEMORY_REFRESH_REQUEST_EVENT, onlineRefresh);
      window.clearInterval(localInterval);
      window.clearInterval(serverInterval);
    };
  }, [readLedger, synchronizeCache]);

  return {
    items,
    totalMatches,
    hasMoreLocal: items.length < totalMatches,
    filters,
    coverage,
    metadata,
    isLoading,
    isRefreshing,
    error,
    setFilters(patch) {
      setVisibleLimit(PAGE_SIZE);
      setFilterState((current) => ({ ...current, ...patch }));
    },
    clearFilters() {
      setVisibleLimit(PAGE_SIZE);
      setFilterState({});
    },
    loadMore() {
      setVisibleLimit((current) => current + PAGE_SIZE);
    },
    refresh() {
      void synchronizeCache();
    },
  };
}

function localScopeMarker(session: NonNullable<ReturnType<typeof getSession>>): string {
  return session.escopoGlobal ? "LOCAL:ALFA" : `LOCAL:BETA:${session.obraIds.join(",")}`;
}

function assertStableSnapshot(first: MemoryPage, next: MemoryPage): void {
  if (first.scopeHash !== next.scopeHash) {
    throw new Error("O escopo autorizado mudou durante a atualização da Memória.");
  }
  if (first.highWaterMark !== next.highWaterMark) {
    throw new Error("A marca d’água mudou durante a paginação da Memória.");
  }
  const left = first.coverage;
  const right = next.coverage;
  if (
    left.mode !== right.mode ||
    left.complete !== right.complete ||
    left.authorizedEventCount !== right.authorizedEventCount ||
    left.oldestCommitSequence !== right.oldestCommitSequence ||
    left.newestCommitSequence !== right.newestCommitSequence
  ) {
    throw new Error("A cobertura mudou durante a paginação da Memória.");
  }
}

function canReuseCache(
  previous: MemoryCacheMetadata | null,
  page: MemoryPage,
): previous is MemoryCacheMetadata {
  return previous?.complete === true &&
    previous.scopeHash === page.scopeHash &&
    previous.cachedEventCount === previous.authorizedEventCount &&
    previous.highWaterMark === page.highWaterMark &&
    previous.authorizedEventCount === page.coverage.authorizedEventCount &&
    previous.oldestCommitSequence === page.coverage.oldestCommitSequence &&
    previous.newestCommitSequence === page.coverage.newestCommitSequence &&
    previous.coverageMode === page.coverage.mode &&
    previous.serverCoverageComplete === page.coverage.complete;
}
