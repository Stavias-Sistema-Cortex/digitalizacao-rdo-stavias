import {
  apiFetch,
  apiUrl,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import { financeQueryParams } from "./financeiroFilters";
import { ensureRegisteredDevice } from "../../lib/sync/registerDevice";
import type {
  FinanceAuditEvent,
  FinanceAllocation,
  FinanceAllocationHistoryEntry,
  FinanceAllocationItem,
  FinanceAllocationSourceType,
  FinanceCapabilities,
  FinanceCategory,
  FinanceCharge,
  FinanceChargePreview,
  FinanceCostCenter,
  FinanceFilters,
  FinanceFiscalExtraction,
  FinanceInvoice,
  FinanceInvoiceDraft,
  FinanceInvoiceDocument,
  FinanceLedgerEntry,
  FinanceLedgerDraft,
  FinanceOverview,
  FinancePurchase,
  FinancePurchaseDraft,
  FinanceReportRow,
  FinanceStatusDefinition,
  FinanceSettlement,
  FinanceSupplier,
  FinanceControlUnit,
  FinanceControlUnitType,
} from "./financeiro.types";

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

function jsonRequest(method: string, body: unknown): RequestInit {
  return {
    method,
    headers: {
      "Content-Type": "application/json",
      "X-Correlation-Id": crypto.randomUUID(),
    },
    body: JSON.stringify(body),
  };
}

function endpoint(path: string, params?: URLSearchParams): string {
  const suffix = params?.toString();
  return suffix ? `${path}?${suffix}` : path;
}

function scoped(filters: FinanceFilters): URLSearchParams {
  const params = financeQueryParams(filters);
  params.delete("moeda");
  params.delete("tipo");
  params.delete("prioridade");
  return params;
}

export async function buscarCapacidadesFinanceiras(
  obraId: string,
): Promise<FinanceCapabilities> {
  return readJson(await apiFetch(endpoint(
    "/financeiro/capacidades",
    new URLSearchParams({ obraId }),
  )));
}

export async function buscarCapacidadesUnidade(
  unidadeId: string,
): Promise<FinanceCapabilities> {
  return readJson(await apiFetch(endpoint(
    "/financeiro/capacidades",
    new URLSearchParams({ unidadeId }),
  )));
}

export async function buscarUnidadesFinanceiras(
  tipo?: FinanceControlUnitType,
  busca?: string,
): Promise<FinanceControlUnit[]> {
  const params = new URLSearchParams();
  if (tipo) params.set("tipo", tipo);
  params.set("status", "ATIVA");
  if (busca?.trim()) params.set("busca", busca.trim());
  return readJson(await apiFetch(endpoint(
    "/financeiro/unidades",
    params,
  )));
}

export async function criarUnidadeAdministrativa(
  codigo: string,
  nome: string,
): Promise<FinanceControlUnit> {
  return readJson(await apiFetch(
    "/financeiro/unidades/administrativas",
    jsonRequest("POST", { codigo, nome }),
  ));
}

export async function buscarRateios(
  unidadeId?: string,
): Promise<FinanceAllocation[]> {
  const params = new URLSearchParams();
  if (unidadeId?.trim()) params.set("unidadeId", unidadeId.trim());
  return readJson(await apiFetch(endpoint("/financeiro/rateios", params)));
}

export async function buscarRateioOrigem(
  origemTipo: FinanceAllocationSourceType,
  origemId: string,
): Promise<FinanceAllocation | null> {
  const response = await apiFetch(
    `/financeiro/rateios/origens/${origemTipo}/${encodeURIComponent(origemId)}`,
  );
  if (response.status === 404) return null;
  return readJson(response);
}

export async function salvarRateio(
  origemTipo: FinanceAllocationSourceType,
  origemId: string,
  itens: Pick<FinanceAllocationItem,
    "unidadeControleId" | "centroCustoId" | "categoriaId" | "valor">[],
  current?: FinanceAllocation | null,
): Promise<FinanceAllocation> {
  const deviceId = await ensureRegisteredDevice();
  return readJson(await apiFetch(
    `/financeiro/rateios/origens/${origemTipo}/${encodeURIComponent(origemId)}`,
    jsonRequest("PUT", {
      clientMutationId: crypto.randomUUID(),
      baseVersion: current?.versao ?? 0,
      origemTipo,
      origemId,
      itens,
      correlacaoId: crypto.randomUUID(),
      dispositivoId: deviceId,
    }),
  ));
}

export async function buscarHistoricoRateio(
  rateioId: string,
): Promise<{ rateioId: string; eventos: FinanceAllocationHistoryEntry[] }> {
  return readJson(await apiFetch(
    `/financeiro/rateios/${encodeURIComponent(rateioId)}/historico`,
  ));
}

export async function buscarFornecedores(
  obraId: string,
): Promise<FinanceSupplier[]> {
  return readJson(await apiFetch(endpoint(
    "/financeiro/fornecedores",
    new URLSearchParams({ obraId }),
  )));
}

export async function buscarCentrosCusto(
  obraId: string,
): Promise<FinanceCostCenter[]> {
  return readJson(await apiFetch(endpoint(
    "/financeiro/centros-custo",
    new URLSearchParams({ obraId }),
  )));
}

export async function buscarCategoriasFinanceiras(
  obraId: string,
): Promise<FinanceCategory[]> {
  return readJson(await apiFetch(endpoint(
    "/financeiro/categorias",
    new URLSearchParams({ obraId }),
  )));
}

export async function buscarStatusFinanceiros(
  obraId: string,
  agregadoTipo: "COMPRA" | "NOTA_FISCAL" | "LANCAMENTO",
): Promise<FinanceStatusDefinition[]> {
  return readJson(await apiFetch(endpoint(
    "/financeiro/status",
    new URLSearchParams({ obraId, agregadoTipo }),
  )));
}

export async function buscarCompras(
  filters: FinanceFilters,
): Promise<FinancePurchase[]> {
  const params = scoped(filters);
  if (filters.prioridade) {
    params.set("prioridade", filters.prioridade);
  }
  return readJson(await apiFetch(endpoint(
    "/financeiro/compras",
    params,
  )));
}

export async function salvarCompra(
  draft: FinancePurchaseDraft,
  current?: FinancePurchase,
): Promise<FinancePurchase> {
  const clientMutationId = crypto.randomUUID();
  const body = {
    id: current?.id ?? crypto.randomUUID(),
    clientMutationId,
    solicitacaoId: draft.solicitacaoId ?? null,
    ...draft,
    baseVersao: current?.versao ?? null,
  };
  const path = current
    ? `/financeiro/compras/${encodeURIComponent(current.id)}`
    : "/financeiro/compras";
  return readJson(await apiFetch(
    path,
    jsonRequest(current ? "PUT" : "POST", body),
  ));
}

export async function arquivarCompra(
  purchase: FinancePurchase,
): Promise<void> {
  const params = new URLSearchParams({
    obraId: purchase.obraId,
    baseVersao: String(purchase.versao),
    clientMutationId: crypto.randomUUID(),
  });
  const response = await apiFetch(endpoint(
    `/financeiro/compras/${encodeURIComponent(purchase.id)}`,
    params,
  ), { method: "DELETE" });
  if (!response.ok) {
    const body = await readResponseBody(response);
    throw new Error(responseErrorMessage(body, response.status));
  }
}

export async function buscarNotasFiscais(
  filters: FinanceFilters,
): Promise<FinanceInvoice[]> {
  return readJson(await apiFetch(endpoint(
    "/financeiro/notas-fiscais",
    scoped(filters),
  )));
}

export async function buscarDocumentosNota(
  invoiceId: string,
  obraId: string,
): Promise<FinanceInvoiceDocument[]> {
  return readJson(await apiFetch(endpoint(
    `/financeiro/notas-fiscais/${encodeURIComponent(invoiceId)}/anexos`,
    new URLSearchParams({ obraId }),
  )));
}

export async function enviarDocumentoFiscal(
  file: File,
  obraId: string,
  clientMutationId: string,
): Promise<FinanceFiscalExtraction> {
  const [deviceId, clientSha256] = await Promise.all([
    ensureRegisteredDevice(),
    sha256(file),
  ]);
  const params = new URLSearchParams({
    obraId,
    clientMutationId,
    sha256Cliente: clientSha256,
    dispositivoId: deviceId,
  });
  const body = new FormData();
  body.append("arquivo", file);
  return readJson(await apiFetch(endpoint(
    "/financeiro/notas-fiscais/extracoes",
    params,
  ), {
    method: "POST",
    headers: { "X-Correlation-Id": crypto.randomUUID() },
    body,
  }));
}

export async function vincularDocumentoNota(
  invoice: Pick<FinanceInvoice, "id" | "obraId">,
  extraction: FinanceFiscalExtraction,
  documentType: string,
  principal: boolean,
  clientMutationId: string,
): Promise<FinanceInvoiceDocument> {
  const deviceId = await ensureRegisteredDevice();
  return readJson(await apiFetch(endpoint(
    `/financeiro/notas-fiscais/${encodeURIComponent(invoice.id)}/anexos`,
    new URLSearchParams({ obraId: invoice.obraId }),
  ), jsonRequest("POST", {
    id: crypto.randomUUID(),
    storedObjectId: extraction.storedObjectId,
    tipoDocumento: documentType,
    principal,
    clientMutationId,
    extracaoJobId: extraction.id,
    sha256Cliente: extraction.clientSha256,
    dispositivoId: deviceId,
  })));
}

export async function salvarNotaFiscal(
  draft: FinanceInvoiceDraft,
  current?: FinanceInvoice,
): Promise<FinanceInvoice> {
  const body = {
    id: current?.id ?? crypto.randomUUID(),
    clientMutationId: crypto.randomUUID(),
    ...draft,
    compras: [],
    baseVersao: current?.versao ?? null,
  };
  const path = current
    ? `/financeiro/notas-fiscais/${encodeURIComponent(current.id)}`
    : "/financeiro/notas-fiscais";
  return readJson(await apiFetch(
    path,
    jsonRequest(current ? "PUT" : "POST", body),
  ));
}

async function sha256(file: File): Promise<string> {
  const digest = await crypto.subtle.digest(
    "SHA-256",
    await file.arrayBuffer(),
  );
  return Array.from(new Uint8Array(digest))
    .map((value) => value.toString(16).padStart(2, "0"))
    .join("");
}

export async function arquivarNotaFiscal(
  invoice: FinanceInvoice,
): Promise<void> {
  const params = new URLSearchParams({
    obraId: invoice.obraId,
    baseVersao: String(invoice.versao),
    clientMutationId: crypto.randomUUID(),
  });
  const response = await apiFetch(endpoint(
    `/financeiro/notas-fiscais/${encodeURIComponent(invoice.id)}`,
    params,
  ), { method: "DELETE" });
  if (!response.ok) {
    const body = await readResponseBody(response);
    throw new Error(responseErrorMessage(body, response.status));
  }
}

export function urlConteudoDocumento(
  invoiceId: string,
  documentId: string,
  obraId: string,
): string {
  return apiUrl(endpoint(
    `/financeiro/notas-fiscais/${encodeURIComponent(invoiceId)}` +
      `/anexos/${encodeURIComponent(documentId)}/conteudo`,
    new URLSearchParams({ obraId }),
  ));
}

export async function buscarLancamentos(
  filters: FinanceFilters,
): Promise<FinanceLedgerEntry[]> {
  const params = financeQueryParams(filters);
  params.delete("moeda");
  params.delete("prioridade");
  return readJson(await apiFetch(endpoint(
    "/financeiro/lancamentos",
    params,
  )));
}

export async function salvarLancamento(
  draft: FinanceLedgerDraft,
  current?: FinanceLedgerEntry,
): Promise<FinanceLedgerEntry> {
  const body = {
    id: current?.id ?? crypto.randomUUID(),
    clientMutationId: crypto.randomUUID(),
    ...draft,
    alocacoes: [],
    vinculosOrcamento: [],
    baseVersao: current?.versao ?? null,
  };
  const path = current
    ? `/financeiro/lancamentos/${encodeURIComponent(current.id)}`
    : "/financeiro/lancamentos";
  return readJson(await apiFetch(
    path,
    jsonRequest(current ? "PUT" : "POST", body),
  ));
}

export async function arquivarLancamento(
  ledger: FinanceLedgerEntry,
): Promise<void> {
  const params = new URLSearchParams({
    obraId: ledger.obraId,
    baseVersao: String(ledger.versao),
    clientMutationId: crypto.randomUUID(),
  });
  const response = await apiFetch(endpoint(
    `/financeiro/lancamentos/${encodeURIComponent(ledger.id)}`,
    params,
  ), { method: "DELETE" });
  if (!response.ok) {
    const body = await readResponseBody(response);
    throw new Error(responseErrorMessage(body, response.status));
  }
}

export async function registrarLiquidacao(
  ledger: FinanceLedgerEntry,
  valor: number,
  dataLiquidacao: string,
  meio: string,
): Promise<FinanceSettlement> {
  const settlementId = crypto.randomUUID();
  const body = {
    id: settlementId,
    clientMutationId: crypto.randomUUID(),
    obraId: ledger.obraId,
    tipo: ledger.tipo === "RECEBER" ? "RECEBIMENTO" : "PAGAMENTO",
    status: "CONFIRMADA",
    dataLiquidacao,
    moeda: ledger.moeda,
    valorTotal: valor,
    meio,
    referenciaBancaria: null,
    observacoes: null,
    lancamentos: [{
      id: crypto.randomUUID(),
      lancamentoId: ledger.id,
      valorAplicado: valor,
    }],
    baseVersao: null,
  };
  return readJson(await apiFetch(
    "/financeiro/liquidacoes",
    jsonRequest("POST", body),
  ));
}

export async function salvarCentroCusto(
  obraId: string,
  draft: Pick<FinanceCostCenter, "id" | "codigo" | "nome" | "descricao" | "responsavelId">,
): Promise<FinanceCostCenter> {
  return readJson(await apiFetch(endpoint(
    "/financeiro/centros-custo",
    new URLSearchParams({ obraId }),
  ), jsonRequest("POST", draft)));
}

export async function buscarVisaoGeral(
  filters: FinanceFilters,
): Promise<FinanceOverview> {
  const params = financeQueryParams(filters);
  params.delete("prioridade");
  return readJson(await apiFetch(endpoint(
    "/financeiro/visao-geral",
    params,
  )));
}

export async function buscarRelatorio(
  filters: FinanceFilters,
  agruparPor: string,
): Promise<FinanceReportRow[]> {
  const params = financeQueryParams(filters);
  params.delete("prioridade");
  params.set("agruparPor", agruparPor);
  return readJson(await apiFetch(endpoint(
    "/financeiro/relatorios",
    params,
  )));
}

export async function exportarRelatorio(
  filters: FinanceFilters,
  agruparPor: string,
): Promise<Blob> {
  const params = financeQueryParams(filters);
  params.delete("prioridade");
  params.set("agruparPor", agruparPor);
  const response = await apiFetch(endpoint(
    "/financeiro/relatorios/exportacoes",
    params,
  ));
  if (!response.ok) {
    const body = await readResponseBody(response);
    throw new Error(responseErrorMessage(body, response.status));
  }
  return response.blob();
}

interface ChargeApiResponse {
  id: string;
  obraId: string;
  regraId: string | null;
  modeloEmailId: string;
  perfilRemetenteId: string;
  replyToPermitidoId: string | null;
  fornecedorId: string | null;
  alvoTipo: string;
  alvoId: string;
  responsavelId: string | null;
  modo: string;
  status: string;
  destinatario: string;
  vencimentoEm: string | null;
  ocorrenciaPrevistaEm: string | null;
  agendadaPara: string | null;
  tentativas: number;
  proximaTentativaEm: string | null;
  provider: string | null;
  providerMessageId: string | null;
  enviadaEm: string | null;
  erroCategoria: string | null;
  erroSanitizado: string | null;
  versaoLinha: number;
}

export async function buscarCobrancas(
  obraId: string,
): Promise<FinanceCharge[]> {
  return readJson<ChargeApiResponse[]>(await apiFetch(endpoint(
    "/financeiro/cobrancas",
    new URLSearchParams({ obraId }),
  )));
}

interface PreviewApiResponse {
  cobrancaId: string;
  destinatario: string;
  replyTo: string | null;
  subject: string;
  textBody: string;
  previewHash: string;
  confirmedAt: string;
}

export async function previsualizarCobranca(
  chargeId: string,
  obraId: string,
): Promise<FinanceChargePreview> {
  const response = await readJson<PreviewApiResponse>(await apiFetch(endpoint(
    `/financeiro/cobrancas/${encodeURIComponent(chargeId)}/previsualizacao`,
    new URLSearchParams({ obraId }),
  ), jsonRequest("POST", null)));
  return {
    cobrancaId: response.cobrancaId,
    destinatario: response.destinatario,
    replyTo: response.replyTo,
    assunto: response.subject,
    corpoTexto: response.textBody,
    hashConteudo: response.previewHash,
    confirmadaEm: response.confirmedAt,
  };
}

export async function enviarCobranca(
  chargeId: string,
  obraId: string,
): Promise<FinanceCharge> {
  return readJson(await apiFetch(endpoint(
    `/financeiro/cobrancas/${encodeURIComponent(chargeId)}/enviar`,
    new URLSearchParams({ obraId }),
  ), jsonRequest("POST", null)));
}

interface TimelineEventApi {
  id: string;
  type: string;
  occurredAt: string | null;
  origin: string | null;
  payload: unknown;
}

export async function buscarAuditoriaFinanceira(
  entityType: string,
  entityId: string,
): Promise<FinanceAuditEvent[]> {
  const events = await readJson<TimelineEventApi[]>(await apiFetch(endpoint(
    "/ontology/timeline",
    new URLSearchParams({ entityType, entityId, limit: "50" }),
  )));
  return events.map((event) => ({
    id: event.id,
    type: event.type,
    occurredAt: event.occurredAt,
    origin: event.origin,
    payload: event.payload && typeof event.payload === "object" &&
      !Array.isArray(event.payload)
      ? event.payload as Record<string, unknown>
      : {},
  }));
}
