import { ApiTransportError } from "../../lib/api/apiClient";
import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  FinanceRevenueTraceCacheRecord,
} from "../../lib/db/db.types";
import {
  getSession,
  requireDataScope,
} from "../auth/authSession";
import {
  fetchRevenueTrace,
  parseRevenueTraceResponse,
  type RevenueTraceResponse,
} from "./servicePriceApi";

const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

export const REVENUE_TRACE_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000;

export interface RevenueTraceRequest {
  obraId: string;
  from: string;
  to: string;
}

export interface RevenueTraceSnapshot {
  response: RevenueTraceResponse;
  mode: "ONLINE" | "OFFLINE_CACHE";
  fetchedAt: string;
  source: "SERVER_CONFIRMED";
  coverage: FinanceRevenueTraceCacheRecord["coverage"];
}

type CacheReadResult =
  | { status: "HIT"; snapshot: RevenueTraceSnapshot }
  | { status: "MISS" }
  | { status: "STALE" };

interface RevenueTraceIdentity {
  ownerId: string;
  scopeMaterial: string;
  allowedWorksiteIds?: readonly string[];
  request: RevenueTraceRequest;
  key: FinanceRevenueTraceCacheRecord["key"];
}

function canonicalDateFilter(value: string, field: string): string {
  const normalized = value.trim();
  if (!normalized) return "";
  if (!DATE_PATTERN.test(normalized)) {
    throw new Error(`${field} deve ser uma data no formato ISO.`);
  }
  const parsed = new Date(`${normalized}T00:00:00Z`);
  if (
    Number.isNaN(parsed.getTime()) ||
    parsed.toISOString().slice(0, 10) !== normalized
  ) {
    throw new Error(`${field} deve ser uma data válida.`);
  }
  return normalized;
}

function currentIdentity(
  request: RevenueTraceRequest,
): RevenueTraceIdentity {
  const session = getSession();
  if (!session) {
    throw new Error(
      "Sessão válida obrigatória para acessar receita confirmada.",
    );
  }
  const obraId = request.obraId.trim().toLowerCase();
  if (obraId && !UUID_PATTERN.test(obraId)) {
    throw new Error("A obra do demonstrativo deve possuir um ID canônico.");
  }
  if (
    obraId &&
    !session.escopoGlobal &&
    !session.obraIds.includes(obraId)
  ) {
    throw new Error(
      "A sessão atual não possui acesso à obra deste demonstrativo.",
    );
  }
  const from = canonicalDateFilter(request.from, "De");
  const to = canonicalDateFilter(request.to, "Até");
  if (from && to && to < from) {
    throw new Error("O período do demonstrativo é inválido.");
  }
  const { ownerId, scopeMaterial } = requireDataScope();
  const canonicalRequest = { obraId, from, to };
  return {
    ownerId,
    scopeMaterial,
    allowedWorksiteIds: session.escopoGlobal
      ? undefined
      : [...session.obraIds],
    request: canonicalRequest,
    key: [ownerId, scopeMaterial, obraId, from, to],
  };
}

function coverage(
  response: RevenueTraceResponse,
): FinanceRevenueTraceCacheRecord["coverage"] {
  return {
    status: "COMPLETE_ACCEPTED_EXACT",
    from: response.from,
    to: response.to,
    evidenceCount: response.evidenceCount,
  };
}

async function cacheConfirmedResponse(
  identity: RevenueTraceIdentity,
  response: RevenueTraceResponse,
  now: number,
): Promise<RevenueTraceSnapshot> {
  const confirmed = parseRevenueTraceResponse(
    response,
    identity.request.obraId,
    identity.allowedWorksiteIds,
  );
  const fetchedAt = new Date(now).toISOString();
  const record: FinanceRevenueTraceCacheRecord = {
    key: identity.key,
    ownerId: identity.ownerId,
    scopeMaterial: identity.scopeMaterial,
    obraId: identity.request.obraId,
    fromFilter: identity.request.from,
    toFilter: identity.request.to,
    fetchedAt,
    expiresAt: new Date(now + REVENUE_TRACE_CACHE_MAX_AGE_MS).toISOString(),
    source: "SERVER_CONFIRMED",
    coverage: coverage(confirmed),
    response: confirmed,
  };
  const database = await getCortexDb();
  await database.put("finance_revenue_trace_cache", record);
  return {
    response: confirmed,
    mode: "ONLINE",
    fetchedAt,
    source: record.source,
    coverage: record.coverage,
  };
}

async function readConfirmedCache(
  identity: RevenueTraceIdentity,
  now: number,
): Promise<CacheReadResult> {
  const database = await getCortexDb();
  const cached = await database.get(
    "finance_revenue_trace_cache",
    identity.key,
  );
  if (!cached) return { status: "MISS" };
  if (
    cached.ownerId !== identity.ownerId ||
    cached.scopeMaterial !== identity.scopeMaterial ||
    cached.obraId !== identity.request.obraId ||
    cached.fromFilter !== identity.request.from ||
    cached.toFilter !== identity.request.to ||
    cached.source !== "SERVER_CONFIRMED" ||
    cached.coverage.status !== "COMPLETE_ACCEPTED_EXACT"
  ) {
    return { status: "MISS" };
  }
  const fetchedAtMs = Date.parse(cached.fetchedAt);
  const expiresAtMs = Date.parse(cached.expiresAt);
  if (
    !Number.isFinite(fetchedAtMs) ||
    !Number.isFinite(expiresAtMs) ||
    expiresAtMs - fetchedAtMs !== REVENUE_TRACE_CACHE_MAX_AGE_MS
  ) {
    return { status: "MISS" };
  }
  if (now > expiresAtMs) {
    return { status: "STALE" };
  }
  let response: RevenueTraceResponse;
  try {
    response = parseRevenueTraceResponse(
      cached.response,
      identity.request.obraId,
      identity.allowedWorksiteIds,
    );
  } catch {
    return { status: "MISS" };
  }
  if (
    cached.coverage.from !== response.from ||
    cached.coverage.to !== response.to ||
    cached.coverage.evidenceCount !== response.evidenceCount
  ) {
    return { status: "MISS" };
  }
  return {
    status: "HIT",
    snapshot: {
      response,
      mode: "OFFLINE_CACHE",
      fetchedAt: cached.fetchedAt,
      source: cached.source,
      coverage: cached.coverage,
    },
  };
}

async function confirmedFallback(
  identity: RevenueTraceIdentity,
  now: number,
): Promise<RevenueTraceSnapshot> {
  const cached = await readConfirmedCache(identity, now);
  if (cached.status === "HIT") return cached.snapshot;
  if (cached.status === "STALE") {
    throw new Error(
      "O último demonstrativo confirmado expirou. Reconecte para atualizar a receita.",
    );
  }
  throw new Error(
    "Nenhum demonstrativo confirmado está disponível offline para este usuário, obra e período.",
  );
}

export async function loadRevenueTraceSnapshot(
  request: RevenueTraceRequest,
): Promise<RevenueTraceSnapshot> {
  const identity = currentIdentity(request);
  const now = Date.now();
  const online = typeof navigator === "undefined" || navigator.onLine;
  if (!online) {
    return confirmedFallback(identity, now);
  }

  try {
    const response = await fetchRevenueTrace(
      identity.request.obraId,
      identity.request.from,
      identity.request.to,
    );
    return await cacheConfirmedResponse(identity, response, now);
  } catch (error: unknown) {
    if (!(error instanceof ApiTransportError)) {
      throw error;
    }
    return confirmedFallback(identity, now);
  }
}
