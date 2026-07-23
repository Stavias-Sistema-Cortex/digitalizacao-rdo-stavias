import "./institutional.css";

export type InstitutionalStatusState =
  | "LOCAL"
  | "PENDING"
  | "SYNCING"
  | "SYNCED"
  | "CONFLICT"
  | "REJECTED";

const statusLabels: Record<InstitutionalStatusState, string> = {
  LOCAL: "Local",
  PENDING: "Pendente",
  SYNCING: "Sincronizando",
  SYNCED: "Sincronizado",
  CONFLICT: "Conflito",
  REJECTED: "Rejeitado",
};

export interface InstitutionalStatusProps {
  state: InstitutionalStatusState;
  label?: string;
  className?: string;
}

export function InstitutionalStatus({ state, label, className }: InstitutionalStatusProps) {
  return (
    <span
      className={["institutional-status", className].filter(Boolean).join(" ")}
      data-state={state}
      role="status"
    >
      {label ?? statusLabels[state]}
    </span>
  );
}
