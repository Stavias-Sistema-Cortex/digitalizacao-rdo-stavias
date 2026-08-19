import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  current: "session-a",
  list: vi.fn(),
  authorizedIds: vi.fn(),
  history: vi.fn(),
  archive: vi.fn(),
  clean: vi.fn(),
  pendingPreferences: vi.fn(),
  resolveConversationId: vi.fn(),
  confirmPreference: vi.fn(),
  storeConversations: vi.fn(),
  storeMessages: vi.fn(),
  reserveOrdinal: vi.fn(),
}));

vi.mock("./mensagensApi", () => ({
  arquivarConversaApi: mocks.archive,
  getConversationAuthorizationSnapshotApi: mocks.authorizedIds,
  limparConversaApi: mocks.clean,
  listConversationsApi: mocks.list,
  getMessageHistoryApi: mocks.history,
}));
vi.mock("./mensagensRepository", () => ({
  confirmarPreferenciaDaConversaSincronizada: mocks.confirmPreference,
  listarPreferenciasPendentesDaConversa: mocks.pendingPreferences,
  reserveMessagingRequestOrdinal: mocks.reserveOrdinal,
  resolveLocalConversationId: mocks.resolveConversationId,
  storeServerConversations: mocks.storeConversations,
  storeServerMessages: mocks.storeMessages,
}));
vi.mock("../../lib/sync/syncSession", () => ({
  assertSyncSession: (guard: { fingerprint: string }) => {
    if (guard.fingerprint !== mocks.current) {
      throw new Error("A sessão mudou durante a sincronização.");
    }
  },
}));

import {
  flushPendingConversationPreferences,
  refreshMessagingAfterPull,
} from "./mensagensHydration";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

describe("messaging hydration session boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.current = "session-a";
    mocks.list.mockResolvedValue([]);
    mocks.authorizedIds.mockResolvedValue({
      authorizedConversationIds: [],
      preferences: [],
    });
    mocks.pendingPreferences.mockResolvedValue([]);
    mocks.resolveConversationId.mockImplementation(async (id: string) => id);
    mocks.confirmPreference.mockResolvedValue(true);
    mocks.reserveOrdinal.mockResolvedValue(1);
  });

  it("does not persist a conversation list fetched by a replaced session", async () => {
    mocks.list.mockImplementationOnce(async () => {
      mocks.current = "session-b";
      return [];
    });

    await expect(
      refreshMessagingAfterPull(["conversation"], {
        fingerprint: "session-a",
        userId: "user",
      }),
    ).rejects.toThrow("A sessão mudou durante a sincronização.");
    expect(mocks.storeConversations).not.toHaveBeenCalled();
    expect(mocks.storeMessages).not.toHaveBeenCalled();
  });

  it("passes the active guard into the complete authorization write", async () => {
    const guard = {
      fingerprint: "session-a",
      userId: "user",
    };
    const conversations = [{ id: "conversation" }];
    mocks.list
      .mockResolvedValueOnce(conversations)
      .mockResolvedValueOnce([]);
    mocks.authorizedIds.mockResolvedValueOnce({
      authorizedConversationIds: ["conversation"],
      preferences: [{ conversationId: "conversation", limpoAte: null }],
    });
    mocks.history.mockResolvedValueOnce([]);

    await refreshMessagingAfterPull(["conversation"], guard);

    expect(mocks.storeConversations).toHaveBeenCalledWith(
      conversations,
      {
        authorizedConversationIds: ["conversation"],
        archivedConversationIds: [],
        historyCutoffs: [{ conversationId: "conversation", limpoAte: null }],
        requestStartedAt: expect.any(String),
        authorizationSnapshotOrdinal: expect.any(Number),
      },
      guard,
    );
    expect(mocks.storeMessages).toHaveBeenCalledWith(
      [],
      guard,
      { requestOrdinal: expect.any(Number) },
    );
  });

  it("uses the complete authorization snapshot while caching archived conversations", async () => {
    const guard = {
      fingerprint: "session-a",
      userId: "user",
    };
    const active = { id: "active-in-first-page" };
    const archived = { id: "archived-in-first-page" };
    mocks.list
      .mockResolvedValueOnce([active])
      .mockResolvedValueOnce([archived]);
    mocks.authorizedIds.mockResolvedValueOnce({
      authorizedConversationIds: [
        active.id,
        archived.id,
        "authorized-outside-first-page",
      ],
      preferences: [active, archived, {
        id: "authorized-outside-first-page",
      }].map(({ id }) => ({ conversationId: id, limpoAte: null })),
    });
    mocks.history.mockResolvedValueOnce([]);

    await refreshMessagingAfterPull(
      ["authorized-outside-first-page"],
      guard,
    );

    expect(mocks.list).toHaveBeenNthCalledWith(1, 100, false);
    expect(mocks.list).toHaveBeenNthCalledWith(2, 100, true);
    expect(mocks.authorizedIds).toHaveBeenCalledOnce();
    expect(mocks.storeConversations).toHaveBeenCalledWith(
      [active, archived],
      {
        authorizedConversationIds: [
          active.id,
          archived.id,
          "authorized-outside-first-page",
        ],
        archivedConversationIds: [archived.id],
        historyCutoffs: [active, archived, {
          id: "authorized-outside-first-page",
        }].map(({ id }) => ({ conversationId: id, limpoAte: null })),
        requestStartedAt: expect.any(String),
        authorizationSnapshotOrdinal: expect.any(Number),
      },
      guard,
    );
    expect(mocks.history).toHaveBeenCalledWith(
      "authorized-outside-first-page",
      100,
    );
  });

  it("reads the complete authorization gate after both list pages", async () => {
    const guard = { fingerprint: "session-a", userId: "user" };
    const active = deferred<Array<{ id: string }>>();
    const archived = deferred<Array<{ id: string }>>();
    mocks.list
      .mockReturnValueOnce(active.promise)
      .mockReturnValueOnce(archived.promise);
    mocks.authorizedIds.mockResolvedValueOnce({
      authorizedConversationIds: [],
      preferences: [],
    });
    const refresh = refreshMessagingAfterPull(["revoked-c"], guard);

    await Promise.resolve();
    const gateWasReadBeforeListsCompleted =
      mocks.authorizedIds.mock.calls.length > 0;
    active.resolve([{ id: "revoked-c" }]);
    archived.resolve([]);
    await refresh;

    expect(gateWasReadBeforeListsCompleted).toBe(false);
    expect(mocks.storeConversations).toHaveBeenCalledWith(
      [{ id: "revoked-c" }],
      expect.objectContaining({ authorizedConversationIds: [] }),
      guard,
    );
    expect(mocks.history).not.toHaveBeenCalled();
  });

  it("orders the authoritative snapshot after its list pages complete", async () => {
    const guard = { fingerprint: "session-a", userId: "user" };
    const firstActive = deferred<Array<{ id: string }>>();
    const firstArchived = deferred<Array<{ id: string }>>();
    mocks.list
      .mockReturnValueOnce(firstActive.promise)
      .mockReturnValueOnce(firstArchived.promise)
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([]);
    mocks.reserveOrdinal
      .mockResolvedValueOnce(1)
      .mockResolvedValueOnce(2);
    mocks.authorizedIds
      .mockResolvedValueOnce({
        authorizedConversationIds: ["conversation"],
        preferences: [{ conversationId: "conversation", limpoAte: null }],
      })
      .mockResolvedValueOnce({
        authorizedConversationIds: [],
        preferences: [],
      });

    const revokingRefresh = refreshMessagingAfterPull([], guard);
    const authorizedRefresh = refreshMessagingAfterPull([], guard);
    await authorizedRefresh;
    firstActive.resolve([]);
    firstArchived.resolve([]);
    await revokingRefresh;

    expect(mocks.storeConversations).toHaveBeenNthCalledWith(
      1,
      [],
      expect.objectContaining({
        authorizedConversationIds: ["conversation"],
        authorizationSnapshotOrdinal: 1,
      }),
      guard,
    );
    expect(mocks.storeConversations).toHaveBeenNthCalledWith(
      2,
      [],
      expect.objectContaining({
        authorizedConversationIds: [],
        authorizationSnapshotOrdinal: 2,
      }),
      guard,
    );
  });

  it("does not prune a conversation newly authorized between list and gate", async () => {
    const guard = { fingerprint: "session-a", userId: "user" };
    const active = deferred<Array<{ id: string }>>();
    const archived = deferred<Array<{ id: string }>>();
    mocks.list
      .mockReturnValueOnce(active.promise)
      .mockReturnValueOnce(archived.promise);
    mocks.authorizedIds.mockResolvedValueOnce({
      authorizedConversationIds: ["new-c"],
      preferences: [{ conversationId: "new-c", limpoAte: null }],
    });
    mocks.history.mockResolvedValueOnce([]);
    const refresh = refreshMessagingAfterPull(["new-c"], guard);

    active.resolve([]);
    archived.resolve([]);
    await refresh;

    expect(mocks.storeConversations).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ authorizedConversationIds: ["new-c"] }),
      guard,
    );
    expect(mocks.history).toHaveBeenCalledWith("new-c", 100);
  });

  it("refreshes the authorization gate even when the pull has no conversation ids", async () => {
    const guard = { fingerprint: "session-a", userId: "user" };

    await refreshMessagingAfterPull([], guard);

    expect(mocks.list).toHaveBeenCalledTimes(2);
    expect(mocks.authorizedIds).toHaveBeenCalledOnce();
    expect(mocks.storeConversations).toHaveBeenCalledWith(
      [],
      expect.objectContaining({ authorizedConversationIds: [] }),
      guard,
    );
    expect(mocks.history).not.toHaveBeenCalled();
  });

  it("flushes an offline preference against the canonical conversation id", async () => {
    const guard = { fingerprint: "session-a", userId: "user" };
    mocks.pendingPreferences.mockResolvedValueOnce([{
      conversaId: "provisional",
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      limpoAte: null,
      arquivadoPendente: true,
      limpoPendente: false,
      pendente: true,
    }]);
    mocks.resolveConversationId.mockResolvedValueOnce("canonical");

    await expect(flushPendingConversationPreferences(guard)).resolves.toEqual({
      inspected: 1,
      applied: 1,
      errors: 0,
    });

    expect(mocks.archive).toHaveBeenCalledWith("canonical", true);
    expect(mocks.clean).not.toHaveBeenCalled();
    expect(mocks.confirmPreference).toHaveBeenCalledWith({
      conversaId: "canonical",
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      limpoAte: null,
      arquivadoPendente: true,
      limpoPendente: false,
      pendente: true,
    }, guard, ["ARQUIVAMENTO"]);
  });

  it("keeps a preference pending when the server still rejects it", async () => {
    const guard = { fingerprint: "session-a", userId: "user" };
    mocks.pendingPreferences.mockResolvedValueOnce([{
      conversaId: "provisional",
      arquivadoEm: null,
      limpoAte: "2026-08-19T16:00:00.000Z",
      arquivadoPendente: false,
      limpoPendente: true,
      pendente: true,
    }]);
    mocks.clean.mockRejectedValueOnce(new Error("404 Not Found"));

    await expect(flushPendingConversationPreferences(guard)).resolves.toEqual({
      inspected: 1,
      applied: 0,
      errors: 1,
    });

    expect(mocks.archive).not.toHaveBeenCalled();
    expect(mocks.clean).toHaveBeenCalledWith(
      "provisional",
      true,
      "2026-08-19T16:00:00.000Z",
    );
    expect(mocks.confirmPreference).not.toHaveBeenCalled();
  });

  it("never reopens a history cleared on another device when only archive is pending", async () => {
    const guard = { fingerprint: "session-a", userId: "user" };
    mocks.pendingPreferences.mockResolvedValueOnce([{
      conversaId: "conversation",
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      limpoAte: null,
      arquivadoPendente: true,
      limpoPendente: false,
      pendente: true,
    }]);

    await expect(flushPendingConversationPreferences(guard)).resolves.toEqual({
      inspected: 1,
      applied: 1,
      errors: 0,
    });

    expect(mocks.archive).toHaveBeenCalledWith("conversation", true);
    expect(mocks.clean).not.toHaveBeenCalled();
  });

  it("keeps an offline reopen explicit and timestamp-free", async () => {
    const guard = { fingerprint: "session-a", userId: "user" };
    mocks.pendingPreferences.mockResolvedValueOnce([{
      conversaId: "conversation",
      arquivadoEm: null,
      limpoAte: null,
      arquivadoPendente: false,
      limpoPendente: true,
      pendente: true,
    }]);

    await flushPendingConversationPreferences(guard);

    expect(mocks.clean).toHaveBeenCalledWith("conversation", false);
  });

  it("replays both fields when a legacy row may mean desarchive", async () => {
    mocks.pendingPreferences.mockResolvedValueOnce([{
      conversaId: "conversation",
      arquivadoEm: null,
      limpoAte: "2026-08-19T16:00:00.000Z",
      pendente: true,
    }]);

    await flushPendingConversationPreferences();

    expect(mocks.archive).toHaveBeenCalledWith("conversation", false);
    expect(mocks.clean).toHaveBeenCalledWith(
      "conversation",
      true,
      "2026-08-19T16:00:00.000Z",
    );
  });

  it("replays both fields when a legacy row may mean reopen", async () => {
    mocks.pendingPreferences.mockResolvedValueOnce([{
      conversaId: "conversation",
      arquivadoEm: "2026-08-19T16:00:00.000Z",
      limpoAte: null,
      pendente: true,
    }]);

    await flushPendingConversationPreferences();

    expect(mocks.archive).toHaveBeenCalledWith("conversation", true);
    expect(mocks.clean).toHaveBeenCalledWith("conversation", false);
  });
});
