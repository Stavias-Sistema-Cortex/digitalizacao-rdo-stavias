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
  // Espalhado por cima dos padrões, e não com `??`, para que um null
  // explícito no caso continue null em vez de cair no valor de fábrica.
  return {
    id: crypto.randomUUID(),
    obraId: "obra-1",
    dataReferencia: "2026-06-15",
    statusExecucao: "CALCULADO",
    producaoPlanejada: 500,
    producaoRealizada: 240,
    producaoApontada: 400,
    custoRealizado: 40,
    custoPrevistoFinal: 90,
    receitaPrevistaFinal: 120,
    updatedAt: "2026-07-06T12:00:00.000Z",
    ...partial,
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
    expect(points[1].pdorPct).toBe(12);
    expect(points[1]).not.toHaveProperty("custoPct");
  });

  it("mede a produção apontada contra o mesmo planejado, sem misturá-la ao avanço medido", () => {
    const points = buildMonthlySeries(
      [
        snapshot({
          dataReferencia: "2026-06-10",
          producaoPlanejada: 500,
          producaoRealizada: 100,
          producaoApontada: 350,
        }),
      ],
      1000,
    );

    expect(points[0].fisicoPct).toBe(20);
    expect(points[0].apontadaPct).toBe(70);
  });

  it("deixa a produção apontada em branco no registro antigo que não a tem", () => {
    const points = buildMonthlySeries(
      [
        snapshot({
          dataReferencia: "2026-06-10",
          producaoApontada: null,
        }),
      ],
      1000,
    );

    expect(points[0].apontadaPct).toBeNull();
    expect(points[0].fisicoPct).toBe(48);
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
