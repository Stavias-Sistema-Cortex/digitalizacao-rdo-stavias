export type StaviaConfidence =
  | "ALTA"
  | "MEDIA"
  | "BAIXA"
  | "INDETERMINADA";

export type StaviaAnswerType =
  | "FATO"
  | "RESUMO"
  | "INFORMACAO_INSUFICIENTE"
  | string;

export interface StaviaEvidence {
  type: string;
  id: string;
  summary: string;
  updatedAt: string | null;
  validated: boolean;
  attributes: Record<string, unknown>;
}

export interface StaviaAnswer {
  answer: string;
  confidence: StaviaConfidence;
  answerType: StaviaAnswerType;
  sources: StaviaEvidence[];
  insufficientData: boolean;
  warnings: string[];
  metadata: Record<string, unknown> | null;
}

export interface StaviaConsultaRequest {
  pergunta: string;
  usuarioId: string;
  obraId?: string | null;
  rdoId?: string | null;
  contextoSelecionado?: string | null;
  ultimoObraId?: string | null;
  ultimoRdoId?: string | null;
}

export interface StaviaConsultaResponse {
  answer: StaviaAnswer;
  intent: string;
  consultedKnowledgeSources: Record<string, string>;
  knowledgeWarnings: string[];
}

export interface StaviaContextDocument {
  id: string;
  obraId: string;
  nomeArquivo: string;
  contentType: string | null;
  tamanhoBytes: number;
  hashSha256: string;
  descricao: string | null;
  statusProcessamento: string;
  textoDisponivel: boolean;
  criadoEm: string;
}

export interface StaviaSnapshotMetadata {
  snapshotKey: "default";
  generatedAt: string;
  databaseUpdatedAt: string | null;
  localSyncedAt: string | null;
  source: string;
  status: "COMPLETO" | "PARCIAL" | "LOCAL";
  dictionaryVersion: string;
}

export interface StaviaSnapshotObra {
  id: string;
  codigoContrato: string | null;
  codigoCw: string | null;
  codigoInterno: string | null;
  nome: string | null;
  cliente: string | null;
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
  status: string | null;
  updatedAt: string | null;
}

export interface StaviaSnapshotMaoObra {
  colaboradorId: string | null;
  nomeColaborador: string | null;
  cargo: string | null;
  tipoVinculo: string | null;
  quantidade: number | string | null;
  horaInicio: string | null;
  horaFim: string | null;
  observacoes: string | null;
}

export interface StaviaSnapshotEquipamento {
  assetId: string | null;
  prefixo: string | null;
  descricao: string | null;
  tipoEquipamento: string | null;
  tipoVinculo: string | null;
  quantidade: number | string | null;
  horaInicio: string | null;
  horaFim: string | null;
  observacoes: string | null;
}

export interface StaviaSnapshotMaterial {
  materialNome: string | null;
  unidade: string | null;
  quantidadePrevista: number | string | null;
  quantidadeUsinada: number | string | null;
  quantidadeAplicada: number | string | null;
  quantidadeSobra: number | string | null;
  notaFiscal: string | null;
  fornecedor: string | null;
  observacoes: string | null;
}

export interface StaviaSnapshotControleGeometrico {
  subtrecho: string | null;
  numero: string | null;
  estacaInicial: string | null;
  estacaFinal: string | null;
  kmInicial: string | null;
  kmFinal: string | null;
  pista: string | null;
  faixa: string | null;
  ordemServico: string | null;
  comprimentoM: number | string | null;
  larguraM: number | string | null;
  espessura1Cm: number | string | null;
  espessura2Cm: number | string | null;
  espessura3Cm: number | string | null;
  espessuraMediaCm: number | string | null;
  areaM2: number | string | null;
  volumeM3: number | string | null;
  densidade: number | string | null;
  massaTonelada: number | string | null;
  atividadeObservacoes: string | null;
  observacoes: string | null;
}

export interface StaviaSnapshotAlocacao {
  colaboradorId: string | null;
  nomeColaborador: string | null;
  equipe: string | null;
  servicoNome: string | null;
  horaInicio: string | null;
  horaFim: string | null;
  minutos: number | string | null;
  percentualDia: number | string | null;
  turno: string | null;
  funcao: string | null;
  centroCusto: string | null;
  tipoAlocacao: string | null;
  fonte: string | null;
  status: string | null;
  custoHora: number | string | null;
  custoTotal: number | string | null;
  observacoes: string | null;
}

export interface StaviaSnapshotServicoExecutado {
  servicoNome: string | null;
  itemContratualId: string | null;
  quantidadeExecutada: number | string | null;
  unidade: string | null;
  trechoInicial: string | null;
  trechoFinal: string | null;
  localizacao: string | null;
  dataExecucao: string | null;
  turno: string | null;
  statusValidacao: string | null;
  estadoReceita: string | null;
  receitaOperacionalEstimativa: number | string | null;
  custoRealizado: number | string | null;
  retrabalho: boolean | string | null;
  producaoRejeitada: boolean | string | null;
  observacoes: string | null;
}

export interface StaviaSnapshotAttachment {
  id: string;
  rdoId: string;
  obraId: string | null;
  tipo: string | null;
  nome: string | null;
  nomeOriginal: string | null;
  mimeType: string | null;
  tamanhoOriginalBytes: number | string | null;
  tamanhoComprimidoBytes: number | string | null;
  tamanhoBytes: number | string | null;
  syncStatus: string | null;
  createdAt: string | null;
  updatedAt: string | null;
  removedAt: string | null;
  metadata: Record<string, unknown> | null;
}

export interface StaviaSnapshotOperationalEvent {
  id: string;
  type: string;
  principalEntityType: string;
  principalEntityId: string;
  obraId: string | null;
  rdoId: string | null;
  colaboradorId: string | null;
  occurredAt: string | null;
  syncedAt: string | null;
  origin: string | null;
  syncStatus: string | null;
  responsibleUserId: string | null;
  responsibleUserName: string | null;
  schemaVersion: number | string | null;
  relatedEntities: unknown;
  payload: Record<string, unknown> | null;
}

export interface StaviaSnapshotRdo {
  id: string;
  obraId: string;
  programacaoId: string | null;
  numeroRdo: string | null;
  dataRdo: string | null;
  diaSemana: string | null;
  cliente: string | null;
  cidade: string | null;
  contrato: string | null;
  rodovia: string | null;
  uf: string | null;
  kmInicialProgramado: string | null;
  kmFinalProgramado: string | null;
  kmInicialInterditado: string | null;
  kmFinalInterditado: string | null;
  turno: string | null;
  horaInicio: string | null;
  horaFim: string | null;
  condicaoManha: string | null;
  condicaoTarde: string | null;
  condicaoNoite: string | null;
  pluviometriaMm: number | string | null;
  status: string | null;
  fonteCriacao: string | null;
  estadoReceita: string | null;
  fonteArquivo: string | null;
  abaOrigem: string | null;
  linhaOrigem: number | string | null;
  dataOriginal: string | null;
  dataImportacao: string | null;
  usuarioImportacao: string | null;
  criadoEm: string | null;
  enviadoEm: string | null;
  aprovadoEm: string | null;
  versaoLinha: number | string | null;
  syncStatus: string | null;
  observacoes: string | null;
  preenchidoPor: string | null;
  apontadorRdo: string | null;
  encarregadoObra: string | null;
  fiscalizacaoCampo: string | null;
  updatedAt: string | null;
  servicosExecutados: StaviaSnapshotServicoExecutado[];
  maoObra: StaviaSnapshotMaoObra[];
  equipamentos: StaviaSnapshotEquipamento[];
  materiais: StaviaSnapshotMaterial[];
  controlesGeometricos: StaviaSnapshotControleGeometrico[];
  alocacoesColaboradores: StaviaSnapshotAlocacao[];
  attachments: StaviaSnapshotAttachment[];
}

export interface StaviaSnapshotProgramacao {
  id: string;
  obraId: string;
  rdoId: string | null;
  dataProgramacao: string | null;
  equipe: string | null;
  fechamento: string | null;
  encarregado: string | null;
  encarregadoColaboradorId: string | null;
  engenheiro: string | null;
  cliente: string | null;
  servico: string | null;
  tipoServico: string | null;
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
  sentido: string | null;
  periodo: string | null;
  faixa: string | null;
  kmInicial: string | null;
  kmFinal: string | null;
  extensaoM: number | string | null;
  larguraM: number | string | null;
  espessuraCm: number | string | null;
  areaM2: number | string | null;
  volumeM3: number | string | null;
  toneladaMassa: number | string | null;
  tipoCap: string | null;
  teorCapProjeto: number | string | null;
  cap: number | string | null;
  status: string | null;
  fonteCriacao: string | null;
  fonteArquivo: string | null;
  linhaOrigem: number | string | null;
  observacoes: string | null;
  updatedAt: string | null;
}

export interface StaviaSnapshotPdor {
  obraId: string;
  snapshotId: string | null;
  dataReferencia: string | null;
  dataExecucao: string | null;
  statusExecucao: string | null;
  calibracao: string | null;
  risco: string | null;
  receitaEstimadaFinal?: number | string | null;
  p10Receita?: number | string | null;
  p50Receita?: number | string | null;
  p80Receita?: number | string | null;
  p95Receita?: number | string | null;
  probabilidadeAbaixoContrato: number | string | null;
  probabilidadeAbaixo95Pct: number | string | null;
  probabilidadeAbaixo90Pct: number | string | null;
  scoreHeuristico: number | string | null;
  confianca: number | string | null;
}

export interface StaviaSnapshotTarefa {
  id: string;
  obraId: string;
  equipe: string | null;
  titulo: string | null;
  observacoes: string | null;
  criadaPor: string | null;
  criadaPorColaboradorId: string | null;
  responsavelEquipe: string | null;
  responsavelColaboradorId: string | null;
  prioridade: number | null;
  concluida: boolean;
  concluidaEm: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

export interface StaviaSnapshot {
  metadata: StaviaSnapshotMetadata;
  obras: StaviaSnapshotObra[];
  rdos: StaviaSnapshotRdo[];
  programacoes: StaviaSnapshotProgramacao[];
  pdors: StaviaSnapshotPdor[];
  operationalEvents: StaviaSnapshotOperationalEvent[];
  tarefas?: StaviaSnapshotTarefa[];
  ontology?: unknown;
}
