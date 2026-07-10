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
    occurredAt: api.occurredAt,
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
  dataReferencia: string | null;
  dataExecucao: string | null;
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
  erroExecucao: string | null;
}

export interface ObraPdorDriver {
  code: string;
  description: string;
  impact: number | null;
  evidence: string | null;
}

export interface ObraPdor {
  id: string;
  dataReferencia: string | null;
  dataExecucao: string | null;
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

export function obraPdorFromApi(api: ObraPdorApi): ObraPdor {
  return {
    id: api.id,
    dataReferencia: api.dataReferencia,
    dataExecucao: api.dataExecucao,
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
  return obraPdorFromApi(data);
}
