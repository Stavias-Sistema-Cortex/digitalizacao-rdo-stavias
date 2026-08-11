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
import { decimalDigitado } from "../../lib/numeros/numeroDigitado";
import type {
  FinancialPermission,
  FinanceCapabilities,
} from "./financeiro.types";
import type {
  ServiceCatalogPage,
  ServiceCatalogRow,
} from "./servicePriceApi";
import { normalizarUnidade } from "./servicoCatalogoEntrada";

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

export interface UpdateLocalServiceInput {
  code: string;
  name: string;
  description?: string | null;
}

export interface UpdateLocalPriceInput {
  unit: string;
  unitPrice: string;
  contractedQuantity: string;
  validFrom: string;
  validTo?: string | null;
  source: string;
}

export interface CreateLocalPriceInput {
  unit: string;
  currency: string;
  unitPrice: string;
  contractedQuantity: string;
  validFrom: string;
  validTo?: string | null;
  source: string;
}

export interface SupersedeLocalPriceInput {
  unitPrice: string;
  contractedQuantity: string;
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

/*
 * O preço é digitado por gente no Brasil, e o servidor só entende ponto.
 *
 * A tradução mora em `decimalDigitado` porque a versão que existia aqui —
 * `replace(",", ".")` — recusava "1.234,56", que está certo, e aceitava
 * "1.234" como 1,234: mil vezes menos, sem aviso, gravado como valor unitário
 * e propagado dali até a receita medida.
 */
function decimalText(value: string): string {
  const normalized = decimalDigitado(value) ?? "";
  if (!/^\d{1,14}(?:\.\d{1,4})?$/.test(normalized)) {
    throw new Error("Informe um valor unitário válido com até quatro casas decimais.");
  }
  return normalized;
}

function contractedQuantityText(value: string): string {
  const normalized = decimalDigitado(value) ?? "";
  if (
    !/^\d{1,15}(?:\.\d{1,3})?$/.test(normalized) ||
    /^0+(?:\.0+)?$/.test(normalized)
  ) {
    throw new Error(
      "Informe uma quantidade contratada positiva com até três casas decimais.",
    );
  }
  const [integer, fraction = ""] = normalized.split(".");
  return `${integer}.${fraction.padEnd(3, "0")}`;
}

function brlCurrency(value: string): "BRL" {
  const normalized = requiredText(value, "Moeda", 3).toUpperCase();
  if (normalized !== "BRL") {
    throw new Error("A moeda do catálogo de receita deve ser BRL.");
  }
  return "BRL";
}

function sourceText(value: string): string {
  const normalized = requiredText(value, "Fonte", 80).toUpperCase();
  if (!/^[A-Z0-9][A-Z0-9._:-]{0,79}$/.test(normalized)) {
    throw new Error(
      "Fonte inválida. Use letras, números e apenas . _ : -.",
    );
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
          contractedQuantity: price.contractedQuantity === null
            ? null
            : String(price.contractedQuantity),
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

/**
 * Corrige o que foi escrito no cadastro do serviço.
 *
 * <p>Um serviço cadastrado com o nome trocado não tinha conserto: sobrava
 * excluir e cadastrar de novo, o que troca o identificador que os RDOs, os
 * preços e as medições já citam. Aqui o identificador fica e o texto muda.
 *
 * <p>Corrigir não é versionar. O catálogo versiona preço, porque a fronteira
 * entre um valor e o seguinte é um fato do contrato. O nome do serviço não é:
 * escrever errado nunca foi um estado anterior verdadeiro.
 */
export async function queueUpdateService(
  obraId: string,
  serviceId: string,
  input: UpdateLocalServiceInput,
): Promise<QueuedCatalogMutation> {
  const identity = await localMutationIdentity(obraId);
  const entityId = requiredUuid(serviceId, "serviceId");
  const database = await getCortexDb();
  const atual = await database.get("service_catalog", entityId);
  if (!atual) {
    throw new Error("Serviço não encontrado neste dispositivo.");
  }
  const payload = {
    id: entityId,
    obraId: identity.obraId,
    code: requiredText(input.code, "Código", 80).toUpperCase(),
    name: requiredText(input.name, "Nome", 160),
    description: optionalText(input.description, 500),
  };
  if (
    payload.code === atual.code &&
    payload.name === atual.name &&
    payload.description === (atual.description ?? null)
  ) {
    return { entityId, clientMutationId: "" };
  }

  const occurredAt = nowUtc();
  const local: ServiceCatalogLocalRecord = {
    ...atual,
    code: payload.code,
    name: payload.name,
    description: payload.description,
    syncStatus: "PENDING_SYNC",
    updatedAt: occurredAt,
    lastError: null,
  };
  const committed = await commitLocalMutation({
    ...identity,
    entityType: "SERVICE",
    entityId,
    entityName: payload.name,
    operation: "UPDATE",
    transportOperation: "ATUALIZAR_SERVICO_CATALOGO",
    /*
     * O serviço do catálogo não tem versão de linha, e o servidor não pede uma
     * para esta entidade: a repetição é resolvida pelo recibo da mutação. O
     * envelope exige um número em toda atualização, então vai zero — inerte do
     * outro lado, e explícito aqui para ninguém o ler como "estava na versão
     * zero".
     */
    baseVersion: 0,
    occurredAt,
    previousSnapshot: {
      id: entityId,
      obraId: identity.obraId,
      code: atual.code,
      name: atual.name,
      description: atual.description ?? null,
    },
    nextSnapshot: payload,
    principalSnapshot: { ...local },
    eventType: "SERVICE_UPDATED",
    // O serviço criado offline e ainda não aceito sobe antes: corrigir o que o
    // servidor não conhece não tem o que encontrar do outro lado.
    dependsOnMutationIds: await pendingCreateDependency(entityId),
    write: () => [{ store: "service_catalog", value: local, principal: true }],
  });
  return { entityId, clientMutationId: committed.mutation.clientMutationId };
}

/**
 * Tira o serviço de circulação, ou o traz de volta.
 *
 * <p>Uma função só para os dois sentidos: é a mesma transição, e separá-la
 * duplicaria o snapshot, a dependência da criação pendente e a projeção local
 * — três lugares para divergir do servidor, que também trata os dois como um
 * movimento só.
 *
 * <p>O que o serviço já produziu não é tocado: as versões de preço ficam no
 * lugar, e o RDO que o executou continua sabendo o que foi feito e por qual
 * preço. O que muda é que ele deixa de ser oferecido para lançamento novo.
 */
async function enfileirarTransicaoDeExclusao(
  obraId: string,
  serviceId: string,
  excluir: boolean,
  motivo?: string,
): Promise<QueuedCatalogMutation> {
  const identity = await localMutationIdentity(obraId);
  const database = await getCortexDb();
  const atual = await database.get("service_catalog", serviceId);
  if (!atual) {
    throw new Error("Serviço não encontrado neste dispositivo.");
  }
  const proximoStatus = excluir ? "EXCLUIDO" : "ACTIVE";
  if (atual.status === proximoStatus) {
    return { entityId: serviceId, clientMutationId: "" };
  }

  const occurredAt = nowUtc();
  const local: ServiceCatalogLocalRecord = {
    ...atual,
    status: proximoStatus,
    syncStatus: "PENDING_SYNC",
    updatedAt: occurredAt,
    lastError: null,
  };
  const committed = await commitLocalMutation({
    ...identity,
    entityType: "SERVICE",
    entityId: serviceId,
    entityName: atual.name,
    operation: "TRANSITION",
    transportOperation: excluir
      ? "EXCLUIR_SERVICO_CATALOGO"
      : "RESTAURAR_SERVICO_CATALOGO",
    /*
     * O serviço do catálogo não tem versão de linha, e o servidor não pede uma
     * para esta entidade: a repetição aqui é resolvida pelo recibo da mutação,
     * não por comparação de versões. O envelope exige um número em toda
     * transição, então vai zero — inerte do outro lado, e explícito aqui para
     * ninguém o confundir com "estava na versão zero".
     */
    baseVersion: 0,
    occurredAt,
    previousSnapshot: { id: serviceId, obraId: identity.obraId, status: atual.status },
    nextSnapshot: {
      id: serviceId,
      obraId: identity.obraId,
      status: proximoStatus,
      motivo: motivo?.trim() || null,
    },
    principalSnapshot: { ...local },
    eventType: excluir ? "SERVICE_EXCLUDED" : "SERVICE_RESTORED",
    // O serviço criado offline e ainda não aceito precisa subir antes: excluir
    // o que o servidor não conhece não tem o que encontrar do outro lado.
    dependsOnMutationIds: await pendingCreateDependency(serviceId),
    write: () => [{ store: "service_catalog", value: local, principal: true }],
  });
  return { entityId: serviceId, clientMutationId: committed.mutation.clientMutationId };
}

/**
 * O serviço que o servidor nunca conheceu sai do aparelho de vez.
 *
 * <p>A lixeira enfileirava sempre a mesma transição de exclusão, e para o
 * serviço cuja criação foi recusada isso é um beco: o servidor não tem o que
 * excluir, a transição volta recusada, e o serviço fica marcado como pendente —
 * o que faz a reconciliação preservá-lo justamente por estar pendente. Cada
 * clique na lixeira aprofundava o buraco em vez de sair dele.
 *
 * <p>Quando a criação nunca foi aceita, não há nada do outro lado a que uma
 * exclusão se refira. O certo é apagar aqui: o registro, os preços que nasceram
 * com ele e as mutações mortas que os descrevem. Nada disso existiu para o
 * servidor, então nada disso precisa ser contado a ele.
 *
 * @returns `true` quando apagou de vez; `false` quando o serviço existe do lado
 *          do servidor e a exclusão precisa seguir o caminho normal.
 */
export async function apagarServicoNuncaAceito(
  serviceId: string,
): Promise<boolean> {
  const database = await getCortexDb();
  const mutacoesDoServico = await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    serviceId,
  );
  const criacaoAceita = mutacoesDoServico.some(
    (mutation) =>
      mutation.operacao === "CRIAR_SERVICO_CATALOGO" &&
      mutation.status === "SYNCED",
  );
  const criacaoLocal = mutacoesDoServico.some(
    (mutation) => mutation.operacao === "CRIAR_SERVICO_CATALOGO",
  );
  // Sem criação nenhuma no histórico local, o serviço veio do servidor: ele
  // existe lá e a exclusão é uma transição de verdade.
  if (criacaoAceita || !criacaoLocal) return false;

  const transaction = database.transaction(
    ["service_catalog", "service_price_versions", "outbox_mutations"],
    "readwrite",
  );
  const catalogo = transaction.objectStore("service_catalog");
  const precos = transaction.objectStore("service_price_versions");
  const fila = transaction.objectStore("outbox_mutations");

  // Varredura em vez de índice: o índice de preço é composto por obra e
  // serviço, e a obra do preço local pode não ser a que está aberta. A loja é
  // pequena e local, e apagar de menos aqui deixaria preço órfão.
  const precosDoServico = (await precos.getAll()).filter(
    (preco) => preco.serviceId === serviceId,
  );
  for (const preco of precosDoServico) {
    for (const mutation of await fila
      .index("by-entity-id")
      .getAll(preco.id)) {
      await fila.delete(mutation.clientMutationId);
    }
    await precos.delete(preco.id);
  }
  for (const mutation of await fila.index("by-entity-id").getAll(serviceId)) {
    await fila.delete(mutation.clientMutationId);
  }
  await catalogo.delete(serviceId);
  await transaction.done;
  return true;
}

export async function queueExcluirServico(
  obraId: string,
  serviceId: string,
  motivo?: string,
): Promise<QueuedCatalogMutation> {
  return enfileirarTransicaoDeExclusao(obraId, serviceId, true, motivo);
}

export async function queueRestaurarServico(
  obraId: string,
  serviceId: string,
): Promise<QueuedCatalogMutation> {
  return enfileirarTransicaoDeExclusao(obraId, serviceId, false);
}

/**
 * A criação ainda na fila da qual esta mutação depende.
 *
 * <p>Excluir, restaurar ou corrigir o que o servidor ainda não conhece não tem
 * o que encontrar do outro lado. A operação diz qual criação procurar: a do
 * serviço e a do preço são entidades diferentes, e passar a errada devolveria
 * lista vazia — a dependência sumiria em silêncio e a fila mandaria a correção
 * antes do registro existir.
 */
async function pendingCreateDependency(
  entityId: string,
  operation: "CRIAR_SERVICO_CATALOGO" | "CRIAR_PRECO_SERVICO" =
    "CRIAR_SERVICO_CATALOGO",
): Promise<string[]> {
  const database = await getCortexDb();
  const mutations = await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    entityId,
  );
  const pending = mutations.find((mutation) =>
    mutation.status !== "SYNCED" && mutation.status !== "REJECTED" &&
    mutation.operacao === operation);
  return pending ? [pending.clientMutationId] : [];
}

export async function queueCreatePrice(
  obraId: string,
  serviceId: string,
  input: CreateLocalPriceInput,
): Promise<QueuedCatalogMutation> {
  const normalizedServiceId = requiredUuid(serviceId, "serviceId");
  const unit = requiredText(input.unit, "Unidade", 30).toUpperCase();
  const currency = brlCurrency(input.currency);
  const unitPrice = decimalText(input.unitPrice);
  const contractedQuantity = contractedQuantityText(input.contractedQuantity);
  const validFrom = dateText(input.validFrom, "Início da vigência");
  const validTo = input.validTo ? dateText(input.validTo, "Fim da vigência") : null;
  if (validTo && validTo < validFrom) {
    throw new Error("O fim da vigência não pode ser anterior ao início.");
  }
  const source = sourceText(input.source);
  const identity = await localMutationIdentity(obraId);
  const database = await getCortexDb();
  const service = await database.get("service_catalog", normalizedServiceId);
  if (!service) throw new Error("O serviço não está disponível no catálogo local.");
  const entityId = crypto.randomUUID();
  const occurredAt = nowUtc();
  const payload = {
    id: entityId,
    obraId: identity.obraId,
    serviceId: normalizedServiceId,
    unit,
    currency,
    unitPrice,
    contractedQuantity,
    validFrom,
    validTo,
    source,
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

/**
 * Corrige um preço já registrado, no lugar, sem criar versão nova.
 *
 * <p>Substituir era o único caminho para trocar um valor, e ele grava no
 * histórico uma revisão contratual: a versão 1 valeu até tal dia, a versão 2
 * vale de lá em diante. Para um zero a mais digitado ontem, isso inventa um
 * aditivo que nunca existiu, e a receita passa a medir dois períodos com preços
 * diferentes por causa de um erro de digitação.
 *
 * <p>Quem decide se a correção ainda cabe é o servidor, dentro da mesma escrita:
 * preço já citado por uma execução, já substituído ou já cancelado é recusado.
 * Aqui só se recusa o que dá para saber sem rede — versão que este aparelho já
 * sabe encerrada.
 *
 * <p>A unidade entra na correção porque ela é o erro mais comum a consertar:
 * antes de o cadastro aceitar expoente, todo serviço medido em área ou volume
 * entrava como metro linear. Ela arrasta o número da versão, que é contado por
 * unidade, e quem renumera é o servidor. A moeda fica de fora: é BRL e só.
 */
export async function queueUpdatePrice(
  obraId: string,
  priceId: string,
  input: UpdateLocalPriceInput,
): Promise<QueuedCatalogMutation> {
  const identity = await localMutationIdentity(obraId);
  const entityId = requiredUuid(priceId, "priceId");
  // Normaliza aqui também, não só na tela: quem digita "m2" na correção quer
  // metro quadrado, e gravar "M2" seria trocar um símbolo errado por outro.
  const unit = normalizarUnidade(
    requiredText(input.unit, "Unidade", 30),
  ).toUpperCase();
  const unitPrice = decimalText(input.unitPrice);
  const contractedQuantity = input.contractedQuantity.trim()
    ? contractedQuantityText(input.contractedQuantity)
    : null;
  const validFrom = dateText(input.validFrom, "Início da vigência");
  const validTo = input.validTo ? dateText(input.validTo, "Fim da vigência") : null;
  if (validTo && validTo < validFrom) {
    throw new Error("O fim da vigência não pode ser anterior ao início.");
  }
  const source = sourceText(input.source);
  const database = await getCortexDb();
  const previous = await database.get("service_price_versions", entityId);
  if (!previous || previous.obraId !== identity.obraId) {
    throw new Error("A versão de preço não pertence a esta obra.");
  }
  if (previous.status !== "ACTIVE") {
    throw new Error(
      "Esta versão já foi substituída ou cancelada. Publique uma nova em vez de corrigi-la.",
    );
  }

  const occurredAt = nowUtc();
  const payload = {
    id: entityId,
    obraId: identity.obraId,
    unit,
    unitPrice,
    contractedQuantity,
    validFrom,
    validTo,
    source,
  };
  const local: ServicePriceVersionLocalRecord = {
    ...previous,
    unit,
    unitPrice,
    contractedQuantity,
    validFrom,
    validTo,
    source,
    effectiveValidTo: validTo,
    syncStatus: "PENDING_SYNC",
    updatedAt: occurredAt,
    lastError: null,
  };
  const committed = await commitLocalMutation({
    ...identity,
    entityType: "SERVICE_PRICE_VERSION",
    entityId,
    entityName: `Versão ${previous.version}`,
    operation: "UPDATE",
    transportOperation: "ATUALIZAR_PRECO_SERVICO",
    /*
     * A versão de linha vai como está: o servidor não a exige nesta operação —
     * a repetição é resolvida pelo recibo da mutação —, e um preço criado
     * offline ainda está em zero. Exigir um número maior aqui travaria a
     * correção justamente de quem acabou de digitar errado.
     */
    baseVersion: previous.entityVersion,
    occurredAt,
    previousSnapshot: {
      id: entityId,
      obraId: identity.obraId,
      unit: previous.unit,
      unitPrice: previous.unitPrice,
      contractedQuantity: previous.contractedQuantity,
      validFrom: previous.validFrom,
      validTo: previous.validTo,
      source: previous.source,
    },
    nextSnapshot: payload,
    principalSnapshot: { ...local },
    eventType: "SERVICE_PRICE_VERSION_UPDATED",
    dependsOnMutationIds: await pendingCreateDependency(
      entityId,
      "CRIAR_PRECO_SERVICO",
    ),
    write: () => [{
      store: "service_price_versions",
      value: local,
      principal: true,
    }],
  });
  return { entityId, clientMutationId: committed.mutation.clientMutationId };
}

export async function queueSupersedePrice(
  obraId: string,
  previousPriceId: string,
  input: SupersedeLocalPriceInput,
): Promise<QueuedCatalogMutation> {
  const previousId = requiredUuid(previousPriceId, "previousPriceId");
  const unitPrice = decimalText(input.unitPrice);
  const contractedQuantity = contractedQuantityText(input.contractedQuantity);
  const validFrom = dateText(input.validFrom, "Início da vigência");
  const validTo = input.validTo ? dateText(input.validTo, "Fim da vigência") : null;
  const source = sourceText(input.source);
  const identity = await localMutationIdentity(obraId);
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
  if (validFrom <= previous.validFrom || (validTo && validTo < validFrom)) {
    throw new Error("A nova vigência deve começar depois da versão anterior.");
  }
  const payload = {
    id: entityId,
    obraId: identity.obraId,
    previousPriceId: previous.id,
    unitPrice,
    contractedQuantity,
    validFrom,
    validTo,
    source,
  };
  const local: ServicePriceVersionLocalRecord = {
    id: entityId,
    obraId: identity.obraId,
    serviceId: previous.serviceId,
    unit: previous.unit,
    currency: previous.currency,
    version: previous.version + 1,
    unitPrice: payload.unitPrice,
    contractedQuantity: payload.contractedQuantity,
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
