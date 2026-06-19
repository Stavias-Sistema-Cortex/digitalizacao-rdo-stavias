import {
  useMemo,
} from "react";

import {
  useSyncStatus,
  type SyncUiStatus,
} from "../lib/sync/useSyncStatus";
import "./SyncStatusBanner.css";

interface StatusContent {
  title: string;
  description: string;
}

function getStatusContent(
  status: SyncUiStatus,
  pendingCount: number,
  errorCount: number,
  conflictCount: number,
): StatusContent {
  switch (status) {
    case "OFFLINE":
      return {
        title: "Offline",
        description:
          pendingCount > 0
            ? `${pendingCount} alteração(ões) salva(s) no dispositivo aguardando conexão.`
            : "Os dados continuam disponíveis e podem ser salvos neste dispositivo.",
      };

    case "PENDING":
      return {
        title: "Aguardando sincronização",
        description: `${pendingCount} alteração(ões) pendente(s). O sistema tentará novamente automaticamente.`,
      };

    case "SYNCING":
      return {
        title: "Sincronizando",
        description:
          "Enviando alterações locais e buscando atualizações do servidor.",
      };

    case "ERROR":
      return {
        title: "Dados precisam de correção",
        description: `${errorCount} operação(ões) foram rejeitadas pelo servidor.`,
      };

    case "CONFLICT":
      return {
        title: "Conflito de versão",
        description: `${conflictCount} operação(ões) possuem uma versão mais recente no servidor.`,
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

  const content = useMemo(
    () =>
      getStatusContent(
        snapshot.status,
        snapshot.pendingCount,
        snapshot.errorCount,
        snapshot.conflictCount,
      ),
    [
      snapshot.status,
      snapshot.pendingCount,
      snapshot.errorCount,
      snapshot.conflictCount,
    ],
  );

  const lastSyncText = formatLastSync(
    snapshot.lastSyncCompletedAt,
  );

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
        {lastSyncText && (
          <span>
            Última sincronização:{" "}
            {lastSyncText}
          </span>
        )}

        <button
          type="button"
          onClick={() => {
            void refresh();
          }}
        >
          Atualizar status
        </button>
      </div>
    </aside>
  );
}
