import type { FinancialPermission } from "./financeiro.types";

export function formatMoney(value: number, currency: string): string {
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: currency || "BRL",
    minimumFractionDigits: 2,
  }).format(Number.isFinite(value) ? value : 0);
}

export function formatDate(value: string | null): string {
  if (!value) {
    return "—";
  }
  const date = new Date(`${value.slice(0, 10)}T12:00:00`);
  return Number.isNaN(date.valueOf())
    ? value
    : new Intl.DateTimeFormat("pt-BR").format(date);
}

export function formatDateTime(value: string | null): string {
  if (!value) {
    return "—";
  }
  const date = new Date(value);
  return Number.isNaN(date.valueOf())
    ? value
    : new Intl.DateTimeFormat("pt-BR", {
        dateStyle: "short",
        timeStyle: "short",
      }).format(date);
}

export function hasFinancialPermission(
  permissions: FinancialPermission[],
  permission: FinancialPermission,
): boolean {
  return permissions.includes(permission);
}

export function statusTone(status: string | null): string {
  const normalized = (status ?? "").toUpperCase();
  if (/VENC|ERRO|CANCEL|REJEIT|FALH/.test(normalized)) {
    return "danger";
  }
  if (/PAGO|RECEB|APROV|ENVIAD|CONCLU|LIQUID/.test(normalized)) {
    return "success";
  }
  if (/PEND|AGUARD|RASCUN|ABERT|PROGRAM/.test(normalized)) {
    return "warning";
  }
  return "neutral";
}
