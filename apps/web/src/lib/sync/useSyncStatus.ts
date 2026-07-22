import {
  useCallback,
  useEffect,
  useState,
} from "react";

import { getSyncState } from "../db/syncStateRepository";
import { listOutboxMutations } from "../db/outboxRepository";
import type { OutboxMutationRecord } from "../db/db.types";

export type SyncUiStatus =
  | "OFFLINE"
  | "PENDING"
  | "SYNCING"
  | "SYNCED"
  | "REVIEW"
  | "ERROR"
  | "CONFLICT";

export interface SyncStatusSnapshot {
  status: SyncUiStatus;
  isOnline: boolean;
  pendingCount: number;
  syncingCount: number;
  errorCount: number;
  conflictCount: number;
  reviewCount: number;
  reviewReason: string | null;
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
  reviewCount: 0,
  reviewReason: null,
  lastSyncCompletedAt: null,
  lastSyncError: null,
  isLoading: true,
};

export function determineSyncUiStatus(input: {
  isOnline: boolean;
  isSyncing: boolean;
  pendingCount: number;
  syncingCount: number;
  errorCount: number;
  conflictCount: number;
  reviewCount: number;
  lastSyncError: string | null;
}): SyncUiStatus {
  if (!input.isOnline) {
    return "OFFLINE";
  }

  if (input.conflictCount > 0) {
    return "CONFLICT";
  }

  if (input.reviewCount > 0) {
    return "REVIEW";
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

  if (input.lastSyncError) {
    return "ERROR";
  }

  if (input.pendingCount > 0) {
    return "PENDING";
  }

  return "SYNCED";
}

function firstMutationSyncProblem(
  mutations: OutboxMutationRecord[],
): string | null {
  const problem = mutations
    .filter(
      (mutation) =>
        mutation.status === "ERROR" ||
        mutation.status === "CONFLICT",
    )
    .sort((left, right) =>
      right.updatedAt.localeCompare(left.updatedAt),
    )[0];

  if (!problem?.ultimoErro) {
    return null;
  }

  return `Motivo: ${problem.ultimoErro}`;
}

function firstMutationReviewReason(
  mutations: OutboxMutationRecord[],
): string | null {
  const review = mutations
    .filter((mutation) => mutation.status === "REJECTED")
    .sort((left, right) =>
      right.updatedAt.localeCompare(left.updatedAt),
    )[0];

  return review?.blockedReason ?? review?.ultimoErro ?? null;
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

      const reviewCount =
        mutations.filter(
          (mutation) =>
            mutation.status === "REJECTED",
        ).length;

      const isOnline = navigator.onLine;
      const localSyncProblem =
        firstMutationSyncProblem(mutations);
      const reviewReason =
        firstMutationReviewReason(mutations);
      const lastSyncError =
        syncState.lastSyncError ??
        localSyncProblem;

      setSnapshot({
        status: determineSyncUiStatus({
          isOnline,
          isSyncing: syncState.isSyncing,
          pendingCount,
          syncingCount,
          errorCount,
          conflictCount,
          reviewCount,
          lastSyncError,
        }),
        isOnline,
        pendingCount,
        syncingCount,
        errorCount,
        conflictCount,
        reviewCount,
        reviewReason,
        lastSyncCompletedAt:
          syncState.lastSyncCompletedAt,
        lastSyncError,
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
      5_000,
    );

    const initialRefreshId =
      window.setTimeout(() => {
        void refresh();
      }, 0);

    return () => {
      window.clearTimeout(initialRefreshId);
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
