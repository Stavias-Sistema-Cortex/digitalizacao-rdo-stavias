import {
  useMemo,
  useState,
} from "react";

import {
  useSyncStatus,
  type SyncUiStatus,
} from "../lib/sync/useSyncStatus";
import { syncNow } from "../lib/sync/syncEngine";
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
): StatusContent {
  const localChangesCount =
    pendingCount +
    syncingCount +
    errorCount +
    conflictCount;

  switch (status) {
    case "OFFLINE":
      return {
        title: "Sem conexão",
        description:
          localChangesCount > 0
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

  const content = useMemo(
    () =>
      getStatusContent(
        snapshot.status,
        snapshot.pendingCount,
        snapshot.syncingCount,
        snapshot.errorCount,
        snapshot.conflictCount,
      ),
    [
      snapshot.status,
      snapshot.pendingCount,
      snapshot.syncingCount,
      snapshot.errorCount,
      snapshot.conflictCount,
    ],
  );

  const lastSyncText = formatLastSync(
    snapshot.lastSyncCompletedAt,
  );

  const visibleSyncError =
    manualSyncError ||
    (snapshot.status === "ERROR"
      ? snapshot.lastSyncError
      : null);

  async function handleSyncNow(): Promise<void> {
    setIsManualSyncing(true);
    setManualSyncError("");

    try {
      await syncNow();
    } catch (error: unknown) {
      setManualSyncError(
        error instanceof Error
          ? error.message
          : "Falha ao sincronizar agora.",
      );
    } finally {
      setIsManualSyncing(false);
      await refresh();
    }
  }

  return (
    <aside
      className={`sync-banner sync-banner--${snapshot.status.toLowerCase()}`}
      role="status"
      aria-live="polite"
    >
      <div className="sync-banner__indicator">
        <span
          className="sync-banner__dot"
          aria-hidden="true"
        />

        <div>
          <strong>
            {snapshot.isLoading
              ? "Verificando sincronização"
              : content.title}
          </strong>

          <p>
            {snapshot.isLoading
              ? "Consultando o estado local."
              : content.description}
          </p>
        </div>
      </div>

      <div className="sync-banner__metadata">
        <span>
          {lastSyncText
            ? `Última sincronização: ${lastSyncText}`
            : "Ainda não sincronizado"}
        </span>

        {visibleSyncError && (
          <span className="sync-banner__error">
            {visibleSyncError}
          </span>
        )}

        <button
          type="button"
          onClick={() => {
            void handleSyncNow();
          }}
          disabled={isManualSyncing}
        >
          {isManualSyncing
            ? "Sincronizando"
            : "Sincronizar agora"}
        </button>
      </div>
    </aside>
  );
}
