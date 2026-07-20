import type { LocalRdoRecord } from "../../lib/db/db.types";

export interface TeamEntry {
  cargo: string;
  quantidade: number;
}

function parseQuantidade(value: unknown): number {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }

  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value.replace(",", "."));
    return Number.isFinite(parsed) ? parsed : 0;
  }

  return 0;
}

export function teamFromRdo(
  rdo: LocalRdoRecord | null,
): TeamEntry[] {
  if (!rdo) {
    return [];
  }

  const maoObra = rdo.payload.maoObra;

  if (!Array.isArray(maoObra)) {
    return [];
  }

  const byCargo = new Map<string, number>();

  for (const item of maoObra) {
    if (typeof item !== "object" || item === null) {
      continue;
    }

    const entry = item as Record<string, unknown>;
    const cargo =
      typeof entry.cargo === "string"
        ? entry.cargo.trim()
        : "";

    if (!cargo) {
      continue;
    }

    byCargo.set(
      cargo,
      (byCargo.get(cargo) ?? 0) +
        parseQuantidade(entry.quantidade),
    );
  }

  return [...byCargo.entries()].map(
    ([cargo, quantidade]) => ({
      cargo,
      quantidade,
    }),
  );
}
