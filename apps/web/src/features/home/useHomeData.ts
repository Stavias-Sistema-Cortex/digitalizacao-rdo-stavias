import {
  useCallback,
  useEffect,
  useMemo,
  useState,
} from "react";

import { getSession } from "../auth/authSession";
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
  hydrateObrasRelacionadas,
} from "./homeHydration";
import {
  colaboradorStorageKey,
  getLastAccessedObraId,
  setLastAccessedObraId,
} from "./lastAccessedObra";

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

export function useHomeData(): HomeData {
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
      let remoteHydrationConfirmed = false;

      try {
        await hydrateObrasRelacionadas();
        remoteHydrationConfirmed = true;
      } catch {
        // Offline ou API indisponível: segue com o banco local.
      }

      const local = await listObrasLocais();

      if (cancelled) {
        return;
      }

      local.sort((a, b) =>
        a.updatedAt < b.updatedAt ? 1 : -1,
      );
      setObras(local);
      setHasConfirmedRemoteHydration(
        remoteHydrationConfirmed,
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
    }

    void loadObras();

    return () => {
      cancelled = true;
    };
  }, [reloadTick]);

  useEffect(() => {
    if (!focusedObraId) {
      return;
    }

    let cancelled = false;

    async function loadLocalDetails(obraId: string) {
      const [obraSnapshots, obraEvents, rdos] =
        await Promise.all([
          listSnapshotsByObra(obraId),
          listOperationalEventsForObra(obraId),
          listLocalRdos(),
        ]);

      if (cancelled) {
        return;
      }

      setSnapshots(obraSnapshots);

      obraEvents.sort((a, b) =>
        a.occurredAt < b.occurredAt ? 1 : -1,
      );
      setEvents(obraEvents);

      const obraRdos = rdos
        .filter((rdo) => rdo.obraId === obraId)
        .sort((a, b) =>
          a.dataRdo < b.dataRdo ? 1 : -1,
        );
      setLatestRdo(obraRdos[0] ?? null);
    }

    async function loadObraDetails(obraId: string) {
      // Primeiro o que já existe localmente: a troca de obra é imediata,
      // sem exibir dados da obra anterior enquanto a rede responde.
      await loadLocalDetails(obraId);

      try {
        await hydrateHistoricoObra(obraId);
      } catch {
        // Offline: fica com o que havia localmente.
        return;
      }

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
