import { describe, expect, it } from "vitest";

import type { PrevisaoSnapshotRecord } from "../../lib/db/db.types";
import {
  buildMonthlySeries,
  filterByPeriod,
  ratioPct,
} from "./progressSeries";

function snapshot(
  partial: Partial<PrevisaoSnapshotRecord>,
): PrevisaoSnapshotRecord {
  return {
    id: partial.id ?? crypto.randomUUID(),
    obraId: "obra-1",
    dataReferencia: partial.dataReferencia ?? "2026-06-15",
    statusExecucao: partial.statusExecucao ?? "CALCULADO",
    producaoPlanejada: partial.producaoPlanejada ?? 500,
    producaoRealizada: partial.producaoRealizada ?? 240,
    custoRealizado: partial.custoRealizado ?? 40,
    custoPrevistoFinal: partial.custoPrevistoFinal ?? 90,
    receitaPrevistaFinal:
      partial.receitaPrevistaFinal ?? 120,
    updatedAt: "2026-07-06T12:00:00.000Z",
  };
}

describe("ratioPct", () => {
  it("calcula percentual com 1 casa e trata denominador inválido", () => {
    expect(ratioPct(240, 500)).toBe(48);
    expect(ratioPct(1, 3)).toBe(33.3);
    expect(ratioPct(10, 0)).toBeNull();
    expect(ratioPct(10, null)).toBeNull();
    expect(ratioPct(null, 100)).toBeNull();
  });
});

describe("buildMonthlySeries", () => {
  it("usa o último snapshot de cada mês, ignora não calculados e ordena", () => {
    const points = buildMonthlySeries(
      [
        snapshot({
          dataReferencia: "2026-06-10",
          producaoRealizada: 200,
        }),
        snapshot({
          dataReferencia: "2026-06-25",
          producaoRealizada: 240,
        }),
        snapshot({
          dataReferencia: "2026-05-31",
          producaoRealizada: 150,
        }),
        snapshot({
          dataReferencia: "2026-07-01",
          statusExecucao: "DADOS_INSUFICIENTES",
        }),
      ],
      1000,
    );

    expect(points.map((p) => p.month)).toEqual([
      "2026-05",
      "2026-06",
    ]);
    expect(points[1].fisicoPct).toBe(48);
    expect(points[1].custoPct).toBe(44.4);
    expect(points[1].pdorPct).toBe(12);
  });

  it("pdor fica nulo sem valor contratual", () => {
    const points = buildMonthlySeries(
      [snapshot({ dataReferencia: "2026-06-25" })],
      null,
    );

    expect(points[0].pdorPct).toBeNull();
    expect(points[0].fisicoPct).toBe(48);
  });
});

describe("filterByPeriod", () => {
  const points = [
    "2025-08",
    "2025-12",
    "2026-03",
    "2026-05",
    "2026-06",
  ].map((month) => ({
    month,
    fisicoPct: 1,
    custoPct: 1,
    pdorPct: 1,
  }));

  it("3M mantém meses dentro da janela a partir do último", () => {
    expect(
      filterByPeriod(points, "3M").map((p) => p.month),
    ).toEqual(["2026-05", "2026-06"]);
  });

  it("12M corta meses mais antigos que um ano", () => {
    expect(
      filterByPeriod(points, "12M").map((p) => p.month),
    ).toEqual(["2025-08", "2025-12", "2026-03", "2026-05", "2026-06"]);
  });

  it("ALL devolve tudo", () => {
    expect(filterByPeriod(points, "ALL")).toHaveLength(5);
  });
});
