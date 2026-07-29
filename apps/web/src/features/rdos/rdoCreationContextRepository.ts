import { getCortexDb } from "../../lib/db/cortexDb";
import { mergeObraRecords } from "../../lib/db/homeRecordMappers";
import type {
  LocalRdoRecord,
  ObraLocalRecord,
  RdoCreationContextCacheRecord,
} from "../../lib/db/db.types";
import {
  AUTH_SESSION_CHANGED_EVENT,
  getSession,
  type AuthProfile,
} from "../auth/authSession";
import { RDO_CONTEXT_OFFLINE_MISSING } from "./rdoCreationContext";
import { localRecordToDraft } from "./localRecordToDraft";
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
      source: "LEGACY_SERVER" | "LOCAL_CHAIN";
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

const LOCAL_RDO_CHAIN_STATUSES = new Set<LocalRdoRecord["syncStatus"]>([
  "LOCAL_ONLY",
  "LOCAL_PENDING",
  "PENDING_SYNC",
  "SYNCING",
  "SYNCED",
]);

function previousRdoId(record: LocalRdoRecord): string | null {
  const value = record.payload.previousRdoId;
  return typeof value === "string" && value.trim() ? value.trim() : null;
}

function follows(
  candidate: LocalRdoRecord,
  ancestor: LocalRdoRecord,
  recordsById: ReadonlyMap<string, LocalRdoRecord>,
): boolean {
  const visited = new Set<string>();
  let cursor = previousRdoId(candidate);
  while (cursor && !visited.has(cursor)) {
    if (cursor === ancestor.id) return true;
    visited.add(cursor);
    const previous = recordsById.get(cursor);
    cursor = previous ? previousRdoId(previous) : null;
  }
  return false;
}

async function localPendingChainContext(
  context: RdoCreationContextLookup,
  guard: RdoContextSessionGuard,
): Promise<RdoLocalPendingCreationContextLookup | null> {
  const database = await getCortexDb();
  assertContextSession(guard);
  const localRdos = await database.getAllFromIndex(
    "rdos",
    "by-obra-id",
    context.obra.id,
  );
  assertContextSession(guard);
  const processedEvents = await database.getAll("processed_events");
  assertContextSession(guard);
  const creationCommitByRdoId = new Map<string, number>();
  for (const event of processedEvents) {
    if (
      event.entidadeTipo !== "RDO" ||
      event.tipoEvento !== "RDO_CRIADO"
    ) continue;
    const current = creationCommitByRdoId.get(event.entidadeId);
    creationCommitByRdoId.set(
      event.entidadeId,
      current === undefined
        ? event.commitSeq
        : Math.min(current, event.commitSeq),
    );
  }
  const recordsById = new Map(localRdos.map((record) => [record.id, record]));
  const localPrevious = localRdos
    .filter(
      (rdo) =>
        rdo.dataRdo <= context.data &&
        LOCAL_RDO_CHAIN_STATUSES.has(rdo.syncStatus),
    )
    .sort((left, right) => {
      const dateOrder = right.dataRdo.localeCompare(left.dataRdo);
      if (dateOrder !== 0) return dateOrder;
      const leftFollowsRight = follows(left, right, recordsById);
      const rightFollowsLeft = follows(right, left, recordsById);
      if (leftFollowsRight !== rightFollowsLeft) {
        return leftFollowsRight ? -1 : 1;
      }
      const createdOrder = right.createdAt.localeCompare(left.createdAt);
      if (createdOrder !== 0) return createdOrder;
      const leftCommit = creationCommitByRdoId.get(left.id) ?? -1;
      const rightCommit = creationCommitByRdoId.get(right.id) ?? -1;
      if (leftCommit !== rightCommit) return rightCommit - leftCommit;
      const versionOrder =
        (right.versaoEntidade ?? -1) - (left.versaoEntidade ?? -1);
      return versionOrder || right.id.localeCompare(left.id);
    })[0];
  if (!localPrevious) return null;

  const canonicalPrevious = context.previousRdo;
  if (
    canonicalPrevious &&
    (
      localPrevious.id === canonicalPrevious.id ||
      localPrevious.dataRdo < canonicalPrevious.dataRdo ||
      (
        localPrevious.dataRdo === canonicalPrevious.dataRdo &&
        localPrevious.syncStatus === "SYNCED" &&
        previousRdoId(localPrevious) !== canonicalPrevious.id
      )
    )
  ) {
    return null;
  }

  const authorizedIds = new Set(
    context.colaboradores
      .map((collaborator) => collaborator.id.trim())
      .filter(Boolean),
  );
  const previousDraft = localRecordToDraft(localPrevious);
  return {
    obra: {
      id: context.obra.id,
      codigoContrato: context.obra.codigoContrato,
      codigoCw: context.obra.codigoCw,
      nome: context.obra.nome,
      cliente: context.obra.cliente,
      cidade: context.obra.cidade,
      uf: context.obra.uf,
      rodovia: context.obra.rodovia,
      status: context.obra.status,
    },
    data: context.data,
    previousRdo: {
      id: localPrevious.id,
      numeroRdo: localPrevious.numeroRdo,
      dataRdo: localPrevious.dataRdo,
      status: localPrevious.statusRdo,
    },
    previousWorkforce: previousDraft.maoObra
      .filter(
        (item) =>
          item.selected &&
          Boolean(
            item.colaboradorId.trim() ||
            item.nomeColaborador.trim(),
          ),
      )
      .map((item) => {
        const collaboratorId = item.colaboradorId.trim() || null;
        return {
          sourceItemId: item.localId,
          sourceRdoId: localPrevious.id,
          collaboratorId,
          nameSnapshot: item.nomeColaborador.trim() || null,
          roleSnapshot: item.cargo.trim() || null,
          linkType: item.tipoVinculo.trim() || null,
          quantity:
            typeof item.quantidade === "number"
              ? item.quantidade
              : null,
          startTime: item.horaInicio.trim() || null,
          endTime: item.horaFim.trim() || null,
          observations: item.observacoes.trim() || null,
          availability: collaboratorId === null ||
              authorizedIds.has(collaboratorId)
            ? "AVAILABLE"
            : "UNAVAILABLE",
        };
      }),
    programacoes: context.programacoes,
    colaboradores: context.colaboradores,
    equipamentos: context.equipamentos,
  };
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
    versaoEntidade: value.versaoLinha,
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
  return operationalAuthorizedWorksites(worksites, session);
}

function operationalAuthorizedWorksites(
  worksites: readonly ObraLocalRecord[],
  session: AuthProfile,
): ObraLocalRecord[] {
  return worksites
    .filter(
      (item) =>
        item.arquivadoEm == null &&
        (session.escopoGlobal || session.obraIds.includes(item.id)),
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
  const existingById = new Map(existing.map((item) => [item.id, item]));
  for (const item of existing) {
    if (
      (session.escopoGlobal || session.obraIds.includes(item.id)) &&
      !incoming.has(item.id) &&
      item.arquivadoEm == null &&
      (item.syncStatus ?? "SYNCED") === "SYNCED"
    ) {
      await store.delete(item.id);
    }
  }
  const mergedAllowed = allowed.map((item) =>
    mergeObraRecords(existingById.get(item.id), item)
  );
  for (const item of mergedAllowed) await store.put(item);
  await guarded.complete();
  assertContextSession(guard);
  const retained = await database.getAll("obras");
  assertContextSession(guard);
  return operationalAuthorizedWorksites(retained, session);
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
    const localChain = await localPendingChainContext(
      cached.context,
      guard,
    );
    if (localChain) {
      return {
        kind: "LOCAL_PENDING",
        source: "LOCAL_CHAIN",
        cachedAt: cached.cachedAt,
        context: localChain,
      };
    }
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
      const localChain = await localPendingChainContext(
        resolved.context,
        guard,
      );
      if (localChain) {
        return {
          kind: "LOCAL_PENDING",
          source: "LOCAL_CHAIN",
          cachedAt: stored.cachedAt,
          context: localChain,
        };
      }
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
      const localChain = await localPendingChainContext(
        cached.context,
        guard,
      );
      if (localChain) {
        return {
          kind: "LOCAL_PENDING",
          source: "LOCAL_CHAIN",
          cachedAt: cached.cachedAt,
          context: localChain,
        };
      }
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
      const localChain = await localPendingChainContext(
        cached.context,
        guard,
      );
      if (localChain) {
        return {
          kind: "LOCAL_PENDING",
          source: "LOCAL_CHAIN",
          cachedAt: cached.cachedAt,
          context: localChain,
        };
      }
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
