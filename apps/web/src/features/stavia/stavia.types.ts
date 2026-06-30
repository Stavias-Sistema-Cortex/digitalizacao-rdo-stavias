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
  nomeColaborador: string | null;
  cargo: string | null;
  tipoVinculo: string | null;
  quantidade: number | string | null;
}

export interface StaviaSnapshotEquipamento {
  prefixo: string | null;
  descricao: string | null;
  tipoEquipamento: string | null;
  tipoVinculo: string | null;
  quantidade: number | string | null;
}

export interface StaviaSnapshotMaterial {
  materialNome: string | null;
  unidade: string | null;
  quantidadePrevista: number | string | null;
  quantidadeUsinada: number | string | null;
  quantidadeAplicada: number | string | null;
  quantidadeSobra: number | string | null;
}

export interface StaviaSnapshotControleGeometrico {
  subtrecho: string | null;
  kmInicial: string | null;
  kmFinal: string | null;
  comprimentoM: number | string | null;
  larguraM: number | string | null;
  areaM2: number | string | null;
  volumeM3: number | string | null;
}

export interface StaviaSnapshotAlocacao {
  colaboradorId: string | null;
  nomeColaborador: string | null;
  equipe: string | null;
  servicoNome: string | null;
  horaInicio: string | null;
  horaFim: string | null;
  turno: string | null;
  funcao: string | null;
  status: string | null;
}

export interface StaviaSnapshotServicoExecutado {
  servicoNome: string | null;
  quantidadeExecutada: number | string | null;
  unidade: string | null;
  trechoInicial: string | null;
  trechoFinal: string | null;
  localizacao: string | null;
  turno: string | null;
  statusValidacao: string | null;
}

export interface StaviaSnapshotRdo {
  id: string;
  obraId: string;
  programacaoId: string | null;
  numeroRdo: string | null;
  dataRdo: string | null;
  cidade: string | null;
  contrato: string | null;
  rodovia: string | null;
  uf: string | null;
  turno: string | null;
  horaInicio: string | null;
  horaFim: string | null;
  status: string | null;
  observacoes: string | null;
  updatedAt: string | null;
  servicosExecutados: StaviaSnapshotServicoExecutado[];
  maoObra: StaviaSnapshotMaoObra[];
  equipamentos: StaviaSnapshotEquipamento[];
  materiais: StaviaSnapshotMaterial[];
  controlesGeometricos: StaviaSnapshotControleGeometrico[];
  alocacoesColaboradores: StaviaSnapshotAlocacao[];
}

export interface StaviaSnapshotPdoc {
  obraId: string;
  snapshotId: string | null;
  dataReferencia: string | null;
  dataExecucao: string | null;
  statusExecucao: string | null;
  calibracao: string | null;
  risco: string | null;
  probabilidadeQualquerExcedente: number | string | null;
  probabilidadeExceder5Pct: number | string | null;
  probabilidadeExceder10Pct: number | string | null;
  scoreHeuristico: number | string | null;
  confianca: number | string | null;
}

export interface StaviaSnapshot {
  metadata: StaviaSnapshotMetadata;
  obras: StaviaSnapshotObra[];
  rdos: StaviaSnapshotRdo[];
  pdocs: StaviaSnapshotPdoc[];
}
