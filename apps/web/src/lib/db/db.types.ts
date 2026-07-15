export type LocalSyncStatus =
  | "LOCAL_ONLY"
  | "PENDING_SYNC"
  | "SYNCING"
  | "SYNCED"
  | "ERROR"
  | "CONFLICT";

export type OperationalEventType =
  | "RDO_CRIADO"
  | "RDO_EDITADO"
  | "RDO_SALVO_OFFLINE"
  | "RDO_SINCRONIZADO"
  | "RDO_FALHA_SYNC"
  | "FOTO_ADICIONADA"
  | "FOTO_COMPRIMIDA"
  | "FOTO_REMOVIDA"
  | "MEDICAO_TRECHO_ATUALIZADA"
  | "COLABORADOR_ASSOCIADO_RDO"
  | "EQUIPAMENTO_ASSOCIADO_RDO"
  | "OCORRENCIA_REGISTRADA"
  | "CALCULO_REPROCESSADO"
  | "ENTIDADE_RELACIONADA"
  | "ENTIDADE_DESRELACIONADA"
  | "TAREFA_CRIADA"
  | "TAREFA_CONCLUIDA"
  | "TAREFA_REABERTA"
  | "TAREFA_EXCLUIDA";

export type OperationalEventOrigin =
  | "ONLINE"
  | "OFFLINE"
  | "SYNC";

export type OperationalEventSyncStatus =
  | "LOCAL_ONLY"
  | "PENDING_SYNC"
  | "SYNCING"
  | "SYNCED"
  | "SYNC_FAILED";

export interface OperationalEntityRef {
  tipo: string;
  id: string;
  nome?: string | null;
}

export type OutboxMutationStatus =
  | "PENDING"
  | "SYNCING"
  | "SYNCED"
  | "ERROR"
  | "CONFLICT";

export type SyncEntityType = "RDO" | "MENSAGEM";

export type SyncOperation =
  | "CRIAR_RDO"
  | "ATUALIZAR_RDO_RASCUNHO"
  | "ENVIAR_RDO"
  | "CRIAR_MENSAGEM";

export type ConversationType =
  | "OBRA"
  | "EQUIPE"
  | "DIRETA"
  | "GRUPO";

export interface LocalConversationRecord {
  id: string;
  tipo: ConversationType;
  titulo: string | null;
  obraId: string | null;
  equipeId: string | null;
  criadoPor: string | null;
  status: "ATIVA" | "ARQUIVADA";
  ultimaAtividadeEm: string;
  criadoEm: string | null;
  atualizadoEm: string;
  versaoEntidade: number | null;
}

export interface LocalConversationParticipantRecord {
  id: string;
  conversaId: string;
  colaboradorId: string;
  colaboradorNome: string | null;
  papel: "ADMINISTRADOR" | "MEMBRO";
  status: "ATIVO" | "SAIU" | "REMOVIDO";
  entrouEm: string;
  saiuEm: string | null;
  ultimaLeituraEm: string | null;
  versaoEntidade: number | null;
  atualizadoEm: string;
}

export type LocalMessageSyncStatus =
  | "PENDING"
  | "SYNCING"
  | "SENT"
  | "ERROR";

export interface LocalMessageReceipt {
  id: string;
  mensagemId: string;
  colaboradorId: string;
  entregueEm: string | null;
  lidaEm: string | null;
}

export interface LocalMessageRecord {
  id: string;
  conversaId: string;
  remetenteId: string | null;
  remetenteNome: string | null;
  clientMessageId: string;
  texto: string | null;
  estado: string;
  enviadaClienteEm: string;
  criadaServidorEm: string | null;
  atualizadoEm: string;
  versaoEntidade: number | null;
  recibos: LocalMessageReceipt[];
  syncStatus: LocalMessageSyncStatus;
  ultimoErro: string | null;
}

export interface LocalMessageReferenceRecord {
  id: string;
  mensagemId: string;
  tipoObjeto: string;
  objetoId: string;
  obraId: string | null;
  criadoEm: string | null;
}

export type LocalMessageAttachmentSyncStatus =
  | "WAITING_MESSAGE"
  | "PENDING_UPLOAD"
  | "UPLOADING"
  | "UPLOADED"
  | "ERROR";

export interface LocalMessageAttachmentRecord {
  id: string;
  mensagemId: string;
  clientAttachmentId: string;
  nomeOriginal: string;
  nomeSeguro: string;
  mimeType: string;
  tamanhoBytes: number;
  hashSha256: string;
  status: "PENDENTE" | "DISPONIVEL" | "FALHOU";
  ultimoErro: string | null;
  criadoEm: string | null;
  atualizadoEm: string;
  disponivelEm: string | null;
  versaoEntidade: number | null;
  arquivo: Blob | null;
  syncStatus: LocalMessageAttachmentSyncStatus;
}

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

export interface OperationalEventRecord {
  id: string;
  type: OperationalEventType;
  principalEntity: OperationalEntityRef;
  principalEntityKey: string;
  relatedEntities: OperationalEntityRef[];
  obraId: string | null;
  rdoId: string | null;
  colaboradorId: string | null;
  occurredAt: string;
  syncedAt: string | null;
  origin: OperationalEventOrigin;
  responsibleUserId: string | null;
  responsibleUserName: string | null;
  payload: Record<string, unknown>;
  syncStatus: OperationalEventSyncStatus;
  schemaVersion: number;
}

export type AttachmentType = "FOTO" | "VIDEO";

export type AttachmentSyncStatus =
  | "LOCAL_ONLY"
  | "PENDING_SYNC"
  | "SYNCING"
  | "SYNCED"
  | "SYNC_FAILED";

export interface RdoAttachmentRecord {
  id: string;
  rdoId: string;
  obraId: string | null;

  tipo: AttachmentType;

  nome: string;
  nomeOriginal: string | null;
  mimeType: string;
  tamanhoBytes: number;
  tamanhoOriginalBytes: number;
  tamanhoComprimidoBytes: number;

  arquivo: Blob;

  syncStatus: AttachmentSyncStatus;
  ultimoErro: string | null;
  metadata: Record<string, unknown>;

  createdAt: string;
  updatedAt: string;
  removedAt: string | null;
}

export interface StaviaSnapshotRecord {
  key: "default";
  snapshot: import("../../features/stavia/stavia.types").StaviaSnapshot;
  localSyncedAt: string;
  updatedAt: string;
}

export interface ObraLocalRecord {
  id: string;
  codigoContrato: string;
  nome: string;
  cliente: string | null;
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
  status: string;
  observacoes: string | null;
  latitude: number | null;
  longitude: number | null;
  valorContratual: number | null;
  updatedAt: string;
}

export type TarefaPrioridade = 1 | 2 | 3;

export interface TarefaRecord {
  id: string;
  obraId: string;
  equipe: string;
  titulo: string;
  observacoes: string;
  criadaPor: string;
  criadaPorColaboradorId: string | null;
  responsavelEquipe: string;
  responsavelColaboradorId: string | null;
  prioridade: TarefaPrioridade;
  concluida: boolean;
  concluidaEm: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ColaboradorLocalRecord {
  id: string;
  nome: string;
  cpfMascarado: string | null;
  nomePerfil: string | null;
  ativo: boolean;
  updatedAt: string | null;
  cachedAt: string;
}

export interface PrevisaoSnapshotRecord {
  id: string;
  obraId: string;
  dataReferencia: string;
  statusExecucao: string;
  producaoPlanejada: number | null;
  producaoRealizada: number | null;
  custoRealizado: number | null;
  custoPrevistoFinal: number | null;
  receitaPrevistaFinal: number | null;
  updatedAt: string;
}
