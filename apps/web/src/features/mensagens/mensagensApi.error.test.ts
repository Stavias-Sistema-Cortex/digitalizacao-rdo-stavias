import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/api/apiClient")>()),
  apiFetch: mocks.apiFetch,
}));

import {
  getConversationAuthorizationSnapshotApi,
  getMessageHistoryApi,
  limparConversaApi,
  listAuthorizedConversationIdsApi,
} from "./mensagensApi";

describe("mensagensApi error boundary", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("preserva o status 404 para a conversa local ainda não confirmada", async () => {
    mocks.apiFetch.mockResolvedValue(
      new Response("Not Found", {
        status: 404,
        headers: { "Content-Type": "text/plain" },
      }),
    );

    await expect(getMessageHistoryApi("conversa-local")).rejects.toMatchObject({
      name: "ApiError",
      status: 404,
    });
  });

  it("reads and normalizes the complete authorized conversation id snapshot", async () => {
    mocks.apiFetch.mockResolvedValue(
      Response.json(["conversation-a", " conversation-b ", "conversation-a"]),
    );

    await expect(listAuthorizedConversationIdsApi()).resolves.toEqual([
      "conversation-a",
      "conversation-b",
    ]);
    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/mensagens/conversas/autorizadas/ids",
    );
  });

  it("rejects a malformed authorization snapshot before it can drive pruning", async () => {
    mocks.apiFetch.mockResolvedValue(
      Response.json(["conversation-a", null]),
    );

    await expect(listAuthorizedConversationIdsApi()).rejects.toThrow(
      /retrato de conversas autorizadas veio incompleto/i,
    );
  });

  it("reads the complete authorization and personal preference envelope", async () => {
    mocks.apiFetch.mockResolvedValue(Response.json({
      authorizedConversationIds: ["conversation-a", "conversation-b"],
      preferences: [
        {
          conversationId: "conversation-a",
          limpoAte: "2026-08-19T10:00:00Z",
        },
        { conversationId: "conversation-b", limpoAte: null },
      ],
    }));

    await expect(getConversationAuthorizationSnapshotApi()).resolves.toEqual({
      authorizedConversationIds: ["conversation-a", "conversation-b"],
      preferences: [
        {
          conversationId: "conversation-a",
          limpoAte: "2026-08-19T10:00:00Z",
        },
        { conversationId: "conversation-b", limpoAte: null },
      ],
    });
    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/mensagens/conversas/autorizadas/snapshot",
    );
  });

  it("rejects an incomplete preference envelope before pruning", async () => {
    mocks.apiFetch.mockResolvedValue(Response.json({
      authorizedConversationIds: ["conversation-a", "conversation-b"],
      preferences: [
        { conversationId: "conversation-a", limpoAte: null },
      ],
    }));

    await expect(getConversationAuthorizationSnapshotApi()).rejects.toThrow(
      /preferencias pessoais veio incompleto/i,
    );
  });

  it("sends the original offline cutoff only when clearing history", async () => {
    mocks.apiFetch.mockResolvedValue(new Response(null, { status: 204 }));

    await limparConversaApi(
      "conversation-a",
      true,
      "2026-08-19T10:00:00.000Z",
    );

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/mensagens/conversas/conversation-a/limpar",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ limpoAte: "2026-08-19T10:00:00.000Z" }),
      },
    );
  });

  it("keeps reopening explicit and does not send a cutoff", async () => {
    mocks.apiFetch.mockResolvedValue(new Response(null, { status: 204 }));

    await limparConversaApi(
      "conversation-a",
      false,
      "2026-08-19T10:00:00.000Z",
    );

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/mensagens/conversas/conversation-a/reabrir-historico",
      { method: "POST" },
    );
  });
});
