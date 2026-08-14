import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ObraPdor } from "./obrasApi";
import { PdorPanel } from "./PdorPanel";

function pdorDeExemplo(): ObraPdor {
  return {
    id: "snap-1",
    obraId: "obra-1",
    dataReferencia: "2026-07-01",
    janelaTemporal: {
      inicioProgramacao: "2025-12-10",
      fimProgramacao: "2026-07-01",
      dataReferencia: "2026-07-01",
      janelaEquipamentosDias: 30,
      serieHistoricaSemanal: true,
    },
    dataExecucao: "2026-07-08T09:00:00",
    versaoModelo: "PDOR-0.2.0",
    versaoPremissas: "PDOR-ASSUMPTIONS-0.2.0",
    versaoDados: "dados-1",
    statusExecucao: "SUCCESS",
    statusExecucaoLabel: "Concluído",
    calibracao: "NOT_CALIBRATED",
    calibracaoLabel: "Não calibrado",
    risco: "HIGH",
    riscoLabel: "Alto",
    faseLabel: "Produção",
    receitaPrevistaFinal: 934000,
    p10: 880000,
    p50: 912000,
    p80: 940000,
    p95: 955000,
    probabilidadeAbaixoContrato: 0.72,
    confianca: 0.61,
    drivers: [{
      code: "PRODUCTIVITY_LOSS",
      description: "Produtividade abaixo do esperado",
      impact: 0.3,
      evidence: "SPI=0,82",
    }],
    warnings: [],
    featuresUtilizadas: [{ code: "", label: "Valor contratual", detail: null, field: "contractValue", availability: "DIRECT" }],
    dadosAusentes: [{ code: "", label: "Capacidade de mão de obra em horas", detail: null, field: "laborCapacityHours", availability: "ABSENT" }],
    limitacoes: [{ code: "LIMITACAO_1", label: "Histórico insuficiente para calibração.", detail: null, field: null, availability: null }],
    alertas: [{ code: "RISCO_ALTO", label: "Risco de receita elevado", detail: null, field: null, availability: null }],
    recomendacoes: [{ code: "REVISAR_DRIVERS", label: "Revisar fatores de risco", detail: "Priorizar os drivers registrados.", field: null, availability: null }],
    comparacaoAnterior: { available: true, riskDirection: "SUBIU", previousSnapshotId: "snap-0", changedInputCount: 1 },
    evidencias: [{ entityType: "RDO", entityId: "rdo-1", source: "rdo", role: "EXECUCAO_REAL", observedAt: null }],
    iniciadoPor: "usuario-1",
    tipoIniciador: "USER",
    erroExecucao: null,
    algorithmVersion: "PDOR-REVENUE-1",
    evidenceIds: ["evidence-1"],
    evidenceHighWaterMark: 812,
    coverageCode: "COMPLETE_ACCEPTED_EXACT",
    assumptions: { iterations: 10_000 },
    executedAtUtc: "2026-07-08T12:00:00Z",
    stale: false,
    current: true,
  };
}

describe("PdorPanel", () => {
  it("expõe calibração, comparação, lacunas, recomendações e proveniência", () => {
    const pdor = pdorDeExemplo();

    const html = renderToStaticMarkup(
      <PdorPanel pdor={pdor} loading={false} error={null} />,
    );

    expect(html).toContain("Não calibrado");
    expect(html).toContain("O risco subiu");
    expect(html).toContain("Capacidade de mão de obra em horas");
    expect(html).toContain("Revisar fatores de risco");
    // O que sustenta o número, contado em português — a lista de UUIDs,
    // versões e o JSON de premissas eram diagnóstico de máquina e saíram.
    expect(html).toContain("1 RDO, 1 evidência de receita aceita");
    expect(html).not.toContain("rdo-1");
    expect(html).not.toContain("evidence-1");
    expect(html).not.toContain("PDOR-ASSUMPTIONS-0.2.0");
    expect(html).not.toContain("PDOR-REVENUE-1");
    expect(html).not.toContain("COMPLETE_ACCEPTED_EXACT");
  });


  /*
   * A mensagem do servidor lista os campos pelo identificador interno.
   * Exibi-la crua enchia a tela de contractValue, measuredRevenue e afins —
   * diagnóstico de quem programa, numa tela de quem administra a obra.
   */
  it("não despeja o nome interno dos campos quando há rótulo em português", () => {
    const html = renderToStaticMarkup(
      <PdorPanel
        pdor={{
          ...pdorDeExemplo(),
          statusExecucao: "INSUFFICIENT_DATA",
          statusExecucaoLabel: "Dados insuficientes",
          erroExecucao:
            "Dados insuficientes para calcular o PDOR. Campos ausentes: "
            + "contractValue, measuredRevenue, validatedRevenue.",
        }}
        loading={false}
        error={null}
      />,
    );

    expect(html).not.toContain("contractValue");
    expect(html).not.toContain("measuredRevenue");
    expect(html).not.toContain("Campos ausentes");
    expect(html).toContain("Falta um dado da obra");
    // O rótulo humano continua: é ele que responde o que precisa ser preenchido.
    expect(html).toContain("Capacidade de mão de obra em horas");
    expect(html).toContain("Falta preencher");
  });

  it("mantém a frase do servidor quando não há lista com rótulos", () => {
    const html = renderToStaticMarkup(
      <PdorPanel
        pdor={{
          ...pdorDeExemplo(),
          statusExecucao: "FAILED",
          statusExecucaoLabel: "Falhou",
          dadosAusentes: [],
          erroExecucao: "A origem de receita não respondeu.",
        }}
        loading={false}
        error={null}
      />,
    );

    expect(html).toContain("A origem de receita não respondeu.");
  });
});

/*
 * A manchete pode contradizer a própria faixa: sem nenhuma medição, o índice
 * de captura é zero, a projeção direta cai no valor contratual inteiro e a
 * simulação devolve percentis zerados. Os dois números são o que o modelo
 * produziu — o que não pode é apresentá-los lado a lado sem dizer isso.
 */
describe("ressalva da receita prevista sem distribuição", () => {
  it("avisa quando o número é teto de contrato, não previsão", () => {
    const html = renderToStaticMarkup(
      <PdorPanel
        pdor={{
          ...pdorDeExemplo(),
          receitaPrevistaFinal: 93147130,
          p10: 0,
          p50: 0,
          p80: 0,
          p95: 0,
        }}
        loading={false}
        error={null}
      />,
    );

    expect(html).toContain("teto do contrato");
    // E a faixa some: "Faixa R$ 0 a R$ 0" embaixo de noventa e três milhões
    // não informa nada — é a contradição escrita por extenso.
    expect(html).not.toContain("Faixa");
  });

  it("não avisa nada quando a simulação produziu faixa", () => {
    const html = renderToStaticMarkup(
      <PdorPanel pdor={pdorDeExemplo()} loading={false} error={null} />,
    );

    expect(html).not.toContain("teto do contrato");
    expect(html).toContain("Faixa");
  });
});

/*
 * O painel abria com cinco blocos de justificativa embaixo dos números —
 * fatores de risco, dados ausentes, limitações, alertas, recomendações,
 * proveniência —, todos expandidos. A pergunta da tela é "quanto esta obra deve
 * faturar", e o número que a responde ficava espremido no topo de uma parede de
 * texto. Nada foi jogado fora; tudo passou para trás de uma porta fechada.
 */
describe("a justificativa fica atrás de uma porta", () => {
  it("recolhe fatores, listas e proveniência num só detalhe fechado", () => {
    const html = renderToStaticMarkup(
      <PdorPanel pdor={pdorDeExemplo()} loading={false} error={null} />,
    );

    const detalhe = html.slice(html.indexOf("<details"));
    expect(html).toContain("Como este número foi calculado");
    // Fechado: sem `open`, o navegador não mostra nada disso de saída.
    expect(detalhe).not.toContain("<details open");
    // E continua tudo lá dentro, para quem for auditar.
    expect(detalhe).toContain("Principais fatores de risco");
    expect(detalhe).toContain("Capacidade de mão de obra em horas");
    expect(detalhe).toContain("Revisar fatores de risco");
    expect(detalhe).toContain("registros vivos da obra");
  });

  it("o número e a faixa continuam fora da porta", () => {
    const html = renderToStaticMarkup(
      <PdorPanel pdor={pdorDeExemplo()} loading={false} error={null} />,
    );

    const antesDaPorta = html.slice(0, html.indexOf("<details"));
    expect(antesDaPorta).toContain("Receita prevista final");
    expect(antesDaPorta).toContain("Risco de ficar abaixo do contrato");
    expect(antesDaPorta).toContain("Alto");
  });

  /*
   * "O risco permaneceu estável" era o caso mais comum, e não é notícia:
   * ocupava uma linha inteira acima do número para dizer que nada mudou.
   */
  it("só anuncia a comparação quando o risco se mexeu", () => {
    const estavel = renderToStaticMarkup(
      <PdorPanel
        pdor={{
          ...pdorDeExemplo(),
          comparacaoAnterior: {
            available: true,
            riskDirection: "ESTAVEL",
            previousSnapshotId: "snap-0",
            changedInputCount: 11,
          },
        }}
        loading={false}
        error={null}
      />,
    );

    expect(estavel).not.toContain("permaneceu estável");
    expect(estavel).not.toContain("11 entradas mudaram");
  });
});

/*
 * Apagar o RDO que sustentava a projeção deixa a obra sem entrada suficiente, e
 * é esse o estado que passa a ocupar a posição de atual. A tela precisa dizer o
 * que falta preencher — sem enterrar a resposta atrás da porta do detalhe, que
 * é para quem quer auditar, não para quem está diante de um cálculo que não
 * saiu.
 */
describe("cálculo que não saiu", () => {
  it("deixa aberto o que falta preencher e recolhe o resto", () => {
    const html = renderToStaticMarkup(
      <PdorPanel
        pdor={{
          ...pdorDeExemplo(),
          statusExecucao: "INSUFFICIENT_DATA",
          statusExecucaoLabel: "Dados insuficientes",
        }}
        loading={false}
        error={null}
      />,
    );

    const antesDaPorta = html.slice(0, html.indexOf("<details"));
    expect(antesDaPorta).toContain("Dados insuficientes");
    expect(antesDaPorta).toContain("Falta preencher");
    expect(antesDaPorta).toContain("Capacidade de mão de obra em horas");
    // E a mesma lista não se repete lá dentro.
    expect(html.slice(html.indexOf("<details")))
      .not.toContain("Dados ausentes ou ambíguos");
  });
});
