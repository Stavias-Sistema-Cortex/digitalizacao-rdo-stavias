import {
  AUTH_SESSION_CHANGED_EVENT,
  getSession,
  type AuthProfile,
} from "../../features/auth/authSession";
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
  SyncEntityType,
  SyncOperation,
} from "../db/db.types";
import {
  buildCanonicalMutation,
  canonicalMutationJson,
  isCanonicalOutboxMutation,
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
  "TAREFA_ATUALIZADA",
  "TAREFA_CONCLUIDA",
  "TAREFA_REABERTA",
  "TAREFA_EXCLUIDA",
  "EQUIPE_CRIADA",
  "EQUIPE_ATUALIZADA",
  "EQUIPE_ARQUIVADA",
  "EQUIPE_VINCULO_ALTERADO",
  "VINCULO_OBRA_ATRIBUIDO",
  "VINCULO_OBRA_REVOGADO",
  "SOLICITACAO_INTEGRACAO_CRIADA",
  "COMPRA_CRIADA",
  "SERVICE_CREATED",
  "SERVICE_PRICE_VERSION_PUBLISHED",
  "SERVICE_PRICE_VERSION_SUPERSEDED",
  "SERVICE_PRICE_VERSION_CANCELLED",
] as const satisfies readonly OperationalEventType[];

type CanonicalWriteStore = "outbox_mutations" | "operational_events";
export type LocalDomainStore = Exclude<CortexStoreName, CanonicalWriteStore>;

const PRINCIPAL_STORE_BY_ENTITY_TYPE = {
  RDO: "rdos",
  TAREFA: "tarefas",
  CONVERSA: "mensagem_conversas",
  MENSAGEM: "mensagens",
  MENSAGEM_ANEXO: "mensagem_anexos",
  SERVICE: "service_catalog",
  SERVICE_PRICE_VERSION: "service_price_versions",
  EQUIPE: "teams",
} as const satisfies Partial<Record<SyncEntityType, LocalDomainStore>>;

const OUTBOX_ONLY_ENTITY_TYPES = new Set<SyncEntityType>([
  "SOLICITACAO_COMPRA",
  "COMPRA",
  "VINCULO_OBRA",
  "SOLICITACAO_INTEGRACAO",
]);

type LocalMutationDomainPut<TStore extends LocalDomainStore> = {
  [Store in TStore]: {
    store: Store;
    value: CortexDbSchema[Store]["value"];
    principal?: boolean;
    insertOnly?: boolean;
  };
}[TStore];

type LocalMutationDomainDelete<TStore extends LocalDomainStore> = {
  [Store in TStore]: {
    store: Store;
    deleteKey: string;
  };
}[TStore];

export type LocalMutationDomainWrite<TStore extends LocalDomainStore> =
  | LocalMutationDomainPut<TStore>
  | LocalMutationDomainDelete<TStore>;

export interface LocalMutationCommand<TStore extends LocalDomainStore> {
  clientMutationId?: string;
  ontologyEventId?: string;
  deviceId: string;
  userId: string;
  obraId: string | null;
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
  /** Persisted principal can wrap the transport payload with local metadata. */
  principalSnapshot?: Record<string, unknown>;
  /**
   * Optional compare-and-set guard read inside the same IndexedDB transaction.
   * `null` means the principal must not exist yet.
   */
  expectedPrincipalSnapshot?: Record<string, unknown> | null;
  /** Active entity mutations observed while the caller derived its baseVersion. */
  expectedActiveMutationIds?: readonly string[];
  eventType: OperationalEventType;
  relatedEntities?: readonly OperationalEntityRef[];
  colaboradorId?: string | null;
  correlationId?: string;
  causationId?: string | null;
  transport?: OutboxTransport;
  dependsOnMutationIds?: readonly string[];
  initialBlockedReason?: "RDO_CREATION_CONTEXT_REQUIRED";
  /** Immutable canonical envelope replaced causally in the same transaction. */
  supersedesMutationId?: string;
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
  session: AuthProfile;
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
  initialBlockedReason: "RDO_CREATION_CONTEXT_REQUIRED" | null;
  supersedesMutationId: string | null;
  expectedPrincipalSnapshot: Record<string, unknown> | null | undefined;
  expectedActiveMutationIds: string[] | undefined;
}

export async function commitLocalMutation<TStore extends LocalDomainStore>(
  command: LocalMutationCommand<TStore>,
): Promise<CommittedLocalMutation> {
  // Everything before buildCanonicalMutation's digest is synchronous. This
  // closes the mutation window for command objects and domain write closures.
  const authorizedSession = authorizeActiveSession(command);
  const prepared = prepareCoordinatorInput(command);
  const builtEnvelope = await buildCanonicalMutation({
    ...prepared.envelope,
    authorizationScope: authorizedSession.authorizationScope,
    relatedEntities: prepared.relatedEntities,
  });
  const built = prepared.initialBlockedReason === null
    ? builtEnvelope
    : {
        ...builtEnvelope,
        mutation: {
          ...builtEnvelope.mutation,
          blockedReason: prepared.initialBlockedReason,
        },
      };
  assertSessionUnchanged(authorizedSession, prepared.envelope);
  const event = canonicalEvent(prepared, built, authorizedSession.actorName);
  if (
    prepared.supersedesMutationId &&
    built.mutation.causationId !== prepared.supersedesMutationId
  ) {
    throw new TypeError(
      "A mutação substituta deve referenciar a mutação original como causa.",
    );
  }
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
  const transactionDone = transaction.done;
  void transactionDone.catch(() => undefined);
  let sessionInvalidated = false;
  const abortOnSessionChange = () => {
    try {
      assertSessionUnchanged(authorizedSession, prepared.envelope);
    } catch {
      sessionInvalidated = true;
      try {
        transaction.abort();
      } catch {
        // The transaction may already have completed or aborted.
      }
    }
  };
  if (typeof window !== "undefined") {
    window.addEventListener(AUTH_SESSION_CHANGED_EVENT, abortOnSessionChange);
  }

  try {
    if (prepared.expectedPrincipalSnapshot !== undefined) {
      const principal = prepared.writes.find(
        (write): write is LocalMutationDomainPut<TStore> =>
          "value" in write && write.principal === true,
      );
      if (!principal) {
        transaction.abort();
        throw new Error(
          "A pré-condição local exige uma escrita principal.",
        );
      }
      const currentPrincipal = await transaction
        .objectStore(principal.store)
        .get(prepared.envelope.entityId as never);
      const currentSnapshot =
        currentPrincipal === undefined ? null : currentPrincipal;
      if (
        canonicalMutationJson(currentSnapshot) !==
          canonicalMutationJson(prepared.expectedPrincipalSnapshot)
      ) {
        transaction.abort();
        throw new Error(
          "A entidade local mudou durante a preparação da mutação. Recarregue e tente novamente.",
        );
      }
    }
    if (prepared.expectedActiveMutationIds !== undefined) {
      const currentActiveMutationIds = (
        await transaction
          .objectStore("outbox_mutations")
          .index("by-entity-id")
          .getAll(prepared.envelope.entityId)
      )
        .filter(
          (mutation) =>
            mutation.entidadeTipo === prepared.envelope.entityType &&
            ["PENDING", "ERROR", "SYNCING"].includes(mutation.status),
        )
        .map((mutation) => mutation.clientMutationId)
        .sort();
      if (
        canonicalMutationJson(currentActiveMutationIds) !==
          canonicalMutationJson(prepared.expectedActiveMutationIds)
      ) {
        transaction.abort();
        throw new Error(
          "A fila local da entidade mudou durante a preparação da mutação. Recarregue e tente novamente.",
        );
      }
    }
    if (prepared.supersedesMutationId) {
      const outboxStore = transaction.objectStore("outbox_mutations");
      const eventStore = transaction.objectStore("operational_events");
      const original = await outboxStore.get(prepared.supersedesMutationId);
      if (
        !original ||
        !isCanonicalOutboxMutation(original) ||
        !["PENDING", "ERROR"].includes(original.status) ||
        original.entityType !== built.mutation.entityType ||
        original.entityId !== built.mutation.entityId
      ) {
        transaction.abort();
        throw new Error(
          "A mutação canônica original não está disponível para substituição.",
        );
      }
      const originalEvents = await eventStore
        .index("by-client-mutation-id")
        .getAll(original.clientMutationId);
      if (originalEvents.length !== 1 || originalEvents[0].schemaVersion !== 13) {
        transaction.abort();
        throw new Error(
          "A mutação canônica original não possui evento local único.",
        );
      }
      await outboxStore.put({
        ...original,
        status: "REJECTED",
        nextAttemptAt: null,
        lastSafeCode: "SUPERSEDED_BY_LOCAL_EDIT",
        blockedReason: `SUPERSEDED_BY:${built.mutation.clientMutationId}`,
        ultimoErro: "Envelope substituído após edição local rastreável.",
        updatedAt: built.mutation.occurredAt,
      });
      await eventStore.put({
        ...originalEvents[0],
        result: "REJECTED",
        syncStatus: "SYNC_FAILED",
        errorCategory: "SUPERSEDED_BY_LOCAL_EDIT",
      });
    }
    for (const write of prepared.writes) {
      const store = transaction.objectStore(write.store);
      if ("deleteKey" in write) {
        void store.delete(write.deleteKey as never).catch(() => undefined);
      } else {
        void (write.insertOnly
          ? store.add(write.value as never)
          : store.put(write.value as never)
        ).catch(() => undefined);
      }
    }
    void transaction
      .objectStore("outbox_mutations")
      .add(built.mutation)
      .catch(() => undefined);
    await transaction
      .objectStore("operational_events")
      .add(event);
    await transactionDone;
    assertSessionUnchanged(authorizedSession, prepared.envelope);
  } catch (error) {
    try {
      transaction.abort();
    } catch {
      // A failed IndexedDB request may already have aborted the transaction.
    }
    await transactionDone.catch(() => undefined);
    if (sessionInvalidated) {
      throw new Error(
        "A sessão mudou durante o registro da mutação local.",
        { cause: error },
      );
    }
    throw error;
  } finally {
    if (typeof window !== "undefined") {
      window.removeEventListener(
        AUTH_SESSION_CHANGED_EVENT,
        abortOnSessionChange,
      );
    }
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
  const writes = prepareWrites(
    command.write,
    command.principalSnapshot ?? envelope.nextSnapshot,
    envelope.entityId,
    envelope.entityType,
    envelope.obraId,
  );
  const expectedPrincipalSnapshot =
    command.expectedPrincipalSnapshot === undefined
      ? undefined
      : command.expectedPrincipalSnapshot === null
        ? null
        : cloneForIndexedDb(
            command.expectedPrincipalSnapshot,
            "expectedPrincipalSnapshot",
          );
  const expectedActiveMutationIds =
    command.expectedActiveMutationIds === undefined
      ? undefined
      : command.expectedActiveMutationIds
          .map((value, index) =>
            uuid(value, `expectedActiveMutationIds[${index}]`),
          )
          .sort();
  if (
    expectedActiveMutationIds &&
    new Set(expectedActiveMutationIds).size !==
      expectedActiveMutationIds.length
  ) {
    throw new TypeError(
      "expectedActiveMutationIds must not contain duplicates.",
    );
  }
  const initialBlockedReason = prepareInitialBlockedReason(
    command.initialBlockedReason,
    envelope,
  );

  return {
    envelope,
    eventType,
    entityName,
    relatedEntities,
    colaboradorId,
    writes,
    initialBlockedReason,
    expectedPrincipalSnapshot,
    expectedActiveMutationIds,
    supersedesMutationId:
      command.supersedesMutationId === undefined
        ? null
        : uuid(command.supersedesMutationId, "supersedesMutationId"),
  };
}

function prepareInitialBlockedReason(
  value: LocalMutationCommand<LocalDomainStore>["initialBlockedReason"],
  envelope: Pick<
    BuildCanonicalMutationInput,
    "entityType" | "operation" | "transportOperation" | "nextSnapshot"
  >,
): "RDO_CREATION_CONTEXT_REQUIRED" | null {
  if (value === undefined) return null;
  if (
    value !== "RDO_CREATION_CONTEXT_REQUIRED" ||
    envelope.entityType !== "RDO" ||
    envelope.operation !== "CREATE" ||
    envelope.transportOperation !== "CRIAR_RDO" ||
    envelope.nextSnapshot.creationContextVersion !== null
  ) {
    throw new TypeError(
      "RDO_CREATION_CONTEXT_REQUIRED só pode bloquear CREATE de RDO sem receipt.",
    );
  }
  return value;
}

function prepareWrites<TStore extends LocalDomainStore>(
  writer: LocalMutationCommand<TStore>["write"],
  nextSnapshot: Record<string, unknown>,
  entityId: string,
  entityType: string,
  obraId: string | null,
): LocalMutationDomainWrite<TStore>[] {
  const result: unknown = writer();
  if (isThenable(result)) {
    void Promise.resolve(result).catch(() => undefined);
    throw new TypeError("Local mutation write plan must be synchronous.");
  }
  if (!Array.isArray(result)) {
    throw new TypeError("Local mutation write plan is required.");
  }
  if (result.length === 0) {
    if (!OUTBOX_ONLY_ENTITY_TYPES.has(entityType as SyncEntityType)) {
      throw new TypeError(
        `entityType ${entityType} requires a local principal write.`,
      );
    }
    return [];
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
    if ("deleteKey" in write) {
      return {
        store: write.store,
        deleteKey: requiredText(
          write.deleteKey,
          `write[${index}].deleteKey`,
        ),
      } as LocalMutationDomainWrite<TStore>;
    }
    return {
      store: write.store,
      value: cloneForIndexedDb(write.value, `write[${index}].value`),
      principal: write.principal === true,
      insertOnly: write.insertOnly === true,
    } as LocalMutationDomainWrite<TStore>;
  });
  const principals = writes.filter(
    (write): write is LocalMutationDomainPut<TStore> =>
      "value" in write && write.principal === true,
  );
  if (principals.length !== 1) {
    throw new TypeError("Local mutation write plan requires one principal write.");
  }
  const principal = principals[0];
  const principalValue = principal.value as unknown;
  if (writes.some((write) => write !== principal && write.store === principal.store)) {
    throw new TypeError(
      "Principal domain store may contain only the principal write.",
    );
  }
  if (
    principalValue === null ||
    typeof principalValue !== "object" ||
    Array.isArray(principalValue) ||
    !("id" in principalValue) ||
    principalValue.id !== entityId
  ) {
    throw new TypeError("Principal domain write id must equal entityId.");
  }
  const expectedStore = PRINCIPAL_STORE_BY_ENTITY_TYPE[
    entityType as keyof typeof PRINCIPAL_STORE_BY_ENTITY_TYPE
  ];
  if (expectedStore === undefined) {
    throw new TypeError(
      `entityType ${entityType} does not have a local principal store.`,
    );
  }
  if (principal.store !== expectedStore) {
    throw new TypeError(
      `entityType ${entityType} requires principal store ${expectedStore}.`,
    );
  }
  if (
    (entityType === "RDO" || entityType === "TAREFA") &&
    (!("obraId" in principalValue) || principalValue.obraId !== obraId)
  ) {
    throw new TypeError(
      `Principal ${entityType} obraId must equal envelope obraId.`,
    );
  }
  if (
    entityType === "EQUIPE" &&
    (!("obraPrincipalId" in principalValue) ||
      principalValue.obraPrincipalId !== obraId)
  ) {
    throw new TypeError(
      "Principal EQUIPE obraPrincipalId must equal envelope obraId.",
    );
  }
  if (
    canonicalMutationJson(principal.value) !==
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
  if (envelope.obraId === null) {
    if (!session.escopoGlobal || session.papelAcesso !== "ALFA") {
      throw new Error(
        "A mutação global exige uma sessão Alfa com escopo global.",
      );
    }
  } else if (
    !session.escopoGlobal &&
    !session.obraIds.includes(envelope.obraId)
  ) {
    throw new Error("A obra da mutação não pertence ao escopo da sessão.");
  }
  return {
    session,
    fingerprint: sessionFingerprint(session),
    actorName: session.nome,
    authorizationScope: session.escopoGlobal
      ? ["ALFA:GLOBAL"]
      : envelope.obraId === null
        ? []
        : [envelope.obraId],
  };
}

function assertSessionUnchanged(
  expected: AuthorizedSession,
  envelope: Pick<BuildCanonicalMutationInput, "userId" | "obraId">,
): void {
  const current = authorizeActiveSession(envelope);
  if (
    current.session !== expected.session ||
    current.fingerprint !== expected.fingerprint
  ) {
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
