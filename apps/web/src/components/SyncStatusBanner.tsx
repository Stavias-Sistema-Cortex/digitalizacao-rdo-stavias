import {
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";

import {
  useSyncStatus,
  type SyncUiStatus,
} from "../lib/sync/useSyncStatus";
import { syncNow } from "../lib/sync/syncEngine";
import { SyncStateFacts } from "./institutional/SyncStateStrip";
import {
  shouldClearManualSyncPresentationError,
  type ManualSyncPresentationError,
} from "./syncPresentation";
import "./SyncStatusBanner.css";

interface StatusContent {
  title: string;
  description: string;
}

type SyncChipStatus =
  | Exclude<SyncUiStatus, "REVIEW">
  | "CHECKING";

function pluralize(
  count: number,
  singular: string,
  plural: string,
): string {
  return `${count} ${count === 1 ? singular : plural}`;
}

function getStatusContent(
  status: SyncUiStatus,
  pendingCount: number,
  syncingCount: number,
  errorCount: number,
  conflictCount: number,
  reviewCount: number,
): StatusContent {
  const localChangesCount =
    pendingCount +
    syncingCount +
    errorCount +
    conflictCount +
    reviewCount;

  switch (status) {
    case "OFFLINE":
      return {
        title: "Sem conexão",
        description:
          reviewCount > 0
            ? `${pluralize(
                reviewCount,
                "registro exige",
                "registros exigem",
              )} revisão neste dispositivo. Esses itens não serão reenviados automaticamente.`
            : localChangesCount > 0
            ? `${pluralize(
                localChangesCount,
                "alteração permanece",
                "alterações permanecem",
              )} salva${
                localChangesCount === 1 ? "" : "s"
              } neste dispositivo e será${
                localChangesCount === 1 ? "" : "ão"
              } sincronizada${
                localChangesCount === 1 ? "" : "s"
              } quando a conexão retornar.`
            : "Os dados continuam disponíveis e podem ser salvos neste dispositivo.",
      };

    case "PENDING":
      return {
        title: "Aguardando sincronização",
        description: `${pluralize(
          pendingCount,
          "alteração está pendente",
          "alterações estão pendentes",
        )}. O sistema tentará sincronizar automaticamente.`,
      };

    case "SYNCING":
      return {
        title: "Sincronizando",
        description:
          "Enviando alterações locais e buscando atualizações do servidor.",
      };

    case "ERROR":
      if (errorCount === 0) {
        return {
          title: "Falha na sincronização",
          description:
            "Não foi possível confirmar a sincronização com o servidor. Os dados locais continuam salvos neste dispositivo.",
        };
      }

      return {
        title: "Falha na sincronização",
        description: `${pluralize(
          errorCount,
          "alteração não pôde ser sincronizada",
          "alterações não puderam ser sincronizadas",
        )}. Os dados continuam salvos neste dispositivo.`,
      };

    case "CONFLICT":
      return {
        title: "Conflito de versão",
        description: `${pluralize(
          conflictCount,
          "RDO possui",
          "RDOs possuem",
        )} uma versão mais recente no servidor. ${
          conflictCount === 1
            ? "Esse registro está temporariamente bloqueado"
            : "Esses registros estão temporariamente bloqueados"
        } para edição.`,
      };

    case "REVIEW":
      return {
        title: "Revisão necessária",
        description: `${pluralize(
          reviewCount,
          "registro exige",
          "registros exigem",
        )} revisão antes de qualquer novo envio.`,
      };

    case "SYNCED":
      return {
        title: "Sincronizado",
        description:
          "As alterações locais estão alinhadas com o servidor.",
      };
  }
}

export function SyncStatusBanner() {
  const { snapshot, refresh } =
    useSyncStatus();
  const [manualSyncError, setManualSyncError] =
    useState<ManualSyncPresentationError | null>(null);
  const [isManualSyncing, setIsManualSyncing] =
    useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  const displayedStatus: SyncUiStatus =
    manualSyncError ? "ERROR" : snapshot.status;
  const chipStatus: SyncChipStatus = manualSyncError
    ? "ERROR"
    : snapshot.isLoading
      ? "CHECKING"
      : snapshot.status === "REVIEW"
        ? "CONFLICT"
        : snapshot.status;

  const content = useMemo(
    () =>
      getStatusContent(
        displayedStatus,
        snapshot.pendingCount,
        snapshot.syncingCount,
        snapshot.errorCount,
        snapshot.conflictCount,
        snapshot.reviewCount,
      ),
    [
      displayedStatus,
      snapshot.pendingCount,
      snapshot.syncingCount,
      snapshot.errorCount,
      snapshot.conflictCount,
      snapshot.reviewCount,
    ],
  );

  const visibleSyncDetail =
    manualSyncError?.message ??
    (displayedStatus === "REVIEW"
      ? snapshot.reviewReason
      : displayedStatus === "ERROR" || displayedStatus === "CONFLICT"
        ? snapshot.lastSyncError
        : null);

  const attentionCount =
    snapshot.pendingCount +
    snapshot.errorCount +
    snapshot.conflictCount +
    snapshot.reviewCount;

  useEffect(() => {
    if (
      !manualSyncError ||
      !shouldClearManualSyncPresentationError(
        manualSyncError,
        snapshot,
      )
    ) {
      return undefined;
    }

    const clearId = window.setTimeout(() => {
      setManualSyncError((current) =>
        current === manualSyncError ? null : current,
      );
    }, 0);

    return () => window.clearTimeout(clearId);
  }, [manualSyncError, snapshot]);

  // Fecha o popover ao clicar fora ou pressionar Escape.
  useEffect(() => {
    if (!isOpen) {
      return undefined;
    }

    function handlePointerDown(event: PointerEvent) {
      if (
        rootRef.current &&
        event.target instanceof Node &&
        !rootRef.current.contains(event.target)
      ) {
        setIsOpen(false);
      }
    }

    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        setIsOpen(false);
      }
    }

    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);

    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [isOpen]);

  async function handleSyncNow(): Promise<void> {
    setIsManualSyncing(true);

    try {
      await syncNow();
    } catch (error: unknown) {
      setManualSyncError(
        {
          message: error instanceof Error
            ? error.message
            : "Falha ao sincronizar agora.",
          occurredAt: new Date().toISOString(),
        },
      );
    } finally {
      setIsManualSyncing(false);
      await refresh();
    }
  }

  const chipTitle = snapshot.isLoading && !manualSyncError
    ? "Verificando sincronização"
    : content.title;

  return (
    <div
      ref={rootRef}
      className="sync-status-control"
    >
      <span
        className={`sync-status-summary sync-status-summary--${chipStatus.toLowerCase()}`}
        aria-hidden="true"
      >
        {chipTitle}
      </span>
      <div
        className={`sync-chip sync-chip--${chipStatus.toLowerCase()}`}
      >
        <button
          type="button"
          className="sync-chip__button"
          onClick={() => setIsOpen((open) => !open)}
          aria-expanded={isOpen}
          aria-haspopup="dialog"
          aria-label={`Sincronização: ${chipTitle}`}
          title={chipTitle}
        >
          <svg
            className="sync-chip__icon"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <path d="M21 12a9 9 0 0 1-15.3 6.4L3 16" />
            <path d="M3 12a9 9 0 0 1 15.3-6.4L21 8" />
            <path d="M3 21v-5h5" />
            <path d="M21 3v5h-5" />
          </svg>
          <span className="sync-chip__dot" aria-hidden="true" />
          {attentionCount > 0 ? (
            <span className="sync-chip__count" aria-hidden="true">
              {attentionCount > 9 ? "9+" : attentionCount}
            </span>
          ) : null}
        </button>

        <span
          className="visually-hidden"
          role="status"
          aria-live="polite"
        >
          {chipTitle}
        </span>

        {isOpen ? (
          <div
            className="sync-chip__popover"
            role="dialog"
            aria-label="Estado da sincronização"
          >
            <div className="sync-chip__header">
              <span
                className="sync-chip__header-dot"
                aria-hidden="true"
              />
              <strong>{chipTitle}</strong>
            </div>

            <p className="sync-chip__description">
              {snapshot.isLoading
                ? "Consultando o estado local."
                : content.description}
            </p>

            <SyncStateFacts
              snapshot={snapshot}
              className="sync-chip__facts"
            />

            {visibleSyncDetail && (
              <p className="sync-chip__error">
                {visibleSyncDetail}
              </p>
            )}

            <button
              type="button"
              className="sync-chip__action"
              onClick={() => {
                void handleSyncNow();
              }}
              disabled={isManualSyncing}
            >
              {isManualSyncing
                ? "Sincronizando..."
                : "Sincronizar agora"}
            </button>
          </div>
        ) : null}
      </div>
    </div>
  );
}
