import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";
import type { DecimalValue } from "./servicePriceApi";
import { exactScaledInteger } from "./revenueDecimal";

export type PendingRevenuePriceState = "EXACT_ACTIVE" | "UNAVAILABLE";
export type PendingRevenueApprovalState =
  | "REGISTERED"
  | "LEGACY_UNVERIFIED";
export type PendingRevenueLegacyEvidenceState =
  | "NONE"
  | "VERIFIABLE"
  | "INVALID";

export interface PendingRevenueExecution {
  worksiteId: string;
  worksiteName: string;
  rdoId: string;
  rdoNumber: string | null;
  executionId: string;
  serviceId: string | null;
  serviceCode: string | null;
  serviceName: string;
  executionDate: string;
  quantity: DecimalValue;
  unit: string;
  rdoEntityVersion: number;
  approvalState: PendingRevenueApprovalState;
  legacyEvidenceState: PendingRevenueLegacyEvidenceState;
  priceState: PendingRevenuePriceState;
  priceReason: string;
  currentUnitPrice: DecimalValue | null;
  currency: "BRL" | null;
  evidenceUnitPrice: DecimalValue | null;
  evidenceCurrency: "BRL" | null;
}

export interface PendingRevenueDecision {
  rdoId: string;
  executionId: string;
  decision: "VALIDAR" | "REJEITAR";
  justification: string | null;
  baseVersion: number;
  clientMutationId: string;
}

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const UNAVAILABLE_PRICE_REASONS = new Set([
  "SERVICE_UNMAPPED",
  "SERVICE_NOT_FOUND",
  "SERVICE_INACTIVE",
  "UNIT_MISSING",
  "EXACT_PRICE_NOT_FOUND",
  "EXACT_PRICE_AMBIGUOUS",
]);

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

function optionalString(
  value: unknown,
  field: string,
  maxLength = 512,
): string | null {
  if (value === null) return null;
  return requiredString(value, field, maxLength);
}

function uuid(value: unknown, field: string): string {
  const normalized = requiredString(value, field, 36).toLowerCase();
  if (!UUID_PATTERN.test(normalized)) {
    throw new Error(`O servidor retornou ${field} em formato inválido.`);
  }
  return normalized;
}

function optionalUuid(value: unknown, field: string): string | null {
  return value === null ? null : uuid(value, field);
}

function isoDate(value: unknown, field: string): string {
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

function decimal(
  value: unknown,
  scale: number,
  field: string,
): DecimalValue {
  if (typeof value !== "string" && typeof value !== "number") {
    throw new Error(`O servidor retornou ${field} em formato inválido.`);
  }
  exactScaledInteger(value, scale, field);
  return value;
}

function optionalBrlPrice(
  body: Record<string, unknown>,
  priceField: string,
  currencyField: string,
): { price: DecimalValue | null; currency: "BRL" | null } {
  const price = body[priceField];
  const currency = body[currencyField];
  if (price === null && currency === null) {
    return { price: null, currency: null };
  }
  if (price === null || currency === null) {
    throw new Error("O servidor retornou preço pendente em formato inválido.");
  }
  const parsedCurrency = requiredString(currency, currencyField, 3);
  if (parsedCurrency !== "BRL") {
    throw new Error("O servidor retornou preço pendente em moeda inválida.");
  }
  return {
    price: decimal(price, 4, priceField),
    currency: parsedCurrency,
  };
}

function parsePendingRevenueExecution(value: unknown): PendingRevenueExecution {
  const body = record(value, "uma execução pendente");
  const approvalState = requiredString(
    body.approvalState,
    "approvalState",
    32,
  );
  const legacyEvidenceState = requiredString(
    body.legacyEvidenceState,
    "legacyEvidenceState",
    32,
  );
  const priceState = requiredString(body.priceState, "priceState", 32);
  const priceReason = requiredString(body.priceReason, "priceReason", 64);
  if (
    approvalState !== "REGISTERED" &&
    approvalState !== "LEGACY_UNVERIFIED"
  ) {
    throw new Error("O servidor retornou approvalState em formato inválido.");
  }
  if (
    legacyEvidenceState !== "NONE" &&
    legacyEvidenceState !== "VERIFIABLE" &&
    legacyEvidenceState !== "INVALID"
  ) {
    throw new Error(
      "O servidor retornou legacyEvidenceState em formato inválido.",
    );
  }
  if (priceState !== "EXACT_ACTIVE" && priceState !== "UNAVAILABLE") {
    throw new Error("O servidor retornou priceState em formato inválido.");
  }

  const common = {
    worksiteId: uuid(body.worksiteId, "worksiteId"),
    worksiteName: requiredString(body.worksiteName, "worksiteName"),
    rdoId: uuid(body.rdoId, "rdoId"),
    rdoNumber: optionalString(body.rdoNumber, "rdoNumber", 128),
    executionId: uuid(body.executionId, "executionId"),
    serviceId: optionalUuid(body.serviceId, "serviceId"),
    serviceCode: optionalString(body.serviceCode, "serviceCode", 128),
    serviceName: requiredString(body.serviceName, "serviceName"),
    executionDate: isoDate(body.executionDate, "executionDate"),
    quantity: decimal(body.quantity, 3, "quantity"),
    unit: requiredString(body.unit, "unit", 32),
    rdoEntityVersion: nonNegativeInteger(
      body.rdoEntityVersion,
      "rdoEntityVersion",
    ),
    approvalState: approvalState as PendingRevenueApprovalState,
    legacyEvidenceState: legacyEvidenceState as PendingRevenueLegacyEvidenceState,
    priceState: priceState as PendingRevenuePriceState,
    priceReason,
  };

  const evidence = optionalBrlPrice(
    body,
    "evidenceUnitPrice",
    "evidenceCurrency",
  );
  if (
    approvalState === "REGISTERED" &&
    (legacyEvidenceState !== "NONE" || evidence.price !== null)
  ) {
    throw new Error("O servidor retornou evidência legada em formato inválido.");
  }
  if (
    approvalState === "LEGACY_UNVERIFIED" &&
    legacyEvidenceState === "NONE"
  ) {
    throw new Error("O servidor retornou evidência legada em formato inválido.");
  }
  if (
    legacyEvidenceState === "VERIFIABLE" &&
    evidence.price === null
  ) {
    throw new Error("O servidor retornou evidência legada em formato inválido.");
  }
  if (legacyEvidenceState === "NONE" && evidence.price !== null) {
    throw new Error("O servidor retornou evidência legada em formato inválido.");
  }
  if (legacyEvidenceState === "INVALID" && evidence.price !== null) {
    throw new Error("O servidor retornou evidência legada em formato inválido.");
  }

  const currentPrice = optionalBrlPrice(
    body,
    "currentUnitPrice",
    "currency",
  );
  if (priceState === "EXACT_ACTIVE") {
    if (priceReason !== "EXACT_ACTIVE_PRICE") {
      throw new Error("O servidor retornou preço pendente em formato inválido.");
    }
    if (currentPrice.price === null) {
      throw new Error("O servidor retornou preço pendente em formato inválido.");
    }
    return {
      ...common,
      currentUnitPrice: currentPrice.price,
      currency: currentPrice.currency,
      evidenceUnitPrice: evidence.price,
      evidenceCurrency: evidence.currency,
    };
  }

  if (!UNAVAILABLE_PRICE_REASONS.has(priceReason)) {
    throw new Error("O servidor retornou motivo de preço pendente inválido.");
  }
  if (currentPrice.price !== null) {
    throw new Error("O servidor retornou preço pendente em formato inválido.");
  }
  return {
    ...common,
    currentUnitPrice: null,
    currency: null,
    evidenceUnitPrice: evidence.price,
    evidenceCurrency: evidence.currency,
  };
}

async function readJson(response: Response): Promise<unknown> {
  const body = await readResponseBody(response);
  if (!response.ok) {
    throw new Error(responseErrorMessage(body, response.status));
  }
  if (body === null) {
    throw new Error("O servidor retornou uma resposta financeira vazia.");
  }
  return body;
}

export async function fetchPendingRevenueExecutions(
  obraId: string,
): Promise<PendingRevenueExecution[]> {
  const params = new URLSearchParams({ obraId });
  const response = record(
    await readJson(await apiFetch(
      `/financeiro/rastreio-receita/pendentes?${params}`,
    )),
    "a fila de validação financeira",
  );
  if (!Array.isArray(response.rows)) {
    throw new Error("O servidor retornou a fila de validação em formato inválido.");
  }
  const rows = response.rows.map(parsePendingRevenueExecution);
  if (rows.some((row) => row.worksiteId !== obraId)) {
    throw new Error("O servidor retornou uma execução fora da obra solicitada.");
  }
  return rows;
}

export async function decidePendingRevenueExecution(
  decision: PendingRevenueDecision,
): Promise<void> {
  const body = await readJson(await apiFetch(
    `/rdos/${encodeURIComponent(decision.rdoId)}/execucoes/${
      encodeURIComponent(decision.executionId)
    }/decisoes`,
    {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
        "X-Correlation-Id": decision.clientMutationId,
      },
      body: JSON.stringify({
        decisao: decision.decision,
        justificativa: decision.justification,
        baseVersao: decision.baseVersion,
        clientMutationId: decision.clientMutationId,
      }),
    },
  ));
  record(body, "a decisão financeira");
}
