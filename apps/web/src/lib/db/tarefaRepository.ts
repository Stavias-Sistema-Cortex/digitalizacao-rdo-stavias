import { getCortexDb } from "./cortexDb";
import { addOperationalEvent } from "./operationalEventRepository";
import type {
  OperationalEntityRef,
  TarefaPrioridade,
  TarefaRecord,
} from "./db.types";

function createTarefaId(): string {
  if ("randomUUID" in crypto) {
    return crypto.randomUUID();
  }

  return `tarefa-${Date.now()}-${Math.random()
    .toString(16)
    .slice(2)}`;
}

/** Quem executou a ação — vai para o evento ontológico. */
export interface TarefaAtor {
  colaboradorId: string | null;
  nome: string;
}

export interface NovaTarefaInput {
  obraId: string;
  equipe: string;
  titulo: string;
  observacoes: string;
  criadaPor: string;
  criadaPorColaboradorId: string | null;
  responsavelEquipe: string;
  responsavelColaboradorId: string | null;
  prioridade: TarefaPrioridade;
}

function tarefaEntity(
  tarefa: TarefaRecord,
): OperationalEntityRef {
  return {
    tipo: "TAREFA",
    id: tarefa.id,
    nome: tarefa.titulo,
  };
}

function tarefaRelatedEntities(
  tarefa: TarefaRecord,
): OperationalEntityRef[] {
  const related: OperationalEntityRef[] = [
    {
      tipo: "OBRA",
      id: tarefa.obraId,
      nome: null,
    },
  ];

  if (tarefa.equipe.trim()) {
    related.push({
      tipo: "EQUIPE",
      id: tarefa.equipe,
      nome: tarefa.equipe,
    });
  }

  if (tarefa.responsavelEquipe.trim()) {
    related.push({
      tipo: "COLABORADOR",
      id:
        tarefa.responsavelColaboradorId ??
        tarefa.responsavelEquipe,
      nome: tarefa.responsavelEquipe,
    });
  }

  return related;
}

function tarefaEventPayload(
  tarefa: TarefaRecord,
): Record<string, unknown> {
  return {
    titulo: tarefa.titulo,
    equipe: tarefa.equipe,
    prioridade: tarefa.prioridade,
    observacoes: tarefa.observacoes,
    criadaPor: tarefa.criadaPor,
    criadaPorColaboradorId: tarefa.criadaPorColaboradorId,
    responsavelEquipe: tarefa.responsavelEquipe,
    responsavelColaboradorId:
      tarefa.responsavelColaboradorId,
    concluida: tarefa.concluida,
    concluidaEm: tarefa.concluidaEm,
    criadaEm: tarefa.createdAt,
  };
}

async function registrarEventoTarefa(
  type:
    | "TAREFA_CRIADA"
    | "TAREFA_CONCLUIDA"
    | "TAREFA_REABERTA"
    | "TAREFA_EXCLUIDA",
  tarefa: TarefaRecord,
  ator: TarefaAtor,
): Promise<void> {
  await addOperationalEvent({
    type,
    principalEntity: tarefaEntity(tarefa),
    relatedEntities: tarefaRelatedEntities(tarefa),
    obraId: tarefa.obraId,
    colaboradorId: ator.colaboradorId,
    responsibleUserId: ator.colaboradorId,
    responsibleUserName: ator.nome || null,
    payload: tarefaEventPayload(tarefa),
  });
}

export async function listTarefasByObra(
  obraId: string,
): Promise<TarefaRecord[]> {
  const database = await getCortexDb();
  return database.getAllFromIndex(
    "tarefas",
    "by-obra-id",
    obraId,
  );
}

export async function listAllTarefas(): Promise<
  TarefaRecord[]
> {
  const database = await getCortexDb();
  return database.getAll("tarefas");
}

export async function createTarefa(
  input: NovaTarefaInput,
  ator: TarefaAtor,
): Promise<TarefaRecord> {
  const now = new Date().toISOString();
  const record: TarefaRecord = {
    id: createTarefaId(),
    obraId: input.obraId,
    equipe: input.equipe.trim(),
    titulo: input.titulo.trim(),
    observacoes: input.observacoes.trim(),
    criadaPor: input.criadaPor.trim(),
    criadaPorColaboradorId:
      input.criadaPorColaboradorId,
    responsavelEquipe: input.responsavelEquipe.trim(),
    responsavelColaboradorId:
      input.responsavelColaboradorId,
    prioridade: input.prioridade,
    concluida: false,
    concluidaEm: null,
    createdAt: now,
    updatedAt: now,
  };

  const database = await getCortexDb();
  await database.put("tarefas", record);
  await registrarEventoTarefa(
    "TAREFA_CRIADA",
    record,
    ator,
  );

  return record;
}

export async function setTarefaConclusao(
  id: string,
  concluida: boolean,
  ator: TarefaAtor,
): Promise<TarefaRecord | undefined> {
  const database = await getCortexDb();
  const existing = await database.get("tarefas", id);

  if (!existing) {
    return undefined;
  }

  const now = new Date().toISOString();
  const updated: TarefaRecord = {
    ...existing,
    concluida,
    concluidaEm: concluida ? now : null,
    updatedAt: now,
  };

  await database.put("tarefas", updated);
  await registrarEventoTarefa(
    concluida ? "TAREFA_CONCLUIDA" : "TAREFA_REABERTA",
    updated,
    ator,
  );

  return updated;
}

export async function deleteTarefa(
  id: string,
  ator: TarefaAtor,
): Promise<void> {
  const database = await getCortexDb();
  const existing = await database.get("tarefas", id);

  await database.delete("tarefas", id);

  if (existing) {
    await registrarEventoTarefa(
      "TAREFA_EXCLUIDA",
      existing,
      ator,
    );
  }
}
