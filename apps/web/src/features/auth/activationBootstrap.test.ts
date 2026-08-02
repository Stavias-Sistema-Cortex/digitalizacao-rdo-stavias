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

  it("desiste da sondagem e segue para a entrada quando o serviço está frio", async () => {
    // Nada é desenhado enquanto esta sondagem não responde. Sem prazo, um
    // serviço parado deixava a tela em branco pelo tempo que o navegador
    // aguentasse, antes mesmo de aparecer o campo de CPF.
    vi.useFakeTimers();
    try {
      const fetchImplementation = vi.fn(
        (_input: RequestInfo | URL, init?: RequestInit) =>
          new Promise<Response>((_resolve, reject) => {
            init?.signal?.addEventListener("abort", () => {
              reject(new DOMException("Aborted", "AbortError"));
            });
          }),
      );

      const sondagem = probeActivationOnly(fetchImplementation);
      await vi.advanceTimersByTimeAsync(6_000);

      await expect(sondagem).resolves.toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it("passa um sinal de cancelamento junto com a requisição", async () => {
    const fetchImplementation = vi.fn().mockResolvedValue({
      status: 200,
      json: vi.fn().mockResolvedValue({}),
    } as unknown as Response);

    await probeActivationOnly(fetchImplementation);

    const [, options] = fetchImplementation.mock.calls[0] as [
      RequestInfo,
      RequestInit,
    ];
    expect(options.signal).toBeInstanceOf(AbortSignal);
    expect(options.signal?.aborted).toBe(false);
  });
});
