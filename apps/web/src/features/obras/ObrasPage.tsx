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
import { useStaviaLauncher } from "../stavia/useStaviaLauncher";
import {
  buscarPdorAtual,
  buscarTimelineObra,
  type ObraPdor,
  type ObraTimelineEvent,
} from "./obrasApi";
import { OperationalMap } from "./map/OperationalMap";
import { PdorPanel } from "./PdorPanel";

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
  const [timelineResult, setTimelineResult] = useState<{
    obraId: string;
    items: ObraTimelineEvent[];
    error: string | null;
  } | null>(null);
  const [pdorResult, setPdorResult] = useState<{
    obraId: string;
    value: ObraPdor | null;
    error: string | null;
  } | null>(null);
  const { openStavia, setStaviaContext } =
    useStaviaLauncher();

  useEffect(() => {
    setStaviaContext({ obraId: focusedObra?.id ?? "" });
  }, [focusedObra?.id, setStaviaContext]);

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
      return;
    }

    let cancelled = false;

    buscarTimelineObra(focusedObraId)
      .then((items) => {
        if (!cancelled) {
          setTimelineResult({
            obraId: focusedObraId,
            items,
            error: null,
          });
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setTimelineResult({
            obraId: focusedObraId,
            items: [],
            error:
              error instanceof Error
                ? error.message
                : "Timeline indisponivel.",
          });
        }
      });

    return () => {
      cancelled = true;
    };
  }, [focusedObraId]);

  useEffect(() => {
    if (!focusedObraId) {
      return;
    }

    let cancelled = false;

    buscarPdorAtual(focusedObraId)
      .then((result) => {
        if (!cancelled) {
          setPdorResult({
            obraId: focusedObraId,
            value: result,
            error: null,
          });
        }
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setPdorResult({
            obraId: focusedObraId,
            value: null,
            error:
              error instanceof Error
                ? error.message
                : "Previsão de receita indisponível.",
          });
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
  const timeline =
    timelineResult?.obraId === focusedObraId
      ? timelineResult.items
      : [];
  const timelineError =
    timelineResult?.obraId === focusedObraId
      ? timelineResult.error
      : null;
  const isTimelineLoading =
    Boolean(focusedObraId) &&
    timelineResult?.obraId !== focusedObraId;
  const pdor =
    pdorResult?.obraId === focusedObraId
      ? pdorResult.value
      : null;
  const pdorError =
    pdorResult?.obraId === focusedObraId
      ? pdorResult.error
      : null;
  const isPdorLoading =
    Boolean(focusedObraId) &&
    pdorResult?.obraId !== focusedObraId;
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
                    onClick={() =>
                      openStavia({
                        obraId: focusedObra?.id ?? "",
                      })
                    }
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

                <OperationalMap
                  key={focusedObra.id}
                  obra={{
                    id: focusedObra.id,
                    nome: focusedObra.nome,
                    latitude: focusedObra.latitude,
                    longitude: focusedObra.longitude,
                  }}
                />

                <PdorPanel
                  pdor={pdor}
                  loading={isPdorLoading}
                  error={pdorError}
                />

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
    </CortexShell>
  );
}
