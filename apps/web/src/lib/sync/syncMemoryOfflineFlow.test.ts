import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const syncMocks = vi.hoisted(() => ({
  ensureDevice: vi.fn(async () => "device-memory-flow"),
  uploads: vi.fn(async () => ({ pushed: 0, applied: 0, errors: 0 })),
  push: vi.fn(async () => ({
    pushed: 1,
    applied: 1,
    errors: 0,
    retryableErrors: 0,
    conflicts: 0,
  })),
  pull: vi.fn(async () => ({ pulled: 1, messagingConversationIds: [] })),
  ack: vi.fn(async () => 121),
}));

vi.mock("../../features/mensagens/objectUploadSync", () => ({
  processObjectUploads: syncMocks.uploads,
}));
vi.mock("../../features/mensagens/mensagensHydration", () => ({
  refreshMessagingAfterPull: vi.fn(async () => undefined),
}));
vi.mock("../db/localRdoService", () => ({
  hydrateBlockedRdoCreationContextsForSync: vi.fn(async () => 0),
  repairRdoCreateMutationsForSync: vi.fn(async () => 0),
  recoverRejectedRdoMutationsForSync: vi.fn(async () => 0),
}));
vi.mock("./ackCursor", () => ({ acknowledgeCurrentCursor: syncMocks.ack }));
vi.mock("./pullEvents", () => ({ pullEvents: syncMocks.pull }));
vi.mock("./pushOutbox", () => ({ pushOutbox: syncMocks.push }));
vi.mock("./registerDevice", () => ({
  ensureRegisteredDevice: syncMocks.ensureDevice,
}));
vi.mock("./syncStorage", () => ({
  recoverInterruptedMutations: vi.fn(async () => undefined),
  recoverCanonicalConflictReconciliations: vi.fn(async () => 0),
  repairMissingMaoObraReferencesForSync: vi.fn(async () => 0),
  repairMissingObraReferencesForSync: vi.fn(async () => 0),
  resolveCanonicalUploadReplacements: vi.fn(async () => 0),
}));

import {
  clearSession,
  getSession,
  setOfflineSession,
  setSession,
  type AuthProfile,
} from "../../features/auth/authSession";
import {
  closeCortexDb,
  getCortexDb,
} from "../db/cortexDb";
import { databaseNameForScope } from "../db/localDataNamespace";
import type {
  MemoryFilters,
  MemoryPage,
  MemoryServerEvent,
} from "../../features/home/memory/memoryApi";
import {
  bindMemoryReconnectRefresh,
  createMemoryRefreshState,
  runCoalescedMemoryRefresh,
} from "../../features/home/memory/memoryReconnect";
import { createMemoryRepository } from "../../features/home/memory/memoryRepository";
import {
  assertMemorySessionGuard,
  captureMemorySessionGuard,
} from "../../features/home/memory/memorySessionGuard";
import { hydrateAuthorizedMemoryCache } from "../../features/home/memory/useMemoryLedger";
import { syncNow } from "./syncEngine";

const testWindow = new EventTarget();
vi.stubGlobal("window", testWindow);
vi.stubGlobal("navigator", { onLine: true });

const USER_ID = "00000000-0000-4000-8000-000000000701";
const WORKSITE_ID = "00000000-0000-4000-8000-000000000702";

let profile: AuthProfile;
let databaseName = "";

beforeEach(async () => {
  vi.clearAllMocks();
  profile = {
    colaboradorId: USER_ID,
    nome: "Encarregado",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
  setSession(profile);
  databaseName = await databaseNameForScope(USER_ID, `BETA:${WORKSITE_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

describe("canonical sync to offline Memory flow", () => {
  it("hydrates before push, refreshes after guarded sync, reopens and searches full history offline", async () => {
    const beforePush = pagedServer([serverEvent(1)]);
    const afterSyncEvents = Array.from({ length: 121 }, (_, index) =>
      serverEvent(121 - index, 121 - index === 7 ? "Pesquisa offline profunda" : undefined),
    );
    const afterSync = pagedServer(afterSyncEvents);
    let activeServer = beforePush;
    const harness = reconnectHarness(() => activeServer);

    await harness.refresh();
    expect(beforePush).toHaveBeenCalledTimes(1);

    activeServer = afterSync;
    await syncNow();
    await harness.waitForRefresh();

    expect(afterSync).toHaveBeenCalledTimes(2);
    expect(harness.lastError()).toBeNull();
    harness.dispose();
    await closeCortexDb();
    setOfflineSession(profile);

    const reopened = createMemoryRepository(await getCortexDb());
    const metadata = await reopened.latestMetadata(USER_ID);
    const result = await reopened.search({
      userId: USER_ID,
      scopeHash: metadata?.scopeHash ?? "missing",
      filters: { q: "pesquisa offline profunda" },
      allowedWorksiteIds: [WORKSITE_ID],
      limit: 20,
    });

    expect(getSession()).not.toBeNull();
    expect(metadata).toMatchObject({
      highWaterMark: 121,
      authorizedEventCount: 121,
      cachedEventCount: 121,
      complete: true,
      graph: { fresh: true, lagEventCount: 0 },
    });
    expect(result.items.map((item) => item.eventId)).toEqual(["event-7"]);
  });

  it("keeps the pre-push cache when post-sync hydration fails", async () => {
    const beforePush = pagedServer([serverEvent(1)]);
    const failedServer = vi.fn(async () => {
      throw new Error("Falha de rede controlada.");
    });
    let activeServer = beforePush;
    const harness = reconnectHarness(() => activeServer);
    await harness.refresh();

    activeServer = failedServer;
    await syncNow();
    await harness.waitForRefresh();

    const repository = createMemoryRepository(await getCortexDb());
    expect(harness.lastError()).toMatchObject({
      message: "Falha de rede controlada.",
    });
    expect(await repository.latestMetadata(USER_ID)).toMatchObject({
      highWaterMark: 1,
      cachedEventCount: 1,
      complete: true,
    });
    harness.dispose();
  });

  it("rejects post-sync hydration from a rotated identical session without clearing old UI data", async () => {
    const beforePush = pagedServer([serverEvent(1)]);
    const advancedPage = page(
      [serverEvent(2), serverEvent(1)],
      2,
      0,
      false,
    );
    const rotatedServer = vi.fn(async () => {
      setSession({ ...profile, obraIds: [...profile.obraIds] });
      return advancedPage;
    });
    let activeServer = beforePush;
    const cacheReset = vi.fn();
    const harness = reconnectHarness(() => activeServer, cacheReset);
    await harness.refresh();

    activeServer = rotatedServer;
    await syncNow();
    await harness.waitForRefresh();

    await closeCortexDb();
    const repository = createMemoryRepository(await getCortexDb());
    expect(harness.lastError()).toMatchObject({
      message: "A autorização mudou durante a leitura da Memória.",
    });
    expect(cacheReset).toHaveBeenCalledTimes(1);
    expect(await repository.latestMetadata(USER_ID)).toMatchObject({
      highWaterMark: 1,
      cachedEventCount: 1,
      complete: true,
    });
    harness.dispose();
  });
});

function reconnectHarness(
  server: () => MemoryPageLoader,
  onCacheReset = vi.fn(),
) {
  const state = createMemoryRefreshState();
  let latestRefresh: Promise<void> = Promise.resolve();
  let error: Error | null = null;
  const hydrate = async () => {
    try {
      const guard = captureMemorySessionGuard();
      const database = await getCortexDb();
      assertMemorySessionGuard(guard);
      const repository = createMemoryRepository(database);
      const previous = await repository.latestMetadata(USER_ID);
      assertMemorySessionGuard(guard);
      await hydrateAuthorizedMemoryCache({
        guard,
        previousMetadata: previous,
        fetchPage: server(),
        putPage: repository.putPage,
        markComplete: repository.markComplete,
        resetAuthorizedCache: repository.resetAuthorizedCache,
        onCacheReset,
        assertSession: () => assertMemorySessionGuard(guard),
      });
      error = null;
    } catch (cause: unknown) {
      error = cause instanceof Error ? cause : new Error("Falha desconhecida.");
    }
  };
  const refresh = () => {
    latestRefresh = runCoalescedMemoryRefresh(state, hydrate);
    return latestRefresh;
  };
  const dispose = bindMemoryReconnectRefresh(testWindow, () => {
    void refresh();
  });
  return {
    refresh,
    waitForRefresh: () => latestRefresh,
    lastError: () => error,
    dispose,
  };
}

function pagedServer(events: MemoryServerEvent[]) {
  return vi.fn(async (
    _filters: MemoryFilters,
    cursor: MemoryPage["nextCursor"] = null,
  ): Promise<MemoryPage> => {
    const offset = cursor ? Number(cursor.token) : 0;
    const items = events.slice(offset, offset + 100);
    const nextOffset = offset + items.length;
    return page(items, events[0]?.commitSequence ?? 0, nextOffset, nextOffset < events.length);
  });
}

type MemoryPageLoader = (
  filters: MemoryFilters,
  cursor?: MemoryPage["nextCursor"],
) => Promise<MemoryPage>;

function page(
  items: MemoryServerEvent[],
  highWaterMark: number,
  nextOffset: number,
  hasMore: boolean,
): MemoryPage {
  return {
    items,
    nextCursor: hasMore ? { token: String(nextOffset) } : null,
    hasMore,
    highWaterMark,
    scopeHash: "scope-offline-flow",
    coverage: {
      mode: "FULL_HISTORY",
      complete: true,
      authorizedEventCount: highWaterMark,
      oldestCommitSequence: highWaterMark === 0 ? null : 1,
      newestCommitSequence: highWaterMark,
      graph: {
        checkpointCommitSequence: highWaterMark,
        targetCommitSequence: highWaterMark,
        lagEventCount: 0,
        fresh: true,
        lastSafeError: null,
      },
    },
    serverTime: `2026-07-22T10:00:${String(Math.min(59, highWaterMark)).padStart(2, "0")}Z`,
  };
}

function serverEvent(index: number, principalName = `Evento ${index}`): MemoryServerEvent {
  return {
    eventId: `event-${index}`,
    commitSequence: index,
    eventType: "RDO_EDITADO",
    source: "SYNC_PUSH",
    principalEntityType: "RDO",
    principalEntityId: `rdo-${index}`,
    principalName,
    worksiteId: WORKSITE_ID,
    worksiteName: "Obra offline",
    rdoId: `rdo-${index}`,
    rdoNumber: String(index),
    serviceName: null,
    occurredAt: `2026-07-22T09:00:${String(index % 60).padStart(2, "0")}Z`,
    synchronizedAt: "2026-07-22T10:00:00Z",
    origin: "SYNC",
    syncStatus: "SYNCED",
    schemaVersion: 13,
    result: "SYNCED",
    errorCategory: null,
    relevance: 0,
  };
}
