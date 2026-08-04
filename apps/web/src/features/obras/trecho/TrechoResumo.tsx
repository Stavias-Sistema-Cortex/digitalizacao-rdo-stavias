import { formatarKm, type ProjecaoTrecho } from "./trechoGeometry";
import "./TrechoEsquematico.css";

interface TrechoResumoProps {
  projecao: ProjecaoTrecho;
  procedencia?: string | null;
}

function numero(valor: number, casas = 0): string {
  return new Intl.NumberFormat("pt-BR", {
    maximumFractionDigits: casas,
  }).format(valor);
}

function periodo(projecao: ProjecaoTrecho): string {
  const { primeiraExecucao, ultimaExecucao } = projecao.resumo;
  if (!primeiraExecucao || !ultimaExecucao) {
    return "—";
  }
  /*
   * A data chega como dia puro, mas basta um registro carimbado com hora para
   * a concatenação virar "2026-08-04T10:00:00T00:00:00" e a tela exibir
   * "Invalid Date" no lugar do período da obra. Corta no dia e confere o
   * resultado: sem leitura confiável, o traço é mais honesto que uma data
   * inventada.
   */
  const formatar = (data: string) => {
    const dia = new Date(`${data.slice(0, 10)}T00:00:00`);
    return Number.isNaN(dia.getTime())
      ? "—"
      : dia.toLocaleDateString("pt-BR");
  };
  return primeiraExecucao === ultimaExecucao
    ? formatar(primeiraExecucao)
    : `${formatar(primeiraExecucao)} – ${formatar(ultimaExecucao)}`;
}

/**
 * Resumo de obra desativada ou arquivada.
 *
 * O acompanhamento ao vivo é desligado junto com a obra: nada de mapa, camada
 * ou captura. Fica o registro consolidado do que foi executado, que continua
 * sendo o histórico autoritativo lido das mesmas fontes.
 */
export function TrechoResumo({ projecao, procedencia }: TrechoResumoProps) {
  const semProducao = projecao.resumo.totalSegmentos === 0;

  return (
    <section
      className="trecho-esquematico trecho-resumo"
      aria-labelledby="trecho-resumo-title"
    >
      <header className="trecho-esquematico-header">
        <div>
          <p className="eyebrow">Obra desativada</p>
          <h3 id="trecho-resumo-title">
            Resumo do trecho · {projecao.rodovia ?? "Rodovia não informada"}
          </h3>
          <span>
            Acompanhamento em tempo real desligado. Histórico preservado.
          </span>
        </div>
        {procedencia ? (
          <p className="trecho-esquematico-procedencia">{procedencia}</p>
        ) : null}
      </header>

      {semProducao ? (
        <div className="trecho-esquematico-vazio">
          <strong>Nenhuma produção foi registrada nesta obra</strong>
          <p>
            Não há controle geométrico nem serviço executado lançado em RDO para
            resumir.
          </p>
        </div>
      ) : (
        <dl className="trecho-resumo-grade">
          <div>
            <dt>Faixa quilométrica</dt>
            <dd>
              {formatarKm(projecao.kmMin)} a {formatarKm(projecao.kmMax)}
            </dd>
          </div>
          <div>
            <dt>Extensão executada</dt>
            <dd>
              {projecao.resumo.extensaoTotalM > 0
                ? `${numero(projecao.resumo.extensaoTotalM)} m`
                : "—"}
            </dd>
          </div>
          <div>
            <dt>Área executada</dt>
            <dd>
              {projecao.resumo.areaTotalM2 > 0
                ? `${numero(projecao.resumo.areaTotalM2)} m²`
                : "—"}
            </dd>
          </div>
          <div>
            <dt>Massa aplicada</dt>
            <dd>
              {projecao.resumo.massaTotalTonelada > 0
                ? `${numero(projecao.resumo.massaTotalTonelada, 1)} t`
                : "—"}
            </dd>
          </div>
          <div>
            <dt>RDOs com produção</dt>
            <dd>{projecao.resumo.totalRdos}</dd>
          </div>
          <div>
            <dt>Período</dt>
            <dd>{periodo(projecao)}</dd>
          </div>
          <div>
            <dt>Sentidos</dt>
            <dd>{projecao.sentidos.join(", ") || "—"}</dd>
          </div>
          <div>
            <dt>Faixas</dt>
            <dd>{projecao.faixas.join(", ") || "—"}</dd>
          </div>
        </dl>
      )}
    </section>
  );
}
