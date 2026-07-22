import type { SyncStatusSnapshot } from "../../lib/sync/useSyncStatus";

import {
  InstitutionalStatus,
  type InstitutionalStatusState,
} from "./InstitutionalStatus";
import "./institutional.css";

export interface SyncStateStripProps {
  snapshot: SyncStatusSnapshot;
  className?: string;
  presentationError?: string | null;
}

export interface SyncStateFactsProps {
  snapshot: SyncStatusSnapshot;
  className?: string;
  isChecking?: boolean;
}

function institutionalStateFromSyncStatus(
  status: SyncStatusSnapshot["status"],
): InstitutionalStatusState | null {
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
    case "REVIEW":
    case "ERROR":
      return null;
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

export function SyncStateFacts({
  snapshot,
  className,
  isChecking = snapshot.isLoading,
}: SyncStateFactsProps) {
  const classNames = [
    "institutional-sync-state__facts",
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
    <dl className={classNames}>
      <div>
        <dt>Fila local</dt>
        {isChecking ? (
          <dd
            className="tabular-nums"
            aria-label="Ainda verificando"
          >
            —
          </dd>
        ) : (
          <dd className="tabular-nums">{queueCount}</dd>
        )}
      </div>
      <div>
        <dt>Conflitos</dt>
        {isChecking ? (
          <dd
            className="tabular-nums"
            aria-label="Ainda verificando"
          >
            —
          </dd>
        ) : (
          <dd className="tabular-nums">
            {snapshot.conflictCount}
          </dd>
        )}
      </div>
      <div>
        <dt>Revisões</dt>
        {isChecking ? (
          <dd
            className="tabular-nums"
            aria-label="Ainda verificando"
          >
            —
          </dd>
        ) : (
          <dd className="tabular-nums">
            {snapshot.reviewCount}
          </dd>
        )}
      </div>
      <div className="institutional-sync-state__last-sync">
        <dt>Última sincronização</dt>
        {isChecking ? (
          <dd aria-label="Ainda verificando">—</dd>
        ) : (
          <dd>
            {lastSync && snapshot.lastSyncCompletedAt ? (
              <time dateTime={snapshot.lastSyncCompletedAt}>
                {lastSync}
              </time>
            ) : (
              "Não registrada"
            )}
          </dd>
        )}
      </div>
    </dl>
  );
}

export function SyncStateStrip({
  snapshot,
  className,
  presentationError = null,
}: SyncStateStripProps) {
  const classNames = [
    "institutional-sync-state",
    "institutional-frame",
    className,
  ]
    .filter(Boolean)
    .join(" ");
  const reviewCount = snapshot.reviewCount;
  const isChecking = snapshot.isLoading && !presentationError;
  const isError =
    !isChecking &&
    (snapshot.status === "ERROR" || Boolean(presentationError));
  const isReview =
    !isChecking && snapshot.status === "REVIEW";
  const errorMessage = presentationError || snapshot.lastSyncError;
  const institutionalState = institutionalStateFromSyncStatus(
    snapshot.status,
  );

  return (
    <section
      aria-label="Estado de sincronização"
      className={classNames}
    >
      {isChecking ? (
        <p
          className="institutional-sync-state__checking"
          data-sync-status="CHECKING"
          role="status"
        >
          Verificando estado local
        </p>
      ) : isError ? (
        <p
          className="institutional-sync-state__error"
          data-sync-error-count={snapshot.errorCount}
          data-sync-status="ERROR"
          role="status"
        >
          <span>Falha na sincronização</span>
          {snapshot.errorCount > 0 ? (
            <span className="tabular-nums">
              {snapshot.errorCount} falha
              {snapshot.errorCount === 1 ? "" : "s"} local
              {snapshot.errorCount === 1 ? "" : "is"}
            </span>
          ) : null}
          {errorMessage ? <span>{errorMessage}</span> : null}
        </p>
      ) : isReview ? (
        <p
          className="institutional-sync-state__error"
          data-sync-review-count={reviewCount}
          data-sync-status="REVIEW"
          role="status"
        >
          <span>Revisão necessária</span>
          <span className="tabular-nums">
            {reviewCount} registro{reviewCount === 1 ? "" : "s"} exige
            {reviewCount === 1 ? "" : "m"} revisão
          </span>
          {snapshot.reviewReason ? (
            <span>{snapshot.reviewReason}</span>
          ) : null}
        </p>
      ) : institutionalState ? (
        <InstitutionalStatus state={institutionalState} />
      ) : (
        <p
          className="institutional-sync-state__error"
          data-sync-error-count={snapshot.errorCount}
          data-sync-status="ERROR"
          role="status"
        >
          <span>Falha na sincronização</span>
          {snapshot.errorCount > 0 ? (
            <span className="tabular-nums">
              {snapshot.errorCount} falha
              {snapshot.errorCount === 1 ? "" : "s"} local
              {snapshot.errorCount === 1 ? "" : "is"}
            </span>
          ) : null}
          {snapshot.lastSyncError ? (
            <span>{snapshot.lastSyncError}</span>
          ) : null}
        </p>
      )}
      <SyncStateFacts
        snapshot={snapshot}
        isChecking={isChecking}
      />
    </section>
  );
}
