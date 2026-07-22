import { useEffect, useState } from "react";

import {
  buscarRastreioReceita,
  type FinanceRevenueTrace,
} from "./financeiroApi";
import { formatMoney } from "./financeiroView";
import { FinanceTraceEvidenceDrawer } from "./FinanceTraceEvidenceDrawer";

export function FinanceRevenueTracePage({
  obraId,
  de,
  ate,
}: {
  obraId: string;
  de: string;
  ate: string;
}) {
  const [data, setData] = useState<FinanceRevenueTrace | null>(null);
  const [error, setError] = useState("");
  const [selectedService, setSelectedService] = useState<
    FinanceRevenueTrace["tiposServico"][number] | null
  >(null);

  useEffect(() => {
    let cancelled = false;
    queueMicrotask(() => {
      if (!cancelled) {
        setData(null);
        setError("");
      }
    });
    void buscarRastreioReceita({ obraId, de, ate })
      .then((result) => {
        if (!cancelled) setData(result);
      })
      .catch((reason: unknown) => {
        if (!cancelled) {
          setError(reason instanceof Error
            ? reason.message
            : "Não foi possível carregar o rastreio.");
        }
      });
    return () => {
      cancelled = true;
    };
  }, [obraId, de, ate]);

  if (error) {
    return <section className="finance-error-state" role="alert">{error}</section>;
  }
  if (!data) {
    return <p className="finance-loading">Atualizando rastreio operacional…</p>;
  }

  return (
    <>
      <FinanceRevenueTraceContent
        data={data}
        onSelectService={setSelectedService}
      />

      <FinanceTraceEvidenceDrawer
        service={selectedService}
        onClose={() => setSelectedService(null)}
      />
    </>
  );
}

export function FinanceRevenueTraceContent({
  data,
  onSelectService,
}: {
  data: FinanceRevenueTrace;
  onSelectService: (
    service: FinanceRevenueTrace["tiposServico"][number],
  ) => void;
}) {
  if (data.tiposServico.length === 0) {
    return (
      <section
        className="finance-operational-result finance-revenue-empty"
        data-finance-revenue-state="empty"
        role="status"
      >
        <div className="finance-section-heading">
          <div>
            <p className="finance-kicker">Receita operacional</p>
            <h2>Ainda não há receita operacional registrada</h2>
            <p>
              Os valores serão exibidos quando houver uma execução de serviço
              validada no período, vinculada a uma obra autorizada.
            </p>
          </div>
        </div>
      </section>
    );
  }

  const total = data.consolidado;
  const receitaCompleta = data.tiposServico.every(
    (service) => service.receitaDisponivel,
  );
  return (
    <>
      <section className="finance-operational-result">
        <div className="finance-section-heading">
          <div>
            <p className="finance-kicker">RDO → serviço → receita</p>
            <h2>Receita operacional rastreável</h2>
            <p>
              Valores estimados pela produção registrada; faturamento e recebimento
              são exibidos separadamente quando houver preço contratual vinculado.
            </p>
          </div>
        </div>
        <div className="finance-operational-metrics">
          <Metric label="Produção" value={String(total.producao)} />
          <Metric
            label="Receita estimada"
            value={receitaCompleta
              ? formatMoney(total.receitaEstimada, "BRL")
              : "Indisponível"}
          />
          <Metric label="Custo" value={formatMoney(total.custo, "BRL")} />
          <Metric
            label="Margem"
            value={receitaCompleta ? formatMoney(total.margem, "BRL") : "Indisponível"}
          />
        </div>
        {receitaCompleta ? (
          <div className="finance-operational-states">
            <span>Medida {formatMoney(total.receitaMedida, "BRL")}</span>
            <span>Aprovada {formatMoney(total.receitaAprovada, "BRL")}</span>
            <span>Faturada {formatMoney(total.receitaFaturada, "BRL")}</span>
            <span>Recebida {formatMoney(total.receitaRecebida, "BRL")}</span>
          </div>
        ) : (
          <p className="finance-revenue-disclosure">
            <strong>Receita indisponível.</strong> Há execução validada sem preço
            contratual vinculado; o Córtex não a converte em valor zero.
          </p>
        )}
      </section>

      <section className="finance-workspace-section">
        <div className="finance-section-heading">
          <div>
            <p className="finance-kicker">Entre obras</p>
            <h2>Tipos de serviço em execução</h2>
          </div>
        </div>
        {data.tiposServico.length === 0 ? (
          <p className="finance-row-empty">Nenhum serviço registrado no período.</p>
        ) : (
          <div className="finance-table-wrap">
            <table className="finance-table">
              <thead>
                <tr>
                  <th>Serviço</th>
                  <th>Produção</th>
                  <th>Obras</th>
                  <th>Receita estimada</th>
                  <th>Margem</th>
                  <th><span className="sr-only">Ações</span></th>
                </tr>
              </thead>
              <tbody>
                {data.tiposServico.map((service) => (
                  <tr key={`${service.nome}-${service.unidade}`}>
                    <td data-label="Serviço">
                      <strong>{service.nome}</strong><br />
                      <small>{service.unidade}</small>
                    </td>
                    <td data-label="Produção">{service.totais.producao} {service.unidade}</td>
                    <td data-label="Obras">
                      {service.obras.map((obra) => obra.obraNome).join(", ")}
                    </td>
                    <td data-label="Receita estimada">
                      {service.receitaDisponivel
                        ? formatMoney(service.totais.receitaEstimada, "BRL")
                        : "Indisponível"}
                    </td>
                    <td data-label="Margem">
                      {service.receitaDisponivel
                        ? formatMoney(service.totais.margem, "BRL")
                        : "Indisponível"}
                    </td>
                    <td className="finance-row-actions">
                      <button
                        type="button"
                        className="finance-secondary-action"
                        onClick={() => onSelectService(service)}
                      >
                        Evidências
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}
