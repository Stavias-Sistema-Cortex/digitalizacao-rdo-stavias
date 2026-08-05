export type LocalSyncStatus =
  | "LOCAL_ONLY"
  | "LOCAL_PENDING"
  | "PENDING_SYNC"
  | "SYNCING"
  | "SYNCED"
  | "ERROR"
  | "CONFLICT";

export type OperationalEventType =
  | "RDO_CRIADO"
  | "RDO_EDITADO"
  | "RDO_CANCELADO"
  | "RDO_RESTAURADO"
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
  | "TAREFA_ATUALIZADA"
  | "TAREFA_CONCLUIDA"
  | "TAREFA_REABERTA"
  | "TAREFA_EXCLUIDA"
  | "EQUIPE_CRIADA"
  | "EQUIPE_ATUALIZADA"
  | "EQUIPE_ARQUIVADA"
  | "EQUIPE_DESARQUIVADA"
  | "EQUIPE_VINCULO_ALTERADO"
  | "VINCULO_OBRA_ATRIBUIDO"
  | "VINCULO_OBRA_REVOGADO"
  | "SOLICITACAO_INTEGRACAO_CRIADA"
  | "COMPRA_CRIADA"
  | "SERVICE_CREATED"
  | "SERVICE_PRICE_VERSION_PUBLISHED"
  | "SERVICE_PRICE_VERSION_SUPERSEDED"
  | "SERVICE_PRICE_VERSION_CANCELLED"
  | "OBRA_ATUALIZADA"
  | "OBRA_DESATIVADA"
  | "OBRA_ATIVADA"
  | "OBRA_ARQUIVADA"
  | "OBRA_RESTAURADA"
  | "GEOMETRIA_CRIADA"
  | "GEOMETRIA_ATUALIZADA"
  | "GEOMETRIA_ENCERRADA";

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
  /*
   * A pessoa abriu mão da própria edição para ficar com a do servidor. É
   * diferente de REJECTED, onde quem recusou foi o servidor: aqui houve uma
   * decisão humana, e o histórico precisa distinguir as duas — o evento
   * permanece na Memória em vez de sumir junto com a mutação.
   */
  | "DISCARDED"
  /*
   * A intenção desta escrita foi absorvida por um envelope posterior da mesma
   * entidade — o caso comum é o salvamento automático do RDO, que reescreve o
   * apontamento a cada campo preenchido. Nada falhou e o servidor pode nunca
   * ter visto a original: chamar isso de recusa é acusar de erro o
   * funcionamento normal do app em campo.
   */
  | "SUPERSEDED"
  | "REJECTED";

export interface CanonicalMutationEnvelopeV13 {
  readonly schemaVersion: 13;
  readonly clientMutationId: string;
  readonly deviceId: string;
  readonly userId: string;
  readonly obraId: string | null;
  readonly entityType: string;
  readonly entityId: string;
  readonly operation: CanonicalMutationOperation;
  readonly baseVersion: number | null;
  readonly changedFields: readonly string[];
  readonly occurredAt: string;
  readonly payload: Readonly<Record<string, unknown>>;
}

export interface MutationFieldPatch {
  changed: Record<string, unknown>;
  baseValues: Record<string, unknown>;
}

export interface MutationTrace {
  readonly actorId: string;
  readonly deviceId: string;
  readonly authorizationScope: readonly string[];
  readonly correlationId: string;
  readonly causationId: string | null;
  readonly ontologyEventId: string;
  readonly payloadHash: string;
}

export type SyncEntityType =
  | "RDO"
  | "TAREFA"
  | "CONVERSA"
  | "MENSAGEM"
  | "MENSAGEM_ANEXO"
  | "SOLICITACAO_COMPRA"
  | "COMPRA"
  | "SERVICE"
  | "SERVICE_PRICE_VERSION"
  | "EQUIPE"
  | "OBRA"
  | "VINCULO_OBRA"
  | "GEOMETRIA_OBRA"
  | "SOLICITACAO_INTEGRACAO";

export type SyncOperation =
  | "CRIAR_RDO"
  | "ATUALIZAR_RDO_RASCUNHO"
  | "ENVIAR_RDO"
  | "CANCELAR_RDO"
  | "RESTAURAR_RDO"
  | "CRIAR_TAREFA"
  | "ATUALIZAR_TAREFA"
  | "CONCLUIR_TAREFA"
  | "REABRIR_TAREFA"
  | "EXCLUIR_TAREFA"
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
  | "ARQUIVAR_COMPRA"
  | "CRIAR_SERVICO_CATALOGO"
  | "CRIAR_PRECO_SERVICO"
  | "SUBSTITUIR_PRECO_SERVICO"
  | "CANCELAR_PRECO_SERVICO"
  | "CRIAR_EQUIPE"
  | "ATUALIZAR_EQUIPE"
  | "ARQUIVAR_EQUIPE"
  | "DESARQUIVAR_EQUIPE"
  | "ALTERAR_VINCULO_EQUIPE"
  | "ATUALIZAR_OBRA"
  | "DESATIVAR_OBRA"
  | "ATIVAR_OBRA"
  | "ARQUIVAR_OBRA"
  | "RESTAURAR_OBRA"
  | "VINCULAR_COLABORADOR_OBRA"
  | "REVOGAR_VINCULO_COLABORADOR_OBRA"
  | "REGISTRAR_GEOMETRIA_OBRA"
  | "REGISTRAR_GEOMETRIA_CAMPO"
  | "ATUALIZAR_GEOMETRIA_OBRA"
  | "ENCERRAR_GEOMETRIA_OBRA"
  | "SOLICITAR_INTEGRACAO";

export type OutboxTransport =
  | "SYNC_PUSH"
  | "OBJECT_UPLOAD";

export interface LocalRdoRecord {
  id: string;
  obraId: string;
  programacaoId: string | null;
  numeroRdo: string;
  dataRdo: string;
  statusRdo: "RASCUNHO" | "ENVIADO" | "CANCELADA";
  syncStatus: LocalSyncStatus;
  versaoEntidade: number | null;
  payload: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
  /**
   * Apagamento reversível, espelhando `rdo.cancelado_em` no servidor. Ausente
   * nos registros gravados antes desta capacidade existir: ler como não
   * apagado é a leitura verdadeira deles.
   */
  canceladoEm?: string | null;
}

export interface ServiceCatalogLocalRecord {
  id: string;
  code: string;
  name: string;
  description: string | null;
  status: string;
  syncStatus: LocalSyncStatus;
  createdAt: string;
  updatedAt: string;
  lastError: string | null;
}

export interface ServicePriceVersionLocalRecord {
  id: string;
  obraId: string;
  serviceId: string;
  unit: string;
  currency: string;
  version: number;
  unitPrice: string;
  contractedQuantity: string | null;
  validFrom: string;
  validTo: string | null;
  source: string | null;
  supersedesId: string | null;
  status: string;
  effectiveValidTo: string | null;
  entityVersion: number;
  syncStatus: LocalSyncStatus;
  createdAt: string;
  updatedAt: string;
  lastError: string | null;
}

export interface FinanceCapabilitiesCacheRecord {
  key: [string, string];
  ownerId: string;
  obraId: string;
  permissions: string[];
  cachedAt: string;
  sessionExpiresAt: string;
}

export interface FinanceRevenueTraceCacheRecord {
  key: [string, string, string, string, string];
  ownerId: string;
  scopeMaterial: string;
  obraId: string;
  fromFilter: string;
  toFilter: string;
  fetchedAt: string;
  expiresAt: string;
  source: "SERVER_CONFIRMED";
  coverage: {
    status: "COMPLETE_ACCEPTED_EXACT";
    from: string;
    to: string;
    evidenceCount: number;
  };
  response: unknown;
}

/**
 * Uma camada geoespacial da obra no dispositivo.
 *
 * Guarda tanto o que o servidor confirmou quanto o que foi desenhado ou
 * capturado em campo e ainda não subiu. `syncStatus` distingue os dois casos, e
 * `fetchedAt` registra quando o servidor confirmou aquela versão, para que a
 * interface consiga rotular a idade do dado offline em vez de apresentá-lo como
 * atual.
 */
export interface ObraGeometriaLocalRecord {
  id: string;
  ownerId: string;
  obraId: string;
  categoria: string;
  objetoTipo: string | null;
  objetoId: string | null;
  geometry: unknown;
  properties: Record<string, unknown>;
  fonte: string;
  status: "ATIVA" | "ENCERRADA";
  validoDesde: string;
  validoAte: string | null;
  versao: number;
  syncStatus: LocalSyncStatus;
  fetchedAt: string | null;
  updatedAt: string;
}

/** Cache local da projeção linear do trecho, com a mesma disciplina de idade. */
export interface ObraTrechoCacheRecord {
  key: [string, string];
  ownerId: string;
  obraId: string;
  fetchedAt: string;
  payload: unknown;
}

export interface FinancePdorRevenueCacheRecord {
  key: [string, string, string];
  ownerId: string;
  scopeMaterial: string;
  obraId: string;
  fetchedAt: string;
  expiresAt: string;
  sessionExpiresAt: string;
  source: "SERVER_CONFIRMED";
  payloadHash: string;
  provenance: {
    snapshotId: string | null;
    worksiteId: string;
    referenceDate: string | null;
    temporalWindow: {
      inicioProgramacao: string | null;
      fimProgramacao: string | null;
      dataReferencia: string | null;
      janelaEquipamentosDias: number | null;
      serieHistoricaSemanal: boolean | null;
    } | null;
    evidenceHighWaterMark: number | null;
    coverageCode:
      | "COMPLETE_ACCEPTED_EXACT"
      | "PARTIAL_ACCEPTED_EXACT"
      | "NO_ACCEPTED_EVIDENCE"
      | "NO_CURRENT_SNAPSHOT";
    evidenceCount: number;
    algorithmVersion: string | null;
    executedAtUtc: string | null;
  };
  response: unknown;
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
  readonly clientMutationId: string;
  entidadeTipo: SyncEntityType;
  entidadeId: string;
  operacao: SyncOperation;
  baseVersao: number | null;
  readonly payload: Readonly<Record<string, unknown>>;
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
  retryAttempt?: number;
  lastSafeCode?: string | null;
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
  readonly correlationId: string;
  readonly causationId: string | null;
  readonly fieldPatch: MutationFieldPatch;
  readonly relatedEntities: readonly OperationalEntityRef[];
  readonly trace: MutationTrace;
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

export interface SyncExecutionLeaseRecord {
  ownerToken: string;
  acquiredAt: string;
  heartbeatAt: string;
  expiresAt: string;
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
  syncExecutionLease: SyncExecutionLeaseRecord | null;
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
  serverCommitSequence?: number | null;
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
  codigoInterno?: string | null;
  nome: string;
  cliente: string | null;
  descricao?: string | null;
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
  fonteArquivo?: string | null;
  status: string;
  observacoes: string | null;
  latitude: number | null;
  longitude: number | null;
  valorContratual: number | null;
  versaoEntidade?: number | null;
  arquivadoEm?: string | null;
  syncStatus?: LocalSyncStatus;
  ultimoErro?: string | null;
  updatedAt: string;
}

export interface RdoCreationContextCacheRecord {
  ownerId: string;
  obraId: string;
  selectedDate: string;
  sourceVersion: number;
  receiptVersion: number;
  cachedAt: string;
  coverage: Record<string, unknown>;
  context: Record<string, unknown>;
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
  versaoEntidade: number | null;
  syncStatus: LocalSyncStatus;
  deletadaEm: string | null;
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
  syncStatus?: "PENDING_SYNC" | "SYNCED" | "CONFLICT" | "REJECTED";
  ultimoErro?: string | null;
  pendingMutationId?: string | null;
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

export interface MemorySearchDocumentRecord {
  key: string;
  userId: string;
  scopeHash: string;
  eventId: string;
  commitSequence: number | null;
  normalizedText: string;
  structuralKeys: {
    eventType: string;
    entityType: string;
    entityId: string | null;
    worksiteId: string | null;
    rdoId: string | null;
    actorId: string | null;
    deviceId: string | null;
    origin: string | null;
    result: string | null;
  };
  syncStatus:
    | "UPDATED"
    | "LOCAL_PENDING"
    | "SYNCING"
    | "CONFLICT"
    | "DISCARDED"
    | "SUPERSEDED"
    | "REJECTED";
  sourceKind: "SERVER" | "LOCAL";
  occurredAt: string;
  eventType: string;
  source: string | null;
  principalName: string | null;
  worksiteName: string | null;
  rdoNumber: string | null;
  serviceName: string | null;
  errorCategory: string | null;
  trace: {
    clientMutationId: string | null;
    correlationId: string | null;
    causationId: string | null;
    entityVersion: number | null;
  };
  review: {
    status: "CONFLICT" | "REJECTED";
    clientMutationId: string | null;
    baseVersion: number | null;
    eventVersion: number | null;
    remoteVersion: number | null;
    localStateAvailable: boolean;
    remoteStateAvailable: boolean;
    changedFields: string[];
    conflictFields: string[];
    canReconcile: boolean;
    unavailableReason:
      | "REJECTED"
      | "LOCAL_EVIDENCE_UNAVAILABLE"
      | "REMOTE_SNAPSHOT_UNAVAILABLE"
      | "UNSUPPORTED_ENTITY"
      | "CREATE_CONFLICT_REQUIRES_REVIEW"
      | "REMOTE_SNAPSHOT_MISMATCH"
      | "FIELD_CONFLICT"
      | null;
  } | null;
}

export interface MemoryCacheMetadataRecord {
  key: string;
  userId: string;
  scopeHash: string;
  highWaterMark: number;
  authorizedEventCount: number;
  cachedEventCount: number;
  oldestCommitSequence: number | null;
  newestCommitSequence: number;
  coverageMode: string;
  serverCoverageComplete: boolean;
  graph?: {
    checkpointCommitSequence: number;
    targetCommitSequence: number;
    lagEventCount: number;
    fresh: boolean;
    lastSafeError: string | null;
  };
  complete: boolean;
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
