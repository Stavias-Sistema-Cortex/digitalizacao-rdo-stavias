import type { OutboxMutationRecord } from "../db/db.types";

export interface MissingOutboxDependency {
  mutationId: string;
  dependencyId: string;
}

export interface OutboxDependencyAnalysis {
  cycles: string[];
  missingDependencies: MissingOutboxDependency[];
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

  for (const mutation of mutations) {
    for (const dependencyId of dependencyIds(mutation)) {
      if (!byId.has(dependencyId)) {
        missingDependencies.push({
          mutationId: mutation.clientMutationId,
          dependencyId,
        });
      }
    }
  }

  const state = new Map<string, "VISITING" | "VISITED">();
  const stack: string[] = [];
  const cycles = new Set<string>();

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
    for (const dependencyId of dependencyIds(mutation)) {
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
        (dependencyId) => byId.get(dependencyId)?.status === "SYNCED",
      );
    })
    .sort((left, right) =>
      left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
    )
    .slice(0, safeLimit);
}
