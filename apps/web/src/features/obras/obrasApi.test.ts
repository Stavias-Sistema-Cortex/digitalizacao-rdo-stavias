import { describe, expect, it } from "vitest";

import {
  obraPdorFromApi,
  obraTimelineEventFromApi,
} from "./obrasApi";

describe("obraTimelineEventFromApi", () => {
  it("preserva rastreabilidade do evento vindo do cortex sem inventar campos", () => {
    const event = obraTimelineEventFromApi({
      id: "evt-1",
      commitSeq: 42,
      type: "OBRA_ATUALIZADA",
      principalEntityType: "OBRA",
      principalEntityId: "obra-1",
      relatedEntities: [],
      obraId: "obra-1",
      rdoId: null,
      colaboradorId: null,
      occurredAt: "2026-07-08T10:00:00",
      syncedAt: "2026-07-08T10:01:00",
      origin: "ONLINE",
      syncStatus: "SYNCED",
      schemaVersion: 1,
      payload: {
        nome: "Obra BR-262",
        cidade: "Campo Grande",
        rodovia: "BR-262",
      },
    });

    expect(event).toEqual({
      id: "evt-1",
      commitSeq: 42,
      type: "OBRA_ATUALIZADA",
      principalEntityType: "OBRA",
      principalEntityId: "obra-1",
      obraId: "obra-1",
      occurredAt: "2026-07-08T10:00:00",
      origin: "ONLINE",
      syncStatus: "SYNCED",
      payload: {
        nome: "Obra BR-262",
        cidade: "Campo Grande",
        rodovia: "BR-262",
      },
    });
  });
});

describe("obraPdorFromApi", () => {
  it("converte o snapshot PDOR real da API sem inventar valores", () => {
    const pdor = obraPdorFromApi({
      id: "snap-1",
      dataReferencia: "2026-07-01",
      dataExecucao: "2026-07-08T09:00:00",
      statusExecucao: "SUCCESS",
      statusExecucaoLabel: "Concluído",
      calibracao: "CALIBRATION_IN_PROGRESS",
      calibracaoLabel: "Calibração em andamento",
      risco: "MODERATE",
      riscoLabel: "Moderado",
      fase: "PRODUCTION",
      faseLabel: "Produção",
      receitaEstimadaFinal: "912345.67",
      racs: { ponderado: "934000.10", rci: "920000.00" },
      p10: 880000.5,
      p50: "912345.67",
      p80: 940000,
      p95: "955000.00",
      probabilidadeAbaixoContrato: "0.42",
      confianca: 0.61,
      drivers: [
        {
          code: "LOW_REVENUE_CAPTURE",
          description: "Captura de receita abaixo da produção executada",
          impact: "0.31",
          evidence: "RCI=0.87",
        },
        { invalido: true },
      ],
      warnings: ["Há 3 linhas de programação sem quantidade completa.", 7],
      erroExecucao: null,
    });

    expect(pdor.receitaPrevistaFinal).toBe(934000.1);
    expect(pdor.p10).toBe(880000.5);
    expect(pdor.p95).toBe(955000);
    expect(pdor.probabilidadeAbaixoContrato).toBe(0.42);
    expect(pdor.confianca).toBe(0.61);
    expect(pdor.risco).toBe("MODERATE");
    expect(pdor.drivers).toEqual([
      {
        code: "LOW_REVENUE_CAPTURE",
        description: "Captura de receita abaixo da produção executada",
        impact: 0.31,
        evidence: "RCI=0.87",
      },
    ]);
    expect(pdor.warnings).toEqual([
      "Há 3 linhas de programação sem quantidade completa.",
    ]);
  });

  it("usa receitaEstimadaFinal quando o rac ponderado não vem na resposta", () => {
    const pdor = obraPdorFromApi({
      id: "snap-2",
      dataReferencia: null,
      dataExecucao: null,
      statusExecucao: "SUCCESS",
      statusExecucaoLabel: null,
      calibracao: null,
      calibracaoLabel: null,
      risco: null,
      riscoLabel: null,
      fase: null,
      faseLabel: null,
      receitaEstimadaFinal: 500000,
      racs: null,
      p10: null,
      p50: null,
      p80: null,
      p95: null,
      probabilidadeAbaixoContrato: null,
      confianca: null,
      drivers: null,
      warnings: null,
      erroExecucao: null,
    });

    expect(pdor.receitaPrevistaFinal).toBe(500000);
    expect(pdor.drivers).toEqual([]);
    expect(pdor.warnings).toEqual([]);
  });
});
