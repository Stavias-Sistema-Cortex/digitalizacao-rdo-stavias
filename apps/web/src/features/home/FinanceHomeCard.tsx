import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

import {
  buscarCapacidadesFinanceiras,
  buscarVisaoGeral,
} from "../financeiro/financeiroApi";
import { EMPTY_FINANCE_FILTERS } from "../financeiro/financeiroFilters";
import type { FinanceOverview } from "../financeiro/financeiro.types";
import { formatMoney } from "../financeiro/financeiroView";

interface FinanceHomeCardProps {
  obraId: string;
}

export function FinanceHomeCard({ obraId }: FinanceHomeCardProps) {
  const [overview, setOverview] = useState<FinanceOverview | null>(null);
  const [state, setState] = useState<
    "loading" | "ready" | "denied" | "offline" | "error"
  >("loading");
  const [error, setError] = useState("");
  const [reloadTick, setReloadTick] = useState(0);

  useEffect(() => {
    let cancelled = false;

    async function load() {
      if (!navigator.onLine) {
        setOverview(null);
        setState("offline");
        return;
      }

      setState("loading");
      setError("");
      try {
        const capabilities = await buscarCapacidadesFinanceiras(obraId);
        if (!capabilities.permissoes.includes("FINANCEIRO_VISUALIZAR")) {
          if (!cancelled) {
            setOverview(null);
            setState("denied");
          }
          return;
        }
        const result = await buscarVisaoGeral({
          ...EMPTY_FINANCE_FILTERS,
          obraId,
        });
        if (!cancelled) {
          setOverview(result);
          setState("ready");
        }
      } catch (reason: unknown) {
        if (!cancelled) {
          setOverview(null);
          setError(reason instanceof Error
            ? reason.message
            : "Não foi possível consultar o financeiro.");
          setState("error");
        }
      }
    }

    void load();
    return () => {
      cancelled = true;
    };
  }, [obraId, reloadTick]);

  return (
    <section className="home-card home-finance-card">
      <h3>
        Financeiro
        {state === "ready" && overview?.possuiDados ? (
          <span>{overview.quantidadeLancamentos} lançamento(s)</span>
        ) : null}
      </h3>
      {state === "loading" ? (
        <p className="home-card-muted" role="status">Consultando o servidor…</p>
      ) : state === "denied" ? (
        <p className="home-card-muted">Seu perfil não possui acesso financeiro nesta obra.</p>
      ) : state === "offline" ? (
        <p className="home-card-muted">O resumo financeiro exige conexão; nenhum total foi estimado localmente.</p>
      ) : state === "error" ? (
        <div className="home-finance-error" role="alert">
          <p>{error}</p>
          <button type="button" onClick={() => setReloadTick((tick) => tick + 1)}>Tentar novamente</button>
        </div>
      ) : overview && overview.possuiDados ? (
        <>
          <dl className="home-finance-totals">
            <div><dt>Em aberto</dt><dd>{formatMoney(overview.aberto, overview.moeda)}</dd></div>
            <div><dt>Vencido</dt><dd className={overview.vencido > 0 ? "is-alert" : ""}>{formatMoney(overview.vencido, overview.moeda)}</dd></div>
            <div><dt>A receber</dt><dd>{formatMoney(overview.aReceber, overview.moeda)}</dd></div>
          </dl>
          <Link to={`/financeiro?obra=${encodeURIComponent(obraId)}&secao=visao-geral`}>Abrir gestão financeira</Link>
        </>
      ) : (
        <>
          <p className="home-card-muted">Nenhum lançamento financeiro encontrado para esta obra.</p>
          <Link to={`/financeiro?obra=${encodeURIComponent(obraId)}&secao=visao-geral`}>Abrir gestão financeira</Link>
        </>
      )}
    </section>
  );
}
