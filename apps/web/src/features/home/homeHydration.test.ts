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
      },
      NOW,
    );

    expect(record.valorContratual).toBe(1500000);
    expect(record.latitude).toBe(-20.4697);
    expect(record.nome).toBe("Obra BR-262");
  });
});

describe("snapshotRecordFromApi", () => {
  it("mapeia histórico e descarta itens sem dataReferencia", () => {
    const ok = snapshotRecordFromApi(
      {
        id: "snap-1",
        obraId: "obra-1",
        dataReferencia: "2026-06-30",
        statusExecucao: "CALCULADO",
        producaoPlanejada: 500,
        producaoRealizada: 240,
        custoRealizado: 40,
        custoPrevistoFinal: 90,
        receitaPrevistaFinal: 120,
      },
      NOW,
    );
    const missing = snapshotRecordFromApi(
      {
        id: "snap-2",
        obraId: "obra-1",
        dataReferencia: null,
        statusExecucao: null,
        producaoPlanejada: null,
        producaoRealizada: null,
        custoRealizado: null,
        custoPrevistoFinal: null,
        receitaPrevistaFinal: null,
      },
      NOW,
    );

    expect(ok?.dataReferencia).toBe("2026-06-30");
    expect(missing).toBeNull();
  });
});
