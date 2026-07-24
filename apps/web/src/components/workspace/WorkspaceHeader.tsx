import type { ReactNode } from "react";

import { CortexPageHeader } from "../header/CortexPageHeader";

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
    <CortexPageHeader
      eyebrow={eyebrow}
      title={title}
      description={description}
      actions={actions}
      legacyPrefix="workspace-header"
    />
  );
}
