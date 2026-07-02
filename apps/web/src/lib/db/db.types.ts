export type LocalSyncStatus =
  | "LOCAL_ONLY"
  | "PENDING_SYNC"
  | "SYNCING"
  | "SYNCED"
  | "ERROR"
  | "CONFLICT";

export type OutboxMutationStatus =
  | "PENDING"
  | "SYNCING"
  | "SYNCED"
  | "ERROR"
  | "CONFLICT";

export type SyncEntityType = "RDO";

export type SyncOperation =
  | "CRIAR_RDO"
  | "ATUALIZAR_RDO_RASCUNHO"
  | "ENVIAR_RDO";

export interface LocalRdoRecord {
  id: string;
  obraId: string;
  programacaoId: string | null;
  numeroRdo: string;
  dataRdo: string;
  statusRdo: "RASCUNHO" | "ENVIADO";
  syncStatus: LocalSyncStatus;
  versaoEntidade: number | null;
  payload: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface LocalRdoChildRecord {
  id: string;
  rdoId: string;
  localId: string;
  syncStatus: LocalSyncStatus;
  payload: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export type LocalRdoMaoObraRecord = LocalRdoChildRecord;

export type LocalRdoEquipamentoRecord = LocalRdoChildRecord;

export type LocalRdoMaterialRecord = LocalRdoChildRecord;

export type LocalRdoControleGeometricoRecord =
  LocalRdoChildRecord;

export interface OutboxMutationRecord {
  clientMutationId: string;
  entidadeTipo: SyncEntityType;
  entidadeId: string;
  operacao: SyncOperation;
  baseVersao: number | null;
  payload: Record<string, unknown>;
  status: OutboxMutationStatus;
  tentativas: number;
  ultimaTentativaEm: string | null;
  ultimoErro: string | null;
  conflito: Record<string, unknown> | null;
  criadaNoClienteEm: string;
  updatedAt: string;
}

export interface SyncStateRecord {
  key: "default";
  deviceId: string | null;
  usuarioId: string | null;
  lastPulledCommitSeq: number;
  lastAckedCommitSeq: number;
  isSyncing: boolean;
  lastSyncStartedAt: string | null;
  lastSyncCompletedAt: string | null;
  lastSyncError: string | null;
}

export interface ProcessedEventRecord {
  commitSeq: number;
  eventoId: string;
  tipoEvento: string;
  entidadeTipo: string;
  entidadeId: string;
  aplicadoEm: string;
}
export type AttachmentType = "FOTO" | "VIDEO";

export type AttachmentSyncStatus =
  | "LOCAL_ONLY"
  | "PENDING_UPLOAD"
  | "UPLOADING"
  | "UPLOADED"
  | "ERROR";

export interface RdoAttachmentRecord {
  id: string;
  rdoId: string;

  tipo: AttachmentType;

  nome: string;
  mimeType: string;
  tamanhoBytes: number;

  arquivo: Blob;

  syncStatus: AttachmentSyncStatus;
  ultimoErro: string | null;

  createdAt: string;
  updatedAt: string;
}

export interface StaviaSnapshotRecord {
  key: "default";
  snapshot: import("../../features/stavia/stavia.types").StaviaSnapshot;
  localSyncedAt: string;
  updatedAt: string;
}
