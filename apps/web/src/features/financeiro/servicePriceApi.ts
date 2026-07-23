import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

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
  from: string | null;
  to: string | null;
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
  return readJson(await apiFetch(
    `/financeiro/rastreio-receita?${params}`,
  ));
}

export async function fetchRevenueTraceEvidence(
  executionId: string,
): Promise<RevenueTraceEvidence> {
  return readJson(await apiFetch(
    `/financeiro/rastreio-receita/${encodeURIComponent(executionId)}`,
  ));
}
