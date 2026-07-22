import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
}));

vi.mock("../../../lib/api/apiClient", () => ({
  apiFetch: mocks.apiFetch,
  readResponseBody: async (response: Response) => response.json(),
  responseErrorMessage: (_body: unknown, status: number) =>
    `HTTP ${status}`,
}));

import {
  fetchMemoryPage,
  memoryEventFromApi,
  memoryQueryString,
} from "./memoryApi";

describe("memoryApi", () => {
  beforeEach(() => mocks.apiFetch.mockReset());

  it("serializa filtros estruturais sem valores vazios", () => {
    expect(memoryQueryString({
      obraId: "obra-1",
      eventType: "",
      limit: 50,
    }, 90)).toBe(
      "obraId=obra-1&limit=50&beforeCommitSeq=90",
    );
  });

  it("preserva ator e auditoria sem inferir campos ausentes", () => {
    expect(memoryEventFromApi({
      id: "e1",
      commitSeq: 8,
      type: "RDO_CRIADO",
      actorId: null,
      actorName: null,
      payload: {},
      relatedEntities: [],
    })).toMatchObject({
      actorId: null,
      actorName: null,
      actorLabel: "Processo do sistema",
      sourceKind: "SERVER",
    });
  });

  it("busca a página pelo endpoint exclusivo de memória", async () => {
    mocks.apiFetch.mockResolvedValue(new Response(JSON.stringify({
      events: [{
        id: "e1",
        commitSeq: 8,
        type: "RDO_CRIADO",
        actorId: "actor-1",
        actorName: "Ana Lima",
        principalEntityType: "RDO",
        principalEntityId: "rdo-1",
        payload: {},
        relatedEntities: [],
      }],
      nextBeforeCommitSeq: 8,
      hasMore: true,
      scope: "GLOBAL",
      serverTime: "2026-07-16T18:00:00Z",
    }), { status: 200 }));

    const page = await fetchMemoryPage({ obraId: "obra-1", limit: 50 });

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/ontology/memory?obraId=obra-1&limit=50",
    );
    expect(page.events[0]).toMatchObject({
      id: "e1",
      actorLabel: "Ana Lima",
      sourceKind: "SERVER",
    });
    expect(page.hasMore).toBe(true);
  });
});
