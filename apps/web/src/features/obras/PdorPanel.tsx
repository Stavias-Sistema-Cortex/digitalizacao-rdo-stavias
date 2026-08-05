import type {
  ObraPdor,
  ObraPdorExplanationItem,
} from "./obrasApi";

interface PdorPanelProps {
  pdor: ObraPdor | null;
  loading: boolean;
  error: string | null;
}

function formatCurrency(value: number | null): string {
  if (value === null) return "-";
  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 0,
  }).format(value);
}

function formatDateOnly(value: string | null): string {
  if (!value) return "";
  const date = new Date(value.includes("T") ? value : `${value}T00:00:00`);
  if (Number.isNaN(date.getTime())) return value;
  return new Intl.DateTimeFormat("pt-BR", { dateStyle: "short" }).format(date);
}

function formatPercent(value: number | null): string {
  if (value === null) return "-";
  return new Intl.NumberFormat("pt-BR", {
    style: "percent",
    maximumFractionDigits: 1,
  }).format(value);
}

function riskClass(risk: string | null): string {
  const normalized = (risk ?? "").toUpperCase();
  if (normalized === "CRITICAL" || normalized === "HIGH") {
    return "obras-pdor-risk obras-pdor-risk--alto";
  }
  if (normalized === "MODERATE") {
    return "obras-pdor-risk obras-pdor-risk--medio";
  }
  if (normalized === "LOW") {
    return "obras-pdor-risk obras-pdor-risk--baixo";
  }
  return "obras-pdor-risk";
}

function comparisonText(pdor: ObraPdor): string | null {
  if (!pdor.comparacaoAnterior?.available) return null;
  const changed = pdor.comparacaoAnterior.changedInputCount;
  const suffix = changed > 0
    ? ` ${changed} ${changed === 1 ? "entrada mudou" : "entradas mudaram"}.`
    : "";
  switch (pdor.comparacaoAnterior.riskDirection) {
    case "SUBIU":
      return `O risco subiu desde a análise anterior.${suffix}`;
    case "CAIU":
      return `O risco caiu desde a análise anterior.${suffix}`;
    case "ESTAVEL":
      return `O risco permaneceu estável desde a análise anterior.${suffix}`;
    default:
      return `Existe uma análise anterior, mas o risco não é comparável.${suffix}`;
  }
}

/**
 * O que dizer quando o cálculo não saiu.
 *
 * A mensagem do servidor lista os campos pelo identificador interno —
 * "Campos ausentes: contractValue, measuredRevenue, validatedRevenue..." —, que
 * é diagnóstico de quem programa, não informação para quem administra a obra.
 * Os mesmos campos já chegam em `dadosAusentes` com rótulo em português, e é
 * essa lista, logo abaixo, que responde à pergunta útil: o que falta preencher.
 *
 * Sem a lista, a frase crua ainda é melhor que silêncio, e por isso continua
 * sendo o último recurso.
 */
function mensagemDeExecucao(pdor: ObraPdor): string {
  if (pdor.dadosAusentes.length > 0) {
    const quantos = pdor.dadosAusentes.length;
    return quantos === 1
      ? "Falta um dado da obra para calcular a previsão."
      : `Faltam ${quantos} dados da obra para calcular a previsão.`;
  }
  return (
    pdor.erroExecucao ??
    "O PDOR não pôde ser calculado com os dados atuais."
  );
}

function ExplanationList({
  title,
  items,
}: {
  title: string;
  items: ObraPdorExplanationItem[];
}) {
  if (items.length === 0) return null;
  return (
    <section className="obras-pdor-explanation-section">
      <h4>{title}</h4>
      <ul>
        {items.slice(0, 5).map((item, index) => (
          <li key={item.code || item.field || `${item.label}-${index}`}>
            <strong>{item.label}</strong>
            {item.detail ? <span>{item.detail}</span> : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

export function PdorPanel({ pdor, loading, error }: PdorPanelProps) {
  const comparison = pdor ? comparisonText(pdor) : null;
  const notCalibrated = Boolean(
    pdor?.calibracao && pdor.calibracao !== "CALIBRATED",
  );

  return (
    <section className="obras-pdor" aria-label="Previsão de receita PDOR">
      <div className="obras-pdor-header">
        <div>
          <h3>Previsão de receita · PDOR</h3>
          <span>
            {pdor?.dataReferencia
              ? `Referência ${formatDateOnly(pdor.dataReferencia)}`
              : "Calculado a partir dos dados operacionais da obra"}
          </span>
        </div>
        {pdor?.riscoLabel ? (
          <span className={riskClass(pdor.risco)}>{pdor.riscoLabel}</span>
        ) : null}
      </div>

      {loading ? (
        <p className="obras-pdor-note">Consultando previsão de receita...</p>
      ) : error ? (
        <p className="obras-pdor-note">{error}</p>
      ) : !pdor ? (
        <p className="obras-pdor-note">
          Nenhum cálculo PDOR registrado ainda. O próximo RDO sincronizado
          dispara o cálculo automaticamente.
        </p>
      ) : pdor.statusExecucao !== "SUCCESS" ? (
        <>
          <div className="obras-pdor-insufficient">
            <strong>{pdor.statusExecucaoLabel ?? pdor.statusExecucao}</strong>
            <p>{mensagemDeExecucao(pdor)}</p>
          </div>
          <div className="obras-pdor-explanation-grid">
            <ExplanationList title="Falta preencher" items={pdor.dadosAusentes} />
            <ExplanationList title="Limitações conhecidas" items={pdor.limitacoes} />
          </div>
        </>
      ) : (
        <>
          {notCalibrated ? (
            <p className="obras-pdor-calibration" role="status">
              <strong>{pdor.calibracaoLabel ?? "Não calibrado"}.</strong>{" "}
              As probabilidades usam as premissas versionadas e devem ser
              interpretadas com as limitações abaixo.
            </p>
          ) : null}

          {comparison ? <p className="obras-pdor-comparison">{comparison}</p> : null}

          <dl className="obras-pdor-grid">
            <div className="obras-pdor-main">
              <dt>Receita prevista final</dt>
              <dd>{formatCurrency(pdor.receitaPrevistaFinal ?? pdor.p50)}</dd>
              <dd className="obras-pdor-range">
                Faixa {formatCurrency(pdor.p10)} a {formatCurrency(pdor.p95)} ·
                P50 {formatCurrency(pdor.p50)}
              </dd>
            </div>
            <div>
              <dt>Risco de ficar abaixo do contrato</dt>
              <dd>{formatPercent(pdor.probabilidadeAbaixoContrato)}</dd>
            </div>
            <div>
              <dt>Confiança do cálculo</dt>
              <dd>{formatPercent(pdor.confianca)}</dd>
            </div>
            <div>
              <dt>Calibração</dt>
              <dd>{pdor.calibracaoLabel ?? pdor.calibracao ?? "-"}</dd>
            </div>
          </dl>

          {pdor.drivers.length > 0 ? (
            <section className="obras-pdor-factors">
              <h4>Principais fatores de risco</h4>
              <ul className="obras-pdor-drivers">
                {pdor.drivers.slice(0, 4).map((driver) => (
                  <li key={driver.code || driver.description}>
                    <strong>{driver.description}</strong>
                    {driver.evidence ? <span>{driver.evidence}</span> : null}
                  </li>
                ))}
              </ul>
            </section>
          ) : null}

          <div className="obras-pdor-explanation-grid">
            <ExplanationList title="Dados ausentes ou ambíguos" items={pdor.dadosAusentes} />
            <ExplanationList title="Limitações conhecidas" items={pdor.limitacoes} />
            <ExplanationList title="Alertas derivados" items={pdor.alertas} />
            <ExplanationList title="Ações recomendadas" items={pdor.recomendacoes} />
          </div>

          <details className="obras-pdor-provenance">
            <summary>Proveniência, versões e evidências</summary>
            <dl>
              <div><dt>Modelo</dt><dd>{pdor.versaoModelo ?? "-"}</dd></div>
              <div><dt>Algoritmo da receita</dt><dd>{pdor.algorithmVersion ?? "-"}</dd></div>
              <div><dt>Premissas</dt><dd>{pdor.versaoPremissas ?? "-"}</dd></div>
              <div><dt>Dados</dt><dd>{pdor.versaoDados ?? "-"}</dd></div>
              <div><dt>Cobertura</dt><dd>{pdor.coverageCode ?? "-"}</dd></div>
              <div><dt>High-water ontológico</dt><dd>{pdor.evidenceHighWaterMark ?? "-"}</dd></div>
              <div><dt>Execução UTC</dt><dd>{pdor.executedAtUtc ?? "-"}</dd></div>
              <div><dt>Estado</dt><dd>{pdor.stale ? "Histórico · obsoleto" : pdor.current ? "Atual" : "Histórico"}</dd></div>
              <div><dt>Iniciador</dt><dd>{pdor.iniciadoPor ?? "Não registrado"}</dd></div>
              <div><dt>Features avaliadas</dt><dd>{pdor.featuresUtilizadas.length}</dd></div>
              <div><dt>Evidências de receita</dt><dd>{pdor.evidenceIds.length}</dd></div>
            </dl>
            {Object.keys(pdor.assumptions).length > 0 ? (
              <pre className="obras-pdor-assumptions">{JSON.stringify(pdor.assumptions, null, 2)}</pre>
            ) : null}
            {pdor.evidenceIds.length > 0 ? (
              <ul className="obras-pdor-evidence-list">
                {pdor.evidenceIds.slice(0, 12).map((evidenceId) => (
                  <li key={evidenceId}>
                    <strong>REVENUE_EVIDENCE</strong>
                    <code>{evidenceId}</code>
                  </li>
                ))}
              </ul>
            ) : null}
            {pdor.evidencias.length > 0 ? (
              <ul className="obras-pdor-evidence-list">
                {pdor.evidencias.slice(0, 8).map((evidence) => (
                  <li key={`${evidence.entityType}-${evidence.entityId}`}>
                    <strong>{evidence.entityType}</strong>
                    <code>{evidence.entityId}</code>
                    {evidence.source ? <span>{evidence.source}</span> : null}
                  </li>
                ))}
              </ul>
            ) : null}
          </details>
        </>
      )}
    </section>
  );
}
