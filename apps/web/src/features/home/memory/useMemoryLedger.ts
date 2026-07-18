import { useCallback, useEffect, useMemo, useState } from "react";

import { getSession, hasOnlineSession } from "../../auth/authSession";
import { listOperationalEvents } from "../../../lib/db/operationalEventRepository";
import { listOutboxMutations } from "../../../lib/db/outboxRepository";
import type {
  OperationalEventRecord,
  OutboxMutationRecord,
} from "../../../lib/db/db.types";
import { fetchMemoryPage } from "./memoryApi";
import type {
  MemoryConflictReviewRecord,
  MemoryEvent,
  MemoryFilters,
} from "./memory.types";
import {
  filterMemoryEvents,
  memoryConflictReviewRecords,
  mergeMemoryEvents,
} from "./memoryViewModel";

export interface MemoryCoverage {
  mode: "FULL" | "DEVICE_ONLY" | "PARTIAL";
  label: string;
  detail: string;
}

export interface MemoryLedgerState {
  events: MemoryEvent[];
  hasMore: boolean;
  loadMore: () => Promise<void>;
  reload: () => void;
  coverage: MemoryCoverage;
  reviewRecords: MemoryConflictReviewRecord[];
  isReviewLoading: boolean;
  reviewError: string | null;
  error: string | null;
  isInitialLoading: boolean;
  isLoadingMore: boolean;
}

export function useMemoryLedger(
  filters: MemoryFilters,
): MemoryLedgerState {
  const session = getSession();
  const userId = session?.colaboradorId ?? "";
  const allowedObraIds = useMemo<readonly string[] | null>(
    () => session?.escopoGlobal ? null : session?.obraIds ?? [],
    [session?.escopoGlobal, session?.obraIds],
  );
  const filtersKey = JSON.stringify(filters);
  const [serverEvents, setServerEvents] = useState<MemoryEvent[]>([]);
  const [localEvents, setLocalEvents] = useState<OperationalEventRecord[]>([]);
  const [outboxMutations, setOutboxMutations] = useState<
    OutboxMutationRecord[]
  >([]);
  const [isOutboxLoading, setIsOutboxLoading] = useState(true);
  const [localReviewError, setLocalReviewError] = useState<string | null>(null);
  const [outboxReviewError, setOutboxReviewError] = useState<string | null>(null);
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isInitialLoading, setIsInitialLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [serverAvailable, setServerAvailable] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);

  const isReviewLoading = isInitialLoading || isOutboxLoading;
  const reviewError = localReviewError ?? outboxReviewError;
  const canReachServer = navigator.onLine && hasOnlineSession();

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) {
        setServerEvents([]);
        setNextCursor(null);
        setHasMore(false);
        setError(null);
        setServerAvailable(false);
        setIsInitialLoading(true);
        setIsOutboxLoading(true);
        setLocalReviewError(null);
        setOutboxReviewError(null);
      }
    });

    void listOperationalEvents()
      .then((events) => {
        if (!cancelled) {
          setLocalEvents(events);
          setIsInitialLoading(false);
          setLocalReviewError(null);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLocalEvents([]);
          setIsInitialLoading(false);
          setLocalReviewError("A leitura dos registros locais falhou.");
          setError("Não foi possível ler os registros deste dispositivo.");
        }
      });

    void listOutboxMutations()
      .then((mutations) => {
        if (!cancelled) {
          setOutboxMutations(mutations);
          setIsOutboxLoading(false);
          setOutboxReviewError(null);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setOutboxMutations([]);
          setIsOutboxLoading(false);
          setOutboxReviewError("A leitura da fila local falhou.");
        }
      });

    if (canReachServer) {
      void fetchMemoryPage(filters)
        .then((page) => {
          if (!cancelled) {
            setServerEvents(page.events);
            setNextCursor(page.nextBeforeCommitSeq);
            setHasMore(page.hasMore);
            setServerAvailable(true);
          }
        })
        .catch((fetchError: unknown) => {
          if (!cancelled) {
            setError(fetchError instanceof Error
              ? fetchError.message
              : "O registro confirmado não pôde ser consultado.");
          }
        });
    }

    return () => {
      cancelled = true;
    };
    // filtersKey representa o recorte estrutural e reinicia a paginação.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [filtersKey, reloadTick, canReachServer]);

  const events = useMemo(
    () => filterMemoryEvents(
      mergeMemoryEvents(
        serverEvents,
        localEvents,
        allowedObraIds,
        userId,
      ),
      filters,
    ),
    [serverEvents, localEvents, allowedObraIds, userId, filters],
  );
  const reviewRecords = useMemo(() => {
    const visibleEventIds = new Set(events.map((event) => event.id));

    return memoryConflictReviewRecords(
      localEvents,
      outboxMutations,
    ).filter((record) => visibleEventIds.has(record.eventId));
  }, [events, localEvents, outboxMutations]);

  const coverage = useMemo<MemoryCoverage>(() => {
    if (!canReachServer) {
      return {
        mode: "DEVICE_ONLY",
        label: "Somente este dispositivo",
        detail: "Sem conexão autenticada; a lista não representa o histórico global.",
      };
    }
    if (!serverAvailable) {
      return {
        mode: "PARTIAL",
        label: "Cobertura parcial",
        detail: "Os registros locais estão visíveis, mas o servidor ainda não confirmou o recorte.",
      };
    }
    return {
      mode: "FULL",
      label: "Servidor + dispositivo",
      detail: "Commits confirmados e alterações locais pendentes no escopo autorizado.",
    };
  }, [canReachServer, serverAvailable]);

  const loadMore = useCallback(async () => {
    if (!nextCursor || !canReachServer || isLoadingMore) {
      return;
    }
    setIsLoadingMore(true);
    try {
      const page = await fetchMemoryPage(filters, nextCursor);
      setServerEvents((current) => {
        const ids = new Set(current.map((event) => event.id));
        return [...current, ...page.events.filter((event) => !ids.has(event.id))];
      });
      setNextCursor(page.nextBeforeCommitSeq);
      setHasMore(page.hasMore);
      setServerAvailable(true);
    } catch (loadError: unknown) {
      setError(loadError instanceof Error
        ? loadError.message
        : "Não foi possível carregar registros anteriores.");
    } finally {
      setIsLoadingMore(false);
    }
  }, [canReachServer, filters, isLoadingMore, nextCursor]);

  const reload = useCallback(() => {
    setReloadTick((tick) => tick + 1);
  }, []);

  return {
    events,
    hasMore,
    loadMore,
    reload,
    coverage,
    reviewRecords,
    isReviewLoading,
    reviewError,
    error,
    isInitialLoading,
    isLoadingMore,
  };
}
