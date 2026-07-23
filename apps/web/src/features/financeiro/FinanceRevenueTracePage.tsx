import { useEffect, useMemo, useState } from "react";

import {
  fetchRevenueTrace,
  fetchRevenueTraceEvidence,
  type DecimalValue,
  type RevenueTraceEvidence,
  type RevenueTraceRow,
} from "./servicePriceApi";
import { FinanceTraceEvidenceDrawer } from "./FinanceTraceEvidenceDrawer";

interface FinanceRevenueTracePageProps {
  obraId: string;
  de?: string;
  ate?: string;
  rows?: readonly RevenueTraceRow[];
}

function decimal(value: DecimalValue): number {
  const parsed = typeof value === "number" ? value : Number(value);
  return Number.isFinite(parsed) ? parsed : 0;
}

function currency(value: DecimalValue, digits = 2): string {
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
  }).format(decimal(value)).replace(/\u00a0/g, " ");
}

function quantity(value: DecimalValue): string {
  return new Intl.NumberFormat("pt-BR", {
    minimumFractionDigits: 3,
    maximumFractionDigits: 3,
  }).format(decimal(value));
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
}: FinanceRevenueTracePageProps) {
  const [remoteRows, setRemoteRows] = useState<RevenueTraceRow[]>([]);
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
      void fetchRevenueTrace(obraId, de, ate)
        .then((response) => {
          if (!cancelled) setRemoteRows(response.rows);
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

  const rows = controlledRows ?? remoteRows;
  const total = useMemo(
    () => rows.reduce((sum, row) => sum + decimal(row.revenue), 0),
    [rows],
  );

  function openEvidence(row: RevenueTraceRow) {
    setDetail(null);
    setDetailError("");
    setDetailLoading(true);
    void fetchRevenueTraceEvidence(row.executionId)
      .then(setDetail)
      .catch((reason: unknown) => {
        setDetailError(reason instanceof Error
          ? reason.message
          : "Não foi possível abrir a evidência.");
      })
      .finally(() => setDetailLoading(false));
  }

  return (
    <section className="finance-revenue-trace" aria-labelledby="finance-revenue-title">
      <header>
        <div>
          <span>RDO → serviço → preço vigente → receita → evento</span>
          <h2 id="finance-revenue-title">Receita realizada</h2>
          <p>Cada valor abaixo vem de trabalho executado e de um preço versionado preservado.</p>
        </div>
        <div className="finance-revenue-total">
          <span>Total das evidências visíveis</span>
          <strong>{currency(total)}</strong>
          <small>{rows.length} {rows.length === 1 ? "evidência" : "evidências"}</small>
        </div>
      </header>

      {loading ? <p className="finance-loading" role="status">Lendo evidências aceitas…</p> : null}
      {error ? <p className="finance-error-state" role="alert">{error}</p> : null}
      {!loading && !error && rows.length === 0 ? (
        <div className="finance-empty">
          <div className="finance-empty-mark" aria-hidden="true">∅</div>
          <div><h3>Nenhuma receita aceita neste período</h3><p>O total permanece zerado até existir uma execução validada com preço exato.</p></div>
        </div>
      ) : null}

      {rows.length > 0 ? (
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
