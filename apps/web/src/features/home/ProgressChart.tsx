import { useMemo, useState } from "react";

import type {
  ChartPeriod,
  MonthlyPoint,
} from "./progressSeries";

const SERIES = [
  {
    key: "fisicoPct",
    label: "Avanço físico",
    color: "#0e857a",
  },
  {
    key: "custoPct",
    label: "Custo consumido",
    color: "#4f7cd1",
  },
  {
    key: "pdorPct",
    label: "PDOR vs contrato",
    color: "#e8a13d",
  },
] as const;

type SeriesKey = (typeof SERIES)[number]["key"];

const PERIODS: { value: ChartPeriod; label: string }[] = [
  { value: "3M", label: "3m" },
  { value: "6M", label: "6m" },
  { value: "12M", label: "12m" },
  { value: "ALL", label: "Tudo" },
];

const WIDTH = 480;
const HEIGHT = 240;
const PLOT_LEFT = 46;
const PLOT_RIGHT = 466;
const PLOT_TOP = 16;
const PLOT_BOTTOM = 196;

interface ProgressChartProps {
  points: MonthlyPoint[];
  period: ChartPeriod;
  onPeriodChange: (period: ChartPeriod) => void;
}

function monthLabel(month: string): string {
  const names = [
    "Jan", "Fev", "Mar", "Abr", "Mai", "Jun",
    "Jul", "Ago", "Set", "Out", "Nov", "Dez",
  ];
  const index = Number(month.slice(5)) - 1;
  return names[index] ?? month;
}

export function ProgressChart({
  points,
  period,
  onPeriodChange,
}: ProgressChartProps) {
  const [hiddenSeries, setHiddenSeries] = useState<
    Set<SeriesKey>
  >(new Set());
  const [hoverIndex, setHoverIndex] = useState<
    number | null
  >(null);

  const maxValue = useMemo(() => {
    let max = 120;
    for (const point of points) {
      for (const series of SERIES) {
        const value = point[series.key];
        if (
          value !== null &&
          !hiddenSeries.has(series.key)
        ) {
          max = Math.max(max, value);
        }
      }
    }
    return Math.ceil(max / 30) * 30;
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
    (value / maxValue) * (PLOT_BOTTOM - PLOT_TOP);

  function pathFor(key: SeriesKey): string {
    const segments: string[] = [];
    let pen = false;

    points.forEach((point, index) => {
      const value = point[key];
      if (value === null) {
        pen = false;
        return;
      }
      segments.push(
        `${pen ? "L" : "M"}${xFor(index).toFixed(1)},${yFor(value).toFixed(1)}`,
      );
      pen = true;
    });

    return segments.join(" ");
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

  const gridValues = useMemo(() => {
    const step = maxValue / 4;
    return [0, step, step * 2, step * 3, maxValue];
  }, [maxValue]);

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
          Sem histórico de previsão ainda.
        </p>
      ) : (
        <div className="progress-chart-plot">
          <svg
            viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
            role="img"
            aria-label="Progressão mensal da obra em percentual"
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
                  {Math.round(value)}%
                </text>
              </g>
            ))}

            <line
              x1={PLOT_LEFT}
              x2={PLOT_RIGHT}
              y1={yFor(100)}
              y2={yFor(100)}
              stroke="#9ca3af"
              strokeWidth="1.5"
              strokeDasharray="4 4"
            />

            {SERIES.filter(
              (series) => !hiddenSeries.has(series.key),
            ).map((series) => (
              <path
                key={series.key}
                d={pathFor(series.key)}
                fill="none"
                stroke={series.color}
                strokeWidth="2.5"
                strokeLinejoin="round"
                strokeLinecap="round"
              />
            ))}

            {points.map((point, index) => (
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
            ))}

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
              <strong>{monthLabel(hovered.month)}</strong>
              {SERIES.map((series) => (
                <span key={series.key}>
                  <span
                    className="legend-swatch"
                    style={{ background: series.color }}
                  />
                  {series.label}:{" "}
                  {hovered[series.key] === null
                    ? "—"
                    : `${hovered[series.key]}%`}
                </span>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
