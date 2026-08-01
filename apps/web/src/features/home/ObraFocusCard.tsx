import { useMemo, useState } from "react";

import type {
  LocalRdoRecord,
  ObraLocalRecord,
  OperationalEventRecord,
  PrevisaoSnapshotRecord,
} from "../../lib/db/db.types";
import { janelaDoGrafico } from "./evolucaoDaObra";
import { ObraEvolucaoMapa } from "./ObraEvolucaoMapa";
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
  // A janela do mapa nasce dos pontos que o gráfico exibe, não de uma conta
  // própria: dois cálculos separados divergiriam sob o mesmo rótulo assim que
  // o histórico ficasse defasado.
  const janela = useMemo(
    () => janelaDoGrafico(points, period),
    [points, period],
  );

  const progressoGeral =
    allPoints.length > 0
      ? allPoints[allPoints.length - 1].fisicoPct
      : null;

  const localizacao = [obra.cidade, obra.uf]
    .filter(Boolean)
    .join("/");
  const updatedAt = formatUpdatedAt(obra.updatedAt);

  return (
    <section
      className="home-obra-card home-worksite-command"
      aria-labelledby={`home-worksite-${obra.id}`}
    >
      <header className="home-obra-header">
        <div>
          <span className="home-section-index">Empreendimento em foco</span>
          <div className="home-obra-title-line">
            <h2 id={`home-worksite-${obra.id}`}>{obra.nome}</h2>
            <span className="home-obra-status">{obra.status}</span>
          </div>
        </div>
        <label className="home-obra-selector">
          <span>Obra selecionada</span>
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
        <dl className="home-obra-infos">
          <div>
            <dt>Contrato</dt>
            <dd>
              {obra.codigoContrato || "—"}
              {obra.cliente ? ` · ${obra.cliente}` : ""}
            </dd>
          </div>
          <div>
            <dt>Localização</dt>
            <dd>
              {[localizacao, obra.rodovia]
                .filter(Boolean)
                .join(" · ") || "—"}
              <span className="info-coords">
                {obra.latitude !== null &&
                obra.longitude !== null
                  ? `${obra.latitude}, ${obra.longitude}`
                  : "Coordenadas não informadas"}
              </span>
            </dd>
          </div>
          <div>
            <dt>Observações operacionais</dt>
            <dd>{obra.observacoes || "—"}</dd>
          </div>
        </dl>

        <div className="home-obra-chart">
          <ProgressChart
            points={points}
            period={period}
            onPeriodChange={setPeriod}
          />
        </div>
      </div>

      <section
        className="home-obra-evolucao"
        aria-label="Evolução da obra no mapa"
      >
        <header>
          <span className="home-section-index">Evolução no território</span>
          <small>
            O mapa acompanha o mesmo período do gráfico de avanço.
          </small>
        </header>
        <ObraEvolucaoMapa obra={obra} janela={janela} />
      </section>

      <dl className="home-obra-metrics">
        <div>
          <dt>Ocorrências nos últimos 30 dias</dt>
          <dd className="metric-value">{countOcorrencias30d(events)}</dd>
        </div>
        <div>
          <dt>Último RDO</dt>
          <dd>
            {latestRdo
              ? `${latestRdo.dataRdo} · ${
                  latestRdo.statusRdo === "ENVIADO"
                    ? "enviado"
                    : "rascunho"
                }`
              : "—"}
          </dd>
        </div>
        <div>
          <dt>Progresso geral</dt>
          <dd className="metric-value metric-value--brand">
            {progressoGeral === null ? "—" : `${progressoGeral}%`}
          </dd>
        </div>
      </dl>

      <footer className="home-updated-at">
        <span>Atualização local</span>
        {updatedAt ? (
          <time dateTime={obra.updatedAt}>{updatedAt}</time>
        ) : (
          <span>Não registrada</span>
        )}
      </footer>
    </section>
  );
}
