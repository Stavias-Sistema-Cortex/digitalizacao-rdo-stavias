import { describe, expect, it, vi } from "vitest";

import type { MemoryPage } from "./memoryApi";
import type { MemoryCacheMetadata } from "./memorySearchDocument";
import { hydrateAuthorizedMemoryCache } from "./useMemoryLedger";

const USER_ID = "00000000-0000-4000-8000-000000000001";

function page(input: {
  ids: number[];
  token?: string;
  hasMore: boolean;
  highWaterMark?: number;
  scopeHash?: string;
}): MemoryPage {
  const highWaterMark = input.highWaterMark ?? 3;
  return {
    items: input.ids.map((id) => ({
      eventId: `event-${id}`,
      commitSequence: id,
      eventType: "RDO_EDITADO",
      source: "SYNC_PUSH",
      principalEntityType: "RDO",
      principalEntityId: `rdo-${id}`,
      principalName: null,
      worksiteId: "obra-1",
      worksiteName: null,
      rdoId: `rdo-${id}`,
      rdoNumber: null,
      serviceName: null,
      occurredAt: "2026-07-22T10:00:00Z",
      synchronizedAt: "2026-07-22T10:00:01Z",
      origin: "SYNC",
      syncStatus: "SYNCED",
      schemaVersion: 13,
      result: "SYNCED",
      errorCategory: null,
      relevance: 0,
    })),
    nextCursor: input.token ? { token: input.token } : null,
    hasMore: input.hasMore,
    highWaterMark,
    scopeHash: input.scopeHash ?? "scope-a",
    coverage: {
      mode: "FULL_HISTORY",
      complete: true,
      authorizedEventCount: 3,
      oldestCommitSequence: 1,
      newestCommitSequence: highWaterMark,
    },
    serverTime: "2026-07-22T10:00:02Z",
  };
}

function metadata(): MemoryCacheMetadata {
  return {
    userId: USER_ID,
    scopeHash: "scope-a",
    highWaterMark: 3,
    authorizedEventCount: 3,
    cachedEventCount: 3,
    oldestCommitSequence: 1,
    newestCommitSequence: 3,
    coverageMode: "FULL_HISTORY",
    serverCoverageComplete: true,
    complete: true,
    cachedAt: "2026-07-22T10:00:02Z",
  };
}

describe("authorized Memory cache hydration", () => {
  it("follows only signed cursors and marks coverage complete after every page", async () => {
    const first = page({ ids: [3, 2], token: "signed-next", hasMore: true });
    const last = page({ ids: [1], hasMore: false });
    const fetchPage = vi.fn()
      .mockResolvedValueOnce(first)
      .mockResolvedValueOnce(last);
    const putPage = vi.fn().mockResolvedValue(undefined);
    const markComplete = vi.fn().mockResolvedValue(metadata());

    await hydrateAuthorizedMemoryCache({
      userId: USER_ID,
      previousMetadata: null,
      fetchPage,
      putPage,
      markComplete,
      resetAuthorizedCache: vi.fn().mockResolvedValue(undefined),
      assertSession: vi.fn(),
    });

    expect(fetchPage).toHaveBeenNthCalledWith(1, { limit: 100 }, null);
    expect(fetchPage).toHaveBeenNthCalledWith(2, { limit: 100 }, {
      token: "signed-next",
    });
    expect(putPage).toHaveBeenCalledTimes(2);
    expect(markComplete).toHaveBeenCalledWith(
      USER_ID,
      expect.objectContaining({
        scopeHash: "scope-a",
        highWaterMark: 3,
        hasMore: false,
      }),
    );
  });

  it("leaves already written pages partial when signed snapshot evidence changes", async () => {
    const first = page({ ids: [3, 2], token: "signed-next", hasMore: true });
    const inconsistent = page({
      ids: [1],
      hasMore: false,
      highWaterMark: 4,
    });
    const putPage = vi.fn().mockResolvedValue(undefined);
    const markComplete = vi.fn();

    await expect(
      hydrateAuthorizedMemoryCache({
        userId: USER_ID,
        previousMetadata: null,
        fetchPage: vi.fn()
          .mockResolvedValueOnce(first)
          .mockResolvedValueOnce(inconsistent),
        putPage,
        markComplete,
        resetAuthorizedCache: vi.fn().mockResolvedValue(undefined),
        assertSession: vi.fn(),
      }),
    ).rejects.toThrow(/marca d.água mudou/i);

    expect(putPage).toHaveBeenCalledTimes(1);
    expect(markComplete).not.toHaveBeenCalled();
  });

  it("does not repaginate an unchanged complete snapshot", async () => {
    const first = page({ ids: [3, 2], token: "unused", hasMore: true });
    const fetchPage = vi.fn().mockResolvedValue(first);
    const markComplete = vi.fn().mockResolvedValue(metadata());

    await hydrateAuthorizedMemoryCache({
      userId: USER_ID,
      previousMetadata: metadata(),
      fetchPage,
      putPage: vi.fn().mockResolvedValue(undefined),
      markComplete,
      resetAuthorizedCache: vi.fn().mockResolvedValue(undefined),
      assertSession: vi.fn(),
    });

    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(markComplete).toHaveBeenCalledWith(
      USER_ID,
      expect.objectContaining({ hasMore: false, nextCursor: null }),
    );
  });

  it("purges and rebuilds when authorization count shrinks at the same high-water", async () => {
    const revoked = page({ ids: [3, 2], hasMore: false });
    revoked.coverage = {
      ...revoked.coverage,
      authorizedEventCount: 2,
      oldestCommitSequence: 2,
    };
    const previous = metadata();
    const resetAuthorizedCache = vi.fn().mockResolvedValue(undefined);
    const putPage = vi.fn().mockResolvedValue(undefined);
    const markComplete = vi.fn().mockResolvedValue({
      ...previous,
      authorizedEventCount: 2,
      cachedEventCount: 2,
      oldestCommitSequence: 2,
    });

    await hydrateAuthorizedMemoryCache({
      userId: USER_ID,
      previousMetadata: previous,
      fetchPage: vi.fn().mockResolvedValue(revoked),
      putPage,
      markComplete,
      resetAuthorizedCache,
      assertSession: vi.fn(),
    });

    expect(resetAuthorizedCache).toHaveBeenCalledWith(USER_ID);
    expect(resetAuthorizedCache.mock.invocationCallOrder[0]).toBeLessThan(
      putPage.mock.invocationCallOrder[0],
    );
  });

  it("rebuilds exact coverage when high-water and authorized count advance", async () => {
    const advanced = page({ ids: [4, 3], hasMore: false, highWaterMark: 4 });
    advanced.coverage = {
      ...advanced.coverage,
      authorizedEventCount: 4,
      oldestCommitSequence: 1,
      newestCommitSequence: 4,
    };
    const resetAuthorizedCache = vi.fn().mockResolvedValue(undefined);

    await hydrateAuthorizedMemoryCache({
      userId: USER_ID,
      previousMetadata: metadata(),
      fetchPage: vi.fn().mockResolvedValue(advanced),
      putPage: vi.fn().mockResolvedValue(undefined),
      markComplete: vi.fn().mockResolvedValue({
        ...metadata(),
        highWaterMark: 4,
        authorizedEventCount: 4,
        cachedEventCount: 4,
        newestCommitSequence: 4,
      }),
      resetAuthorizedCache,
      assertSession: vi.fn(),
    });

    expect(resetAuthorizedCache).toHaveBeenCalledWith(USER_ID);
  });
});
