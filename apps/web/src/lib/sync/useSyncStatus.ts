import {
  useCallback,
  useEffect,
  useState,
} from "react";

import { getSyncState } from "../db/syncStateRepository";
import { listOutboxMutations } from "../db/outboxRepository";

export type SyncUiStatus =
  | "OFFLINE"
  | "PENDING"
  | "SYNCING"
  | "SYNCED"
  | "ERROR"
  | "CONFLICT";

export interface SyncStatusSnapshot {
  status: SyncUiStatus;
  isOnline: boolean;
  pendingCount: number;
  syncingCount: number;
  errorCount: number;
  conflictCount: number;
  lastSyncCompletedAt: string | null;
  lastSyncError: string | null;
  isLoading: boolean;
}

const INITIAL_STATUS: SyncStatusSnapshot = {
  status: navigator.onLine
    ? "SYNCED"
    : "OFFLINE",
  isOnline: navigator.onLine,
  pendingCount: 0,
  syncingCount: 0,
  errorCount: 0,
  conflictCount: 0,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  isLoading: true,
};

function determineStatus(input: {
  isOnline: boolean;
  isSyncing: boolean;
  pendingCount: number;
  syncingCount: number;
  errorCount: number;
  conflictCount: number;
}): SyncUiStatus {
  if (!input.isOnline) {
    return "OFFLINE";
  }

  if (input.conflictCount > 0) {
    return "CONFLICT";
  }

  if (input.errorCount > 0) {
    return "ERROR";
  }

  if (
    input.isSyncing ||
    input.syncingCount > 0
  ) {
    return "SYNCING";
  }

  if (input.pendingCount > 0) {
    return "PENDING";
  }

  return "SYNCED";
}

export function useSyncStatus(): {
  snapshot: SyncStatusSnapshot;
  refresh: () => Promise<void>;
} {
  const [snapshot, setSnapshot] =
    useState<SyncStatusSnapshot>(
      INITIAL_STATUS,
    );

  const refresh = useCallback(async () => {
    try {
      const [syncState, mutations] =
        await Promise.all([
          getSyncState(),
          listOutboxMutations(),
        ]);

      const pendingCount =
        mutations.filter(
          (mutation) =>
            mutation.status === "PENDING",
        ).length;

      const syncingCount =
        mutations.filter(
          (mutation) =>
            mutation.status === "SYNCING",
        ).length;

      const errorCount =
        mutations.filter(
          (mutation) =>
            mutation.status === "ERROR",
        ).length;

      const conflictCount =
        mutations.filter(
          (mutation) =>
            mutation.status === "CONFLICT",
        ).length;

      const isOnline = navigator.onLine;

      setSnapshot({
        status: determineStatus({
          isOnline,
          isSyncing: syncState.isSyncing,
          pendingCount,
          syncingCount,
          errorCount,
          conflictCount,
        }),
        isOnline,
        pendingCount,
        syncingCount,
        errorCount,
        conflictCount,
        lastSyncCompletedAt:
          syncState.lastSyncCompletedAt,
        lastSyncError:
          syncState.lastSyncError,
        isLoading: false,
      });
    } catch (error: unknown) {
      const message =
        error instanceof Error
          ? error.message
          : "Falha ao consultar o estado da sincronização.";

      setSnapshot((current) => ({
        ...current,
        status: navigator.onLine
          ? "ERROR"
          : "OFFLINE",
        isOnline: navigator.onLine,
        lastSyncError: message,
        isLoading: false,
      }));
    }
  }, []);

  useEffect(() => {
    function handleConnectionChange(): void {
      void refresh();
    }

    function handleVisibilityChange(): void {
      if (
        document.visibilityState === "visible"
      ) {
        void refresh();
      }
    }

    window.addEventListener(
      "online",
      handleConnectionChange,
    );

    window.addEventListener(
      "offline",
      handleConnectionChange,
    );

    document.addEventListener(
      "visibilitychange",
      handleVisibilityChange,
    );

    const intervalId = window.setInterval(
      () => {
        void refresh();
      },
      1_000,
    );

    void refresh();

    return () => {
      window.removeEventListener(
        "online",
        handleConnectionChange,
      );

      window.removeEventListener(
        "offline",
        handleConnectionChange,
      );

      document.removeEventListener(
        "visibilitychange",
        handleVisibilityChange,
      );

      window.clearInterval(intervalId);
    };
  }, [refresh]);

  return {
    snapshot,
    refresh,
  };
}
