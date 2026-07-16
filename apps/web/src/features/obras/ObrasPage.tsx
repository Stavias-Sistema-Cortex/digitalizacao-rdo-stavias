import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";

import { CortexShell } from "../../components/shell/CortexShell";
import type { ObraLocalRecord } from "../../lib/db/db.types";
import {
  filterObrasByChip,
  filterObrasByRodovia,
  filterObrasByUf,
  OBRA_STATUS_CHIPS,
  type ObraStatusChip,
} from "../home/homeFilters";
import { useHomeData } from "../home/useHomeData";
import { memoryHref } from "../home/memory/memoryLocation";
import { useStaviaLauncher } from "../stavia/useStaviaLauncher";
import { getSession, isAlfa } from "../auth/authSession";
import {
  buscarPdorAtual,
  type ObraPdor,
} from "./obrasApi";
import { NovaObraForm } from "./gestao/NovaObraForm";

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
    isLoading,
    reload,
  } = useHomeData();
  const [chip, setChip] =
    useState<ObraStatusChip>("TODAS");
  const [ufFilter, setUfFilter] = useState("");
  const [rodoviaFilter, setRodoviaFilter] = useState("");
  const [pdor, setPdor] = useState<ObraPdor | null>(null);
  const [pdorError, setPdorError] =
    useState<string | null>(null);
  const [isPdorLoading, setIsPdorLoading] =
    useState(false);
  const [showCreateWorksite, setShowCreateWorksite] =
    useState(false);
  const { openStavia, setStaviaContext } =
    useStaviaLauncher();
  const canCreateWorksite = isAlfa(getSession());

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
    let cancelled = false;
    if (!focusedObraId) {
      queueMicrotask(() => {
        if (!cancelled) {
          setPdor(null);
          setPdorError(null);
          setIsPdorLoading(false);
        }
      });
      return () => {
        cancelled = true;
      };
    }

    queueMicrotask(() => {
      if (!cancelled) {
        setIsPdorLoading(true);
        setPdorError(null);
      }
    });

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
          {canCreateWorksite ? (
            <button
              type="button"
              className="obras-create-action"
              onClick={() => setShowCreateWorksite(true)}
            >
              Criar obra
            </button>
          ) : null}
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
                  <div className="obras-detail-actions">
                    <Link
                      to={memoryHref({
                        obraId: focusedObra.id,
                        entityType: "OBRA",
                        entityId: focusedObra.id,
                      })}
                    >
                      Ver na Memória
                    </Link>
                    <button
                      type="button"
                      className="obras-stavia-button"
                      onClick={() =>
                        openStavia({
                          obraId: focusedObra.id,
                        })
                      }
                    >
                      StavIA
                    </button>
                  </div>
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

              </>
            ) : (
              <p className="obras-empty">
                Nenhuma obra disponivel neste dispositivo.
              </p>
            )}
          </section>
        </section>

        {showCreateWorksite ? (
          <div
            className="nova-obra-dialog-backdrop"
            role="presentation"
            onMouseDown={(event) => {
              if (event.currentTarget === event.target) {
                setShowCreateWorksite(false);
              }
            }}
          >
            <section
              className="nova-obra-dialog"
              role="dialog"
              aria-modal="true"
              aria-labelledby="nova-obra-dialog-title"
            >
              <header>
                <div>
                  <h2 id="nova-obra-dialog-title">Criar obra</h2>
                  <p>
                    A obra entra no escopo global e na rastreabilidade
                    operacional assim que for criada.
                  </p>
                </div>
                <button
                  type="button"
                  aria-label="Fechar cadastro de obra"
                  onClick={() => setShowCreateWorksite(false)}
                >
                  ×
                </button>
              </header>
              <NovaObraForm
                onCancel={() => setShowCreateWorksite(false)}
                onCreated={(created) => {
                  setShowCreateWorksite(false);
                  setFocusedObraId(created.id);
                  reload();
                }}
              />
            </section>
          </div>
        ) : null}
      </main>
    </CortexShell>
  );
}
