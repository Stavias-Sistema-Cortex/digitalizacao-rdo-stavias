import { describe, expect, it, vi } from "vitest";

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
});
