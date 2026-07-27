import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  getOrCreateClientInstance: vi.fn(),
  markClientInstanceAuthenticated: vi.fn(),
}));

vi.mock("../../lib/api/clientInstance", () => ({
  CLIENT_INSTANCE_HEADER: "X-Cortex-Client-Instance",
  getOrCreateClientInstance: mocks.getOrCreateClientInstance,
  markClientInstanceAuthenticated: mocks.markClientInstanceAuthenticated,
}));

import { publicAuthFetch } from "./emailOtpTransport";

const fetchMock = vi.fn();
const clientInstance = "B".repeat(43);

vi.stubGlobal("fetch", fetchMock);
vi.stubGlobal("window", {
  clearTimeout,
  location: {
    hostname: "cortex.example.invalid",
    origin: "https://cortex.example.invalid",
    protocol: "https:",
  },
  setTimeout,
});

describe("publicAuthFetch", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mocks.getOrCreateClientInstance.mockResolvedValue({
      value: clientInstance,
      requiresFreshAuthentication: false,
    });
  });

  it("usa somente cookie, sem acionar o cliente de sessão operacional", async () => {
    fetchMock.mockResolvedValue({ status: 202 } as Response);

    await publicAuthFetch("/auth/email/challenges", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ identifier: "alfa@stavias.example" }),
    });

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(options.credentials).toBe("include");
    const headers = new Headers(options.headers);
    expect(headers.get("Authorization")).toBeNull();
    expect(headers.get("X-Cortex-Client-Instance")).toBe(clientInstance);
  });

  it("substitui uma prova de aba enviada pelo chamador", async () => {
    fetchMock.mockResolvedValue({ status: 202 } as Response);

    await publicAuthFetch("/auth/email/challenges", {
      method: "POST",
      headers: {
        "X-Cortex-Client-Instance": "forged-by-caller",
      },
    });

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(
      new Headers(options.headers).get("X-Cortex-Client-Instance"),
    ).toBe(clientInstance);
  });

  it("vincula a mesma prova à verificação pública de OTP", async () => {
    fetchMock.mockResolvedValue({ status: 200, ok: true } as Response);

    await publicAuthFetch(
      "/auth/email/challenges/11111111-1111-4111-8111-111111111111/verify",
      { method: "POST", body: JSON.stringify({ code: "123456" }) },
    );

    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(
      new Headers(options.headers).get("X-Cortex-Client-Instance"),
    ).toBe(clientInstance);
    expect(mocks.markClientInstanceAuthenticated).toHaveBeenCalledWith(
      clientInstance,
    );
  });

  it("permite somente os dois POSTs públicos exatos de OTP", async () => {
    await expect(
      publicAuthFetch("/obras", { method: "POST" }),
    ).rejects.toThrow("Transição pública de autenticação inválida");
    await expect(
      publicAuthFetch("/auth/email/challenges", { method: "GET" }),
    ).rejects.toThrow("Transição pública de autenticação inválida");

    expect(fetchMock).not.toHaveBeenCalled();
  });
});
