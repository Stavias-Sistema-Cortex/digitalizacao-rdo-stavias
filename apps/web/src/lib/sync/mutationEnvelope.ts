import type {
  CanonicalOutboxMutationRecord,
  MutationFieldPatch,
  OutboxTransport,
  SyncEntityType,
  SyncOperation,
} from "../db/db.types";

export interface MutationActor {
  actorId: string;
  actorName: string;
  deviceId: string;
  authorizationScope: readonly string[];
}

export interface MutationEntity {
  type: SyncEntityType;
  id: string;
}

export interface BuildMutationEnvelopeInput {
  entity: MutationEntity;
  operation: SyncOperation;
  baseVersion: number | null;
  previousState: Record<string, unknown>;
  newState: Record<string, unknown>;
  actor: MutationActor;
  correlationId?: string;
  causationId?: string | null;
  createdAt?: string;
  transport?: OutboxTransport;
  dependsOnMutationIds?: readonly string[];
}

export interface BuiltMutationEnvelopeSnapshots {
  mutation: CanonicalOutboxMutationRecord;
  previousState: Record<string, unknown>;
  newState: Record<string, unknown>;
  actor: MutationActor;
}

interface PreparedMutationEnvelope {
  entity: MutationEntity;
  operation: SyncOperation;
  baseVersion: number | null;
  previousState: Record<string, unknown>;
  newState: Record<string, unknown>;
  newStateJson: string;
  actor: MutationActor;
  clientMutationId: string;
  ontologyEventId: string;
  correlationId: string;
  causationId: string | null;
  createdAt: string;
  transport: OutboxTransport;
  dependsOnMutationIds: string[];
  fieldPatch: MutationFieldPatch;
}

export async function mutationPayloadHash(
  value: unknown,
): Promise<string> {
  const canonicalPayload = canonicalJson(value);

  return hashCanonicalJson(canonicalPayload);
}

async function hashCanonicalJson(
  canonicalPayload: string,
): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(canonicalPayload),
    ),
  );

  return Array.from(digest, (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

export async function buildMutationEnvelope(
  input: BuildMutationEnvelopeInput,
): Promise<CanonicalOutboxMutationRecord> {
  return (await buildMutationEnvelopeWithSnapshots(input)).mutation;
}

export async function buildMutationEnvelopeWithSnapshots(
  input: BuildMutationEnvelopeInput,
): Promise<BuiltMutationEnvelopeSnapshots> {
  const prepared = prepareMutationEnvelope(input);
  const payloadHash = await hashCanonicalJson(prepared.newStateJson);

  const mutation: CanonicalOutboxMutationRecord = {
    contractVersion: 13,
    clientMutationId: prepared.clientMutationId,
    entidadeTipo: prepared.entity.type,
    entidadeId: prepared.entity.id,
    operacao: prepared.operation,
    baseVersao: prepared.baseVersion,
    payload: prepared.newState,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: prepared.createdAt,
    updatedAt: prepared.createdAt,
    transport: prepared.transport,
    dependsOnMutationIds: prepared.dependsOnMutationIds,
    correlationId: prepared.correlationId,
    fieldPatch: prepared.fieldPatch,
    trace: {
      actorId: prepared.actor.actorId,
      deviceId: prepared.actor.deviceId,
      authorizationScope: [...prepared.actor.authorizationScope],
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
    previousState: prepared.previousState,
    newState: prepared.newState,
    actor: prepared.actor,
  };
}

function prepareMutationEnvelope(
  input: BuildMutationEnvelopeInput,
): PreparedMutationEnvelope {
  const entity: MutationEntity = {
    type: input.entity.type,
    id: input.entity.id,
  };
  const operation = input.operation;
  const baseVersion = input.baseVersion;
  const actorId = input.actor.actorId;
  const actorName = input.actor.actorName;
  const deviceId = input.actor.deviceId;
  const authorizationScope = snapshotRequiredTextArray(
    input.actor.authorizationScope,
    "actor.authorizationScope",
  );
  const correlationIdInput = input.correlationId;
  const causationIdInput = input.causationId;
  const createdAtInput = input.createdAt;
  const transport = input.transport ?? "SYNC_PUSH";
  const dependsOnMutationIds = snapshotRequiredTextArray(
    input.dependsOnMutationIds ?? [],
    "dependsOnMutationIds",
  );
  const previousStateJson = canonicalJson(input.previousState);
  const newStateJson = canonicalJson(input.newState);

  validateRequiredText(entity.type, "entity.type");
  validateRequiredText(entity.id, "entity.id");
  validateRequiredText(operation, "operation");
  validateRequiredText(actorId, "actor.actorId");
  validateRequiredText(actorName, "actor.actorName");
  validateRequiredText(deviceId, "actor.deviceId");
  if (authorizationScope.length === 0) {
    throw new TypeError("actor.authorizationScope is required.");
  }
  if (baseVersion !== null && !Number.isFinite(baseVersion)) {
    throw new TypeError("baseVersion must be finite or null.");
  }
  if (correlationIdInput !== undefined) {
    validateRequiredText(correlationIdInput, "correlationId");
  }
  if (causationIdInput !== undefined && causationIdInput !== null) {
    validateRequiredText(causationIdInput, "causationId");
  }
  if (createdAtInput !== undefined) {
    validateRequiredText(createdAtInput, "createdAt");
  }
  validateRequiredText(transport, "transport");

  const previousState = recordSnapshot(
    previousStateJson,
    "previousState",
  );
  const newState = recordSnapshot(newStateJson, "newState");
  const actor: MutationActor = {
    actorId,
    actorName,
    deviceId,
    authorizationScope,
  };
  const clientMutationId = crypto.randomUUID();
  const ontologyEventId = crypto.randomUUID();
  const correlationId = correlationIdInput ?? clientMutationId;
  const causationId = causationIdInput ?? null;
  const createdAt = createdAtInput ?? new Date().toISOString();

  return {
    entity,
    operation,
    baseVersion,
    previousState,
    newState,
    newStateJson,
    actor,
    clientMutationId,
    ontologyEventId,
    correlationId,
    causationId,
    createdAt,
    transport,
    dependsOnMutationIds: [...new Set(dependsOnMutationIds)],
    fieldPatch: buildFieldPatch(previousState, newState),
  };
}

function recordSnapshot(
  canonicalValue: string,
  field: string,
): Record<string, unknown> {
  const snapshot: unknown = JSON.parse(canonicalValue);
  if (
    snapshot === null ||
    typeof snapshot !== "object" ||
    Array.isArray(snapshot)
  ) {
    throw new TypeError(`${field} must be a JSON object.`);
  }

  return snapshot as Record<string, unknown>;
}

function buildFieldPatch(
  previousState: Record<string, unknown>,
  newState: Record<string, unknown>,
): MutationFieldPatch {
  const changed: Record<string, unknown> = {};
  const baseValues: Record<string, unknown> = {};

  for (const key of Object.keys(newState).sort()) {
    const hasBaseValue = Object.prototype.hasOwnProperty.call(
      previousState,
      key,
    );
    if (
      hasBaseValue &&
      canonicalJson(previousState[key]) === canonicalJson(newState[key])
    ) {
      continue;
    }

    changed[key] = newState[key];
    if (hasBaseValue) {
      baseValues[key] = previousState[key];
    }
  }

  return { changed, baseValues };
}

function canonicalJson(value: unknown): string {
  return canonicalJsonAt(value, "$", new WeakSet<object>());
}

function canonicalJsonAt(
  value: unknown,
  path: string,
  ancestors: WeakSet<object>,
): string {
  if (value === null) {
    return "null";
  }

  switch (typeof value) {
    case "string":
    case "boolean":
      return JSON.stringify(value);
    case "number":
      if (!Number.isFinite(value)) {
        throw new TypeError(
          `Canonical JSON rejects non-finite number at ${path}.`,
        );
      }
      return JSON.stringify(value);
    case "undefined":
      throw new TypeError(`Canonical JSON rejects undefined at ${path}.`);
    case "function":
      throw new TypeError(`Canonical JSON rejects functions at ${path}.`);
    case "bigint":
    case "symbol":
      throw new TypeError(
        `Canonical JSON rejects ${typeof value} at ${path}.`,
      );
    case "object":
      break;
  }

  if (ancestors.has(value)) {
    throw new TypeError(`Canonical JSON rejects cycles at ${path}.`);
  }
  ancestors.add(value);

  try {
    if (Array.isArray(value)) {
      const items: string[] = [];
      for (let index = 0; index < value.length; index += 1) {
        items.push(
          canonicalJsonAt(value[index], `${path}[${index}]`, ancestors),
        );
      }
      return `[${items.join(",")}]`;
    }

    const prototype = Object.getPrototypeOf(value);
    if (prototype !== Object.prototype && prototype !== null) {
      throw new TypeError(
        `Canonical JSON rejects non-plain object at ${path}.`,
      );
    }
    if (Object.getOwnPropertySymbols(value).length > 0) {
      throw new TypeError(
        `Canonical JSON rejects symbol keys at ${path}.`,
      );
    }

    const record = value as Record<string, unknown>;
    const entries = Object.keys(record)
      .sort()
      .map((key) =>
        `${JSON.stringify(key)}:${canonicalJsonAt(
          record[key],
          `${path}.${key}`,
          ancestors,
        )}`,
      );
    return `{${entries.join(",")}}`;
  } finally {
    ancestors.delete(value);
  }
}

function snapshotRequiredTextArray(
  values: readonly string[],
  field: string,
): string[] {
  const snapshot: string[] = [];

  for (let index = 0; index < values.length; index += 1) {
    const value: unknown = values[index];
    if (typeof value !== "string" || !value.trim()) {
      throw new TypeError(
        `${field}[${index}] must be a nonblank string.`,
      );
    }
    snapshot.push(value);
  }

  return snapshot;
}

function validateRequiredText(value: unknown, field: string): void {
  if (typeof value !== "string" || !value.trim()) {
    throw new TypeError(`${field} is required.`);
  }
}
