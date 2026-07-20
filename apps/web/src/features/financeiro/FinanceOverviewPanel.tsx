import type {
  FinanceLedgerEntry,
  FinanceOverview,
} from "./financeiro.types";
import { formatDate, formatMoney, statusTone } from "./financeiroView";

interface FinanceOverviewPanelProps {
  overview: FinanceOverview | null;
  ledger: FinanceLedgerEntry[];
}

export function FinanceOverviewPanel({
  overview,
  ledger,
}: FinanceOverviewPanelProps) {
  if (!overview?.possuiDados) {
    return (
      <section className="finance-empty" aria-labelledby="finance-empty-title">
        <div className="finance-empty-mark" aria-hidden="true">↳</div>
        <div>
          <h2 id="finance-empty-title">A obra ainda não tem movimentos financeiros</h2>
          <p>
            Cadastre uma compra, nota fiscal ou lançamento. O resumo será
            calculado pelo servidor assim que houver dados persistidos.
          </p>
        </div>
      </section>
    );
  }

  const metrics = [
    ["Previsto", overview.previsto],
    ["Comprometido", overview.comprometido],
    ["Liquidado", overview.liquidado],
    ["Em aberto", overview.aberto],
    ["Vencido", overview.vencido],
  ] as const;
  const dueEntries = ledger
    .filter((entry) => entry.valorAberto > 0)
    .sort((left, right) => left.vencimentoEm.localeCompare(right.vencimentoEm))
    .slice(0, 6);

  return (
    <div className="finance-overview-layout">
      <section className="finance-ledger-rail" aria-label="Resumo financeiro real">
        <header>
          <div>
            <p>Referência</p>
            <strong>{formatDate(overview.referencia)}</strong>
          </div>
          <span>{overview.quantidadeLancamentos} lançamentos</span>
        </header>
        <div className="finance-ledger-rail__values">
          {metrics.map(([label, value]) => (
            <div key={label} className={label === "Vencido" ? "is-alert" : ""}>
              <span>{label}</span>
              <strong>
                {value === null ? "Sem vínculo" : formatMoney(value, overview.moeda)}
              </strong>
            </div>
          ))}
        </div>
        <footer>
          <span>A pagar {formatMoney(overview.aPagar, overview.moeda)}</span>
          <span>A receber {formatMoney(overview.aReceber, overview.moeda)}</span>
          {!overview.possuiOrcamentoVinculado && (
            <span className="finance-inline-note">
              Comparação orçamentária indisponível: nenhum vínculo real.
            </span>
          )}
        </footer>
      </section>

      <section className="finance-due-list">
        <div className="finance-section-heading">
          <div>
            <p className="finance-kicker">Próximos movimentos</p>
            <h2>Agenda de vencimentos</h2>
          </div>
          <span>{overview.quantidadeVencidos} vencidos</span>
        </div>
        {dueEntries.length === 0 ? (
          <p className="finance-row-empty">Nenhum valor em aberto neste filtro.</p>
        ) : (
          <ul>
            {dueEntries.map((entry) => (
              <li key={entry.id}>
                <span className={`finance-status-dot is-${statusTone(
                  entry.vencido ? "VENCIDO" : entry.statusCodigo,
                )}`} />
                <div>
                  <strong>{entry.descricao}</strong>
                  <span>{entry.fornecedorNome || entry.numeroDocumento || "Sem fornecedor"}</span>
                </div>
                <time dateTime={entry.vencimentoEm}>{formatDate(entry.vencimentoEm)}</time>
                <strong>{formatMoney(entry.valorAberto, entry.moeda)}</strong>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
