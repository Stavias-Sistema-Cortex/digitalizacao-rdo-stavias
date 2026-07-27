import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  current: "session-a",
  list: vi.fn(),
  history: vi.fn(),
  storeConversations: vi.fn(),
  storeMessages: vi.fn(),
}));

vi.mock("./mensagensApi", () => ({
  listConversationsApi: mocks.list,
  getMessageHistoryApi: mocks.history,
}));
vi.mock("./mensagensRepository", () => ({
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

import { refreshMessagingAfterPull } from "./mensagensHydration";

describe("messaging hydration session boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.current = "session-a";
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

  it("passes the active guard into every authoritative repository write", async () => {
    const guard = {
      fingerprint: "session-a",
      userId: "user",
    };
    const conversations = [{ id: "conversation" }];
    mocks.list.mockResolvedValueOnce(conversations);
    mocks.history.mockResolvedValueOnce([]);

    await refreshMessagingAfterPull(["conversation"], guard);

    expect(mocks.storeConversations).toHaveBeenCalledWith(
      conversations,
      { authoritative: true },
      guard,
    );
    expect(mocks.storeMessages).toHaveBeenCalledWith([], guard);
  });
});
