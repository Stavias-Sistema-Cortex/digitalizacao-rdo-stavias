import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { ObraPdor } from "./obrasApi";
import { PdorPanel } from "./PdorPanel";

describe("PdorPanel", () => {
  it("expõe calibração, comparação, lacunas, recomendações e proveniência", () => {
    const pdor: ObraPdor = {
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

    const html = renderToStaticMarkup(
      <PdorPanel pdor={pdor} loading={false} error={null} />,
    );

    expect(html).toContain("Não calibrado");
    expect(html).toContain("O risco subiu");
    expect(html).toContain("Capacidade de mão de obra em horas");
    expect(html).toContain("Revisar fatores de risco");
    expect(html).toContain("PDOR-ASSUMPTIONS-0.2.0");
    expect(html).toContain("rdo-1");
    expect(html).toContain("PDOR-REVENUE-1");
    expect(html).toContain("COMPLETE_ACCEPTED_EXACT");
    expect(html).toContain("812");
    expect(html).toContain("evidence-1");
  });
});
