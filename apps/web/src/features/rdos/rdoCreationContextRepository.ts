import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  ObraLocalRecord,
  RdoCreationContextCacheRecord,
} from "../../lib/db/db.types";
import { getSession } from "../auth/authSession";
import { RDO_CONTEXT_OFFLINE_MISSING } from "./rdoCreationContext";
import {
  buscarContextoDeCriacaoRdo,
  buscarObrasAutorizadasParaRdo,
  type RdoAuthorizedWorksiteLookup,
  type RdoCreationContextLookup,
} from "./rdoLookupApi";

export interface ResolvedRdoCreationContext {
  source: "SERVER" | "CACHE";
  cachedAt: string;
  context: RdoCreationContextLookup;
}

function activeSession() {
  const session = getSession();
  if (!session) {
    throw new Error("Sessão válida obrigatória para acessar o contexto do RDO.");
  }
  return session;
}

function assertWorksiteScope(obraId: string): ReturnType<typeof activeSession> {
  const session = activeSession();
  if (!session.escopoGlobal && !session.obraIds.includes(obraId)) {
    throw new Error("Obra fora do escopo da sessão.");
  }
  return session;
}

function text(value: string | null): string {
  return value?.trim() ?? "";
}

function numberOrNull(value: number | string | null): number | null {
  if (typeof value === "number") return Number.isFinite(value) ? value : null;
  if (typeof value !== "string" || !value.trim()) return null;
  const parsed = Number(value.replace(",", "."));
  return Number.isFinite(parsed) ? parsed : null;
}

function worksiteRecord(
  value: RdoAuthorizedWorksiteLookup,
  cachedAt: string,
): ObraLocalRecord {
  return {
    id: value.id,
    codigoContrato: text(value.codigoContrato),
    nome: text(value.nome),
    cliente: text(value.cliente) || null,
    cidade: text(value.cidade) || null,
    uf: text(value.uf) || null,
    rodovia: text(value.rodovia) || null,
    status: text(value.status),
    observacoes: text(value.observacoes) || null,
    latitude: numberOrNull(value.latitude),
    longitude: numberOrNull(value.longitude),
    valorContratual: numberOrNull(value.valorContratual),
    updatedAt: text(value.atualizadoEm) || cachedAt,
  };
}

export async function listCachedAuthorizedRdoWorksites(): Promise<
  ObraLocalRecord[]
> {
  const session = activeSession();
  const database = await getCortexDb();
  const worksites = await database.getAll("obras");
  return worksites
    .filter(
      (item) => session.escopoGlobal || session.obraIds.includes(item.id),
    )
    .sort((left, right) =>
      (left.nome || left.codigoContrato).localeCompare(
        right.nome || right.codigoContrato,
        "pt-BR",
      ),
    );
}

export async function replaceCachedAuthorizedRdoWorksites(
  values: readonly RdoAuthorizedWorksiteLookup[],
  cachedAt = new Date().toISOString(),
): Promise<ObraLocalRecord[]> {
  const session = activeSession();
  const allowed = values
    .filter(
      (item) =>
        Boolean(item.id.trim()) &&
        (session.escopoGlobal || session.obraIds.includes(item.id)),
    )
    .map((item) => worksiteRecord(item, cachedAt));
  const database = await getCortexDb();
  const transaction = database.transaction("obras", "readwrite");
  const store = transaction.objectStore("obras");
  const incoming = new Set(allowed.map((item) => item.id));
  const existing = await store.getAll();
  for (const item of existing) {
    if (
      (session.escopoGlobal || session.obraIds.includes(item.id)) &&
      !incoming.has(item.id)
    ) {
      await store.delete(item.id);
    }
  }
  for (const item of allowed) await store.put(item);
  await transaction.done;
  return allowed;
}

export async function refreshAuthorizedRdoWorksites(): Promise<
  ObraLocalRecord[]
> {
  const remote = await buscarObrasAutorizadasParaRdo();
  return replaceCachedAuthorizedRdoWorksites(remote);
}

function contextRecord(
  context: RdoCreationContextLookup,
  cachedAt: string,
): RdoCreationContextCacheRecord {
  const session = assertWorksiteScope(context.obra.id);
  if (
    context.provenance.worksiteId !== context.obra.id ||
    context.provenance.selectedDate !== context.data ||
    context.provenance.sourceVersion !== context.freshness.sourceVersion
  ) {
    throw new Error("Proveniência do contexto de criação inválida.");
  }
  return {
    ownerId: session.colaboradorId,
    obraId: context.obra.id,
    selectedDate: context.data,
    sourceVersion: context.provenance.sourceVersion,
    receiptVersion: context.provenance.receiptVersion,
    cachedAt,
    coverage: structuredClone(context.coverage) as unknown as Record<
      string,
      unknown
    >,
    context: structuredClone(context) as unknown as Record<string, unknown>,
  };
}

export async function putRdoCreationContext(
  context: RdoCreationContextLookup,
  cachedAt = new Date().toISOString(),
): Promise<RdoCreationContextCacheRecord> {
  const record = contextRecord(context, cachedAt);
  const database = await getCortexDb();
  await database.put("rdo_creation_contexts", record);
  return record;
}

export async function getCachedRdoCreationContext(
  obraId: string,
  selectedDate: string,
): Promise<
  | (Omit<RdoCreationContextCacheRecord, "context"> & {
      context: RdoCreationContextLookup;
    })
  | undefined
> {
  const session = assertWorksiteScope(obraId);
  const database = await getCortexDb();
  const record = await database.get("rdo_creation_contexts", [
    session.colaboradorId,
    obraId,
    selectedDate,
  ]);
  if (!record || record.ownerId !== session.colaboradorId) return undefined;
  return {
    ...record,
    context: record.context as unknown as RdoCreationContextLookup,
  };
}

export async function requireRdoCreationContext(
  obraId: string,
  selectedDate: string,
  online = typeof navigator !== "undefined" && navigator.onLine,
  fetchRemote: typeof buscarContextoDeCriacaoRdo =
    buscarContextoDeCriacaoRdo,
): Promise<ResolvedRdoCreationContext> {
  const cached = await getCachedRdoCreationContext(obraId, selectedDate);
  if (!online) {
    if (!cached) throw new Error(RDO_CONTEXT_OFFLINE_MISSING);
    return {
      source: "CACHE",
      cachedAt: cached.cachedAt,
      context: cached.context,
    };
  }

  try {
    const context = await fetchRemote(obraId, selectedDate);
    const stored = await putRdoCreationContext(context);
    return { source: "SERVER", cachedAt: stored.cachedAt, context };
  } catch (error) {
    if (cached) {
      return {
        source: "CACHE",
        cachedAt: cached.cachedAt,
        context: cached.context,
      };
    }
    throw error;
  }
}
