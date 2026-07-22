import { getSession, type AuthProfile } from "../../features/auth/authSession";
import {
  getCortexDb,
  type CortexDbSchema,
  type CortexStoreName,
} from "../db/cortexDb";
import type {
  CanonicalMutationOperation,
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  OperationalEntityRef,
  OperationalEventType,
  OutboxTransport,
  SyncOperation,
} from "../db/db.types";
import {
  buildCanonicalMutation,
  canonicalMutationJson,
  type BuildCanonicalMutationInput,
} from "./mutationEnvelope";

export const LOCAL_MUTATION_QUEUED_EVENT = "cortex:local-mutation-queued";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ENTITY_TYPE_PATTERN = /^[A-Z][A-Z0-9_]{0,63}$/;
const OPERATIONAL_EVENT_TYPES = [
  "RDO_CRIADO",
  "RDO_EDITADO",
  "RDO_SALVO_OFFLINE",
  "RDO_SINCRONIZADO",
  "RDO_FALHA_SYNC",
  "FOTO_ADICIONADA",
  "FOTO_COMPRIMIDA",
  "FOTO_REMOVIDA",
  "MEDICAO_TRECHO_ATUALIZADA",
  "COLABORADOR_ASSOCIADO_RDO",
  "EQUIPAMENTO_ASSOCIADO_RDO",
  "OCORRENCIA_REGISTRADA",
  "CALCULO_REPROCESSADO",
  "ENTIDADE_RELACIONADA",
  "ENTIDADE_DESRELACIONADA",
  "TAREFA_CRIADA",
  "TAREFA_CONCLUIDA",
  "TAREFA_REABERTA",
  "TAREFA_EXCLUIDA",
] as const satisfies readonly OperationalEventType[];

type CanonicalWriteStore = "outbox_mutations" | "operational_events";
export type LocalDomainStore = Exclude<CortexStoreName, CanonicalWriteStore>;

export type LocalMutationDomainWrite<TStore extends LocalDomainStore> = {
  [Store in TStore]: {
    store: Store;
    value: CortexDbSchema[Store]["value"];
    principal?: boolean;
  };
}[TStore];

export interface LocalMutationCommand<TStore extends LocalDomainStore> {
  clientMutationId?: string;
  ontologyEventId?: string;
  deviceId: string;
  userId: string;
  obraId: string;
  entityType: string;
  entityId: string;
  entityName?: string | null;
  operation: CanonicalMutationOperation;
  transportOperation: SyncOperation;
  baseVersion: number | null;
  changedFields?: readonly string[];
  occurredAt?: string;
  previousSnapshot: Record<string, unknown>;
  nextSnapshot: Record<string, unknown>;
  eventType: OperationalEventType;
  relatedEntities?: readonly OperationalEntityRef[];
  colaboradorId?: string | null;
  correlationId?: string;
  causationId?: string | null;
  transport?: OutboxTransport;
  dependsOnMutationIds?: readonly string[];
  /**
   * Builds a declarative write plan synchronously. Exactly one write must be
   * principal and equal the canonical nextSnapshot.
   */
  write: () => readonly LocalMutationDomainWrite<TStore>[];
}

export interface CommittedLocalMutation {
  mutation: CanonicalOutboxMutationRecord;
  event: CanonicalOperationalEventRecord;
}

interface AuthorizedSession {
  fingerprint: string;
  actorName: string;
  authorizationScope: string[];
}

interface PreparedCoordinatorInput<TStore extends LocalDomainStore> {
  envelope: Omit<BuildCanonicalMutationInput, "authorizationScope">;
  eventType: OperationalEventType;
  entityName: string | null;
  relatedEntities: OperationalEntityRef[];
  colaboradorId: string | null;
  writes: LocalMutationDomainWrite<TStore>[];
}

export async function commitLocalMutation<TStore extends LocalDomainStore>(
  command: LocalMutationCommand<TStore>,
): Promise<CommittedLocalMutation> {
  // Everything before buildCanonicalMutation's digest is synchronous. This
  // closes the mutation window for command objects and domain write closures.
  const authorizedSession = authorizeActiveSession(command);
  const prepared = prepareCoordinatorInput(command);
  const built = await buildCanonicalMutation({
    ...prepared.envelope,
    authorizationScope: authorizedSession.authorizationScope,
  });
  assertSessionUnchanged(authorizedSession, prepared.envelope);
  const event = canonicalEvent(prepared, built, authorizedSession.actorName);
  const database = await getCortexDb();
  assertSessionUnchanged(authorizedSession, prepared.envelope);

  const storeNames = [
    ...new Set<TStore | CanonicalWriteStore>([
      ...prepared.writes.map((write) => write.store),
      "outbox_mutations",
      "operational_events",
    ]),
  ];
  const transaction = database.transaction(storeNames, "readwrite");

  try {
    for (const write of prepared.writes) {
      void transaction
        .objectStore(write.store)
        .put(write.value as never)
        .catch(() => undefined);
    }
    void transaction
      .objectStore("outbox_mutations")
      .add(built.mutation)
      .catch(() => undefined);
    void transaction
      .objectStore("operational_events")
      .add(event)
      .catch(() => undefined);
    await transaction.done;
  } catch (error) {
    try {
      transaction.abort();
    } catch {
      // A failed IndexedDB request may already have aborted the transaction.
    }
    await transaction.done.catch(() => undefined);
    throw error;
  }

  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(LOCAL_MUTATION_QUEUED_EVENT));
  }

  return { mutation: built.mutation, event };
}

function prepareCoordinatorInput<TStore extends LocalDomainStore>(
  command: LocalMutationCommand<TStore>,
): PreparedCoordinatorInput<TStore> {
  const eventType = operationalEventType(command.eventType);
  const entityName = optionalText(command.entityName, "entityName") ?? null;
  const relatedEntities = (command.relatedEntities ?? []).map(
    (entity, index) => ({
      tipo: operationalEntityType(
        entity.tipo,
        `relatedEntities[${index}].tipo`,
      ),
      id: uuid(entity.id, `relatedEntities[${index}].id`),
      nome: optionalText(
        entity.nome,
        `relatedEntities[${index}].nome`,
      ) ?? null,
    }),
  );
  const colaboradorId = command.colaboradorId === null ||
      command.colaboradorId === undefined
    ? null
    : uuid(command.colaboradorId, "colaboradorId");
  const envelope: Omit<BuildCanonicalMutationInput, "authorizationScope"> = {
    clientMutationId: command.clientMutationId,
    ontologyEventId: command.ontologyEventId,
    deviceId: command.deviceId,
    userId: command.userId,
    obraId: command.obraId,
    entityType: command.entityType,
    entityId: command.entityId,
    operation: command.operation,
    transportOperation: command.transportOperation,
    baseVersion: command.baseVersion,
    changedFields: command.changedFields === undefined
      ? undefined
      : [...command.changedFields],
    occurredAt: command.occurredAt,
    previousSnapshot: command.previousSnapshot,
    nextSnapshot: command.nextSnapshot,
    correlationId: command.correlationId,
    causationId: command.causationId,
    transport: command.transport,
    dependsOnMutationIds: command.dependsOnMutationIds === undefined
      ? undefined
      : [...command.dependsOnMutationIds],
  };
  const writes = prepareWrites(command.write, envelope.nextSnapshot);

  return {
    envelope,
    eventType,
    entityName,
    relatedEntities,
    colaboradorId,
    writes,
  };
}

function prepareWrites<TStore extends LocalDomainStore>(
  writer: LocalMutationCommand<TStore>["write"],
  nextSnapshot: Record<string, unknown>,
): LocalMutationDomainWrite<TStore>[] {
  const result: unknown = writer();
  if (isThenable(result)) {
    void Promise.resolve(result).catch(() => undefined);
    throw new TypeError("Local mutation write plan must be synchronous.");
  }
  if (!Array.isArray(result) || result.length === 0) {
    throw new TypeError("Local mutation write plan is required.");
  }

  const writes = result.map((candidate, index) => {
    if (candidate === null || typeof candidate !== "object") {
      throw new TypeError(`write[${index}] must be an object.`);
    }
    const write = candidate as LocalMutationDomainWrite<TStore>;
    const store = requiredText(write.store, `write[${index}].store`);
    if (store === "outbox_mutations" || store === "operational_events") {
      throw new TypeError(`write[${index}].store is coordinator-owned.`);
    }
    return {
      store: write.store,
      value: cloneForIndexedDb(write.value, `write[${index}].value`),
      principal: write.principal === true,
    } as LocalMutationDomainWrite<TStore>;
  });
  const principals = writes.filter((write) => write.principal === true);
  if (principals.length !== 1) {
    throw new TypeError("Local mutation write plan requires one principal write.");
  }
  if (
    canonicalMutationJson(principals[0].value) !==
      canonicalMutationJson(nextSnapshot)
  ) {
    throw new TypeError("Principal domain write must equal nextSnapshot.");
  }
  return writes;
}

function cloneForIndexedDb<T>(value: T, field: string): T {
  try {
    return structuredClone(value);
  } catch {
    throw new TypeError(`${field} must be structured-cloneable.`);
  }
}

function authorizeActiveSession(
  envelope: Pick<BuildCanonicalMutationInput, "userId" | "obraId">,
): AuthorizedSession {
  const session = getSession();
  if (!session) {
    throw new Error("Sessão válida obrigatória para registrar mutação local.");
  }
  if (session.colaboradorId !== envelope.userId) {
    throw new Error("A mutação local não pertence à sessão ativa.");
  }
  if (!session.escopoGlobal && !session.obraIds.includes(envelope.obraId)) {
    throw new Error("A obra da mutação não pertence ao escopo da sessão.");
  }
  return {
    fingerprint: sessionFingerprint(session),
    actorName: session.nome,
    authorizationScope: session.escopoGlobal
      ? ["ALFA:GLOBAL"]
      : [envelope.obraId],
  };
}

function assertSessionUnchanged(
  expected: AuthorizedSession,
  envelope: Pick<BuildCanonicalMutationInput, "userId" | "obraId">,
): void {
  const current = authorizeActiveSession(envelope);
  if (current.fingerprint !== expected.fingerprint) {
    throw new Error("A sessão mudou durante o registro da mutação local.");
  }
}

function sessionFingerprint(session: AuthProfile): string {
  return canonicalMutationJson({
    colaboradorId: session.colaboradorId,
    nome: session.nome,
    papelAcesso: session.papelAcesso,
    escopoGlobal: session.escopoGlobal,
    obraIds: [...session.obraIds].sort(),
    expiraEm: session.expiraEm,
  });
}

function canonicalEvent<TStore extends LocalDomainStore>(
  prepared: PreparedCoordinatorInput<TStore>,
  built: Awaited<ReturnType<typeof buildCanonicalMutation>>,
  actorName: string,
): CanonicalOperationalEventRecord {
  const { mutation } = built;

  return {
    id: mutation.trace.ontologyEventId,
    type: prepared.eventType,
    principalEntity: {
      tipo: mutation.entityType,
      id: mutation.entityId,
      nome: prepared.entityName,
    },
    principalEntityKey: `${mutation.entityType}:${mutation.entityId}`,
    relatedEntities: prepared.relatedEntities,
    obraId: mutation.obraId,
    rdoId: mutation.entityType === "RDO" ? mutation.entityId : null,
    colaboradorId: prepared.colaboradorId,
    occurredAt: mutation.occurredAt,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: mutation.userId,
    responsibleUserName: actorName,
    payload: built.nextSnapshot,
    syncStatus: "PENDING_SYNC",
    schemaVersion: 13,
    clientMutationId: mutation.clientMutationId,
    deviceId: mutation.deviceId,
    correlationId: mutation.correlationId,
    causationId: mutation.causationId,
    previousState: built.previousSnapshot,
    newState: built.nextSnapshot,
    result: "PENDING",
    errorCategory: null,
    entityVersion: mutation.baseVersion,
  };
}

function operationalEventType(value: unknown): OperationalEventType {
  const text = requiredText(value, "eventType");
  if (!OPERATIONAL_EVENT_TYPES.includes(text as OperationalEventType)) {
    throw new TypeError(`eventType ${text} is not supported.`);
  }
  return text as OperationalEventType;
}

function operationalEntityType(value: unknown, field: string): string {
  const text = requiredText(value, field);
  if (!ENTITY_TYPE_PATTERN.test(text)) {
    throw new TypeError(`${field} is not a canonical entity type.`);
  }
  return text;
}

function uuid(value: unknown, field: string): string {
  const text = requiredText(value, field);
  if (!UUID_PATTERN.test(text)) {
    throw new TypeError(`${field} must be a canonical UUID.`);
  }
  return text;
}

function requiredText(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new TypeError(`${field} must be a nonblank string.`);
  }
  return value;
}

function optionalText(
  value: unknown,
  field: string,
): string | null | undefined {
  if (value === undefined || value === null) return value;
  return requiredText(value, field);
}

function isThenable(value: unknown): value is PromiseLike<unknown> {
  return (
    ((typeof value === "object" && value !== null) ||
      typeof value === "function") &&
    "then" in value &&
    typeof value.then === "function"
  );
}
