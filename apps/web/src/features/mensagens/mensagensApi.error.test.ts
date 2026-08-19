import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/api/apiClient")>()),
  apiFetch: mocks.apiFetch,
}));

import { getMessageHistoryApi } from "./mensagensApi";

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
});
