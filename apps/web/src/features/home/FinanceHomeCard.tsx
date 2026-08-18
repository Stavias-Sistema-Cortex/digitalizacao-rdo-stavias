import { useEffect, useRef, useState } from "react";
import { Link } from "react-router";

import { responseErrorMessage } from "../../lib/api/apiClient";
import { SYNC_COMPLETED_EVENT } from "../../lib/sync/syncEvents";
import { AUTH_SESSION_CHANGED_EVENT } from "../auth/authSession";
import { fetchRevenueCapabilities } from "../financeiro/financeRevenueAccessApi";
import { fetchRevenueTrace } from "../financeiro/servicePriceApi";

interface FinanceHomeCardProps {
  obraId: string;
}

const AUTOMATIC_RETRY_DELAYS_MS = [250, 500, 1_000] as const;

export function FinanceHomeCard({ obraId }: FinanceHomeCardProps) {
  const [evidenceCount, setEvidenceCount] = useState(0);
  const [state, setState] = useState<
    "loading" | "ready" | "denied" | "offline" | "error"
  >("loading");
  const [error, setError] = useState("");
  const [reloadTick, setReloadTick] = useState(0);
  const requestSequence = useRef(0);
  const automaticRetryAttempt = useRef(0);
  const retryTimeout = useRef<number | null>(null);

  useEffect(() => {
    const requestId = requestSequence.current + 1;
    requestSequence.current = requestId;
    let active = true;

    async function load() {
      if (!navigator.onLine) {
        if (active && requestId === requestSequence.current) {
          automaticRetryAttempt.current = 0;
          setEvidenceCount(0);
          setState("offline");
        }
        return;
      }

      setState("loading");
      setError("");
      try {
        const capabilities = await fetchRevenueCapabilities(obraId);
        if (!active || requestId !== requestSequence.current) return;
        if (!capabilities.permissoes.includes("FINANCEIRO_VISUALIZAR")) {
          if (active && requestId === requestSequence.current) {
            automaticRetryAttempt.current = 0;
            setEvidenceCount(0);
            setState("denied");
          }
          return;
        }
        const trace = await fetchRevenueTrace(obraId);
        if (active && requestId === requestSequence.current) {
          automaticRetryAttempt.current = 0;
          setEvidenceCount(trace.evidenceCount);
          setState("ready");
        }
      } catch (reason: unknown) {
        if (active && requestId === requestSequence.current) {
          const retryIndex = automaticRetryAttempt.current;
          if (
            navigator.onLine &&
            retryIndex < AUTOMATIC_RETRY_DELAYS_MS.length
          ) {
            automaticRetryAttempt.current += 1;
            setError("");
            setState("loading");
            retryTimeout.current = window.setTimeout(() => {
              if (active && requestId === requestSequence.current) {
                setReloadTick((tick) => tick + 1);
              }
            }, AUTOMATIC_RETRY_DELAYS_MS[retryIndex]);
            return;
          }
          setError(responseErrorMessage(
            reason instanceof Error
              ? reason.message
              : "Não foi possível consultar as evidências de receita.",
            502,
          ));
          setState("error");
        }
      }
    }

    void load();
    return () => {
      active = false;
      if (retryTimeout.current !== null) {
        window.clearTimeout(retryTimeout.current);
        retryTimeout.current = null;
      }
    };
  }, [obraId, reloadTick]);

  useEffect(() => {
    const requestRefresh = () => {
      automaticRetryAttempt.current = 0;
      setReloadTick((tick) => tick + 1);
    };
    const resetForSession = () => {
      requestSequence.current += 1;
      setEvidenceCount(0);
      setError("");
      setState("loading");
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

  const traceLink = (
    <Link to={`/financeiro?obra=${encodeURIComponent(obraId)}&secao=receita`}>
      Abrir rastreio de receita
    </Link>
  );

  return (
    <section className="home-card home-finance-card">
      <h3>
        Receita da obra
        {state === "ready" ? (
          <span>
            {evidenceCount} {evidenceCount === 1 ? "evidência" : "evidências"}
          </span>
        ) : null}
      </h3>
      {state === "loading" ? (
        <p className="home-card-muted" role="status">
          Consultando evidências aceitas…
        </p>
      ) : state === "denied" ? (
        <p className="home-card-muted">
          Seu perfil não possui acesso à receita desta obra.
        </p>
      ) : state === "offline" ? (
        <>
          <p className="home-card-muted">
            Abra o rastreio para consultar a última evidência confirmada
            armazenada neste dispositivo.
          </p>
          {traceLink}
        </>
      ) : state === "error" ? (
        <div className="home-finance-error" role="alert">
          <p>{error}</p>
          <button
            type="button"
            onClick={() => {
              automaticRetryAttempt.current = 0;
              setReloadTick((tick) => tick + 1);
            }}
          >
            Tentar novamente
          </button>
        </div>
      ) : (
        <>
          <p className="home-card-muted">
            {evidenceCount === 0
              ? "Nenhuma evidência de receita aceita nesta obra."
              : `${evidenceCount} ${evidenceCount === 1
                ? "evidência aceita"
                : "evidências aceitas"}`}
          </p>
          {traceLink}
        </>
      )}
    </section>
  );
}
