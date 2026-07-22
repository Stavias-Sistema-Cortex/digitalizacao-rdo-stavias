import type {
  CanonicalMutationEnvelopeV13,
  CanonicalMutationOperation,
  CanonicalOutboxMutationRecord,
  MutationFieldPatch,
  OutboxMutationRecord,
  OutboxTransport,
  SyncEntityType,
  SyncOperation,
} from "../db/db.types";

export type {
  CanonicalMutationEnvelopeV13,
  CanonicalMutationOperation,
} from "../db/db.types";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

const CANONICAL_OPERATIONS = [
  "CREATE",
  "UPDATE",
  "DELETE",
  "TRANSITION",
] as const satisfies readonly CanonicalMutationOperation[];

const TRANSPORTS = [
  "SYNC_PUSH",
  "OBJECT_UPLOAD",
] as const satisfies readonly OutboxTransport[];

const SYNC_ENTITY_TYPES = [
  "RDO",
  "CONVERSA",
  "MENSAGEM",
  "MENSAGEM_ANEXO",
  "SOLICITACAO_COMPRA",
  "COMPRA",
] as const satisfies readonly SyncEntityType[];

const TRANSPORT_OPERATION_TO_CANONICAL = {
  CRIAR_RDO: "CREATE",
  ATUALIZAR_RDO_RASCUNHO: "UPDATE",
  ENVIAR_RDO: "TRANSITION",
  CRIAR_CONVERSA: "CREATE",
  ADICIONAR_PARTICIPANTE_CONVERSA: "UPDATE",
  REMOVER_PARTICIPANTE_CONVERSA: "UPDATE",
  CRIAR_MENSAGEM: "CREATE",
  EDITAR_MENSAGEM: "UPDATE",
  EXCLUIR_MENSAGEM: "DELETE",
  ADICIONAR_MENSAGEM_ANEXO: "CREATE",
  CRIAR_SOLICITACAO_COMPRA: "CREATE",
  ATUALIZAR_SOLICITACAO_COMPRA: "UPDATE",
  ARQUIVAR_SOLICITACAO_COMPRA: "TRANSITION",
  CRIAR_COMPRA: "CREATE",
  ATUALIZAR_COMPRA: "UPDATE",
  ALTERAR_STATUS_COMPRA: "TRANSITION",
  DECIDIR_APROVACAO_COMPRA: "TRANSITION",
  ARQUIVAR_COMPRA: "TRANSITION",
} as const satisfies Record<SyncOperation, CanonicalMutationOperation>;

export interface BuildCanonicalMutationInput {
  clientMutationId?: string;
  ontologyEventId?: string;
  deviceId: string;
  userId: string;
  obraId: string;
  entityType: string;
  entityId: string;
  operation: CanonicalMutationOperation;
  transportOperation: SyncOperation;
  baseVersion: number | null;
  /** Optional assertion. The coordinator always stores the derived exact delta. */
  changedFields?: readonly string[];
  occurredAt?: string;
  previousSnapshot: Record<string, unknown>;
  nextSnapshot: Record<string, unknown>;
  authorizationScope: readonly string[];
  correlationId?: string;
  causationId?: string | null;
  transport?: OutboxTransport;
  dependsOnMutationIds?: readonly string[];
}

export interface BuiltCanonicalMutation {
  mutation: CanonicalOutboxMutationRecord;
  previousSnapshot: Record<string, unknown>;
  nextSnapshot: Record<string, unknown>;
}

interface PreparedCanonicalMutation {
  envelope: CanonicalMutationEnvelopeV13;
  ontologyEventId: string;
  transportOperation: SyncOperation;
  previousSnapshot: Record<string, unknown>;
  nextSnapshot: Record<string, unknown>;
  authorizationScope: string[];
  correlationId: string;
  causationId: string | null;
  transport: OutboxTransport;
  dependsOnMutationIds: string[];
  fieldPatch: MutationFieldPatch;
  canonicalPayload: string;
}

export async function mutationPayloadHash(value: unknown): Promise<string> {
  return hashCanonicalJson(canonicalMutationJson(value));
}

/** Stable JSON used for provenance comparisons before any asynchronous work. */
export function canonicalMutationJson(value: unknown): string {
  const seen = new Set<object>();

  function normalize(candidate: unknown, path: string): unknown {
    if (
      candidate === null ||
      typeof candidate === "string" ||
      typeof candidate === "boolean"
    ) {
      return candidate;
    }
    if (typeof candidate === "number") {
      if (!Number.isFinite(candidate)) {
        throw new TypeError(`${path} contains a non-finite number.`);
      }
      return candidate;
    }
    if (typeof candidate !== "object") {
      throw new TypeError(`${path} contains a non-JSON value.`);
    }
    if (seen.has(candidate)) {
      throw new TypeError(`${path} contains a circular reference.`);
    }

    seen.add(candidate);
    try {
      if (Array.isArray(candidate)) {
        return Array.from({ length: candidate.length }, (_unused, index) => {
          if (!(index in candidate)) {
            throw new TypeError(`${path}[${index}] is sparse.`);
          }
          return normalize(candidate[index], `${path}[${index}]`);
        });
      }
      const prototype = Object.getPrototypeOf(candidate);
      if (prototype !== Object.prototype && prototype !== null) {
        throw new TypeError(`${path} contains a non-JSON object.`);
      }
      const record = candidate as Record<string, unknown>;
      const normalized: Record<string, unknown> = {};
      for (const key of Object.keys(record).sort()) {
        normalized[key] = normalize(record[key], `${path}.${key}`);
      }
      return normalized;
    } finally {
      seen.delete(candidate);
    }
  }

  return JSON.stringify(normalize(value, "payload"));
}

export function isCanonicalOutboxMutation(
  mutation: OutboxMutationRecord,
): mutation is CanonicalOutboxMutationRecord {
  return mutation.schemaVersion === 13;
}

export function assertCanonicalTransportCoherence(
  mutation: CanonicalOutboxMutationRecord,
): void {
  const entityType = syncEntityType(mutation.entityType);
  if (
    mutation.entidadeTipo !== entityType ||
    mutation.entidadeId !== mutation.entityId ||
    mutation.baseVersao !== mutation.baseVersion ||
    mutation.criadaNoClienteEm !== mutation.occurredAt
  ) {
    throw new TypeError("Canonical mutation transport aliases are incoherent.");
  }
  const expectedOperation = canonicalOperationForTransport(mutation.operacao);
  if (mutation.operation !== expectedOperation) {
    throw new TypeError("Canonical mutation operation aliases are incoherent.");
  }
  canonicalOperation(mutation.operation);
  uuid(mutation.clientMutationId, "clientMutationId");
  uuid(mutation.deviceId, "deviceId");
  uuid(mutation.userId, "userId");
  uuid(mutation.obraId, "obraId");
  uuid(mutation.entityId, "entityId");
  utcInstant(mutation.occurredAt, "occurredAt");
  canonicalMutationJson(mutation.payload);
}

export async function assertCanonicalPayloadHash(
  mutation: CanonicalOutboxMutationRecord,
): Promise<void> {
  assertCanonicalTransportCoherence(mutation);
  const currentHash = await mutationPayloadHash(mutation.payload);
  if (currentHash !== mutation.trace.payloadHash) {
    throw new TypeError("Canonical mutation payload hash is incoherent.");
  }
}

export async function buildCanonicalMutation(
  input: BuildCanonicalMutationInput,
): Promise<BuiltCanonicalMutation> {
  // Preparation is deliberately synchronous: callers cannot mutate provenance
  // while the SHA-256 promise is pending.
  const prepared = prepareCanonicalMutation(input);
  const payloadHash = await hashCanonicalJson(prepared.canonicalPayload);
  const { envelope } = prepared;

  const mutation: CanonicalOutboxMutationRecord = {
    ...envelope,
    payload: { ...envelope.payload },
    changedFields: [...envelope.changedFields],
    entidadeTipo: syncEntityType(envelope.entityType),
    entidadeId: envelope.entityId,
    operacao: prepared.transportOperation,
    baseVersao: envelope.baseVersion,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: envelope.occurredAt,
    updatedAt: envelope.occurredAt,
    transport: prepared.transport,
    dependsOnMutationIds: prepared.dependsOnMutationIds,
    correlationId: prepared.correlationId,
    causationId: prepared.causationId,
    fieldPatch: prepared.fieldPatch,
    trace: {
      actorId: envelope.userId,
      deviceId: envelope.deviceId,
      authorizationScope: prepared.authorizationScope,
      correlationId: prepared.correlationId,
      causationId: prepared.causationId,
      ontologyEventId: prepared.ontologyEventId,
      payloadHash,
    },
    nextAttemptAt: null,
    blockedReason: null,
  };

  assertCanonicalTransportCoherence(mutation);
  return {
    mutation,
    previousSnapshot: prepared.previousSnapshot,
    nextSnapshot: prepared.nextSnapshot,
  };
}

function prepareCanonicalMutation(
  input: BuildCanonicalMutationInput,
): PreparedCanonicalMutation {
  const clientMutationId = uuid(
    input.clientMutationId ?? crypto.randomUUID(),
    "clientMutationId",
  );
  const ontologyEventId = uuid(
    input.ontologyEventId ?? crypto.randomUUID(),
    "ontologyEventId",
  );
  const deviceId = uuid(input.deviceId, "deviceId");
  const userId = uuid(input.userId, "userId");
  const obraId = uuid(input.obraId, "obraId");
  const entityType = syncEntityType(requiredText(input.entityType, "entityType"));
  const entityId = uuid(input.entityId, "entityId");
  const operation = canonicalOperation(input.operation);
  const transportOperation = syncOperation(input.transportOperation);
  if (canonicalOperationForTransport(transportOperation) !== operation) {
    throw new TypeError(
      `transportOperation ${transportOperation} does not represent ${operation}.`,
    );
  }
  const occurredAt = utcInstant(
    input.occurredAt ?? new Date().toISOString(),
    "occurredAt",
  );
  const authorizationScope = authorizationScopeFor(
    input.authorizationScope,
    obraId,
  );
  const dependsOnMutationIds = uuidArray(
    input.dependsOnMutationIds ?? [],
    "dependsOnMutationIds",
  );
  const correlationId = uuid(
    input.correlationId ?? clientMutationId,
    "correlationId",
  );
  const causationId = input.causationId === null || input.causationId === undefined
    ? null
    : uuid(input.causationId, "causationId");
  const transport = outboxTransport(input.transport ?? "SYNC_PUSH");

  if (operation === "CREATE" && input.baseVersion !== null) {
    throw new TypeError("CREATE requires baseVersion null.");
  }
  if (
    operation !== "CREATE" &&
    (!Number.isSafeInteger(input.baseVersion) || (input.baseVersion ?? -1) < 0)
  ) {
    throw new TypeError(`${operation} requires a non-negative baseVersion.`);
  }

  const previousJson = canonicalMutationJson(input.previousSnapshot);
  const nextJson = canonicalMutationJson(input.nextSnapshot);
  const previousSnapshot = recordFromCanonicalJson(
    previousJson,
    "previousSnapshot",
  );
  const nextSnapshot = recordFromCanonicalJson(nextJson, "nextSnapshot");
  const changedFields = changedFieldsBetween(previousSnapshot, nextSnapshot);
  if (input.changedFields !== undefined) {
    const asserted = textArray(input.changedFields, "changedFields");
    if (canonicalMutationJson(asserted) !== canonicalMutationJson(changedFields)) {
      throw new TypeError("changedFields must equal the exact snapshot delta.");
    }
  }

  return {
    envelope: {
      schemaVersion: 13,
      clientMutationId,
      deviceId,
      userId,
      obraId,
      entityType,
      entityId,
      operation,
      baseVersion: input.baseVersion,
      changedFields,
      occurredAt,
      payload: nextSnapshot,
    },
    ontologyEventId,
    transportOperation,
    previousSnapshot,
    nextSnapshot,
    authorizationScope,
    correlationId,
    causationId,
    transport,
    dependsOnMutationIds,
    fieldPatch: fieldPatch(previousSnapshot, nextSnapshot, changedFields),
    canonicalPayload: nextJson,
  };
}

function changedFieldsBetween(
  previous: Record<string, unknown>,
  next: Record<string, unknown>,
): string[] {
  const keys = [...new Set([...Object.keys(previous), ...Object.keys(next)])]
    .sort();
  return keys.filter((field) => {
    const previousHas = Object.prototype.hasOwnProperty.call(previous, field);
    const nextHas = Object.prototype.hasOwnProperty.call(next, field);
    return previousHas !== nextHas ||
      canonicalMutationJson(previous[field]) !== canonicalMutationJson(next[field]);
  });
}

function fieldPatch(
  previous: Record<string, unknown>,
  next: Record<string, unknown>,
  changedFields: readonly string[],
): MutationFieldPatch {
  const changed: Record<string, unknown> = {};
  const baseValues: Record<string, unknown> = {};

  for (const field of changedFields) {
    if (Object.prototype.hasOwnProperty.call(next, field)) {
      changed[field] = next[field];
    }
    if (Object.prototype.hasOwnProperty.call(previous, field)) {
      baseValues[field] = previous[field];
    }
  }

  return { changed, baseValues };
}

function uuid(value: unknown, field: string): string {
  const text = requiredText(value, field);
  if (!UUID_PATTERN.test(text)) {
    throw new TypeError(`${field} must be a canonical UUID.`);
  }
  return text;
}

function utcInstant(value: unknown, field: string): string {
  const text = requiredText(value, field);
  if (!text.endsWith("Z") || !Number.isFinite(Date.parse(text))) {
    throw new TypeError(`${field} must be a UTC ISO instant.`);
  }
  if (new Date(text).toISOString() !== text) {
    throw new TypeError(`${field} must be a canonical UTC ISO instant.`);
  }
  return text;
}

function canonicalOperation(value: unknown): CanonicalMutationOperation {
  const text = requiredText(value, "operation");
  if (!CANONICAL_OPERATIONS.includes(text as CanonicalMutationOperation)) {
    throw new TypeError(`operation ${text} is not supported.`);
  }
  return text as CanonicalMutationOperation;
}

function syncOperation(value: unknown): SyncOperation {
  const text = requiredText(value, "transportOperation");
  if (!(text in TRANSPORT_OPERATION_TO_CANONICAL)) {
    throw new TypeError(`transportOperation ${text} is not supported.`);
  }
  return text as SyncOperation;
}

function canonicalOperationForTransport(
  value: SyncOperation,
): CanonicalMutationOperation {
  return TRANSPORT_OPERATION_TO_CANONICAL[value];
}

function outboxTransport(value: unknown): OutboxTransport {
  const text = requiredText(value, "transport");
  if (!TRANSPORTS.includes(text as OutboxTransport)) {
    throw new TypeError(`transport ${text} is not supported.`);
  }
  return text as OutboxTransport;
}

function requiredText(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new TypeError(`${field} must be a nonblank string.`);
  }
  return value;
}

function syncEntityType(value: string): SyncEntityType {
  if (!SYNC_ENTITY_TYPES.includes(value as SyncEntityType)) {
    throw new TypeError(
      `entityType ${value} does not have a registered sync transport.`,
    );
  }
  return value as SyncEntityType;
}

function authorizationScopeFor(
  values: readonly string[],
  obraId: string,
): string[] {
  const scope = uniqueSortedTextArray(values, "authorizationScope", true);
  if (scope.length === 1 && scope[0] === "ALFA:GLOBAL") {
    return scope;
  }
  const obraIds = scope.map((value, index) =>
    uuid(value, `authorizationScope[${index}]`)
  );
  if (!obraIds.includes(obraId)) {
    throw new TypeError("authorizationScope must include obraId.");
  }
  return obraIds;
}

function uuidArray(values: readonly string[], field: string): string[] {
  if (!Array.isArray(values)) {
    throw new TypeError(`${field} must be an array.`);
  }
  return [...new Set(values.map((value, index) => uuid(value, `${field}[${index}]`)))]
    .sort();
}

function textArray(values: readonly string[], field: string): string[] {
  if (!Array.isArray(values)) {
    throw new TypeError(`${field} must be an array.`);
  }
  return values.map((value, index) =>
    requiredText(value, `${field}[${index}]`)
  );
}

function uniqueSortedTextArray(
  values: readonly string[],
  field: string,
  requireValue = false,
): string[] {
  if (!Array.isArray(values) || (requireValue && values.length === 0)) {
    throw new TypeError(`${field} is required.`);
  }
  return [...new Set(values.map((value, index) =>
    requiredText(value, `${field}[${index}]`)
  ))].sort();
}

function recordFromCanonicalJson(
  value: string,
  field: string,
): Record<string, unknown> {
  const parsed: unknown = JSON.parse(value);
  if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new TypeError(`${field} must be a JSON object.`);
  }
  return parsed as Record<string, unknown>;
}

async function hashCanonicalJson(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}
