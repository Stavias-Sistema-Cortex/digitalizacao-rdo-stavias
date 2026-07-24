import type { ReactNode } from "react";

import { CortexShellHeaderControls } from "../shell/CortexShellChromeContext";
import "./CortexPageHeader.css";

export interface CortexPageHeaderProps {
  eyebrow: string;
  title: string;
  description?: string;
  actions?: ReactNode;
  className?: string;
  /**
   * Keeps page-specific selectors stable while the visible frame is shared.
   * Use the existing component class (for example, `workspace-header`).
   */
  legacyPrefix?: string;
}

function classNames(...values: Array<string | undefined>) {
  return values.filter(Boolean).join(" ");
}

export function CortexPageHeader({
  eyebrow,
  title,
  description,
  actions,
  className,
  legacyPrefix,
}: CortexPageHeaderProps) {
  const copyClassName = classNames(
    "cortex-page-header__copy",
    legacyPrefix ? `${legacyPrefix}__copy` : undefined,
  );
  const eyebrowClassName = classNames(
    "cortex-page-header__eyebrow",
    legacyPrefix ? `${legacyPrefix}__eyebrow` : undefined,
  );
  const descriptionClassName = classNames(
    "cortex-page-header__description",
    legacyPrefix ? `${legacyPrefix}__description` : undefined,
  );
  const actionsClassName = classNames(
    "cortex-page-header__actions",
    legacyPrefix ? `${legacyPrefix}__actions` : undefined,
  );

  return (
    <header
      className={classNames("cortex-page-header", legacyPrefix, className)}
    >
      <div className={copyClassName}>
        <p className={eyebrowClassName}>{eyebrow}</p>
        <h1>{title}</h1>
        {description ? <p className={descriptionClassName}>{description}</p> : null}
      </div>

      <div className="cortex-page-header__right">
        {actions ? <div className={actionsClassName}>{actions}</div> : null}
        <div className="cortex-page-header__global-controls">
          <CortexShellHeaderControls />
        </div>
      </div>
    </header>
  );
}
