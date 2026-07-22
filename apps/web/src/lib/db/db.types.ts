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
  | "CONFLICT"
  | "REJECTED";

export type CanonicalMutationOperation =
  | "CREATE"
  | "UPDATE"
  | "DELETE"
  | "TRANSITION";

export type CanonicalMutationResult =
  | "LOCAL"
  | "PENDING"
  | "SYNCING"
  | "SYNCED"
  | "CONFLICT"
  | "REJECTED";

export interface CanonicalMutationEnvelopeV13 {
  schemaVersion: 13;
  clientMutationId: string;
  deviceId: string;
  userId: string;
  obraId: string;
  entityType: string;
  entityId: string;
  operation: CanonicalMutationOperation;
  baseVersion: number | null;
  changedFields: string[];
  occurredAt: string;
  payload: Record<string, unknown>;
}

export interface MutationFieldPatch {
  changed: Record<string, unknown>;
  baseValues: Record<string, unknown>;
}

export interface MutationTrace {
  actorId: string;
  deviceId: string;
  authorizationScope: string[];
  correlationId: string;
  causationId: string | null;
  ontologyEventId: string;
  payloadHash: string;
}

export type SyncEntityType =
  | "RDO"
  | "CONVERSA"
  | "MENSAGEM"
  | "MENSAGEM_ANEXO"
  | "SOLICITACAO_COMPRA"
  | "COMPRA";

export type SyncOperation =
  | "CRIAR_RDO"
  | "ATUALIZAR_RDO_RASCUNHO"
  | "ENVIAR_RDO"
  | "CRIAR_CONVERSA"
  | "ADICIONAR_PARTICIPANTE_CONVERSA"
  | "REMOVER_PARTICIPANTE_CONVERSA"
  | "CRIAR_MENSAGEM"
  | "EDITAR_MENSAGEM"
  | "EXCLUIR_MENSAGEM"
  | "ADICIONAR_MENSAGEM_ANEXO"
  | "CRIAR_SOLICITACAO_COMPRA"
  | "ATUALIZAR_SOLICITACAO_COMPRA"
  | "ARQUIVAR_SOLICITACAO_COMPRA"
  | "CRIAR_COMPRA"
  | "ATUALIZAR_COMPRA"
  | "ALTERAR_STATUS_COMPRA"
  | "DECIDIR_APROVACAO_COMPRA"
  | "ARQUIVAR_COMPRA";

export type OutboxTransport =
  | "SYNC_PUSH"
  | "OBJECT_UPLOAD";

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

interface OutboxMutationRecordBase {
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
  transport?: OutboxTransport;
  dependsOnMutationIds?: string[];
  correlationId?: string;
}

export interface LegacyOutboxMutationRecord
  extends OutboxMutationRecordBase {
  schemaVersion?: undefined;
  nextAttemptAt?: string | null;
  blockedReason?: string | null;
}

export interface CanonicalOutboxMutationRecord
  extends OutboxMutationRecordBase,
    CanonicalMutationEnvelopeV13 {
  correlationId: string;
  causationId: string | null;
  fieldPatch: MutationFieldPatch;
  trace: MutationTrace;
  nextAttemptAt: string | null;
  blockedReason: string | null;
}

/** Legacy reads remain supported; every new coordinated write is canonical v13. */
export type OutboxMutationRecord =
  | LegacyOutboxMutationRecord
  | CanonicalOutboxMutationRecord;

export type MensagemSyncStatus =
  | "LOCAL"
  | "NA_FILA"
  | "SINCRONIZANDO"
  | "SINCRONIZADO"
  | "FALHOU";

export type ConversaTipo =
  | "DIRETA"
  | "GRUPO"
  | "EQUIPE"
  | "OBRA";

export interface ConversaParticipanteLocal {
  colaboradorId: string;
  nome: string;
  papel: "ADMIN" | "MEMBRO";
  status: "ATIVO" | "REMOVIDO";
  adicionadoEm: string;
}

export interface ConversaLocalRecord {
  id: string;
  tipo: ConversaTipo;
  titulo: string | null;
  obraId: string | null;
  equipeId: string | null;
  status: string;
  participantes: ConversaParticipanteLocal[];
  criadaEm: string;
  atualizadaEm: string;
  versaoEntidade: number | null;
}

export interface MensagemLocalRecord {
  id: string;
  conversaId: string;
  autorId: string;
  autorNome: string;
  corpo: string | null;
  status: "ATIVA" | "EDITADA" | "EXCLUIDA";
  clientMutationId: string;
  criadaNoClienteEm: string;
  criadaEm: string | null;
  editadaEm: string | null;
  deletadaEm: string | null;
  versaoEntidade: number | null;
  syncStatus: MensagemSyncStatus;
  ultimoErro: string | null;
  updatedAt: string;
}

export interface MensagemAnexoLocalRecord {
  id: string;
  mensagemId: string;
  conversaId: string;
  objetoId: string | null;
  uploadMutationId: string | null;
  nome: string;
  mediaType: string;
  tamanhoBytes: number;
  sha256: string | null;
  ordem: number;
  arquivo: Blob | null;
  syncStatus: MensagemSyncStatus;
  ultimoErro: string | null;
  createdAt: string;
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
  clientMutationId?: string;
  deviceId?: string;
  correlationId?: string;
  causationId?: string | null;
  previousState?: Record<string, unknown>;
  newState?: Record<string, unknown>;
  result?: CanonicalMutationResult;
  errorCategory?: string | null;
  entityVersion?: number | null;
}

export interface CanonicalOperationalEventRecord
  extends OperationalEventRecord {
  schemaVersion: 13;
  clientMutationId: string;
  deviceId: string;
  correlationId: string;
  causationId: string | null;
  previousState: Record<string, unknown>;
  newState: Record<string, unknown>;
  result: CanonicalMutationResult;
  errorCategory: string | null;
  entityVersion: number | null;
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

export interface LocalTeamMemberRecord {
  id: string;
  equipeId: string;
  colaboradorId: string;
  colaboradorNome: string;
  papelAcesso: "ALFA" | "BETA" | null;
  funcaoOperacionalId: string;
  funcaoCodigo: string;
  funcaoNome: string;
  responsavel: boolean;
  status: "ATIVO" | "ENCERRADO";
  inicioEm: string;
  fimEm: string | null;
  motivoEncerramento: string | null;
  versaoEntidade: number;
  criadoEm: string;
  atualizadoEm: string;
}

export interface LocalTeamRecord {
  id: string;
  obraPrincipalId: string;
  obraNome: string;
  nome: string;
  descricao: string | null;
  status: "ATIVA" | "ARQUIVADA";
  inicioValidadeEm: string;
  fimValidadeEm: string | null;
  versaoEntidade: number;
  criadoEm: string;
  atualizadoEm: string;
  membros: LocalTeamMemberRecord[];
}

export interface LocalOperationalRoleRecord {
  id: string;
  codigo: string;
  nome: string;
  descricao: string | null;
  ativo: boolean;
  ordemExibicao: number;
  versaoEntidade: number;
  criadoEm: string;
  atualizadoEm: string;
}

export interface LocalTeamHistoryRecord {
  commitSeq: number;
  eventId: string;
  entityType: string;
  entityId: string;
  eventType: string;
  source: string;
  worksiteId: string | null;
  collaboratorId: string | null;
  occurredAt: string;
  recordedAt: string;
  entityVersion: number;
  payload: Record<string, unknown>;
}

export interface LocalTeamWorksiteRecord {
  id: string;
  equipeId: string;
  obraId: string;
  obraNome: string;
  status: "ATIVO" | "ENCERRADO";
  inicioEm: string;
  fimEm: string | null;
  motivoEncerramento: string | null;
  versaoEntidade: number;
  criadoEm: string;
  atualizadoEm: string;
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
