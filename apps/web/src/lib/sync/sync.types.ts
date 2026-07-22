import type {
  MutationFieldPatch,
  OutboxMutationRecord,
  SyncEntityType,
  SyncOperation,
} from "../db/db.types";
import {
  isCanonicalOutboxMutation,
  LEGACY_TRACE_REVIEW_REASON,
} from "../db/outboxContract";

/** @deprecated Use LEGACY_TRACE_REVIEW_REASON from outboxContract. */
export const NONCANONICAL_MUTATION_BLOCKED_REASON =
  LEGACY_TRACE_REVIEW_REASON;

/** @deprecated Use isCanonicalOutboxMutation from outboxContract. */
export const isCanonicalPushMutation = isCanonicalOutboxMutation;

export interface RegisterDeviceRequest {
  id: string;
  nome: string;
  tipo: "WEB";
  usuarioId: string | null;
}

export interface RegisterDeviceResponse {
  id: string;
  nome?: string;
  tipo?: string;
  ativo?: boolean;
}

export interface SyncPushMutationRequest {
  clientMutationId: string;
  entidadeTipo: SyncEntityType;
  entidadeId: string;
  operacao: SyncOperation;
  baseVersao: number | null;
  payload: Record<string, unknown>;
  criadaNoClienteEm: string;
  correlacaoId: string;
  fieldPatch: MutationFieldPatch;
  actorId: string;
  authorizationScope: string[];
  ontologyEventId: string;
  payloadHash: string;
  causationId: string | null;
  dependsOnMutationIds: string[];
}

export interface SyncPushRequest {
  dispositivoId: string;
  mutacoes: SyncPushMutationRequest[];
}

export type ServerMutationStatus =
  | "APLICADA"
  | "CONCILIADA"
  | "DESCARTADA"
  | "REJEITADA"
  | "ERRO";

export interface SyncMutationEntityResult
  extends Record<string, unknown> {
  id?: string;
  versaoEntidade?: number;
  
}export interface SyncPushMutationResult {
  clientMutationId: string;
  status: ServerMutationStatus;
  entidadeTipo?: string;
  entidadeId?: string;
  eventoServidorCommitSeq?: number | null;
  resultado?: SyncMutationEntityResult | null;  conflito?: Record<string, unknown> | null;
  erro?: string | null;
}

export interface SyncPushResponse {
  resultados: SyncPushMutationResult[];
  serverCommitSeq?: number;
  serverTimeUtc?: string;
}

export interface SyncPullEvent {
  commitSeq: number;
  eventoId: string;
  tipoEvento: string;
  entidadeTipo: string;
  entidadeId: string;
  ocorridoEmUtc?: string;
  criadoEmUtc?: string;
  versaoEntidade?: number | null;
  payload?: Record<string, unknown> | null;
}

export interface SyncPullResponse {
  eventos: SyncPullEvent[];
  nextCommitSeq: number;
  hasMore: boolean;
}

export interface SyncAckRequest {
  dispositivoId: string;
  ultimoEventoRecebidoCommitSeq: number;
}

export interface SyncAckResponse {
  dispositivoId?: string;
  ultimoEventoRecebidoCommitSeq?: number;
}

export interface SyncRunSummary {
  deviceId: string;
  pushed: number;
  applied: number;
  errors: number;
  retryableErrors: number;
  conflicts: number;
  pulled: number;
  acknowledgedCommitSeq: number;
}

export function toPushMutationRequest(
  mutation: OutboxMutationRecord,
): SyncPushMutationRequest {
  if (!isCanonicalPushMutation(mutation)) {
    throw new TypeError(
      `Mutacao ${mutation.clientMutationId} bloqueada: envelope canonico v13 incompleto.`,
    );
  }

  return {
    clientMutationId: mutation.clientMutationId,
    entidadeTipo: mutation.entidadeTipo,
    entidadeId: mutation.entidadeId,
    operacao: mutation.operacao,
    baseVersao: mutation.baseVersao,
    payload: mutation.payload,
    criadaNoClienteEm: mutation.criadaNoClienteEm,
    correlacaoId:
      mutation.correlationId,
    fieldPatch: mutation.fieldPatch,
    actorId: mutation.trace.actorId,
    authorizationScope: mutation.trace.authorizationScope,
    ontologyEventId: mutation.trace.ontologyEventId,
    payloadHash: mutation.trace.payloadHash,
    causationId: mutation.trace.causationId,
    dependsOnMutationIds: mutation.dependsOnMutationIds,
  };
}
