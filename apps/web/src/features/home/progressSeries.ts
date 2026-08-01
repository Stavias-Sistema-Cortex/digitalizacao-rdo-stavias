import type { PrevisaoSnapshotRecord } from "../../lib/db/db.types";

export type ChartPeriod = "3M" | "6M" | "12M" | "ALL";

export interface MonthlyPoint {
  month: string;
  fisicoPct: number | null;
  pdorPct: number | null;
}

export function ratioPct(
  numerator: number | null,
  denominator: number | null,
): number | null {
  if (
    numerator === null ||
    denominator === null ||
    !Number.isFinite(numerator) ||
    !Number.isFinite(denominator) ||
    denominator <= 0
  ) {
    return null;
  }

  return Math.round((numerator / denominator) * 1000) / 10;
}

function monthOf(dataReferencia: string): string | null {
  const match = /^(\d{4})-(\d{2})-\d{2}$/.exec(
    dataReferencia,
  );
  return match ? `${match[1]}-${match[2]}` : null;
}

export function buildMonthlySeries(
  snapshots: PrevisaoSnapshotRecord[],
  valorContratual: number | null,
): MonthlyPoint[] {
  const latestPerMonth = new Map<
    string,
    PrevisaoSnapshotRecord
  >();

  for (const snapshot of snapshots) {
    if (snapshot.statusExecucao !== "CALCULADO") {
      continue;
    }

    const month = monthOf(snapshot.dataReferencia);
    if (!month) {
      continue;
    }

    const current = latestPerMonth.get(month);
    if (
      !current ||
      snapshot.dataReferencia > current.dataReferencia
    ) {
      latestPerMonth.set(month, snapshot);
    }
  }

  return [...latestPerMonth.entries()]
    .sort(([a], [b]) => (a < b ? -1 : 1))
    .map(([month, snapshot]) => ({
      month,
      fisicoPct: ratioPct(
        snapshot.producaoRealizada,
        snapshot.producaoPlanejada,
      ),
      pdorPct: ratioPct(
        snapshot.receitaPrevistaFinal,
        valorContratual,
      ),
    }));
}

/**
 * Aritmética de mês sobre a própria string `YYYY-MM`.
 *
 * Exportada porque a janela do mapa de evolução precisa da MESMA conta do
 * gráfico: refazer o deslocamento com `Date` reintroduz o estouro de fim de
 * mês (31 de agosto menos 6 meses cai em 3 de março) que esta função evita.
 */
export function shiftMonth(month: string, delta: number): string {
  const [year, monthIndex] = month.split("-").map(Number);
  const total = year * 12 + (monthIndex - 1) + delta;
  const shiftedYear = Math.floor(total / 12);
  const shiftedMonth = (total % 12) + 1;

  return `${String(shiftedYear).padStart(4, "0")}-${String(
    shiftedMonth,
  ).padStart(2, "0")}`;
}

export function filterByPeriod(
  points: MonthlyPoint[],
  period: ChartPeriod,
): MonthlyPoint[] {
  if (period === "ALL" || points.length === 0) {
    return points;
  }

  const window =
    period === "3M" ? 3 : period === "6M" ? 6 : 12;
  const lastMonth = points[points.length - 1].month;
  const firstAllowed = shiftMonth(lastMonth, -(window - 1));

  return points.filter(
    (point) => point.month >= firstAllowed,
  );
}
