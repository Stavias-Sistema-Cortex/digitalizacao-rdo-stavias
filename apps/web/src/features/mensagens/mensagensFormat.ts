const MINUTE_MS = 60_000;
const HOUR_MS = 60 * MINUTE_MS;
const DAY_MS = 24 * HOUR_MS;

const clockFormat = new Intl.DateTimeFormat("pt-BR", {
  hour: "2-digit",
  minute: "2-digit",
});

const dayMonthFormat = new Intl.DateTimeFormat("pt-BR", {
  day: "2-digit",
  month: "2-digit",
});

const dayKeyFormat = new Intl.DateTimeFormat("pt-BR", {
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

/**
 * Rótulo relativo compacto das legendas de run.
 * Montado à mão de propósito: o ICU do Node e o do navegador divergem em pt-BR
 * (`Intl.RelativeTimeFormat` devolve "há 5 minutos" ou "há 1 min.", nunca a
 * forma curta que o layout pede).
 */
export function formatRelativeTime(value: string, now: Date): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const elapsed = now.getTime() - date.getTime();
  if (elapsed < MINUTE_MS) {
    return "agora";
  }
  if (elapsed < HOUR_MS) {
    return `há ${Math.floor(elapsed / MINUTE_MS)} min`;
  }
  if (elapsed < DAY_MS) {
    return `há ${Math.floor(elapsed / HOUR_MS)} h`;
  }
  if (isPreviousDay(date, now)) {
    return `ontem ${clockFormat.format(date)}`;
  }
  return `${dayMonthFormat.format(date)} ${clockFormat.format(date)}`;
}

export function formatClock(value: string): string {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "" : clockFormat.format(date);
}

export function formatFileSize(bytes: number): string {
  return bytes < 1024 * 1024
    ? `${Math.max(1, Math.round(bytes / 1024))} KB`
    : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) {
    return "?";
  }
  const first = parts[0][0] ?? "";
  const last = parts.length > 1 ? parts[parts.length - 1][0] : "";
  return `${first}${last}`.toLocaleUpperCase("pt-BR");
}

export function messageFrom(cause: unknown): string {
  return cause instanceof Error
    ? cause.message
    : "Não foi possível concluir a operação.";
}

function isPreviousDay(date: Date, now: Date): boolean {
  const yesterday = new Date(now);
  yesterday.setDate(yesterday.getDate() - 1);
  return dayKeyFormat.format(date) === dayKeyFormat.format(yesterday);
}
