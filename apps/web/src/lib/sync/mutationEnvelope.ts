import type {
  CanonicalMutationEnvelopeV13,
  CanonicalMutationOperation,
  CanonicalOutboxMutationRecord,
  MutationFieldPatch,
  OutboxTransport,
  SyncEntityType,
  SyncOperation,
} from "../db/db.types";

export type {
  CanonicalMutationEnvelopeV13,
  CanonicalMutationOperation,
} from "../db/db.types";

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
  changedFields: readonly string[];
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
  return hashCanonicalJson(canonicalJson(value));
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

  return {
    mutation,
    previousSnapshot: prepared.previousSnapshot,
    nextSnapshot: prepared.nextSnapshot,
  };
}

function prepareCanonicalMutation(
  input: BuildCanonicalMutationInput,
): PreparedCanonicalMutation {
  const clientMutationId = optionalRequiredText(
    input.clientMutationId,
    "clientMutationId",
  ) ?? crypto.randomUUID();
  const ontologyEventId = optionalRequiredText(
    input.ontologyEventId,
    "ontologyEventId",
  ) ?? crypto.randomUUID();
  const deviceId = requiredText(input.deviceId, "deviceId");
  const userId = requiredText(input.userId, "userId");
  const obraId = requiredText(input.obraId, "obraId");
  const entityType = requiredText(input.entityType, "entityType");
  syncEntityType(entityType);
  const entityId = requiredText(input.entityId, "entityId");
  const operation = requiredText(
    input.operation,
    "operation",
  ) as CanonicalMutationOperation;
  const transportOperation = requiredText(
    input.transportOperation,
    "transportOperation",
  ) as SyncOperation;
  const occurredAt = optionalRequiredText(
    input.occurredAt,
    "occurredAt",
  ) ?? new Date().toISOString();
  const authorizationScope = requiredTextArray(
    input.authorizationScope,
    "authorizationScope",
    true,
  );
  if (!authorizationScope.includes(obraId)) {
    throw new TypeError("authorizationScope must include obraId.");
  }
  const changedFields = requiredTextArray(
    input.changedFields,
    "changedFields",
    true,
  );
  const dependsOnMutationIds = requiredTextArray(
    input.dependsOnMutationIds ?? [],
    "dependsOnMutationIds",
    false,
  );
  const correlationId = optionalRequiredText(
    input.correlationId,
    "correlationId",
  ) ?? clientMutationId;
  const causationId = input.causationId === null
    ? null
    : optionalRequiredText(input.causationId, "causationId") ?? null;
  const transport = input.transport ?? "SYNC_PUSH";

  if (
    input.baseVersion !== null &&
    (!Number.isSafeInteger(input.baseVersion) || input.baseVersion < 0)
  ) {
    throw new TypeError("baseVersion must be a non-negative integer or null.");
  }

  const previousJson = canonicalJson(input.previousSnapshot);
  const nextJson = canonicalJson(input.nextSnapshot);
  const previousSnapshot = recordFromCanonicalJson(
    previousJson,
    "previousSnapshot",
  );
  const nextSnapshot = recordFromCanonicalJson(nextJson, "nextSnapshot");
  const uniqueChangedFields = [...new Set(changedFields)];

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
      changedFields: uniqueChangedFields,
      occurredAt,
      payload: nextSnapshot,
    },
    ontologyEventId,
    transportOperation,
    previousSnapshot,
    nextSnapshot,
    authorizationScope: [...new Set(authorizationScope)],
    correlationId,
    causationId,
    transport,
    dependsOnMutationIds: [...new Set(dependsOnMutationIds)],
    fieldPatch: fieldPatch(
      previousSnapshot,
      nextSnapshot,
      uniqueChangedFields,
    ),
    canonicalPayload: nextJson,
  };
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

function requiredText(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new TypeError(`${field} must be a nonblank string.`);
  }
  return value;
}

function syncEntityType(value: string): SyncEntityType {
  const supported: readonly SyncEntityType[] = [
    "RDO",
    "CONVERSA",
    "MENSAGEM",
    "MENSAGEM_ANEXO",
    "SOLICITACAO_COMPRA",
    "COMPRA",
  ];
  if (!supported.includes(value as SyncEntityType)) {
    throw new TypeError(
      `entityType ${value} does not have a registered sync transport.`,
    );
  }
  return value as SyncEntityType;
}

function optionalRequiredText(
  value: unknown,
  field: string,
): string | undefined {
  if (value === undefined) return undefined;
  return requiredText(value, field);
}

function requiredTextArray(
  values: readonly string[],
  field: string,
  requireValue: boolean,
): string[] {
  if (!Array.isArray(values) || (requireValue && values.length === 0)) {
    throw new TypeError(`${field} is required.`);
  }

  return Array.from({ length: values.length }, (_unused, index) =>
    requiredText(values[index], `${field}[${index}]`),
  );
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

function canonicalJson(value: unknown): string {
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

async function hashCanonicalJson(value: string): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    new TextEncoder().encode(value),
  );
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}
