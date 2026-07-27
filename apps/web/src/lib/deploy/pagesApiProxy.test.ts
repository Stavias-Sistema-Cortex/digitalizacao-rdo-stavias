import { describe, expect, it, vi } from "vitest";
import { proxyApiRequest } from "./pagesApiProxy";

describe("proxyApiRequest", () => {
  it("preserves the api path, query, method, body, origin, and cookies", async () => {
    const upstreamFetch = vi.fn(async (request: Request) => {
      expect(request.url).toBe(
        "https://cortex-api.onrender.com/api/rdos?limit=20",
      );
      expect(request.method).toBe("POST");
      expect(request.headers.get("origin")).toBe(
        "https://cortex-stavias.pages.dev",
      );
      expect(request.headers.get("cookie")).toBe("CORTEX_SESSION=opaque");
      expect(await request.text()).toBe('{"numero":"RDO-0002"}');
      return new Response('{"ok":true}', {
        status: 201,
        headers: {
          "content-type": "application/json",
          "set-cookie": "CORTEX_SESSION=next; Secure; HttpOnly; SameSite=Lax",
        },
      });
    });

    const response = await proxyApiRequest(
      new Request(
        "https://cortex-stavias.pages.dev/api/rdos?limit=20",
        {
          method: "POST",
          headers: {
            origin: "https://cortex-stavias.pages.dev",
            cookie: "CORTEX_SESSION=opaque",
            "content-type": "application/json",
          },
          body: '{"numero":"RDO-0002"}',
        },
      ),
      { CORTEX_API_ORIGIN: "https://cortex-api.onrender.com" },
      upstreamFetch,
    );

    expect(response.status).toBe(201);
    expect(response.headers.get("set-cookie")).toContain("CORTEX_SESSION=");
  });

  it.each([
    "",
    "http://cortex-api.onrender.com",
    "https://cortex-api.onrender.com/base",
    "https://cortex-api.onrender.com?target=other",
    "https://user:password@cortex-api.onrender.com",
  ])("rejects an unsafe upstream origin: %s", async (origin) => {
    await expect(
      proxyApiRequest(
        new Request("https://cortex-stavias.pages.dev/api/health"),
        { CORTEX_API_ORIGIN: origin },
        vi.fn(),
      ),
    ).rejects.toThrow("CORTEX_API_ORIGIN");
  });
});
