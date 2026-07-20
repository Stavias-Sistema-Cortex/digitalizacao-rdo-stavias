import type { TarefaRecord } from "../../lib/db/db.types";
import type { ChartPeriod } from "../home/progressSeries";

export interface TarefaMonthlyPoint {
  month: string;
  criadas: number;
  concluidas: number;
}

export interface EficienciaResumo {
  total: number;
  concluidas: number;
  percentual: number | null;
}

function monthOf(iso: string | null): string | null {
  if (!iso) {
    return null;
  }

  const match = /^(\d{4})-(\d{2})/.exec(iso);
  return match ? `${match[1]}-${match[2]}` : null;
}

function shiftMonth(month: string, delta: number): string {
  const [year, monthIndex] = month.split("-").map(Number);
  const total = year * 12 + (monthIndex - 1) + delta;
  const shiftedYear = Math.floor(total / 12);
  const shiftedMonth = (total % 12) + 1;

  return `${String(shiftedYear).padStart(4, "0")}-${String(
    shiftedMonth,
  ).padStart(2, "0")}`;
}

/**
 * Série mensal contínua (sem meses pulados) de tarefas criadas —
 * a meta do período — contra tarefas concluídas.
 */
export function buildTarefaSeries(
  tarefas: TarefaRecord[],
): TarefaMonthlyPoint[] {
  const criadasPorMes = new Map<string, number>();
  const concluidasPorMes = new Map<string, number>();

  for (const tarefa of tarefas) {
    const criadaEm = monthOf(tarefa.createdAt);
    if (criadaEm) {
      criadasPorMes.set(
        criadaEm,
        (criadasPorMes.get(criadaEm) ?? 0) + 1,
      );
    }

    const concluidaEm = monthOf(tarefa.concluidaEm);
    if (concluidaEm) {
      concluidasPorMes.set(
        concluidaEm,
        (concluidasPorMes.get(concluidaEm) ?? 0) + 1,
      );
    }
  }

  const months = [
    ...criadasPorMes.keys(),
    ...concluidasPorMes.keys(),
  ].sort();

  if (months.length === 0) {
    return [];
  }

  const first = months[0];
  const last = months[months.length - 1];
  const points: TarefaMonthlyPoint[] = [];

  for (
    let month = first;
    month <= last;
    month = shiftMonth(month, 1)
  ) {
    points.push({
      month,
      criadas: criadasPorMes.get(month) ?? 0,
      concluidas: concluidasPorMes.get(month) ?? 0,
    });
  }

  return points;
}

export function filterTarefaSeriesByPeriod(
  points: TarefaMonthlyPoint[],
  period: ChartPeriod,
): TarefaMonthlyPoint[] {
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

export function eficienciaConclusao(
  tarefas: TarefaRecord[],
): EficienciaResumo {
  const total = tarefas.length;
  const concluidas = tarefas.filter(
    (tarefa) => tarefa.concluida,
  ).length;

  return {
    total,
    concluidas,
    percentual:
      total === 0
        ? null
        : Math.round((concluidas / total) * 1000) / 10,
  };
}
