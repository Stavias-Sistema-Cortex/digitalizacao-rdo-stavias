import { describe, expect, it } from "vitest";

import {
  obraRecordFromApi,
  snapshotRecordFromApi,
} from "./homeHydration";

const NOW = "2026-07-06T12:00:00.000Z";

describe("obraRecordFromApi", () => {
  it("normaliza números vindos como string e aplica valorContratual", () => {
    const record = obraRecordFromApi(
      {
        id: "obra-1",
        codigoContrato: "CT-1",
        nome: "Obra BR-262",
        cliente: "DNIT",
        cidade: null,
        uf: "MS",
        rodovia: "BR-262",
        status: "ATIVA",
        observacoes: null,
        latitude: "-20.4697",
        longitude: "-54.6201",
        valorContratual: "1500000.00",
        atualizadoEm: "2026-07-06T10:00:00",
        versaoLinha: 7,
      },
      NOW,
    );

    expect(record.valorContratual).toBe(1500000);
    expect(record.latitude).toBe(-20.4697);
    expect(record.nome).toBe("Obra BR-262");
    expect(record.versaoEntidade).toBe(7);
    expect(record).toHaveProperty("arquivadoEm", null);
  });
});

describe("snapshotRecordFromApi", () => {
  it("descarta histórico sem status em vez de inventar cálculo concluído", () => {
    const record = snapshotRecordFromApi(
      {
        id: "snap-sem-status",
        obra: { id: "obra-1" },
        dataReferencia: "2026-06-30",
        statusExecucao: null,
        producaoPlanejada: 500,
        producaoRealizada: 240,
        producaoApontada: 410,
        custoRealizado: null,
        custoPrevistoFinal: null,
        receitaPrevistaFinal: 120,
        evidenceIds: ["evidence-1"],
        coverageCode: "COMPLETE_ACCEPTED_EXACT",
        stale: false,
        current: true,
      },
      NOW,
    );

    expect(record).toBeNull();
  });

  it("mapeia histórico e descarta itens sem dataReferencia", () => {
    const ok = snapshotRecordFromApi(
      {
        id: "snap-1",
        obra: { id: "obra-1" },
        dataReferencia: "2026-06-30",
        statusExecucao: "SUCCESS",
        producaoPlanejada: 500,
        producaoRealizada: 240,
        producaoApontada: 410,
        custoRealizado: 40,
        custoPrevistoFinal: 90,
        receitaPrevistaFinal: 120,
        versaoModelo: "PDOR-0.5.1",
        versaoPremissas: "PDOR-ASSUMPTIONS-0.5.0",
        algorithmVersion: "PDOR-REVENUE-2",
        evidenceIds: ["evidence-1"],
        coverageCode: "COMPLETE_ACCEPTED_EXACT",
        stale: false,
        current: true,
      },
      NOW,
    );
    const missing = snapshotRecordFromApi(
      {
        id: "snap-2",
        obra: { id: "obra-1" },
        dataReferencia: null,
        statusExecucao: null,
        producaoPlanejada: null,
        producaoRealizada: null,
        producaoApontada: null,
        custoRealizado: null,
        custoPrevistoFinal: null,
        receitaPrevistaFinal: null,
        evidenceIds: [],
        coverageCode: "NO_ACCEPTED_EVIDENCE",
        stale: false,
        current: true,
      },
      NOW,
    );

    expect(ok).toMatchObject({
      dataReferencia: "2026-06-30",
      producaoRealizada: 240,
      producaoApontada: 410,
      custoRealizado: null,
      custoPrevistoFinal: null,
      receitaPrevistaFinal: 120,
      evidenceIds: ["evidence-1"],
      coverageCode: "COMPLETE_ACCEPTED_EXACT",
      stale: false,
      current: true,
    });
    expect(missing).toBeNull();
  });

  it("descarta SUCCESS sem evidência aceita e snapshots que já não são atuais", () => {
    const base = {
      id: "snap-1",
      obra: { id: "obra-1" },
      dataReferencia: "2026-06-30",
      statusExecucao: "SUCCESS",
      producaoPlanejada: 500,
      producaoRealizada: 240,
      producaoApontada: 410,
      custoRealizado: null,
      custoPrevistoFinal: null,
      receitaPrevistaFinal: 120,
      versaoModelo: "PDOR-0.5.1",
      versaoPremissas: "PDOR-ASSUMPTIONS-0.5.0",
      algorithmVersion: "PDOR-REVENUE-2",
      stale: false,
      current: true,
    };

    expect(snapshotRecordFromApi({
      ...base,
      evidenceIds: [],
      coverageCode: "NO_ACCEPTED_EVIDENCE",
    }, NOW)).toBeNull();
    expect(snapshotRecordFromApi({
      ...base,
      evidenceIds: ["evidence-1"],
      coverageCode: "COMPLETE_ACCEPTED_EXACT",
      stale: true,
      current: false,
    }, NOW)).toBeNull();
  });

  it.each([
    ["algoritmo v1", { algorithmVersion: "PDOR-REVENUE-1" }],
    ["modelo anterior", { versaoModelo: "PDOR-0.5.0" }],
    ["premissas anteriores", { versaoPremissas: "PDOR-ASSUMPTIONS-0.4.0" }],
  ])("descarta histórico SUCCESS incompatível: %s", (_label, legacy) => {
    expect(snapshotRecordFromApi({
      id: "snap-legado",
      obra: { id: "obra-1" },
      dataReferencia: "2026-06-30",
      statusExecucao: "SUCCESS",
      producaoPlanejada: 500,
      producaoRealizada: 240,
      producaoApontada: 410,
      custoRealizado: null,
      custoPrevistoFinal: null,
      receitaPrevistaFinal: 120,
      versaoModelo: "PDOR-0.5.1",
      versaoPremissas: "PDOR-ASSUMPTIONS-0.5.0",
      algorithmVersion: "PDOR-REVENUE-2",
      evidenceIds: ["evidence-1"],
      coverageCode: "COMPLETE_ACCEPTED_EXACT",
      stale: false,
      current: true,
      ...legacy,
    }, NOW)).toBeNull();
  });

  it("preserva o snapshot atual de dados insuficientes para suprimir o SUCCESS anterior", () => {
    const record = snapshotRecordFromApi({
      id: "snap-current-insufficient",
      obra: { id: "obra-1" },
      dataReferencia: "2026-06-30",
      statusExecucao: "INSUFFICIENT_DATA",
      producaoPlanejada: null,
      producaoRealizada: null,
      producaoApontada: null,
      custoRealizado: null,
      custoPrevistoFinal: null,
      receitaPrevistaFinal: null,
      versaoModelo: "PDOR-0.5.1",
      versaoPremissas: "PDOR-ASSUMPTIONS-0.5.0",
      algorithmVersion: "PDOR-REVENUE-2",
      evidenceIds: [],
      coverageCode: "NO_ACCEPTED_EVIDENCE",
      stale: false,
      current: true,
    }, NOW);

    expect(record).toMatchObject({
      id: "snap-current-insufficient",
      statusExecucao: "INSUFFICIENT_DATA",
      evidenceIds: [],
      coverageCode: "NO_ACCEPTED_EVIDENCE",
      stale: false,
      current: true,
    });
  });
});
