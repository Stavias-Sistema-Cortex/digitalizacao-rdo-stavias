import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn() }));

vi.mock("../../../lib/api/apiClient", async () => {
  const actual = await vi.importActual<typeof import("../../../lib/api/apiClient")>(
    "../../../lib/api/apiClient",
  );
  return { ...actual, apiFetch: mocks.apiFetch };
});

import { fetchMemoryPage, memoryQueryString } from "./memoryApi";

describe("Memory API client", () => {
  beforeEach(() => mocks.apiFetch.mockReset());

  it("serializes structural filters and only the opaque signed cursor", () => {
    expect(
      memoryQueryString(
        {
          q: "compactação norte",
          worksiteId: "obra-1",
          entityType: "RDO",
          entityId: "rdo-1",
          from: "2026-07-01T00:00:00.000Z",
          limit: 100,
        },
        { token: "signed.cursor-token" },
      ),
    ).toBe(
      "q=compacta%C3%A7%C3%A3o+norte&entityType=RDO&entityId=rdo-1&obraId=obra-1&from=2026-07-01T00%3A00%3A00.000Z&limit=100&cursor=signed.cursor-token",
    );
  });

  it("parses V47 items, high-water, scope hash and coverage without fallbacks", async () => {
    mocks.apiFetch.mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [
            {
              eventId: "event-9",
              commitSequence: 9,
              eventType: "RDO_CRIADO",
              source: "SYNC_PUSH",
              principalEntityType: "RDO",
              principalEntityId: "rdo-9",
              principalName: "RDO 9",
              worksiteId: "obra-1",
              worksiteName: "BR-262",
              rdoId: "rdo-9",
              rdoNumber: "009",
              serviceName: null,
              occurredAt: "2026-07-22T09:00:00Z",
              synchronizedAt: "2026-07-22T09:00:01Z",
              origin: "SYNC",
              syncStatus: "SYNCED",
              schemaVersion: 13,
              result: "SYNCED",
              errorCategory: null,
              relevance: 0,
            },
          ],
          nextCursor: { token: "signed.next-page" },
          hasMore: true,
          highWaterMark: 12,
          scopeHash: "scope-a",
          coverage: {
            mode: "FULL_HISTORY",
            complete: true,
            authorizedEventCount: 12,
            oldestCommitSequence: 1,
            newestCommitSequence: 12,
          },
          serverTime: "2026-07-22T10:00:00Z",
        }),
        { status: 200 },
      ),
    );

    const page = await fetchMemoryPage({ limit: 100 });

    expect(mocks.apiFetch).toHaveBeenCalledWith("/ontology/memory?limit=100");
    expect(page).toMatchObject({
      items: [{ eventId: "event-9", commitSequence: 9 }],
      nextCursor: { token: "signed.next-page" },
      hasMore: true,
      highWaterMark: 12,
      scopeHash: "scope-a",
      coverage: { complete: true, authorizedEventCount: 12 },
    });
  });

  it("rejects malformed pages instead of inventing coverage", async () => {
    mocks.apiFetch.mockResolvedValue(
      new Response(JSON.stringify({ items: [] }), { status: 200 }),
    );
    await expect(fetchMemoryPage({})).rejects.toThrow(/high-water/i);
  });

  it("accepts the intentionally redacted principal ID of a PII event", async () => {
    mocks.apiFetch.mockResolvedValue(
      new Response(JSON.stringify({
        items: [{
          eventId: "event-pii",
          commitSequence: 1,
          eventType: "COLABORADOR_ATUALIZADO",
          source: "SYNC_PUSH",
          principalEntityType: "COLABORADOR",
          principalEntityId: null,
          principalName: null,
          worksiteId: null,
          worksiteName: null,
          rdoId: null,
          rdoNumber: null,
          serviceName: null,
          occurredAt: "2026-07-22T09:00:00Z",
          synchronizedAt: "2026-07-22T09:00:01Z",
          origin: "SYNC",
          syncStatus: "SYNCED",
          schemaVersion: 13,
          result: "SYNCED",
          errorCategory: null,
          relevance: 0,
        }],
        nextCursor: null,
        hasMore: false,
        highWaterMark: 1,
        scopeHash: "scope-pii",
        coverage: {
          mode: "FULL_HISTORY",
          complete: true,
          authorizedEventCount: 1,
          oldestCommitSequence: 1,
          newestCommitSequence: 1,
        },
        serverTime: "2026-07-22T10:00:00Z",
      }), { status: 200 }),
    );

    const response = await fetchMemoryPage({});
    expect(response.items[0].principalEntityId).toBeNull();
  });
});
