import {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";

import { getSession, isAlfa } from "../auth/authSession";
import {
  listObrasLocais,
} from "../../lib/db/obraLocalRepository";
import {
  listSnapshotsByObra,
} from "../../lib/db/previsaoSnapshotRepository";
import {
  listOperationalEventsForObra,
} from "../../lib/db/operationalEventRepository";
import { listLocalRdos } from "../../lib/db/rdoRepository";
import type {
  LocalRdoRecord,
  ObraLocalRecord,
  OperationalEventRecord,
  PrevisaoSnapshotRecord,
} from "../../lib/db/db.types";
import {
  hydrateHistoricoObra,
  hydrateObrasArquivadas,
  hydrateObrasRelacionadas,
} from "./homeHydration";
import {
  colaboradorStorageKey,
  getLastAccessedObraId,
  setLastAccessedObraId,
} from "./lastAccessedObra";
import { filterOperationalObras } from "./homeFilters";
import { syncSessionFingerprint } from "../../lib/sync/syncSession";

function currentSessionFingerprint(): string | null {
  const session = getSession();
  return session ? syncSessionFingerprint(session) : null;
}

export interface HomeData {
  obras: ObraLocalRecord[];
  focusedObraId: string | null;
  focusedObra: ObraLocalRecord | null;
  setFocusedObraId: (obraId: string) => void;
  snapshots: PrevisaoSnapshotRecord[];
  events: OperationalEventRecord[];
  latestRdo: LocalRdoRecord | null;
  isLoading: boolean;
  hasConfirmedRemoteHydration: boolean;
  dataUpdatedAt: string | null;
  reload: () => void;
}

export function useHomeData(
  options: { includeArchived?: boolean } = {},
): HomeData {
  const storageKey = useMemo(
    () => colaboradorStorageKey(getSession()),
    [],
  );

  const [obras, setObras] = useState<ObraLocalRecord[]>([]);
  const [focusedObraId, setFocusedObraIdState] = useState<
    string | null
  >(() => getLastAccessedObraId(storageKey));
  const [snapshots, setSnapshots] = useState<
    PrevisaoSnapshotRecord[]
  >([]);
  const [events, setEvents] = useState<
    OperationalEventRecord[]
  >([]);
  const [latestRdo, setLatestRdo] =
    useState<LocalRdoRecord | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [
    hasConfirmedRemoteHydration,
    setHasConfirmedRemoteHydration,
  ] = useState(false);
  const [reloadTick, setReloadTick] = useState(0);

  const reload = useCallback(() => {
    setReloadTick((tick) => tick + 1);
  }, []);

  const setFocusedObraId = useCallback(
    (obraId: string) => {
      setFocusedObraIdState(obraId);
      setLastAccessedObraId(storageKey, obraId);
    },
    [storageKey],
  );

  useEffect(() => {
    let cancelled = false;

    async function loadObras() {
      setIsLoading(true);
      setHasConfirmedRemoteHydration(false);
      const relatedSessionFingerprint =
        currentSessionFingerprint();
      let relatedHydrationSucceeded = false;

      try {
        await hydrateObrasRelacionadas();
        if (cancelled) return;
        relatedHydrationSucceeded = true;
      } catch {
        if (cancelled) return;
        // Offline ou API indisponível: segue com o banco local.
      }
      if (
        currentSessionFingerprint() !==
          relatedSessionFingerprint
      ) {
        clearSessionBoundState();
        return;
      }
      const relatedHydrationConfirmed =
        relatedHydrationSucceeded;

      const shouldHydrateArchived =
        options.includeArchived === true &&
        isAlfa(getSession());
      let archivedHydrationConfirmed =
        !shouldHydrateArchived;
      if (shouldHydrateArchived) {
        const archivedSessionFingerprint =
          currentSessionFingerprint();
        try {
          await hydrateObrasArquivadas();
          if (cancelled) return;
          if (
            currentSessionFingerprint() !==
              archivedSessionFingerprint
          ) {
            clearSessionBoundState();
            return;
          }
          archivedHydrationConfirmed = true;
        } catch {
          if (cancelled) return;
          if (
            currentSessionFingerprint() !==
              archivedSessionFingerprint
          ) {
            clearSessionBoundState();
            return;
          }
          // A Lixeira remota falhou; o cache Alfa continua disponível.
        }
      }

      const canIncludeArchived =
        options.includeArchived === true &&
        isAlfa(getSession());
      const localReadSessionFingerprint =
        currentSessionFingerprint();
      let cached: ObraLocalRecord[];
      try {
        cached = await listObrasLocais({
          includeArchived: canIncludeArchived,
        });
      } catch {
        if (cancelled) return;
        if (
          currentSessionFingerprint() !==
            localReadSessionFingerprint
        ) {
          clearSessionBoundState();
          return;
        }
        cached = [];
      }

      if (cancelled) return;
      if (
        currentSessionFingerprint() !==
          localReadSessionFingerprint
      ) {
        clearSessionBoundState();
        return;
      }

      cached.sort((a, b) =>
        a.updatedAt < b.updatedAt ? 1 : -1,
      );
      const local = canIncludeArchived
        ? cached
        : filterOperationalObras(cached);
      setObras(local);
      setHasConfirmedRemoteHydration(
        relatedHydrationConfirmed &&
          archivedHydrationConfirmed,
      );

      setFocusedObraIdState((current) => {
        if (
          current &&
          local.some((obra) => obra.id === current)
        ) {
          return current;
        }
        return local[0]?.id ?? null;
      });

      setIsLoading(false);

      function clearSessionBoundState(): void {
        if (!cancelled) {
          setObras([]);
          setFocusedObraIdState(null);
          setSnapshots([]);
          setEvents([]);
          setLatestRdo(null);
          setHasConfirmedRemoteHydration(false);
          setIsLoading(false);
        }
      }
    }

    void loadObras();

    return () => {
      cancelled = true;
    };
  }, [options.includeArchived, reloadTick]);

  useEffect(() => {
    if (!focusedObraId) {
      return;
    }

    let cancelled = false;
    const detailSessionFingerprint =
      currentSessionFingerprint();

    function detailContextChanged(): boolean {
      if (cancelled) return true;
      if (
        currentSessionFingerprint() ===
          detailSessionFingerprint
      ) {
        return false;
      }
      setObras([]);
      setFocusedObraIdState(null);
      setSnapshots([]);
      setEvents([]);
      setLatestRdo(null);
      setHasConfirmedRemoteHydration(false);
      setIsLoading(false);
      return true;
    }

    async function loadLocalDetails(
      obraId: string,
    ): Promise<boolean> {
      try {
        const [obraSnapshots, obraEvents, rdos] = await Promise.all([
          listSnapshotsByObra(obraId),
          listOperationalEventsForObra(obraId),
          listLocalRdos(),
        ]);

        if (detailContextChanged()) {
          return false;
        }

        setSnapshots(obraSnapshots);

        obraEvents.sort((a, b) =>
          a.occurredAt < b.occurredAt ? 1 : -1,
        );
        setEvents(obraEvents);

        const obraRdos = rdos
          // O último RDO da obra é o último que vale; um apagado não é.
          .filter((rdo) => rdo.obraId === obraId && !rdo.canceladoEm)
          .sort((a, b) =>
            a.dataRdo < b.dataRdo ? 1 : -1,
          );
        setLatestRdo(obraRdos[0] ?? null);
        return true;
      } catch {
        if (detailContextChanged()) {
          return false;
        }
        setSnapshots([]);
        setEvents([]);
        setLatestRdo(null);
        return false;
      }
    }

    async function loadObraDetails(obraId: string) {
      // Primeiro o que já existe localmente: a troca de obra é imediata,
      // sem exibir dados da obra anterior enquanto a rede responde.
      const loadedLocalDetails =
        await loadLocalDetails(obraId);
      if (!loadedLocalDetails) return;

      try {
        await hydrateHistoricoObra(obraId);
      } catch {
        if (detailContextChanged()) return;
        // Offline: fica com o que havia localmente.
        return;
      }
      if (detailContextChanged()) return;

      await loadLocalDetails(obraId);
    }

    void loadObraDetails(focusedObraId);

    return () => {
      cancelled = true;
    };
  }, [focusedObraId, reloadTick]);

  const focusedObra = useMemo(
    () =>
      obras.find((obra) => obra.id === focusedObraId) ??
      null,
    [obras, focusedObraId],
  );

  const dataUpdatedAt = focusedObra?.updatedAt ?? null;

  return {
    obras,
    focusedObraId,
    focusedObra,
    setFocusedObraId,
    snapshots: focusedObraId ? snapshots : [],
    events: focusedObraId ? events : [],
    latestRdo: focusedObraId ? latestRdo : null,
    isLoading,
    hasConfirmedRemoteHydration,
    dataUpdatedAt,
    reload,
  };
}
