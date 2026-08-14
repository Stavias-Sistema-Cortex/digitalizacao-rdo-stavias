import type { ReactNode } from "react";

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

/**
 * Só a mudança é notícia.
 *
 * <p>A frase saía sempre, e na maioria das vezes dizia "o risco permaneceu
 * estável" — que é o esperado, não uma informação. Ocupava uma linha inteira no
 * topo do painel para não dizer nada. Risco que subiu ou caiu, sim: isso o
 * gestor precisa ver antes de qualquer número.
 */
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
    default:
      return null;
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

/**
 * O número da manchete contradiz a própria faixa.
 *
 * <p>Sem nenhuma medição o índice de captura é zero: a projeção direta cai no
 * valor contratual inteiro, e a simulação, que precisa de receita observada,
 * devolve percentis zerados. Os dois valores são o que o modelo produziu — o
 * que não pode acontecer é apresentá-los lado a lado sem dizer isso.
 */
function semDistribuicao(pdor: ObraPdor): boolean {
  const previsto = pdor.receitaPrevistaFinal ?? pdor.p50;
  return (
    previsto !== null &&
    previsto > 0 &&
    (pdor.p50 === null || pdor.p50 === 0) &&
    (pdor.p95 === null || pdor.p95 === 0)
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
            {/* O rótulo e a explicação eram dois elementos em linha, sem nada
                entre eles: na tela saía "Consumo real de materialSoma das
                quantidades…", uma frase emendada na outra. A explicação passa
                a ocupar a própria linha, que é onde ela se lê. */}
            <strong>{item.label}</strong>
            {item.detail ? <span>{item.detail}</span> : null}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Rótulos humanos para o que sustenta o cálculo.
 *
 * <p>[singular, plural]. Tipo que a ontologia inventar depois e não estiver
 * aqui aparece como "registro", que continua verdadeiro.
 */
const NOME_DA_EVIDENCIA: Record<string, readonly [string, string]> = {
  RDO: ["RDO", "RDOs"],
  PROGRAMACAO: ["programação", "programações"],
  SERVICE_PRICE_VERSION: ["preço do catálogo", "preços do catálogo"],
  ITEM_CONTRATUAL: ["item contratual", "itens contratuais"],
  EXECUCAO_SERVICO_RDO: ["execução de serviço", "execuções de serviço"],
  EQUIPE: ["equipe", "equipes"],
  EQUIPE_MEMBRO: ["membro de equipe", "membros de equipe"],
  OBRA_GEOMETRIA: ["geometria da obra", "geometrias da obra"],
  EVENTO_OPERACIONAL: ["evento operacional", "eventos operacionais"],
};

/**
 * O que o cálculo leu, contado em português.
 *
 * <p>O painel despejava a lista crua: "SERVICE_PRICE_VERSION 8aa4fc2e-…",
 * dezenas de identificadores que ninguém consegue conferir. A informação útil
 * dessa lista é outra, e é curta: quantos registros vivos de cada tipo
 * sustentam o número. "3 preços do catálogo" responde na hora de onde vem um
 * teto de contrato que se acreditava excluído — o identificador não.
 */
function resumoDasEvidencias(pdor: ObraPdor): string {
  const porTipo = new Map<string, number>();
  for (const evidencia of pdor.evidencias) {
    porTipo.set(
      evidencia.entityType,
      (porTipo.get(evidencia.entityType) ?? 0) + 1,
    );
  }
  const partes = [...porTipo.entries()].map(([tipo, quantos]) => {
    const nomes = NOME_DA_EVIDENCIA[tipo] ?? ["registro", "registros"];
    return `${quantos} ${quantos === 1 ? nomes[0] : nomes[1]}`;
  });
  if (pdor.evidenceIds.length > 0) {
    partes.push(
      `${pdor.evidenceIds.length} ${
        pdor.evidenceIds.length === 1
          ? "evidência de receita aceita"
          : "evidências de receita aceitas"
      }`,
    );
  }
  if (partes.length === 0) {
    return "O cálculo não encontrou registro vivo na obra.";
  }
  return `O cálculo leu os registros vivos da obra: ${partes.join(", ")}.`;
}

/**
 * A justificativa fica atrás de uma porta fechada — e fala português.
 *
 * <p>O detalhe chegou a despejar a proveniência inteira: versões de modelo,
 * hash dos dados, identificador do iniciador, a lista de UUIDs das evidências
 * e o JSON cru das premissas. Diagnóstico de máquina, numa tela cuja pergunta
 * é "quanto esta obra deve faturar" — e que já viaja completo na resposta da
 * API para quem for auditar de verdade. Aqui ficam as listas que se leem:
 * fatores de risco, lacunas, limitações, alertas, recomendações e a contagem
 * do que sustenta o número.
 */
function DetalheDoCalculo({
  pdor,
  dadosAusentesJaVisiveis = false,
  children,
}: {
  pdor: ObraPdor;
  /** Quando o cálculo não saiu, a lista já está aberta acima; não se repete. */
  dadosAusentesJaVisiveis?: boolean;
  children?: ReactNode;
}) {
  return (
    <details className="obras-pdor-detalhe">
      <summary>Como este número foi calculado</summary>
      <div className="obras-pdor-detalhe-corpo">
        {children}
        <div className="obras-pdor-explanation-grid">
          <ExplanationList
            title="Dados ausentes ou ambíguos"
            items={dadosAusentesJaVisiveis ? [] : pdor.dadosAusentes}
          />
          <ExplanationList title="Limitações conhecidas" items={pdor.limitacoes} />
          <ExplanationList title="Alertas derivados" items={pdor.alertas} />
          <ExplanationList title="Ações recomendadas" items={pdor.recomendacoes} />
        </div>
        <p className="obras-pdor-fontes">{resumoDasEvidencias(pdor)}</p>
        {pdor.stale ? (
          <p className="obras-pdor-fontes">
            Este snapshot já venceu; um mais novo o substituiu.
          </p>
        ) : null}
      </div>
    </details>
  );
}

export function PdorPanel({ pdor, loading, error }: PdorPanelProps) {
  const comparison = pdor ? comparisonText(pdor) : null;
  // A calibração já tem cartão próprio na grade: o parágrafo que a repetia em
  // prosa, acima dos números, só empurrava o número para baixo.

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
          {/* O que falta preencher é a única lista que responde à pergunta de
              quem está diante de um cálculo que não saiu: fica aberta. */}
          <div className="obras-pdor-explanation-grid">
            <ExplanationList title="Falta preencher" items={pdor.dadosAusentes} />
          </div>
          <DetalheDoCalculo pdor={pdor} dadosAusentesJaVisiveis />
        </>
      ) : (
        <>
          {comparison ? <p className="obras-pdor-comparison">{comparison}</p> : null}

          <dl className="obras-pdor-grid">
            <div className="obras-pdor-main">
              <dt>Receita prevista final</dt>
              <dd>{formatCurrency(pdor.receitaPrevistaFinal ?? pdor.p50)}</dd>
              {/*
                Ou a faixa, ou a ressalva — nunca as duas.
                "Faixa R$ 0 a R$ 0" embaixo de noventa e três milhões não é
                informação, é a contradição escrita por extenso. Quando a
                simulação não produziu percentis, o lugar dessa linha é da frase
                que explica por quê.
              */}
              {semDistribuicao(pdor) ? (
                <dd className="obras-pdor-ressalva">
                  Sem receita medida, este é o teto do contrato — não uma
                  previsão.
                </dd>
              ) : (
                <dd className="obras-pdor-range">
                  Faixa {formatCurrency(pdor.p10)} a {formatCurrency(pdor.p95)} ·
                  P50 {formatCurrency(pdor.p50)}
                </dd>
              )}
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

          <DetalheDoCalculo pdor={pdor}>
            {pdor.drivers.length > 0 ? (
              <section className="obras-pdor-explanation-section">
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
          </DetalheDoCalculo>
        </>
      )}
    </section>
  );
}
