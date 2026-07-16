import { describe, expect, it } from "vitest";
import type { OperationalEventRecord } from "../../../lib/db/db.types";
import type { MemoryEvent } from "./memory.types";
import {
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
});
