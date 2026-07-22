import type {
  CanonicalOutboxMutationRecord,
  MutationFieldPatch,
  OutboxMutationRecord,
} from "./db.types";

/**
 * A origem deste bloqueio é local e técnica: não representa uma rejeição de
 * negócio pelo servidor. O registro permanece íntegro para revisão na Memória.
 */
export const LEGACY_TRACE_REVIEW_REASON =
  "Rastro canônico ausente; requer revisão.";

export const BLOCKED_DEPENDENCY_REVIEW_REASON =
  "Dependência local bloqueada; requer revisão antes de nova tentativa.";

export const MISSING_DEPENDENCY_REVIEW_REASON =
  "Dependência local ausente; requer revisão antes de nova tentativa.";

export const CYCLIC_DEPENDENCY_REVIEW_REASON =
  "Dependência cíclica na fila local; requer revisão.";

export type CanonicalDispatchableOutboxMutation =
  CanonicalOutboxMutationRecord & {
    dependsOnMutationIds: string[];
  };

function isObjectRecord(
  value: unknown,
): value is Record<string, unknown> {
  return (
    value !== null &&
    typeof value === "object" &&
    !Array.isArray(value)
  );
}

function isNonBlankText(value: unknown): value is string {
  return typeof value === "string" && value.trim().length > 0;
}

function isStringList(
  value: unknown,
  options: { allowEmpty: boolean },
): value is string[] {
  return (
    Array.isArray(value) &&
    (options.allowEmpty || value.length > 0) &&
    value.every(isNonBlankText)
  );
}

function isFieldPatch(
  value: unknown,
): value is MutationFieldPatch {
  return (
    isObjectRecord(value) &&
    isObjectRecord(value.changed) &&
    isObjectRecord(value.baseValues)
  );
}

/**
 * This is deliberately a structural guard. Authorization, UUID ownership and
 * the payload hash remain authoritative server checks; this only prevents an
 * old, partial browser record from being serialized as if it were canonical.
 */
export function isCanonicalOutboxMutation(
  mutation: OutboxMutationRecord,
): mutation is CanonicalDispatchableOutboxMutation {
  const trace = mutation.trace;
  const allowsEmptyAuthorizationScope =
    mutation.entidadeTipo === "MENSAGEM" ||
    mutation.entidadeTipo === "MENSAGEM_ANEXO";

  return (
    mutation.contractVersion === 13 &&
    isNonBlankText(mutation.clientMutationId) &&
    isNonBlankText(mutation.entidadeTipo) &&
    isNonBlankText(mutation.entidadeId) &&
    isNonBlankText(mutation.operacao) &&
    (mutation.baseVersao === null ||
      (typeof mutation.baseVersao === "number" &&
        Number.isFinite(mutation.baseVersao))) &&
    isObjectRecord(mutation.payload) &&
    isNonBlankText(mutation.criadaNoClienteEm) &&
    isNonBlankText(mutation.correlationId) &&
    isFieldPatch(mutation.fieldPatch) &&
    isObjectRecord(trace) &&
    isNonBlankText(trace.actorId) &&
    isNonBlankText(trace.deviceId) &&
    isStringList(trace.authorizationScope, {
      allowEmpty: allowsEmptyAuthorizationScope,
    }) &&
    trace.correlationId === mutation.correlationId &&
    (trace.causationId === null || isNonBlankText(trace.causationId)) &&
    isNonBlankText(trace.ontologyEventId) &&
    typeof trace.payloadHash === "string" &&
    /^[0-9a-f]{64}$/i.test(trace.payloadHash) &&
    isStringList(mutation.dependsOnMutationIds, {
      allowEmpty: true,
    }) &&
    Object.hasOwn(mutation, "nextAttemptAt") &&
    (mutation.nextAttemptAt === null ||
      typeof mutation.nextAttemptAt === "string") &&
    Object.hasOwn(mutation, "blockedReason") &&
    (mutation.blockedReason === null ||
      typeof mutation.blockedReason === "string")
  );
}

export function isOutboxMutationBlocked(
  mutation: OutboxMutationRecord,
): boolean {
  return (
    typeof mutation.blockedReason === "string" &&
    mutation.blockedReason.trim().length > 0
  );
}

export function isOutboxMutationInActiveDispatchState(
  mutation: OutboxMutationRecord,
): boolean {
  return (
    mutation.status === "PENDING" ||
    mutation.status === "SYNCING" ||
    mutation.status === "ERROR"
  );
}
