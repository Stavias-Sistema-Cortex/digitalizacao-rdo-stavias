import type { PrevisaoSnapshotRecord } from "../../lib/db/db.types";
import { isSupportedPdorRevenueContract } from "../financeiro/pdorRevenuePolicy";

export type ChartPeriod = "3M" | "6M" | "12M" | "ALL";

export interface MonthlyPoint {
  month: string;
  fisicoPct: number | null;
  apontadaPct: number | null;
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

/**
 * A receita prevista só entra no gráfico com base canônica completa: cálculo
 * concluído, evidência aceita presente. É a mesma régua do painel PDOR.
 */
function elegivelParaReceita(
  snapshot: PrevisaoSnapshotRecord,
): boolean {
  return (
    snapshot.statusExecucao === "SUCCESS" &&
    snapshot.evidenceIds?.length !== 0 &&
    snapshot.coverageCode !== "NO_ACCEPTED_EVIDENCE"
  );
}

/**
 * As linhas de produção não dependem da receita ter saído.
 *
 * <p>Avanço físico e produção apontada são quantidades dos RDOs e da
 * programação; a receita exige preço e evidência aceita. Quando o mês inteiro
 * era descartado por o PDOR não ter fechado a receita, a obra nova — com
 * apontamento em dia e catálogo ainda vazio — abria um gráfico em branco que
 * prometia três séries na legenda e não desenhava nenhuma.</p>
 */
function temProducao(snapshot: PrevisaoSnapshotRecord): boolean {
  return (
    snapshot.producaoPlanejada !== null &&
    snapshot.producaoPlanejada > 0 &&
    (snapshot.producaoRealizada !== null ||
      snapshot.producaoApontada !== null)
  );
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
    if (
      snapshot.current !== true ||
      snapshot.stale !== false ||
      !isSupportedPdorRevenueContract(snapshot) ||
      (!elegivelParaReceita(snapshot) && !temProducao(snapshot))
    ) {
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
      /*
       * Produção apontada é linha própria, não substituta do avanço físico:
       * enquanto a medição não fecha, ela costuma correr na frente, e é
       * justamente essa distância que interessa ver.
       */
      apontadaPct: ratioPct(
        snapshot.producaoApontada,
        snapshot.producaoPlanejada,
      ),
      // Nulo quando a receita daquele mês não tem base canônica: a linha
      // amarela abre um buraco em vez de desenhar um número descartado.
      pdorPct: elegivelParaReceita(snapshot)
        ? ratioPct(
            snapshot.receitaPrevistaFinal,
            valorContratual,
          )
        : null,
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
