import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getOrCreateClientInstance: vi.fn(),
}));

vi.mock("../../lib/api/clientInstance", () => ({
  CLIENT_INSTANCE_HEADER: "X-Cortex-Client-Instance",
  getOrCreateClientInstance: mocks.getOrCreateClientInstance,
}));

import { probeActivationOnly } from "./activationBootstrap";

const localValues = new Map<string, string>();
const clientInstance = "C".repeat(43);

vi.stubGlobal("localStorage", {
  getItem: (key: string) => localValues.get(key) ?? null,
  removeItem: (key: string) => localValues.delete(key),
  setItem: (key: string, value: string) => localValues.set(key, value),
});

describe("activation bootstrap", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localValues.clear();
    mocks.getOrCreateClientInstance.mockResolvedValue({
      value: clientInstance,
      requiresFreshAuthentication: false,
    });
  });

  it("does not probe the cookie-backed session while remote isolation is persisted", async () => {
    const fetchImplementation = vi.fn();
    localStorage.setItem("cortex.auth.remote-session-isolation", "1");

    await expect(probeActivationOnly(fetchImplementation)).resolves.toBe(false);

    expect(fetchImplementation).not.toHaveBeenCalled();
  });

  it("envia a prova desta aba ao consultar a sessão de ativação", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue({
      status: 503,
      json: vi.fn().mockResolvedValue({ code: "CORTEX_ACTIVATION_ONLY" }),
    } as Response);

    await expect(probeActivationOnly(fetchImplementation)).resolves.toBe(true);

    expect(fetchImplementation).toHaveBeenCalledTimes(1);
    const [, options] = fetchImplementation.mock.calls[0] as [
      string,
      RequestInit,
    ];
    expect(
      new Headers(options.headers).get("X-Cortex-Client-Instance"),
    ).toBe(clientInstance);
  });

  it("probes activation publicly without a shared cookie from a document that needs fresh authentication", async () => {
    mocks.getOrCreateClientInstance.mockResolvedValueOnce({
      value: clientInstance,
      requiresFreshAuthentication: true,
    });
    const fetchImplementation = vi.fn().mockResolvedValue({
      status: 503,
      json: vi.fn().mockResolvedValue({ code: "CORTEX_ACTIVATION_ONLY" }),
    } as Response);

    await expect(probeActivationOnly(fetchImplementation)).resolves.toBe(true);

    expect(fetchImplementation).toHaveBeenCalledWith(
      expect.any(String),
      expect.objectContaining({ credentials: "omit" }),
    );
  });
});
