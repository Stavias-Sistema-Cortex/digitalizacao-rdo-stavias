import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

export interface ObraTimelineEventApi {
  id: string;
  commitSeq: number | null;
  type: string;
  principalEntityType: string;
  principalEntityId: string;
  relatedEntities: unknown;
  obraId: string | null;
  rdoId: string | null;
  colaboradorId: string | null;
  occurredAt: string | null;
  syncedAt: string | null;
  origin: string | null;
  syncStatus: string | null;
  schemaVersion: number | null;
  payload: unknown;
}

export interface ObraTimelineEvent {
  id: string;
  commitSeq: number | null;
  type: string;
  principalEntityType: string;
  principalEntityId: string;
  obraId: string | null;
  occurredAt: string | null;
  origin: string | null;
  syncStatus: string | null;
  payload: Record<string, unknown>;
}

async function readJson<T>(response: Response): Promise<T> {
  const data = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(
      responseErrorMessage(data, response.status),
    );
  }

  return data as T;
}

function objectPayload(value: unknown): Record<string, unknown> {
  if (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value)
  ) {
    return value as Record<string, unknown>;
  }

  return {};
}

function timelineTimestampForMachine(
  value: string | null,
  schemaVersion: number | null,
): string | null {
  if (
    !value ||
    schemaVersion === null ||
    (schemaVersion !== 2 && schemaVersion < 13) ||
    /(?:Z|[+-]\d{2}:\d{2})$/i.test(value)
  ) {
    return value;
  }

  return `${value}Z`;
}

export function obraTimelineEventFromApi(
  api: ObraTimelineEventApi,
): ObraTimelineEvent {
  return {
    id: api.id,
    commitSeq: api.commitSeq,
    type: api.type,
    principalEntityType: api.principalEntityType,
    principalEntityId: api.principalEntityId,
    obraId: api.obraId,
    occurredAt: timelineTimestampForMachine(
      api.occurredAt,
      api.schemaVersion,
    ),
    origin: api.origin,
    syncStatus: api.syncStatus,
    payload: objectPayload(api.payload),
  };
}

export async function buscarTimelineObra(
  obraId: string,
): Promise<ObraTimelineEvent[]> {
  const params = new URLSearchParams({
    entityType: "OBRA",
    entityId: obraId,
    limit: "50",
  });
  const response = await apiFetch(
    `/ontology/timeline?${params.toString()}`,
  );
  const data = await readJson<ObraTimelineEventApi[]>(response);

  return data.map(obraTimelineEventFromApi);
}

export interface ObraPdorApi {
  id: string;
  obra?: {
    id: string;
    codigoContrato?: string | null;
    codigoCw?: string | null;
    codigoInterno?: string | null;
    nome?: string | null;
  } | null;
  dataReferencia: string | null;
  dataExecucao: string | null;
  versaoModelo?: string | null;
  versaoPremissas?: string | null;
  versaoDados?: string | null;
  statusExecucao: string;
  statusExecucaoLabel: string | null;
  calibracao: string | null;
  calibracaoLabel: string | null;
  risco: string | null;
  riscoLabel: string | null;
  fase: string | null;
  faseLabel: string | null;
  receitaEstimadaFinal: number | string | null;
  racs: Record<string, number | string | null> | null;
  p10: number | string | null;
  p50: number | string | null;
  p80: number | string | null;
  p95: number | string | null;
  probabilidadeAbaixoContrato: number | string | null;
  confianca: number | string | null;
  drivers: unknown;
  warnings: unknown;
  escopoAnalisado?: unknown;
  janelaTemporal?: unknown;
  featuresUtilizadas?: unknown;
  dadosAusentes?: unknown;
  limitacoes?: unknown;
  alertasDerivados?: unknown;
  recomendacoes?: unknown;
  comparacaoAnterior?: unknown;
  evidencias?: unknown;
  iniciadoPor?: string | null;
  tipoIniciador?: string | null;
  algorithmVersion?: string | null;
  evidenceIds?: unknown;
  evidenceHighWaterMark?: number | null;
  coverageCode?: string | null;
  assumptions?: unknown;
  executedAtUtc?: string | null;
  stale?: boolean;
  current?: boolean;
  erroExecucao: string | null;
}

export interface ObraPdorDriver {
  code: string;
  description: string;
  impact: number | null;
  evidence: string | null;
}

export interface ObraPdorExplanationItem {
  code: string;
  label: string;
  detail: string | null;
  field: string | null;
  availability: string | null;
}

export interface ObraPdorEvidence {
  entityType: string;
  entityId: string;
  source: string | null;
  role: string | null;
  observedAt: string | null;
}

export interface ObraPdorComparison {
  available: boolean;
  riskDirection: string | null;
  previousSnapshotId: string | null;
  changedInputCount: number;
}

export interface ObraPdorTemporalWindow {
  inicioProgramacao: string | null;
  fimProgramacao: string | null;
  dataReferencia: string | null;
  janelaEquipamentosDias: number | null;
  serieHistoricaSemanal: boolean | null;
}

export interface ObraPdor {
  id: string;
  obraId: string;
  dataReferencia: string | null;
  janelaTemporal: ObraPdorTemporalWindow | null;
  dataExecucao: string | null;
  versaoModelo: string | null;
  versaoPremissas: string | null;
  versaoDados: string | null;
  statusExecucao: string;
  statusExecucaoLabel: string | null;
  calibracao: string | null;
  calibracaoLabel: string | null;
  risco: string | null;
  riscoLabel: string | null;
  faseLabel: string | null;
  receitaPrevistaFinal: number | null;
  p10: number | null;
  p50: number | null;
  p80: number | null;
  p95: number | null;
  probabilidadeAbaixoContrato: number | null;
  confianca: number | null;
  drivers: ObraPdorDriver[];
  warnings: string[];
  featuresUtilizadas: ObraPdorExplanationItem[];
  dadosAusentes: ObraPdorExplanationItem[];
  limitacoes: ObraPdorExplanationItem[];
  alertas: ObraPdorExplanationItem[];
  recomendacoes: ObraPdorExplanationItem[];
  comparacaoAnterior: ObraPdorComparison | null;
  evidencias: ObraPdorEvidence[];
  iniciadoPor: string | null;
  tipoIniciador: string | null;
  algorithmVersion: string | null;
  evidenceIds: string[];
  evidenceHighWaterMark: number | null;
  coverageCode: string | null;
  assumptions: Record<string, unknown>;
  executedAtUtc: string | null;
  stale: boolean;
  current: boolean;
  erroExecucao: string | null;
}

function toNumber(value: number | string | null | undefined): number | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }

  const parsed =
    typeof value === "number"
      ? value
      : Number(String(value).replace(",", "."));

  return Number.isNaN(parsed) ? null : parsed;
}

function driversFromApi(value: unknown): ObraPdorDriver[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.flatMap((item) => {
    if (typeof item !== "object" || item === null) {
      return [];
    }

    const driver = item as Record<string, unknown>;
    const description = driver.description;

    if (typeof description !== "string" || !description) {
      return [];
    }

    return [{
      code: typeof driver.code === "string" ? driver.code : "",
      description,
      impact: toNumber(driver.impact as number | string | null),
      evidence:
        typeof driver.evidence === "string" ? driver.evidence : null,
    }];
  });
}

function warningsFromApi(value: unknown): string[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.filter(
    (item): item is string => typeof item === "string" && item !== "",
  );
}

function stringArray(value: unknown): string[] {
  return Array.isArray(value)
    ? value.filter((item): item is string =>
        typeof item === "string" && item.trim().length > 0)
    : [];
}

function stringField(
  value: Record<string, unknown>,
  field: string,
): string | null {
  const candidate = value[field];
  return typeof candidate === "string" && candidate.trim()
    ? candidate.trim()
    : null;
}

function explanationItemsFromApi(
  value: unknown,
): ObraPdorExplanationItem[] {
  if (!Array.isArray(value)) {
    return [];
  }

  return value.flatMap((raw) => {
    if (typeof raw !== "object" || raw === null) {
      return [];
    }
    const item = raw as Record<string, unknown>;
    const description = stringField(item, "descricao");
    const label =
      stringField(item, "titulo") ??
      stringField(item, "rotulo") ??
      description ??
      stringField(item, "campo") ??
      stringField(item, "codigo");

    if (!label) {
      return [];
    }

    return [{
      code: stringField(item, "codigo") ?? "",
      label,
      detail:
        stringField(item, "detalhe") ??
        (description !== label ? description : null),
      field: stringField(item, "campo"),
      availability: stringField(item, "disponibilidade"),
    }];
  });
}

function comparisonFromApi(value: unknown): ObraPdorComparison | null {
  if (typeof value !== "object" || value === null) {
    return null;
  }
  const comparison = value as Record<string, unknown>;
  const changedInputs = comparison.inputsAlterados;
  return {
    available: comparison.disponivel === true,
    riskDirection: stringField(comparison, "direcaoRisco"),
    previousSnapshotId: stringField(comparison, "snapshotAnteriorId"),
    changedInputCount: Array.isArray(changedInputs)
      ? changedInputs.length
      : 0,
  };
}

function evidencesFromApi(value: unknown): ObraPdorEvidence[] {
  if (!Array.isArray(value)) {
    return [];
  }
  return value.flatMap((raw) => {
    if (typeof raw !== "object" || raw === null) {
      return [];
    }
    const evidence = raw as Record<string, unknown>;
    const entityType = stringField(evidence, "tipoEntidade");
    const entityId = stringField(evidence, "entidadeId");
    if (!entityType || !entityId) {
      return [];
    }
    return [{
      entityType,
      entityId,
      source: stringField(evidence, "fonte"),
      role: stringField(evidence, "papel"),
      observedAt: stringField(evidence, "observadoEm"),
    }];
  });
}

function temporalWindowFromApi(
  value: unknown,
): ObraPdorTemporalWindow | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return null;
  }
  const window = value as Record<string, unknown>;
  const requiredFields = [
    "inicioProgramacao",
    "fimProgramacao",
    "dataReferencia",
    "janelaEquipamentosDias",
    "serieHistoricaSemanal",
  ];
  if (requiredFields.some(
    (field) => !Object.prototype.hasOwnProperty.call(window, field),
  )) {
    return null;
  }
  const start = window.inicioProgramacao;
  const end = window.fimProgramacao;
  const referenceDate = window.dataReferencia;
  const equipmentWindow = window.janelaEquipamentosDias;
  const weeklySeries = window.serieHistoricaSemanal;
  if (
    (start !== null && (typeof start !== "string" || !start.trim())) ||
    (end !== null && (typeof end !== "string" || !end.trim())) ||
    typeof referenceDate !== "string" ||
    !referenceDate.trim() ||
    typeof equipmentWindow !== "number" ||
    !Number.isSafeInteger(equipmentWindow) ||
    typeof weeklySeries !== "boolean"
  ) {
    return null;
  }
  return {
    inicioProgramacao: typeof start === "string" ? start.trim() : null,
    fimProgramacao: typeof end === "string" ? end.trim() : null,
    dataReferencia: referenceDate.trim(),
    janelaEquipamentosDias: equipmentWindow,
    serieHistoricaSemanal: weeklySeries,
  };
}

export function obraPdorFromApi(api: ObraPdorApi): ObraPdor {
  return {
    id: api.id,
    obraId: api.obra?.id ?? "",
    dataReferencia: api.dataReferencia,
    janelaTemporal: temporalWindowFromApi(api.janelaTemporal),
    dataExecucao: api.dataExecucao,
    versaoModelo: api.versaoModelo ?? null,
    versaoPremissas: api.versaoPremissas ?? null,
    versaoDados: api.versaoDados ?? null,
    statusExecucao: api.statusExecucao,
    statusExecucaoLabel: api.statusExecucaoLabel,
    calibracao: api.calibracao,
    calibracaoLabel: api.calibracaoLabel,
    risco: api.risco,
    riscoLabel: api.riscoLabel,
    faseLabel: api.faseLabel,
    receitaPrevistaFinal:
      toNumber(api.racs?.ponderado) ?? toNumber(api.receitaEstimadaFinal),
    p10: toNumber(api.p10),
    p50: toNumber(api.p50),
    p80: toNumber(api.p80),
    p95: toNumber(api.p95),
    probabilidadeAbaixoContrato: toNumber(api.probabilidadeAbaixoContrato),
    confianca: toNumber(api.confianca),
    drivers: driversFromApi(api.drivers),
    warnings: warningsFromApi(api.warnings),
    featuresUtilizadas: explanationItemsFromApi(api.featuresUtilizadas),
    dadosAusentes: explanationItemsFromApi(api.dadosAusentes),
    limitacoes: explanationItemsFromApi(api.limitacoes),
    alertas: explanationItemsFromApi(api.alertasDerivados),
    recomendacoes: explanationItemsFromApi(api.recomendacoes),
    comparacaoAnterior: comparisonFromApi(api.comparacaoAnterior),
    evidencias: evidencesFromApi(api.evidencias),
    iniciadoPor: api.iniciadoPor ?? null,
    tipoIniciador: api.tipoIniciador ?? null,
    algorithmVersion: api.algorithmVersion ?? null,
    evidenceIds: stringArray(api.evidenceIds),
    evidenceHighWaterMark:
      typeof api.evidenceHighWaterMark === "number" &&
      Number.isSafeInteger(api.evidenceHighWaterMark)
        ? api.evidenceHighWaterMark
        : null,
    coverageCode: api.coverageCode ?? null,
    assumptions: objectPayload(api.assumptions),
    executedAtUtc: api.executedAtUtc ?? null,
    stale: api.stale === true,
    current: api.current === true,
    erroExecucao: api.erroExecucao,
  };
}

/**
 * Busca o snapshot PDOR mais recente da obra. Retorna null quando a obra
 * ainda não tem nenhum cálculo (404) — o próximo RDO dispara o cálculo
 * automaticamente no backend.
 */
export async function buscarPdorAtual(
  obraId: string,
): Promise<ObraPdor | null> {
  const response = await apiFetch(
    `/obras/${encodeURIComponent(obraId)}/pdor/atual`,
  );

  if (response.status === 404) {
    return null;
  }

  const data = await readJson<ObraPdorApi>(response);
  const pdor = obraPdorFromApi(data);
  if (pdor.obraId !== obraId) {
    throw new Error(
      "O servidor retornou um PDOR fora da obra financeira solicitada.",
    );
  }
  return pdor;
}
