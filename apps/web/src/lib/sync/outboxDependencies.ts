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
      /*
       * Dependência ausente da fila está satisfeita, e tratá-la como bloqueio
       * foi o defeito mais caro desta área.
       *
       * A outbox é fila, não arquivo: quem aplica sai dela. Uma dependência que
       * não está mais ali já teve desfecho — aplicou, ou foi descartada. Nos
       * dois casos prender o filho é errado, e de formas diferentes. No
       * primeiro, o bloqueio é falso: o pai já subiu e o filho podia ter ido
       * junto. No segundo, troca uma recusa do servidor — visível, com saída
       * pela tela — por um congelamento mudo, sem status, sem erro, sem nada
       * que a pessoa possa apertar.
       *
       * Foi assim que três adições de membro ficaram paradas: dependiam de
       * mutações que uma limpeza de fila removeu, e ninguém tinha como saber.
       * A tela dizia "3 alterações pendentes. O sistema tentará sincronizar
       * automaticamente" enquanto o motor nem as considerava.
       *
       * `missingId` continua sendo devolvido porque a análise informa quem
       * ficou órfão — o que se removeu foi o poder de vetar o envio. Se o
       * servidor precisar do pai, ele recusa e a recusa aparece.
       */
      return {
        satisfied: true,
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

/**
 * Por que uma linha PENDING não entra no envio desta janela.
 *
 * `AGUARDANDO_RETENTATIVA` e `OUTRO_TRANSPORTE` são espera legítima: a primeira
 * volta sozinha quando a espera vence, a segunda sobe pelo caminho dos anexos.
 * Os outros três são impasse — ninguém os desfaz sem alguém saber deles.
 */
export type MotivoDeTrava =
  | "BLOQUEADA"
  | "CICLO"
  | "DEPENDENCIA_PENDENTE";

export type MotivoDeNaoEnvio =
  | MotivoDeTrava
  | "OUTRO_TRANSPORTE"
  | "AGUARDANDO_RETENTATIVA";

export interface PendenciaQueNaoSobe {
  mutationId: string;
  entidadeTipo: string;
  entidadeId: string;
  operacao: string;
  motivo: MotivoDeTrava;
  /** O código do bloqueio, quando o motivo é `BLOQUEADA`. */
  detalhe: string | null;
}

/**
 * A regra única de quem sobe, expressa como o motivo de não subir.
 *
 * Estava escrita só dentro do filtro do envio, e por isso a tela não tinha como
 * repeti-la: a tarja contava toda linha PENDING e prometia envio automático
 * para todas, inclusive as que este filtro descarta em silêncio. Uma segunda
 * cópia da regra na camada de exibição envelheceria diferente, e os dois lugares
 * passariam a discordar sobre o mesmo aparelho — que é justamente o defeito.
 */
function motivoDeNaoEnvio(
  mutation: OutboxMutationRecord,
  byId: ReadonlyMap<string, OutboxMutationRecord>,
  cycles: ReadonlySet<string>,
  now: number,
): MotivoDeNaoEnvio | null {
  if (mutation.blockedReason) {
    return "BLOQUEADA";
  }
  if (!isSyncPush(mutation)) {
    return "OUTRO_TRANSPORTE";
  }
  if (
    typeof mutation.nextAttemptAt === "string" &&
    Number.isFinite(Date.parse(mutation.nextAttemptAt)) &&
    Date.parse(mutation.nextAttemptAt) > now
  ) {
    return "AGUARDANDO_RETENTATIVA";
  }
  if (cycles.has(mutation.clientMutationId)) {
    return "CICLO";
  }
  const temDependenciaPendente = dependencyIds(mutation).some(
    (dependencyId) => !resolveDependency(byId, dependencyId).satisfied,
  );
  return temDependenciaPendente ? "DEPENDENCIA_PENDENTE" : null;
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
  const cycles = new Set(analyzeOutboxDependencies(mutations).cycles);
  return mutations
    .filter(
      (mutation) =>
        mutation.status === "PENDING" &&
        motivoDeNaoEnvio(mutation, byId, cycles, now) === null,
    )
    .sort((left, right) =>
      left.criadaNoClienteEm.localeCompare(right.criadaNoClienteEm),
    )
    .slice(0, safeLimit);
}

/**
 * As linhas que a fila conta como pendentes e que o envio nunca vai buscar.
 *
 * Sem isto, o impasse não tinha como aparecer: a tela dizia "aguardando
 * sincronização, o sistema tentará automaticamente" para uma linha que o motor
 * sequer considera, e o único jeito de descobrir o motivo era despejar o
 * IndexedDB no console.
 *
 * A espera legítima fica de fora de propósito. Chamar de travado o que volta
 * sozinho em um minuto gastaria o alarme com o caso que não precisa dele.
 */
export function explicarPendenciasQueNaoSobem(
  mutations: OutboxMutationRecord[],
  now = Date.now(),
): PendenciaQueNaoSobe[] {
  const byId = new Map(
    mutations.map((mutation) => [
      mutation.clientMutationId,
      mutation,
    ]),
  );
  const cycles = new Set(analyzeOutboxDependencies(mutations).cycles);
  const travantes: ReadonlySet<string> = new Set<MotivoDeTrava>([
    "BLOQUEADA",
    "CICLO",
    "DEPENDENCIA_PENDENTE",
  ]);

  return mutations
    .filter((mutation) => mutation.status === "PENDING")
    .map((mutation) => ({
      mutation,
      motivo: motivoDeNaoEnvio(mutation, byId, cycles, now),
    }))
    .filter(
      (item): item is {
        mutation: OutboxMutationRecord;
        motivo: MotivoDeTrava;
      } => item.motivo !== null && travantes.has(item.motivo),
    )
    .map(({ mutation, motivo }) => ({
      mutationId: mutation.clientMutationId,
      entidadeTipo: mutation.entidadeTipo,
      entidadeId: mutation.entidadeId,
      operacao: mutation.operacao,
      motivo,
      detalhe:
        motivo === "BLOQUEADA" && typeof mutation.blockedReason === "string"
          ? mutation.blockedReason.trim()
          : null,
    }))
    .sort((left, right) =>
      left.mutationId.localeCompare(right.mutationId),
    );
}
