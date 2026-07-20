import type { ReactNode } from "react";

interface FinanceDrawerProps {
  title: string;
  description?: string;
  onClose: () => void;
  children: ReactNode;
  wide?: boolean;
}

export function FinanceDrawer({
  title,
  description,
  onClose,
  children,
  wide = false,
}: FinanceDrawerProps) {
  return (
    <div className="finance-drawer-layer">
      <button
        type="button"
        className="finance-drawer-backdrop"
        aria-label="Fechar painel"
        onClick={onClose}
      />
      <aside
        className={wide ? "finance-drawer is-wide" : "finance-drawer"}
        role="dialog"
        aria-modal="true"
        aria-labelledby="finance-drawer-title"
      >
        <header>
          <div>
            <p className="finance-kicker">Financeiro</p>
            <h2 id="finance-drawer-title">{title}</h2>
            {description ? <p>{description}</p> : null}
          </div>
          <button type="button" onClick={onClose} aria-label="Fechar">
            ×
          </button>
        </header>
        <div className="finance-drawer-content">{children}</div>
      </aside>
    </div>
  );
}
