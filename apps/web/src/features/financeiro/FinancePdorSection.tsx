import { useEffect, useRef, useState } from "react";

import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import { AUTH_SESSION_CHANGED_EVENT } from "../auth/authSession";
import { PdorPanel } from "../obras/PdorPanel";
import { recalcularPdor } from "../obras/obrasApi";
import {
  loadPdorRevenueSnapshot,
  type PdorRevenueSnapshot,
} from "./pdorRevenueCacheRepository";

interface FinancePdorSectionProps {
  obraId: string;
  refreshVersion?: number;
}

function coverageLabel(
  coverageCode: PdorRevenueSnapshot["provenance"]["coverageCode"],
): string {
  if (coverageCode === "COMPLETE_ACCEPTED_EXACT") {
    return "Cobertura completa";
  }
  if (coverageCode === "PARTIAL_ACCEPTED_EXACT") {
    return "Cobertura parcial";
  }
  if (coverageCode === "NO_ACCEPTED_EVIDENCE") {
    return "Sem evidência aceita";
  }
  return "Ausência confirmada";
}

function isoDateLabel(value: string | null): string | null {
  if (!value) return null;
  const [year, month, day] = value.split("-");
  return year && month && day ? `${day}/${month}/${year}` : null;
}

function temporalWindowLabel(
  provenance: PdorRevenueSnapshot["provenance"],
): string | null {
  const window = provenance.temporalWindow;
  const referenceDate = isoDateLabel(provenance.referenceDate);
  if (!window) {
    return referenceDate
      ? `Referência do snapshot ${referenceDate}`
      : null;
  }
  const start = isoDateLabel(window.inicioProgramacao);
  const end = isoDateLabel(window.fimProgramacao);
  const scheduleWindow = start && end
    ? `${start} → ${end}`
    : start
      ? `a partir de ${start}`
      : end
        ? `até ${end}`
        : "programação sem limites informados";
  return `Janela do snapshot ${scheduleWindow}${
    referenceDate ? ` · referência ${referenceDate}` : ""
  }`;
}

export function FinancePdorSection({
  obraId,
  refreshVersion = 0,
}: FinancePdorSectionProps) {
  const [snapshot, setSnapshot] = useState<PdorRevenueSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [automaticRefreshVersion, setAutomaticRefreshVersion] = useState(0);
  const requestSequence = useRef(0);
  const windowLabel = snapshot
    ? temporalWindowLabel(snapshot.provenance)
    : null;

  useEffect(() => {
    const requestId = requestSequence.current + 1;
    requestSequence.current = requestId;
    let active = true;
    queueMicrotask(() => {
      if (!active || requestId !== requestSequence.current) return;
      setSnapshot(null);
      setError(null);
      setLoading(true);
      void loadPdorRevenueSnapshot({ obraId })
        .then((loaded) => {
          if (active && requestId === requestSequence.current) {
            setSnapshot(loaded);
          }
        })
        .catch((reason: unknown) => {
          if (active && requestId === requestSequence.current) {
            setSnapshot(null);
            setError(reason instanceof Error
              ? reason.message
              : "Não foi possível carregar a previsão PDOR.");
          }
        })
        .finally(() => {
          if (active && requestId === requestSequence.current) {
            setLoading(false);
          }
        });
    });
    return () => {
      active = false;
    };
  }, [automaticRefreshVersion, obraId, refreshVersion]);

  useEffect(() => {
    const requestRefresh = () => {
      setAutomaticRefreshVersion((version) => version + 1);
    };
    const resetForSession = () => {
      requestSequence.current += 1;
      setSnapshot(null);
      setError(null);
      setLoading(true);
      requestRefresh();
    };
    window.addEventListener(SYNC_COMPLETED_EVENT, requestRefresh);
    window.addEventListener("online", requestRefresh);
    window.addEventListener("offline", requestRefresh);
    window.addEventListener(AUTH_SESSION_CHANGED_EVENT, resetForSession);
    return () => {
      window.removeEventListener(SYNC_COMPLETED_EVENT, requestRefresh);
      window.removeEventListener("online", requestRefresh);
      window.removeEventListener("offline", requestRefresh);
      window.removeEventListener(AUTH_SESSION_CHANGED_EVENT, resetForSession);
    };
  }, []);

  /*
   * Recalcular à mão.
   *
   * <p>O cálculo já era disparado por evento — a cada mudança de RDO —, e essa
   * era a única porta. Só que o snapshot é cache: quando o que ele projetava
   * deixa de existir e nenhum RDO muda depois disso, a projeção velha fica na
   * tela sem nada que a tire de lá. Apagar o último RDO da obra era exatamente
   * esse caso.
   *
   * <p>Recalcular não inventa dado: relê a obra como ela está agora. Se as
   * entradas sumiram, o resultado é "dados insuficientes" — que é a resposta
   * correta, no lugar de um número que já não descreve nada.
   */
  const [recalculando, setRecalculando] = useState(false);
  const [avisoDoRecalculo, setAvisoDoRecalculo] = useState<string | null>(null);

  async function recalcular() {
    setRecalculando(true);
    setAvisoDoRecalculo(null);
    try {
      await recalcularPdor(obraId);
      setAvisoDoRecalculo("Previsão recalculada com os dados de agora.");
      setAutomaticRefreshVersion((version) => version + 1);
    } catch (motivo: unknown) {
      setAvisoDoRecalculo(
        motivo instanceof Error
          ? motivo.message
          : "Não foi possível recalcular a previsão agora.",
      );
    } finally {
      setRecalculando(false);
    }
  }

  return (
    <section className="finance-pdor-section">
      <div className="finance-pdor-acoes">
        <button
          type="button"
          onClick={() => void recalcular()}
          disabled={recalculando || loading}
        >
          {recalculando ? "Recalculando…" : "Recalcular previsão"}
        </button>
        {avisoDoRecalculo ? (
          <span role="status">{avisoDoRecalculo}</span>
        ) : null}
      </div>
      {snapshot ? (
        <div
          className={`finance-revenue-provenance is-${
            snapshot.mode === "ONLINE" ? "online" : "offline"
          }`}
          role="status"
        >
          <strong>
            Fonte: previsão PDOR confirmada pelo servidor ·{" "}
            {snapshot.mode === "ONLINE"
              ? "consulta atual"
              : "disponível offline"}
          </strong>
          <span>
            Atualizado em{" "}
            {new Intl.DateTimeFormat("pt-BR", {
              dateStyle: "short",
              timeStyle: "short",
              timeZone: "America/Sao_Paulo",
            }).format(new Date(snapshot.fetchedAt))}
          </span>
          <span>
            {coverageLabel(snapshot.provenance.coverageCode)} ·{" "}
            {snapshot.provenance.evidenceCount}{" "}
            {snapshot.provenance.evidenceCount === 1
              ? "evidência"
              : "evidências"}
          </span>
          {windowLabel ? <span>{windowLabel}</span> : null}
          {snapshot.provenance.evidenceHighWaterMark !== null ? (
            <span>
              Commit ontológico{" "}
              {snapshot.provenance.evidenceHighWaterMark}
            </span>
          ) : null}
        </div>
      ) : null}
      <PdorPanel
        pdor={snapshot?.pdor ?? null}
        loading={loading}
        error={error}
      />
    </section>
  );
}
