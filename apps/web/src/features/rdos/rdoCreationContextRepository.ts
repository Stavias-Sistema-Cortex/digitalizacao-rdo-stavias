import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  ObraLocalRecord,
  RdoCreationContextCacheRecord,
} from "../../lib/db/db.types";
import {
  AUTH_SESSION_CHANGED_EVENT,
  getSession,
  type AuthProfile,
} from "../auth/authSession";
import { RDO_CONTEXT_OFFLINE_MISSING } from "./rdoCreationContext";
import {
  assertRdoCreationContextLookup,
  buscarContextoDeCriacaoRdo,
  buscarContextoParaRascunhoRdo,
  buscarObrasAutorizadasParaRdo,
  type RdoDraftCreationContextLookup,
  type RdoLocalPendingCreationContextLookup,
  type RdoAuthorizedWorksiteLookup,
  type RdoCreationContextLookup,
} from "./rdoLookupApi";

export interface ResolvedRdoCreationContext {
  source: "SERVER" | "CACHE";
  cachedAt: string;
  context: RdoCreationContextLookup;
}

export type ResolvedRdoDraftCreationContext =
  | {
      kind: "CANONICAL";
      source: "SERVER" | "CACHE";
      cachedAt: string;
      context: RdoCreationContextLookup;
    }
  | {
      kind: "LOCAL_PENDING";
      source: "LEGACY_SERVER";
      cachedAt: string;
      context: RdoLocalPendingCreationContextLookup;
    };

function activeSession() {
  const session = getSession();
  if (!session) {
    throw new Error("Sessão válida obrigatória para acessar o contexto do RDO.");
  }
  return session;
}

export interface RdoContextSessionGuard {
  session: AuthProfile;
  fingerprint: string;
}

function sessionFingerprint(session: AuthProfile): string {
  return JSON.stringify({
    colaboradorId: session.colaboradorId,
    papelAcesso: session.papelAcesso,
    escopoGlobal: session.escopoGlobal,
    obraIds: [...session.obraIds].sort(),
    expiraEm: session.expiraEm,
  });
}

export function captureContextSession(): RdoContextSessionGuard {
  const session = activeSession();
  return { session, fingerprint: sessionFingerprint(session) };
}

export function assertContextSession(guard: RdoContextSessionGuard): void {
  const current = getSession();
  if (
    current === null ||
    current !== guard.session ||
    sessionFingerprint(current) !== guard.fingerprint
  ) {
    throw new Error("A sessão mudou durante a leitura do contexto do RDO.");
  }
}

function guardContextTransaction(
  transaction: { abort(): void; readonly done: Promise<unknown> },
  guard: RdoContextSessionGuard,
) {
  let invalidated = false;
  const abortOnSessionChange = () => {
    try {
      assertContextSession(guard);
    } catch {
      invalidated = true;
      try {
        transaction.abort();
      } catch {
        // A transação pode já ter terminado.
      }
    }
  };
  if (typeof window !== "undefined") {
    window.addEventListener(AUTH_SESSION_CHANGED_EVENT, abortOnSessionChange);
  }
  const dispose = () => {
    if (typeof window !== "undefined") {
      window.removeEventListener(
        AUTH_SESSION_CHANGED_EVENT,
        abortOnSessionChange,
      );
    }
  };
  void transaction.done.then(dispose, dispose);
  return {
    async complete() {
      try {
        abortOnSessionChange();
        if (invalidated) {
          await transaction.done.catch(() => undefined);
          throw new Error(
            "A sessão mudou durante a leitura do contexto do RDO.",
          );
        }
        await transaction.done;
        assertContextSession(guard);
      } catch (error: unknown) {
        if (invalidated) {
          throw new Error(
            "A sessão mudou durante a leitura do contexto do RDO.",
            { cause: error },
          );
        }
        throw error;
      } finally {
        dispose();
      }
    },
  };
}

function assertWorksiteScope(
  obraId: string,
  guard: RdoContextSessionGuard = captureContextSession(),
): AuthProfile {
  assertContextSession(guard);
  const session = guard.session;
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
  const guard = captureContextSession();
  const session = guard.session;
  const database = await getCortexDb();
  assertContextSession(guard);
  const worksites = await database.getAll("obras");
  assertContextSession(guard);
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
  guard: RdoContextSessionGuard = captureContextSession(),
): Promise<ObraLocalRecord[]> {
  assertContextSession(guard);
  const session = guard.session;
  const allowed = values
    .filter(
      (item) =>
        Boolean(item.id.trim()) &&
        (session.escopoGlobal || session.obraIds.includes(item.id)),
    )
    .map((item) => worksiteRecord(item, cachedAt));
  const database = await getCortexDb();
  assertContextSession(guard);
  const transaction = database.transaction("obras", "readwrite");
  const guarded = guardContextTransaction(transaction, guard);
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
  await guarded.complete();
  return allowed;
}

export async function refreshAuthorizedRdoWorksites(): Promise<
  ObraLocalRecord[]
> {
  const guard = captureContextSession();
  const remote = await buscarObrasAutorizadasParaRdo();
  assertContextSession(guard);
  return replaceCachedAuthorizedRdoWorksites(
    remote,
    new Date().toISOString(),
    guard,
  );
}

function contextRecord(
  context: RdoCreationContextLookup,
  cachedAt: string,
  guard: RdoContextSessionGuard,
): RdoCreationContextCacheRecord {
  assertRdoCreationContextLookup(context);
  const session = assertWorksiteScope(context.obra.id, guard);
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
  guard: RdoContextSessionGuard = captureContextSession(),
): Promise<RdoCreationContextCacheRecord> {
  const record = contextRecord(context, cachedAt, guard);
  const database = await getCortexDb();
  assertContextSession(guard);
  const transaction = database.transaction("rdo_creation_contexts", "readwrite");
  const guarded = guardContextTransaction(transaction, guard);
  await transaction.objectStore("rdo_creation_contexts").put(record);
  await guarded.complete();
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
  const guard = captureContextSession();
  const session = assertWorksiteScope(obraId, guard);
  const database = await getCortexDb();
  assertContextSession(guard);
  const record = await database.get("rdo_creation_contexts", [
    session.colaboradorId,
    obraId,
    selectedDate,
  ]);
  assertContextSession(guard);
  if (!record || record.ownerId !== session.colaboradorId) return undefined;
  assertRdoCreationContextLookup(record.context);
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
  const guard = captureContextSession();
  assertWorksiteScope(obraId, guard);
  const cached = await getCachedRdoCreationContext(obraId, selectedDate);
  assertContextSession(guard);
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
    assertContextSession(guard);
    const stored = await putRdoCreationContext(
      context,
      new Date().toISOString(),
      guard,
    );
    return { source: "SERVER", cachedAt: stored.cachedAt, context };
  } catch (error) {
    assertContextSession(guard);
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

export async function requireRdoDraftCreationContext(
  obraId: string,
  selectedDate: string,
  online = typeof navigator !== "undefined" && navigator.onLine,
  fetchRemote: (
    obraId: string,
    selectedDate: string,
  ) => Promise<RdoDraftCreationContextLookup> =
    buscarContextoParaRascunhoRdo,
): Promise<ResolvedRdoDraftCreationContext> {
  const guard = captureContextSession();
  assertWorksiteScope(obraId, guard);
  const cached = await getCachedRdoCreationContext(obraId, selectedDate);
  assertContextSession(guard);
  if (!online) {
    if (!cached) throw new Error(RDO_CONTEXT_OFFLINE_MISSING);
    return {
      kind: "CANONICAL",
      source: "CACHE",
      cachedAt: cached.cachedAt,
      context: cached.context,
    };
  }

  try {
    const resolved = await fetchRemote(obraId, selectedDate);
    assertContextSession(guard);
    if (resolved.kind === "CANONICAL") {
      const stored = await putRdoCreationContext(
        resolved.context,
        new Date().toISOString(),
        guard,
      );
      return {
        kind: "CANONICAL",
        source: "SERVER",
        cachedAt: stored.cachedAt,
        context: resolved.context,
      };
    }
    if (
      resolved.context.obra.id !== obraId ||
      resolved.context.data !== selectedDate
    ) {
      throw new Error(
        "Contexto local pendente não corresponde à seleção.",
      );
    }
    if (cached) {
      return {
        kind: "CANONICAL",
        source: "CACHE",
        cachedAt: cached.cachedAt,
        context: cached.context,
      };
    }
    return {
      kind: "LOCAL_PENDING",
      source: "LEGACY_SERVER",
      cachedAt: new Date().toISOString(),
      context: resolved.context,
    };
  } catch (error) {
    assertContextSession(guard);
    if (cached) {
      return {
        kind: "CANONICAL",
        source: "CACHE",
        cachedAt: cached.cachedAt,
        context: cached.context,
      };
    }
    throw error;
  }
}
