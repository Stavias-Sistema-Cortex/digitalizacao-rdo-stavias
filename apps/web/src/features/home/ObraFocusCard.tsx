import { useMemo, useState } from "react";

import type {
  LocalRdoRecord,
  ObraLocalRecord,
  OperationalEventRecord,
  PrevisaoSnapshotRecord,
} from "../../lib/db/db.types";
import { ProgressChart } from "./ProgressChart";
import {
  buildMonthlySeries,
  filterByPeriod,
  type ChartPeriod,
} from "./progressSeries";

interface ObraFocusCardProps {
  obra: ObraLocalRecord;
  obraOptions: ObraLocalRecord[];
  onSelectObra: (obraId: string) => void;
  snapshots: PrevisaoSnapshotRecord[];
  events: OperationalEventRecord[];
  latestRdo: LocalRdoRecord | null;
}

function formatUpdatedAt(iso: string | null): string {
  if (!iso) {
    return "";
  }

  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return "";
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function countOcorrencias30d(
  events: OperationalEventRecord[],
): number {
  const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;

  return events.filter(
    (event) =>
      event.type === "OCORRENCIA_REGISTRADA" &&
      new Date(event.occurredAt).getTime() >= cutoff,
  ).length;
}

export function ObraFocusCard({
  obra,
  obraOptions,
  onSelectObra,
  snapshots,
  events,
  latestRdo,
}: ObraFocusCardProps) {
  const [period, setPeriod] = useState<ChartPeriod>("6M");

  const allPoints = useMemo(
    () =>
      buildMonthlySeries(snapshots, obra.valorContratual),
    [snapshots, obra.valorContratual],
  );
  const points = useMemo(
    () => filterByPeriod(allPoints, period),
    [allPoints, period],
  );

  const progressoGeral =
    allPoints.length > 0
      ? allPoints[allPoints.length - 1].fisicoPct
      : null;

  const localizacao = [obra.cidade, obra.uf]
    .filter(Boolean)
    .join("/");

  return (
    <section className="home-obra-card">
      <header className="home-obra-header">
        <span className="home-obra-pill">
          Última Obra Acessada
        </span>
        <h2>{obra.nome}</h2>
        <span className="home-obra-status">
          {obra.status}
        </span>
        <label className="home-obra-selector">
          <span className="visually-hidden">
            Trocar obra
          </span>
          <select
            value={obra.id}
            onChange={(event) => {
              onSelectObra(event.target.value);
            }}
          >
            {obraOptions.map((option) => (
              <option key={option.id} value={option.id}>
                {option.nome}
              </option>
            ))}
          </select>
        </label>
      </header>

      <div className="home-obra-body">
        <div className="home-obra-infos">
          <div>
            <span className="info-label">Contrato</span>
            <strong>
              {obra.codigoContrato || "—"}
              {obra.cliente ? ` · ${obra.cliente}` : ""}
            </strong>
          </div>
          <div>
            <span className="info-label">Localização</span>
            <strong>
              {[localizacao, obra.rodovia]
                .filter(Boolean)
                .join(" · ") || "—"}
            </strong>
            <span className="info-coords">
              {obra.latitude !== null &&
              obra.longitude !== null
                ? `${obra.latitude}, ${obra.longitude}`
                : "—"}
            </span>
          </div>
          <div>
            <span className="info-label">
              Observações gerais
            </span>
            <span>{obra.observacoes || "—"}</span>
          </div>
          <div className="home-obra-metrics">
            <div>
              <span className="info-label">
                Ocorrências (30d)
              </span>
              <strong className="metric-value">
                {countOcorrencias30d(events)}
              </strong>
            </div>
            <div>
              <span className="info-label">Último RDO</span>
              <strong>
                {latestRdo
                  ? `${latestRdo.dataRdo} · ${
                      latestRdo.statusRdo === "ENVIADO"
                        ? "enviado"
                        : "rascunho"
                    }`
                  : "—"}
              </strong>
            </div>
            <div>
              <span className="info-label">
                Progresso geral
              </span>
              <strong className="metric-value metric-value--brand">
                {progressoGeral === null
                  ? "—"
                  : `${progressoGeral}%`}
              </strong>
            </div>
          </div>
          <span className="home-updated-at">
            dados atualizados em{" "}
            {formatUpdatedAt(obra.updatedAt) || "—"}
          </span>
        </div>

        <div className="home-obra-chart">
          <ProgressChart
            points={points}
            period={period}
            onPeriodChange={setPeriod}
          />
        </div>
      </div>
    </section>
  );
}
