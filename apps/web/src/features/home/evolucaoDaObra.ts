import {
  shiftMonth,
  type ChartPeriod,
  type MonthlyPoint,
} from "./progressSeries";

/**
 * Janela geoespacial derivada do gráfico de avanço da Home.
 *
 * O mapa do card promete acompanhar o mesmo período do gráfico, então a
 * janela não é calculada por conta própria: ela nasce dos MESMOS pontos que o
 * gráfico exibe. Calcular "6 meses a partir de hoje" aqui daria dois períodos
 * diferentes sob o mesmo rótulo sempre que o histórico estivesse defasado —
 * gráfico mostrando dez/2025–fev/2026 e mapa mostrando desde mai/2026.
 */
export interface JanelaDeEvolucao {
  /** Primeiro dia considerado, ISO `YYYY-MM-DD`. Nulo não limita o passado. */
  inicio: string | null;
}

const MESES_POR_PERIODO: Readonly<Record<ChartPeriod, number | null>> = {
  "3M": 3,
  "6M": 6,
  "12M": 12,
  ALL: null,
};

function mesUtc(data: Date): string {
  return data.toISOString().slice(0, 7);
}

/**
 * @param pontosVisiveis os pontos que o gráfico está exibindo, já recortados
 * pelo período — o primeiro deles é o início real da janela na tela.
 */
export function janelaDoGrafico(
  pontosVisiveis: readonly MonthlyPoint[],
  period: ChartPeriod,
  hoje: Date = new Date(),
): JanelaDeEvolucao {
  const meses = MESES_POR_PERIODO[period];
  if (meses === null) {
    return { inicio: null };
  }
  if (pontosVisiveis.length > 0) {
    return { inicio: `${pontosVisiveis[0].month}-01` };
  }
  // Sem histórico de previsão o gráfico está vazio e não há âncora; a mesma
  // conta do gráfico (granularidade de mês, N-1 para trás) aplicada ao mês
  // corrente é o que ele mostraria se o primeiro ponto chegasse hoje.
  return { inicio: `${shiftMonth(mesUtc(hoje), -(meses - 1))}-01` };
}
