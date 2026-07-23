import type { ReactNode } from "react";

import { WorkspaceHeader } from "./WorkspaceHeader";
import {
  WorkspaceStatusRail,
  type WorkspaceStatus,
} from "./WorkspaceStatusRail";
import "./OperationalWorkspace.css";

export interface WorkspaceTab {
  id: string;
  label: string;
  active?: boolean;
  disabled?: boolean;
}

export interface OperationalWorkspaceProps {
  eyebrow: string;
  title: string;
  description?: string;
  actions?: ReactNode;
  tabs?: readonly WorkspaceTab[];
  onTabChange?: (id: string) => void;
  status?: WorkspaceStatus;
  statusActions?: ReactNode;
  className?: string;
  children: ReactNode;
}

export function OperationalWorkspace({
  eyebrow,
  title,
  description,
  actions,
  tabs,
  onTabChange,
  status,
  statusActions,
  className,
  children,
}: OperationalWorkspaceProps) {
  return (
    <main
      className={["operational-workspace", className].filter(Boolean).join(" ")}
      data-workspace="operational"
    >
      <WorkspaceHeader
        eyebrow={eyebrow}
        title={title}
        description={description}
        actions={actions}
      />
      {tabs?.length ? (
        <nav className="workspace-tabs" aria-label={`Áreas de ${title}`} role="tablist">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              type="button"
              role="tab"
              aria-selected={tab.active === true}
              disabled={tab.disabled}
              className={tab.active ? "is-active" : ""}
              onClick={() => onTabChange?.(tab.id)}
            >
              {tab.label}
            </button>
          ))}
        </nav>
      ) : null}
      {status ? (
        <WorkspaceStatusRail status={status} actions={statusActions} />
      ) : null}
      <section className="operational-workspace__content">{children}</section>
    </main>
  );
}
