import { describe, expect, it } from "vitest";

import type {
  LocalRdoRecord,
  TarefaRecord,
} from "../../lib/db/db.types";
import {
  equipesDaObra,
  responsaveisSugeridos,
} from "./equipesDaObra";

function makeRdo(
  payload: Record<string, unknown>,
): LocalRdoRecord {
  return {
    id: "rdo-1",
    obraId: "obra-1",
    programacaoId: null,
    numeroRdo: "001",
    dataRdo: "2026-07-01",
    statusRdo: "RASCUNHO",
    syncStatus: "LOCAL_ONLY",
    versaoEntidade: null,
    payload,
    createdAt: "2026-07-01T08:00:00.000Z",
    updatedAt: "2026-07-01T08:00:00.000Z",
  };
}

function makeTarefa(
  overrides: Partial<TarefaRecord>,
): TarefaRecord {
  return {
    id: "tarefa-1",
    obraId: "obra-1",
    equipe: "Equipe Pavimentação",
    titulo: "Conferir sinalização",
    observacoes: "",
    criadaPor: "Ana",
    responsavelEquipe: "Bruno",
    prioridade: 2,
    concluida: false,
    concluidaEm: null,
    createdAt: "2026-07-01T08:00:00.000Z",
    updatedAt: "2026-07-01T08:00:00.000Z",
    ...overrides,
  };
}

describe("equipesDaObra", () => {
  it("extrai equipes das alocações dos RDOs", () => {
    const rdo = makeRdo({
      alocacoesColaboradores: [
        { equipe: "Terraplenagem" },
        { equipe: "Pavimentação " },
        { equipe: "" },
        { semEquipe: true },
      ],
    });

    expect(equipesDaObra([rdo], [])).toEqual([
      "Pavimentação",
      "Terraplenagem",
    ]);
  });

  it("une equipes de tarefas existentes sem duplicar por caixa", () => {
    const rdo = makeRdo({
      alocacoesColaboradores: [
        { equipe: "pavimentação" },
      ],
    });
    const tarefa = makeTarefa({
      equipe: "Pavimentação",
    });
    const outra = makeTarefa({
      id: "tarefa-2",
      equipe: "Drenagem",
    });

    expect(equipesDaObra([rdo], [tarefa, outra])).toEqual([
      "Drenagem",
      "pavimentação",
    ]);
  });

  it("ignora payload sem alocações", () => {
    expect(equipesDaObra([makeRdo({})], [])).toEqual([]);
  });
});

describe("responsaveisSugeridos", () => {
  it("junta nomes do RDO e das tarefas sem repetir", () => {
    const rdo = makeRdo({
      encarregadoObra: "Carlos Silva",
      apontadorRdo: "Dora",
      maoObra: [
        { nomeColaborador: "Edu" },
        { nomeColaborador: "carlos silva" },
        { nomeColaborador: "" },
      ],
    });
    const tarefa = makeTarefa({
      responsavelEquipe: "Fabiana",
    });

    expect(
      responsaveisSugeridos([rdo], [tarefa]),
    ).toEqual(["Carlos Silva", "Dora", "Edu", "Fabiana"]);
  });
});
