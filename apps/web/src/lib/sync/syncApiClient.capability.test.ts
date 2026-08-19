import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
}));

vi.mock("../api/apiClient", async (importOriginal) => {
  const original =
    await importOriginal<typeof import("../api/apiClient")>();
  return {
    ...original,
    apiFetch: mocks.apiFetch,
  };
});

import { pushMutationsApi } from "./syncApiClient";

describe("sync push client capabilities", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.apiFetch.mockResolvedValue(new Response(JSON.stringify({
      resultados: [],
    }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    }));
  });

  it("declara que consegue adotar o alias de conversa na mesma transação", async () => {
    await pushMutationsApi({ dispositivoId: "device", mutacoes: [] });

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/sync/push",
      expect.objectContaining({
        headers: expect.objectContaining({
          "X-Cortex-Sync-Capabilities": "conversation-alias-remap-v1",
        }),
      }),
    );
  });
});
