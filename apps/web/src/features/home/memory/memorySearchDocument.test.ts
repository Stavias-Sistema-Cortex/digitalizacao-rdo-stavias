import { describe, expect, it } from "vitest";

import type { OperationalEventRecord } from "../../../lib/db/db.types";
import {
  localEventToSearchDocument,
  memoryCoverage,
  normalizeMemoryText,
  serverEventToSearchDocument,
} from "./memorySearchDocument";
import type { MemoryServerEvent } from "./memoryApi";

const USER_ID = "00000000-0000-4000-8000-000000000001";
const WORKSITE_ID = "00000000-0000-4000-8000-000000000002";
const SCOPE_HASH = "scope-a";

function serverEvent(overrides: Partial<MemoryServerEvent> = {}): MemoryServerEvent {
  return {
    eventId: "event-1",
    commitSequence: 42,
    eventType: "RDO_EDITADO",
    source: "SYNC_PUSH",
    principalEntityType: "RDO",
    principalEntityId: "rdo-1",
    principalName: "Compactação Norte",
    worksiteId: WORKSITE_ID,
    worksiteName: "Duplicação BR-262",
    rdoId: "rdo-1",
    rdoNumber: "RDO-017",
    serviceName: "Compactação do aterro",
    occurredAt: "2026-07-21T12:00:00.000Z",
    synchronizedAt: "2026-07-21T12:00:01.000Z",
    origin: "SYNC",
    syncStatus: "SYNCED",
    schemaVersion: 13,
    result: "SYNCED",
    errorCategory: null,
    relevance: 0,
    ...overrides,
  };
}

function localEvent(
  overrides: Partial<OperationalEventRecord> = {},
): OperationalEventRecord {
  return {
    id: "local-event-1",
    type: "RDO_EDITADO",
    principalEntity: { tipo: "RDO", id: "rdo-2", nome: "Talude Sul" },
    principalEntityKey: "RDO:rdo-2",
    relatedEntities: [],
    obraId: WORKSITE_ID,
    rdoId: "rdo-2",
    colaboradorId: null,
    occurredAt: "2026-07-22T09:00:00.000Z",
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: USER_ID,
    responsibleUserName: "Encarregado",
    payload: { observacaoPrivada: "não indexar payload arbitrário" },
    syncStatus: "PENDING_SYNC",
    schemaVersion: 13,
    clientMutationId: "mutation-1",
    result: "PENDING",
    ...overrides,
  };
}

describe("Memory search documents", () => {
  it("normalizes case, diacritics and whitespace once on write", () => {
    expect(normalizeMemoryText("  CompactAÇÃO   do ATERRO  ")).toBe(
      "compactacao do aterro",
    );
  });

  it("indexes only the safe server projection and structural keys", () => {
    const document = serverEventToSearchDocument(
      USER_ID,
      SCOPE_HASH,
      serverEvent(),
    );

    expect(document).toMatchObject({
      userId: USER_ID,
      scopeHash: SCOPE_HASH,
      eventId: "event-1",
      commitSequence: 42,
      syncStatus: "UPDATED",
      structuralKeys: {
        eventType: "RDO_EDITADO",
        entityType: "RDO",
        entityId: "rdo-1",
        worksiteId: WORKSITE_ID,
        rdoId: "rdo-1",
        origin: "SYNC",
        result: "SYNCED",
      },
    });
    expect(document.normalizedText).toContain("compactacao norte");
    expect(document.normalizedText).toContain("duplicacao br-262");
  });

  it("preserves a protected PII identity as null without inventing an ID", () => {
    const document = serverEventToSearchDocument(
      USER_ID,
      SCOPE_HASH,
      serverEvent({
        principalEntityType: "COLABORADOR",
        principalEntityId: null,
        principalName: null,
      }),
    );

    expect(document.structuralKeys.entityId).toBeNull();
    expect(document.normalizedText).not.toContain("undefined");
    expect(document.normalizedText).not.toContain("nao informada");
  });

  it.each([
    ["PENDING_SYNC", "PENDING", "LOCAL_PENDING"],
    ["SYNCING", "SYNCING", "SYNCING"],
    ["SYNC_FAILED", "CONFLICT", "CONFLICT"],
    ["SYNC_FAILED", "REJECTED", "REJECTED"],
  ] as const)(
    "maps local %s/%s to the literal ledger state %s",
    (syncStatus, result, expected) => {
      expect(
        localEventToSearchDocument(
          USER_ID,
          SCOPE_HASH,
          localEvent({ syncStatus, result }),
        ).syncStatus,
      ).toBe(expected);
    },
  );

  it("never indexes arbitrary local payload values", () => {
    const document = localEventToSearchDocument(
      USER_ID,
      SCOPE_HASH,
      localEvent(),
    );
    expect(document.normalizedText).toContain("talude sul");
    expect(document.normalizedText).not.toContain("nao indexar");
  });

  it("applies the same PII redaction to local evidence", () => {
    const document = localEventToSearchDocument(
      USER_ID,
      SCOPE_HASH,
      localEvent({
        principalEntity: {
          tipo: "COLABORADOR",
          id: "cpf-or-private-id",
          nome: "Nome privado",
        },
        principalEntityKey: "COLABORADOR:cpf-or-private-id",
      }),
    );

    expect(document.structuralKeys.entityId).toBeNull();
    expect(document.principalName).toBeNull();
    expect(document.normalizedText).not.toContain("cpf-or-private-id");
    expect(document.normalizedText).not.toContain("nome privado");
  });
});

describe("memoryCoverage", () => {
  it("labels incomplete offline coverage as partial", () => {
    expect(
      memoryCoverage({
        online: false,
        metadata: {
          userId: USER_ID,
          scopeHash: SCOPE_HASH,
          highWaterMark: 90,
          authorizedEventCount: 90,
          cachedEventCount: 30,
          oldestCommitSequence: 1,
          newestCommitSequence: 90,
          coverageMode: "FULL_HISTORY",
          serverCoverageComplete: true,
          graph: {
            checkpointCommitSequence: 87,
            targetCommitSequence: 90,
            lagEventCount: 3,
            fresh: false,
            lastSafeError: null,
          },
          complete: false,
          cachedAt: "2026-07-22T10:00:00.000Z",
        },
        localStatuses: [],
      }),
    ).toMatchObject({ code: "PARTIAL", label: "Parcial" });
  });

  it("keeps a complete offline cache truthful up to its high-water mark", () => {
    expect(
      memoryCoverage({
        online: false,
        metadata: {
          userId: USER_ID,
          scopeHash: SCOPE_HASH,
          highWaterMark: 90,
          authorizedEventCount: 90,
          cachedEventCount: 90,
          oldestCommitSequence: 1,
          newestCommitSequence: 90,
          coverageMode: "FULL_HISTORY",
          serverCoverageComplete: true,
          graph: {
            checkpointCommitSequence: 90,
            targetCommitSequence: 90,
            lagEventCount: 0,
            fresh: true,
            lastSafeError: null,
          },
          complete: true,
          cachedAt: "2026-07-22T10:00:00.000Z",
        },
        localStatuses: [],
      }),
    ).toMatchObject({ code: "UPDATED", label: "Atualizado" });
  });

  it("surfaces the most actionable durable local state", () => {
    expect(
      memoryCoverage({
        online: true,
        metadata: null,
        localStatuses: ["LOCAL_PENDING", "CONFLICT"],
      }),
    ).toMatchObject({
      code: "CONFLICT",
      label: "Conflito",
      detail: expect.stringMatching(/cobertura.*parcial/i),
    });
  });

  it("labels a complete event cache as partial while graph projection lags", () => {
    expect(
      memoryCoverage({
        online: true,
        metadata: {
          userId: USER_ID,
          scopeHash: SCOPE_HASH,
          highWaterMark: 90,
          authorizedEventCount: 90,
          cachedEventCount: 90,
          oldestCommitSequence: 1,
          newestCommitSequence: 90,
          coverageMode: "FULL_HISTORY",
          serverCoverageComplete: true,
          graph: {
            checkpointCommitSequence: 87,
            targetCommitSequence: 90,
            lagEventCount: 3,
            fresh: false,
            lastSafeError: "GRAPH_PROJECTION_FAILED",
          },
          complete: true,
          cachedAt: "2026-07-22T10:00:00.000Z",
        },
        localStatuses: [],
      }),
    ).toMatchObject({
      code: "PARTIAL",
      label: "Parcial",
      detail: expect.stringMatching(/3 eventos.*commit 90.*GRAPH_PROJECTION_FAILED/i),
    });
  });

  it("keeps conflict priority and appends the graph lag warning", () => {
    expect(
      memoryCoverage({
        online: true,
        metadata: {
          userId: USER_ID,
          scopeHash: SCOPE_HASH,
          highWaterMark: 90,
          authorizedEventCount: 90,
          cachedEventCount: 90,
          oldestCommitSequence: 1,
          newestCommitSequence: 90,
          coverageMode: "FULL_HISTORY",
          serverCoverageComplete: true,
          graph: {
            checkpointCommitSequence: 87,
            targetCommitSequence: 90,
            lagEventCount: 3,
            fresh: false,
            lastSafeError: null,
          },
          complete: true,
          cachedAt: "2026-07-22T10:00:00.000Z",
        },
        localStatuses: ["CONFLICT"],
      }),
    ).toMatchObject({
      code: "CONFLICT",
      detail: expect.stringMatching(/divergentes.*grafo.*3 eventos/i),
    });
  });
});
