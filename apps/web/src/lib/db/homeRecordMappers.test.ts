import { describe, expect, it } from "vitest";

import {
  mergeObraRecords,
  obraRecordFromPayload,
  snapshotRecordFromPayload,
  toNumberOrNull,
} from "./homeRecordMappers";
import type { ObraLocalRecord } from "./db.types";

const NOW = "2026-07-06T12:00:00.000Z";

describe("toNumberOrNull", () => {
  it("aceita número, string numérica e rejeita o resto", () => {
    expect(toNumberOrNull(12.5)).toBe(12.5);
    expect(toNumberOrNull("42.7")).toBe(42.7);
    expect(toNumberOrNull("abc")).toBeNull();
    expect(toNumberOrNull(null)).toBeNull();
    expect(toNumberOrNull(undefined)).toBeNull();
  });
});

describe("obraRecordFromPayload", () => {
  it("mapeia payload completo do evento OBRA_ATUALIZADA", () => {
    const record = obraRecordFromPayload(
      {
        obraId: "obra-1",
        codigoContrato: "CT-1",
        nome: "Obra BR-262",
        cliente: "DNIT",
        cidade: "Campo Grande",
        uf: "MS",
        rodovia: "BR-262",
        status: "ATIVA",
        observacoes: "Frente ativa",
        latitude: -20.4697,
        longitude: -54.6201,
        atualizadoEm: "2026-07-06T10:00:00",
      },
      NOW,
    );

    expect(record).not.toBeNull();
    expect(record?.id).toBe("obra-1");
    expect(record?.valorContratual).toBeNull();
    expect(record?.latitude).toBe(-20.4697);
  });

  it("retorna null sem obraId ou nome", () => {
    expect(obraRecordFromPayload({ nome: "X" }, NOW)).toBeNull();
    expect(obraRecordFromPayload({ obraId: "1" }, NOW)).toBeNull();
  });
});

describe("mergeObraRecords", () => {
  it("preserva valorContratual local quando o novo vem nulo", () => {
    const existing: ObraLocalRecord = {
      id: "obra-1",
      codigoContrato: "CT-1",
      nome: "Antiga",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: 1000000,
      updatedAt: NOW,
    };
    const incoming: ObraLocalRecord = {
      ...existing,
      nome: "Nova",
      valorContratual: null,
    };

    const merged = mergeObraRecords(existing, incoming);

    expect(merged.nome).toBe("Nova");
    expect(merged.valorContratual).toBe(1000000);
  });

  it("aplica valorContratual quando o novo traz valor", () => {
    const incoming = {
      id: "obra-1",
      codigoContrato: "CT-1",
      nome: "Obra",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "ATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: 2000000,
      updatedAt: NOW,
    };

    expect(mergeObraRecords(undefined, incoming).valorContratual).toBe(2000000);
  });
});

describe("snapshotRecordFromPayload", () => {
  it("mapeia payload do evento PREVISAO_FINANCEIRA_CALCULADA", () => {
    const record = snapshotRecordFromPayload(
      {
        snapshotId: "snap-1",
        obraId: "obra-1",
        dataReferencia: "2026-07-01",
        statusExecucao: "CALCULADO",
        producaoPlanejada: "500.00",
        producaoRealizada: 240,
        custoRealizado: 40,
        custoPrevistoFinal: 90,
        receitaPrevistaFinal: 120,
      },
      NOW,
    );

    expect(record?.id).toBe("snap-1");
    expect(record?.producaoPlanejada).toBe(500);
    expect(record?.dataReferencia).toBe("2026-07-01");
  });

  it("retorna null sem snapshotId, obraId ou dataReferencia", () => {
    expect(
      snapshotRecordFromPayload({ obraId: "1", dataReferencia: "2026-07-01" }, NOW),
    ).toBeNull();
  });
});
