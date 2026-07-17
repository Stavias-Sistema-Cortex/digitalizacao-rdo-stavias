import type {
  OperationalEventRecord,
  OutboxMutationRecord,
} from "../../../lib/db/db.types";
import type {
  MemoryConflictReviewRecord,
  MemoryDiffRow,
  MemoryEntityRef,
  MemoryEvent,
  MemoryFieldConflicts,
  MemoryFieldConflictValues,
  MemoryFilters,
} from "./memory.types";

const EVENT_LABELS: Record<string, string> = {
  RDO_CRIADO: "RDO criado",
  RDO_EDITADO: "RDO editado",
  RDO_SALVO_OFFLINE: "RDO salvo no dispositivo",
  RDO_SINCRONIZADO: "RDO sincronizado",
  RDO_FALHA_SYNC: "Falha na sincronização do RDO",
  FOTO_ADICIONADA: "Foto adicionada",
  FOTO_COMPRIMIDA: "Foto comprimida",
  FOTO_REMOVIDA: "Foto removida",
  MEDICAO_TRECHO_ATUALIZADA: "Medição de trecho atualizada",
  COLABORADOR_ASSOCIADO_RDO: "Colaborador associado ao RDO",
  EQUIPAMENTO_ASSOCIADO_RDO: "Equipamento associado ao RDO",
  OCORRENCIA_REGISTRADA: "Ocorrência registrada",
  CALCULO_REPROCESSADO: "Cálculo reprocessado",
  ENTIDADE_RELACIONADA: "Entidade relacionada",
  ENTIDADE_DESRELACIONADA: "Entidade desrelacionada",
  TAREFA_CRIADA: "Tarefa criada",
  TAREFA_CONCLUIDA: "Tarefa concluída",
  TAREFA_REABERTA: "Tarefa reaberta",
  TAREFA_EXCLUIDA: "Tarefa excluída",
  OBRA_CRIADA: "Obra criada",
  OBRA_ATUALIZADA: "Obra atualizada",
  EQUIPE_CRIADA: "Equipe criada",
  EQUIPE_ATUALIZADA: "Equipe atualizada",
  EQUIPE_ARQUIVADA: "Equipe arquivada",
  MEMBRO_EQUIPE_ADICIONADO: "Membro adicionado à equipe",
  MEMBRO_EQUIPE_ATUALIZADO: "Participação na equipe atualizada",
  MEMBRO_EQUIPE_ENCERRADO: "Participação na equipe encerrada",
  EQUIPE_OBRA_ASSOCIADA: "Obra associada à equipe",
  EQUIPE_OBRA_DESASSOCIADA: "Obra desassociada da equipe",
};

export function mergeMemoryEvents(
  serverEvents: MemoryEvent[],
  localEvents: OperationalEventRecord[],
  allowedObraIds: readonly string[] | null,
  userId: string,
): MemoryEvent[] {
  const serverIds = new Set(serverEvents.map((event) => event.id));
  const allowed = allowedObraIds === null
    ? null
    : new Set(allowedObraIds);
  const deviceEvents = localEvents
    .filter((event) => !serverIds.has(event.id))
    .filter((event) => {
      if (event.obraId) {
        return allowed === null || allowed.has(event.obraId);
      }
      return event.responsibleUserId === userId;
    })
    .map(memoryEventFromDevice);

  return [...serverEvents, ...deviceEvents].sort(compareEvents);
}

export function memoryEventLabel(type: string): string {
  return EVENT_LABELS[type] ?? type
    .toLocaleLowerCase("pt-BR")
    .replaceAll("_", " ")
    .replace(/^./, (letter) => letter.toLocaleUpperCase("pt-BR"));
}

export function memoryDiffRows(event: MemoryEvent): MemoryDiffRow[] {
  const fields = new Set([
    ...Object.keys(event.previousState),
    ...Object.keys(event.newState),
  ]);
  return [...fields]
    .filter((field) => !sameValue(
      event.previousState[field],
      event.newState[field],
    ))
    .sort((left, right) => left.localeCompare(right, "pt-BR"))
    .map((field) => ({
      field,
      previous: event.previousState[field],
      next: event.newState[field],
    }));
}

export function memoryConflictReviewRecords(
  localEvents: OperationalEventRecord[],
  mutations: OutboxMutationRecord[],
): MemoryConflictReviewRecord[] {
  const eventsByMutation = new Map<string, OperationalEventRecord[]>();

  for (const event of localEvents) {
    const clientMutationId = text(event.clientMutationId);
    if (!clientMutationId || normalized(event.result) !== "CONFLICT") {
      continue;
    }
    const events = eventsByMutation.get(clientMutationId) ?? [];
    events.push(event);
    eventsByMutation.set(clientMutationId, events);
  }

  return mutations.flatMap((mutation) => {
    if (mutation.status !== "CONFLICT") {
      return [];
    }
    const conflicts = storedFieldConflicts(mutation.conflito);
    if (Object.keys(conflicts).length === 0) {
      return [];
    }
    const candidates = eventsByMutation.get(mutation.clientMutationId) ?? [];
    const eventId = text(mutation.trace?.ontologyEventId);
    const event = eventId
      ? candidates.find((candidate) => candidate.id === eventId)
      : candidates[0];
    if (!event) {
      return [];
    }

    return [{
      eventId: event.id,
      clientMutationId: mutation.clientMutationId,
      actorId: text(event.responsibleUserId),
      actorName: text(event.responsibleUserName),
      deviceId: text(event.deviceId),
      entity: entityRef(event.principalEntity),
      operation: mutation.operacao,
      occurredAt: text(event.occurredAt),
      updatedAt: text(mutation.updatedAt),
      conflicts,
    }];
  }).sort(compareConflictReviews);
}

export function filterMemoryEvents(
  events: MemoryEvent[],
  filters: MemoryFilters,
): MemoryEvent[] {
  const entityType = normalized(filters.entityType);
  const entityId = text(filters.entityId);
  const obraId = text(filters.obraId);
  const rdoId = text(filters.rdoId);
  const actorId = text(filters.actorId);
  const eventType = normalized(filters.eventType);
  const origin = normalized(filters.origin);
  const result = normalized(filters.result);
  const from = timestamp(text(filters.from));
  const to = timestamp(text(filters.to));

  return events.filter((event) => {
    if (obraId && event.obraId !== obraId) return false;
    if (rdoId && event.rdoId !== rdoId) return false;
    if (actorId && event.actorId !== actorId) return false;
    if (eventType && normalized(event.type) !== eventType) return false;
    if (origin && normalized(event.origin) !== origin) return false;
    if (result && normalized(event.result) !== result) return false;
    if (filters.from && timestamp(event.occurredAt) < from) return false;
    if (filters.to && timestamp(event.occurredAt) > to) return false;
    if (entityType && entityId) {
      const matchesPrincipal =
        normalized(event.principalEntity.type) === entityType &&
        event.principalEntity.id === entityId;
      const matchesRelated = event.relatedEntities.some((entity) =>
        normalized(entity.type) === entityType && entity.id === entityId
      );
      if (!matchesPrincipal && !matchesRelated) return false;
    }
    return true;
  });
}

function memoryEventFromDevice(
  event: OperationalEventRecord,
): MemoryEvent {
  const actorName = text(event.responsibleUserName);
  return {
    id: event.id,
    commitSeq: null,
    sourceKind: "DEVICE",
    type: event.type,
    source: "Este dispositivo",
    actorId: text(event.responsibleUserId),
    actorName,
    actorLabel: actorName ?? "Processo do sistema",
    obraId: text(event.obraId),
    rdoId: text(event.rdoId),
    colaboradorId: text(event.colaboradorId),
    principalEntity: entityRef(event.principalEntity),
    relatedEntities: event.relatedEntities.map(entityRef),
    occurredAt: text(event.occurredAt),
    syncedAt: text(event.syncedAt),
    origin: text(event.origin),
    syncStatus: text(event.syncStatus),
    schemaVersion: event.schemaVersion,
    deviceId: text(event.deviceId),
    correlationId: text(event.correlationId),
    causationId: text(event.causationId),
    previousState: optionalObject(event.previousState) ??
      nestedObject(event.payload, "previousState"),
    newState: optionalObject(event.newState) ??
      nestedObject(event.payload, "newState"),
    payload: event.payload,
    result: text(event.result) ?? (
      event.syncStatus === "SYNC_FAILED" ? "FALHA" : null
    ),
    errorCategory: text(event.errorCategory),
  };
}

function entityRef(entity: {
  tipo: string;
  id: string;
  nome?: string | null;
}): MemoryEntityRef {
  return {
    type: entity.tipo,
    id: entity.id,
    name: text(entity.nome),
  };
}

function nestedObject(
  payload: Record<string, unknown>,
  field: string,
): Record<string, unknown> {
  const value = payload[field];
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return {};
}

function optionalObject(
  value: unknown,
): Record<string, unknown> | null {
  if (value && typeof value === "object" && !Array.isArray(value)) {
    return value as Record<string, unknown>;
  }
  return null;
}

function storedFieldConflicts(
  value: Record<string, unknown> | null,
): MemoryFieldConflicts {
  const stored = optionalObject(value);
  if (!stored) {
    return {};
  }

  const conflicts: MemoryFieldConflicts = {};
  for (const field of Object.keys(stored).sort()) {
    const values = optionalObject(stored[field]);
    if (!values) {
      continue;
    }
    const conflict: MemoryFieldConflictValues = {};
    for (const key of ["base", "local", "remote"] as const) {
      if (Object.prototype.hasOwnProperty.call(values, key)) {
        conflict[key] = values[key];
      }
    }
    if (Object.keys(conflict).length > 0) {
      conflicts[field] = conflict;
    }
  }
  return conflicts;
}

function compareConflictReviews(
  left: MemoryConflictReviewRecord,
  right: MemoryConflictReviewRecord,
): number {
  const timeDifference = timestamp(right.updatedAt) - timestamp(left.updatedAt);
  return timeDifference || left.eventId.localeCompare(right.eventId);
}

function compareEvents(left: MemoryEvent, right: MemoryEvent): number {
  const timeDifference = timestamp(right.occurredAt) - timestamp(left.occurredAt);
  if (timeDifference !== 0) {
    return timeDifference;
  }
  if (left.commitSeq !== null && right.commitSeq !== null) {
    return right.commitSeq - left.commitSeq;
  }
  if (left.sourceKind !== right.sourceKind) {
    return left.sourceKind === "DEVICE" ? -1 : 1;
  }
  return left.id.localeCompare(right.id);
}

function timestamp(value: string | null): number {
  const parsed = value ? Date.parse(value) : 0;
  return Number.isFinite(parsed) ? parsed : 0;
}

function text(value: string | null | undefined): string | null {
  return value?.trim() || null;
}

function normalized(value: string | null | undefined): string | null {
  return text(value)?.toLocaleUpperCase("pt-BR") ?? null;
}

function sameValue(left: unknown, right: unknown): boolean {
  if (Object.is(left, right)) {
    return true;
  }
  try {
    return JSON.stringify(left) === JSON.stringify(right);
  } catch {
    return false;
  }
}
