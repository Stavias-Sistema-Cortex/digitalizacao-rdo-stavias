export function relativeTime(
  iso: string,
  nowMs: number,
): string {
  const timestamp = Date.parse(iso);

  if (Number.isNaN(timestamp)) {
    return "";
  }

  const deltaMs = nowMs - timestamp;
  const minutes = Math.floor(deltaMs / 60_000);

  if (minutes < 1) {
    return "agora há pouco";
  }

  if (minutes < 60) {
    return `há ${minutes} min`;
  }

  const hours = Math.floor(minutes / 60);

  if (hours < 24) {
    return `há ${hours} h`;
  }

  const days = Math.floor(hours / 24);

  if (days <= 30) {
    return days === 1 ? "há 1 dia" : `há ${days} dias`;
  }

  const date = new Date(timestamp);

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
  }).format(date);
}
