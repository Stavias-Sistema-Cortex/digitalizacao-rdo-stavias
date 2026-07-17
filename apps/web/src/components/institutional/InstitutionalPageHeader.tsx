import type { ReactNode } from "react";

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
  const classNames = [
    "institutional-page-header",
    className,
  ]
    .filter(Boolean)
    .join(" ");

  return (
    <header className={classNames}>
      <div className="institutional-page-header__copy">
        <p className="institutional-page-header__eyebrow">
          {eyebrow}
        </p>
        <h1>{title}</h1>
        {description ? (
          <p className="institutional-page-header__description">
            {description}
          </p>
        ) : null}
      </div>
      {actions ? (
        <div className="institutional-page-header__actions">
          {actions}
        </div>
      ) : null}
    </header>
  );
}
