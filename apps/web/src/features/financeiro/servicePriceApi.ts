import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import {
  exactScaledInteger,
  sumExactDecimals,
} from "./revenueDecimal";

export type DecimalValue = number | string;

export interface ServiceCatalogService {
  id: string;
  code: string;
  name: string;
  description: string | null;
  status: string;
  createdAt: string;
}

export interface ServicePriceVersion {
  id: string;
  obraId: string;
  serviceId: string;
  unit: string;
  currency: string;
  version: number;
  unitPrice: DecimalValue;
  validFrom: string;
  validTo: string | null;
  supersedesId: string | null;
  status: string;
  effectiveValidTo: string | null;
  createdAt: string;
  /** Canonical ontology entity version used for safe offline transitions. */
  entityVersion?: number;
  /** Audit source is optional for backwards-compatible server responses. */
  source?: string | null;
}

export interface ServiceCatalogRow {
  service: ServiceCatalogService;
  priceVersions: ServicePriceVersion[];
}

export interface ServiceCatalogPage {
  items: ServiceCatalogRow[];
  nextCursor: string | null;
  authorizedItemCount: number;
  authorizedPriceVersionCount: number;
  authorizedCancellationCount: number;
  returnedItemCount: number;
  returnedPriceVersionCount: number;
  returnedCancellationCount: number;
  coverage: string;
  highWaterMark: number;
}

export interface RevenueTraceRow {
  worksiteId: string;
  worksiteName: string;
  rdoId: string;
  rdoNumber: string;
  executionId: string;
  executionDate: string;
  serviceId: string;
  serviceCode: string;
  serviceName: string;
  priceVersionId: string;
  priceVersion: number;
  quantity: DecimalValue;
  unit: string;
  unitPrice: DecimalValue;
  currency: string;
  revenue: DecimalValue;
  coverageCode: string;
  revenueEvidenceId: string;
  revenueEventId: string;
  eventCommitSequence: number;
  acceptedAt: string;
}

export interface RevenueTraceResponse {
  from: string;
  to: string;
  totalRevenue: DecimalValue;
  evidenceCount: number;
  rows: RevenueTraceRow[];
}

export interface RevenueOntologyLink {
  sourceType: string;
  sourceId: string;
  relationType: string;
  targetType: string;
  targetId: string;
  active: boolean;
}

export interface RevenueTraceEvidence {
  row: RevenueTraceRow;
  ontologyLinks: RevenueOntologyLink[];
}

function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`O servidor retornou ${field} em formato inválido.`);
  }
  return value as Record<string, unknown>;
}

function requiredString(
  value: unknown,
  field: string,
  maxLength = 512,
): string {
  if (
    typeof value !== "string" ||
    !value.trim() ||
    value.length > maxLength
  ) {
    throw new Error(`O servidor retornou ${field} em formato inválido.`);
  }
  return value;
}

function requiredUuid(value: unknown, field: string): string {
  const uuid = requiredString(value, field, 36).toLowerCase();
  if (
    !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/
      .test(uuid)
  ) {
    throw new Error(`O servidor retornou ${field} em formato inválido.`);
  }
  return uuid;
}

function requiredDate(value: unknown, field: string): string {
  const date = requiredString(value, field, 10);
  const parsed = new Date(`${date}T00:00:00Z`);
  if (
    !/^\d{4}-\d{2}-\d{2}$/.test(date) ||
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== date
  ) {
    throw new Error(`O servidor retornou ${field} em formato inválido.`);
  }
  return date;
}

function nonNegativeInteger(value: unknown, field: string): number {
  if (!Number.isSafeInteger(value) || (value as number) < 0) {
    throw new Error(`O servidor retornou ${field} em formato inválido.`);
  }
  return value as number;
}

function decimalValue(
  value: unknown,
  scale: number,
  field: string,
): DecimalValue {
  if (typeof value !== "string" && typeof value !== "number") {
    throw new Error(
      `O servidor retornou um valor decimal inválido em ${field}.`,
    );
  }
  exactScaledInteger(value, scale, field);
  return value;
}

function parseRevenueTraceRow(
  value: unknown,
  expectedWorksiteId = "",
  allowedWorksiteIds?: ReadonlySet<string>,
): RevenueTraceRow {
  const body = record(value, "uma evidência de receita");
  const worksiteId = requiredUuid(body.worksiteId, "worksiteId");
  if (expectedWorksiteId && worksiteId !== expectedWorksiteId) {
    throw new Error(
      "O servidor retornou evidência fora da obra financeira solicitada.",
    );
  }
  if (allowedWorksiteIds && !allowedWorksiteIds.has(worksiteId)) {
    throw new Error(
      "O servidor retornou evidência fora do escopo autorizado da sessão.",
    );
  }
  const currency = requiredString(body.currency, "currency", 3);
  if (currency !== "BRL") {
    throw new Error(
      "O servidor retornou uma moeda incompatível com o total de receita em BRL.",
    );
  }
  const coverageCode = requiredString(
    body.coverageCode,
    "coverageCode",
    64,
  );
  if (coverageCode !== "ACCEPTED_EXACT") {
    throw new Error(
      "O servidor retornou receita sem cobertura exata e aceita.",
    );
  }
  const acceptedAt = requiredString(body.acceptedAt, "acceptedAt");
  if (!Number.isFinite(Date.parse(acceptedAt))) {
    throw new Error("O servidor retornou acceptedAt em formato inválido.");
  }

  return {
    worksiteId,
    worksiteName: requiredString(body.worksiteName, "worksiteName"),
    rdoId: requiredUuid(body.rdoId, "rdoId"),
    rdoNumber: requiredString(body.rdoNumber, "rdoNumber"),
    executionId: requiredUuid(body.executionId, "executionId"),
    executionDate: requiredDate(body.executionDate, "executionDate"),
    serviceId: requiredUuid(body.serviceId, "serviceId"),
    serviceCode: requiredString(body.serviceCode, "serviceCode"),
    serviceName: requiredString(body.serviceName, "serviceName"),
    priceVersionId: requiredUuid(body.priceVersionId, "priceVersionId"),
    priceVersion: nonNegativeInteger(body.priceVersion, "priceVersion"),
    quantity: decimalValue(body.quantity, 3, "quantity"),
    unit: requiredString(body.unit, "unit", 32),
    unitPrice: decimalValue(body.unitPrice, 4, "unitPrice"),
    currency,
    revenue: decimalValue(body.revenue, 2, "revenue"),
    coverageCode,
    revenueEvidenceId: requiredUuid(
      body.revenueEvidenceId,
      "revenueEvidenceId",
    ),
    revenueEventId: requiredUuid(body.revenueEventId, "revenueEventId"),
    eventCommitSequence: nonNegativeInteger(
      body.eventCommitSequence,
      "eventCommitSequence",
    ),
    acceptedAt,
  };
}

export function parseRevenueTraceResponse(
  value: unknown,
  expectedWorksiteId = "",
  allowedWorksiteIds?: readonly string[],
): RevenueTraceResponse {
  const body = record(value, "o demonstrativo de receita");
  if (!Array.isArray(body.rows)) {
    throw new Error("O servidor retornou rows em formato inválido.");
  }
  const allowedWorksites = allowedWorksiteIds === undefined
    ? undefined
    : new Set(allowedWorksiteIds);
  const rows = body.rows.map((row) =>
    parseRevenueTraceRow(row, expectedWorksiteId, allowedWorksites)
  );
  const evidenceCount = nonNegativeInteger(
    body.evidenceCount,
    "evidenceCount",
  );
  if (evidenceCount !== rows.length) {
    throw new Error(
      "O servidor retornou cobertura de receita inconsistente.",
    );
  }
  const from = requiredDate(body.from, "from");
  const to = requiredDate(body.to, "to");
  if (to < from) {
    throw new Error("O servidor retornou um período de receita inválido.");
  }
  if (rows.some((row) =>
    row.executionDate < from || row.executionDate > to
  )) {
    throw new Error(
      "O servidor retornou uma evidência fora do período confirmado.",
    );
  }
  const totalRevenue = decimalValue(
    body.totalRevenue,
    2,
    "totalRevenue",
  );
  if (
    exactScaledInteger(totalRevenue, 2, "totalRevenue") !==
      sumExactDecimals(
        rows.map((row) => row.revenue),
        2,
        "revenue",
      )
  ) {
    throw new Error(
      "O servidor retornou totalRevenue incompatível com a soma exata das linhas.",
    );
  }
  return {
    from,
    to,
    totalRevenue,
    evidenceCount,
    rows,
  };
}

function parseRevenueTraceEvidence(value: unknown): RevenueTraceEvidence {
  const body = record(value, "a evidência detalhada de receita");
  if (!Array.isArray(body.ontologyLinks)) {
    throw new Error("O servidor retornou ontologyLinks em formato inválido.");
  }
  const ontologyLinks = body.ontologyLinks.map((value) => {
    const link = record(value, "uma relação ontológica");
    if (typeof link.active !== "boolean") {
      throw new Error("O servidor retornou active em formato inválido.");
    }
    return {
      sourceType: requiredString(link.sourceType, "sourceType"),
      sourceId: requiredString(link.sourceId, "sourceId"),
      relationType: requiredString(link.relationType, "relationType"),
      targetType: requiredString(link.targetType, "targetType"),
      targetId: requiredString(link.targetId, "targetId"),
      active: link.active,
    };
  });
  return {
    row: parseRevenueTraceRow(body.row),
    ontologyLinks,
  };
}

async function readJson<T>(response: Response): Promise<T> {
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  if (body === null) {
    throw new Error("O servidor retornou uma resposta financeira vazia.");
  }
  return body as T;
}

export async function fetchServiceCatalog(
  obraId: string,
  query = "",
  cursor = "",
): Promise<ServiceCatalogPage> {
  const params = new URLSearchParams();
  if (query.trim()) params.set("query", query.trim());
  if (cursor.trim()) params.set("cursor", cursor.trim());
  params.set("limit", "100");
  return readJson(await apiFetch(
    `/obras/${encodeURIComponent(obraId)}/financeiro/catalogo-servicos?${params}`,
  ));
}

export async function fetchCompleteServiceCatalog(
  obraId: string,
): Promise<ServiceCatalogPage> {
  const items: ServiceCatalogRow[] = [];
  const serviceIds = new Set<string>();
  const seenCursors = new Set<string>();
  let cursor = "";
  let lastPage: ServiceCatalogPage | null = null;

  for (let pageNumber = 0; pageNumber < 1_000; pageNumber += 1) {
    const page = await fetchServiceCatalog(obraId, "", cursor);
    if (lastPage && page.highWaterMark !== lastPage.highWaterMark) {
      throw new Error("O snapshot do catálogo mudou durante a paginação.");
    }
    for (const row of page.items) {
      if (serviceIds.has(row.service.id)) {
        throw new Error("O servidor repetiu um serviço na paginação do catálogo.");
      }
      serviceIds.add(row.service.id);
      items.push(row);
    }
    lastPage = page;
    if (!page.nextCursor) {
      return {
        ...page,
        items,
        nextCursor: null,
        returnedItemCount: items.length,
        returnedPriceVersionCount: items.reduce(
          (total, row) => total + row.priceVersions.length,
          0,
        ),
        coverage: "COMPLETE",
      };
    }
    if (seenCursors.has(page.nextCursor)) {
      throw new Error("O servidor retornou um cursor repetido para o catálogo.");
    }
    seenCursors.add(page.nextCursor);
    cursor = page.nextCursor;
  }
  throw new Error("O catálogo excedeu o limite seguro de paginação.");
}

export async function fetchRevenueTrace(
  obraId: string,
  from = "",
  to = "",
): Promise<RevenueTraceResponse> {
  const params = new URLSearchParams({ obraId });
  if (from) params.set("de", from);
  if (to) params.set("ate", to);
  return parseRevenueTraceResponse(
    await readJson(await apiFetch(
      `/financeiro/rastreio-receita?${params}`,
    )),
    obraId,
  );
}

export async function fetchRevenueTraceEvidence(
  executionId: string,
): Promise<RevenueTraceEvidence> {
  return parseRevenueTraceEvidence(
    await readJson(await apiFetch(
      `/financeiro/rastreio-receita/${encodeURIComponent(executionId)}`,
    )),
  );
}
