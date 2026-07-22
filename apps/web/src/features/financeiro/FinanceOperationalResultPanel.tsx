import type { FinanceOperationalResult } from "./financeiro.types";
import { formatMoney } from "./financeiroView";

export function FinanceOperationalResultPanel({ result, error }: {
  result: FinanceOperationalResult | null;
  error: string;
}) {
  if (error) return <section className="finance-error-state"><span>{error}</span></section>;
  if (!result || result.servicos.length === 0) return <section className="finance-empty"><div><h2>Sem produção de RDO no período</h2><p>Quando os serviços forem registrados nos RDOs, o resultado operacional aparecerá aqui.</p></div></section>;
  const pdor = result.pdor;
  return <section className="finance-operational-result" aria-label="Resultado operacional dos RDOs">
    <div className="finance-section-heading"><div><p className="finance-kicker">Produção da obra</p><h2>Resultado operacional</h2><p>Receita estimada pelos serviços executados nos RDOs; não representa faturamento ou caixa.</p></div></div>
    <div className="finance-operational-metrics">
      <Metric label="Produção realizada" value={String(result.producaoRealizada)} />
      <Metric label="Receita operacional estimada" value={formatMoney(result.receitaOperacionalEstimada, "BRL")} />
      <Metric label="Custo realizado" value={formatMoney(result.custoRealizado, "BRL")} />
      <Metric label="Margem atual" value={formatMoney(result.margemAtual, "BRL")} />
    </div>
    <div className="finance-operational-states"><span>Medida {formatMoney(result.receitaMedida, "BRL")}</span><span>Aprovada {formatMoney(result.receitaAprovada, "BRL")}</span><span>Faturada {formatMoney(result.receitaFaturada, "BRL")}</span><span>Recebida {formatMoney(result.receitaRecebida, "BRL")}</span></div>
    <div className="finance-pdor-summary"><strong>Projeção PDOR</strong>{pdor ? <span>{pdor.statusExecucaoLabel || pdor.statusExecucao} · Receita prevista final {pdor.receitaPrevistaFinal == null ? "indisponível" : formatMoney(pdor.receitaPrevistaFinal, "BRL")}</span> : <span>Sem snapshot disponível.</span>}</div>
    <div className="finance-table-wrap"><table className="finance-table"><thead><tr><th>Serviço</th><th>Produção</th><th>Custo</th><th>Receita estimada</th><th>Margem</th><th>RDOs</th></tr></thead><tbody>{result.servicos.map((service) => <tr key={`${service.nome}-${service.unidade}`}><td>{service.nome}</td><td>{service.quantidadeExecutada} {service.unidade}</td><td>{formatMoney(service.custoRealizado, "BRL")}</td><td>{service.receitaOperacionalEstimada === null ? "Receita indisponível" : formatMoney(service.receitaOperacionalEstimada, "BRL")}</td><td>{service.margem === null ? "-" : formatMoney(service.margem, "BRL")}</td><td>{service.quantidadeRdos}</td></tr>)}</tbody></table></div>
  </section>;
}
function Metric({ label, value }: { label: string; value: string }) { return <div><span>{label}</span><strong>{value}</strong></div>; }

