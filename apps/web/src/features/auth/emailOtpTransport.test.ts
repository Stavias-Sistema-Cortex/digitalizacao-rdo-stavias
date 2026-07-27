import { beforeEach, describe, expect, it, vi } from "vitest";

import { publicAuthFetch } from "./emailOtpTransport";

const fetchMock = vi.fn();

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
    expect(new Headers(options.headers).get("Authorization")).toBeNull();
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
