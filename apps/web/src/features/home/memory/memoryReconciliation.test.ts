import { describe, expect, it, vi } from "vitest";

import type { MemorySearchDocument } from "./memorySearchDocument";
import { runMemoryReconciliation } from "./useMemoryLedger";

function reviewDocument(canReconcile: boolean): MemorySearchDocument {
  return {
    key: "user:scope:event-1",
    userId: "user-1",
    scopeHash: "scope-1",
    eventId: "event-1",
    commitSequence: null,
    normalizedText: "rdo conflito",
    structuralKeys: {
      eventType: "RDO_EDITADO",
      entityType: "RDO",
      entityId: "rdo-1",
      worksiteId: "obra-1",
      rdoId: "rdo-1",
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
    rdoNumber: "17",
    serviceName: null,
    errorCategory: "VERSION_CONFLICT",
    trace: {
      clientMutationId: "mutation-1",
      correlationId: "correlation-1",
      causationId: null,
      entityVersion: 3,
    },
    review: {
      status: "CONFLICT",
      clientMutationId: "mutation-1",
      baseVersion: 3,
      eventVersion: 3,
      remoteVersion: 4,
      localStateAvailable: true,
      remoteStateAvailable: canReconcile,
      changedFields: ["titulo"],
      conflictFields: canReconcile ? [] : ["titulo"],
      canReconcile,
      unavailableReason: canReconcile ? null : "REMOTE_SNAPSHOT_UNAVAILABLE",
    },
  };
}

describe("Memory reconciliation action", () => {
  it("never calls the canonical mutator without complete review evidence", async () => {
    const reconcile = vi.fn();

    await expect(
      runMemoryReconciliation(reviewDocument(false), reconcile),
    ).resolves.toBe(false);
    expect(reconcile).not.toHaveBeenCalled();
  });

  it("delegates an eligible conflict to the existing canonical mutator", async () => {
    const reconcile = vi.fn().mockResolvedValue({ clientMutationId: "replacement" });

    await expect(
      runMemoryReconciliation(reviewDocument(true), reconcile),
    ).resolves.toBe(true);
    expect(reconcile).toHaveBeenCalledWith("mutation-1");
  });
});
