import "fake-indexeddb/auto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import {
  createTarefa,
  deleteTarefa,
  setTarefaConclusao,
} from "./tarefaRepository";
import {
  applyPulledEventsAtomically,
  applyPushResultAtomically,
} from "../sync/syncStorage";

const actorId = "00000000-0000-4000-8000-000000000161";
const obraId = "00000000-0000-4000-8000-000000000162";
const actor = {
  colaboradorId: actorId,
  nome: "Encarregada de campo",
};

describe("tarefaRepository", () => {
  beforeEach(() => {
    vi.stubGlobal("BroadcastChannel", undefined);
    setSession({
      colaboradorId: actorId,
      nome: actor.nome,
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [obraId],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
  });

  afterEach(async () => {
    await closeCortexDb();
    clearSession();
    vi.unstubAllGlobals();
  });

  it("cria a tarefa, o envelope v13 e o evento ontológico na mesma transação", async () => {
    const tarefa = await createTarefa({
      obraId,
      equipe: "Fresagem",
      titulo: "Sinalizar desvio",
      observacoes: "Antes do início do turno.",
      criadaPor: actor.nome,
      criadaPorColaboradorId: actorId,
      responsavelEquipe: "Equipe Fresagem",
      responsavelColaboradorId: null,
      prioridade: 1,
    }, actor);
    const database = await getCortexDb();
    const [mutation] = (await database.getAll("outbox_mutations"))
      .filter((candidate) => candidate.entidadeId === tarefa.id);
    const [event] = (await database.getAll("operational_events"))
      .filter((candidate) => candidate.principalEntity.id === tarefa.id);

    expect(mutation).toMatchObject({
      contractVersion: 13,
      entidadeTipo: "TAREFA",
      entidadeId: tarefa.id,
      operacao: "CRIAR_TAREFA",
      baseVersao: null,
      trace: {
        actorId,
        authorizationScope: [obraId],
      },
    });
    expect(event).toMatchObject({
      contractVersion: 13,
      type: "TAREFA_CRIADA",
      clientMutationId: mutation?.clientMutationId,
      principalEntity: {
        tipo: "TAREFA",
        id: tarefa.id,
      },
    });
  });

  it("encadeia conclusão e exclusão sem perder a trilha offline", async () => {
    const criada = await createTarefa({
      obraId,
      equipe: "Fresagem",
      titulo: "Inspecionar junta",
      observacoes: "",
      criadaPor: actor.nome,
      criadaPorColaboradorId: actorId,
      responsavelEquipe: "Equipe Fresagem",
      responsavelColaboradorId: null,
      prioridade: 2,
    }, actor);

    await setTarefaConclusao(criada.id, true, actor);
    await deleteTarefa(criada.id, actor);

    const database = await getCortexDb();
    const mutations = (await database.getAll("outbox_mutations"))
      .filter((mutation) => mutation.entidadeId === criada.id);
    const events = (await database.getAll("operational_events"))
      .filter((event) => event.principalEntity.id === criada.id);
    const stored = await database.get("tarefas", criada.id);

    const creation = mutations.find(
      (mutation) => mutation.operacao === "CRIAR_TAREFA",
    );
    const conclusion = mutations.find(
      (mutation) => mutation.operacao === "CONCLUIR_TAREFA",
    );
    const deletion = mutations.find(
      (mutation) => mutation.operacao === "EXCLUIR_TAREFA",
    );

    expect(mutations).toHaveLength(3);
    expect(conclusion?.dependsOnMutationIds).toEqual([
      creation?.clientMutationId,
    ]);
    expect(deletion?.dependsOnMutationIds).toEqual([
      conclusion?.clientMutationId,
    ]);
    expect(conclusion?.baseVersao).toBe(1);
    expect(deletion?.baseVersao).toBe(2);
    expect(events).toHaveLength(3);
    expect(events.map((event) => event.type)).toEqual(expect.arrayContaining([
      "TAREFA_CRIADA",
      "TAREFA_CONCLUIDA",
      "TAREFA_EXCLUIDA",
    ]));
    expect(stored?.deletadaEm).toEqual(expect.any(String));
  });

  it("materializa a confirmação do servidor na tarefa, sem apagar a fila posterior", async () => {
    const criada = await createTarefa({
      obraId,
      equipe: "Fresagem",
      titulo: "Confirmar sinalização",
      observacoes: "",
      criadaPor: actor.nome,
      criadaPorColaboradorId: actorId,
      responsavelEquipe: "Equipe Fresagem",
      responsavelColaboradorId: null,
      prioridade: 1,
    }, actor);
    await setTarefaConclusao(criada.id, true, actor);

    const database = await getCortexDb();
    const creation = (await database.getAll("outbox_mutations"))
      .find((mutation) =>
        mutation.entidadeId === criada.id &&
        mutation.operacao === "CRIAR_TAREFA"
      );

    expect(creation).toBeDefined();
    await database.put("outbox_mutations", {
      ...creation!,
      status: "SYNCING",
      tentativas: 1,
    });

    await applyPushResultAtomically({
      clientMutationId: creation!.clientMutationId,
      status: "APLICADA",
      entidadeTipo: "TAREFA",
      entidadeId: criada.id,
      resultado: { versaoEntidade: 1 },
    });

    await expect(database.get("tarefas", criada.id)).resolves.toMatchObject({
      versaoEntidade: 1,
      syncStatus: "PENDING_SYNC",
      concluida: true,
    });
  });

  it("materializa uma tarefa recebida pelo pull com tombstone preservado", async () => {
    const database = await getCortexDb();
    const remoteTaskId = "00000000-0000-4000-8000-000000000199";

    await applyPulledEventsAtomically([
      {
        commitSeq: 41,
        eventoId: "00000000-0000-4000-8000-000000000198",
        tipoEvento: "TAREFA_EXCLUIDA",
        entidadeTipo: "TAREFA",
        entidadeId: remoteTaskId,
        versaoEntidade: 3,
        payload: {
          id: remoteTaskId,
          obraId,
          equipe: "Fresagem",
          titulo: "Tarefa remota",
          observacoes: "Encerrada em outro dispositivo.",
          criadaPor: "Encarregada remota",
          criadaPorColaboradorId: actorId,
          responsavelEquipe: "Equipe Fresagem",
          responsavelColaboradorId: null,
          prioridade: 2,
          concluida: true,
          concluidaEm: "2026-07-20T12:00:00.000Z",
          createdAt: "2026-07-20T10:00:00.000Z",
          updatedAt: "2026-07-20T12:00:00.000Z",
          deletadaEm: "2026-07-20T12:00:00.000Z",
        },
      },
    ], 41);

    await expect(database.get("tarefas", remoteTaskId)).resolves.toMatchObject({
      id: remoteTaskId,
      versaoEntidade: 3,
      syncStatus: "SYNCED",
      concluida: true,
      deletadaEm: "2026-07-20T12:00:00.000Z",
    });
  });

  it("não deixa o pull remoto substituir uma tarefa ainda pendente localmente", async () => {
    const local = await createTarefa({
      obraId,
      equipe: "Fresagem",
      titulo: "Registro local pendente",
      observacoes: "",
      criadaPor: actor.nome,
      criadaPorColaboradorId: actorId,
      responsavelEquipe: "Equipe Fresagem",
      responsavelColaboradorId: null,
      prioridade: 3,
    }, actor);
    const database = await getCortexDb();

    await applyPulledEventsAtomically([
      {
        commitSeq: 42,
        eventoId: "00000000-0000-4000-8000-000000000197",
        tipoEvento: "TAREFA_CRIADA",
        entidadeTipo: "TAREFA",
        entidadeId: local.id,
        versaoEntidade: 8,
        payload: {
          id: local.id,
          obraId,
          equipe: "Fresagem",
          titulo: "Versão remota que não pode substituir o rascunho",
          observacoes: "",
          criadaPor: "Outro dispositivo",
          criadaPorColaboradorId: actorId,
          responsavelEquipe: "Equipe Fresagem",
          responsavelColaboradorId: null,
          prioridade: 1,
          concluida: false,
          concluidaEm: null,
          createdAt: "2099-01-01T10:00:00.000Z",
          updatedAt: "2099-01-01T10:00:00.000Z",
          deletadaEm: null,
        },
      },
    ], 42);

    await expect(database.get("tarefas", local.id)).resolves.toMatchObject({
      titulo: "Registro local pendente",
      syncStatus: "PENDING_SYNC",
      versaoEntidade: null,
    });
  });
});
