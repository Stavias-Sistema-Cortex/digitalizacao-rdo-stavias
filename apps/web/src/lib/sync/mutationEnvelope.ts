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

export async function mutationPayloadHash(
  value: unknown,
): Promise<string> {
  const canonicalPayload = canonicalJson(value);
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
  validateRequiredText(input.entity.type, "entity.type");
  validateRequiredText(input.entity.id, "entity.id");
  validateRequiredText(input.operation, "operation");
  validateRequiredText(input.actor.actorId, "actor.actorId");
  validateRequiredText(input.actor.actorName, "actor.actorName");
  validateRequiredText(input.actor.deviceId, "actor.deviceId");
  input.actor.authorizationScope.forEach((scope, index) =>
    validateRequiredText(scope, `actor.authorizationScope[${index}]`),
  );

  if (input.correlationId !== undefined) {
    validateRequiredText(input.correlationId, "correlationId");
  }
  if (input.causationId !== undefined && input.causationId !== null) {
    validateRequiredText(input.causationId, "causationId");
  }

  canonicalJson(input.previousState);
  canonicalJson(input.newState);

  const clientMutationId = crypto.randomUUID();
  const ontologyEventId = crypto.randomUUID();
  const correlationId = input.correlationId ?? clientMutationId;
  const causationId = input.causationId ?? null;
  const createdAt = input.createdAt ?? new Date().toISOString();
  const fieldPatch = buildFieldPatch(
    input.previousState,
    input.newState,
  );

  return {
    contractVersion: 13,
    clientMutationId,
    entidadeTipo: input.entity.type,
    entidadeId: input.entity.id,
    operacao: input.operation,
    baseVersao: input.baseVersion,
    payload: input.newState,
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: createdAt,
    updatedAt: createdAt,
    transport: input.transport ?? "SYNC_PUSH",
    dependsOnMutationIds: [
      ...new Set(input.dependsOnMutationIds ?? []),
    ],
    correlationId,
    fieldPatch,
    trace: {
      actorId: input.actor.actorId,
      deviceId: input.actor.deviceId,
      authorizationScope: [...input.actor.authorizationScope],
      correlationId,
      causationId,
      ontologyEventId,
      payloadHash: await mutationPayloadHash(input.newState),
    },
    nextAttemptAt: null,
    blockedReason: null,
  };
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

function validateRequiredText(value: string, field: string): void {
  if (!value.trim()) {
    throw new TypeError(`${field} is required.`);
  }
}
