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
        arquivadoEm: "2026-07-06T09:00:00",
        versaoLinha: 7,
        atualizadoEm: "2026-07-06T10:00:00",
      },
      NOW,
    );

    expect(record).not.toBeNull();
    expect(record?.id).toBe("obra-1");
    expect(record?.valorContratual).toBeNull();
    expect(record?.latitude).toBe(-20.4697);
    expect(record).toMatchObject({
      arquivadoEm: "2026-07-06T09:00:00",
      versaoEntidade: 7,
    });
    expect(record).not.toHaveProperty("syncStatus");
    expect(record).not.toHaveProperty("ultimoErro");
  });

  it("mapeia resposta REST por id e versaoEntidade", () => {
    expect(
      obraRecordFromPayload(
        {
          id: "obra-rest",
          nome: "Obra REST",
          status: "INATIVA",
          arquivadoEm: null,
          versaoEntidade: 11,
          atualizadoEm: "2026-07-06T11:00:00",
        },
        NOW,
      ),
    ).toMatchObject({
      id: "obra-rest",
      status: "INATIVA",
      arquivadoEm: null,
      versaoEntidade: 11,
    });
  });

  it("mapeia versão sem materializar arquivamento nem outros lifecycle omitidos", () => {
    const record = obraRecordFromPayload(
      {
        id: "obra-rest-versionada",
        nome: "Obra REST versionada",
        status: "INATIVA",
        versaoLinha: 12,
      },
      NOW,
    );

    expect(record).toMatchObject({ versaoEntidade: 12 });
    expect(record).not.toHaveProperty("arquivadoEm");
    expect(record).not.toHaveProperty("syncStatus");
    expect(record).not.toHaveProperty("ultimoErro");
  });

  it("mapeia arquivamento sem materializar versão nem outros lifecycle omitidos", () => {
    const record = obraRecordFromPayload(
      {
        id: "obra-rest-arquivada",
        nome: "Obra REST arquivada",
        status: "INATIVA",
        arquivadoEm: null,
      },
      NOW,
    );

    expect(record).toMatchObject({ arquivadoEm: null });
    expect(record).not.toHaveProperty("versaoEntidade");
    expect(record).not.toHaveProperty("syncStatus");
    expect(record).not.toHaveProperty("ultimoErro");
  });

  it("não inventa estado de ciclo de vida quando o payload REST é esparso", () => {
    const record = obraRecordFromPayload(
      {
        id: "obra-rest-esparsa",
        nome: "Obra REST esparsa",
        status: "ATIVA",
      },
      NOW,
    );

    expect(record).not.toBeNull();
    expect(record).not.toHaveProperty("versaoEntidade");
    expect(record).not.toHaveProperty("arquivadoEm");
    expect(record).not.toHaveProperty("syncStatus");
    expect(record).not.toHaveProperty("ultimoErro");
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
      versaoEntidade: 4,
      arquivadoEm: null,
      syncStatus: "SYNCED",
      ultimoErro: null,
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
      versaoEntidade: 2,
      arquivadoEm: null,
      syncStatus: "SYNCED",
      ultimoErro: null,
      updatedAt: NOW,
    };

    expect(mergeObraRecords(undefined, incoming).valorContratual).toBe(2000000);
  });

  it("não sobrescreve snapshot otimista com hidratação REST", () => {
    const pending: ObraLocalRecord = {
      id: "obra-1",
      codigoContrato: "CT-LOCAL",
      nome: "Nome local",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "INATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      versaoEntidade: 4,
      arquivadoEm: "2026-07-06T10:00:00",
      syncStatus: "CONFLICT",
      ultimoErro: "Conflito preservado",
      updatedAt: NOW,
    };
    const remote: ObraLocalRecord = {
      ...pending,
      codigoContrato: "CT-REMOTO",
      nome: "Nome remoto",
      status: "ATIVA",
      arquivadoEm: null,
      versaoEntidade: 5,
      syncStatus: "SYNCED",
      ultimoErro: null,
    };

    expect(mergeObraRecords(pending, remote)).toEqual(pending);
  });

  it("preserva arquivamento sincronizado quando a hidratação omite lifecycle", () => {
    const existing: ObraLocalRecord = {
      id: "obra-1",
      codigoContrato: "CT-LOCAL",
      nome: "Nome arquivado",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "INATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      versaoEntidade: 8,
      arquivadoEm: "2026-07-06T10:00:00.000Z",
      syncStatus: "SYNCED",
      ultimoErro: null,
      updatedAt: NOW,
    };
    const remote: ObraLocalRecord = {
      id: "obra-1",
      codigoContrato: "CT-REMOTO",
      nome: "Nome remoto",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "INATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      updatedAt: NOW,
    };

    expect(mergeObraRecords(existing, remote)).toMatchObject({
      codigoContrato: "CT-REMOTO",
      nome: "Nome remoto",
      versaoEntidade: 8,
      arquivadoEm: "2026-07-06T10:00:00.000Z",
      syncStatus: "SYNCED",
      ultimoErro: null,
    });
  });

  it("atualiza versão sem restaurar obra arquivada quando arquivadoEm foi omitido", () => {
    const existing: ObraLocalRecord = {
      id: "obra-1",
      codigoContrato: "CT-LOCAL",
      nome: "Nome arquivado",
      cliente: null,
      cidade: null,
      uf: null,
      rodovia: null,
      status: "INATIVA",
      observacoes: null,
      latitude: null,
      longitude: null,
      valorContratual: null,
      versaoEntidade: 8,
      arquivadoEm: "2026-07-06T10:00:00.000Z",
      syncStatus: "SYNCED",
      ultimoErro: "Diagnóstico local preservado",
      updatedAt: NOW,
    };
    const incoming = obraRecordFromPayload(
      {
        id: "obra-1",
        nome: "Nome remoto",
        status: "INATIVA",
        versaoEntidade: 9,
      },
      NOW,
    )!;

    expect(mergeObraRecords(existing, incoming)).toMatchObject({
      nome: "Nome remoto",
      versaoEntidade: 9,
      arquivadoEm: "2026-07-06T10:00:00.000Z",
      syncStatus: "SYNCED",
      ultimoErro: "Diagnóstico local preservado",
    });
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
