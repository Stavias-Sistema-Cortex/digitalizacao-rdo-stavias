import { describe, expect, it } from "vitest";
import type {
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  OperationalEventRecord,
} from "../../../lib/db/db.types";
import type { MemoryEvent } from "./memory.types";
import {
  memoryConflictReviewRecords,
  memoryDiffRows,
  memoryEventLabel,
  mergeMemoryEvents,
} from "./memoryViewModel";

const serverEvent: MemoryEvent = {
  id: "server-1",
  commitSeq: 12,
  sourceKind: "SERVER",
  type: "RDO_EDITADO",
  source: "RdoService",
  actorId: "actor-1",
  actorName: "Ana",
  actorLabel: "Ana",
  obraId: "obra-1",
  rdoId: "rdo-1",
  colaboradorId: null,
  principalEntity: { type: "RDO", id: "rdo-1", name: null },
  relatedEntities: [],
  occurredAt: "2026-07-16T14:00:00Z",
  syncedAt: "2026-07-16T14:00:01Z",
  origin: "ONLINE",
  syncStatus: "SYNCED",
  schemaVersion: 2,
  deviceId: null,
  correlationId: "corr-1",
  causationId: null,
  previousState: { status: "RASCUNHO", quantidade: 2 },
  newState: { status: "ENVIADO", quantidade: 3 },
  payload: {},
  result: "SUCESSO",
  errorCategory: null,
};

function localEvent(
  overrides: Partial<OperationalEventRecord> = {},
): OperationalEventRecord {
  return {
    id: "local-1",
    type: "TAREFA_CONCLUIDA",
    principalEntity: { tipo: "TAREFA", id: "tarefa-1" },
    principalEntityKey: "TAREFA:tarefa-1",
    relatedEntities: [],
    obraId: "obra-1",
    rdoId: null,
    colaboradorId: "actor-1",
    occurredAt: "2026-07-16T15:00:00Z",
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: "actor-1",
    responsibleUserName: "Ana",
    payload: { titulo: "Conferir pavimento" },
    syncStatus: "PENDING_SYNC",
    schemaVersion: 1,
    ...overrides,
  };
}

describe("memoryViewModel", () => {
  it("mescla eventos locais autorizados e mantém o servidor como verdade", () => {
    const result = mergeMemoryEvents(
      [serverEvent],
      [
        localEvent(),
        localEvent({ id: "server-1" }),
        localEvent({ id: "blocked", obraId: "obra-x" }),
        localEvent({
          id: "own-global",
          obraId: null,
          responsibleUserId: "actor-1",
        }),
        localEvent({
          id: "foreign-global",
          obraId: null,
          responsibleUserId: "actor-2",
        }),
      ],
      ["obra-1"],
      "actor-1",
    );

    expect(result.map((event) => event.id)).toEqual([
      "local-1",
      "own-global",
      "server-1",
    ]);
    expect(result[0]).toMatchObject({
      sourceKind: "DEVICE",
      commitSeq: null,
      actorLabel: "Ana",
    });
  });

  it("considera escopo global quando a lista autorizada é nula", () => {
    const result = mergeMemoryEvents(
      [],
      [localEvent({ obraId: "obra-x" })],
      null,
      "actor-1",
    );
    expect(result).toHaveLength(1);
  });

  it("descreve eventos e produz diferenças apenas para campos alterados", () => {
    expect(memoryEventLabel("TAREFA_CONCLUIDA"))
      .toBe("Tarefa concluída");
    expect(memoryEventLabel("REGRA_NOVA"))
      .toBe("Regra nova");
    expect(memoryDiffRows(serverEvent)).toEqual([
      { field: "quantidade", previous: 2, next: 3 },
      { field: "status", previous: "RASCUNHO", next: "ENVIADO" },
    ]);
  });

  it("expõe no dispositivo somente a proveniência canônica realmente armazenada", () => {
    const canonical = localEvent({
      contractVersion: 13,
      clientMutationId: "mutation-conflict",
      deviceId: "device-1",
      correlationId: "correlation-1",
      causationId: "causation-1",
      previousState: { titulo: "Base" },
      newState: { titulo: "Local" },
      result: "CONFLICT",
      errorCategory: "FIELD_CONFLICT",
      entityVersion: 4,
    });

    const [event] = mergeMemoryEvents(
      [],
      [canonical],
      ["obra-1"],
      "actor-1",
    );

    expect(event).toMatchObject({
      deviceId: "device-1",
      correlationId: "correlation-1",
      causationId: "causation-1",
      previousState: { titulo: "Base" },
      newState: { titulo: "Local" },
      result: "CONFLICT",
      errorCategory: "FIELD_CONFLICT",
    });
  });

  it("projeta revisão de conflito apenas quando evento e triplas estão armazenados", () => {
    const mutation: CanonicalOutboxMutationRecord = {
      contractVersion: 13,
      clientMutationId: "mutation-conflict",
      entidadeTipo: "RDO",
      entidadeId: "rdo-1",
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      baseVersao: 4,
      payload: { titulo: "Local" },
      status: "CONFLICT",
      tentativas: 1,
      ultimaTentativaEm: "2026-07-17T15:01:00Z",
      ultimoErro: "Conflito de campo.",
      conflito: {
        titulo: { base: "Base", local: "Local", remote: "Remoto" },
      },
      criadaNoClienteEm: "2026-07-17T15:00:00Z",
      updatedAt: "2026-07-17T15:02:00Z",
      transport: "SYNC_PUSH",
      dependsOnMutationIds: [],
      correlationId: "correlation-1",
      fieldPatch: {
        changed: { titulo: "Local" },
        baseValues: { titulo: "Base" },
      },
      trace: {
        actorId: "actor-1",
        deviceId: "device-1",
        authorizationScope: ["obra-1"],
        correlationId: "correlation-1",
        causationId: null,
        ontologyEventId: "event-conflict",
        payloadHash: "a".repeat(64),
      },
      nextAttemptAt: null,
      blockedReason: null,
    };
    const conflictEvent = localEvent({
      contractVersion: 13,
      id: "event-conflict",
      principalEntity: { tipo: "RDO", id: "rdo-1" },
      principalEntityKey: "RDO:rdo-1",
      rdoId: "rdo-1",
      clientMutationId: mutation.clientMutationId,
      deviceId: "device-1",
      correlationId: "correlation-1",
      causationId: null,
      previousState: { titulo: "Base" },
      newState: { titulo: "Local" },
      result: "CONFLICT",
      errorCategory: "FIELD_CONFLICT",
      entityVersion: 4,
    }) as CanonicalOperationalEventRecord;
    const unstructured = {
      ...mutation,
      clientMutationId: "mutation-version-only",
      conflito: { versaoAtual: 5 },
      trace: {
        ...mutation.trace,
        ontologyEventId: "event-version-only",
      },
    };

    expect(memoryConflictReviewRecords(
      [conflictEvent],
      [mutation, unstructured],
    )).toEqual([{
      eventId: "event-conflict",
      clientMutationId: "mutation-conflict",
      actorId: "actor-1",
      actorName: "Ana",
      deviceId: "device-1",
      entity: { type: "RDO", id: "rdo-1", name: null },
      operation: "ATUALIZAR_RDO_RASCUNHO",
      occurredAt: "2026-07-16T15:00:00Z",
      updatedAt: "2026-07-17T15:02:00Z",
      conflicts: {
        titulo: { base: "Base", local: "Local", remote: "Remoto" },
      },
    }]);
  });
});
