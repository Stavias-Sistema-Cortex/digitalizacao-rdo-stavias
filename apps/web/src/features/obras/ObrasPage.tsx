import { useEffect, useMemo, useState } from "react";

import { CortexShell } from "../../components/shell/CortexShell";
import type {
  ObraLocalRecord,
  OperationalEventRecord,
} from "../../lib/db/db.types";
import {
  filterObrasByChip,
  filterObrasByRodovia,
  filterObrasByUf,
  OBRA_STATUS_CHIPS,
  type ObraStatusChip,
} from "../home/homeFilters";
import { useHomeData } from "../home/useHomeData";
import { StaviaPanel } from "../stavia/StaviaPanel";
import {
  buscarPdorAtual,
  buscarTimelineObra,
  type ObraPdor,
  type ObraTimelineEvent,
} from "./obrasApi";

const TRACE_KEYS = [
  "codigoContrato",
  "nome",
  "cliente",
  "cidade",
  "uf",
  "rodovia",
  "status",
  "observacoes",
];

function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function formatCurrency(value: number | null): string {
  if (value === null) {
    return "-";
  }

  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 0,
  }).format(value);
}

function formatDateOnly(value: string | null): string {
  if (!value) {
    return "";
  }

  const date = new Date(
    value.includes("T") ? value : `${value}T00:00:00`,
  );

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
  }).format(date);
}

function formatPercent(value: number | null): string {
  if (value === null) {
    return "-";
  }

  return new Intl.NumberFormat("pt-BR", {
    style: "percent",
    maximumFractionDigits: 1,
  }).format(value);
}

function riskClass(risco: string | null): string {
  const normalized = (risco ?? "").toUpperCase();

  if (normalized === "CRITICAL" || normalized === "HIGH") {
    return "obras-pdor-risk obras-pdor-risk--alto";
  }

  if (normalized === "MODERATE") {
    return "obras-pdor-risk obras-pdor-risk--medio";
  }

  if (normalized === "LOW") {
    return "obras-pdor-risk obras-pdor-risk--baixo";
  }

  return "obras-pdor-risk";
}

function payloadSummary(
  payload: Record<string, unknown>,
): string {
  return TRACE_KEYS
    .map((key) => {
      const value = payload[key];

      if (
        value === null ||
        value === undefined ||
        value === ""
      ) {
        return null;
      }

      return `${key}: ${String(value)}`;
    })
    .filter((value): value is string => Boolean(value))
    .join(" · ");
}

function localEventToTrace(
  event: OperationalEventRecord,
): ObraTimelineEvent {
  return {
    id: event.id,
    commitSeq: null,
    type: event.type,
    principalEntityType: event.principalEntity.tipo,
    principalEntityId: event.principalEntity.id,
    obraId: event.obraId,
    occurredAt: event.occurredAt,
    origin: event.origin,
    syncStatus: event.syncStatus,
    payload: event.payload,
  };
}

function obraSubtitle(obra: ObraLocalRecord): string {
  return [
    obra.codigoContrato,
    obra.cliente,
    [obra.cidade, obra.uf].filter(Boolean).join("/"),
    obra.rodovia,
  ]
    .filter(Boolean)
    .join(" · ");
}

export function ObrasPage() {
  const {
    obras,
    focusedObra,
    focusedObraId,
    setFocusedObraId,
    events,
    isLoading,
    reload,
  } = useHomeData();
  const [chip, setChip] =
    useState<ObraStatusChip>("TODAS");
  const [ufFilter, setUfFilter] = useState("");
  const [rodoviaFilter, setRodoviaFilter] = useState("");
  const [timeline, setTimeline] = useState<
    ObraTimelineEvent[]
  >([]);
  const [timelineError, setTimelineError] =
    useState<string | null>(null);
  const [isTimelineLoading, setIsTimelineLoading] =
    useState(false);
  const [pdor, setPdor] = useState<ObraPdor | null>(null);
  const [pdorError, setPdorError] =
    useState<string | null>(null);
  const [isPdorLoading, setIsPdorLoading] =
    useState(false);
  const [isStaviaOpen, setIsStaviaOpen] = useState(false);

  const ufs = useMemo(
    () =>
      [...new Set(obras.map((obra) => obra.uf))].filter(
        (uf): uf is string => Boolean(uf),
      ),
    [obras],
  );

  const rodovias = useMemo(
    () =>
      [
        ...new Set(obras.map((obra) => obra.rodovia)),
      ].filter((rodovia): rodovia is string =>
        Boolean(rodovia),
      ),
    [obras],
  );

  const filteredObras = useMemo(
    () =>
      filterObrasByRodovia(
        filterObrasByUf(
          filterObrasByChip(obras, chip),
          ufFilter,
        ),
        rodoviaFilter,
      ),
    [obras, chip, ufFilter, rodoviaFilter],
  );

  useEffect(() => {
    if (!focusedObraId) {
      setTimeline([]);
      setTimelineError(null);
      return;
    }

    let cancelled = false;
    setIsTimelineLoading(true);
    setTimelineError(null);

    buscarTimelineObra(focusedObraId)
      .then((items) => {
        if (!cancelled) {
          setTimeline(items);
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setTimeline([]);
          setTimelineError(
            error instanceof Error
              ? error.message
              : "Timeline indisponivel.",
          );
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsTimelineLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [focusedObraId]);

  useEffect(() => {
    if (!focusedObraId) {
      setPdor(null);
      setPdorError(null);
      return;
    }

    let cancelled = false;
    setIsPdorLoading(true);
    setPdorError(null);

    buscarPdorAtual(focusedObraId)
      .then((result) => {
        if (!cancelled) {
          setPdor(result);
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setPdor(null);
          setPdorError(
            error instanceof Error
              ? error.message
              : "Previsão de receita indisponível.",
          );
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsPdorLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [focusedObraId]);

  const localTrace = useMemo(
    () => events.map(localEventToTrace),
    [events],
  );
  const traceEvents =
    timeline.length > 0 ? timeline : localTrace;
  const traceSource =
    timeline.length > 0
      ? "Cortex online"
      : localTrace.length > 0
        ? "Cortex local"
        : "Sem eventos";

  return (
    <CortexShell
      active="obras"
      onRefresh={reload}
      isRefreshing={isLoading}
    >
      <main className="obras-page">
        <header className="obras-topbar">
          <div>
            <p className="eyebrow">Ontologia operacional</p>
            <h1>Obras</h1>
          </div>
          <div
            className="home-chips"
            role="group"
            aria-label="Filtrar obras por status"
          >
            {OBRA_STATUS_CHIPS.map((option) => (
              <button
                key={option.value}
                type="button"
                className={
                  option.value === chip
                    ? "chip chip--active"
                    : "chip"
                }
                onClick={() => setChip(option.value)}
              >
                {option.label}
              </button>
            ))}
          </div>
          <div className="home-uf-filter">
            <span>Filtrar por:</span>
            <select
              value={ufFilter}
              aria-label="Filtrar por UF"
              onChange={(event) =>
                setUfFilter(event.target.value)
              }
            >
              <option value="">UF: todas</option>
              {ufs.map((uf) => (
                <option key={uf} value={uf}>
                  {uf}
                </option>
              ))}
            </select>
            <select
              value={rodoviaFilter}
              aria-label="Filtrar por rodovia"
              onChange={(event) =>
                setRodoviaFilter(event.target.value)
              }
            >
              <option value="">Rodovia: todas</option>
              {rodovias.map((rodovia) => (
                <option key={rodovia} value={rodovia}>
                  {rodovia}
                </option>
              ))}
            </select>
          </div>
        </header>

        <section className="obras-workspace">
          <aside
            className="obras-list"
            aria-label="Obras relacionadas"
          >
            {isLoading && obras.length === 0 ? (
              <p className="obras-empty">Carregando obras...</p>
            ) : filteredObras.length === 0 ? (
              <p className="obras-empty">
                Nenhuma obra encontrada.
              </p>
            ) : (
              filteredObras.map((obra) => (
                <button
                  key={obra.id}
                  type="button"
                  className={
                    obra.id === focusedObra?.id
                      ? "obras-list-item active"
                      : "obras-list-item"
                  }
                  onClick={() => setFocusedObraId(obra.id)}
                >
                  <span className="obras-list-title">
                    {obra.nome}
                  </span>
                  <span className="obras-list-meta">
                    {obraSubtitle(obra) || obra.id}
                  </span>
                </button>
              ))
            )}
          </aside>

          <section className="obras-detail">
            {focusedObra ? (
              <>
                <div className="obras-detail-header">
                  <div>
                    <span className="home-obra-pill">
                      {focusedObra.status}
                    </span>
                    <h2>{focusedObra.nome}</h2>
                    <p>{obraSubtitle(focusedObra) || "-"}</p>
                  </div>
                  <button
                    type="button"
                    className="obras-stavia-button"
                    onClick={() => setIsStaviaOpen(true)}
                  >
                    StavIA
                  </button>
                </div>

                <dl className="obras-facts">
                  <div>
                    <dt>Contrato</dt>
                    <dd>
                      {focusedObra.codigoContrato || "-"}
                    </dd>
                  </div>
                  <div>
                    <dt>Valor contratual</dt>
                    <dd>
                      {formatCurrency(
                        focusedObra.valorContratual,
                      )}
                    </dd>
                  </div>
                  <div>
                    <dt>Localizacao</dt>
                    <dd>
                      {[focusedObra.cidade, focusedObra.uf]
                        .filter(Boolean)
                        .join("/") || "-"}
                    </dd>
                  </div>
                  <div>
                    <dt>Rodovia</dt>
                    <dd>{focusedObra.rodovia || "-"}</dd>
                  </div>
                  <div>
                    <dt>Coordenadas</dt>
                    <dd>
                      {focusedObra.latitude !== null &&
                      focusedObra.longitude !== null
                        ? `${focusedObra.latitude}, ${focusedObra.longitude}`
                        : "-"}
                    </dd>
                  </div>
                  <div>
                    <dt>Atualizado em</dt>
                    <dd>
                      {formatDateTime(focusedObra.updatedAt) ||
                        "-"}
                    </dd>
                  </div>
                </dl>

                <section
                  className="obras-pdor"
                  aria-label="Previsão de receita PDOR"
                >
                  <div className="obras-pdor-header">
                    <div>
                      <h3>Previsão de receita · PDOR</h3>
                      <span>
                        {pdor?.dataReferencia
                          ? `Referência ${formatDateOnly(pdor.dataReferencia)}`
                          : "Calculado a partir dos RDOs da obra"}
                      </span>
                    </div>
                    {pdor?.riscoLabel ? (
                      <span className={riskClass(pdor.risco)}>
                        {pdor.riscoLabel}
                      </span>
                    ) : null}
                  </div>

                  {isPdorLoading ? (
                    <p className="obras-pdor-note">
                      Consultando previsão de receita...
                    </p>
                  ) : pdorError ? (
                    <p className="obras-pdor-note">
                      {pdorError}
                    </p>
                  ) : !pdor ? (
                    <p className="obras-pdor-note">
                      Nenhum cálculo PDOR registrado ainda. O
                      próximo RDO sincronizado dispara o cálculo
                      automaticamente.
                    </p>
                  ) : pdor.statusExecucao !== "SUCCESS" ? (
                    <div className="obras-pdor-insufficient">
                      <strong>
                        {pdor.statusExecucaoLabel ??
                          pdor.statusExecucao}
                      </strong>
                      <p>
                        {pdor.erroExecucao ??
                          "O PDOR não pôde ser calculado com os dados atuais."}
                      </p>
                    </div>
                  ) : (
                    <>
                      <dl className="obras-pdor-grid">
                        <div className="obras-pdor-main">
                          <dt>Receita prevista final</dt>
                          <dd>
                            {formatCurrency(
                              pdor.receitaPrevistaFinal ??
                                pdor.p50,
                            )}
                          </dd>
                          <dd className="obras-pdor-range">
                            Faixa {formatCurrency(pdor.p10)} a{" "}
                            {formatCurrency(pdor.p95)} · P50{" "}
                            {formatCurrency(pdor.p50)}
                          </dd>
                        </div>
                        <div>
                          <dt>Risco de ficar abaixo do contrato</dt>
                          <dd>
                            {formatPercent(
                              pdor.probabilidadeAbaixoContrato,
                            )}
                          </dd>
                        </div>
                        <div>
                          <dt>Confiança do cálculo</dt>
                          <dd>{formatPercent(pdor.confianca)}</dd>
                        </div>
                        <div>
                          <dt>Calibração</dt>
                          <dd>
                            {pdor.calibracaoLabel ??
                              pdor.calibracao ??
                              "-"}
                          </dd>
                        </div>
                      </dl>

                      {pdor.drivers.length > 0 ? (
                        <ul className="obras-pdor-drivers">
                          {pdor.drivers
                            .slice(0, 3)
                            .map((driver) => (
                              <li key={driver.code || driver.description}>
                                <strong>
                                  {driver.description}
                                </strong>
                                {driver.evidence ? (
                                  <span>{driver.evidence}</span>
                                ) : null}
                              </li>
                            ))}
                        </ul>
                      ) : null}
                    </>
                  )}
                </section>

                <section className="obras-trace">
                  <div className="obras-trace-header">
                    <div>
                      <h3>Rastreabilidade Cortex</h3>
                      <span>{traceSource}</span>
                    </div>
                    <span className="obras-trace-status">
                      {isTimelineLoading
                        ? "consultando"
                        : timelineError
                          ? "local"
                          : "sincronizado"}
                    </span>
                  </div>

                  {timelineError ? (
                    <p className="obras-trace-note">
                      {timelineError}
                    </p>
                  ) : null}

                  {traceEvents.length === 0 ? (
                    <p className="obras-empty">
                      Nenhum evento operacional registrado.
                    </p>
                  ) : (
                    <ol className="obras-trace-list">
                      {traceEvents.map((event) => (
                        <li key={event.id}>
                          <div>
                            <strong>{event.type}</strong>
                            <span>
                              {formatDateTime(
                                event.occurredAt,
                              ) || "-"}
                            </span>
                          </div>
                          <p>
                            {payloadSummary(event.payload) ||
                              event.principalEntityId}
                          </p>
                          <small>
                            {[
                              event.origin,
                              event.syncStatus,
                              event.commitSeq === null
                                ? null
                                : `commit ${event.commitSeq}`,
                            ]
                              .filter(Boolean)
                              .join(" · ")}
                          </small>
                        </li>
                      ))}
                    </ol>
                  )}
                </section>
              </>
            ) : (
              <p className="obras-empty">
                Nenhuma obra disponivel neste dispositivo.
              </p>
            )}
          </section>
        </section>
      </main>

      {isStaviaOpen ? (
        <StaviaPanel
          key={`stavia-obras:${focusedObra?.id ?? ""}`}
          variant="floating"
          isOpen={isStaviaOpen}
          onOpenChange={setIsStaviaOpen}
          initialObraId={focusedObra?.id ?? ""}
        />
      ) : null}
    </CortexShell>
  );
}
