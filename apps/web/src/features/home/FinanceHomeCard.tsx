import { useEffect, useState } from "react";
import { Link } from "react-router";

import { fetchRevenueCapabilities } from "../financeiro/financeRevenueAccessApi";
import { fetchRevenueTrace } from "../financeiro/servicePriceApi";

interface FinanceHomeCardProps {
  obraId: string;
}

export function FinanceHomeCard({ obraId }: FinanceHomeCardProps) {
  const [evidenceCount, setEvidenceCount] = useState(0);
  const [state, setState] = useState<
    "loading" | "ready" | "denied" | "offline" | "error"
  >("loading");
  const [error, setError] = useState("");
  const [reloadTick, setReloadTick] = useState(0);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      if (!navigator.onLine) {
        setEvidenceCount(0);
        setState("offline");
        return;
      }

      setState("loading");
      setError("");
      try {
        const capabilities = await fetchRevenueCapabilities(obraId);
        if (!capabilities.permissoes.includes("FINANCEIRO_VISUALIZAR")) {
          if (!cancelled) {
            setEvidenceCount(0);
            setState("denied");
          }
          return;
        }
        const trace = await fetchRevenueTrace(obraId);
        if (!cancelled) {
          setEvidenceCount(trace.evidenceCount);
          setState("ready");
        }
      } catch (reason: unknown) {
        if (!cancelled) {
          setEvidenceCount(0);
          setError(reason instanceof Error
            ? reason.message
            : "Não foi possível consultar as evidências de receita.");
          setState("error");
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [obraId, reloadTick]);

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
            onClick={() => setReloadTick((tick) => tick + 1)}
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
