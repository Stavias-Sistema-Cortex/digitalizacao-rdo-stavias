import { describe, expect, it } from "vitest";

import { responderComSnapshotStavia } from "./staviaLocalEngine";
import type {
  StaviaSnapshot,
  StaviaSnapshotTarefa,
} from "./stavia.types";

function makeTarefa(
  partial: Partial<StaviaSnapshotTarefa> & { id: string },
): StaviaSnapshotTarefa {
  return {
    obraId: "obra-1",
    equipe: "Pavimentação",
    titulo: "Tarefa",
    observacoes: null,
    criadaPor: "João Lucas",
    criadaPorColaboradorId: null,
    responsavelEquipe: "Carlos Meireles",
    responsavelColaboradorId: null,
    prioridade: 2,
    concluida: false,
    concluidaEm: null,
    createdAt: "2026-07-01T09:00:00.000Z",
    updatedAt: "2026-07-01T09:00:00.000Z",
    ...partial,
  };
}

function makeSnapshot(
  tarefas: StaviaSnapshotTarefa[],
): StaviaSnapshot {
  return {
    metadata: {
      snapshotKey: "default",
      generatedAt: "2026-07-10T12:00:00",
      databaseUpdatedAt: null,
      localSyncedAt: null,
      source: "TEST",
      status: "LOCAL",
      dictionaryVersion: "test",
    },
    obras: [
      {
        id: "obra-1",
        codigoContrato: "CT-2026-001",
        codigoCw: null,
        codigoInterno: null,
        nome: "Duplicação BR-040",
        cliente: null,
        cidade: "Curvelo",
        uf: "MG",
        rodovia: "BR-040",
        status: null,
        updatedAt: null,
      },
    ],
    rdos: [],
    programacoes: [],
    pdors: [],
    operationalEvents: [],
    tarefas,
  };
}

function ask(
  snapshot: StaviaSnapshot,
  pergunta: string,
) {
  return responderComSnapshotStavia({
    snapshot,
    pergunta,
    context: {},
    isOnline: false,
  });
}

describe("staviaLocalEngine · tarefas", () => {
  const snapshot = makeSnapshot([
    makeTarefa({
      id: "t1",
      titulo: "Sinalizar desvio noturno",
      criadaPor: "João Lucas",
      createdAt: "2026-07-05T08:30:00.000Z",
    }),
    makeTarefa({
      id: "t2",
      titulo: "Aferir usina",
      criadaPor: "João Lucas",
      concluida: true,
      concluidaEm: "2026-07-08T10:00:00.000Z",
      createdAt: "2026-07-02T11:00:00.000Z",
    }),
    makeTarefa({
      id: "t3",
      titulo: "Organizar canteiro",
      equipe: "Drenagem",
      criadaPor: "Maria José Antônio",
      createdAt: "2026-07-06T14:00:00.000Z",
    }),
  ]);

  it("encaminha a autoria de criação de tarefa para Home > Memória", () => {
    const response = ask(
      snapshot,
      "JOAO lucas criou alguma tarefa?",
    );

    expect(response).not.toBeNull();
    expect(response?.intent).toBe("TIMELINE_OPERACIONAL");
    expect(response?.answer.answer).toContain("Home → Memória");
    expect(response?.answer.answer).not.toContain("Sinalizar desvio noturno");
  });

  it("encaminha a data de conclusão de tarefa para Home > Memória", () => {
    const response = ask(
      snapshot,
      "Quando a tarefa Aferir usina foi concluída?",
    );

    expect(response?.intent).toBe("TIMELINE_OPERACIONAL");
    expect(response?.answer.answer).toContain("Home → Memória");
  });

  it("encaminha o campo data de conclusão para Home > Memória", () => {
    const response = ask(
      snapshot,
      "Qual a data de conclusão da tarefa Aferir usina?",
    );

    expect(response?.intent).toBe("TIMELINE_OPERACIONAL");
    expect(response?.answer.answer).toContain("Home → Memória");
  });

  it("filtra por responsável da equipe", () => {
    const response = ask(
      snapshot,
      "quais tarefas estão sob responsabilidade do carlos meireles?",
    );

    expect(response?.intent).toBe("TAREFAS");
    expect(response?.answer.answer).toContain(
      "Carlos Meireles",
    );
  });

  it("não usa a autoria como filtro fora de Home > Memória", () => {
    const response = ask(
      snapshot,
      "quais tarefas de joao lucas?",
    );

    expect(response?.answer.answer).toContain("Não encontrei tarefas");
    expect(response?.answer.answer).not.toContain(
      "Sinalizar desvio noturno",
    );
    expect(response?.answer.answer).not.toContain("Aferir usina");
  });

  it("filtra tarefas concluídas", () => {
    const response = ask(
      snapshot,
      "quais tarefas foram concluidas?",
    );

    expect(response?.answer.answer).toContain(
      "Aferir usina",
    );
    expect(response?.answer.answer).not.toContain(
      "Sinalizar desvio noturno",
    );
  });

  it("lista tudo quando não há pessoa na pergunta", () => {
    const response = ask(snapshot, "quais tarefas existem?");

    expect(response?.answer.answer).toContain(
      "3 tarefa(s)",
    );
    expect(response?.answer.answer).toContain("pendente");
    expect(response?.answer.answer).toContain("concluída");
    expect(response?.answer.answer).not.toContain("João Lucas");
    expect(response?.answer.answer).not.toMatch(/criada em/i);
    expect(response?.answer.answer).not.toMatch(/concluída em/i);
    expect(response?.answer.sources[0]?.attributes).not.toHaveProperty(
      "criadaPor",
    );
    expect(response?.answer.sources[0]?.attributes).not.toHaveProperty(
      "criadaEm",
    );
    expect(response?.answer.sources[0]?.attributes).not.toHaveProperty(
      "concluidaEm",
    );
  });

  it("recorta por equipe quando o nome não é de pessoa", () => {
    const response = ask(
      snapshot,
      "tarefas da equipe drenagem",
    );

    expect(response?.answer.answer).toContain(
      "Organizar canteiro",
    );
    expect(response?.answer.answer).not.toContain(
      "Aferir usina",
    );
  });

  it("não trata dêiticos como nome de pessoa", () => {
    const response = ask(
      snapshot,
      "quais tarefas desta obra?",
    );

    expect(response?.answer.answer).toContain(
      "3 tarefa(s)",
    );
  });

  it("recorta pelo nome da obra", () => {
    const foraDaObra = makeTarefa({
      id: "t4",
      obraId: "obra-2",
      titulo: "Tarefa de outra obra",
    });
    const comDuasObras = makeSnapshot([
      ...(snapshot.tarefas ?? []),
      foraDaObra,
    ]);

    const response = ask(
      comDuasObras,
      "tarefas da obra duplicacao br-040",
    );

    expect(response?.answer.answer).toContain(
      "Organizar canteiro",
    );
    expect(response?.answer.answer).not.toContain(
      "Tarefa de outra obra",
    );
  });

  it("mantém a consulta de responsabilidades no estado atual", () => {
    const response = ask(
      snapshot,
      "quais tarefas estão sob responsabilidade de ninguém?",
    );

    expect(response?.answer.answer).toContain(
      "Não encontrei tarefas",
    );
  });

  it("orienta quando ainda não há tarefas registradas", () => {
    const response = ask(
      makeSnapshot([]),
      "tem alguma tarefa?",
    );

    expect(response?.answer.insufficientData).toBe(true);
  });
});
