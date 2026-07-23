import type { ReactNode } from "react";

export interface WorkspaceHeaderProps {
  eyebrow: string;
  title: string;
  description?: string;
  actions?: ReactNode;
}

export function WorkspaceHeader({
  eyebrow,
  title,
  description,
  actions,
}: WorkspaceHeaderProps) {
  return (
    <header className="workspace-header">
      <div className="workspace-header__copy">
        <p className="workspace-header__eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        {description ? (
          <p className="workspace-header__description">{description}</p>
        ) : null}
      </div>
      {actions ? (
        <div className="workspace-header__actions">{actions}</div>
      ) : null}
    </header>
  );
}
