import type { MaterialUsinado, ObraUsinados } from "./usinadosApi";
import { baixarUsinadosCsv, buildUsinadosCsv } from "./usinadosExport";
import "./UsinadosPanel.css";

interface UsinadosPanelProps {
  usinados: ObraUsinados | null;
  loading: boolean;
  error: string | null;
}

function formatQuantidade(value: number | null): string {
  if (value === null) return "—";
  return new Intl.NumberFormat("pt-BR", {
    maximumFractionDigits: 3,
  }).format(value);
}

function formatCurrency(value: number | null): string {
  if (value === null) return "—";
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
  }).format(value);
}

/**
 * A fração da usinagem que virou sobra — só quando as duas medidas existem.
 */
function percentualDesperdicio(material: MaterialUsinado): string | null {
  if (
    material.quantidadeSobra === null ||
    material.quantidadeUsinada === null ||
    material.quantidadeUsinada <= 0
  ) {
    return null;
  }
  return new Intl.NumberFormat("pt-BR", {
    style: "percent",
    maximumFractionDigits: 1,
  }).format(material.quantidadeSobra / material.quantidadeUsinada);
}

function motivoDoPreco(material: MaterialUsinado): string | null {
  if (material.precoMotivo === "SEM_PRECO") {
    return "sem preço no catálogo";
  }
  if (material.precoMotivo === "PRECO_AMBIGUO") {
    return "mais de um preço vigente";
  }
  return null;
}

/**
 * Totais de usinagem e aplicação da obra em foco.
 *
 * Mostra, material a material, o que os RDOs declararam: previsto, usinado,
 * aplicado (o feito), o que ficou por aplicar e a sobra — o desperdiçado —,
 * cada um na unidade em que foi lançado (T, M³, M²…). O dinheiro aparece
 * quando o financeiro é visível e o preço casa no catálogo da obra; linha sem
 * preço diz o porquê em vez de fingir um zero. O botão exporta exatamente o
 * que está na tela, em CSV que o Excel abre direto.
 */
export function UsinadosPanel({ usinados, loading, error }: UsinadosPanelProps) {
  return (
    <section
      className="obras-usinados"
      aria-label="Totais de serviços usinados"
    >
      <div className="obras-usinados-header">
        <div>
          <p className="eyebrow">Serviços usinados</p>
          <h3>Usinagem, aplicação e sobra</h3>
          <span>
            Somatório do que os RDOs desta obra declararam, até agora
          </span>
        </div>
        {usinados && usinados.materiais.length > 0 ? (
          <button
            type="button"
            className="obras-usinados-exportar"
            onClick={() => {
              baixarUsinadosCsv(buildUsinadosCsv(usinados));
            }}
          >
            Exportar CSV
          </button>
        ) : null}
      </div>

      {loading ? (
        <p className="obras-usinados-note">Consultando os RDOs da obra…</p>
      ) : error ? (
        <p className="obras-usinados-note">{error}</p>
      ) : !usinados || usinados.materiais.length === 0 ? (
        <p className="obras-usinados-note">
          Nenhum material usinado declarado nos RDOs desta obra ainda.
        </p>
      ) : (
        <>
          {!usinados.precosVisiveis ? (
            <p className="obras-usinados-note">
              Os valores em reais não aparecem porque este acesso não tem o
              financeiro da obra liberado.
            </p>
          ) : null}
          <div className="obras-usinados-tabela-scroll">
            <table className="obras-usinados-tabela">
              <thead>
                <tr>
                  <th scope="col">Material</th>
                  <th scope="col">Un.</th>
                  <th scope="col">Previsto</th>
                  <th scope="col">Usinado</th>
                  <th scope="col">Aplicado</th>
                  <th scope="col">Não aplicado</th>
                  <th scope="col">Sobra</th>
                  {usinados.precosVisiveis ? (
                    <>
                      <th scope="col">Valor aplicado</th>
                      <th scope="col">Valor desperdiçado</th>
                    </>
                  ) : null}
                </tr>
              </thead>
              <tbody>
                {usinados.materiais.map((material) => {
                  const desperdicio = percentualDesperdicio(material);
                  const motivo = motivoDoPreco(material);
                  return (
                    <tr key={`${material.material}:${material.unidade ?? ""}`}>
                      <th scope="row">
                        {material.material}
                        <small>
                          {material.totalRdos}{" "}
                          {material.totalRdos === 1 ? "RDO" : "RDOs"}
                        </small>
                      </th>
                      <td>{material.unidade ?? "—"}</td>
                      <td>{formatQuantidade(material.quantidadePrevista)}</td>
                      <td>{formatQuantidade(material.quantidadeUsinada)}</td>
                      <td>{formatQuantidade(material.quantidadeAplicada)}</td>
                      <td>
                        {formatQuantidade(material.quantidadeNaoAplicada)}
                      </td>
                      <td>
                        {formatQuantidade(material.quantidadeSobra)}
                        {desperdicio ? <small>{desperdicio}</small> : null}
                      </td>
                      {usinados.precosVisiveis ? (
                        motivo ? (
                          <td colSpan={2} className="obras-usinados-sem-preco">
                            {motivo}
                          </td>
                        ) : (
                          <>
                            <td>{formatCurrency(material.valorAplicado)}</td>
                            <td>
                              {formatCurrency(material.valorDesperdicado)}
                            </td>
                          </>
                        )
                      ) : null}
                    </tr>
                  );
                })}
              </tbody>
              {usinados.precosVisiveis && usinados.totais ? (
                <tfoot>
                  <tr>
                    <th scope="row" colSpan={7}>
                      Total das linhas com preço
                      {usinados.totais.materiaisSemPreco > 0 ? (
                        <small>
                          {usinados.totais.materiaisSemPreco}{" "}
                          {usinados.totais.materiaisSemPreco === 1
                            ? "material sem preço fora do total"
                            : "materiais sem preço fora do total"}
                        </small>
                      ) : null}
                    </th>
                    <td>{formatCurrency(usinados.totais.valorAplicado)}</td>
                    <td>
                      {formatCurrency(usinados.totais.valorDesperdicado)}
                    </td>
                  </tr>
                </tfoot>
              ) : null}
            </table>
          </div>
        </>
      )}
    </section>
  );
}
