export type NumericInput = number | "";

export type RdoSyncStatus =
  | "LOCAL_ONLY"
  | "LOCAL_PENDING"
  | "PENDING_SYNC"
  | "SYNCING"
  | "SYNCED"
  | "ERROR"
  | "CONFLICT";

export type TurnoRdo = "DIURNO" | "NOTURNO";

export type WorkforceAvailability =
  | "AVAILABLE"
  | "UNAVAILABLE"
  | "UNKNOWN";

export type WorkforceOrigin =
  | "PREVIOUS_RDO"
  | "AUTHORIZED_CONTEXT"
  | "MANUAL";

export type CondicaoClimatica =
  | ""
  | "BOM"
  | "NUBLADO"
  | "CHUVA"
  | "IMPOSSIBILITADO"
  | "NAO_APLICAVEL";

export interface MaoObraDraft {
  localId: string;
  origemItemId: string;
  sourceRdoId: string;
  origin: WorkforceOrigin;
  availability: WorkforceAvailability;
  selected: boolean;
  colaboradorId: string;
  nomeColaborador: string;
  cargo: string;
  tipoVinculo: string;
  quantidade: NumericInput;
  horaInicio: string;
  horaFim: string;
  observacoes: string;
}

export interface EquipamentoDraft {
  localId: string;
  assetId: string;
  prefixo: string;
  descricao: string;
  tipoEquipamento: string;
  tipoVinculo: string;
  quantidade: NumericInput;
  horaInicio: string;
  horaFim: string;
  observacoes: string;
}

export interface MaterialDraft {
  localId: string;
  materialNome: string;
  unidade: string;
  quantidadePrevista: NumericInput;
  quantidadeUsinada: NumericInput;
  quantidadeAplicada: NumericInput;
  quantidadeSobra: NumericInput;
  notaFiscal: string;
  fornecedor: string;
  observacoes: string;
}

export interface ControleGeometricoDraft {
  localId: string;
  subtrecho: string;
  numero: string;
  estacaInicial: string;
  estacaFinal: string;
  kmInicial: string;
  kmFinal: string;
  pista: string;
  faixa: string;
  ordemServico: string;
  atividadeObservacoes: string;
  comprimentoM: NumericInput;
  larguraM: NumericInput;
  espessura1Cm: NumericInput;
  espessura2Cm: NumericInput;
  espessura3Cm: NumericInput;
  densidade: NumericInput;
  observacoes: string;
}

export interface ServicoExecutadoDraft {
  localId: string;
  serviceId: string;
  priceVersionId: string;
  servicoNome: string;
  itemContratualId: string;
  quantidadeExecutada: NumericInput;
  unidade: string;
  trechoInicial: string;
  trechoFinal: string;
  /** Pista e faixa onde o serviço aconteceu, no vocabulário do controle geométrico. */
  pista: string;
  faixa: string;
  localizacao: string;
  turno: "" | TurnoRdo;
  statusValidacao: "" | "REGISTRADA" | "VALIDADA" | "REJEITADA";
  retrabalho: boolean;
  producaoRejeitada: boolean;
  observacoes: string;
}

export interface AlocacaoColaboradorDraft {
  localId: string;
  colaboradorId: string;
  equipe: string;
  servicoNome: string;
  horaInicio: string;
  horaFim: string;
  percentualDia: NumericInput;
  turno: "" | TurnoRdo;
  funcao: string;
  centroCusto: string;
  tipoAlocacao:
    | ""
    | "TRABALHO"
    | "DESLOCAMENTO"
    | "TREINAMENTO"
    | "MANUTENCAO"
    | "APOIO"
    | "ADMINISTRATIVO"
    | "AFASTAMENTO"
    | "OUTRO";
  fonte: string;
  status: "" | "REGISTRADA" | "VALIDADA" | "CONFLITO";
  observacoes: string;
}

export interface RdoAttachmentDraft {
  id: string;
  rdoId: string;
  obraId: string | null;
  tipo: "FOTO";
  nome: string;
  nomeOriginal: string | null;
  mimeType: string;
  tamanhoOriginalBytes: number;
  tamanhoComprimidoBytes: number;
  tamanhoBytes: number;
  syncStatus:
    | "LOCAL_ONLY"
    | "PENDING_SYNC"
    | "SYNCING"
    | "SYNCED"
    | "SYNC_FAILED";
  createdAt: string;
  updatedAt: string;
  removedAt: string | null;
  metadata: Record<string, unknown>;
}

export interface RdoImportEvidence {
  source: "IMPORTED_DOCUMENT";
  rawWorksiteIdentity: {
    numeroRdo: string;
    obraId: string;
    dataRdo: string;
    cliente: string;
    contrato: string;
    rodovia: string;
    cidade: string;
    uf: string;
  };
  boundContext: {
    obraId: string;
    dataRdo: string;
    receiptVersion: number;
  };
}

export interface RdoDraft {
  id: string;
  obraId: string;
  programacaoId: string;
  previousRdoId: string;
  previousRdoNumber: string;
  creationContextVersion: number | null;
  apontadorColaboradorId: string;
  numeroRdo: string;
  dataRdo: string;
  cliente: string;
  contrato: string;
  rodovia: string;
  cidade: string;
  uf: string;
  kmInicialProgramado: string;
  kmFinalProgramado: string;
  kmInicialInterditado: string;
  kmFinalInterditado: string;
  turno: "" | TurnoRdo;
  horaInicio: string;
  horaFim: string;
  condicaoManha: CondicaoClimatica;
  condicaoTarde: CondicaoClimatica;
  condicaoNoite: CondicaoClimatica;
  pluviometriaMm: NumericInput;
  observacoes: string;
  preenchidoPor: string;
  apontadorRdo: string;
  encarregadoObra: string;
  fiscalizacaoCampo: string;
  servicosExecutados: ServicoExecutadoDraft[];
  alocacoesColaboradores: AlocacaoColaboradorDraft[];
  maoObra: MaoObraDraft[];
  equipamentos: EquipamentoDraft[];
  materiais: MaterialDraft[];
  controlesGeometricos: ControleGeometricoDraft[];
  attachments: RdoAttachmentDraft[];
  importEvidence: RdoImportEvidence | null;
  syncStatus: RdoSyncStatus;
}
