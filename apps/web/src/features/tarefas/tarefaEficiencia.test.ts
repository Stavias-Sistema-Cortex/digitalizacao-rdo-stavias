import { describe, expect, it } from "vitest";

import type { TarefaRecord } from "../../lib/db/db.types";
import {
  buildTarefaSeries,
  eficienciaConclusao,
  filterTarefaSeriesByPeriod,
} from "./tarefaEficiencia";

let sequence = 0;

function makeTarefa(
  overrides: Partial<TarefaRecord>,
): TarefaRecord {
  sequence += 1;

  return {
    id: `tarefa-${sequence}`,
    obraId: "obra-1",
    equipe: "Pavimentação",
    titulo: "Tarefa",
    observacoes: "",
    criadaPor: "Ana",
    responsavelEquipe: "Bruno",
    prioridade: 2,
    concluida: false,
    concluidaEm: null,
    createdAt: "2026-05-10T08:00:00.000Z",
    updatedAt: "2026-05-10T08:00:00.000Z",
    ...overrides,
  };
}

describe("buildTarefaSeries", () => {
  it("agrupa criadas e concluídas por mês em faixa contínua", () => {
    const tarefas = [
      makeTarefa({
        createdAt: "2026-04-02T10:00:00.000Z",
      }),
      makeTarefa({
        createdAt: "2026-04-20T10:00:00.000Z",
        concluida: true,
        concluidaEm: "2026-06-01T09:00:00.000Z",
      }),
      makeTarefa({
        createdAt: "2026-06-05T10:00:00.000Z",
        concluida: true,
        concluidaEm: "2026-06-06T09:00:00.000Z",
      }),
    ];

    expect(buildTarefaSeries(tarefas)).toEqual([
      { month: "2026-04", criadas: 2, concluidas: 0 },
      { month: "2026-05", criadas: 0, concluidas: 0 },
      { month: "2026-06", criadas: 1, concluidas: 2 },
    ]);
  });

  it("retorna vazio sem tarefas", () => {
    expect(buildTarefaSeries([])).toEqual([]);
  });
});

describe("filterTarefaSeriesByPeriod", () => {
  const points = [
    { month: "2025-11", criadas: 1, concluidas: 0 },
    { month: "2026-02", criadas: 2, concluidas: 1 },
    { month: "2026-04", criadas: 3, concluidas: 2 },
  ];

  it("mantém tudo com ALL", () => {
    expect(
      filterTarefaSeriesByPeriod(points, "ALL"),
    ).toEqual(points);
  });

  it("corta pela janela de meses ancorada no último ponto", () => {
    expect(
      filterTarefaSeriesByPeriod(points, "3M"),
    ).toEqual([
      { month: "2026-02", criadas: 2, concluidas: 1 },
      { month: "2026-04", criadas: 3, concluidas: 2 },
    ]);
  });
});

describe("eficienciaConclusao", () => {
  it("calcula percentual de conclusão", () => {
    const tarefas = [
      makeTarefa({ concluida: true }),
      makeTarefa({ concluida: true }),
      makeTarefa({}),
    ];

    expect(eficienciaConclusao(tarefas)).toEqual({
      total: 3,
      concluidas: 2,
      percentual: 66.7,
    });
  });

  it("retorna percentual nulo sem tarefas", () => {
    expect(eficienciaConclusao([])).toEqual({
      total: 0,
      concluidas: 0,
      percentual: null,
    });
  });
});
