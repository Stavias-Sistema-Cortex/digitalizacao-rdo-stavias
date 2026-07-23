import type { RevenueTraceEvidence } from "./servicePriceApi";

interface FinanceTraceEvidenceDrawerProps {
  detail: RevenueTraceEvidence | null;
  loading?: boolean;
  error?: string;
  onClose: () => void;
}

function Identity({ label, value }: { label: string; value: string }) {
  return (
    <div className="finance-trace-identity">
      <dt>{label}</dt>
      <dd><code>{value}</code></dd>
    </div>
  );
}

export function FinanceTraceEvidenceDrawer({
  detail,
  loading = false,
  error = "",
  onClose,
}: FinanceTraceEvidenceDrawerProps) {
  if (!detail && !loading && !error) return null;

  return (
    <div className="finance-trace-drawer-backdrop" onMouseDown={onClose}>
      <aside
        className="finance-trace-drawer"
        role="dialog"
        aria-modal="true"
        aria-labelledby="finance-trace-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <header>
          <div>
            <span>Rastro ontológico</span>
            <h2 id="finance-trace-title">Evidência de receita</h2>
          </div>
          <button type="button" onClick={onClose} aria-label="Fechar evidência">×</button>
        </header>

        {loading ? <p role="status">Carregando a cadeia registrada…</p> : null}
        {error ? <p role="alert">{error}</p> : null}
        {detail ? (
          <>
            <p className="finance-trace-drawer__summary">
              Esta receita existe porque uma execução validada preservou o preço
              vigente e publicou um evento aceito. Os IDs abaixo são os registros reais.
            </p>
            <dl className="finance-trace-identities">
              <Identity label="RDO" value={detail.row.rdoId} />
              <Identity label="Execução" value={detail.row.executionId} />
              <Identity label="Serviço" value={detail.row.serviceId} />
              <Identity label="Versão do preço" value={detail.row.priceVersionId} />
              <Identity label="Evidência" value={detail.row.revenueEvidenceId} />
              <Identity label="Evento" value={detail.row.revenueEventId} />
            </dl>
            <section className="finance-trace-relations">
              <h3>Relações materializadas</h3>
              {detail.ontologyLinks.length === 0 ? (
                <p>Nenhuma relação adicional foi retornada.</p>
              ) : (
                <ul>
                  {detail.ontologyLinks.map((link) => (
                    <li key={`${link.sourceType}:${link.sourceId}:${link.relationType}:${link.targetId}`}>
                      <code>{link.sourceType}:{link.sourceId}</code>
                      <strong>{link.relationType}</strong>
                      <code>{link.targetType}:{link.targetId}</code>
                      <span>{link.active ? "ativa" : "histórica"}</span>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          </>
        ) : null}
      </aside>
    </div>
  );
}
