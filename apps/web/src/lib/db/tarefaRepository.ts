import { getSession } from "../../features/auth/authSession";
import { commitLocalMutation } from "../sync/localMutationCoordinator";
import { isCanonicalOutboxMutation } from "../sync/mutationEnvelope";
import { getCortexDb } from "./cortexDb";
import type {
  CanonicalOutboxMutationRecord,
  OperationalEntityRef,
  OperationalEventType,
  OutboxMutationRecord,
  TarefaPrioridade,
  TarefaRecord,
} from "./db.types";
import {
  getSyncState,
  updateSyncState,
} from "./syncStateRepository";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

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

export interface AtualizarTarefaInput {
  equipe?: string;
  titulo?: string;
  observacoes?: string;
  responsavelEquipe?: string;
  responsavelColaboradorId?: string | null;
  prioridade?: TarefaPrioridade;
}

type TarefaOperation =
  | "CRIAR_TAREFA"
  | "ATUALIZAR_TAREFA"
  | "CONCLUIR_TAREFA"
  | "REABRIR_TAREFA"
  | "EXCLUIR_TAREFA";

interface TarefaMutationIdentity {
  obraId: string;
  userId: string;
  deviceId: string;
}

function nowUtc(): string {
  return new Date().toISOString();
}

function createTarefaId(): string {
  return crypto.randomUUID();
}

function tarefaRelatedEntities(
  tarefa: TarefaRecord,
): OperationalEntityRef[] {
  return [{
    tipo: "OBRA",
    id: tarefa.obraId,
    nome: null,
  }];
}

/**
 * O transporte descreve a tarefa inteira sem metadados locais de fila. Assim,
 * servidor e Memória conseguem reconstruir cada transição independentemente.
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
    deletadaEm: tarefa.deletadaEm,
  };
}

async function tarefaMutationIdentity(
  obraId: string,
  ator: TarefaAtor,
): Promise<TarefaMutationIdentity> {
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
      "A obra da tarefa não pertence ao escopo da sessão atual.",
    );
  }

  const state = await getSyncState();
  const deviceId =
    state.usuarioId === session.colaboradorId &&
      state.deviceId &&
      UUID_PATTERN.test(state.deviceId)
      ? state.deviceId
      : crypto.randomUUID();
  if (
    state.deviceId !== deviceId ||
    state.usuarioId !== session.colaboradorId
  ) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }

  return {
    obraId,
    userId: session.colaboradorId,
    deviceId,
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
      "Esta tarefa possui uma mutação legada preservada para revisão. Não é seguro reescrevê-la automaticamente.",
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
      left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm) ||
      left.clientMutationId.localeCompare(right.clientMutationId),
    );
}

function taskMutationTail(
  mutations: readonly CanonicalOutboxMutationRecord[],
): CanonicalOutboxMutationRecord | null {
  if (mutations.length === 0) {
    return null;
  }
  const referencedPredecessors = new Set<string>();
  for (const mutation of mutations) {
    for (const dependencyId of mutation.dependsOnMutationIds ?? []) {
      referencedPredecessors.add(dependencyId);
    }
    if (mutation.causationId) {
      referencedPredecessors.add(mutation.causationId);
    }
  }
  const tails = mutations.filter(
    (mutation) =>
      !referencedPredecessors.has(mutation.clientMutationId),
  );
  if (tails.length !== 1) {
    throw new Error(
      "A cadeia local da tarefa está ambígua e precisa de revisão antes de receber outra edição.",
    );
  }
  return tails[0];
}

/**
 * Cada envelope pendente anterior representa uma versão futura do agregado.
 * Isso permite criar, concluir e excluir offline mantendo a ordem causal.
 */
function expectedTaskBaseVersion(
  tarefa: TarefaRecord,
  pendingMutations: readonly CanonicalOutboxMutationRecord[],
): number {
  return (tarefa.versaoEntidade ?? 0) + pendingMutations.length;
}

function eventForOperation(
  operation: TarefaOperation,
): OperationalEventType {
  switch (operation) {
    case "CRIAR_TAREFA":
      return "TAREFA_CRIADA";
    case "ATUALIZAR_TAREFA":
      return "TAREFA_ATUALIZADA";
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
  identity: TarefaMutationIdentity;
  expectedPrincipalSnapshot: TarefaRecord | null;
  expectedActiveMutationIds: string[];
  insertOnly?: boolean;
  causationId?: string | null;
  dependsOnMutationIds?: string[];
}): Promise<void> {
  const { tarefa } = input;
  const transportOperation = input.operation;
  await commitLocalMutation({
    ...input.identity,
    entityType: "TAREFA",
    entityId: tarefa.id,
    entityName: tarefa.titulo,
    operation:
      transportOperation === "CRIAR_TAREFA"
        ? "CREATE"
        : transportOperation === "ATUALIZAR_TAREFA"
          ? "UPDATE"
          : transportOperation === "EXCLUIR_TAREFA"
            ? "DELETE"
            : "TRANSITION",
    transportOperation,
    baseVersion: input.baseVersion,
    occurredAt: tarefa.updatedAt,
    previousSnapshot: input.previousState,
    nextSnapshot: tarefaSyncPayload(tarefa),
    principalSnapshot: { ...tarefa },
    expectedPrincipalSnapshot:
      input.expectedPrincipalSnapshot === null
        ? null
        : { ...input.expectedPrincipalSnapshot },
    expectedActiveMutationIds: input.expectedActiveMutationIds,
    eventType: eventForOperation(transportOperation),
    relatedEntities: tarefaRelatedEntities(tarefa),
    colaboradorId: input.identity.userId,
    causationId: input.causationId,
    dependsOnMutationIds: input.dependsOnMutationIds,
    write: () => [{
      store: "tarefas",
      value: tarefa,
      principal: true,
      insertOnly: input.insertOnly === true,
    }],
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
  const identity = await tarefaMutationIdentity(input.obraId, ator);
  const record: TarefaRecord = {
    id: createTarefaId(),
    obraId: identity.obraId,
    equipe: input.equipe.trim(),
    titulo: input.titulo.trim(),
    observacoes: input.observacoes.trim(),
    criadaPor: getSession()?.nome ?? ator.nome.trim(),
    criadaPorColaboradorId: identity.userId,
    responsavelEquipe: input.responsavelEquipe.trim(),
    responsavelColaboradorId: input.responsavelColaboradorId,
    prioridade: input.prioridade,
    concluida: false,
    concluidaEm: null,
    versaoEntidade: null,
    syncStatus: "PENDING_SYNC",
    deletadaEm: null,
    createdAt: timestamp,
    updatedAt: timestamp,
  };

  await commitTarefaMutation({
    tarefa: record,
    previousState: {},
    operation: "CRIAR_TAREFA",
    baseVersion: null,
    identity,
    expectedPrincipalSnapshot: null,
    expectedActiveMutationIds: [],
    insertOnly: true,
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
      "Esta tarefa possui um conflito pendente. Resolva-o antes de alterar seu estado.",
    );
  }
  if (existing.concluida === concluida) {
    return existing;
  }

  const identity = await tarefaMutationIdentity(existing.obraId, ator);
  const pendingMutations = await activeTaskMutations(existing.id);
  const timestamp = nowUtc();
  const updated: TarefaRecord = {
    ...existing,
    concluida,
    concluidaEm: concluida ? timestamp : null,
    updatedAt: timestamp,
    syncStatus: "PENDING_SYNC",
  };
  const predecessor = taskMutationTail(pendingMutations);

  await commitTarefaMutation({
    tarefa: updated,
    previousState: tarefaSyncPayload(existing),
    operation: concluida ? "CONCLUIR_TAREFA" : "REABRIR_TAREFA",
    baseVersion: expectedTaskBaseVersion(existing, pendingMutations),
    identity,
    expectedPrincipalSnapshot: existing,
    expectedActiveMutationIds: pendingMutations.map(
      (mutation) => mutation.clientMutationId,
    ),
    causationId: predecessor?.clientMutationId,
    dependsOnMutationIds: predecessor
      ? [predecessor.clientMutationId]
      : undefined,
  });

  return updated;
}

export async function updateTarefa(
  id: string,
  input: AtualizarTarefaInput,
  ator: TarefaAtor,
): Promise<TarefaRecord | undefined> {
  const database = await getCortexDb();
  const existing = await database.get("tarefas", id);

  if (!existing || existing.deletadaEm) {
    return undefined;
  }
  if (existing.syncStatus === "CONFLICT") {
    throw new Error(
      "Esta tarefa possui um conflito pendente. Resolva-o antes de editar seus campos.",
    );
  }

  const updatedFields = {
    equipe:
      input.equipe === undefined
        ? existing.equipe
        : requiredTrimmed(input.equipe, "equipe"),
    titulo:
      input.titulo === undefined
        ? existing.titulo
        : requiredTrimmed(input.titulo, "título"),
    observacoes:
      input.observacoes === undefined
        ? existing.observacoes
        : input.observacoes.trim(),
    responsavelEquipe:
      input.responsavelEquipe === undefined
        ? existing.responsavelEquipe
        : input.responsavelEquipe.trim(),
    responsavelColaboradorId:
      input.responsavelColaboradorId === undefined
        ? existing.responsavelColaboradorId
        : input.responsavelColaboradorId,
    prioridade: input.prioridade ?? existing.prioridade,
  };
  if (
    updatedFields.equipe === existing.equipe &&
    updatedFields.titulo === existing.titulo &&
    updatedFields.observacoes === existing.observacoes &&
    updatedFields.responsavelEquipe === existing.responsavelEquipe &&
    updatedFields.responsavelColaboradorId ===
      existing.responsavelColaboradorId &&
    updatedFields.prioridade === existing.prioridade
  ) {
    return existing;
  }

  const identity = await tarefaMutationIdentity(existing.obraId, ator);
  const pendingMutations = await activeTaskMutations(existing.id);
  const timestamp = nowUtc();
  const updated: TarefaRecord = {
    ...existing,
    ...updatedFields,
    updatedAt: timestamp,
    syncStatus: "PENDING_SYNC",
  };
  const predecessor = taskMutationTail(pendingMutations);

  await commitTarefaMutation({
    tarefa: updated,
    previousState: tarefaSyncPayload(existing),
    operation: "ATUALIZAR_TAREFA",
    baseVersion: expectedTaskBaseVersion(existing, pendingMutations),
    identity,
    expectedPrincipalSnapshot: existing,
    expectedActiveMutationIds: pendingMutations.map(
      (mutation) => mutation.clientMutationId,
    ),
    causationId: predecessor?.clientMutationId,
    dependsOnMutationIds: predecessor
      ? [predecessor.clientMutationId]
      : undefined,
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
      "Esta tarefa possui um conflito pendente. Resolva-o antes de excluí-la.",
    );
  }

  const identity = await tarefaMutationIdentity(existing.obraId, ator);
  const pendingMutations = await activeTaskMutations(existing.id);
  const timestamp = nowUtc();
  const tombstone: TarefaRecord = {
    ...existing,
    deletadaEm: timestamp,
    updatedAt: timestamp,
    syncStatus: "PENDING_SYNC",
  };
  const predecessor = taskMutationTail(pendingMutations);

  await commitTarefaMutation({
    tarefa: tombstone,
    previousState: tarefaSyncPayload(existing),
    operation: "EXCLUIR_TAREFA",
    baseVersion: expectedTaskBaseVersion(existing, pendingMutations),
    identity,
    expectedPrincipalSnapshot: existing,
    expectedActiveMutationIds: pendingMutations.map(
      (mutation) => mutation.clientMutationId,
    ),
    causationId: predecessor?.clientMutationId,
    dependsOnMutationIds: predecessor
      ? [predecessor.clientMutationId]
      : undefined,
  });
}

function requiredTrimmed(value: string, field: string): string {
  const trimmed = value.trim();
  if (!trimmed) {
    throw new Error(`O campo ${field} da tarefa é obrigatório.`);
  }
  return trimmed;
}
