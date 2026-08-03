import { useEffect, useMemo, useState } from "react";

import { ApiTransportError } from "../../lib/api/apiClient";
import {
  fetchRevenueTraceEvidence,
  type DecimalValue,
  type RevenueTraceEvidence,
  type RevenueTraceRow,
} from "./servicePriceApi";
import { FinanceTraceEvidenceDrawer } from "./FinanceTraceEvidenceDrawer";
import {
  loadRevenueTraceSnapshot,
  type RevenueTraceSnapshot,
} from "./revenueTraceCacheRepository";
import {
  formatCurrencyDecimal,
  formatQuantityDecimal,
  formatScaledInteger,
  sumExactDecimals,
} from "./revenueDecimal";

interface FinanceRevenueTracePageProps {
  obraId: string;
  de?: string;
  ate?: string;
  rows?: readonly RevenueTraceRow[];
  totalRevenue?: DecimalValue;
}

function currency(value: DecimalValue, digits = 2): string {
  return formatCurrencyDecimal(value, digits);
}

function quantity(value: DecimalValue): string {
  return formatQuantityDecimal(value);
}

function coverageLabel(value: string): string {
  if (value === "ACCEPTED_EXACT") return "ACEITA EXATA";
  return value.replaceAll("_", " ");
}

export function FinanceRevenueTracePage({
  obraId,
  de = "",
  ate = "",
  rows: controlledRows,
  totalRevenue: controlledTotalRevenue,
}: FinanceRevenueTracePageProps) {
  const [snapshot, setSnapshot] = useState<RevenueTraceSnapshot | null>(null);
  const [loading, setLoading] = useState(controlledRows === undefined);
  const [error, setError] = useState("");
  const [detail, setDetail] = useState<RevenueTraceEvidence | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState("");

  useEffect(() => {
    if (controlledRows !== undefined) return;
    let cancelled = false;
    queueMicrotask(() => {
      if (cancelled) return;
      setLoading(true);
      setError("");
      setSnapshot(null);
      void loadRevenueTraceSnapshot({ obraId, from: de, to: ate })
        .then((loaded) => {
          if (!cancelled) setSnapshot(loaded);
        })
        .catch((reason: unknown) => {
          if (!cancelled) {
            setError(reason instanceof Error
              ? reason.message
              : "Não foi possível carregar as evidências de receita.");
          }
        })
        .finally(() => {
          if (!cancelled) setLoading(false);
        });
    });
    return () => { cancelled = true; };
  }, [obraId, de, ate, controlledRows]);

  const rows = controlledRows ?? snapshot?.response.rows ?? [];
  const totalResult = useMemo(() => {
    try {
      if (controlledRows !== undefined) {
        const exact = controlledTotalRevenue === undefined
          ? sumExactDecimals(
              controlledRows.map((row) => row.revenue),
              2,
              "revenue",
            )
          : null;
        return {
          text: controlledTotalRevenue === undefined
            ? `R$ ${formatScaledInteger(exact as bigint, 2)}`
            : currency(controlledTotalRevenue),
          error: "",
        };
      }
      if (!snapshot) return { text: "—", error: "" };
      return {
        text: currency(snapshot.response.totalRevenue),
        error: "",
      };
    } catch (reason: unknown) {
      return {
        text: "—",
        error: reason instanceof Error
          ? reason.message
          : "O demonstrativo contém valor monetário inválido.",
      };
    }
  }, [controlledRows, controlledTotalRevenue, snapshot]);
  const visibleError = error || totalResult.error;
  const hasConfirmedData = controlledRows !== undefined || snapshot !== null;

  function openEvidence(row: RevenueTraceRow) {
    setDetail(null);
    setDetailError("");
    if (typeof navigator !== "undefined" && !navigator.onLine) {
      setDetailError(
        "O rastro detalhado está indisponível offline. Reconecte para consultar as relações confirmadas; nenhuma relação foi inventada.",
      );
      setDetailLoading(false);
      return;
    }
    setDetailLoading(true);
    void fetchRevenueTraceEvidence(row.executionId)
      .then(setDetail)
      .catch((reason: unknown) => {
        setDetailError(reason instanceof ApiTransportError
          ? "O rastro detalhado está indisponível offline. Reconecte para consultar as relações confirmadas; nenhuma relação foi inventada."
          : reason instanceof Error
            ? reason.message
            : "Não foi possível abrir a evidência.");
      })
      .finally(() => setDetailLoading(false));
  }

  return (
    <section className="finance-revenue-trace" aria-labelledby="finance-revenue-title">
      <header>
        <div>
          <span>Receita confirmada</span>
          <h2 id="finance-revenue-title">Receita realizada</h2>
          <p>Cada valor abaixo vem de trabalho executado e de um preço versionado preservado.</p>
        </div>
        <div className="finance-revenue-total">
          <span>Total confirmado pelo servidor</span>
          <strong>{totalResult.text}</strong>
          {/*
            Os agregados saem das linhas já carregadas — nenhuma consulta a
            mais. Quem abre a tela lê de onde o total vem sem somar de cabeça:
            quantas evidências, de quantos serviços, em quantos RDOs.
          */}
          <small>
            {rows.length} {rows.length === 1 ? "evidência" : "evidências"}
            {rows.length > 0
              ? ` · ${new Set(rows.map((row) => row.serviceCode)).size} serviço(s) · ${new Set(rows.map((row) => row.rdoNumber)).size} RDO(s)`
              : ""}
          </small>
        </div>
      </header>

      {loading ? <p className="finance-loading" role="status">Lendo evidências aceitas…</p> : null}
      {visibleError ? (
        <p className="finance-error-state" role="alert">{visibleError}</p>
      ) : null}
      {snapshot ? (
        <div
          className={`finance-revenue-provenance is-${snapshot.mode === "ONLINE" ? "online" : "offline"}`}
          role="status"
        >
          <strong>
            Fonte: demonstrativo confirmado pelo servidor ·{" "}
            {snapshot.mode === "ONLINE" ? "consulta atual" : "disponível offline"}
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
            Cobertura completa · aceita exata ·{" "}
            {snapshot.coverage.evidenceCount}{" "}
            {snapshot.coverage.evidenceCount === 1 ? "evidência" : "evidências"}
          </span>
        </div>
      ) : null}
      {!loading && !visibleError && hasConfirmedData && rows.length === 0 ? (
        <div className="finance-empty">
                    <div><h3>Nenhuma receita aceita neste período</h3><p>O total permanece zerado até existir uma execução validada com preço exato.</p></div>
        </div>
      ) : null}

      {!visibleError && rows.length > 0 ? (
        <div className="finance-revenue-table-wrap">
          <table className="finance-revenue-table">
            <thead><tr><th>Data / RDO</th><th>Serviço</th><th>Memória do preço</th><th>Receita</th><th>Estado</th><th><span className="sr-only">Rastro</span></th></tr></thead>
            <tbody>
              {rows.map((row) => (
                <tr key={row.revenueEvidenceId}>
                  <td data-label="Data / RDO"><time dateTime={row.executionDate}>{new Intl.DateTimeFormat("pt-BR").format(new Date(`${row.executionDate}T12:00:00Z`))}</time><strong>{row.rdoNumber}</strong></td>
                  <td data-label="Serviço"><strong>{row.serviceName}</strong><code>{row.serviceCode}</code></td>
                  <td data-label="Memória do preço"><span>{quantity(row.quantity)} × {currency(row.unitPrice, 4)}</span><small>{row.unit} · versão {row.priceVersion}</small></td>
                  <td data-label="Receita" className="is-number"><strong>{currency(row.revenue)}</strong></td>
                  <td data-label="Estado"><span className="finance-revenue-coverage">{coverageLabel(row.coverageCode)}</span></td>
                  <td data-label="Rastro"><button type="button" onClick={() => openEvidence(row)}>Ver rastro</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      <FinanceTraceEvidenceDrawer
        detail={detail}
        loading={detailLoading}
        error={detailError}
        onClose={() => { setDetail(null); setDetailLoading(false); setDetailError(""); }}
      />
    </section>
  );
}
