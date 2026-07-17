import type { SyncStatusSnapshot } from "../../lib/sync/useSyncStatus";

import {
  InstitutionalStatus,
  type InstitutionalStatusState,
} from "./InstitutionalStatus";
import "./institutional.css";

export interface SyncStateStripProps {
  snapshot: SyncStatusSnapshot;
  className?: string;
}

function institutionalStateFromSyncStatus(
  status: SyncStatusSnapshot["status"],
): InstitutionalStatusState {
  switch (status) {
    case "OFFLINE":
      return "LOCAL";
    case "PENDING":
      return "PENDING";
    case "SYNCING":
      return "SYNCING";
    case "SYNCED":
      return "SYNCED";
    case "CONFLICT":
      return "CONFLICT";
    case "ERROR":
      return "REJECTED";
  }
}

function formatLastSync(
  completedAt: string | null,
): string | null {
  if (!completedAt) {
    return null;
  }

  const date = new Date(completedAt);

  if (Number.isNaN(date.getTime())) {
    return null;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

export function SyncStateStrip({
  snapshot,
  className,
}: SyncStateStripProps) {
  const classNames = [
    "institutional-sync-state",
    "institutional-frame",
    className,
  ]
    .filter(Boolean)
    .join(" ");
  const queueCount =
    snapshot.pendingCount + snapshot.syncingCount;
  const lastSync = formatLastSync(
    snapshot.lastSyncCompletedAt,
  );

  return (
    <section
      aria-label="Estado de sincronização"
      className={classNames}
    >
      <InstitutionalStatus
        state={institutionalStateFromSyncStatus(snapshot.status)}
      />
      <dl className="institutional-sync-state__facts">
        <div>
          <dt>Fila local</dt>
          <dd className="tabular-nums">{queueCount}</dd>
        </div>
        <div>
          <dt>Conflitos</dt>
          <dd className="tabular-nums">
            {snapshot.conflictCount}
          </dd>
        </div>
        <div className="institutional-sync-state__last-sync">
          <dt>Última sincronização</dt>
          <dd>
            {lastSync && snapshot.lastSyncCompletedAt ? (
              <time dateTime={snapshot.lastSyncCompletedAt}>
                {lastSync}
              </time>
            ) : (
              "Não registrada"
            )}
          </dd>
        </div>
      </dl>
    </section>
  );
}
