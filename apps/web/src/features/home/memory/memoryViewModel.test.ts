import { describe, expect, it } from "vitest";
import type {
  CanonicalOperationalEventRecord,
  CanonicalOutboxMutationRecord,
  OutboxMutationRecord,
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

function conflictMutation(
  clientMutationId = "mutation-conflict",
  conflito: Record<string, unknown> = {
    titulo: { base: "Base", local: "Local", remote: "Remoto" },
  },
): CanonicalOutboxMutationRecord {
  return {
    contractVersion: 13,
    clientMutationId,
    entidadeTipo: "RDO",
    entidadeId: "rdo-1",
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: 4,
    payload: { titulo: "Local" },
    status: "CONFLICT",
    tentativas: 1,
    ultimaTentativaEm: "2026-07-17T15:01:00Z",
    ultimoErro: "Conflito de campo.",
    conflito,
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
      ontologyEventId: `event-${clientMutationId}`,
      payloadHash: "a".repeat(64),
    },
    nextAttemptAt: null,
    blockedReason: null,
  };
}

function conflictEvent(
  mutation: CanonicalOutboxMutationRecord,
): CanonicalOperationalEventRecord {
  return localEvent({
    contractVersion: 13,
    id: mutation.trace.ontologyEventId,
    principalEntity: { tipo: "RDO", id: mutation.entidadeId },
    principalEntityKey: `RDO:${mutation.entidadeId}`,
    rdoId: mutation.entidadeId,
    clientMutationId: mutation.clientMutationId,
    deviceId: mutation.trace.deviceId,
    correlationId: mutation.trace.correlationId,
    causationId: mutation.trace.causationId,
    previousState: { titulo: "Base" },
    newState: { titulo: "Local" },
    result: "CONFLICT",
    errorCategory: "FIELD_CONFLICT",
    entityVersion: mutation.baseVersao,
  }) as CanonicalOperationalEventRecord;
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
    const mutation = conflictMutation();
    const event = conflictEvent(mutation);

    expect(memoryConflictReviewRecords(
      [event],
      [mutation],
    )).toEqual([{
      eventId: "event-mutation-conflict",
      clientMutationId: "mutation-conflict",
      actorId: "actor-1",
      actorName: "Ana",
      deviceId: "device-1",
      entity: { type: "RDO", id: "rdo-1", name: null },
      operation: "ATUALIZAR_RDO_RASCUNHO",
      occurredAt: "2026-07-16T15:00:00Z",
      updatedAt: "2026-07-17T15:02:00Z",
      status: "CONFLICT",
      reason: "Conflito de campo.",
      authorizationScope: ["obra-1"],
      conflicts: {
        titulo: { base: "Base", local: "Local", remote: "Remoto" },
      },
    }]);
  });

  it("omite conflito apenas de versão mesmo com evento correlacionado", () => {
    const mutation = conflictMutation(
      "mutation-version-only",
      { versaoAtual: 5 },
    );

    expect(memoryConflictReviewRecords(
      [conflictEvent(mutation)],
      [mutation],
    )).toEqual([]);
  });

  it("omite conflito estruturado sem evento persistido", () => {
    expect(memoryConflictReviewRecords(
      [],
      [conflictMutation("mutation-without-event")],
    )).toEqual([]);
  });

  it("mantém uma rejeição legada revisável mesmo sem inventar um evento canônico", () => {
    const rejected = {
      ...conflictMutation("legacy-rejected"),
      contractVersion: undefined,
      trace: undefined,
      fieldPatch: undefined,
      nextAttemptAt: undefined,
      blockedReason: "Rastro canônico ausente; requer revisão.",
      status: "REJECTED",
      conflito: null,
      ultimoErro: "Rastro canônico ausente; requer revisão.",
      entidadeId: "legacy-message-1",
      entidadeTipo: "MENSAGEM",
      operacao: "CRIAR_MENSAGEM",
      criadaNoClienteEm: "2026-07-20T10:00:00.000Z",
      updatedAt: "2026-07-20T10:01:00.000Z",
    } as unknown as OutboxMutationRecord;

    expect(memoryConflictReviewRecords([], [rejected])).toEqual([{
      eventId: null,
      clientMutationId: "legacy-rejected",
      actorId: null,
      actorName: null,
      deviceId: null,
      entity: { type: "MENSAGEM", id: "legacy-message-1", name: null },
      operation: "CRIAR_MENSAGEM",
      occurredAt: "2026-07-20T10:00:00.000Z",
      updatedAt: "2026-07-20T10:01:00.000Z",
      status: "REJECTED",
      reason: "Rastro canônico ausente; requer revisão.",
      authorizationScope: [],
      conflicts: {},
    }]);
  });

  it("preserva __proto__ como campo próprio na revisão armazenada", () => {
    const storedConflicts = JSON.parse(
      '{"__proto__":{"base":"Base","local":"Local","remote":"Remoto"}}',
    ) as Record<string, unknown>;
    const mutation = conflictMutation(
      "mutation-proto-field",
      storedConflicts,
    );

    const [review] = memoryConflictReviewRecords(
      [conflictEvent(mutation)],
      [mutation],
    );

    expect(review).toBeDefined();
    expect(Object.getPrototypeOf(review?.conflicts)).toBe(Object.prototype);
    expect(Object.hasOwn(review?.conflicts ?? {}, "__proto__")).toBe(true);
    expect(review?.conflicts).toEqual(storedConflicts);
  });
});
