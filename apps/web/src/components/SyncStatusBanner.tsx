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
import { isSyncLeaseContentionError } from "../lib/sync/syncLeaseContention";
import { requeueMutationsInReview } from "../lib/sync/syncStorage";
import "./SyncStatusBanner.css";

interface StatusContent {
  title: string;
  description: string;
}

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

function formatLastSync(
  value: string | null,
): string | null {
  if (!value) {
    return null;
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return null;
  }

  return new Intl.DateTimeFormat(
    "pt-BR",
    {
      dateStyle: "short",
      timeStyle: "medium",
    },
  ).format(date);
}

export function SyncStatusBanner() {
  const { snapshot, refresh } =
    useSyncStatus();
  const [manualSyncError, setManualSyncError] =
    useState("");
  const [isManualSyncing, setIsManualSyncing] =
    useState(false);
  const [isOpen, setIsOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  const displayedStatus: SyncUiStatus =
    manualSyncError ? "ERROR" : snapshot.status;
  const chipVisualStatus =
    displayedStatus === "REVIEW"
      ? "CONFLICT"
      : displayedStatus;

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

  const lastSyncText = formatLastSync(
    snapshot.lastSyncCompletedAt,
  );

  const visibleSyncError =
    manualSyncError ||
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
      manualSyncError &&
      !snapshot.lastSyncError &&
      snapshot.status !== "ERROR"
    ) {
      const resetId = window.setTimeout(() => {
        setManualSyncError("");
      }, 0);

      return () => window.clearTimeout(resetId);
    }

    return undefined;
  }, [
    manualSyncError,
    snapshot.lastSyncError,
    snapshot.status,
  ]);

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
    setManualSyncError("");

    try {
      await syncNow();
    } catch (error: unknown) {
      // Outra aba já está sincronizando: o trabalho está sendo feito, e
      // anunciar falha aqui faria quem está em campo achar que o apontamento
      // não subiu. O `refresh` abaixo mostra o que a outra aba conseguiu.
      if (!isSyncLeaseContentionError(error)) {
        setManualSyncError(
          error instanceof Error
            ? error.message
            : "Falha ao sincronizar agora.",
        );
      }
    } finally {
      setIsManualSyncing(false);
      await refresh();
    }
  }

  // "Sincronizar agora" não toca em registro recusado, por definição. Sem esta
  // ação, quem chega em "revisão necessária" não tem o que fazer na tela.
  async function handleReleaseReview(): Promise<void> {
    setIsManualSyncing(true);
    setManualSyncError("");

    try {
      await requeueMutationsInReview();
      await syncNow();
    } catch (error: unknown) {
      // O reenvio já aconteceu antes do envio: se a disputa de aba interrompe
      // aqui, dizer "falha ao reenviar" nega um reenvio que de fato ocorreu, e
      // convida a repetir um gesto que já surtiu efeito.
      if (!isSyncLeaseContentionError(error)) {
        setManualSyncError(
          error instanceof Error
            ? error.message
            : "Falha ao reenviar os registros em revisão.",
        );
      }
    } finally {
      setIsManualSyncing(false);
      await refresh();
    }
  }

  const chipTitle = snapshot.isLoading
    ? "Verificando sincronização"
    : content.title;

  return (
    <div
      ref={rootRef}
      className={`sync-chip sync-chip--${chipVisualStatus.toLowerCase()}`}
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

      <span className="visually-hidden" role="status" aria-live="polite">
        {chipTitle}
      </span>

      {isOpen ? (
        <div
          className="sync-chip__popover"
          role="dialog"
          aria-label="Estado da sincronização"
        >
          <div className="sync-chip__header">
            <span className="sync-chip__header-dot" aria-hidden="true" />
            <strong>{chipTitle}</strong>
          </div>

          <p className="sync-chip__description">
            {snapshot.isLoading
              ? "Consultando o estado local."
              : content.description}
          </p>

          <p className="sync-chip__meta">
            {lastSyncText
              ? `Última sincronização: ${lastSyncText}`
              : "Ainda não sincronizado"}
          </p>

          {visibleSyncError && (
            <p className="sync-chip__error">
              {visibleSyncError}
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

          {snapshot.reviewCount > 0 ? (
            <>
              <button
                type="button"
                className="sync-chip__action sync-chip__action--secondary"
                onClick={() => {
                  void handleReleaseReview();
                }}
                disabled={isManualSyncing}
              >
                {`Reenviar ${pluralize(
                  snapshot.reviewCount,
                  "registro em revisão",
                  "registros em revisão",
                )}`}
              </button>
              <p className="sync-chip__hint">
                Nada se perde: o que não subir volta para revisão.
              </p>
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}
