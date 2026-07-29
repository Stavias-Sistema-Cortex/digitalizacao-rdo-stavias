import type {
  AlocacaoColaboradorDraft,
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  RdoAttachmentDraft,
  RdoDraft,
  ServicoExecutadoDraft,
} from "../../features/rdos/rdo.types";
import { localRecordToDraft } from "../../features/rdos/localRecordToDraft";
import {
  buscarContextoDeCriacaoRdo,
  buscarColaboradoresAutorizadosDaObra,
  buscarRdoAutoritativoPorId,
  type AuthoritativeRdoLookup,
  RdoLookupHttpError,
  RdoLookupPayloadError,
  type RdoContextCoverageSection,
  type RdoCreationContextLookup,
} from "../../features/rdos/rdoLookupApi";
import {
  assertContextSession,
  captureContextSession,
  type RdoContextSessionGuard,
} from "../../features/rdos/rdoCreationContextRepository";
import { getCortexDb } from "./cortexDb";
import type {
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  LocalRdoChildRecord,
  LocalRdoRecord,
  LocalSyncStatus,
  OperationalEntityRef,
  OperationalEventRecord,
  OutboxMutationRecord,
  RdoAttachmentRecord,
  RdoCreationContextCacheRecord,
} from "./db.types";
import {
  buildOperationalEvent,
  queryOperationalEvents,
} from "./operationalEventRepository";
import {
  buildCanonicalMutation,
  canonicalMutationJson,
  isCanonicalOutboxMutation,
} from "../sync/mutationEnvelope";
import { guardSyncTransaction } from "../sync/guardedSyncTransaction";
import {
  assertSyncSession,
  captureOnlineSyncSession,
  type SyncSessionGuard,
} from "../sync/syncSession";
import {
  SyncLeaseLostError,
  type SyncExecutionLease,
} from "../sync/syncExecutionLease";
import {
  assertCanonicalMutationEventProvenance,
} from "../sync/syncStorage";
import { getSession } from "../../features/auth/authSession";
import {
  getSyncState,
  updateSyncState,
} from "./syncStateRepository";
import {
  commitLocalMutation,
  type LocalMutationDomainWrite,
} from "../sync/localMutationCoordinator";

export interface SaveRdoDraftResult {
  rdo: LocalRdoRecord;
  mutation: OutboxMutationRecord;
}

type RdoChildStoreName =
  | "rdoMaoObra"
  | "rdoEquipamentos"
  | "rdoMateriais"
  | "rdoControlesGeometricos";

type RdoCoalescibleOperation =
  | "CRIAR_RDO"
  | "ATUALIZAR_RDO_RASCUNHO";

export function canCoalesceLegacyRdoMutation(
  mutation: OutboxMutationRecord,
  operation: RdoCoalescibleOperation,
): boolean {
  return !isCanonicalOutboxMutation(mutation) &&
    mutation.operacao === operation &&
    !(
      typeof mutation.blockedReason === "string" &&
      /^NON_APPLIED_SUPERSEDED_BY:[^\s:]+$/.test(
        mutation.blockedReason.trim(),
      )
    ) &&
    mutation.status === "PENDING" &&
    mutation.tentativas === 0 &&
    mutation.ultimaTentativaEm === null;
}

interface RdoChildStoreWriter {
  index: (name: "by-rdo-id") => {
    getAllKeys: (query: string) => Promise<string[]>;
  };
  delete: (key: string) => Promise<void>;
  put: (
    value: LocalRdoChildRecord,
  ) => Promise<string>;
}

interface RdoChildWriteTransaction {
  objectStore: (
    name: RdoChildStoreName,
  ) => RdoChildStoreWriter;
}

interface RdoAttachmentStoreWriter {
  index: (name: "by-rdo-id") => {
    getAll: (query: string) => Promise<RdoAttachmentRecord[]>;
  };
  put: (value: RdoAttachmentRecord) => Promise<string>;
}

interface RdoAttachmentWriteTransaction {
  objectStore: (
    name: "rdo_attachments",
  ) => RdoAttachmentStoreWriter;
}

function nowUtc(): string {
  return new Date().toISOString();
}

function removeLocalId<T extends { localId: string }>(
  item: T,
): Omit<T, "localId"> {
  const { localId, ...payload } = item;

  void localId;

  return payload;
}

export function rdoDraftFromLocalRecord(
  rdo: LocalRdoRecord,
): RdoDraft {
  return localRecordToDraft(rdo);
}

function nullIfEmpty(value: string): string | null {
  return value.trim() === "" ? null : value;
}

export function rdoCreationContextBlockReason(
  draft: RdoDraft,
): "RDO_CREATION_CONTEXT_REQUIRED" | null {
  return draft.creationContextVersion !== null &&
    Number.isSafeInteger(draft.creationContextVersion) &&
    draft.creationContextVersion > 0
    ? null
    : "RDO_CREATION_CONTEXT_REQUIRED";
}

const RDO_CREATION_CONTEXT_REQUIRED =
  "Contexto versionado da obra é obrigatório para criar o RDO.";

function contextualText(value: string | null | undefined): string {
  return value?.trim() ?? "";
}

const CONTRACT_INSTANT_PATTERN =
  /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?Z$/;

function parseContractInstant(value: unknown): number | null {
  if (typeof value !== "string") return null;
  const match = CONTRACT_INSTANT_PATTERN.exec(value);
  if (!match) return null;
  const [year, month, day, hour, minute, second] = match
    .slice(1, 7)
    .map(Number);
  const millisecond = Number((match[7] ?? "").padEnd(3, "0").slice(0, 3));
  const timestamp = Date.UTC(
    year,
    month - 1,
    day,
    hour,
    minute,
    second,
    millisecond,
  );
  const parsed = new Date(timestamp);
  return year >= 1000 &&
      parsed.getUTCFullYear() === year &&
      parsed.getUTCMonth() === month - 1 &&
      parsed.getUTCDate() === day &&
      parsed.getUTCHours() === hour &&
      parsed.getUTCMinutes() === minute &&
      parsed.getUTCSeconds() === second &&
      parsed.getUTCMilliseconds() === millisecond
    ? timestamp
    : null;
}

function hasCanonicalCreationIdentity(
  draft: RdoDraft,
  context: RdoCreationContextLookup,
): boolean {
  const previous = context.previousRdo;
  const canonicalPreviousRdoId =
    previous &&
      previous.dataRdo < context.data &&
      context.provenance.previousRdoId === previous.id
      ? previous.id
      : "";
  return draft.numeroRdo === contextualText(context.nextNumberSuggestion) &&
    draft.cliente === contextualText(context.obra.cliente) &&
    draft.contrato === contextualText(context.obra.codigoContrato) &&
    draft.rodovia === contextualText(context.obra.rodovia) &&
    draft.cidade === contextualText(context.obra.cidade) &&
    draft.uf === contextualText(context.obra.uf) &&
    draft.previousRdoId === canonicalPreviousRdoId;
}

function validCreationContextReceipt(
  draft: RdoDraft,
  record: RdoCreationContextCacheRecord,
): boolean {
  try {
    const context = record.context as unknown as RdoCreationContextLookup;
    const { coverage, freshness, provenance } = context;
    const generatedAt = parseContractInstant(freshness.generatedAt);
    const staleAfter = parseContractInstant(freshness.staleAfter);
    return record.obraId === draft.obraId &&
      record.selectedDate === draft.dataRdo &&
      record.receiptVersion === draft.creationContextVersion &&
      record.sourceVersion === provenance.sourceVersion &&
      context.obra.id === draft.obraId &&
      context.data === draft.dataRdo &&
      provenance.worksiteId === draft.obraId &&
      provenance.selectedDate === draft.dataRdo &&
      provenance.receiptVersion === draft.creationContextVersion &&
      Number.isSafeInteger(provenance.receiptVersion) &&
      provenance.receiptVersion > 0 &&
      Number.isSafeInteger(provenance.sourceVersion) &&
      provenance.sourceVersion >= 0 &&
      provenance.sourceVersion === freshness.sourceVersion &&
      freshness.status === "FRESH" &&
      provenance.generatedAt === freshness.generatedAt &&
      provenance.previousRdoId === (context.previousRdo?.id ?? null) &&
      generatedAt !== null &&
      staleAfter !== null &&
      generatedAt < staleAfter &&
      canonicalMutationJson(record.coverage) ===
        canonicalMutationJson(coverage as unknown as Record<string, unknown>) &&
      completeCoverage(coverage.previousWorkforce) &&
      completeCoverage(coverage.programacoes) &&
      completeCoverage(coverage.colaboradores) &&
      completeCoverage(coverage.equipamentos) &&
      explicitCatalogCoverage(coverage.serviceCatalog) &&
      explicitCatalogCoverage(coverage.priceCatalog) &&
      hasCanonicalCreationIdentity(draft, context);
  } catch {
    return false;
  }
}

async function requireExactRdoCreationContext(
  draft: RdoDraft,
): Promise<RdoContextSessionGuard> {
  if (rdoCreationContextBlockReason(draft)) {
    throw new Error(RDO_CREATION_CONTEXT_REQUIRED);
  }
  const guard = captureContextSession();
  const session = guard.session;
  if (!session.escopoGlobal && !session.obraIds.includes(draft.obraId)) {
    throw new Error(RDO_CREATION_CONTEXT_REQUIRED);
  }
  const database = await getCortexDb();
  assertContextSession(guard);
  const record = await database.get("rdo_creation_contexts", [
    session.colaboradorId,
    draft.obraId,
    draft.dataRdo,
  ]);
  assertContextSession(guard);
  if (
    !record ||
    record.ownerId !== session.colaboradorId ||
    !validCreationContextReceipt(draft, record)
  ) {
    throw new Error(RDO_CREATION_CONTEXT_REQUIRED);
  }
  return guard;
}

export interface RdoCreationContextCacheEntry {
  schemaVersion: 1;
  worksiteId: string;
  selectedDate: string;
  receiptVersion: number;
  previousRdoId: string | null;
  sourceVersion: number;
  generatedAt: string;
  staleAfter: string;
  cachedAt: string;
  context: RdoCreationContextLookup;
}

function completeCoverage(
  section: RdoContextCoverageSection,
): boolean {
  return section.status === "COMPLETE" &&
    section.complete === true &&
    Number.isSafeInteger(section.total) &&
    Number.isSafeInteger(section.returned) &&
    section.total >= 0 &&
    section.returned === section.total;
}

function explicitCatalogCoverage(
  section: RdoContextCoverageSection,
): boolean {
  return completeCoverage(section) ||
    (section.status === "NOT_CONFIGURED" &&
      section.complete === false &&
      section.total === 0 &&
      section.returned === 0);
}

function durableCreationContextCache(
  context: RdoCreationContextLookup,
  rdo: LocalRdoRecord,
  cachedAt: string,
): RdoCreationContextCacheEntry | null {
  const provenance = context?.provenance;
  const coverage = context?.coverage;
  const freshness = context?.freshness;
  const previousRdoId = context?.previousRdo?.id ?? null;

  if (!provenance || !coverage || !freshness ||
      context.data !== rdo.dataRdo ||
      freshness.status !== "FRESH" ||
      provenance.worksiteId !== rdo.obraId ||
      provenance.selectedDate !== rdo.dataRdo ||
      provenance.previousRdoId !== previousRdoId ||
      !Number.isSafeInteger(provenance.receiptVersion) ||
      provenance.receiptVersion <= 0 ||
      !Number.isSafeInteger(provenance.sourceVersion) ||
      provenance.sourceVersion < 0 ||
      provenance.sourceVersion !== freshness.sourceVersion ||
      provenance.generatedAt !== freshness.generatedAt ||
      typeof freshness.staleAfter !== "string" ||
      !Number.isFinite(Date.parse(freshness.generatedAt)) ||
      !Number.isFinite(Date.parse(freshness.staleAfter)) ||
      Date.parse(freshness.staleAfter) <=
        Date.parse(freshness.generatedAt) ||
      !completeCoverage(coverage.previousWorkforce) ||
      !completeCoverage(coverage.programacoes) ||
      !completeCoverage(coverage.colaboradores) ||
      !completeCoverage(coverage.equipamentos) ||
      !explicitCatalogCoverage(coverage.serviceCatalog) ||
      !explicitCatalogCoverage(coverage.priceCatalog)) {
    return null;
  }

  return {
    schemaVersion: 1,
    worksiteId: rdo.obraId,
    selectedDate: rdo.dataRdo,
    receiptVersion: provenance.receiptVersion,
    previousRdoId,
    sourceVersion: provenance.sourceVersion,
    generatedAt: provenance.generatedAt,
    staleAfter: freshness.staleAfter,
    cachedAt,
    context,
  };
}

function legacyPersistedUpdateCanOmitContext(
  rdo: LocalRdoRecord,
): boolean {
  if (rdo.versaoEntidade === null) {
    return false;
  }
  const persisted = rdo.payload.creationContextVersion;
  return persisted === undefined || persisted === null;
}

export function rdoUpdateCreationContextBlockReason(
  draft: RdoDraft,
  persistedRdo: LocalRdoRecord,
): "RDO_CREATION_CONTEXT_REQUIRED" | null {
  const createRule = rdoCreationContextBlockReason(draft);
  if (createRule === null) {
    return null;
  }
  return legacyPersistedUpdateCanOmitContext(persistedRdo)
    ? null
    : createRule;
}

function entityName(value: string | null | undefined): string | null {
  if (!value || !value.trim()) {
    return null;
  }

  return value.trim();
}

function numberFromText(value: string): number | null {
  const normalized = value.trim().replace(",", ".");

  if (!normalized) {
    return null;
  }

  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

function numberFromInput(value: string | number): number | null {
  if (typeof value === "number") {
    return Number.isFinite(value) ? value : null;
  }

  return numberFromText(value);
}

function round3(value: number): number {
  return Math.round((value + Number.EPSILON) * 1000) / 1000;
}

function calculatedLengthFromKm(
  kmInicial: string,
  kmFinal: string,
): number | null {
  const start = numberFromText(kmInicial);
  const end = numberFromText(kmFinal);

  if (start === null || end === null || end < start) {
    return null;
  }

  return round3((end - start) * 1000);
}

function attachmentPayload(
  attachment: RdoAttachmentDraft,
): Record<string, unknown> {
  return {
    id: attachment.id,
    rdoId: attachment.rdoId,
    obraId: attachment.obraId,
    tipo: attachment.tipo,
    nome: attachment.nome,
    nomeOriginal: attachment.nomeOriginal,
    mimeType: attachment.mimeType,
    tamanhoOriginalBytes: attachment.tamanhoOriginalBytes,
    tamanhoComprimidoBytes: attachment.tamanhoComprimidoBytes,
    tamanhoBytes: attachment.tamanhoBytes,
    syncStatus: attachment.syncStatus,
    createdAt: attachment.createdAt,
    updatedAt: attachment.updatedAt,
    removedAt: attachment.removedAt,
    metadata: attachment.metadata,
  };
}

function buildMaoObraPayload(item: MaoObraDraft) {
  const {
    localId,
    selected: _selected,
    sourceRdoId: _sourceRdoId,
    origin: _origin,
    availability: _availability,
    ...base
  } = item;
  void _selected;
  void _sourceRdoId;
  void _origin;
  void _availability;

  return {
    ...base,
    id: localId,
    origemItemId: nullIfEmpty(base.origemItemId),
    colaboradorId: nullIfEmpty(base.colaboradorId),
    horaInicio: nullIfEmpty(base.horaInicio),
    horaFim: nullIfEmpty(base.horaFim),
    observacoes: nullIfEmpty(base.observacoes),
  };
}

function buildEquipamentoPayload(
  item: EquipamentoDraft,
) {
  const base = removeLocalId(item);

  return {
    ...base,
    id: item.localId,
    assetId: nullIfEmpty(base.assetId),
    horaInicio: nullIfEmpty(base.horaInicio),
    horaFim: nullIfEmpty(base.horaFim),
    observacoes: nullIfEmpty(base.observacoes),
  };
}

function buildServicoExecutadoPayload(
  item: ServicoExecutadoDraft,
) {
  return {
    id: item.localId,
    serviceId: nullIfEmpty(item.serviceId),
    priceVersionId: nullIfEmpty(item.priceVersionId),
    servicoNome: item.servicoNome,
    itemContratualId: nullIfEmpty(
      item.itemContratualId,
    ),
    quantidadeExecutada: item.quantidadeExecutada,
    unidade: nullIfEmpty(item.unidade),
    trechoInicial: nullIfEmpty(item.trechoInicial),
    trechoFinal: nullIfEmpty(item.trechoFinal),
    localizacao: nullIfEmpty(item.localizacao),
    turno: nullIfEmpty(item.turno),
    statusValidacao: item.statusValidacao,
    retrabalho: item.retrabalho,
    producaoRejeitada: item.producaoRejeitada,
    observacoes: nullIfEmpty(item.observacoes),
  };
}

function buildAlocacaoPayload(
  item: AlocacaoColaboradorDraft,
) {
  return {
    id: item.localId,
    colaboradorId: nullIfEmpty(item.colaboradorId),
    equipe: nullIfEmpty(item.equipe),
    servicoNome: nullIfEmpty(item.servicoNome),
    horaInicio: nullIfEmpty(item.horaInicio),
    horaFim: nullIfEmpty(item.horaFim),
    percentualDia: item.percentualDia,
    turno: nullIfEmpty(item.turno),
    funcao: nullIfEmpty(item.funcao),
    centroCusto: nullIfEmpty(item.centroCusto),
    tipoAlocacao: item.tipoAlocacao,
    fonte: nullIfEmpty(item.fonte),
    status: item.status,
    observacoes: nullIfEmpty(item.observacoes),
  };
}

function buildServicoExecutadoLocalPayload(
  item: ServicoExecutadoDraft,
): ServicoExecutadoDraft {
  return {
    localId: item.localId,
    serviceId: item.serviceId,
    priceVersionId: item.priceVersionId,
    servicoNome: item.servicoNome,
    itemContratualId: item.itemContratualId,
    quantidadeExecutada: item.quantidadeExecutada,
    unidade: item.unidade,
    trechoInicial: item.trechoInicial,
    trechoFinal: item.trechoFinal,
    localizacao: item.localizacao,
    turno: item.turno,
    statusValidacao: item.statusValidacao,
    retrabalho: item.retrabalho,
    producaoRejeitada: item.producaoRejeitada,
    observacoes: item.observacoes,
  };
}

function buildAlocacaoLocalPayload(
  item: AlocacaoColaboradorDraft,
): AlocacaoColaboradorDraft {
  return {
    localId: item.localId,
    colaboradorId: item.colaboradorId,
    equipe: item.equipe,
    servicoNome: item.servicoNome,
    horaInicio: item.horaInicio,
    horaFim: item.horaFim,
    percentualDia: item.percentualDia,
    turno: item.turno,
    funcao: item.funcao,
    centroCusto: item.centroCusto,
    tipoAlocacao: item.tipoAlocacao,
    fonte: item.fonte,
    status: item.status,
    observacoes: item.observacoes,
  };
}

function isServicoExecutadoEmpty(
  item: ServicoExecutadoDraft,
): boolean {
  return (
    item.servicoNome.trim() === "" &&
    item.quantidadeExecutada === "" &&
    item.itemContratualId.trim() === ""
  );
}

function isServicoExecutadoSyncable(
  item: ServicoExecutadoDraft,
): boolean {
  if (isServicoExecutadoEmpty(item)) {
    return false;
  }

  return item.quantidadeExecutada !== "";
}

function isAlocacaoEmpty(
  item: AlocacaoColaboradorDraft,
): boolean {
  return (
    item.colaboradorId.trim() === "" &&
    item.equipe.trim() === "" &&
    item.servicoNome.trim() === ""
  );
}

function isMaterialEmpty(
  item: RdoDraft["materiais"][number],
): boolean {
  const { localId, ...fields } = item;
  void localId;

  return Object.values(fields).every(
    (value) =>
      value === null ||
      value === undefined ||
      (typeof value === "string" &&
        value.trim() === ""),
  );
}

function isMaoObraEmpty(item: MaoObraDraft): boolean {
  return (
    item.colaboradorId.trim() === "" &&
    item.nomeColaborador.trim() === "" &&
    item.cargo.trim() === "" &&
    item.quantidade === "" &&
    item.horaInicio.trim() === "" &&
    item.horaFim.trim() === "" &&
    item.observacoes.trim() === ""
  );
}

function isEquipamentoEmpty(
  item: EquipamentoDraft,
): boolean {
  return (
    item.assetId.trim() === "" &&
    item.prefixo.trim() === "" &&
    item.descricao.trim() === "" &&
    item.tipoEquipamento.trim() === "" &&
    item.quantidade === "" &&
    item.horaInicio.trim() === "" &&
    item.horaFim.trim() === "" &&
    item.observacoes.trim() === ""
  );
}

function isControleEmpty(
  item: ControleGeometricoDraft,
): boolean {
  const { localId, ...fields } = item;
  void localId;

  return Object.values(fields).every(
    (value) =>
      value === null ||
      value === undefined ||
      (typeof value === "string" &&
        value.trim() === ""),
  );
}

export function buildRdoSyncPayload(
  draft: RdoDraft,
  operationalEvents: OperationalEventRecord[] = [],
): Record<string, unknown> {
  const attachments = draft.attachments ?? [];

  return {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId: draft.programacaoId || null,
    previousRdoId: nullIfEmpty(draft.previousRdoId),
    creationContextVersion: draft.creationContextVersion,
    apontadorColaboradorId: nullIfEmpty(
      draft.apontadorColaboradorId,
    ),
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    cliente: nullIfEmpty(draft.cliente),
    contrato: nullIfEmpty(draft.contrato),
    rodovia: nullIfEmpty(draft.rodovia),
    cidade: nullIfEmpty(draft.cidade),
    uf: nullIfEmpty(draft.uf),
    kmInicialProgramado: nullIfEmpty(
      draft.kmInicialProgramado,
    ),
    kmFinalProgramado: nullIfEmpty(
      draft.kmFinalProgramado,
    ),
    kmInicialInterditado: nullIfEmpty(
      draft.kmInicialInterditado,
    ),
    kmFinalInterditado: nullIfEmpty(
      draft.kmFinalInterditado,
    ),
    turno: draft.turno,
    horaInicio: draft.horaInicio || null,
    horaFim: draft.horaFim || null,
    condicaoManha: draft.condicaoManha || null,
    condicaoTarde: draft.condicaoTarde || null,
    condicaoNoite: draft.condicaoNoite || null,
    pluviometriaMm:
      draft.pluviometriaMm === ""
        ? null
        : draft.pluviometriaMm,
    observacoes: draft.observacoes,
    preenchidoPor: nullIfEmpty(draft.preenchidoPor),
    apontadorRdo: nullIfEmpty(draft.apontadorRdo),
    encarregadoObra: nullIfEmpty(draft.encarregadoObra),
    fiscalizacaoCampo: nullIfEmpty(
      draft.fiscalizacaoCampo,
    ),
    servicosExecutados:
      draft.servicosExecutados
        .filter(isServicoExecutadoSyncable)
        .map(buildServicoExecutadoPayload),
    alocacoesColaboradores:
      draft.alocacoesColaboradores
        .filter((item) => !isAlocacaoEmpty(item))
        .map(buildAlocacaoPayload),
    maoObra: draft.maoObra
      .filter((item) => item.selected && !isMaoObraEmpty(item))
      .map(buildMaoObraPayload),
    equipamentos: draft.equipamentos
      .filter((item) => !isEquipamentoEmpty(item))
      .map(buildEquipamentoPayload),
    materiais: draft.materiais
      .filter((item) => !isMaterialEmpty(item))
      .map((item) => ({
        ...removeLocalId(item),
        id: item.localId,
      })),

    controlesGeometricos:
      draft.controlesGeometricos
        .filter((item) => !isControleEmpty(item))
        .map((item) => ({
          ...removeLocalId(item),
          id: item.localId,
        })),
    attachments: attachments
      .filter((item) => item.removedAt === null)
      .map(attachmentPayload),
    operationalEvents: operationalEvents.map((event) => ({
      id: event.id,
      type: event.type,
      principalEntity: event.principalEntity,
      relatedEntities: event.relatedEntities,
      obraId: event.obraId,
      rdoId: event.rdoId,
      colaboradorId: event.colaboradorId,
      occurredAt: event.occurredAt,
      syncedAt: event.syncedAt,
      origin: event.origin,
      responsibleUserId: event.responsibleUserId,
      responsibleUserName: event.responsibleUserName,
      payload: event.payload,
      syncStatus: event.syncStatus,
      schemaVersion: event.schemaVersion,
    })),
  };
}

export function buildRdoSyncPayloadFromLocalRecord(
  rdo: LocalRdoRecord,
  operationalEvents: OperationalEventRecord[] = [],
): Record<string, unknown> {
  return buildRdoSyncPayload(
    rdoDraftFromLocalRecord(rdo),
    operationalEvents,
  );
}

function buildRdoLocalPayload(
  draft: RdoDraft,
): Record<string, unknown> {
  const attachments = draft.attachments ?? [];

  return {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId: draft.programacaoId || null,
    previousRdoId: draft.previousRdoId,
    previousRdoNumber: draft.previousRdoNumber,
    creationContextVersion: draft.creationContextVersion,
    apontadorColaboradorId: draft.apontadorColaboradorId,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    cliente: draft.cliente,
    contrato: draft.contrato,
    rodovia: draft.rodovia,
    cidade: draft.cidade,
    uf: draft.uf,
    kmInicialProgramado:
      draft.kmInicialProgramado,
    kmFinalProgramado:
      draft.kmFinalProgramado,
    kmInicialInterditado:
      draft.kmInicialInterditado,
    kmFinalInterditado:
      draft.kmFinalInterditado,
    turno: draft.turno,
    horaInicio: draft.horaInicio,
    horaFim: draft.horaFim,
    condicaoManha: draft.condicaoManha,
    condicaoTarde: draft.condicaoTarde,
    condicaoNoite: draft.condicaoNoite,
    pluviometriaMm: draft.pluviometriaMm,
    observacoes: draft.observacoes,
    preenchidoPor: draft.preenchidoPor,
    apontadorRdo: draft.apontadorRdo,
    encarregadoObra: draft.encarregadoObra,
    fiscalizacaoCampo: draft.fiscalizacaoCampo,
    servicosExecutados: draft.servicosExecutados.map(
      buildServicoExecutadoLocalPayload,
    ),
    alocacoesColaboradores:
      draft.alocacoesColaboradores.map(buildAlocacaoLocalPayload),
    maoObra: draft.maoObra,
    equipamentos: draft.equipamentos,
    materiais: draft.materiais,
    controlesGeometricos:
      draft.controlesGeometricos,
    attachments: attachments.map(attachmentPayload),
    importEvidence: draft.importEvidence,
  };
}

function rdoEntity(draft: RdoDraft): OperationalEntityRef {
  return {
    tipo: "RDO",
    id: draft.id,
    nome: draft.numeroRdo ? `RDO ${draft.numeroRdo}` : "RDO local",
  };
}

function obraEntity(draft: RdoDraft): OperationalEntityRef {
  return {
    tipo: "OBRA",
    id: draft.obraId,
    nome: entityName(draft.contrato) ?? entityName(draft.cliente),
  };
}

function nonEmptyRelated(
  entities: OperationalEntityRef[],
): OperationalEntityRef[] {
  return entities.filter(
    (entity) => entity.id && entity.id.trim(),
  );
}

function isUuid(value: string): boolean {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(
    value,
  );
}

function photoLifecycleEventId(
  attachmentId: string,
  lifecycle: "ADICIONADA" | "COMPRIMIDA" | "REMOVIDA",
): string {
  const normalizedId = attachmentId.trim();

  if (!isUuid(normalizedId)) {
    return crypto.randomUUID();
  }

  if (lifecycle === "ADICIONADA") {
    return normalizedId;
  }

  const currentMarker = normalizedId[14];
  const preferredMarker = lifecycle === "COMPRIMIDA" ? "5" : "6";
  const alternateMarker = lifecycle === "COMPRIMIDA" ? "d" : "e";
  const marker =
    currentMarker === preferredMarker
      ? alternateMarker
      : preferredMarker;

  return `${normalizedId.slice(0, 14)}${marker}${normalizedId.slice(15)}`;
}

function buildPhotoLifecycleEvents(
  draft: RdoDraft,
  base: {
    obraId: string;
    rdoId: string;
    occurredAt: string;
    origin: "OFFLINE";
    syncStatus: "PENDING_SYNC";
    schemaVersion: number;
  },
  rdo: OperationalEntityRef,
  obra: OperationalEntityRef,
): OperationalEventRecord[] {
  return draft.attachments.flatMap((attachment) => {
    const relatedEntities = nonEmptyRelated([rdo, obra]);
    const photo = {
      tipo: "RDO_FOTO",
      id: attachment.id,
      nome: attachment.nome,
    };
    const occurredAt = attachment.createdAt || base.occurredAt;
    const events: OperationalEventRecord[] = [
      buildOperationalEvent({
        ...base,
        id: photoLifecycleEventId(attachment.id, "ADICIONADA"),
        type: "FOTO_ADICIONADA",
        principalEntity: photo,
        relatedEntities,
        occurredAt,
        payload: {
          nomeOriginal: attachment.nomeOriginal,
          mimeType: attachment.mimeType,
          tamanhoOriginalBytes: attachment.tamanhoOriginalBytes,
          tamanhoComprimidoBytes: attachment.tamanhoComprimidoBytes,
          tamanhoBytes: attachment.tamanhoBytes,
          syncStatus: attachment.syncStatus,
          metadata: attachment.metadata,
        },
      }),
    ];

    if (
      attachment.tamanhoComprimidoBytes <
      attachment.tamanhoOriginalBytes
    ) {
      events.push(
        buildOperationalEvent({
          ...base,
          id: photoLifecycleEventId(
            attachment.id,
            "COMPRIMIDA",
          ),
          type: "FOTO_COMPRIMIDA",
          principalEntity: photo,
          relatedEntities,
          occurredAt: attachment.updatedAt || occurredAt,
          payload: {
            tamanhoOriginalBytes: attachment.tamanhoOriginalBytes,
            tamanhoComprimidoBytes:
              attachment.tamanhoComprimidoBytes,
            reducaoBytes:
              attachment.tamanhoOriginalBytes -
              attachment.tamanhoComprimidoBytes,
          },
        }),
      );
    }

    if (attachment.removedAt !== null) {
      events.push(
        buildOperationalEvent({
          ...base,
          id: photoLifecycleEventId(attachment.id, "REMOVIDA"),
          type: "FOTO_REMOVIDA",
          principalEntity: photo,
          relatedEntities,
          occurredAt: attachment.removedAt,
          payload: {
            attachmentId: attachment.id,
            nomeOriginal: attachment.nomeOriginal,
          },
        }),
      );
    }

    return events;
  });
}

function buildRdoSaveOperationalEvents(
  draft: RdoDraft,
  isExisting: boolean,
  timestamp: string,
): OperationalEventRecord[] {
  const rdo = rdoEntity(draft);
  const obra = obraEntity(draft);
  const base = {
    obraId: draft.obraId,
    rdoId: draft.id,
    occurredAt: timestamp,
    origin: "OFFLINE" as const,
    syncStatus: "PENDING_SYNC" as const,
    schemaVersion: 1,
  };

  const events: OperationalEventRecord[] = [
    buildOperationalEvent({
      ...base,
      type: isExisting ? "RDO_EDITADO" : "RDO_CRIADO",
      principalEntity: rdo,
      relatedEntities: nonEmptyRelated([obra]),
      payload: {
        numeroRdo: draft.numeroRdo,
        dataRdo: draft.dataRdo,
        statusRdo: draft.syncStatus,
      },
    }),
    buildOperationalEvent({
      ...base,
      type: "RDO_SALVO_OFFLINE",
      principalEntity: rdo,
      relatedEntities: nonEmptyRelated([obra]),
      payload: {
        numeroRdo: draft.numeroRdo,
        dataRdo: draft.dataRdo,
        totalFotos: (draft.attachments ?? []).filter(
          (item) => item.removedAt === null,
        ).length,
      },
    }),
    buildOperationalEvent({
      ...base,
      type: "ENTIDADE_RELACIONADA",
      principalEntity: rdo,
      relatedEntities: nonEmptyRelated([obra]),
      payload: {
        relationType: "PERTENCE_A",
        origemTipo: "RDO",
        origemId: draft.id,
        destinoTipo: "OBRA",
        destinoId: draft.obraId,
      },
    }),
  ];

  events.push(...buildPhotoLifecycleEvents(draft, base, rdo, obra));

  if (draft.programacaoId.trim()) {
    events.push(
      buildOperationalEvent({
        ...base,
        type: "ENTIDADE_RELACIONADA",
        principalEntity: rdo,
        relatedEntities: [
          {
            tipo: "PROGRAMACAO_OPERACIONAL",
            id: draft.programacaoId,
            nome: null,
          },
        ],
        payload: {
          relationType: "GERADO_A_PARTIR_DE",
          origemTipo: "RDO",
          origemId: draft.id,
          destinoTipo: "PROGRAMACAO_OPERACIONAL",
          destinoId: draft.programacaoId,
        },
      }),
    );
  }

  for (const item of draft.alocacoesColaboradores) {
    if (!item.colaboradorId.trim()) {
      continue;
    }

    events.push(
      buildOperationalEvent({
        ...base,
        type: "COLABORADOR_ASSOCIADO_RDO",
        principalEntity: {
          tipo: "COLABORADOR",
          id: item.colaboradorId,
          nome: entityName(item.equipe) ?? entityName(item.funcao),
        },
        relatedEntities: nonEmptyRelated([rdo, obra]),
        colaboradorId: item.colaboradorId,
        payload: {
          equipe: item.equipe,
          funcao: item.funcao,
          servicoNome: item.servicoNome,
          horaInicio: item.horaInicio,
          horaFim: item.horaFim,
          percentualDia: item.percentualDia,
          tipoAlocacao: item.tipoAlocacao,
        },
      }),
    );
  }

  for (const item of draft.maoObra.filter((row) => row.selected)) {
    const colaboradorId = item.colaboradorId.trim();
    if (!colaboradorId && !item.nomeColaborador.trim()) {
      continue;
    }

    events.push(
      buildOperationalEvent({
        ...base,
        type: "COLABORADOR_ASSOCIADO_RDO",
        principalEntity: {
          tipo: colaboradorId ? "COLABORADOR" : "RDO_MAO_OBRA",
          id: colaboradorId || item.localId,
          nome:
            entityName(item.nomeColaborador) ??
            entityName(item.cargo),
        },
        relatedEntities: nonEmptyRelated([rdo, obra]),
        colaboradorId: colaboradorId || null,
        payload: {
          nomeColaborador: item.nomeColaborador,
          cargo: item.cargo,
          tipoVinculo: item.tipoVinculo,
          quantidade: item.quantidade,
          localId: item.localId,
          localEntityId: `${draft.id}:maoObra:${item.localId}`,
        },
      }),
    );
  }

  for (const item of draft.equipamentos) {
    const assetId = item.assetId.trim();

    if (
      !assetId &&
      !item.prefixo.trim() &&
      !item.descricao.trim()
    ) {
      continue;
    }

    events.push(
      buildOperationalEvent({
        ...base,
        type: "EQUIPAMENTO_ASSOCIADO_RDO",
        principalEntity: {
          tipo: assetId ? "ATIVO" : "RDO_EQUIPAMENTO",
          id: assetId || item.localId,
          nome:
            entityName(item.prefixo) ??
            entityName(item.descricao) ??
            entityName(item.tipoEquipamento),
        },
        relatedEntities: nonEmptyRelated([rdo, obra]),
        payload: {
          prefixo: item.prefixo,
          descricao: item.descricao,
          tipoEquipamento: item.tipoEquipamento,
          tipoVinculo: item.tipoVinculo,
          quantidade: item.quantidade,
          localId: item.localId,
          localEntityId: `${draft.id}:equipamento:${item.localId}`,
        },
      }),
    );
  }

  for (const item of draft.controlesGeometricos) {
    if (isControleEmpty(item)) {
      continue;
    }

    const extensaoM =
      calculatedLengthFromKm(item.kmInicial, item.kmFinal) ??
      (typeof item.comprimentoM === "number"
        ? item.comprimentoM
        : null);

    events.push(
      buildOperationalEvent({
        ...base,
        type: "MEDICAO_TRECHO_ATUALIZADA",
        principalEntity: rdo,
        relatedEntities: [
          {
            tipo: "CONTROLE_GEOMETRICO",
            id: item.localId,
            nome:
              entityName(item.subtrecho) ??
              entityName(item.numero) ??
              entityName(item.ordemServico),
          },
        ],
        payload: {
          subtrecho: item.subtrecho,
          numero: item.numero,
          kmInicial: item.kmInicial,
          kmFinal: item.kmFinal,
          extensaoM,
          pista: item.pista,
          faixa: item.faixa,
          comprimentoM: item.comprimentoM,
          larguraM: item.larguraM,
          localId: item.localId,
          localEntityId: `${draft.id}:controle:${item.localId}`,
        },
      }),
    );
  }

  const hasCalculations =
    draft.controlesGeometricos.some((item) => !isControleEmpty(item)) ||
    draft.materiais.some((item) => !isMaterialEmpty(item));

  if (hasCalculations) {
    events.push(
      buildOperationalEvent({
        ...base,
        type: "CALCULO_REPROCESSADO",
        principalEntity: rdo,
        relatedEntities: nonEmptyRelated([obra]),
        payload: {
          controlesGeometricos: draft.controlesGeometricos.length,
          materiais: draft.materiais.length,
          calculosOffline: [
            "extensao_trecho",
            "sobra_material",
            "area_volume_massa",
          ],
        },
      }),
    );
  }

  const occurrenceText = [
    draft.observacoes,
    ...draft.servicosExecutados.map((item) => item.observacoes),
  ]
    .join(" ")
    .toLowerCase();
  const hasOccurrence =
    /ocorr|inciden|problema|paralis|chuva|interdi|rejeitad/.test(
      occurrenceText,
    ) ||
    draft.servicosExecutados.some(
      (item) => item.producaoRejeitada || item.retrabalho,
    );

  if (hasOccurrence) {
    events.push(
      buildOperationalEvent({
        ...base,
        type: "OCORRENCIA_REGISTRADA",
        principalEntity: rdo,
        relatedEntities: nonEmptyRelated([obra]),
        payload: {
          observacoes: draft.observacoes,
          servicosComRetrabalho:
            draft.servicosExecutados.filter(
              (item) => item.retrabalho,
            ).length,
          servicosRejeitados:
            draft.servicosExecutados.filter(
              (item) => item.producaoRejeitada,
            ).length,
        },
      }),
    );
  }

  return events;
}

function childRecordId(
  rdoId: string,
  collection: string,
  localId: string,
): string {
  return `${rdoId}:${collection}:${localId}`;
}

function buildChildRecord<TItem extends { localId: string }>(
  rdoId: string,
  collection: string,
  item: TItem,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord {
  return {
    id: childRecordId(
      rdoId,
      collection,
      item.localId,
    ),
    rdoId,
    localId: item.localId,
    syncStatus,
    payload: removeLocalId(item),
    createdAt: timestamp,
    updatedAt: timestamp,
  };
}

function buildMaoObraRecords(
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord[] {
  return draft.maoObra.map((item: MaoObraDraft) =>
    buildChildRecord(
      draft.id,
      "maoObra",
      item,
      syncStatus,
      timestamp,
    ),
  );
}

function buildEquipamentoRecords(
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord[] {
  return draft.equipamentos.map((item: EquipamentoDraft) =>
    buildChildRecord(
      draft.id,
      "equipamentos",
      item,
      syncStatus,
      timestamp,
    ),
  );
}

function buildMaterialRecords(
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord[] {
  return draft.materiais.map((item: MaterialDraft) =>
    buildChildRecord(
      draft.id,
      "materiais",
      item,
      syncStatus,
      timestamp,
    ),
  );
}

function buildControleGeometricoRecords(
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): LocalRdoChildRecord[] {
  return draft.controlesGeometricos.map(
    (item: ControleGeometricoDraft) =>
      buildChildRecord(
        draft.id,
        "controlesGeometricos",
        item,
        syncStatus,
        timestamp,
      ),
  );
}

async function replaceStoreRecords(
  store: RdoChildStoreWriter,
  rdoId: string,
  records: LocalRdoChildRecord[],
): Promise<void> {
  const existingKeys =
    await store.index("by-rdo-id").getAllKeys(rdoId);

  await Promise.all(
    existingKeys.map((key) => store.delete(key)),
  );

  await Promise.all(
    records.map((record) => store.put(record)),
  );
}

async function replaceChildRecords(
  transaction: RdoChildWriteTransaction,
  draft: RdoDraft,
  syncStatus: LocalSyncStatus,
  timestamp: string,
): Promise<void> {
  await Promise.all([
    replaceStoreRecords(
      transaction.objectStore("rdoMaoObra"),
      draft.id,
      buildMaoObraRecords(
        draft,
        syncStatus,
        timestamp,
      ),
    ),
    replaceStoreRecords(
      transaction.objectStore("rdoEquipamentos"),
      draft.id,
      buildEquipamentoRecords(
        draft,
        syncStatus,
        timestamp,
      ),
    ),
    replaceStoreRecords(
      transaction.objectStore("rdoMateriais"),
      draft.id,
      buildMaterialRecords(
        draft,
        syncStatus,
        timestamp,
      ),
    ),
    replaceStoreRecords(
      transaction.objectStore(
        "rdoControlesGeometricos",
      ),
      draft.id,
      buildControleGeometricoRecords(
        draft,
        syncStatus,
        timestamp,
      ),
    ),
  ]);
}

async function reopenUnsyncedRdoAttachments(
  transaction: RdoAttachmentWriteTransaction,
  rdoId: string,
  timestamp: string,
): Promise<void> {
  const store = transaction.objectStore("rdo_attachments");
  const attachments = await store.index("by-rdo-id").getAll(rdoId);
  await Promise.all(
    attachments
      .filter((attachment) => attachment.syncStatus !== "SYNCED")
      .map((attachment) =>
        store.put({
          ...attachment,
          syncStatus: "PENDING_SYNC",
          ultimoErro: null,
          updatedAt: timestamp,
        })
      ),
  );
}

export function validateRdoDraftForSync(draft: RdoDraft): void {
  if (!draft.id.trim()) {
    throw new Error(
      "O RDO precisa ter um ID local.",
    );
  }

  if (!draft.obraId.trim()) {
    throw new Error("A obra é obrigatória.");
  }

  if (!draft.dataRdo.trim()) {
    throw new Error("A data do RDO é obrigatória.");
  }

  if (
    (draft.attachments ?? []).filter((item) => item.removedAt === null)
      .length > 5
  ) {
    throw new Error("O limite é de 5 fotos por RDO.");
  }

  validateKmRange(
    draft.kmInicialProgramado,
    draft.kmFinalProgramado,
    "trecho programado",
  );
  validateKmRange(
    draft.kmInicialInterditado,
    draft.kmFinalInterditado,
    "trecho interditado",
  );

  draft.controlesGeometricos.forEach((item, index) => {
    validateKmRange(
      item.kmInicial,
      item.kmFinal,
      `controle geométrico ${index + 1}`,
    );
  });

  draft.servicosExecutados.forEach((item, index) => {
    const quantidadeExecutada = numberFromInput(
      item.quantidadeExecutada,
    );

    if (
      quantidadeExecutada !== null &&
      quantidadeExecutada < 0
    ) {
      throw new Error(
        `A quantidade executada do serviço ${index + 1} deve ser maior ou igual a zero.`,
      );
    }
  });

  const selectedCollaboratorIds = draft.maoObra
    .filter((item) => item.selected && item.colaboradorId.trim())
    .map((item) => item.colaboradorId.trim());
  if (
    draft.maoObra.some(
      (item) =>
        item.selected &&
        !item.colaboradorId.trim() &&
        !item.nomeColaborador.trim(),
    )
  ) {
    throw new Error("Informe o nome da mão de obra manual.");
  }
  if (
    draft.maoObra.some(
      (item) =>
        item.selected &&
        !item.colaboradorId.trim() &&
        item.nomeColaborador.trim().length > 255,
    )
  ) {
    throw new Error(
      "O nome da mão de obra manual deve ter no máximo 255 caracteres.",
    );
  }
  if (new Set(selectedCollaboratorIds).size !== selectedCollaboratorIds.length) {
    throw new Error("A equipe do RDO contém colaborador duplicado.");
  }
  if (
    draft.maoObra.some(
      (item) => item.selected && item.availability === "UNAVAILABLE",
    )
  ) {
    throw new Error("Colaborador indisponível não pode ser selecionado.");
  }
  if (
    draft.apontadorColaboradorId.trim() &&
    !selectedCollaboratorIds.includes(draft.apontadorColaboradorId.trim())
  ) {
    throw new Error("O apontador deve estar selecionado na equipe do RDO.");
  }

  const requiresCaixa = draft.servicosExecutados.some((item) =>
    item.servicoNome.toLowerCase().includes("caixa"),
  );

  if (
    requiresCaixa &&
    !draft.controlesGeometricos.some(
      (item) => item.numero.trim() || item.subtrecho.trim(),
    )
  ) {
    throw new Error(
      "Informe a caixa no controle geométrico para serviços que exigem caixa.",
    );
  }
}

function validateKmRange(
  initialValue: string,
  finalValue: string,
  label: string,
): void {
  const initial = numberFromText(initialValue);
  const final = numberFromText(finalValue);

  if (initial === null || final === null) {
    return;
  }

  if (final < initial) {
    throw new Error(
      `O KM final do ${label} não pode ser menor que o KM inicial.`,
    );
  }
}

export async function saveNewRdoDraftAtomically(
  draft: RdoDraft,
  options: { occurredAt?: string } = {},
): Promise<SaveRdoDraftResult> {
  validateRdoDraftForSync(draft);
  const contextGuard = await requireExactRdoCreationContext(draft);
  assertContextSession(contextGuard);

  const database = await getCortexDb();
  assertContextSession(contextGuard);
  const timestamp = options.occurredAt ?? nowUtc();
  const localPayload = buildRdoLocalPayload(draft);
  const syncPayload = buildRdoSyncPayload(draft);

  const rdo: LocalRdoRecord = {
    id: draft.id,
    obraId: draft.obraId,
    programacaoId:
      draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: localPayload,
    createdAt: timestamp,
    updatedAt: timestamp,
  };

  const session = contextGuard.session;
  const state = await getSyncState();
  assertContextSession(contextGuard);
  const uuidPattern =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  const deviceId =
    state.usuarioId === session.colaboradorId &&
    state.deviceId &&
    uuidPattern.test(state.deviceId)
      ? state.deviceId
      : crypto.randomUUID();
  if (state.deviceId !== deviceId || state.usuarioId !== session.colaboradorId) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
    assertContextSession(contextGuard);
  }

  let dependsOnMutationIds: string[] = [];
  if (draft.previousRdoId.trim()) {
    const sourceMutations = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      draft.previousRdoId,
    );
    assertContextSession(contextGuard);
    const dependency = sourceMutations
      .filter(
        (candidate) =>
          candidate.entidadeTipo === "RDO" &&
          candidate.operacao === "CRIAR_RDO" &&
          candidate.status !== "SYNCED",
      )
      .sort((left, right) =>
        right.criadaNoClienteEm.localeCompare(left.criadaNoClienteEm),
      )[0];
    if (dependency) dependsOnMutationIds = [dependency.clientMutationId];
  }

  type RdoCreationStore =
    | "rdos"
    | "rdoMaoObra"
    | "rdoEquipamentos"
    | "rdoMateriais"
    | "rdoControlesGeometricos";
  const childWrites: LocalMutationDomainWrite<RdoCreationStore>[] = [
    ...buildMaoObraRecords(draft, "PENDING_SYNC", timestamp).map(
      (value) => ({ store: "rdoMaoObra" as const, value }),
    ),
    ...buildEquipamentoRecords(draft, "PENDING_SYNC", timestamp).map(
      (value) => ({ store: "rdoEquipamentos" as const, value }),
    ),
    ...buildMaterialRecords(draft, "PENDING_SYNC", timestamp).map(
      (value) => ({ store: "rdoMateriais" as const, value }),
    ),
    ...buildControleGeometricoRecords(draft, "PENDING_SYNC", timestamp).map(
      (value) => ({ store: "rdoControlesGeometricos" as const, value }),
    ),
  ];
  assertContextSession(contextGuard);
  const committed = await commitLocalMutation<RdoCreationStore>({
    deviceId,
    userId: session.colaboradorId,
    obraId: draft.obraId,
    entityType: "RDO",
    entityId: draft.id,
    entityName: draft.numeroRdo.trim() || null,
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: timestamp,
    previousSnapshot: {},
    nextSnapshot: syncPayload,
    principalSnapshot: rdo as unknown as Record<string, unknown>,
    eventType: "RDO_CRIADO",
    relatedEntities: [
      {
        tipo: "OBRA",
        id: draft.obraId,
        nome: entityName(draft.contrato) ?? entityName(draft.cliente),
      },
    ],
    dependsOnMutationIds,
    write: () => [
      {
        store: "rdos",
        value: rdo,
        principal: true,
        insertOnly: true,
      },
      ...childWrites,
    ],
  });
  assertContextSession(contextGuard);

  return {
    rdo,
    mutation: committed.mutation,
  };
}

export async function saveLocalPendingRdoDraftAtomically(
  draft: RdoDraft,
  options: { occurredAt?: string } = {},
): Promise<SaveRdoDraftResult> {
  validateRdoDraftForSync(draft);
  if (rdoCreationContextBlockReason(draft) === null) {
    throw new Error(
      "Rascunho local pendente não pode declarar um receipt canônico.",
    );
  }

  const contextGuard = captureContextSession();
  const session = contextGuard.session;
  const timestamp = options.occurredAt ?? nowUtc();
  const pendingDraft: RdoDraft = {
    ...draft,
    creationContextVersion: null,
    syncStatus: "LOCAL_PENDING",
  };
  const localPayload = buildRdoLocalPayload(pendingDraft);
  const syncPayload = buildRdoSyncPayload(pendingDraft);
  const rdo: LocalRdoRecord = {
    id: pendingDraft.id,
    obraId: pendingDraft.obraId,
    programacaoId: pendingDraft.programacaoId || null,
    numeroRdo: pendingDraft.numeroRdo,
    dataRdo: pendingDraft.dataRdo,
    statusRdo: "RASCUNHO",
    syncStatus: "LOCAL_PENDING",
    versaoEntidade: null,
    payload: localPayload,
    createdAt: timestamp,
    updatedAt: timestamp,
  };

  const database = await getCortexDb();
  assertContextSession(contextGuard);
  const state = await getSyncState();
  assertContextSession(contextGuard);
  const uuidPattern =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  const deviceId =
    state.usuarioId === session.colaboradorId &&
    state.deviceId &&
    uuidPattern.test(state.deviceId)
      ? state.deviceId
      : crypto.randomUUID();
  if (state.deviceId !== deviceId || state.usuarioId !== session.colaboradorId) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
    assertContextSession(contextGuard);
  }

  let dependsOnMutationIds: string[] = [];
  if (pendingDraft.previousRdoId.trim()) {
    const sourceMutations = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      pendingDraft.previousRdoId,
    );
    assertContextSession(contextGuard);
    const dependency = sourceMutations
      .filter(
        (candidate) =>
          candidate.entidadeTipo === "RDO" &&
          candidate.operacao === "CRIAR_RDO" &&
          candidate.status !== "SYNCED",
      )
      .sort((left, right) =>
        right.criadaNoClienteEm.localeCompare(left.criadaNoClienteEm),
      )[0];
    if (dependency) dependsOnMutationIds = [dependency.clientMutationId];
  }

  type RdoLocalPendingStore =
    | "rdos"
    | "rdoMaoObra"
    | "rdoEquipamentos"
    | "rdoMateriais"
    | "rdoControlesGeometricos";
  const childWrites: LocalMutationDomainWrite<RdoLocalPendingStore>[] = [
    ...buildMaoObraRecords(pendingDraft, "LOCAL_PENDING", timestamp).map(
      (value) => ({ store: "rdoMaoObra" as const, value }),
    ),
    ...buildEquipamentoRecords(pendingDraft, "LOCAL_PENDING", timestamp).map(
      (value) => ({ store: "rdoEquipamentos" as const, value }),
    ),
    ...buildMaterialRecords(pendingDraft, "LOCAL_PENDING", timestamp).map(
      (value) => ({ store: "rdoMateriais" as const, value }),
    ),
    ...buildControleGeometricoRecords(
      pendingDraft,
      "LOCAL_PENDING",
      timestamp,
    ).map(
      (value) => ({ store: "rdoControlesGeometricos" as const, value }),
    ),
  ];
  const committed = await commitLocalMutation<RdoLocalPendingStore>({
    deviceId,
    userId: session.colaboradorId,
    obraId: pendingDraft.obraId,
    entityType: "RDO",
    entityId: pendingDraft.id,
    entityName: pendingDraft.numeroRdo.trim() || null,
    operation: "CREATE",
    transportOperation: "CRIAR_RDO",
    baseVersion: null,
    occurredAt: timestamp,
    previousSnapshot: {},
    nextSnapshot: syncPayload,
    principalSnapshot: rdo as unknown as Record<string, unknown>,
    eventType: "RDO_CRIADO",
    relatedEntities: [
      {
        tipo: "OBRA",
        id: pendingDraft.obraId,
        nome: entityName(pendingDraft.contrato) ??
          entityName(pendingDraft.cliente),
      },
    ],
    dependsOnMutationIds,
    initialBlockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
    write: () => [
      {
        store: "rdos",
        value: rdo,
        principal: true,
        insertOnly: true,
      },
      ...childWrites,
    ],
  });
  assertContextSession(contextGuard);
  return { rdo, mutation: committed.mutation };
}

async function keepRdoContextHydrationRetryable(
  clientMutationId: string,
  guard: SyncSessionGuard,
  timestamp: string,
): Promise<void> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const guardedTransaction = guardSyncTransaction(
    database.transaction(["outbox_mutations"], "readwrite"),
    guard,
  );
  const store = guardedTransaction.transaction.objectStore(
    "outbox_mutations",
  );
  const current = await store.get(clientMutationId);
  if (current &&
      current.entidadeTipo === "RDO" &&
      current.operacao === "CRIAR_RDO" &&
      current.status !== "SYNCED" &&
      (
        !isCanonicalOutboxMutation(current) ||
        current.blockedReason === "RDO_CREATION_CONTEXT_REQUIRED"
      )) {
    await store.put({
      ...current,
      status: "PENDING",
      ultimoErro:
        "Contexto da obra indisponível; a sincronização tentará novamente.",
      conflito: null,
      blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
      nextAttemptAt: new Date(
        Date.parse(timestamp) + 60_000,
      ).toISOString(),
      updatedAt: timestamp,
    });
  }
  await guardedTransaction.complete();
}

export async function hydrateBlockedRdoCreationContextsForSync(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const candidates = [
    ...(await database.getAllFromIndex(
      "outbox_mutations",
      "by-status",
      "PENDING",
    )),
    ...(await database.getAllFromIndex(
      "outbox_mutations",
      "by-status",
      "ERROR",
    )),
  ].filter((mutation) =>
    mutation.entidadeTipo === "RDO" &&
    mutation.operacao === "CRIAR_RDO" &&
    (
      !isCanonicalOutboxMutation(mutation) ||
      mutation.blockedReason === "RDO_CREATION_CONTEXT_REQUIRED"
    )
  );
  const seenRdoIds = new Set<string>();
  let hydrated = 0;

  for (const mutation of candidates) {
    assertSyncSession(guard);
    if (seenRdoIds.has(mutation.entidadeId)) {
      continue;
    }
    seenRdoIds.add(mutation.entidadeId);
    const rdo = await database.get("rdos", mutation.entidadeId);
    if (!rdo || rdo.syncStatus === "SYNCED") {
      continue;
    }
    if (mutation.blockedReason !== "RDO_CREATION_CONTEXT_REQUIRED" &&
        rdoCreationContextBlockReason(rdoDraftFromLocalRecord(rdo)) === null) {
      continue;
    }

    const timestamp = nowUtc();
    let context: RdoCreationContextLookup;
    try {
      context = await buscarContextoDeCriacaoRdo(
        rdo.obraId,
        rdo.dataRdo,
      );
    } catch {
      assertSyncSession(guard);
      await keepRdoContextHydrationRetryable(
        mutation.clientMutationId,
        guard,
        timestamp,
      );
      continue;
    }
    assertSyncSession(guard);

    const cache = durableCreationContextCache(context, rdo, timestamp);
    if (!cache) {
      await keepRdoContextHydrationRetryable(
        mutation.clientMutationId,
        guard,
        timestamp,
      );
      continue;
    }

    const guardedTransaction = guardSyncTransaction(
      database.transaction(
        ["rdos", "outbox_mutations"],
        "readwrite",
      ),
      guard,
    );
    const transaction = guardedTransaction.transaction;
    const rdoStore = transaction.objectStore("rdos");
    const outboxStore = transaction.objectStore("outbox_mutations");
    const currentRdo = await rdoStore.get(rdo.id);
    const currentMutation = await outboxStore.get(
      mutation.clientMutationId,
    );

    if (!currentRdo ||
        currentRdo.obraId !== cache.worksiteId ||
        currentRdo.dataRdo !== cache.selectedDate ||
        !currentMutation ||
        currentMutation.entidadeId !== currentRdo.id ||
        currentMutation.operacao !== "CRIAR_RDO" ||
        currentMutation.status === "SYNCED" ||
        (
          isCanonicalOutboxMutation(currentMutation) &&
          currentMutation.blockedReason !==
            "RDO_CREATION_CONTEXT_REQUIRED"
        )) {
      await guardedTransaction.complete();
      continue;
    }

    const hydratedRdo: LocalRdoRecord = {
      ...currentRdo,
      payload: {
        ...currentRdo.payload,
        previousRdoId: cache.previousRdoId,
        creationContextVersion: cache.receiptVersion,
        creationContextCache: cache,
      },
      updatedAt: timestamp,
    };
    await rdoStore.put(hydratedRdo);
    await guardedTransaction.complete();

    if (isCanonicalOutboxMutation(currentMutation)) {
      assertSyncSession(guard);
      const draft = rdoDraftFromLocalRecord(hydratedRdo);
      const pendingOperationalEvents = (
        await queryOperationalEvents({
          rdoId: hydratedRdo.id,
          limit: 500,
        })
      ).filter((event) => event.syncStatus !== "SYNCED");
      assertSyncSession(guard);
      await replacePendingRdoCreate({
        draft,
        existingRdo: hydratedRdo,
        original: currentMutation,
        localPayload: {
          ...buildRdoLocalPayload(draft),
          creationContextCache: cache,
        },
        pendingOperationalEvents,
        timestamp,
      });
      assertSyncSession(guard);
    }
    hydrated += 1;
  }

  return hydrated;
}

export async function repairRdoCreateMutationsForSync(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const candidates = [
    ...(await database.getAllFromIndex(
      "outbox_mutations",
      "by-status",
      "PENDING",
    )),
    ...(await database.getAllFromIndex(
      "outbox_mutations",
      "by-status",
      "ERROR",
    )),
  ];

  let repaired = 0;

  for (const candidate of candidates) {
    assertSyncSession(guard);
    if (isCanonicalOutboxMutation(candidate)) {
      continue;
    }
    if (
      candidate.entidadeTipo !== "RDO" ||
      candidate.operacao !== "CRIAR_RDO"
    ) {
      continue;
    }

    const mutation = await database.get(
      "outbox_mutations",
      candidate.clientMutationId,
    );
    const rdo = await database.get("rdos", candidate.entidadeId);
    assertSyncSession(guard);
    if (
      !mutation ||
      isCanonicalOutboxMutation(mutation) ||
      mutation.entidadeTipo !== "RDO" ||
      mutation.operacao !== "CRIAR_RDO" ||
      !["PENDING", "ERROR"].includes(mutation.status)
    ) {
      continue;
    }
    if (!rdo || rdo.syncStatus === "SYNCED") {
      continue;
    }

    const draft = rdoDraftFromLocalRecord(rdo);
    const blockedReason = rdoCreationContextBlockReason(draft);
    const pendingOperationalEvents = (
      await queryOperationalEvents({
        rdoId: rdo.id,
        limit: 500,
      })
    ).filter((event) => event.syncStatus !== "SYNCED");
    assertSyncSession(guard);
    const timestamp = nowUtc();

    if (!canCoalesceLegacyRdoMutation(mutation, "CRIAR_RDO")) {
      const creationContextCache =
        typeof rdo.payload.creationContextCache === "object" &&
        rdo.payload.creationContextCache !== null
          ? { creationContextCache: rdo.payload.creationContextCache }
          : {};
      await replacePendingRdoCreate({
        draft,
        existingRdo: rdo,
        original: mutation,
        localPayload: {
          ...buildRdoLocalPayload(draft),
          ...creationContextCache,
        },
        pendingOperationalEvents,
        timestamp,
      });
      assertSyncSession(guard);
      repaired += 1;
      continue;
    }

    const guardedTransaction = guardSyncTransaction(
      database.transaction(
        [
          "rdos",
          "outbox_mutations",
          "rdoMaoObra",
          "rdoEquipamentos",
          "rdoMateriais",
          "rdoControlesGeometricos",
        ],
        "readwrite",
      ),
      guard,
    );
    const transaction = guardedTransaction.transaction;
    const outboxStore = transaction.objectStore("outbox_mutations");
    const rdoStore = transaction.objectStore("rdos");
    const currentMutation = await outboxStore.get(
      mutation.clientMutationId,
    );
    const currentRdo = await rdoStore.get(rdo.id);

    if (
      !currentMutation ||
      !currentRdo ||
      !canCoalesceLegacyRdoMutation(currentMutation, "CRIAR_RDO")
    ) {
      await guardedTransaction.complete();
      continue;
    }

    await outboxStore.put({
      ...currentMutation,
      payload: buildRdoSyncPayload(draft, pendingOperationalEvents),
      status: "PENDING",
      ultimoErro: null,
      conflito: null,
      blockedReason,
      nextAttemptAt: null,
      updatedAt: timestamp,
    });
    await rdoStore.put({
      ...currentRdo,
      syncStatus: "PENDING_SYNC",
      updatedAt: timestamp,
    });
    await replaceChildRecords(
      transaction,
      draft,
      "PENDING_SYNC",
      timestamp,
    );
    await guardedTransaction.complete();

    repaired += 1;
  }

  return repaired;
}

const RDO_WORKFORCE_LINK_REJECTION =
  "colaborador nao esta ativo e vinculado a obra do rdo";

export interface RdoRejectedMutationRecoveryOptions {
  now?: () => string;
  clientMutationIdFactory?: () => string;
  ontologyEventIdFactory?: () => string;
  correctiveOperationalEventIdFactory?: () => string;
  recoveredReplacementIds?: Set<string>;
  recoveredReplacementByOriginalId?: Map<string, string>;
  loadAuthorizedCollaboratorIds?: (
    obraId: string,
    dataRdo: string,
  ) => Promise<ReadonlySet<string>>;
  lookupAuthoritativeRdo?: (
    rdoId: string,
  ) => Promise<AuthoritativeRdoLookup>;
  executionLease?: SyncExecutionLease;
}

export class RdoWorkforceContextUnverifiedError extends Error {
  constructor() {
    super("O contexto atual de colaboradores está parcial ou incompatível.");
    this.name = "RdoWorkforceContextUnverifiedError";
  }
}

function isPermanentRecoveryLookupError(error: unknown): boolean {
  return error instanceof RdoLookupHttpError &&
    error.status >= 400 &&
    error.status < 500 &&
    ![401, 403, 408, 425, 429].includes(error.status);
}

function normalizedServerMessage(value: string | null): string {
  return (value ?? "")
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[.\s]+$/g, "")
    .trim();
}

function isRecoverableRejectedRdoMutation(
  mutation: OutboxMutationRecord,
): mutation is CanonicalOutboxMutationRecord {
  if (
    !isCanonicalOutboxMutation(mutation) ||
    mutation.entidadeTipo !== "RDO" ||
    mutation.status !== "REJECTED"
  ) {
    return false;
  }
  const supportedRdoOperation =
    (
      mutation.operation === "CREATE" &&
      mutation.operacao === "CRIAR_RDO"
    ) ||
    (
      mutation.operation === "UPDATE" &&
      mutation.operacao === "ATUALIZAR_RDO_RASCUNHO"
    );
  if (!supportedRdoOperation) return false;
  return mutation.lastSafeCode === "IDEMPOTENCY_MISMATCH" ||
    (
      mutation.lastSafeCode === "VALIDATION_OR_AUTHORIZATION" &&
      normalizedServerMessage(mutation.ultimoErro) ===
        RDO_WORKFORCE_LINK_REJECTION
    );
}

async function currentAuthorizedCollaboratorIds(
  obraId: string,
): Promise<ReadonlySet<string>> {
  let collaborators;
  try {
    collaborators = await buscarColaboradoresAutorizadosDaObra(obraId);
  } catch (error: unknown) {
    if (error instanceof RdoLookupPayloadError) {
      throw new RdoWorkforceContextUnverifiedError();
    }
    throw error;
  }
  if (!collaborators.complete) {
    throw new RdoWorkforceContextUnverifiedError();
  }
  return new Set(collaborators.ids);
}

function repairInvalidWorkforceLinks(
  draft: RdoDraft,
  authorizedIds: ReadonlySet<string>,
): {
  draft: RdoDraft;
  changed: boolean;
  hasInvalidIds: boolean;
  unresolvedInvalidIds: string[];
  invalidStructuredAllocationIds: string[];
  repairedNominalWorkforceLinks:
    readonly RepairedNominalWorkforceLink[];
} {
  const referencedIds = new Set<string>();
  for (const item of draft.maoObra) {
    if (item.selected && item.colaboradorId.trim()) {
      referencedIds.add(item.colaboradorId.trim());
    }
  }
  for (const item of draft.alocacoesColaboradores) {
    if (item.colaboradorId.trim()) {
      referencedIds.add(item.colaboradorId.trim());
    }
  }
  if (draft.apontadorColaboradorId.trim()) {
    referencedIds.add(draft.apontadorColaboradorId.trim());
  }
  const invalidIds = new Set(
    [...referencedIds].filter((id) => !authorizedIds.has(id)),
  );
  const invalidStructuredAllocationIds = [
    ...new Set(
      draft.alocacoesColaboradores
        .map((item) => item.colaboradorId.trim())
        .filter((id) => id && invalidIds.has(id)),
    ),
  ];
  const unresolvedInvalidIds = new Set<string>();
  const repairedNominalWorkforceLinks:
    RepairedNominalWorkforceLink[] = [];
  const maoObra = draft.maoObra.map((item) => {
    const collaboratorId = item.colaboradorId.trim();
    if (
      !item.selected ||
      !collaboratorId ||
      !invalidIds.has(collaboratorId)
    ) {
      return item;
    }
    if (!item.nomeColaborador.trim()) {
      unresolvedInvalidIds.add(collaboratorId);
      return item;
    }
    const localId = item.localId.trim();
    if (localId) {
      repairedNominalWorkforceLinks.push({
        localId,
        collaboratorId,
        collaboratorName: item.nomeColaborador.trim(),
      });
    }
    return {
      ...item,
      origemItemId: "",
      sourceRdoId: "",
      origin: "MANUAL" as const,
      availability: "UNKNOWN" as const,
      colaboradorId: "",
    };
  });
  const clearsApontador = invalidIds.has(
    draft.apontadorColaboradorId.trim(),
  );
  const changed =
    invalidIds.size > 0 &&
    unresolvedInvalidIds.size === 0 &&
    (
      maoObra.some((item, index) => item !== draft.maoObra[index]) ||
      clearsApontador
    );
  return {
    draft: changed
      ? {
          ...draft,
          maoObra,
          apontadorColaboradorId: clearsApontador
            ? ""
            : draft.apontadorColaboradorId,
          apontadorRdo: draft.apontadorRdo,
        }
      : draft,
    changed,
    hasInvalidIds: invalidIds.size > 0,
    unresolvedInvalidIds: [...unresolvedInvalidIds],
    invalidStructuredAllocationIds,
    repairedNominalWorkforceLinks,
  };
}

interface WorkforceOperationalEventRecovery {
  events: unknown[];
  corrections: readonly WorkforceOperationalEventCorrection[];
  invalidReason: string | null;
}

interface WorkforceOperationalEventCorrection {
  originalEventId: string;
  correctiveEventId: string;
  originalSerializedEvent: Readonly<Record<string, unknown>>;
  type: OperationalEventRecord["type"];
  colaboradorId: null;
  principalEntity: OperationalEntityRef;
  payload: Record<string, unknown>;
}

interface RepairedNominalWorkforceLink {
  localId: string;
  collaboratorId: string;
  collaboratorName: string;
}

function objectRecord(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null &&
      !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function nonBlankText(value: unknown): string | null {
  if (typeof value !== "string") return null;
  const text = value.trim();
  return text || null;
}

function serializeOperationalEventForTransport(
  event: OperationalEventRecord,
): Record<string, unknown> {
  return {
    id: event.id,
    type: event.type,
    principalEntity: event.principalEntity,
    relatedEntities: event.relatedEntities,
    obraId: event.obraId,
    rdoId: event.rdoId,
    colaboradorId: event.colaboradorId,
    occurredAt: event.occurredAt,
    syncedAt: event.syncedAt,
    origin: event.origin,
    responsibleUserId: event.responsibleUserId,
    responsibleUserName: event.responsibleUserName,
    payload: event.payload,
    syncStatus: event.syncStatus,
    schemaVersion: event.schemaVersion,
  };
}

function recoverWorkforceOperationalEvents(
  payload: Readonly<Record<string, unknown>>,
  authorizedIds: ReadonlySet<string>,
  repairedNominalWorkforceLinks:
    readonly RepairedNominalWorkforceLink[],
  occurredAt: string,
  correctiveEventIdFactory: () => string,
): WorkforceOperationalEventRecovery {
  const sourceEvents = Array.isArray(payload.operationalEvents)
    ? payload.operationalEvents
    : [];
  const events: unknown[] = [];
  const corrections: WorkforceOperationalEventCorrection[] = [];
  const sourceEventIds = sourceEvents.flatMap((sourceEvent) => {
    const eventId = nonBlankText(objectRecord(sourceEvent)?.id);
    return eventId ? [eventId] : [];
  });
  const reservedEventIds = new Set(sourceEventIds);
  if (reservedEventIds.size !== sourceEventIds.length) {
    return {
      events: [],
      corrections: [],
      invalidReason:
        "Há IDs duplicados nos eventos operacionais do RDO.",
    };
  }

  const appendCorrection = (
    event: Record<string, unknown>,
    originalEventId: string | null,
    type: OperationalEventRecord["type"],
    principalEntity: OperationalEntityRef,
    correctionPayload: Record<string, unknown>,
  ): string | null => {
    if (!originalEventId) {
      return "Evento operacional sem ID não pode ser corrigido com rastreabilidade.";
    }
    const correctiveEventId = nonBlankText(
      correctiveEventIdFactory(),
    );
    if (
      !correctiveEventId ||
      reservedEventIds.has(correctiveEventId)
    ) {
      return "O ID do evento operacional corretivo é inválido ou duplicado.";
    }
    reservedEventIds.add(correctiveEventId);
    const causalPayload = {
      ...correctionPayload,
      supersedesEventId: originalEventId,
      causationId: originalEventId,
      recoveryReason: "WORKFORCE_LINK_RECOVERED_AS_NOMINAL",
    };
    events.push({
      id: correctiveEventId,
      type,
      principalEntity,
      relatedEntities: event.relatedEntities,
      obraId: event.obraId,
      rdoId: event.rdoId,
      colaboradorId: null,
      occurredAt,
      syncedAt: null,
      origin: event.origin,
      responsibleUserId: event.responsibleUserId,
      responsibleUserName: event.responsibleUserName,
      payload: causalPayload,
      syncStatus: "PENDING_SYNC",
      schemaVersion: 1,
    });
    corrections.push({
      originalEventId,
      correctiveEventId,
      originalSerializedEvent: event,
      type,
      colaboradorId: null,
      principalEntity,
      payload: causalPayload,
    });
    return null;
  };

  for (const sourceEvent of sourceEvents) {
    const event = objectRecord(sourceEvent);
    const collaboratorId = nonBlankText(event?.colaboradorId);
    if (
      !event ||
      !collaboratorId ||
      authorizedIds.has(collaboratorId)
    ) {
      events.push(sourceEvent);
      continue;
    }

    const eventId = nonBlankText(event.id);
    const eventPayload = objectRecord(event.payload);
    const localId = nonBlankText(eventPayload?.localId);
    const collaboratorName =
      nonBlankText(eventPayload?.nomeColaborador);
    const principal = objectRecord(event.principalEntity);
    const repairedLink = repairedNominalWorkforceLinks.find(
      (link) =>
        link.localId === localId &&
        link.collaboratorId === collaboratorId &&
        link.collaboratorName === collaboratorName,
    );
    if (
      event.type !== "COLABORADOR_ASSOCIADO_RDO" ||
      !repairedLink ||
      principal?.tipo !== "COLABORADOR" ||
      nonBlankText(principal.id) !== collaboratorId
    ) {
      return {
        events: [],
        corrections: [],
        invalidReason:
          "O evento operacional não corresponde exatamente à mão de obra nominal reparada.",
      };
    }
    const principalEntity: OperationalEntityRef = {
      tipo: "RDO_MAO_OBRA",
      id: repairedLink.localId,
      nome: repairedLink.collaboratorName,
    };
    const invalidReason = appendCorrection(
      event,
      eventId,
      "COLABORADOR_ASSOCIADO_RDO",
      principalEntity,
      eventPayload ?? {},
    );
    if (invalidReason) {
      return { events: [], corrections: [], invalidReason };
    }
  }

  return {
    events,
    corrections,
    invalidReason: null,
  };
}

function replacementCanonicalEvent(
  original: CanonicalOperationalEventRecord,
  built: Awaited<ReturnType<typeof buildCanonicalMutation>>,
): CanonicalOperationalEventRecord {
  const { mutation } = built;
  return {
    ...original,
    id: mutation.trace.ontologyEventId,
    type: mutation.entityType === "RDO"
      ? mutation.operation === "CREATE"
        ? "RDO_CRIADO"
        : mutation.operation === "UPDATE"
          ? "RDO_EDITADO"
          : original.type
      : original.type,
    occurredAt: mutation.occurredAt,
    syncedAt: null,
    payload: built.nextSnapshot,
    syncStatus: "PENDING_SYNC",
    clientMutationId: mutation.clientMutationId,
    deviceId: mutation.deviceId,
    correlationId: mutation.correlationId,
    causationId: mutation.causationId,
    previousState: built.previousSnapshot,
    newState: built.nextSnapshot,
    result: "PENDING",
    errorCategory: null,
    entityVersion: mutation.baseVersion,
  };
}

function pendingCanonicalEvent(
  event: CanonicalOperationalEventRecord,
): CanonicalOperationalEventRecord {
  return {
    ...event,
    syncedAt: null,
    syncStatus: "PENDING_SYNC",
    result: "PENDING",
    errorCategory: null,
  };
}

function rewireLegacyDependencies(
  mutation: OutboxMutationRecord,
  replacements: ReadonlyMap<string, string>,
): OutboxMutationRecord | null {
  if (
    isCanonicalOutboxMutation(mutation) ||
    !["PENDING", "ERROR"].includes(mutation.status) ||
    !mutation.dependsOnMutationIds?.some((id) => replacements.has(id))
  ) {
    return null;
  }
  const dependsOnMutationIds = [
    ...new Set(
      mutation.dependsOnMutationIds.map((dependencyId) =>
        replacements.get(dependencyId) ?? dependencyId
      ),
    ),
  ];
  return {
    ...mutation,
    dependsOnMutationIds,
  };
}

interface RejectedRecoverySnapshot {
  mutation: CanonicalOutboxMutationRecord;
  event: CanonicalOperationalEventRecord;
}

interface TerminalizableRejectedRecoverySnapshot {
  mutation: CanonicalOutboxMutationRecord;
  event: CanonicalOperationalEventRecord | null;
}

interface RecoveryDecisionSnapshot {
  allMutations: readonly OutboxMutationRecord[];
  allEvents: readonly OperationalEventRecord[];
  rdoId?: string;
  rdo?: LocalRdoRecord | null;
}

async function assertRecoveryLease(
  lease: SyncExecutionLease | undefined,
): Promise<void> {
  if (lease) await lease.assertOwned();
}

async function assertRecoveryLeaseInTransaction(
  transaction: ReturnType<
    Awaited<ReturnType<typeof getCortexDb>>["transaction"]
  >,
  lease: SyncExecutionLease | undefined,
): Promise<void> {
  if (!lease) return;
  const state = await transaction.objectStore("sync_state").get("default");
  const durableLease = state?.syncExecutionLease;
  const expiry = durableLease
    ? Date.parse(durableLease.expiresAt)
    : Number.NaN;
  if (
    !durableLease ||
    durableLease.ownerToken !== lease.ownerToken ||
    !Number.isFinite(expiry) ||
    expiry <= Date.now()
  ) {
    transaction.abort();
    throw new SyncLeaseLostError();
  }
}

async function terminalizeRejectedRecovery(
  database: Awaited<ReturnType<typeof getCortexDb>>,
  guard: SyncSessionGuard,
  snapshots: readonly TerminalizableRejectedRecoverySnapshot[],
  safeCode: string,
  message: string,
  lease: SyncExecutionLease | undefined,
  decisionSnapshot?: RecoveryDecisionSnapshot,
): Promise<void> {
  if (snapshots.length === 0) return;
  await assertRecoveryLease(lease);
  const entityId = snapshots[0].mutation.entityId;
  const expectedEntityMutations = await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    entityId,
  );
  assertSyncSession(guard);
  await assertRecoveryLease(lease);
  const guarded = guardSyncTransaction(
    database.transaction(
      ["outbox_mutations", "operational_events", "rdos", "sync_state"],
      "readwrite",
    ),
    guard,
  );
  const transaction = guarded.transaction;
  const outbox = transaction.objectStore("outbox_mutations");
  const events = transaction.objectStore("operational_events");
  const currentEntityMutations = await outbox
    .index("by-entity-id")
    .getAll(entityId);
  if (
    canonicalMutationJson(currentEntityMutations) !==
      canonicalMutationJson(expectedEntityMutations)
  ) {
    await guarded.complete();
    return;
  }
  if (
    decisionSnapshot &&
    (
      canonicalMutationJson(await outbox.getAll()) !==
        canonicalMutationJson(decisionSnapshot.allMutations) ||
      canonicalMutationJson(await events.getAll()) !==
        canonicalMutationJson(decisionSnapshot.allEvents)
      ||
      (
        decisionSnapshot.rdoId !== undefined &&
        canonicalMutationJson(
          (await transaction.objectStore("rdos").get(
            decisionSnapshot.rdoId,
          )) ?? null,
        ) !== canonicalMutationJson(decisionSnapshot.rdo ?? null)
      )
    )
  ) {
    await guarded.complete();
    return;
  }
  for (const snapshot of snapshots) {
    const current = await outbox.get(snapshot.mutation.clientMutationId);
    const currentEvent = snapshot.event
      ? await events.get(snapshot.event.id)
      : null;
    if (
      !current ||
      canonicalMutationJson(current) !==
        canonicalMutationJson(snapshot.mutation) ||
      (
        snapshot.event &&
        (
          !currentEvent ||
          canonicalMutationJson(currentEvent) !==
            canonicalMutationJson(snapshot.event)
        )
      )
    ) {
      await guarded.complete();
      return;
    }
  }
  await assertRecoveryLeaseInTransaction(transaction, lease);
  const timestamp = nowUtc();
  for (const snapshot of snapshots) {
    await outbox.put({
      ...snapshot.mutation,
      status: "REJECTED",
      nextAttemptAt: null,
      lastSafeCode: safeCode,
      blockedReason: safeCode,
      ultimoErro: message,
      updatedAt: timestamp,
    });
    if (snapshot.event) {
      await events.put({
        ...snapshot.event,
        result: "REJECTED",
        syncStatus: "SYNC_FAILED",
        errorCategory: safeCode,
      });
    }
  }
  await guarded.complete();
}

async function deferRejectedRecovery(
  database: Awaited<ReturnType<typeof getCortexDb>>,
  guard: SyncSessionGuard,
  snapshot: RejectedRecoverySnapshot,
  deferredAt: string,
  lease: SyncExecutionLease | undefined,
): Promise<void> {
  await assertRecoveryLease(lease);
  const guarded = guardSyncTransaction(
    database.transaction(
      ["outbox_mutations", "sync_state"],
      "readwrite",
    ),
    guard,
  );
  const transaction = guarded.transaction;
  const outbox = transaction.objectStore("outbox_mutations");
  const current = await outbox.get(snapshot.mutation.clientMutationId);
  if (
    !current ||
    canonicalMutationJson(current) !==
      canonicalMutationJson(snapshot.mutation)
  ) {
    await guarded.complete();
    return;
  }
  await assertRecoveryLeaseInTransaction(transaction, lease);
  const parsed = Date.parse(deferredAt);
  const baseTime = Number.isFinite(parsed) ? parsed : Date.now();
  const attempts = snapshot.mutation.tentativas + 1;
  const delay = Math.min(
    60 * 60 * 1000,
    60_000 * (2 ** Math.min(Math.max(attempts - 1, 0), 6)),
  );
  await outbox.put({
    ...snapshot.mutation,
    tentativas: attempts,
    nextAttemptAt: new Date(baseTime + delay).toISOString(),
    updatedAt: deferredAt,
  });
  await guarded.complete();
}

async function canonicalEventSnapshot(
  database: Awaited<ReturnType<typeof getCortexDb>>,
  mutation: CanonicalOutboxMutationRecord,
): Promise<CanonicalOperationalEventRecord | null> {
  const events = await database.getAllFromIndex(
    "operational_events",
    "by-client-mutation-id",
    mutation.clientMutationId,
  );
  return events.length === 1 && events[0].schemaVersion === 13
    ? events[0] as CanonicalOperationalEventRecord
    : null;
}

function canonicalEventFromSnapshot(
  events: readonly OperationalEventRecord[],
  mutation: CanonicalOutboxMutationRecord,
): CanonicalOperationalEventRecord | null {
  const matches = events.filter(
    (event) =>
      event.clientMutationId === mutation.clientMutationId,
  );
  return matches.length === 1 && matches[0].schemaVersion === 13
    ? matches[0] as CanonicalOperationalEventRecord
    : null;
}

function affectedCanonicalDependents(
  allMutations: readonly OutboxMutationRecord[],
  rootIds: ReadonlySet<string>,
): CanonicalOutboxMutationRecord[] {
  const maximumDependents = 64;
  const allDependentIds = allDependentMutationIds(
    allMutations,
    rootIds,
  );
  if (allDependentIds.size > maximumDependents) {
    throw new Error(
      "A cadeia de dependências excede o limite seguro de recuperação.",
    );
  }
  const allMutationsById = new Map(
    allMutations.map((mutation) => [
      mutation.clientMutationId,
      mutation,
    ]),
  );
  const allGraphIds = new Set([...rootIds, ...allDependentIds]);
  const unresolvedGraph = new Set(allGraphIds);
  const resolvedGraph = new Set<string>();
  for (const mutationId of allGraphIds) {
    const mutation = allMutationsById.get(mutationId);
    if (!mutation) {
      throw new Error("A cadeia de dependências possui nó ausente.");
    }
    for (const dependencyId of mutation.dependsOnMutationIds ?? []) {
      if (dependencyId === mutationId) {
        throw new Error("A cadeia de dependências contém autodependência.");
      }
      const dependency = allMutationsById.get(dependencyId);
      if (!dependency) {
        throw new Error("A cadeia de dependências possui dependência ausente.");
      }
      if (
        !allGraphIds.has(dependencyId) &&
        (
          dependency.status !== "SYNCED" ||
          (
            isCanonicalOutboxMutation(mutation) &&
            (
              !isCanonicalOutboxMutation(dependency) ||
              dependency.userId !== mutation.userId ||
              dependency.obraId !== mutation.obraId
            )
          )
        )
      ) {
        throw new Error(
          "A cadeia de dependências possui dependência externa não aplicada.",
        );
      }
    }
  }
  while (unresolvedGraph.size > 0) {
    const ready = [...unresolvedGraph].filter((mutationId) => {
      const mutation = allMutationsById.get(mutationId)!;
      return (mutation.dependsOnMutationIds ?? [])
        .filter((dependencyId) => allGraphIds.has(dependencyId))
        .every((dependencyId) => resolvedGraph.has(dependencyId));
    });
    if (ready.length === 0) {
      throw new Error("A cadeia de dependências contém um ciclo.");
    }
    for (const mutationId of ready) {
      unresolvedGraph.delete(mutationId);
      resolvedGraph.add(mutationId);
    }
  }
  const affected = new Set(rootIds);
  const result: CanonicalOutboxMutationRecord[] = [];
  let changed = true;
  while (changed) {
    changed = false;
    for (const mutation of allMutations) {
      if (
        !isCanonicalOutboxMutation(mutation) ||
        !["PENDING", "ERROR"].includes(mutation.status) ||
        affected.has(mutation.clientMutationId) ||
        !(mutation.dependsOnMutationIds ?? []).some(
          (id) => affected.has(id),
        )
      ) {
        continue;
      }
      affected.add(mutation.clientMutationId);
      result.push(mutation);
      if (result.length > maximumDependents) {
        throw new Error(
          "A cadeia de dependências excede o limite seguro de recuperação.",
        );
      }
      changed = true;
    }
  }
  const affectedIds = new Set([
    ...rootIds,
    ...result.map((mutation) => mutation.clientMutationId),
  ]);
  for (const descendantId of allDependentMutationIds(
    allMutations,
    rootIds,
  )) {
    const descendant = allMutations.find(
      (mutation) => mutation.clientMutationId === descendantId,
    );
    if (
      descendant &&
      isCanonicalOutboxMutation(descendant) &&
      !affectedIds.has(descendantId)
    ) {
      throw new Error(
        "A cadeia canônica atravessa uma dependência legada.",
      );
    }
  }
  const mutationsById = new Map(
    allMutations.map((mutation) => [
      mutation.clientMutationId,
      mutation,
    ]),
  );
  const roots = [...rootIds].map((rootId) => {
    const root = mutationsById.get(rootId);
    if (!root || !isCanonicalOutboxMutation(root)) {
      throw new Error("A raiz canônica da recuperação está ausente.");
    }
    return root;
  });
  const nodes = [...roots, ...result];
  for (const mutation of nodes) {
    for (const dependencyId of mutation.dependsOnMutationIds ?? []) {
      if (dependencyId === mutation.clientMutationId) {
        throw new Error("A cadeia canônica contém autodependência.");
      }
      const dependency = mutationsById.get(dependencyId);
      if (!dependency) {
        throw new Error("A cadeia canônica possui dependência ausente.");
      }
      if (
        !affectedIds.has(dependencyId) &&
        (
          !isCanonicalOutboxMutation(dependency) ||
          dependency.status !== "SYNCED" ||
          dependency.userId !== mutation.userId ||
          dependency.obraId !== mutation.obraId
        )
      ) {
        throw new Error(
          "A cadeia canônica possui dependência externa não aplicada.",
        );
      }
    }
  }
  const resolved = new Set<string>();
  const pending = new Map(
    nodes.map((mutation) => [mutation.clientMutationId, mutation]),
  );
  const ordered: CanonicalOutboxMutationRecord[] = [];
  while (pending.size > 0) {
    const ready = [...pending.values()].filter((mutation) =>
      (mutation.dependsOnMutationIds ?? [])
        .filter((id) => affectedIds.has(id))
        .every((id) => resolved.has(id))
    );
    if (ready.length === 0) {
      throw new Error(
        "A cadeia canônica de dependências contém um ciclo.",
      );
    }
    ready.sort((left, right) =>
      left.occurredAt.localeCompare(right.occurredAt)
    );
    for (const mutation of ready) {
      pending.delete(mutation.clientMutationId);
      resolved.add(mutation.clientMutationId);
      if (!rootIds.has(mutation.clientMutationId)) {
        ordered.push(mutation);
      }
    }
  }
  return ordered;
}

function allCanonicalDependents(
  allMutations: readonly OutboxMutationRecord[],
  rootIds: ReadonlySet<string>,
): CanonicalOutboxMutationRecord[] {
  const affected = new Set(rootIds);
  const dependents: CanonicalOutboxMutationRecord[] = [];
  let changed = true;
  while (changed) {
    changed = false;
    for (const mutation of allMutations) {
      if (
        !isCanonicalOutboxMutation(mutation) ||
        affected.has(mutation.clientMutationId) ||
        !(mutation.dependsOnMutationIds ?? []).some(
          (dependencyId) => affected.has(dependencyId),
        )
      ) {
        continue;
      }
      affected.add(mutation.clientMutationId);
      dependents.push(mutation);
      changed = true;
    }
  }
  return dependents;
}

function allDependentMutationIds(
  allMutations: readonly OutboxMutationRecord[],
  rootIds: ReadonlySet<string>,
): ReadonlySet<string> {
  const affected = new Set(rootIds);
  let changed = true;
  while (changed) {
    changed = false;
    for (const mutation of allMutations) {
      if (
        affected.has(mutation.clientMutationId) ||
        !(mutation.dependsOnMutationIds ?? []).some(
          (dependencyId) => affected.has(dependencyId),
        )
      ) {
        continue;
      }
      affected.add(mutation.clientMutationId);
      changed = true;
    }
  }
  for (const rootId of rootIds) affected.delete(rootId);
  return affected;
}

type RecoveryAncestryState = "CLEAR" | "RECOVERED" | "INVALID";

function localEditSupersededAncestorIds(
  mutation: CanonicalOutboxMutationRecord,
  entityMutations: readonly OutboxMutationRecord[],
): ReadonlySet<string> {
  const byId = new Map(
    entityMutations.map((candidate) => [
      candidate.clientMutationId,
      candidate,
    ]),
  );
  const ancestors = new Set<string>();
  let child = mutation;
  while (child.causationId && !ancestors.has(child.causationId)) {
    const ancestor = byId.get(child.causationId);
    if (
      !ancestor ||
      !isCanonicalOutboxMutation(ancestor) ||
      ancestor.userId !== mutation.userId ||
      ancestor.obraId !== mutation.obraId ||
      ancestor.status !== "REJECTED" ||
      ancestor.lastSafeCode !== "SUPERSEDED_BY_LOCAL_EDIT" ||
      ancestor.blockedReason !==
        `SUPERSEDED_BY:${child.clientMutationId}`
    ) {
      break;
    }
    ancestors.add(ancestor.clientMutationId);
    child = ancestor;
  }
  return ancestors;
}

function recoveryAncestryState(
  mutation: CanonicalOutboxMutationRecord,
  entityMutations: readonly OutboxMutationRecord[],
): RecoveryAncestryState {
  const byId = new Map(
    entityMutations.map((candidate) => [
      candidate.clientMutationId,
      candidate,
    ]),
  );
  const visited = new Set<string>();
  let ancestorId = mutation.causationId;
  while (ancestorId) {
    if (visited.has(ancestorId)) return "INVALID";
    visited.add(ancestorId);
    const ancestor = byId.get(ancestorId);
    if (!ancestor) return "INVALID";
    if (
      ancestor.lastSafeCode ===
        "SUPERSEDED_BY_IDEMPOTENCY_RECOVERY" ||
      ancestor.lastSafeCode ===
        "SUPERSEDED_BY_WORKFORCE_RECOVERY" ||
      ancestor.lastSafeCode ===
        "SUPERSEDED_BY_DEPENDENCY_REWIRE"
    ) {
      return "RECOVERED";
    }
    if (
      !isCanonicalOutboxMutation(ancestor) ||
      ancestor.userId !== mutation.userId ||
      ancestor.obraId !== mutation.obraId
    ) {
      return "INVALID";
    }
    ancestorId = ancestor.causationId;
  }
  return "CLEAR";
}

function syncedCanonicalCausalAncestorIds(
  mutation: CanonicalOutboxMutationRecord,
  entityMutations: readonly OutboxMutationRecord[],
): ReadonlySet<string> {
  if (mutation.operation !== "UPDATE") return new Set();
  const byId = new Map(
    entityMutations.map((candidate) => [
      candidate.clientMutationId,
      candidate,
    ]),
  );
  const ancestors = new Set<string>();
  const visited = new Set<string>();
  let ancestorId = mutation.causationId;
  while (ancestorId && !visited.has(ancestorId)) {
    visited.add(ancestorId);
    const ancestor = byId.get(ancestorId);
    if (
      !ancestor ||
      !isCanonicalOutboxMutation(ancestor) ||
      ancestor.entityId !== mutation.entityId ||
      ancestor.userId !== mutation.userId ||
      ancestor.obraId !== mutation.obraId
    ) {
      break;
    }
    if (
      ancestor.status === "SYNCED" &&
      ancestor.occurredAt <= mutation.occurredAt &&
      (
        (
          ancestor.operation === "CREATE" &&
          ancestor.operacao === "CRIAR_RDO"
        ) ||
        (
          ancestor.operation === "UPDATE" &&
          ancestor.operacao === "ATUALIZAR_RDO_RASCUNHO"
        )
      )
    ) {
      ancestors.add(ancestor.clientMutationId);
    }
    ancestorId = ancestor.causationId;
  }
  return ancestors;
}

export async function recoverRejectedRdoMutationsForSync(
  guard: SyncSessionGuard = captureOnlineSyncSession(),
  options: RdoRejectedMutationRecoveryOptions = {},
): Promise<number> {
  assertSyncSession(guard);
  const database = await getCortexDb();
  assertSyncSession(guard);
  const now = options.now ?? nowUtc;
  const scanTime = Date.parse(now());
  const candidates = (
    await database.getAllFromIndex(
      "outbox_mutations",
      "by-status",
      "REJECTED",
    )
  )
    .filter(isRecoverableRejectedRdoMutation)
    .filter((mutation) => {
      const nextAttempt = Date.parse(mutation.nextAttemptAt ?? "");
      return !Number.isFinite(nextAttempt) ||
        !Number.isFinite(scanTime) ||
        nextAttempt <= scanTime;
    })
    .sort((left, right) => right.occurredAt.localeCompare(left.occurredAt));
  const mutationIdFactory =
    options.clientMutationIdFactory ?? (() => crypto.randomUUID());
  const eventIdFactory =
    options.ontologyEventIdFactory ?? (() => crypto.randomUUID());
  const correctiveEventIdFactory =
    options.correctiveOperationalEventIdFactory ??
      (() => crypto.randomUUID());
  const loadAuthorizedIds =
    options.loadAuthorizedCollaboratorIds ??
      currentAuthorizedCollaboratorIds;
  const lookupAuthoritativeRdo =
    options.lookupAuthoritativeRdo ??
      buscarRdoAutoritativoPorId;
  let recovered = 0;
  const visitedEntities = new Set<string>();

  for (const candidate of candidates) {
    assertSyncSession(guard);
    if (visitedEntities.has(candidate.entityId)) continue;
    visitedEntities.add(candidate.entityId);
    const candidateMutationSnapshot = await database.getAll(
      "outbox_mutations",
    );
    const candidateEventSnapshot = await database.getAll(
      "operational_events",
    );
    const entityMutations = candidateMutationSnapshot.filter(
      (mutation) => mutation.entidadeId === candidate.entityId,
    );
    const rejectedMutations = entityMutations
      .filter(isRecoverableRejectedRdoMutation)
      .sort((left, right) =>
        right.occurredAt.localeCompare(left.occurredAt)
    );
    const original = rejectedMutations[0];
    if (!original || original.userId !== guard.userId) continue;
    const rootIds = new Set(
      rejectedMutations.map((mutation) => mutation.clientMutationId),
    );
    const rejectedSnapshots = rejectedMutations.map(
      (mutation) => ({
        mutation,
        event: canonicalEventFromSnapshot(
          candidateEventSnapshot,
          mutation,
        ),
      }),
    );
    if (rejectedMutations.length > 1) {
      const onlyCreates = rejectedMutations.every(
        (mutation) => mutation.operation === "CREATE",
      );
      await terminalizeRejectedRecovery(
        database,
        guard,
        rejectedSnapshots,
        onlyCreates
          ? "DUPLICATE_REJECTED_RDO_CREATE_REQUIRES_REVIEW"
          : "DUPLICATE_REJECTED_RDO_MUTATION_REQUIRES_REVIEW",
        onlyCreates
          ? "Há mais de um CREATE rejeitado para o mesmo RDO."
          : "Há mais de uma alteração rejeitada para o mesmo RDO.",
        options.executionLease,
        {
          allMutations: candidateMutationSnapshot,
          allEvents: candidateEventSnapshot,
        },
      );
      continue;
    }
    const originalEvent = rejectedSnapshots[0]?.event ?? null;
    const allMutations = candidateMutationSnapshot;
    const allEvents = candidateEventSnapshot;
    const rdo = await database.get("rdos", original.entidadeId);
    assertSyncSession(guard);
    if (
      !rdo ||
      !originalEvent ||
      rdo.id !== original.entityId ||
      rdo.obraId !== original.obraId
    ) {
      await terminalizeRejectedRecovery(
        database,
        guard,
        rejectedSnapshots,
        "RDO_RECOVERY_LOCAL_STATE_INVALID",
        "O RDO rejeitado não possui estado e evento locais únicos.",
        options.executionLease,
        {
          allMutations,
          allEvents,
          rdoId: original.entidadeId,
          rdo: rdo ?? null,
        },
      );
      continue;
    }
    const snapshot = { mutation: original, event: originalEvent };
    const recoveryDecisionSnapshot = {
      allMutations,
      allEvents,
      rdoId: original.entidadeId,
      rdo,
    };
    try {
      await assertCanonicalMutationEventProvenance(
        original,
        pendingCanonicalEvent(originalEvent),
      );
    } catch {
      await terminalizeRejectedRecovery(
        database,
        guard,
        [snapshot],
        "CANONICAL_RECOVERY_PROVENANCE_INVALID",
        "A mutação rejeitada perdeu a coerência canônica local.",
        options.executionLease,
        recoveryDecisionSnapshot,
      );
      continue;
    }
    const ancestryState = recoveryAncestryState(
      original,
      entityMutations,
    );
    if (ancestryState === "RECOVERED") {
      await terminalizeRejectedRecovery(
        database,
        guard,
        [snapshot],
        "AUTO_RECOVERY_LIMIT_REACHED",
        "A autorrecuperação já foi tentada uma vez; revise o RDO.",
        options.executionLease,
        recoveryDecisionSnapshot,
      );
      continue;
    }
    if (ancestryState === "INVALID") {
      await terminalizeRejectedRecovery(
        database,
        guard,
        [snapshot],
        "CAUSAL_CHAIN_INVALID",
        "A cadeia causal local está ausente ou contém um ciclo.",
        options.executionLease,
        recoveryDecisionSnapshot,
      );
      continue;
    }
    const transitiveDependentIds = allDependentMutationIds(
      candidateMutationSnapshot,
      rootIds,
    );
    const localEditAncestorIds = localEditSupersededAncestorIds(
      original,
      entityMutations,
    );
    const syncedCausalAncestorIds = syncedCanonicalCausalAncestorIds(
      original,
      entityMutations,
    );
    const transitiveDependents = candidateMutationSnapshot.filter(
      (mutation) =>
        transitiveDependentIds.has(mutation.clientMutationId),
    );
    if (
      transitiveDependents.some(
        (mutation) => mutation.status === "SYNCING",
      )
    ) {
      continue;
    }
    if (
      transitiveDependents.some(
        (mutation) =>
          !["PENDING", "ERROR"].includes(mutation.status),
      )
    ) {
      await terminalizeRejectedRecovery(
        database,
        guard,
        [snapshot],
        "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
        "Uma dependência transitiva já está em estado terminal.",
        options.executionLease,
        recoveryDecisionSnapshot,
      );
      continue;
    }
    const otherSameEntityMutations = entityMutations.filter(
      (mutation) =>
        mutation.clientMutationId !== original.clientMutationId &&
        !localEditAncestorIds.has(mutation.clientMutationId),
    );
    if (
      otherSameEntityMutations.some(
        (mutation) => mutation.status === "SYNCING",
      )
    ) {
      continue;
    }
    const competingActiveMutations = entityMutations.filter(
      (mutation) =>
        mutation.clientMutationId !== original.clientMutationId &&
        !localEditAncestorIds.has(mutation.clientMutationId) &&
        !syncedCausalAncestorIds.has(mutation.clientMutationId) &&
        !(
          mutation.operacao !== "CRIAR_RDO" &&
          transitiveDependentIds.has(mutation.clientMutationId) &&
          ["PENDING", "ERROR"].includes(mutation.status)
        )
    );
    if (competingActiveMutations.length > 0) {
      const competingDecisionSnapshot = {
        allMutations: candidateMutationSnapshot,
        allEvents: candidateEventSnapshot,
      };
      const onlyCreates = competingActiveMutations.every(
        (mutation) => mutation.operacao === "CRIAR_RDO",
      );
      await terminalizeRejectedRecovery(
        database,
        guard,
        rejectedSnapshots,
        onlyCreates
          ? "COMPETING_RDO_CREATE_REQUIRES_REVIEW"
          : "COMPETING_RDO_MUTATION_REQUIRES_REVIEW",
        onlyCreates
          ? "Já existe outro CREATE ativo para o mesmo RDO."
          : "Já existe outra alteração ativa e independente para o mesmo RDO.",
        options.executionLease,
        competingDecisionSnapshot,
      );
      continue;
    }
    const canonicalDependents = allCanonicalDependents(
      allMutations,
      rootIds,
    );
    if (
      canonicalDependents.some(
        (dependent) => dependent.status === "SYNCING",
      )
    ) {
      continue;
    }
    if (
      canonicalDependents.some(
        (dependent) =>
          !["PENDING", "ERROR"].includes(dependent.status),
      )
    ) {
      await terminalizeRejectedRecovery(
        database,
        guard,
        [snapshot],
        "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
        "Uma dependência canônica já está em estado terminal.",
        options.executionLease,
        recoveryDecisionSnapshot,
      );
      continue;
    }
    let dependents: CanonicalOutboxMutationRecord[];
    try {
      dependents = affectedCanonicalDependents(allMutations, rootIds);
    } catch {
      await terminalizeRejectedRecovery(
        database,
        guard,
        [snapshot],
        "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
        "A cadeia canônica de dependências é inválida ou excede o limite.",
        options.executionLease,
        recoveryDecisionSnapshot,
      );
      continue;
    }
    const workforceRecovery =
      original.lastSafeCode === "VALIDATION_OR_AUTHORIZATION";
    let repairedDraft = rdoDraftFromLocalRecord(rdo);
    let previousSnapshot = originalEvent.previousState;
    let nextPayload: Record<string, unknown>;
    let replacementOperation = original.operation;
    let replacementTransportOperation = original.operacao;
    let replacementBaseVersion = original.baseVersion;
    let authoritativeRdo: LocalRdoRecord | null = null;
    let workforceOperationalEventRecovery:
      WorkforceOperationalEventRecovery | null = null;
    let workforceCorrectionOriginalEvents:
      OperationalEventRecord[] = [];
    let recoveryTimestamp: string | null = null;
    if (workforceRecovery) {
      let authorizedIds: ReadonlySet<string>;
      await assertRecoveryLease(options.executionLease);
      try {
        authorizedIds = await loadAuthorizedIds(rdo.obraId, rdo.dataRdo);
      } catch (error: unknown) {
        assertSyncSession(guard);
        await assertRecoveryLease(options.executionLease);
        if (
          error instanceof RdoWorkforceContextUnverifiedError ||
          isPermanentRecoveryLookupError(error)
        ) {
          await terminalizeRejectedRecovery(
            database,
            guard,
            [snapshot],
            "WORKFORCE_RECOVERY_CONTEXT_UNVERIFIED",
            "O contexto atual de colaboradores não pôde ser comprovado.",
            options.executionLease,
            recoveryDecisionSnapshot,
          );
        } else {
          await deferRejectedRecovery(
            database,
            guard,
            snapshot,
            now(),
            options.executionLease,
          );
        }
        continue;
      }
      assertSyncSession(guard);
      await assertRecoveryLease(options.executionLease);
      const repaired = repairInvalidWorkforceLinks(
        repairedDraft,
        authorizedIds,
      );
      if (repaired.invalidStructuredAllocationIds.length > 0) {
        await terminalizeRejectedRecovery(
          database,
          guard,
          [snapshot],
          "WORKFORCE_ALLOCATION_REASSIGNMENT_REQUIRED",
          "Reatribua ou remova a alocação sem vínculo ativo.",
          options.executionLease,
          recoveryDecisionSnapshot,
        );
        continue;
      }
      if (repaired.hasInvalidIds && !repaired.changed) {
        await terminalizeRejectedRecovery(
          database,
          guard,
          [snapshot],
          "WORKFORCE_RECOVERY_REQUIRES_REVIEW",
          repaired.unresolvedInvalidIds.length > 0
            ? "Há vínculo inválido sem nome nominal para recuperação segura."
            : "O contexto atual ainda autoriza todos os vínculos locais.",
          options.executionLease,
          recoveryDecisionSnapshot,
        );
        continue;
      }
      repairedDraft = repaired.draft;
      recoveryTimestamp = now();
      workforceOperationalEventRecovery =
        recoverWorkforceOperationalEvents(
          original.payload,
          authorizedIds,
          repaired.repairedNominalWorkforceLinks,
          recoveryTimestamp,
          correctiveEventIdFactory,
        );
      if (workforceOperationalEventRecovery.invalidReason) {
        await terminalizeRejectedRecovery(
          database,
          guard,
          [snapshot],
          "WORKFORCE_OPERATIONAL_EVENT_RECOVERY_REQUIRES_REVIEW",
          workforceOperationalEventRecovery.invalidReason,
          options.executionLease,
          recoveryDecisionSnapshot,
        );
        continue;
      }
      const correctionOriginals =
        workforceOperationalEventRecovery.corrections.map(
          (correction) =>
            candidateEventSnapshot.find(
              (event) => event.id === correction.originalEventId,
            ),
        );
      const corrections =
        workforceOperationalEventRecovery.corrections;
      if (
        correctionOriginals.some(
          (event, index) =>
            !event ||
            event.schemaVersion !== 1 ||
            ![
              "PENDING_SYNC",
              "SYNCING",
              "SYNC_FAILED",
            ].includes(event.syncStatus) ||
            canonicalMutationJson(
              serializeOperationalEventForTransport(event),
            ) !==
              canonicalMutationJson(
                corrections[index].originalSerializedEvent,
              ),
        )
      ) {
        await terminalizeRejectedRecovery(
          database,
          guard,
          [snapshot],
          "WORKFORCE_OPERATIONAL_EVENT_RECOVERY_REQUIRES_REVIEW",
          "O evento operacional original não está íntegro como registro local schema-v1.",
          options.executionLease,
          recoveryDecisionSnapshot,
        );
        continue;
      }
      workforceCorrectionOriginalEvents =
        correctionOriginals as OperationalEventRecord[];
      nextPayload = {
        ...buildRdoSyncPayload(repairedDraft),
        operationalEvents:
          workforceOperationalEventRecovery.events,
      };
    } else if (original.operation === "UPDATE") {
      nextPayload = original.payload as Record<string, unknown>;
    } else {
      let authoritative: AuthoritativeRdoLookup;
      await assertRecoveryLease(options.executionLease);
      try {
        authoritative = await lookupAuthoritativeRdo(original.entityId);
      } catch (error: unknown) {
        assertSyncSession(guard);
        await assertRecoveryLease(options.executionLease);
        if (isPermanentRecoveryLookupError(error)) {
          await terminalizeRejectedRecovery(
            database,
            guard,
            [snapshot],
            "IDEMPOTENCY_RECONCILIATION_REQUIRED",
            "A reconciliação autoritativa foi recusada permanentemente.",
            options.executionLease,
            recoveryDecisionSnapshot,
          );
        } else {
          await deferRejectedRecovery(
            database,
            guard,
            snapshot,
            now(),
            options.executionLease,
          );
        }
        continue;
      }
      assertSyncSession(guard);
      await assertRecoveryLease(options.executionLease);
      if (authoritative.kind === "FOUND") {
        if (
          authoritative.rdo.obraId !== rdo.obraId ||
          authoritative.rdo.clientMutationId !==
            original.clientMutationId ||
          authoritative.rdo.status !== "RASCUNHO"
        ) {
          await terminalizeRejectedRecovery(
            database,
            guard,
            [snapshot],
            "IDEMPOTENCY_RECONCILIATION_REQUIRED",
            "O RDO autoritativo pertence a outra obra.",
            options.executionLease,
            recoveryDecisionSnapshot,
          );
          continue;
        }
        const reconciledRdo: LocalRdoRecord = {
          ...rdo,
          programacaoId:
            typeof authoritative.rdo.programacaoId === "string"
              ? authoritative.rdo.programacaoId
              : null,
          numeroRdo:
            typeof authoritative.rdo.numeroRdo === "string"
              ? authoritative.rdo.numeroRdo
              : rdo.numeroRdo,
          dataRdo:
            typeof authoritative.rdo.dataRdo === "string"
              ? authoritative.rdo.dataRdo
              : rdo.dataRdo,
          statusRdo: "RASCUNHO",
          versaoEntidade: authoritative.version,
          payload: authoritative.rdo,
          syncStatus: "SYNCED",
          updatedAt: now(),
        };
        authoritativeRdo = reconciledRdo;
        const authoritativeDraft =
          rdoDraftFromLocalRecord(reconciledRdo);
        previousSnapshot = buildRdoSyncPayload(authoritativeDraft);
        nextPayload = buildRdoSyncPayload(repairedDraft);
        replacementOperation = "UPDATE";
        replacementTransportOperation = "ATUALIZAR_RDO_RASCUNHO";
        replacementBaseVersion = authoritative.version;
      } else if (authoritative.kind === "MISSING") {
        nextPayload = buildRdoSyncPayload(repairedDraft);
      } else {
        await deferRejectedRecovery(
          database,
          guard,
          snapshot,
          now(),
          options.executionLease,
        );
        continue;
      }
    }

    const timestamp = recoveryTimestamp ?? now();
    const replacementIds = new Map<string, string>();
    const rootReplacementId = mutationIdFactory();
    for (const rootId of rootIds) {
      replacementIds.set(rootId, rootReplacementId);
    }
    for (const dependent of dependents) {
      replacementIds.set(
        dependent.clientMutationId,
        mutationIdFactory(),
      );
    }
    const built = await buildCanonicalMutation({
      clientMutationId: rootReplacementId,
      ontologyEventId: eventIdFactory(),
      deviceId: original.deviceId,
      userId: original.userId,
      obraId: original.obraId,
      entityType: original.entityType,
      entityId: original.entityId,
      operation: replacementOperation,
      transportOperation: replacementTransportOperation,
      baseVersion: replacementBaseVersion,
      occurredAt: timestamp,
      previousSnapshot,
      nextSnapshot: nextPayload,
      authorizationScope: original.trace.authorizationScope,
      correlationId: original.correlationId,
      causationId: original.clientMutationId,
      transport: original.transport,
      dependsOnMutationIds: original.dependsOnMutationIds,
      relatedEntities: original.relatedEntities,
    });
    const replacementEvent = replacementCanonicalEvent(
      originalEvent,
      built,
    );
    const dependentReplacements = [];
    let dependentInvalid = false;
    for (const dependent of dependents) {
      const event = await canonicalEventSnapshot(database, dependent);
      if (
        !event ||
        dependent.userId !== original.userId ||
        dependent.obraId !== original.obraId
      ) {
        dependentInvalid = true;
        break;
      }
      try {
        await assertCanonicalMutationEventProvenance(
          dependent,
          pendingCanonicalEvent(event),
        );
      } catch {
        dependentInvalid = true;
        break;
      }
      const dependentBuilt = await buildCanonicalMutation({
        clientMutationId: replacementIds.get(
          dependent.clientMutationId,
        ),
        ontologyEventId: eventIdFactory(),
        deviceId: dependent.deviceId,
        userId: dependent.userId,
        obraId: dependent.obraId,
        entityType: dependent.entityType,
        entityId: dependent.entityId,
        operation: dependent.operation,
        transportOperation: dependent.operacao,
        baseVersion: dependent.baseVersion,
        occurredAt: timestamp,
        previousSnapshot: event.previousState,
        nextSnapshot: dependent.payload,
        authorizationScope: dependent.trace.authorizationScope,
        correlationId: dependent.correlationId,
        causationId: dependent.clientMutationId,
        transport: dependent.transport,
        dependsOnMutationIds: [
          ...new Set(
            (dependent.dependsOnMutationIds ?? []).map(
              (id) => replacementIds.get(id) ?? id,
            ),
          ),
        ],
        relatedEntities: dependent.relatedEntities,
      });
      dependentReplacements.push({
        original: dependent,
        originalEvent: event,
        built: dependentBuilt,
        event: replacementCanonicalEvent(event, dependentBuilt),
      });
    }
    if (dependentInvalid) {
      await terminalizeRejectedRecovery(
        database,
        guard,
        [snapshot],
        "DEPENDENCY_RECOVERY_REQUIRES_REVIEW",
        "Uma dependência canônica não possui evento local íntegro.",
        options.executionLease,
        recoveryDecisionSnapshot,
      );
      continue;
    }
    assertSyncSession(guard);
    await assertRecoveryLease(options.executionLease);

    const guardedTransaction = guardSyncTransaction(
      database.transaction(
        [
          "rdos",
          "outbox_mutations",
          "operational_events",
          "rdoMaoObra",
          "rdoEquipamentos",
          "rdoMateriais",
          "rdoControlesGeometricos",
          "rdo_attachments",
          "sync_state",
        ],
        "readwrite",
      ),
      guard,
    );
    const transaction = guardedTransaction.transaction;
    const outboxStore = transaction.objectStore("outbox_mutations");
    const eventStore = transaction.objectStore("operational_events");
    const rdoStore = transaction.objectStore("rdos");
    const currentOriginal = await outboxStore.get(
      original.clientMutationId,
    );
    const currentRdo = await rdoStore.get(rdo.id);
    const currentEvents = await eventStore
      .index("by-client-mutation-id")
      .getAll(original.clientMutationId);
    const currentDependentEventGroups = await Promise.all(
      dependentReplacements.map((dependent) =>
        eventStore
          .index("by-client-mutation-id")
          .getAll(dependent.original.clientMutationId)
      ),
    );
    const currentWorkforceCorrectionOriginalEvents =
      await Promise.all(
        workforceCorrectionOriginalEvents.map((event) =>
          eventStore.get(event.id)
        ),
      );
    const existingCorrectiveEvents = await Promise.all(
      (
        workforceOperationalEventRecovery?.corrections ?? []
      ).map((correction) =>
        eventStore.get(correction.correctiveEventId)
      ),
    );
    if (
      !currentOriginal ||
      !currentRdo ||
      currentEvents.length !== 1 ||
      canonicalMutationJson(currentOriginal) !==
        canonicalMutationJson(original) ||
      canonicalMutationJson(currentRdo) !== canonicalMutationJson(rdo) ||
      canonicalMutationJson(currentEvents[0]) !==
        canonicalMutationJson(originalEvent) ||
      dependentReplacements.some(
        (dependent, index) =>
          currentDependentEventGroups[index]?.length !== 1 ||
          canonicalMutationJson(currentDependentEventGroups[index][0]) !==
            canonicalMutationJson(dependent.originalEvent),
      ) ||
      currentWorkforceCorrectionOriginalEvents.some(
        (event, index) =>
          !event ||
          canonicalMutationJson(event) !==
            canonicalMutationJson(
              workforceCorrectionOriginalEvents[index],
            ),
      ) ||
      existingCorrectiveEvents.some((event) => event !== undefined) ||
      canonicalMutationJson(
        await outboxStore.index("by-entity-id").getAll(original.entityId),
      ) !== canonicalMutationJson(entityMutations) ||
      canonicalMutationJson(await outboxStore.getAll()) !==
        canonicalMutationJson(allMutations)
    ) {
      await guardedTransaction.complete();
      continue;
    }

    const safeCode = workforceRecovery
      ? "SUPERSEDED_BY_WORKFORCE_RECOVERY"
      : "SUPERSEDED_BY_IDEMPOTENCY_RECOVERY";
    await assertRecoveryLeaseInTransaction(
      transaction,
      options.executionLease,
    );
    await outboxStore.put({
      ...currentOriginal,
      status: "REJECTED",
      nextAttemptAt: null,
      lastSafeCode: safeCode,
      blockedReason:
        `SUPERSEDED_BY:${built.mutation.clientMutationId}`,
      ultimoErro:
        "Envelope rejeitado substituído por nova mutação canônica.",
      updatedAt: timestamp,
    });
    await eventStore.put({
      ...currentEvents[0],
      result: "REJECTED",
      syncStatus: "SYNC_FAILED",
      errorCategory: safeCode,
    } as CanonicalOperationalEventRecord);
    await outboxStore.add(built.mutation);
    await eventStore.add(replacementEvent);
    if (workforceOperationalEventRecovery) {
      for (
        const correction of
          workforceOperationalEventRecovery.corrections
      ) {
        const currentEvent = await eventStore.get(
          correction.originalEventId,
        );
        if (!currentEvent || currentEvent.schemaVersion !== 1) {
          throw new Error(
            "O evento operacional original mudou durante a recuperação.",
          );
        }
        await eventStore.put({
          ...currentEvent,
          syncStatus: "SYNC_FAILED",
          syncedAt: null,
        });
        await eventStore.add({
          ...currentEvent,
          id: correction.correctiveEventId,
          type: correction.type,
          colaboradorId: correction.colaboradorId,
          principalEntity: correction.principalEntity,
          principalEntityKey:
            `${correction.principalEntity.tipo}:${correction.principalEntity.id}`,
          payload: correction.payload,
          occurredAt: timestamp,
          syncStatus: "PENDING_SYNC",
          syncedAt: null,
          schemaVersion: 1,
          causationId: correction.originalEventId,
        });
      }
    }
    for (const dependent of dependentReplacements) {
      await outboxStore.put({
        ...dependent.original,
        status: "REJECTED",
        nextAttemptAt: null,
        lastSafeCode: "SUPERSEDED_BY_DEPENDENCY_REWIRE",
        blockedReason:
          `SUPERSEDED_BY:${dependent.built.mutation.clientMutationId}`,
        ultimoErro:
          "Dependência canônica religada por envelope substituto.",
        updatedAt: timestamp,
      });
      await eventStore.put({
        ...dependent.originalEvent,
        result: "REJECTED",
        syncStatus: "SYNC_FAILED",
        errorCategory: "SUPERSEDED_BY_DEPENDENCY_REWIRE",
      });
      await outboxStore.add(dependent.built.mutation);
      await eventStore.add(dependent.event);
    }

    for (const queued of await outboxStore.getAll()) {
      const rewired = rewireLegacyDependencies(
        queued,
        replacementIds,
      );
      if (rewired) {
        await outboxStore.put(rewired);
      }
    }

    const updatedRdo: LocalRdoRecord = {
      ...currentRdo,
      payload: buildRdoLocalPayload(repairedDraft),
      versaoEntidade:
        authoritativeRdo?.versaoEntidade ?? currentRdo.versaoEntidade,
      syncStatus: "PENDING_SYNC",
      updatedAt: timestamp,
    };
    await rdoStore.put(updatedRdo);
    await replaceChildRecords(
      transaction,
      repairedDraft,
      "PENDING_SYNC",
      timestamp,
    );
    await reopenUnsyncedRdoAttachments(
      transaction,
      rdo.id,
      timestamp,
    );
    await guardedTransaction.complete();
    options.recoveredReplacementIds?.add(
      built.mutation.clientMutationId,
    );
    options.recoveredReplacementByOriginalId?.set(
      original.clientMutationId,
      built.mutation.clientMutationId,
    );
    recovered += 1;
  }

  return recovered;
}

export async function saveExistingRdoDraftAtomically(
  draft: RdoDraft,
): Promise<SaveRdoDraftResult> {
  validateRdoDraftForSync(draft);

  const database = await getCortexDb();
  const timestamp = nowUtc();
  const operationalEvents = buildRdoSaveOperationalEvents(
    draft,
    true,
    timestamp,
  );
  const pendingOperationalEvents = (
    await queryOperationalEvents({
      rdoId: draft.id,
      limit: 500,
    })
  ).filter((event) => event.syncStatus !== "SYNCED");
  const localPayload = buildRdoLocalPayload(draft);
  const syncPayload = buildRdoSyncPayload(draft, [
    ...pendingOperationalEvents,
    ...operationalEvents,
  ]);

  const preflightRdo = await database.get("rdos", draft.id);
  const preflightMutations = await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    draft.id,
  );
  const pendingCanonicalCreate = preflightMutations
    .filter(
      (candidate): candidate is CanonicalOutboxMutationRecord =>
        isCanonicalOutboxMutation(candidate) &&
        candidate.operation === "CREATE" &&
        candidate.operacao === "CRIAR_RDO" &&
        ["PENDING", "ERROR"].includes(candidate.status),
    )
    .sort((left, right) =>
      right.occurredAt.localeCompare(left.occurredAt),
    )[0];
  const attemptedLegacyCreate = preflightMutations
    .filter(
      (candidate) =>
        !isCanonicalOutboxMutation(candidate) &&
        candidate.operacao === "CRIAR_RDO" &&
        ["PENDING", "ERROR"].includes(candidate.status) &&
        !canCoalesceLegacyRdoMutation(candidate, "CRIAR_RDO"),
    )
    .sort((left, right) =>
      right.criadaNoClienteEm.localeCompare(left.criadaNoClienteEm),
    )[0];

  if (
    preflightRdo &&
    (pendingCanonicalCreate || attemptedLegacyCreate)
  ) {
    return replacePendingRdoCreate({
      draft,
      existingRdo: preflightRdo,
      original: pendingCanonicalCreate ?? attemptedLegacyCreate!,
      localPayload,
      pendingOperationalEvents,
      timestamp,
    });
  }

  const transaction = database.transaction(
    [
      "rdos",
      "outbox_mutations",
      "rdoMaoObra",
      "rdoEquipamentos",
      "rdoMateriais",
      "rdoControlesGeometricos",
      "operational_events",
    ],
    "readwrite",
  );

  const rdoStore =
    transaction.objectStore("rdos");

  const outboxStore =
    transaction.objectStore(
      "outbox_mutations",
    );
  const eventStore =
    transaction.objectStore("operational_events");

  const existingRdo =
    await rdoStore.get(draft.id);

  if (!existingRdo) {
    transaction.abort();

    throw new Error(
      `O RDO local ${draft.id} não foi encontrado.`,
    );
  }

  if (existingRdo.statusRdo === "ENVIADO") {
    transaction.abort();

    throw new Error(
      "Um RDO enviado não pode mais ser editado.",
    );
  }

  if (
    existingRdo.syncStatus === "CONFLICT"
  ) {
    transaction.abort();

    throw new Error(
      "Este RDO possui um conflito pendente. Resolva o conflito antes de editar.",
    );
  }

  const entityIndex =
    outboxStore.index("by-entity-id");
  const updateContextBlockReason =
    rdoUpdateCreationContextBlockReason(draft, existingRdo);

  const entityMutations =
    await entityIndex.getAll(draft.id);

  const existingCreateMutation =
    entityMutations
      .filter(
        (candidate) => canCoalesceLegacyRdoMutation(
          candidate,
          "CRIAR_RDO",
        ),
      )
      .sort((left, right) =>
        right.criadaNoClienteEm.localeCompare(
          left.criadaNoClienteEm,
        ),
      )[0];

  let mutation: OutboxMutationRecord;

  /*
   * O RDO ainda não foi criado com sucesso no servidor.
   * Atualizamos a mutação CRIAR_RDO existente em vez de
   * criar outra mutação.
   */
  if (
    existingRdo.versaoEntidade === null &&
    existingCreateMutation
  ) {
    mutation = {
      ...existingCreateMutation,
      payload: syncPayload,
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: null,
      conflito: null,
      blockedReason: rdoCreationContextBlockReason(draft),
      nextAttemptAt: null,
      updatedAt: timestamp,
    };
  } else {
    /*
     * O CRIAR_RDO já foi sincronizado, mas a versão
     * do servidor ainda não foi armazenada localmente.
     * Não podemos enviar uma atualização sem baseVersao.
     */
    if (
      existingRdo.versaoEntidade === null
    ) {
      transaction.abort();

      throw new Error(
        "O RDO já existe no servidor, mas sua versão local não foi registrada. A sincronização precisa salvar versaoEntidade antes de permitir esta atualização.",
      );
    }

    const existingUpdateMutation =
      entityMutations
        .filter(
          (candidate) => canCoalesceLegacyRdoMutation(
            candidate,
            "ATUALIZAR_RDO_RASCUNHO",
          ),
        )
        .sort((left, right) =>
          right.criadaNoClienteEm.localeCompare(
            left.criadaNoClienteEm,
          ),
        )[0];
    const inFlightLegacyUpdate =
      entityMutations
        .filter(
          (candidate) =>
            !isCanonicalOutboxMutation(candidate) &&
            candidate.operacao === "ATUALIZAR_RDO_RASCUNHO" &&
            candidate.status === "SYNCING",
        )
        .sort((left, right) =>
          right.criadaNoClienteEm.localeCompare(
            left.criadaNoClienteEm,
          ),
        )[0];

    if (existingUpdateMutation) {
      mutation = {
        ...existingUpdateMutation,
        baseVersao:
          existingRdo.versaoEntidade,
        payload: syncPayload,
        status: "PENDING",
        tentativas: 0,
        ultimaTentativaEm: null,
        ultimoErro: null,
        conflito: null,
        blockedReason: updateContextBlockReason,
        nextAttemptAt: null,
        updatedAt: timestamp,
      };
    } else {
      mutation = {
        clientMutationId:
          crypto.randomUUID(),
        entidadeTipo: "RDO",
        entidadeId: draft.id,
        operacao:
          "ATUALIZAR_RDO_RASCUNHO",
        baseVersao:
          existingRdo.versaoEntidade,
        payload: syncPayload,
        status: "PENDING",
        tentativas: 0,
        ultimaTentativaEm: null,
        ultimoErro: null,
        conflito: null,
        blockedReason: updateContextBlockReason,
        nextAttemptAt: null,
        dependsOnMutationIds: inFlightLegacyUpdate
          ? [inFlightLegacyUpdate.clientMutationId]
          : undefined,
        criadaNoClienteEm: timestamp,
        updatedAt: timestamp,
      };
    }
  }

  const updatedRdo: LocalRdoRecord = {
    ...existingRdo,
    obraId: draft.obraId,
    programacaoId:
      draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    payload: localPayload,
    syncStatus: "PENDING_SYNC",
    updatedAt: timestamp,
  };

  await Promise.all([
    rdoStore.put(updatedRdo),
    outboxStore.put(mutation),
    ...operationalEvents.map((event) =>
      eventStore.put(event),
    ),
    replaceChildRecords(
      transaction,
      draft,
      "PENDING_SYNC",
      timestamp,
    ),
  ]);

  await transaction.done;

  return {
    rdo: updatedRdo,
    mutation,
  };
}

async function replacePendingRdoCreate(input: {
  draft: RdoDraft;
  existingRdo: LocalRdoRecord;
  original: OutboxMutationRecord;
  localPayload: Record<string, unknown>;
  pendingOperationalEvents: OperationalEventRecord[];
  timestamp: string;
}): Promise<SaveRdoDraftResult> {
  const {
    draft,
    existingRdo,
    original,
    localPayload,
    pendingOperationalEvents,
    timestamp,
  } = input;
  const session = getSession();
  if (!session) {
    throw new Error("Sessão válida obrigatória para editar o RDO local.");
  }
  const replacementPayload = buildRdoSyncPayload(
    draft,
    pendingOperationalEvents.filter((event) => event.schemaVersion !== 13),
  );
  const updatedRdo: LocalRdoRecord = {
    ...existingRdo,
    obraId: draft.obraId,
    programacaoId: draft.programacaoId || null,
    numeroRdo: draft.numeroRdo,
    dataRdo: draft.dataRdo,
    payload: localPayload,
    syncStatus: "PENDING_SYNC",
    updatedAt: timestamp,
  };
  const database = await getCortexDb();
  const state = await getSyncState();
  const uuidPattern =
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
  const deviceId =
    isCanonicalOutboxMutation(original)
      ? original.deviceId
      : state.usuarioId === session.colaboradorId &&
          state.deviceId &&
          uuidPattern.test(state.deviceId)
        ? state.deviceId
        : crypto.randomUUID();
  if (
    !isCanonicalOutboxMutation(original) &&
    (state.deviceId !== deviceId ||
      state.usuarioId !== session.colaboradorId)
  ) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }
  const childStores = [
    "rdoMaoObra",
    "rdoEquipamentos",
    "rdoMateriais",
    "rdoControlesGeometricos",
  ] as const;
  const existingChildren = await Promise.all(
    childStores.map(async (store) => ({
      store,
      records: await database.getAllFromIndex(store, "by-rdo-id", draft.id),
    })),
  );
  type RdoReplacementStore =
    | "rdos"
    | "rdoMaoObra"
    | "rdoEquipamentos"
    | "rdoMateriais"
    | "rdoControlesGeometricos";
  const writes: LocalMutationDomainWrite<RdoReplacementStore>[] = [
    {
      store: "rdos",
      value: updatedRdo,
      principal: true,
    },
    ...existingChildren.flatMap(({ store, records }) =>
      records.map(
        (record) =>
          ({
            store,
            deleteKey: record.id,
          }) as LocalMutationDomainWrite<RdoReplacementStore>,
      ),
    ),
    ...buildMaoObraRecords(draft, "PENDING_SYNC", timestamp).map(
      (value) => ({ store: "rdoMaoObra" as const, value }),
    ),
    ...buildEquipamentoRecords(draft, "PENDING_SYNC", timestamp).map(
      (value) => ({ store: "rdoEquipamentos" as const, value }),
    ),
    ...buildMaterialRecords(draft, "PENDING_SYNC", timestamp).map(
      (value) => ({ store: "rdoMateriais" as const, value }),
    ),
    ...buildControleGeometricoRecords(draft, "PENDING_SYNC", timestamp).map(
      (value) => ({ store: "rdoControlesGeometricos" as const, value }),
    ),
  ];
  const recoveredAsUpdate = existingRdo.versaoEntidade !== null;
  const committed = await commitLocalMutation<RdoReplacementStore>({
    deviceId,
    userId: session.colaboradorId,
    obraId: draft.obraId,
    entityType: "RDO",
    entityId: draft.id,
    entityName: draft.numeroRdo.trim() || null,
    operation: recoveredAsUpdate ? "UPDATE" : "CREATE",
    transportOperation: recoveredAsUpdate
      ? "ATUALIZAR_RDO_RASCUNHO"
      : "CRIAR_RDO",
    baseVersion: recoveredAsUpdate
      ? existingRdo.versaoEntidade
      : null,
    occurredAt: timestamp,
    previousSnapshot: original.payload as Record<string, unknown>,
    nextSnapshot: replacementPayload,
    principalSnapshot: updatedRdo as unknown as Record<string, unknown>,
    eventType: "RDO_EDITADO",
    relatedEntities: [
      {
        tipo: "OBRA",
        id: draft.obraId,
        nome: entityName(draft.contrato) ?? entityName(draft.cliente),
      },
    ],
    correlationId: isCanonicalOutboxMutation(original)
      ? original.correlationId
      : crypto.randomUUID(),
    causationId: original.clientMutationId,
    dependsOnMutationIds: original.dependsOnMutationIds,
    initialBlockedReason:
      !recoveredAsUpdate &&
      rdoCreationContextBlockReason(draft) !== null
        ? "RDO_CREATION_CONTEXT_REQUIRED"
        : undefined,
    supersedesMutationId: original.clientMutationId,
    write: () => writes,
  });
  return { rdo: updatedRdo, mutation: committed.mutation };
}

export async function saveRdoDraftAtomically(
  draft: RdoDraft,
): Promise<SaveRdoDraftResult> {
  const database = await getCortexDb();

  const existingRdo = await database.get(
    "rdos",
    draft.id,
  );

  if (existingRdo) {
    return saveExistingRdoDraftAtomically(
      draft,
    );
  }

  return saveNewRdoDraftAtomically(draft);
}
