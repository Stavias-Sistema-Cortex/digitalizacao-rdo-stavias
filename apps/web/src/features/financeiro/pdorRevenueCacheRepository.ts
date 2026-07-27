import type { IDBPDatabase } from "idb";

import { ApiTransportError } from "../../lib/api/apiClient";
import {
  getCortexDb,
  type CortexDbSchema,
} from "../../lib/db/cortexDb";
import type {
  FinancePdorRevenueCacheRecord,
} from "../../lib/db/db.types";
import {
  getSession,
  requireDataScope,
} from "../auth/authSession";
import {
  buscarPdorAtual,
  type ObraPdor,
} from "../obras/obrasApi";

export const PDOR_REVENUE_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1_000;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;
const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ACCEPTED_COVERAGE = new Set([
  "COMPLETE_ACCEPTED_EXACT",
  "PARTIAL_ACCEPTED_EXACT",
  "NO_ACCEPTED_EVIDENCE",
] as const);

export interface PdorRevenueRequest {
  obraId: string;
}

export interface PdorRevenueSnapshot {
  pdor: ObraPdor | null;
  mode: "ONLINE" | "OFFLINE_CACHE";
  source: "SERVER_CONFIRMED";
  fetchedAt: string;
  provenance: FinancePdorRevenueCacheRecord["provenance"];
}

interface PdorRevenueDependencies {
  online?: boolean;
  now?: number;
  fetchCurrent?: (obraId: string) => Promise<ObraPdor | null>;
}

interface PdorRevenueIdentity {
  ownerId: string;
  scopeMaterial: string;
  sessionExpiresAt: string;
  request: PdorRevenueRequest;
  key: FinancePdorRevenueCacheRecord["key"];
}

type CacheReadResult =
  | { status: "HIT"; snapshot: PdorRevenueSnapshot }
  | { status: "MISS" }
  | { status: "STALE" };

function validIsoDate(value: unknown): value is string {
  if (typeof value !== "string" || !DATE_PATTERN.test(value)) {
    return false;
  }
  const parsed = new Date(`${value}T00:00:00Z`);
  return !Number.isNaN(parsed.getTime()) &&
    parsed.toISOString().slice(0, 10) === value;
}

function currentIdentity(request: PdorRevenueRequest): PdorRevenueIdentity {
  const session = getSession();
  if (!session) {
    throw new Error(
      "Sessão válida obrigatória para acessar a previsão PDOR.",
    );
  }
  const obraId = request.obraId.trim().toLowerCase();
  if (!UUID_PATTERN.test(obraId)) {
    throw new Error("A obra do PDOR deve possuir um ID canônico.");
  }
  if (!session.escopoGlobal && !session.obraIds.includes(obraId)) {
    throw new Error(
      "A sessão atual não possui acesso à obra desta previsão PDOR.",
    );
  }
  const { ownerId, scopeMaterial } = requireDataScope();
  const canonicalRequest = { obraId };
  return {
    ownerId,
    scopeMaterial,
    sessionExpiresAt: session.expiraEm,
    request: canonicalRequest,
    key: [ownerId, scopeMaterial, obraId],
  };
}

function assertIdentityCurrent(identity: PdorRevenueIdentity): void {
  try {
    const current = currentIdentity(identity.request);
    if (
      current.ownerId !== identity.ownerId ||
      current.scopeMaterial !== identity.scopeMaterial ||
      current.sessionExpiresAt !== identity.sessionExpiresAt ||
      current.key.some((part, index) => part !== identity.key[index])
    ) {
      throw new Error();
    }
  } catch {
    throw new Error(
      "A sessão mudou durante a consulta do PDOR; a resposta foi descartada.",
    );
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function finiteNonNegative(value: unknown): value is number {
  return typeof value === "number" &&
    Number.isFinite(value) &&
    value >= 0;
}

function probability(value: unknown): value is number {
  return finiteNonNegative(value) && value <= 1;
}

function validTimestamp(value: unknown): value is string {
  return typeof value === "string" &&
    value.trim().length > 0 &&
    Number.isFinite(Date.parse(value));
}

function assertTemporalWindow(
  value: unknown,
  referenceDate: string,
): asserts value is NonNullable<ObraPdor["janelaTemporal"]> {
  if (!isRecord(value)) {
    throw new Error(
      "O servidor não informou a janela temporal real do snapshot PDOR.",
    );
  }
  const start = value.inicioProgramacao;
  const end = value.fimProgramacao;
  if (
    (start !== null && !validIsoDate(start)) ||
    (end !== null && !validIsoDate(end)) ||
    value.dataReferencia !== referenceDate ||
    !Number.isSafeInteger(value.janelaEquipamentosDias) ||
    (value.janelaEquipamentosDias as number) < 0 ||
    typeof value.serieHistoricaSemanal !== "boolean" ||
    (typeof start === "string" &&
      typeof end === "string" &&
      end < start)
  ) {
    throw new Error(
      "A janela temporal do snapshot PDOR é inválida ou divergente.",
    );
  }
}

function assertConfirmedPdor(
  value: unknown,
  expectedWorksiteId: string,
): asserts value is ObraPdor {
  if (!isRecord(value)) {
    throw new Error("O servidor retornou um snapshot PDOR inválido.");
  }
  const evidenceIds = value.evidenceIds;
  const coverageCode = value.coverageCode;
  if (value.obraId !== expectedWorksiteId) {
    throw new Error(
      "O servidor retornou um PDOR fora da obra financeira solicitada.",
    );
  }
  if (
    typeof value.id !== "string" ||
    !UUID_PATTERN.test(value.id) ||
    value.statusExecucao !== "SUCCESS" ||
    value.current !== true ||
    value.stale !== false
  ) {
    throw new Error(
      "O servidor não confirmou um snapshot PDOR atual.",
    );
  }
  if (
    typeof value.dataReferencia !== "string" ||
    !validIsoDate(value.dataReferencia) ||
    !validTimestamp(value.executedAtUtc) ||
    typeof value.algorithmVersion !== "string" ||
    !value.algorithmVersion.trim() ||
    !Number.isSafeInteger(value.evidenceHighWaterMark) ||
    (value.evidenceHighWaterMark as number) < 0 ||
    typeof coverageCode !== "string" ||
    !ACCEPTED_COVERAGE.has(
      coverageCode as Exclude<
        FinancePdorRevenueCacheRecord["provenance"]["coverageCode"],
        "NO_CURRENT_SNAPSHOT"
      >,
    ) ||
    !Array.isArray(evidenceIds) ||
    evidenceIds.some((id) => typeof id !== "string" || !id.trim()) ||
    new Set(evidenceIds).size !== evidenceIds.length ||
    !isRecord(value.assumptions)
  ) {
    throw new Error(
      "A proveniência de receita do snapshot PDOR está incompleta.",
    );
  }
  assertTemporalWindow(value.janelaTemporal, value.dataReferencia);
  if (
    (coverageCode === "NO_ACCEPTED_EVIDENCE" && evidenceIds.length !== 0) ||
    (coverageCode !== "NO_ACCEPTED_EVIDENCE" && evidenceIds.length === 0)
  ) {
    throw new Error(
      "A cobertura do snapshot PDOR diverge das evidências confirmadas.",
    );
  }
  const percentiles = [value.p10, value.p50, value.p80, value.p95];
  if (
    !finiteNonNegative(value.receitaPrevistaFinal) ||
    percentiles.some((item) => !finiteNonNegative(item)) ||
    (value.p10 as number) > (value.p50 as number) ||
    (value.p50 as number) > (value.p80 as number) ||
    (value.p80 as number) > (value.p95 as number) ||
    !probability(value.probabilidadeAbaixoContrato) ||
    !probability(value.confianca)
  ) {
    throw new Error(
      "Os valores da projeção de receita PDOR são inválidos.",
    );
  }
}

function provenance(
  identity: PdorRevenueIdentity,
  pdor: ObraPdor | null,
): FinancePdorRevenueCacheRecord["provenance"] {
  return {
    snapshotId: pdor?.id ?? null,
    worksiteId: identity.request.obraId,
    referenceDate: pdor?.dataReferencia ?? null,
    temporalWindow: pdor?.janelaTemporal ?? null,
    evidenceHighWaterMark: pdor?.evidenceHighWaterMark ?? null,
    coverageCode: pdor?.coverageCode as
      FinancePdorRevenueCacheRecord["provenance"]["coverageCode"] ??
      "NO_CURRENT_SNAPSHOT",
    evidenceCount: pdor?.evidenceIds.length ?? 0,
    algorithmVersion: pdor?.algorithmVersion ?? null,
    executedAtUtc: pdor?.executedAtUtc ?? null,
  };
}

function canonicalJson(value: unknown): string {
  if (value === null) return "null";
  if (typeof value === "string" || typeof value === "boolean") {
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isFinite(value)) {
      throw new Error("O snapshot PDOR contém número inválido.");
    }
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    return `[${value.map(canonicalJson).join(",")}]`;
  }
  if (isRecord(value)) {
    return `{${Object.keys(value)
      .filter((key) => value[key] !== undefined)
      .sort()
      .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
      .join(",")}}`;
  }
  throw new Error("O snapshot PDOR contém valor não serializável.");
}

async function payloadHash(value: unknown): Promise<string> {
  const digest = new Uint8Array(
    await crypto.subtle.digest(
      "SHA-256",
      new TextEncoder().encode(canonicalJson(value)),
    ),
  );
  return Array.from(
    digest,
    (byte) => byte.toString(16).padStart(2, "0"),
  ).join("");
}

function sameProvenance(
  left: FinancePdorRevenueCacheRecord["provenance"],
  right: FinancePdorRevenueCacheRecord["provenance"],
): boolean {
  return canonicalJson(left) === canonicalJson(right);
}

async function cacheConfirmedResponse(
  database: IDBPDatabase<CortexDbSchema>,
  identity: PdorRevenueIdentity,
  pdor: ObraPdor | null,
  now: number,
): Promise<PdorRevenueSnapshot> {
  if (pdor !== null) {
    assertConfirmedPdor(pdor, identity.request.obraId);
  }
  assertIdentityCurrent(identity);
  const fetchedAt = new Date(now).toISOString();
  const sessionExpiry = Date.parse(identity.sessionExpiresAt);
  const expiresAt = new Date(
    Math.min(now + PDOR_REVENUE_CACHE_MAX_AGE_MS, sessionExpiry),
  ).toISOString();
  const confirmedProvenance = provenance(identity, pdor);
  const record: FinancePdorRevenueCacheRecord = {
    key: identity.key,
    ownerId: identity.ownerId,
    scopeMaterial: identity.scopeMaterial,
    obraId: identity.request.obraId,
    fetchedAt,
    expiresAt,
    sessionExpiresAt: identity.sessionExpiresAt,
    source: "SERVER_CONFIRMED",
    payloadHash: await payloadHash(pdor),
    provenance: confirmedProvenance,
    response: pdor,
  };
  assertIdentityCurrent(identity);
  await database.put("finance_pdor_revenue_cache", record);
  return {
    pdor,
    mode: "ONLINE",
    source: record.source,
    fetchedAt,
    provenance: confirmedProvenance,
  };
}

async function readConfirmedCache(
  database: IDBPDatabase<CortexDbSchema>,
  identity: PdorRevenueIdentity,
  now: number,
): Promise<CacheReadResult> {
  const cached = await database.get(
    "finance_pdor_revenue_cache",
    identity.key,
  );
  assertIdentityCurrent(identity);
  if (!cached) return { status: "MISS" };
  if (
    cached.ownerId !== identity.ownerId ||
    cached.scopeMaterial !== identity.scopeMaterial ||
    cached.obraId !== identity.request.obraId ||
    cached.source !== "SERVER_CONFIRMED"
  ) {
    return { status: "MISS" };
  }
  const fetchedAtMs = Date.parse(cached.fetchedAt);
  const expiresAtMs = Date.parse(cached.expiresAt);
  const cacheSessionExpiry = Date.parse(cached.sessionExpiresAt);
  if (
    !Number.isFinite(fetchedAtMs) ||
    !Number.isFinite(expiresAtMs) ||
    !Number.isFinite(cacheSessionExpiry) ||
    expiresAtMs <= fetchedAtMs ||
    expiresAtMs > fetchedAtMs + PDOR_REVENUE_CACHE_MAX_AGE_MS ||
    expiresAtMs > cacheSessionExpiry
  ) {
    return { status: "MISS" };
  }
  if (now > expiresAtMs) return { status: "STALE" };
  if (await payloadHash(cached.response) !== cached.payloadHash) {
    return { status: "MISS" };
  }
  let pdor: ObraPdor | null;
  if (cached.response === null) {
    pdor = null;
  } else {
    try {
      assertConfirmedPdor(cached.response, identity.request.obraId);
      pdor = cached.response;
    } catch {
      return { status: "MISS" };
    }
  }
  const confirmedProvenance = provenance(identity, pdor);
  if (!sameProvenance(cached.provenance, confirmedProvenance)) {
    return { status: "MISS" };
  }
  return {
    status: "HIT",
    snapshot: {
      pdor,
      mode: "OFFLINE_CACHE",
      source: cached.source,
      fetchedAt: cached.fetchedAt,
      provenance: cached.provenance,
    },
  };
}

async function confirmedFallback(
  database: IDBPDatabase<CortexDbSchema>,
  identity: PdorRevenueIdentity,
  now: number,
): Promise<PdorRevenueSnapshot> {
  const cached = await readConfirmedCache(database, identity, now);
  if (cached.status === "HIT") return cached.snapshot;
  if (cached.status === "STALE") {
    throw new Error(
      "A última previsão PDOR confirmada expirou. Reconecte para atualizar a receita.",
    );
  }
  throw new Error(
    "Nenhuma previsão PDOR confirmada está disponível offline para este usuário e obra.",
  );
}

export async function loadPdorRevenueSnapshot(
  request: PdorRevenueRequest,
  dependencies: PdorRevenueDependencies = {},
): Promise<PdorRevenueSnapshot> {
  const identity = currentIdentity(request);
  const database = await getCortexDb();
  assertIdentityCurrent(identity);
  const now = dependencies.now ?? Date.now();
  const online = dependencies.online ??
    (typeof navigator === "undefined" || navigator.onLine);
  if (!online) {
    return confirmedFallback(database, identity, now);
  }
  const fetchCurrent = dependencies.fetchCurrent ?? buscarPdorAtual;
  try {
    const pdor = await fetchCurrent(identity.request.obraId);
    assertIdentityCurrent(identity);
    return await cacheConfirmedResponse(database, identity, pdor, now);
  } catch (error: unknown) {
    if (!(error instanceof ApiTransportError)) {
      throw error;
    }
    return confirmedFallback(database, identity, now);
  }
}
