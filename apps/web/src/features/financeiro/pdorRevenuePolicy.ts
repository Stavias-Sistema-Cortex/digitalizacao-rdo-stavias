import type {
  LocalRdoRecord,
  OutboxMutationRecord,
} from "../../lib/db/db.types";

export const PDOR_REVENUE_ALGORITHM_VERSION = "PDOR-REVENUE-3";
export const PDOR_REVENUE_MODEL_VERSION = "PDOR-0.5.1";
export const PDOR_REVENUE_ASSUMPTIONS_VERSION =
  "PDOR-ASSUMPTIONS-0.5.0";

const PENDING_STATUSES = new Set<OutboxMutationRecord["status"]>([
  "PENDING",
  "SYNCING",
  "ERROR",
  "CONFLICT",
]);
const CATALOG_OPERATIONS = new Set<OutboxMutationRecord["operacao"]>([
  "CRIAR_SERVICO_CATALOGO",
  "ATUALIZAR_SERVICO_CATALOGO",
  "EXCLUIR_SERVICO_CATALOGO",
  "RESTAURAR_SERVICO_CATALOGO",
]);
const PRICE_OPERATIONS = new Set<OutboxMutationRecord["operacao"]>([
  "CRIAR_PRECO_SERVICO",
  "ATUALIZAR_PRECO_SERVICO",
  "SUBSTITUIR_PRECO_SERVICO",
  "CANCELAR_PRECO_SERVICO",
]);
const WORKSITE_LIFECYCLE_OPERATIONS = new Set<
  OutboxMutationRecord["operacao"]
>(["ARQUIVAR_OBRA", "RESTAURAR_OBRA"]);
const RDO_INPUT_OPERATIONS = new Set<OutboxMutationRecord["operacao"]>([
  "CRIAR_RDO",
  "ATUALIZAR_RDO_RASCUNHO",
  "ENVIAR_RDO",
  "RESTAURAR_RDO",
]);

export type PdorRevenueInvalidationReason =
  | "RDO_PENDING_CANCELLATION"
  | "RDO_CANCELLED_AFTER_CONFIRMATION"
  | "CATALOG_MUTATION_PENDING"
  | "PRICE_MUTATION_PENDING"
  | "WORKSITE_LIFECYCLE_PENDING"
  | "RDO_MUTATION_PENDING";

export interface PdorRevenueLocalSnapshot {
  obraId: string;
  dataReferencia: string;
  statusExecucao: string;
  confirmedAt: string;
  explicitRdoIds?: readonly string[];
}

export function isSupportedPdorRevenueContract(value: {
  algorithmVersion?: unknown;
  versaoModelo?: unknown;
  versaoPremissas?: unknown;
}): boolean {
  return value.algorithmVersion === PDOR_REVENUE_ALGORITHM_VERSION &&
    value.versaoModelo === PDOR_REVENUE_MODEL_VERSION &&
    value.versaoPremissas === PDOR_REVENUE_ASSUMPTIONS_VERSION;
}

function normalizedText(value: unknown): string | null {
  return typeof value === "string" && value.trim()
    ? value.trim().toLowerCase()
    : null;
}

function mutationWorksiteId(
  mutation: OutboxMutationRecord,
): string | null {
  if (mutation.schemaVersion === 13) {
    return normalizedText(mutation.obraId);
  }
  return normalizedText(mutation.payload.obraId) ??
    (mutation.entidadeTipo === "OBRA"
      ? normalizedText(mutation.entidadeId)
      : null);
}

function mutationCanInvalidateSnapshot(
  mutation: OutboxMutationRecord,
  confirmedAt: string,
): boolean {
  if (PENDING_STATUSES.has(mutation.status)) return true;
  if (mutation.status !== "SYNCED") return false;
  const confirmedAtMs = Date.parse(confirmedAt);
  const createdAtMs = Date.parse(mutation.criadaNoClienteEm);
  const updatedAtMs = Date.parse(mutation.updatedAt);
  if (
    !Number.isFinite(confirmedAtMs) ||
    !Number.isFinite(createdAtMs) ||
    !Number.isFinite(updatedAtMs)
  ) {
    return true;
  }
  return Math.max(createdAtMs, updatedAtMs) >= confirmedAtMs;
}

function mutationForWorksite(
  mutation: OutboxMutationRecord,
  worksiteId: string,
  confirmedAt: string,
): boolean {
  return mutationCanInvalidateSnapshot(mutation, confirmedAt) &&
    mutationWorksiteId(mutation) === worksiteId;
}

export function findPdorRevenueLocalInvalidation(
  snapshot: PdorRevenueLocalSnapshot,
  rdos: readonly LocalRdoRecord[],
  mutations: readonly OutboxMutationRecord[],
): PdorRevenueInvalidationReason | null {
  if (snapshot.statusExecucao !== "SUCCESS") return null;

  const worksiteId = snapshot.obraId.trim().toLowerCase();
  const relevantForThisWorksite = mutations.filter((mutation) =>
    mutationForWorksite(mutation, worksiteId, snapshot.confirmedAt)
  );
  if (mutations.some((mutation) =>
    mutationCanInvalidateSnapshot(mutation, snapshot.confirmedAt) &&
    CATALOG_OPERATIONS.has(mutation.operacao)
  )) {
    return "CATALOG_MUTATION_PENDING";
  }
  if (relevantForThisWorksite.some((mutation) =>
    PRICE_OPERATIONS.has(mutation.operacao)
  )) {
    return "PRICE_MUTATION_PENDING";
  }
  if (relevantForThisWorksite.some((mutation) =>
    WORKSITE_LIFECYCLE_OPERATIONS.has(mutation.operacao)
  )) {
    return "WORKSITE_LIFECYCLE_PENDING";
  }
  if (relevantForThisWorksite.some((mutation) => {
    if (
      mutation.entidadeTipo !== "RDO" ||
      !RDO_INPUT_OPERATIONS.has(mutation.operacao)
    ) {
      return false;
    }
    const dataRdo = normalizedText(mutation.payload.dataRdo);
    // Sem data civil confiável, não é seguro afirmar que o RDO ficou fora.
    return dataRdo === null || dataRdo <= snapshot.dataReferencia;
  })) {
    return "RDO_MUTATION_PENDING";
  }

  const explicitRdoIds = new Set(
    (snapshot.explicitRdoIds ?? [])
      .map((id) => id.trim().toLowerCase())
      .filter(Boolean),
  );
  const eligibleRdos = rdos.filter((rdo) => {
    if (rdo.obraId.trim().toLowerCase() !== worksiteId) return false;
    const id = rdo.id.trim().toLowerCase();
    return explicitRdoIds.has(id) || rdo.dataRdo <= snapshot.dataReferencia;
  });
  const eligibleRdoIds = new Set(
    eligibleRdos.map((rdo) => rdo.id.trim().toLowerCase()),
  );
  const relevantRdoCancellation = mutations.find((mutation) => {
    if (
      mutation.entidadeTipo !== "RDO" ||
      mutation.operacao !== "CANCELAR_RDO" ||
      !mutationCanInvalidateSnapshot(mutation, snapshot.confirmedAt)
    ) {
      return false;
    }
    const entityId = mutation.entidadeId.trim().toLowerCase();
    if (eligibleRdoIds.has(entityId)) return true;
    if (mutationWorksiteId(mutation) !== worksiteId) return false;
    const dataRdo = normalizedText(mutation.payload.dataRdo);
    // Sem a data do RDO não há prova de que a mutação ficou fora da janela.
    return dataRdo === null || dataRdo <= snapshot.dataReferencia;
  });
  if (relevantRdoCancellation) {
    return PENDING_STATUSES.has(relevantRdoCancellation.status)
      ? "RDO_PENDING_CANCELLATION"
      : "RDO_CANCELLED_AFTER_CONFIRMATION";
  }

  const confirmedAtMs = Date.parse(snapshot.confirmedAt);
  for (const rdo of eligibleRdos) {
    if (!rdo.canceladoEm) continue;
    const cancelledAtMs = Date.parse(rdo.canceladoEm);
    if (
      !Number.isFinite(confirmedAtMs) ||
      !Number.isFinite(cancelledAtMs) ||
      cancelledAtMs >= confirmedAtMs
    ) {
      return "RDO_CANCELLED_AFTER_CONFIRMATION";
    }
  }
  return null;
}

export function pdorRevenueInvalidationMessage(
  reason: PdorRevenueInvalidationReason,
): string {
  switch (reason) {
    case "RDO_PENDING_CANCELLATION":
      return "A previsão foi ocultada porque um RDO elegível possui cancelamento local pendente.";
    case "RDO_CANCELLED_AFTER_CONFIRMATION":
      return "A previsão foi ocultada porque um RDO elegível foi cancelado depois da confirmação.";
    case "CATALOG_MUTATION_PENDING":
      return "A previsão foi ocultada porque há alteração de catálogo posterior à confirmação ou ainda pendente.";
    case "PRICE_MUTATION_PENDING":
      return "A previsão foi ocultada porque há alteração de preço posterior à confirmação ou ainda pendente nesta obra.";
    case "WORKSITE_LIFECYCLE_PENDING":
      return "A previsão foi ocultada porque o arquivamento ou a restauração da obra ainda não foi confirmado por este snapshot.";
    case "RDO_MUTATION_PENDING":
      return "A previsão foi ocultada porque há mutação de RDO posterior à confirmação ou ainda pendente na janela do snapshot.";
  }
}
