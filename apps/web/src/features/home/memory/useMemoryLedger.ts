import { useCallback, useEffect, useMemo, useState } from "react";

import { getSession, hasOnlineSession } from "../../auth/authSession";
import { listOperationalEvents } from "../../../lib/db/operationalEventRepository";
import type { OperationalEventRecord } from "../../../lib/db/db.types";
import { fetchMemoryPage } from "./memoryApi";
import type { MemoryEvent, MemoryFilters } from "./memory.types";
import {
  filterMemoryEvents,
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
  const [nextCursor, setNextCursor] = useState<number | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isInitialLoading, setIsInitialLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [serverAvailable, setServerAvailable] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);

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
      }
    });

    void listOperationalEvents()
      .then((events) => {
        if (!cancelled) {
          setLocalEvents(events);
          setIsInitialLoading(false);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setLocalEvents([]);
          setIsInitialLoading(false);
          setError("Não foi possível ler os registros deste dispositivo.");
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
    error,
    isInitialLoading,
    isLoadingMore,
  };
}
