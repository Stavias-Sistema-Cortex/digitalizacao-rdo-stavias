import type {
  CanonicalOutboxMutationRecord,
  OutboxMutationRecord,
} from "../db/db.types";
import { isCanonicalOutboxMutation } from "./mutationEnvelope";

export interface MissingOutboxDependency {
  mutationId: string;
  dependencyId: string;
}

export interface InvalidOutboxDependencyAlias {
  mutationId: string;
  dependencyId: string;
  aliasId: string;
  reason:
    | "ENTITY_MISMATCH"
    | "NON_CANONICAL_CREATE"
    | "CAUSATION_MISMATCH";
}

export interface OutboxDependencyAnalysis {
  cycles: string[];
  missingDependencies: MissingOutboxDependency[];
  invalidAliases: InvalidOutboxDependencyAlias[];
}

function dependencyIds(mutation: OutboxMutationRecord): string[] {
  if (!Array.isArray(mutation.dependsOnMutationIds)) {
    return [];
  }

  return [
    ...new Set(
      mutation.dependsOnMutationIds
        .filter((value): value is string =>
          typeof value === "string" && Boolean(value.trim()),
        )
        .map((value) => value.trim()),
    ),
  ];
}

function isSyncPush(mutation: OutboxMutationRecord): boolean {
  return !mutation.transport || mutation.transport === "SYNC_PUSH";
}

function supersededById(mutation: OutboxMutationRecord): string | null {
  if (mutation.status !== "REJECTED") return null;
  const blockedReason = mutation.blockedReason;
  if (typeof blockedReason !== "string") return null;
  const match = /^SUPERSEDED_BY:([^\s:]+)$/.exec(blockedReason.trim());
  return match?.[1] ?? null;
}

function sameLogicalEntity(
  left: OutboxMutationRecord,
  right: OutboxMutationRecord,
): boolean {
  return left.entidadeTipo === right.entidadeTipo &&
    left.entidadeId === right.entidadeId;
}

interface DependencyResolution {
  satisfied: boolean;
  missingId: string | null;
  cycle: string[];
  invalidAliasId: string | null;
  invalidAliasReason: InvalidOutboxDependencyAlias["reason"] | null;
}

function isCanonicalRdoCreate(
  mutation: OutboxMutationRecord,
): mutation is CanonicalOutboxMutationRecord {
  return isCanonicalOutboxMutation(mutation) &&
    mutation.entidadeTipo === "RDO" &&
    mutation.operation === "CREATE" &&
    mutation.operacao === "CRIAR_RDO" &&
    mutation.transport === "SYNC_PUSH";
}

function isLegacyRdoCreate(
  mutation: OutboxMutationRecord,
): boolean {
  return !isCanonicalOutboxMutation(mutation) &&
    mutation.entidadeTipo === "RDO" &&
    mutation.operacao === "CRIAR_RDO" &&
    (!mutation.transport || mutation.transport === "SYNC_PUSH");
}

function isCanonicalRdoRecovery(
  mutation: OutboxMutationRecord,
): mutation is CanonicalOutboxMutationRecord {
  return isCanonicalOutboxMutation(mutation) &&
    mutation.entidadeTipo === "RDO" &&
    (
      (
        mutation.operation === "CREATE" &&
        mutation.operacao === "CRIAR_RDO"
      ) ||
      (
        mutation.operation === "UPDATE" &&
        mutation.operacao === "ATUALIZAR_RDO_RASCUNHO" &&
        mutation.baseVersion !== null
      )
    ) &&
    mutation.transport === "SYNC_PUSH";
}

function aliasMismatchReason(
  current: OutboxMutationRecord,
  replacement: OutboxMutationRecord,
): InvalidOutboxDependencyAlias["reason"] | null {
  if (!sameLogicalEntity(current, replacement)) return "ENTITY_MISMATCH";
  const validCanonicalReplacement =
    isCanonicalRdoCreate(current) && isCanonicalRdoCreate(replacement);
  const validLegacyRecovery =
    isLegacyRdoCreate(current) && isCanonicalRdoRecovery(replacement);
  if (!validCanonicalReplacement && !validLegacyRecovery) {
    return "NON_CANONICAL_CREATE";
  }
  if (replacement.causationId !== current.clientMutationId) {
    return "CAUSATION_MISMATCH";
  }
  return null;
}

function resolveDependency(
  byId: ReadonlyMap<string, OutboxMutationRecord>,
  dependencyId: string,
): DependencyResolution {
  const path: string[] = [];
  const visited = new Map<string, number>();
  let currentId = dependencyId;

  while (true) {
    const cycleAt = visited.get(currentId);
    if (cycleAt !== undefined) {
      return {
        satisfied: false,
        missingId: null,
        cycle: path.slice(cycleAt),
        invalidAliasId: null,
        invalidAliasReason: null,
      };
    }
    visited.set(currentId, path.length);
    path.push(currentId);
    const current = byId.get(currentId);
    if (!current) {
      return {
        satisfied: false,
        missingId: currentId,
        cycle: [],
        invalidAliasId: null,
        invalidAliasReason: null,
      };
    }
    if (current.status === "SYNCED") {
      return {
        satisfied: true,
        missingId: null,
        cycle: [],
        invalidAliasId: null,
        invalidAliasReason: null,
      };
    }
    const replacementId = supersededById(current);
    if (!replacementId) {
      return {
        satisfied: false,
        missingId: null,
        cycle: [],
        invalidAliasId: null,
        invalidAliasReason: null,
      };
    }
    const replacement = byId.get(replacementId);
    if (!replacement) {
      return {
        satisfied: false,
        missingId: replacementId,
        cycle: [],
        invalidAliasId: null,
        invalidAliasReason: null,
      };
    }
    const mismatchReason = aliasMismatchReason(current, replacement);
    if (mismatchReason) {
      return {
        satisfied: false,
        missingId: null,
        cycle: [],
        invalidAliasId: currentId,
        invalidAliasReason: mismatchReason,
      };
    }
    currentId = replacementId;
  }
}

export function analyzeOutboxDependencies(
  mutations: OutboxMutationRecord[],
): OutboxDependencyAnalysis {
  const byId = new Map(
    mutations.map((mutation) => [
      mutation.clientMutationId,
      mutation,
    ]),
  );
  const missingDependencies: MissingOutboxDependency[] = [];
  const invalidAliases: InvalidOutboxDependencyAlias[] = [];
  const cycles = new Set<string>();

  for (const mutation of mutations) {
    for (const dependencyId of dependencyIds(mutation)) {
      const resolution = resolveDependency(byId, dependencyId);
      if (resolution.missingId) {
        missingDependencies.push({
          mutationId: mutation.clientMutationId,
          dependencyId: resolution.missingId,
        });
      }
      for (const cycleId of resolution.cycle) cycles.add(cycleId);
      if (resolution.invalidAliasId && resolution.invalidAliasReason) {
        invalidAliases.push({
          mutationId: mutation.clientMutationId,
          dependencyId,
          aliasId: resolution.invalidAliasId,
          reason: resolution.invalidAliasReason,
        });
      }
    }
  }

  const state = new Map<string, "VISITING" | "VISITED">();
  const stack: string[] = [];

  function visit(mutationId: string): void {
    const currentState = state.get(mutationId);
    if (currentState === "VISITED") {
      return;
    }
    if (currentState === "VISITING") {
      const cycleStart = stack.lastIndexOf(mutationId);
      for (const id of stack.slice(cycleStart)) {
        cycles.add(id);
      }
      return;
    }

    const mutation = byId.get(mutationId);
    if (!mutation) {
      return;
    }
    state.set(mutationId, "VISITING");
    stack.push(mutationId);
    const aliasId = supersededById(mutation);
    for (const dependencyId of [
      ...dependencyIds(mutation),
      ...(aliasId ? [aliasId] : []),
    ]) {
      visit(dependencyId);
    }
    stack.pop();
    state.set(mutationId, "VISITED");
  }

  for (const mutation of mutations) {
    visit(mutation.clientMutationId);
  }

  return {
    cycles: [...cycles].sort(),
    missingDependencies: missingDependencies.sort((left, right) =>
      `${left.mutationId}:${left.dependencyId}`.localeCompare(
        `${right.mutationId}:${right.dependencyId}`,
      ),
    ),
    invalidAliases: invalidAliases.sort((left, right) =>
      `${left.mutationId}:${left.dependencyId}:${left.aliasId}`.localeCompare(
        `${right.mutationId}:${right.dependencyId}:${right.aliasId}`,
      ),
    ),
  };
}

export function selectReadyOutboxMutations(
  mutations: OutboxMutationRecord[],
  limit: number,
  now = Date.now(),
): OutboxMutationRecord[] {
  const safeLimit = Math.max(0, Math.floor(limit));
  if (safeLimit === 0) {
    return [];
  }

  const byId = new Map(
    mutations.map((mutation) => [
      mutation.clientMutationId,
      mutation,
    ]),
  );
  const analysis = analyzeOutboxDependencies(mutations);
  const cycles = new Set(analysis.cycles);
  const missingByMutation = new Set(
    analysis.missingDependencies.map((item) => item.mutationId),
  );

  return mutations
    .filter((mutation) => {
      if (
        mutation.status !== "PENDING" ||
        Boolean(mutation.blockedReason) ||
        !isSyncPush(mutation) ||
        (typeof mutation.nextAttemptAt === "string" &&
          Number.isFinite(Date.parse(mutation.nextAttemptAt)) &&
          Date.parse(mutation.nextAttemptAt) > now) ||
        cycles.has(mutation.clientMutationId) ||
        missingByMutation.has(mutation.clientMutationId)
      ) {
        return false;
      }

      return dependencyIds(mutation).every(
        (dependencyId) => resolveDependency(byId, dependencyId).satisfied,
      );
    })
    .sort((left, right) =>
      left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
    )
    .slice(0, safeLimit);
}
