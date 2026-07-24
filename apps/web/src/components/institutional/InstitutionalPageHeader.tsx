import type { ReactNode } from "react";

import { CortexPageHeader } from "../header/CortexPageHeader";
import "./institutional.css";

export interface InstitutionalPageHeaderProps {
  eyebrow: string;
  title: string;
  description?: string;
  actions?: ReactNode;
  className?: string;
}

export function InstitutionalPageHeader({
  eyebrow,
  title,
  description,
  actions,
  className,
}: InstitutionalPageHeaderProps) {
  return (
    <CortexPageHeader
      eyebrow={eyebrow}
      title={title}
      description={description}
      actions={actions}
      className={className}
      legacyPrefix="institutional-page-header"
    />
  );
}
