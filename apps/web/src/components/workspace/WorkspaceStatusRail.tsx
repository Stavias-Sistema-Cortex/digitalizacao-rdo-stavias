import type { ReactNode } from "react";

export interface WorkspaceStatus {
  code: string;
  label: string;
  detail?: string;
}

export interface WorkspaceStatusRailProps {
  status: WorkspaceStatus;
  actions?: ReactNode;
}

export function WorkspaceStatusRail({
  status,
  actions,
}: WorkspaceStatusRailProps) {
  return (
    <section className="workspace-status-rail" aria-label="Estado operacional">
      <div
        className="workspace-status-rail__state"
        data-status={status.code}
        role="status"
      >
        <span className="workspace-status-rail__marker" aria-hidden="true" />
        <strong>{status.label}</strong>
        {status.detail ? <span>{status.detail}</span> : null}
      </div>
      {actions ? (
        <div className="workspace-status-rail__actions">{actions}</div>
      ) : null}
    </section>
  );
}
