import { describe, expect, it } from "vitest";

import {
  buildMemoryExport,
  MAX_MEMORY_EXPORT_ITEMS,
} from "./memoryExport";
import type { MemorySearchDocument } from "./memorySearchDocument";

function document(index: number): MemorySearchDocument {
  return {
    key: `user:scope:event-${index}`,
    userId: "user-private",
    scopeHash: "scope-private",
    eventId: `event-${index}`,
    commitSequence: index,
    normalizedText: `índice privado ${index}`,
    structuralKeys: {
      eventType: "RDO_EDITADO",
      entityType: "RDO",
      entityId: `rdo-${index}`,
      worksiteId: "obra-1",
      rdoId: `rdo-${index}`,
      actorId: "actor-1",
      deviceId: "device-1",
      origin: "OFFLINE",
      result: "CONFLICT",
    },
    syncStatus: "CONFLICT",
    sourceKind: "LOCAL",
    occurredAt: "2026-07-25T12:00:00.000Z",
    eventType: "RDO_EDITADO",
    source: "OFFLINE",
    principalName: "RDO autorizado",
    worksiteName: "Obra autorizada",
    rdoNumber: String(index),
    serviceName: null,
    errorCategory: "VERSION_CONFLICT",
    trace: {
      clientMutationId: `mutation-${index}`,
      correlationId: `correlation-${index}`,
      causationId: null,
      entityVersion: 3,
    },
    review: {
      status: "CONFLICT",
      clientMutationId: `mutation-${index}`,
      baseVersion: 3,
      eventVersion: 3,
      remoteVersion: 4,
      localStateAvailable: true,
      remoteStateAvailable: false,
      changedFields: ["titulo"],
      conflictFields: ["titulo"],
      canReconcile: false,
      unavailableReason: "REMOTE_SNAPSHOT_UNAVAILABLE",
    },
  };
}

describe("bounded authorized Memory export", () => {
  it("exports only the bounded filtered safe projection with explicit truncation", () => {
    const result = buildMemoryExport({
      items: Array.from(
        { length: MAX_MEMORY_EXPORT_ITEMS + 1 },
        (_, index) => document(index + 1),
      ),
      totalMatches: MAX_MEMORY_EXPORT_ITEMS + 7,
      filters: {
        actorId: "actor-1",
        deviceId: "device-1",
        result: "CONFLICT",
      },
      metadata: null,
      generatedAt: "2026-07-25T12:30:00.000Z",
    });
    const parsed = JSON.parse(result.content);

    expect(result).toMatchObject({
      exportedCount: MAX_MEMORY_EXPORT_ITEMS,
      totalMatches: MAX_MEMORY_EXPORT_ITEMS + 7,
      truncated: true,
      filename: "memoria-operacional-2026-07-25T12-30-00-000Z.json",
    });
    expect(parsed.items).toHaveLength(MAX_MEMORY_EXPORT_ITEMS);
    expect(parsed.filters).toEqual({
      actorId: "actor-1",
      deviceId: "device-1",
      result: "CONFLICT",
    });
    expect(parsed.items[0]).toMatchObject({
      eventId: "event-1",
      structuralKeys: {
        actorId: "actor-1",
        deviceId: "device-1",
      },
      trace: {
        clientMutationId: "mutation-1",
        entityVersion: 3,
      },
      review: {
        remoteVersion: 4,
        remoteStateAvailable: false,
      },
    });
    expect(result.content).not.toContain("índice privado");
    expect(result.content).not.toContain("user-private");
    expect(result.content).not.toContain("scope-private");
  });
});
