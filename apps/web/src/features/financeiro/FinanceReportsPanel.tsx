import { useState } from "react";

import { exportarRelatorio } from "./financeiroApi";
import type {
  FinanceFilters,
  FinanceOverview,
  FinanceReportRow,
} from "./financeiro.types";
import { formatMoney } from "./financeiroView";

export function FinanceReportsPanel({
  filters,
  overview,
  rows,
  reportGroup,
  onReportGroupChange,
}: {
  filters: FinanceFilters;
  overview: FinanceOverview | null;
  rows: FinanceReportRow[];
  reportGroup: string;
  onReportGroupChange: (group: string) => void;
}) {
  const [exporting, setExporting] = useState(false);
  const [error, setError] = useState("");
  const maximum = Math.max(0, ...rows.map((row) => row.valorLiquido));

  async function exportCsv() {
    setExporting(true);
    setError("");
    try {
      const blob = await exportarRelatorio(filters, reportGroup);
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = "relatorio-financeiro.csv";
      link.click();
      URL.revokeObjectURL(url);
    } catch (reason: unknown) {
      setError(reason instanceof Error ? reason.message : "Não foi possível exportar o relatório.");
    } finally {
      setExporting(false);
    }
  }

  return (
    <section className="finance-workspace-section">
      <div className="finance-section-heading">
        <div>
          <p className="finance-kicker">Leitura gerencial</p>
          <h2>Relatórios</h2>
          <p>A tabela, o gráfico e o CSV usam a mesma consulta escopada.</p>
        </div>
        <div className="finance-heading-actions">
          <label className="finance-inline-select">
            Agrupar por
            <select value={reportGroup} onChange={(event) => onReportGroupChange(event.target.value)}>
              <option value="CENTRO_CUSTO">Centro de custo</option>
              <option value="FORNECEDOR">Fornecedor</option>
              <option value="CATEGORIA">Categoria</option>
              <option value="RESPONSAVEL">Responsável</option>
            </select>
          </label>
          <button type="button" className="finance-secondary-action" disabled={exporting || rows.length === 0} onClick={() => void exportCsv()}>
            {exporting ? "Exportando…" : "Exportar CSV"}
          </button>
        </div>
      </div>

      {!overview?.possuiDados || rows.length === 0 ? (
        <div className="finance-row-empty">
          O servidor não retornou séries para este filtro. Nenhum gráfico sintético foi exibido.
        </div>
      ) : (
        <div className="finance-report-layout">
          <section className="finance-report-chart" aria-label="Valores líquidos por grupo">
            {rows.map((row) => (
              <div key={`${row.grupoId}-${row.moeda}`}>
                <header><span>{row.grupoNome}</span><strong>{formatMoney(row.valorLiquido, row.moeda)}</strong></header>
                <div><span style={{ width: `${maximum > 0 ? (row.valorLiquido / maximum) * 100 : 0}%` }} /></div>
              </div>
            ))}
          </section>
          <div className="finance-table-wrap">
            <table className="finance-table">
              <thead><tr><th>Grupo</th><th className="is-number">Líquido</th><th className="is-number">Liquidado</th><th className="is-number">Aberto</th><th className="is-number">Itens</th></tr></thead>
              <tbody>{rows.map((row) => (
                <tr key={`${row.grupoId}-${row.moeda}`}>
                  <td data-label="Grupo"><strong>{row.grupoNome}</strong></td>
                  <td data-label="Líquido" className="is-number">{formatMoney(row.valorLiquido, row.moeda)}</td>
                  <td data-label="Liquidado" className="is-number">{formatMoney(row.valorLiquidado, row.moeda)}</td>
                  <td data-label="Aberto" className="is-number">{formatMoney(row.valorAberto, row.moeda)}</td>
                  <td data-label="Itens" className="is-number">{row.quantidade}</td>
                </tr>
              ))}</tbody>
            </table>
          </div>
        </div>
      )}
      {error ? <p className="finance-form-error" role="alert">{error}</p> : null}
    </section>
  );
}
