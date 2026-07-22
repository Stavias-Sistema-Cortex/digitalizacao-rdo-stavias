import { getSession } from "../../features/auth/authSession";
import { commitLocalMutation } from "../sync/localMutationCoordinator";
import type { MutationActor } from "../sync/mutationEnvelope";
import { getCortexDb } from "./cortexDb";
import {
  getSyncState,
  updateSyncState,
} from "./syncStateRepository";
import type {
  CanonicalOutboxMutationRecord,
  OperationalEntityRef,
  OperationalEventType,
  OutboxMutationRecord,
  SyncOperation,
  TarefaPrioridade,
  TarefaRecord,
} from "./db.types";
import { isCanonicalOutboxMutation } from "./outboxContract";

/** Quem executou a ação — validado novamente contra a sessão local atual. */
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

type TarefaOperation =
  | "CRIAR_TAREFA"
  | "CONCLUIR_TAREFA"
  | "REABRIR_TAREFA"
  | "EXCLUIR_TAREFA";

function nowUtc(): string {
  return new Date().toISOString();
}

function createTarefaId(): string {
  return crypto.randomUUID();
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

/**
 * O payload do sync descreve a tarefa inteira, nunca um delta implícito. Isso
 * permite que servidor e Memória reconstruam a mudança sem depender do estado
 * transitório deste dispositivo.
 */
function tarefaSyncPayload(
  tarefa: TarefaRecord,
): Record<string, unknown> {
  return {
    id: tarefa.id,
    obraId: tarefa.obraId,
    equipe: tarefa.equipe,
    titulo: tarefa.titulo,
    observacoes: tarefa.observacoes,
    criadaPor: tarefa.criadaPor,
    criadaPorColaboradorId: tarefa.criadaPorColaboradorId,
    responsavelEquipe: tarefa.responsavelEquipe,
    responsavelColaboradorId: tarefa.responsavelColaboradorId,
    prioridade: tarefa.prioridade,
    concluida: tarefa.concluida,
    concluidaEm: tarefa.concluidaEm,
    createdAt: tarefa.createdAt,
    updatedAt: tarefa.updatedAt,
    deletadaEm: tarefa.deletadaEm ?? null,
  };
}

async function tarefaMutationActor(
  obraId: string,
  ator: TarefaAtor,
): Promise<MutationActor> {
  const session = getSession();
  if (!session) {
    throw new Error(
      "Abra uma sessão protegida antes de registrar uma tarefa local.",
    );
  }
  if (
    ator.colaboradorId !== null &&
    ator.colaboradorId !== session.colaboradorId
  ) {
    throw new Error(
      "A autoria da tarefa não corresponde à sessão protegida atual.",
    );
  }
  if (!session.escopoGlobal && !session.obraIds.includes(obraId)) {
    throw new Error(
      "A sessão atual não possui acesso à obra desta tarefa.",
    );
  }

  const syncState = await getSyncState();
  const deviceId =
    syncState.usuarioId === session.colaboradorId && syncState.deviceId
      ? syncState.deviceId
      : crypto.randomUUID();

  if (
    syncState.usuarioId !== session.colaboradorId ||
    syncState.deviceId !== deviceId
  ) {
    await updateSyncState({
      usuarioId: session.colaboradorId,
      deviceId,
    });
  }

  return {
    actorId: session.colaboradorId,
    actorName: session.nome,
    deviceId,
    authorizationScope: [obraId],
  };
}

function isActiveMutation(mutation: OutboxMutationRecord): boolean {
  return (
    mutation.status === "PENDING" ||
    mutation.status === "ERROR" ||
    mutation.status === "SYNCING"
  );
}

async function activeTaskMutations(
  tarefaId: string,
): Promise<CanonicalOutboxMutationRecord[]> {
  const database = await getCortexDb();
  const mutations = await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    tarefaId,
  );
  const active = mutations.filter(
    (mutation) =>
      mutation.entidadeTipo === "TAREFA" &&
      isActiveMutation(mutation),
  );

  if (active.some((mutation) => !isCanonicalOutboxMutation(mutation))) {
    throw new Error(
      "Esta tarefa possui uma mutação legada preservada para revisão na Memória. Não é seguro reescrevê-la automaticamente.",
    );
  }
  if (active.some((mutation) => mutation.status === "SYNCING")) {
    throw new Error(
      "Esta tarefa está sendo sincronizada. Aguarde a confirmação antes de alterar seu estado.",
    );
  }

  return active
    .filter(isCanonicalOutboxMutation)
    .sort((left, right) =>
      left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
    );
}

/**
 * Uma criação offline começa na versão 1 quando chegar ao servidor. Cada
 * envelope pendente anterior representa exatamente uma próxima versão e deve
 * ser a causa da mutação seguinte.
 */
function expectedTaskBaseVersion(
  tarefa: TarefaRecord,
  pendingMutations: readonly CanonicalOutboxMutationRecord[],
): number {
  const confirmed = tarefa.versaoEntidade ?? 0;
  return confirmed + pendingMutations.length;
}

function eventForOperation(
  operation: TarefaOperation,
): OperationalEventType {
  switch (operation) {
    case "CRIAR_TAREFA":
      return "TAREFA_CRIADA";
    case "CONCLUIR_TAREFA":
      return "TAREFA_CONCLUIDA";
    case "REABRIR_TAREFA":
      return "TAREFA_REABERTA";
    case "EXCLUIR_TAREFA":
      return "TAREFA_EXCLUIDA";
  }
}

async function commitTarefaMutation(input: {
  tarefa: TarefaRecord;
  previousState: Record<string, unknown>;
  operation: TarefaOperation;
  baseVersion: number | null;
  actor: MutationActor;
  writeMode: "add" | "put";
  causationId?: string | null;
  dependsOnMutationIds?: string[];
}): Promise<void> {
  const { tarefa } = input;
  await commitLocalMutation({
    stores: ["tarefas"],
    entity: {
      type: "TAREFA",
      id: tarefa.id,
      name: tarefa.titulo,
      obraId: tarefa.obraId,
      colaboradorId: input.actor.actorId,
      relatedEntities: tarefaRelatedEntities(tarefa),
    },
    eventType: eventForOperation(input.operation),
    operation: input.operation as SyncOperation,
    baseVersion: input.baseVersion,
    previousState: input.previousState,
    newState: tarefaSyncPayload(tarefa),
    actor: input.actor,
    createdAt: tarefa.updatedAt,
    ...(input.causationId === undefined
      ? {}
      : { causationId: input.causationId }),
    ...(input.dependsOnMutationIds === undefined
      ? {}
      : { dependsOnMutationIds: input.dependsOnMutationIds }),
    write: (transaction) => {
      const store = transaction.objectStore("tarefas");
      if (input.writeMode === "add") {
        void store.add(tarefa).catch(() => undefined);
      } else {
        void store.put(tarefa).catch(() => undefined);
      }
      return undefined;
    },
  });
}

export async function listTarefasByObra(
  obraId: string,
): Promise<TarefaRecord[]> {
  const database = await getCortexDb();
  const tarefas = await database.getAllFromIndex(
    "tarefas",
    "by-obra-id",
    obraId,
  );
  return tarefas.filter((tarefa) => !tarefa.deletadaEm);
}

export async function listAllTarefas(): Promise<TarefaRecord[]> {
  const database = await getCortexDb();
  return (await database.getAll("tarefas"))
    .filter((tarefa) => !tarefa.deletadaEm);
}

export async function createTarefa(
  input: NovaTarefaInput,
  ator: TarefaAtor,
): Promise<TarefaRecord> {
  const timestamp = nowUtc();
  const actor = await tarefaMutationActor(input.obraId, ator);
  const record: TarefaRecord = {
    id: createTarefaId(),
    obraId: input.obraId,
    equipe: input.equipe.trim(),
    titulo: input.titulo.trim(),
    observacoes: input.observacoes.trim(),
    criadaPor: actor.actorName,
    criadaPorColaboradorId: actor.actorId,
    responsavelEquipe: input.responsavelEquipe.trim(),
    responsavelColaboradorId: input.responsavelColaboradorId,
    prioridade: input.prioridade,
    concluida: false,
    concluidaEm: null,
    createdAt: timestamp,
    updatedAt: timestamp,
    versaoEntidade: null,
    syncStatus: "PENDING_SYNC",
    deletadaEm: null,
  };

  await commitTarefaMutation({
    tarefa: record,
    previousState: {},
    operation: "CRIAR_TAREFA",
    baseVersion: null,
    actor,
    writeMode: "add",
  });

  return record;
}

export async function setTarefaConclusao(
  id: string,
  concluida: boolean,
  ator: TarefaAtor,
): Promise<TarefaRecord | undefined> {
  const database = await getCortexDb();
  const existing = await database.get("tarefas", id);

  if (!existing || existing.deletadaEm) {
    return undefined;
  }
  if (existing.syncStatus === "CONFLICT") {
    throw new Error(
      "Esta tarefa possui um conflito pendente. Consulte a Memória antes de alterá-la.",
    );
  }
  if (existing.concluida === concluida) {
    return existing;
  }

  const actor = await tarefaMutationActor(existing.obraId, ator);
  const pendingMutations = await activeTaskMutations(existing.id);
  const timestamp = nowUtc();
  const updated: TarefaRecord = {
    ...existing,
    concluida,
    concluidaEm: concluida ? timestamp : null,
    updatedAt: timestamp,
    syncStatus: "PENDING_SYNC",
  };
  const predecessor = pendingMutations.at(-1) ?? null;

  await commitTarefaMutation({
    tarefa: updated,
    previousState: tarefaSyncPayload(existing),
    operation: concluida ? "CONCLUIR_TAREFA" : "REABRIR_TAREFA",
    baseVersion: expectedTaskBaseVersion(existing, pendingMutations),
    actor,
    writeMode: "put",
    ...(predecessor === null
      ? {}
      : {
          causationId: predecessor.clientMutationId,
          dependsOnMutationIds: [predecessor.clientMutationId],
        }),
  });

  return updated;
}

export async function deleteTarefa(
  id: string,
  ator: TarefaAtor,
): Promise<void> {
  const database = await getCortexDb();
  const existing = await database.get("tarefas", id);

  if (!existing || existing.deletadaEm) {
    return;
  }
  if (existing.syncStatus === "CONFLICT") {
    throw new Error(
      "Esta tarefa possui um conflito pendente. Consulte a Memória antes de excluí-la.",
    );
  }

  const actor = await tarefaMutationActor(existing.obraId, ator);
  const pendingMutations = await activeTaskMutations(existing.id);
  const timestamp = nowUtc();
  const tombstone: TarefaRecord = {
    ...existing,
    deletadaEm: timestamp,
    updatedAt: timestamp,
    syncStatus: "PENDING_SYNC",
  };
  const predecessor = pendingMutations.at(-1) ?? null;

  await commitTarefaMutation({
    tarefa: tombstone,
    previousState: tarefaSyncPayload(existing),
    operation: "EXCLUIR_TAREFA",
    baseVersion: expectedTaskBaseVersion(existing, pendingMutations),
    actor,
    writeMode: "put",
    ...(predecessor === null
      ? {}
      : {
          causationId: predecessor.clientMutationId,
          dependsOnMutationIds: [predecessor.clientMutationId],
        }),
  });
}
