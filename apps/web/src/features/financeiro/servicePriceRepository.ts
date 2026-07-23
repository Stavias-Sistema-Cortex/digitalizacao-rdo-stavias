import { getSession } from "../auth/authSession";
import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  FinanceCapabilitiesCacheRecord,
  LocalSyncStatus,
  OutboxMutationRecord,
  ServiceCatalogLocalRecord,
  ServicePriceVersionLocalRecord,
} from "../../lib/db/db.types";
import {
  getSyncState,
  updateSyncState,
} from "../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../lib/sync/localMutationCoordinator";
import type {
  FinancialPermission,
  FinanceCapabilities,
} from "./financeiro.types";
import type {
  ServiceCatalogPage,
  ServiceCatalogRow,
} from "./servicePriceApi";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const FINANCIAL_PERMISSIONS = new Set<FinancialPermission>([
  "FINANCEIRO_VISUALIZAR",
  "FINANCEIRO_OPERAR",
  "FINANCEIRO_APROVAR",
  "FINANCEIRO_ADMINISTRAR",
]);

export interface LocalServiceCatalogRow extends ServiceCatalogRow {
  service: ServiceCatalogLocalRecord;
  priceVersions: ServicePriceVersionLocalRecord[];
}

export interface CreateLocalServiceInput {
  code: string;
  name: string;
  description?: string | null;
}

export interface CreateLocalPriceInput {
  unit: string;
  currency: string;
  unitPrice: string;
  validFrom: string;
  validTo?: string | null;
  source: string;
}

export interface SupersedeLocalPriceInput {
  unitPrice: string;
  validFrom: string;
  validTo?: string | null;
  source: string;
}

export interface CancelLocalPriceInput {
  effectiveAt: string;
  reason: string;
}

export interface QueuedCatalogMutation {
  entityId: string;
  clientMutationId: string;
}

function nowUtc(): string {
  return new Date().toISOString();
}

function requiredUuid(value: string, field: string): string {
  const normalized = value.trim().toLowerCase();
  if (!UUID_PATTERN.test(normalized)) {
    throw new Error(`${field} deve ser um UUID canônico.`);
  }
  return normalized;
}

function requiredText(value: string, field: string, maxLength: number): string {
  const normalized = value.trim();
  if (!normalized || normalized.length > maxLength) {
    throw new Error(`${field} é obrigatório e deve ter até ${maxLength} caracteres.`);
  }
  return normalized;
}

function optionalText(
  value: string | null | undefined,
  maxLength: number,
): string | null {
  const normalized = value?.trim() ?? "";
  if (!normalized) return null;
  if (normalized.length > maxLength) {
    throw new Error(`O texto deve ter até ${maxLength} caracteres.`);
  }
  return normalized;
}

function decimalText(value: string): string {
  const normalized = value.trim().replace(",", ".");
  if (!/^\d{1,14}(?:\.\d{1,4})?$/.test(normalized)) {
    throw new Error("Informe um valor unitário válido com até quatro casas decimais.");
  }
  return normalized;
}

function dateText(value: string, field: string): string {
  const normalized = value.trim();
  if (!/^\d{4}-\d{2}-\d{2}$/.test(normalized)) {
    throw new Error(`${field} deve ser uma data válida.`);
  }
  const parsed = new Date(`${normalized}T00:00:00Z`);
  if (Number.isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== normalized) {
    throw new Error(`${field} deve ser uma data válida.`);
  }
  return normalized;
}

async function localMutationIdentity(obraId: string): Promise<{
  obraId: string;
  userId: string;
  deviceId: string;
}> {
  const worksiteId = requiredUuid(obraId, "obraId");
  const session = getSession();
  if (!session) {
    throw new Error("Sessão válida obrigatória para alterar o catálogo.");
  }
  if (!session.escopoGlobal && !session.obraIds.includes(worksiteId)) {
    throw new Error("A obra não pertence ao escopo da sessão.");
  }
  const state = await getSyncState();
  const deviceId = state.usuarioId === session.colaboradorId &&
      state.deviceId && UUID_PATTERN.test(state.deviceId)
    ? state.deviceId
    : crypto.randomUUID();
  if (state.deviceId !== deviceId || state.usuarioId !== session.colaboradorId) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }
  return { obraId: worksiteId, userId: session.colaboradorId, deviceId };
}

function mutationSyncStatus(
  mutation: OutboxMutationRecord | undefined,
): { syncStatus: LocalSyncStatus; lastError: string | null } {
  if (!mutation || mutation.status === "SYNCED") {
    return { syncStatus: "SYNCED", lastError: null };
  }
  if (mutation.status === "SYNCING") {
    return { syncStatus: "SYNCING", lastError: null };
  }
  if (mutation.status === "CONFLICT") {
    return { syncStatus: "CONFLICT", lastError: mutation.ultimoErro };
  }
  if (mutation.status === "ERROR" || mutation.status === "REJECTED") {
    return { syncStatus: "ERROR", lastError: mutation.ultimoErro };
  }
  return { syncStatus: "PENDING_SYNC", lastError: mutation.ultimoErro };
}

function latestMutationByEntity(
  mutations: OutboxMutationRecord[],
): Map<string, OutboxMutationRecord> {
  const latest = new Map<string, OutboxMutationRecord>();
  for (const mutation of mutations) {
    if (!["SERVICE", "SERVICE_PRICE_VERSION"].includes(mutation.entidadeTipo)) {
      continue;
    }
    const existing = latest.get(mutation.entidadeId);
    if (!existing || existing.updatedAt <= mutation.updatedAt) {
      latest.set(mutation.entidadeId, mutation);
    }
  }
  return latest;
}

export async function listLocalServiceCatalog(
  obraId: string,
  query = "",
): Promise<LocalServiceCatalogRow[]> {
  const worksiteId = requiredUuid(obraId, "obraId");
  const database = await getCortexDb();
  const [services, prices, mutations] = await Promise.all([
    database.getAll("service_catalog"),
    database.getAllFromIndex("service_price_versions", "by-worksite", worksiteId),
    database.getAll("outbox_mutations"),
  ]);
  const mutationByEntity = latestMutationByEntity(mutations);
  const normalizedQuery = query.trim().toLocaleLowerCase("pt-BR");
  const derivedServices = services.map((service) => ({
    ...service,
    ...mutationSyncStatus(mutationByEntity.get(service.id)),
  }));
  const derivedPrices = prices.map((price) => ({
    ...price,
    ...mutationSyncStatus(mutationByEntity.get(price.id)),
  }));
  const pendingSuccessors = new Set(
    derivedPrices
      .filter((price) => price.syncStatus !== "SYNCED" && price.supersedesId)
      .map((price) => price.supersedesId as string),
  );

  return derivedServices
    .filter((service) => !normalizedQuery || [
      service.code,
      service.name,
      service.description ?? "",
    ].some((value) => value.toLocaleLowerCase("pt-BR").includes(normalizedQuery)))
    .sort((left, right) => left.code.localeCompare(right.code, "pt-BR"))
    .map((service) => ({
      service,
      priceVersions: derivedPrices
        .filter((price) => price.serviceId === service.id)
        .map((price) => pendingSuccessors.has(price.id)
          ? { ...price, status: "SUPERSEDED" }
          : price)
        .sort((left, right) => right.version - left.version ||
          right.createdAt.localeCompare(left.createdAt)),
    }));
}

export async function hydrateServiceCatalog(
  obraId: string,
  remote: ServiceCatalogPage,
  options: { replaceCompleteSnapshot?: boolean } = {},
): Promise<void> {
  const worksiteId = requiredUuid(obraId, "obraId");
  const database = await getCortexDb();
  const transaction = database.transaction(
    ["service_catalog", "service_price_versions", "outbox_mutations"],
    "readwrite",
  );
  const outbox = await transaction.objectStore("outbox_mutations").getAll();
  const pendingIds = new Set(
    outbox
      .filter((mutation) =>
        ["SERVICE", "SERVICE_PRICE_VERSION"].includes(mutation.entidadeTipo) &&
        !["SYNCED", "REJECTED"].includes(mutation.status))
      .map((mutation) => mutation.entidadeId),
  );
  const catalogStore = transaction.objectStore("service_catalog");
  const priceStore = transaction.objectStore("service_price_versions");
  const timestamp = nowUtc();
  const remoteServiceIds = new Set<string>();
  const remotePriceIds = new Set<string>();

  for (const row of remote.items) {
    remoteServiceIds.add(row.service.id);
    if (!pendingIds.has(row.service.id)) {
      await catalogStore.put({
        ...row.service,
        syncStatus: "SYNCED",
        updatedAt: timestamp,
        lastError: null,
      });
    }
    for (const price of row.priceVersions) {
      if (price.obraId !== worksiteId) continue;
      remotePriceIds.add(price.id);
      if (!pendingIds.has(price.id)) {
        await priceStore.put({
          ...price,
          unitPrice: String(price.unitPrice),
          source: price.source ?? null,
          entityVersion: price.entityVersion ?? 0,
          syncStatus: "SYNCED",
          updatedAt: timestamp,
          lastError: null,
        });
      }
    }
  }

  if (options.replaceCompleteSnapshot && remote.coverage === "COMPLETE") {
    for (const price of await priceStore.index("by-worksite").getAll(worksiteId)) {
      if (!pendingIds.has(price.id) && !remotePriceIds.has(price.id)) {
        await priceStore.delete(price.id);
      }
    }
    for (const service of await catalogStore.getAll()) {
      if (!pendingIds.has(service.id) && !remoteServiceIds.has(service.id)) {
        const pricesForService = await priceStore
          .index("by-worksite-service")
          .getAll([worksiteId, service.id]);
        if (pricesForService.length === 0) {
          await catalogStore.delete(service.id);
        }
      }
    }
  }
  await transaction.done;
}

export async function queueCreateService(
  obraId: string,
  input: CreateLocalServiceInput,
): Promise<QueuedCatalogMutation> {
  const identity = await localMutationIdentity(obraId);
  const entityId = crypto.randomUUID();
  const occurredAt = nowUtc();
  const payload = {
    id: entityId,
    obraId: identity.obraId,
    code: requiredText(input.code, "Código", 80).toUpperCase(),
    name: requiredText(input.name, "Nome", 160),
    description: optionalText(input.description, 500),
  };
  const local: ServiceCatalogLocalRecord = {
    id: entityId,
    code: payload.code,
    name: payload.name,
    description: payload.description,
    status: "ACTIVE",
    syncStatus: "PENDING_SYNC",
    createdAt: occurredAt,
    updatedAt: occurredAt,
    lastError: null,
  };
  const committed = await commitLocalMutation({
    ...identity,
    entityType: "SERVICE",
    entityId,
    entityName: local.name,
    operation: "CREATE",
    transportOperation: "CRIAR_SERVICO_CATALOGO",
    baseVersion: null,
    occurredAt,
    previousSnapshot: {},
    nextSnapshot: payload,
    principalSnapshot: { ...local },
    eventType: "SERVICE_CREATED",
    write: () => [{
      store: "service_catalog",
      value: local,
      principal: true,
      insertOnly: true,
    }],
  });
  return { entityId, clientMutationId: committed.mutation.clientMutationId };
}

async function pendingCreateDependency(entityId: string): Promise<string[]> {
  const database = await getCortexDb();
  const mutations = await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    entityId,
  );
  const pending = mutations.find((mutation) =>
    mutation.status !== "SYNCED" && mutation.status !== "REJECTED" &&
    mutation.operacao === "CRIAR_SERVICO_CATALOGO");
  return pending ? [pending.clientMutationId] : [];
}

export async function queueCreatePrice(
  obraId: string,
  serviceId: string,
  input: CreateLocalPriceInput,
): Promise<QueuedCatalogMutation> {
  const identity = await localMutationIdentity(obraId);
  const normalizedServiceId = requiredUuid(serviceId, "serviceId");
  const database = await getCortexDb();
  const service = await database.get("service_catalog", normalizedServiceId);
  if (!service) throw new Error("O serviço não está disponível no catálogo local.");
  const entityId = crypto.randomUUID();
  const occurredAt = nowUtc();
  const validFrom = dateText(input.validFrom, "Início da vigência");
  const validTo = input.validTo ? dateText(input.validTo, "Fim da vigência") : null;
  if (validTo && validTo < validFrom) {
    throw new Error("O fim da vigência não pode ser anterior ao início.");
  }
  const payload = {
    id: entityId,
    obraId: identity.obraId,
    serviceId: normalizedServiceId,
    unit: requiredText(input.unit, "Unidade", 30).toUpperCase(),
    currency: requiredText(input.currency, "Moeda", 3).toUpperCase(),
    unitPrice: decimalText(input.unitPrice),
    validFrom,
    validTo,
    source: requiredText(input.source, "Fonte", 80).toUpperCase(),
  };
  const existing = await database.getAllFromIndex(
    "service_price_versions",
    "by-worksite-service",
    [identity.obraId, normalizedServiceId],
  );
  const local: ServicePriceVersionLocalRecord = {
    ...payload,
    version: Math.max(0, ...existing.map((price) => price.version)) + 1,
    source: payload.source,
    supersedesId: null,
    status: "ACTIVE",
    effectiveValidTo: validTo,
    entityVersion: 0,
    syncStatus: "PENDING_SYNC",
    createdAt: occurredAt,
    updatedAt: occurredAt,
    lastError: null,
  };
  const committed = await commitLocalMutation({
    ...identity,
    entityType: "SERVICE_PRICE_VERSION",
    entityId,
    entityName: `${service.code} · ${payload.unit}`,
    operation: "CREATE",
    transportOperation: "CRIAR_PRECO_SERVICO",
    baseVersion: null,
    occurredAt,
    previousSnapshot: {},
    nextSnapshot: payload,
    principalSnapshot: { ...local },
    eventType: "SERVICE_PRICE_VERSION_PUBLISHED",
    relatedEntities: [{ tipo: "SERVICE", id: normalizedServiceId, nome: service.name }],
    dependsOnMutationIds: await pendingCreateDependency(normalizedServiceId),
    write: () => [{
      store: "service_price_versions",
      value: local,
      principal: true,
      insertOnly: true,
    }],
  });
  return { entityId, clientMutationId: committed.mutation.clientMutationId };
}

export async function queueSupersedePrice(
  obraId: string,
  previousPriceId: string,
  input: SupersedeLocalPriceInput,
): Promise<QueuedCatalogMutation> {
  const identity = await localMutationIdentity(obraId);
  const previousId = requiredUuid(previousPriceId, "previousPriceId");
  const database = await getCortexDb();
  const previous = await database.get("service_price_versions", previousId);
  if (!previous || previous.obraId !== identity.obraId) {
    throw new Error("A versão de preço não pertence a esta obra.");
  }
  if (previous.syncStatus !== "SYNCED" || previous.status !== "ACTIVE") {
    throw new Error("Sincronize a versão ativa antes de substituí-la.");
  }
  const entityId = crypto.randomUUID();
  const occurredAt = nowUtc();
  const validFrom = dateText(input.validFrom, "Início da vigência");
  const validTo = input.validTo ? dateText(input.validTo, "Fim da vigência") : null;
  if (validFrom <= previous.validFrom || (validTo && validTo < validFrom)) {
    throw new Error("A nova vigência deve começar depois da versão anterior.");
  }
  const payload = {
    id: entityId,
    obraId: identity.obraId,
    previousPriceId: previous.id,
    unitPrice: decimalText(input.unitPrice),
    validFrom,
    validTo,
    source: requiredText(input.source, "Fonte", 80).toUpperCase(),
  };
  const local: ServicePriceVersionLocalRecord = {
    id: entityId,
    obraId: identity.obraId,
    serviceId: previous.serviceId,
    unit: previous.unit,
    currency: previous.currency,
    version: previous.version + 1,
    unitPrice: payload.unitPrice,
    validFrom,
    validTo,
    source: payload.source,
    supersedesId: previous.id,
    status: "ACTIVE",
    effectiveValidTo: validTo,
    entityVersion: 0,
    syncStatus: "PENDING_SYNC",
    createdAt: occurredAt,
    updatedAt: occurredAt,
    lastError: null,
  };
  const committed = await commitLocalMutation({
    ...identity,
    entityType: "SERVICE_PRICE_VERSION",
    entityId,
    entityName: `Versão ${local.version}`,
    operation: "CREATE",
    transportOperation: "SUBSTITUIR_PRECO_SERVICO",
    baseVersion: null,
    occurredAt,
    previousSnapshot: {},
    nextSnapshot: payload,
    principalSnapshot: { ...local },
    eventType: "SERVICE_PRICE_VERSION_SUPERSEDED",
    relatedEntities: [{
      tipo: "SERVICE_PRICE_VERSION",
      id: previous.id,
      nome: `Versão ${previous.version}`,
    }],
    write: () => [{
      store: "service_price_versions",
      value: local,
      principal: true,
      insertOnly: true,
    }],
  });
  return { entityId, clientMutationId: committed.mutation.clientMutationId };
}

export async function queueCancelPrice(
  obraId: string,
  priceId: string,
  input: CancelLocalPriceInput,
): Promise<QueuedCatalogMutation> {
  const identity = await localMutationIdentity(obraId);
  const entityId = requiredUuid(priceId, "priceId");
  const database = await getCortexDb();
  const previous = await database.get("service_price_versions", entityId);
  if (!previous || previous.obraId !== identity.obraId) {
    throw new Error("A versão de preço não pertence a esta obra.");
  }
  if (previous.syncStatus !== "SYNCED" || previous.entityVersion < 1 ||
      previous.status !== "ACTIVE") {
    throw new Error("Sincronize a versão ativa antes de cancelá-la.");
  }
  const occurredAt = nowUtc();
  const payload = {
    id: entityId,
    obraId: identity.obraId,
    effectiveAt: dateText(input.effectiveAt, "Data do cancelamento"),
    reason: requiredText(input.reason, "Motivo", 500),
  };
  const local: ServicePriceVersionLocalRecord = {
    ...previous,
    status: "CANCELLED",
    effectiveValidTo: payload.effectiveAt,
    syncStatus: "PENDING_SYNC",
    updatedAt: occurredAt,
    lastError: null,
  };
  const committed = await commitLocalMutation({
    ...identity,
    entityType: "SERVICE_PRICE_VERSION",
    entityId,
    entityName: `Versão ${previous.version}`,
    operation: "TRANSITION",
    transportOperation: "CANCELAR_PRECO_SERVICO",
    baseVersion: previous.entityVersion,
    occurredAt,
    previousSnapshot: { id: entityId, obraId: identity.obraId },
    nextSnapshot: payload,
    principalSnapshot: { ...local },
    eventType: "SERVICE_PRICE_VERSION_CANCELLED",
    write: () => [{
      store: "service_price_versions",
      value: local,
      principal: true,
    }],
  });
  return { entityId, clientMutationId: committed.mutation.clientMutationId };
}

export async function cacheFinanceCapabilities(
  capabilities: FinanceCapabilities,
): Promise<void> {
  const session = getSession();
  if (!session || !capabilities.obraId) return;
  const obraId = requiredUuid(capabilities.obraId, "obraId");
  if (!session.escopoGlobal && !session.obraIds.includes(obraId)) return;
  const permissions = [...new Set(capabilities.permissoes)]
    .filter((permission): permission is FinancialPermission =>
      FINANCIAL_PERMISSIONS.has(permission))
    .sort();
  const record: FinanceCapabilitiesCacheRecord = {
    key: [session.colaboradorId, obraId],
    ownerId: session.colaboradorId,
    obraId,
    permissions,
    cachedAt: nowUtc(),
    sessionExpiresAt: session.expiraEm,
  };
  const database = await getCortexDb();
  await database.put("finance_capabilities", record);
}

export async function getCachedFinanceCapabilities(
  obraId: string,
): Promise<FinanceCapabilities | null> {
  const session = getSession();
  if (!session) return null;
  const worksiteId = requiredUuid(obraId, "obraId");
  if (!session.escopoGlobal && !session.obraIds.includes(worksiteId)) return null;
  const database = await getCortexDb();
  const cached = await database.get(
    "finance_capabilities",
    [session.colaboradorId, worksiteId],
  );
  if (!cached || cached.ownerId !== session.colaboradorId ||
      cached.sessionExpiresAt !== session.expiraEm ||
      Date.parse(cached.sessionExpiresAt) <= Date.now()) {
    return null;
  }
  const permissions = cached.permissions.filter(
    (permission): permission is FinancialPermission =>
      FINANCIAL_PERMISSIONS.has(permission as FinancialPermission),
  );
  return { obraId: worksiteId, permissoes: permissions };
}
