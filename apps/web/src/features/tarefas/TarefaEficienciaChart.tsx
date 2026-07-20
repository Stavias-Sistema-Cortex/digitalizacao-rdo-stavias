import { useMemo, useState } from "react";

import type { ChartPeriod } from "../home/progressSeries";
import type { TarefaMonthlyPoint } from "./tarefaEficiencia";

const SERIES = [
  {
    key: "concluidas",
    label: "Tarefas concluídas",
    color: "#0d9488",
  },
  {
    key: "criadas",
    label: "Meta de tarefas",
    color: "#4f7cd1",
  },
] as const;

type SeriesKey = (typeof SERIES)[number]["key"];

const PERIODS: { value: ChartPeriod; label: string }[] = [
  { value: "3M", label: "3m" },
  { value: "6M", label: "6m" },
  { value: "12M", label: "12m" },
  { value: "ALL", label: "Tudo" },
];

const WIDTH = 420;
const HEIGHT = 230;
const PLOT_LEFT = 34;
const PLOT_RIGHT = 406;
const PLOT_TOP = 14;
const PLOT_BOTTOM = 190;
const MAX_MONTH_LABELS = 12;

function monthLabel(month: string): string {
  const names = [
    "Jan", "Fev", "Mar", "Abr", "Mai", "Jun",
    "Jul", "Ago", "Set", "Out", "Nov", "Dez",
  ];
  const index = Number(month.slice(5)) - 1;
  return names[index] ?? month;
}

function niceStep(roughStep: number): number {
  if (roughStep <= 1) {
    return 1;
  }

  const magnitude = 10 ** Math.floor(Math.log10(roughStep));
  for (const factor of [1, 2, 5, 10]) {
    if (roughStep <= factor * magnitude) {
      return factor * magnitude;
    }
  }

  return 10 * magnitude;
}

interface TarefaEficienciaChartProps {
  points: TarefaMonthlyPoint[];
  period: ChartPeriod;
  onPeriodChange: (period: ChartPeriod) => void;
}

export function TarefaEficienciaChart({
  points,
  period,
  onPeriodChange,
}: TarefaEficienciaChartProps) {
  const [hiddenSeries, setHiddenSeries] = useState<
    Set<SeriesKey>
  >(new Set());
  const [hoverIndex, setHoverIndex] = useState<
    number | null
  >(null);

  const { yMax, gridValues } = useMemo(() => {
    let max = 0;
    for (const point of points) {
      for (const series of SERIES) {
        if (!hiddenSeries.has(series.key)) {
          max = Math.max(max, point[series.key]);
        }
      }
    }

    const step = niceStep(Math.max(max, 4) / 4);
    const top = step * 4;

    return {
      yMax: top,
      gridValues: [0, step, step * 2, step * 3, top],
    };
  }, [points, hiddenSeries]);

  function toggleSeries(key: SeriesKey) {
    setHiddenSeries((current) => {
      const next = new Set(current);
      if (next.has(key)) {
        next.delete(key);
      } else if (next.size < SERIES.length - 1) {
        next.add(key);
      }
      return next;
    });
  }

  const xFor = (index: number): number =>
    points.length <= 1
      ? (PLOT_LEFT + PLOT_RIGHT) / 2
      : PLOT_LEFT +
        (index * (PLOT_RIGHT - PLOT_LEFT)) /
          (points.length - 1);

  const yFor = (value: number): number =>
    PLOT_BOTTOM -
    (value / yMax) * (PLOT_BOTTOM - PLOT_TOP);

  function linePath(key: SeriesKey): string {
    return points
      .map(
        (point, index) =>
          `${index === 0 ? "M" : "L"}${xFor(index).toFixed(1)},${yFor(point[key]).toFixed(1)}`,
      )
      .join(" ");
  }

  function areaPath(key: SeriesKey): string {
    if (points.length < 2) {
      return "";
    }

    const top = points
      .map(
        (point, index) =>
          `${index === 0 ? "M" : "L"}${xFor(index).toFixed(1)},${yFor(point[key]).toFixed(1)}`,
      )
      .join(" ");

    return `${top} L${xFor(points.length - 1).toFixed(1)},${PLOT_BOTTOM} L${xFor(0).toFixed(1)},${PLOT_BOTTOM} Z`;
  }

  function handleMouseMove(
    event: React.MouseEvent<SVGSVGElement>,
  ) {
    if (points.length === 0) {
      return;
    }
    const rect =
      event.currentTarget.getBoundingClientRect();
    const x =
      ((event.clientX - rect.left) / rect.width) * WIDTH;
    let nearest = 0;
    let nearestDistance = Infinity;
    points.forEach((_, index) => {
      const distance = Math.abs(xFor(index) - x);
      if (distance < nearestDistance) {
        nearestDistance = distance;
        nearest = index;
      }
    });
    setHoverIndex(nearest);
  }

  const labelEvery = Math.max(
    1,
    Math.ceil(points.length / MAX_MONTH_LABELS),
  );
  const hovered =
    hoverIndex === null ? null : points[hoverIndex];

  return (
    <div className="progress-chart">
      <div className="progress-chart-controls">
        <div
          className="progress-chart-legend"
          role="group"
          aria-label="Séries do gráfico"
        >
          {SERIES.map((series) => (
            <button
              key={series.key}
              type="button"
              className={
                hiddenSeries.has(series.key)
                  ? "legend-item legend-item--off"
                  : "legend-item"
              }
              aria-pressed={!hiddenSeries.has(series.key)}
              onClick={() => toggleSeries(series.key)}
            >
              <span
                className="legend-swatch"
                style={{ background: series.color }}
              />
              {series.label}
            </button>
          ))}
        </div>
        <div
          className="progress-chart-periods"
          role="group"
          aria-label="Período"
        >
          {PERIODS.map((option) => (
            <button
              key={option.value}
              type="button"
              className={
                option.value === period
                  ? "period-pill period-pill--active"
                  : "period-pill"
              }
              aria-pressed={option.value === period}
              onClick={() => onPeriodChange(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      {points.length === 0 ? (
        <p className="progress-chart-empty">
          Sem tarefas neste recorte ainda — crie a primeira
          tarefa da equipe para acompanhar a eficiência.
        </p>
      ) : (
        <div className="progress-chart-plot">
          <svg
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            role="img"
            aria-label="Tarefas concluídas contra a meta de tarefas por mês"
            onMouseMove={handleMouseMove}
            onMouseLeave={() => setHoverIndex(null)}
          >
            {gridValues.map((value) => (
              <g key={value}>
                <line
                  x1={PLOT_LEFT}
                  x2={PLOT_RIGHT}
                  y1={yFor(value)}
                  y2={yFor(value)}
                  stroke="#e5e9ea"
                  strokeWidth="1"
                />
                <text
                  x={PLOT_LEFT - 6}
                  y={yFor(value) + 3}
                  textAnchor="end"
                  fontSize="10"
                  fill="#8b9498"
                >
                  {value}
                </text>
              </g>
            ))}

            {SERIES.filter(
              (series) => !hiddenSeries.has(series.key),
            ).map((series) => (
              <g key={series.key}>
                {points.length >= 2 && (
                  <path
                    d={areaPath(series.key)}
                    fill={series.color}
                    fillOpacity="0.14"
                    stroke="none"
                  />
                )}
                <path
                  d={linePath(series.key)}
                  fill="none"
                  stroke={series.color}
                  strokeWidth="2"
                  strokeLinejoin="round"
                  strokeLinecap="round"
                />
                {points.length === 1 && (
                  <circle
                    cx={xFor(0)}
                    cy={yFor(points[0][series.key])}
                    r="4"
                    fill={series.color}
                    stroke="#ffffff"
                    strokeWidth="2"
                  />
                )}
              </g>
            ))}

            {points.map((point, index) =>
              index % labelEvery === 0 ? (
                <text
                  key={point.month}
                  x={xFor(index)}
                  y={PLOT_BOTTOM + 16}
                  textAnchor="middle"
                  fontSize="10"
                  fill="#8b9498"
                >
                  {monthLabel(point.month)}
                </text>
              ) : null,
            )}

            {hoverIndex !== null && (
              <line
                x1={xFor(hoverIndex)}
                x2={xFor(hoverIndex)}
                y1={PLOT_TOP}
                y2={PLOT_BOTTOM}
                stroke="#c3cbcd"
                strokeWidth="1"
              />
            )}
          </svg>

          {hovered && (
            <div className="progress-chart-tooltip">
              <strong>
                {monthLabel(hovered.month)}{" "}
                {hovered.month.slice(0, 4)}
              </strong>
              {SERIES.map((series) => (
                <span key={series.key}>
                  <span
                    className="legend-swatch"
                    style={{ background: series.color }}
                  />
                  {series.label}: {hovered[series.key]}
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
