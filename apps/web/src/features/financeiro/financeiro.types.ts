export type FinancialPermission =
  | "FINANCEIRO_VISUALIZAR"
  | "FINANCEIRO_OPERAR"
  | "FINANCEIRO_APROVAR"
  | "FINANCEIRO_ADMINISTRAR";

export interface FinanceCapabilities {
  unidadeControleId?: string | null;
  obraId: string | null;
  permissoes: FinancialPermission[];
}

export type FinanceControlUnitType =
  | "OBRA"
  | "ATIVO"
  | "ADMINISTRATIVO"
  | "CORPORATIVO";

export interface FinanceControlUnit {
  id: string;
  tipo: FinanceControlUnitType;
  obraId: string | null;
  ativoId: string | null;
  codigo: string;
  nome: string;
  status: "ATIVA" | "ARQUIVADA";
  versao: number;
}

export type FinanceAllocationSourceType =
  | "COMPRA"
  | "NOTA_FISCAL"
  | "LANCAMENTO";

export interface FinanceAllocationItem {
  id: string;
  unidadeControleId: string;
  centroCustoId: string | null;
  categoriaId: string | null;
  valor: number;
  percentual: number;
  ordem: number;
}

export interface FinanceAllocation {
  id: string;
  origemTipo: FinanceAllocationSourceType;
  origemId: string;
  moeda: string;
  valorTotal: number;
  status: "ATIVO" | "ARQUIVADO";
  itens: FinanceAllocationItem[];
  criadoPor: string;
  criadoEm: string;
  atualizadoPor: string | null;
  atualizadoEm: string;
  versao: number;
}

export interface FinanceAllocationHistoryEntry {
  id: string;
  operacao: "CRIAR" | "ATUALIZAR" | "ARQUIVAR";
  versaoAnterior: number | null;
  versaoNova: number;
  estadoAnterior: unknown;
  estadoNovo: unknown;
  alteradoPor: string;
  alteradoEm: string;
  clientMutationId: string;
  correlacaoId: string | null;
  dispositivoId: string | null;
}

export interface FinanceFilters {
  obraId: string;
  de: string;
  ate: string;
  fornecedorId: string;
  centroCustoId: string;
  categoriaId: string;
  statusId: string;
  prioridade: string;
  tipo: string;
  moeda: string;
  query: string;
  manualMode: FinanceManualMode;
  manualRules: FinanceManualFilter[];
}

export type FinanceManualMode = "ALL" | "ANY";

export type FinanceManualField =
  | "descricao"
  | "numero"
  | "fornecedor"
  | "status"
  | "tipo"
  | "valor"
  | "data";

export type FinanceManualOperator =
  | "CONTAINS"
  | "NOT_CONTAINS"
  | "EQUALS"
  | "NOT_EQUALS"
  | "STARTS_WITH"
  | "GREATER_THAN"
  | "LESS_THAN"
  | "BETWEEN"
  | "ON"
  | "BEFORE"
  | "AFTER";

export interface FinanceManualFilter {
  id: string;
  field: FinanceManualField;
  operator: FinanceManualOperator;
  value: string;
  secondValue: string;
}

export interface FinanceManualRow {
  descricao: string;
  numero: string;
  fornecedor: string;
  status: string;
  tipo: string;
  valor: number;
  data: string;
}

export interface FinanceLineItem {
  id: string | null;
  descricao: string;
  quantidade: number;
  unidade: string;
  valorUnitario: number;
  valorTotal: number;
}

export interface FinancePurchaseDraft {
  obraId: string;
  solicitacaoId?: string | null;
  fornecedorId: string;
  centroCustoId: string;
  categoriaId: string;
  responsavelCompraId: string | null;
  statusId: string;
  numeroPedido: string;
  descricao: string;
  prioridade: string;
  moeda: string;
  valorPrevisto: number;
  valorAprovado: number | null;
  valorContratado: number | null;
  valorPago: number | null;
  pedidoEmitidoEm: string | null;
  entregaPrevistaEm: string | null;
  itens: FinanceLineItem[];
}

export interface FinancePurchase extends FinancePurchaseDraft {
  id: string;
  obraNome: string | null;
  centroCustoNome: string | null;
  categoriaNome: string | null;
  fornecedorNome: string | null;
  responsavelCompraNome: string | null;
  statusCodigo: string | null;
  statusNome: string | null;
  criadoEm: string;
  atualizadoEm: string;
  versao: number;
  syncStatus?: "PENDING_SYNC" | "SYNCED" | "ERROR" | "CONFLICT";
  syncError?: string | null;
  clientMutationId?: string;
}

export interface FinanceSupplier {
  id: string;
  razaoSocial: string;
  nomeFantasia: string | null;
  cnpj: string;
  emailFinanceiro: string | null;
  telefone: string | null;
  status: string;
  versao: number;
}

export interface FinanceCostCenter {
  id: string;
  obraId: string;
  codigo: string;
  nome: string;
  descricao: string | null;
  responsavelId: string | null;
  status: string;
  versao: number;
}

export interface FinanceCategory {
  id: string;
  obraId: string;
  codigo: string;
  nome: string;
  descricao: string | null;
  status: string;
  versao: number;
}

export interface FinanceStatusDefinition {
  id: string;
  obraId: string;
  agregadoTipo: string;
  codigo: string;
  nome: string;
  descricao: string | null;
  ordem: number;
  terminal: boolean;
  ativo: boolean;
  versao: number;
}

export interface FinanceInvoice {
  id: string;
  obraId: string;
  fornecedorId: string;
  fornecedorNome: string | null;
  fornecedorCnpj: string | null;
  centroCustoId: string | null;
  categoriaId: string | null;
  responsavelId: string | null;
  responsavelNome: string | null;
  statusId: string;
  statusCodigo: string | null;
  statusNome: string | null;
  numero: string;
  serie: string;
  chaveAcesso: string | null;
  tipoDocumento: string;
  dataEmissao: string;
  dataCompetencia: string | null;
  dataRecebimento: string | null;
  vencimentoEm: string;
  moeda: string;
  valorBruto: number;
  desconto: number;
  acrescimo: number;
  retencoes: number;
  valorLiquido: number;
  ocrStatus: string;
  observacoes: string | null;
  versao: number;
  criadoEm: string;
  atualizadoEm: string;
}

export interface FinanceInvoiceDraft {
  obraId: string;
  fornecedorId: string;
  centroCustoId: string | null;
  categoriaId: string | null;
  responsavelId: string | null;
  statusId: string;
  numero: string;
  serie: string;
  chaveAcesso: string | null;
  tipoDocumento: string;
  dataEmissao: string;
  dataCompetencia: string | null;
  dataRecebimento: string | null;
  vencimentoEm: string;
  moeda: string;
  valorBruto: number;
  desconto: number;
  acrescimo: number;
  retencoes: number;
  valorLiquido: number;
  observacoes: string | null;
}

export interface FinanceInvoiceDocument {
  id: string;
  notaFiscalId: string;
  storedObjectId: string;
  tipoDocumento: string;
  principal: boolean;
  nomeOriginal: string;
  mediaType: string;
  tamanhoBytes: number;
  enviadoPor: string;
  enviadoEm: string;
  dispositivoId: string | null;
  clientMutationId: string | null;
  sha256Cliente: string | null;
  sha256Servidor: string;
  inspecaoStatus: "APROVADO" | "QUARENTENA";
  extracaoJobId: string | null;
  extratorVersao: string | null;
  confirmadoPor: string;
  confirmadoEm: string;
  confirmacaoClientMutationId: string;
  confirmacaoDispositivoId: string | null;
  confirmacaoCorrelacaoId: string | null;
  criadoEm: string;
}

export type FiscalExtractionStatus =
  | "EXTRAIDO"
  | "REVISAO_NECESSARIA"
  | "FALHA";

export type FiscalCandidateValidation =
  | "VALIDO"
  | "DIVERGENTE"
  | "NAO_VERIFICADO";

export interface FinanceFiscalCandidate {
  field: string;
  normalizedValue: unknown;
  originalText: string | null;
  extractor: string;
  extractorVersion: string;
  location: string | null;
  confidence: number;
  validation: FiscalCandidateValidation;
  validationDetail: string | null;
}

export interface FinanceFiscalExtraction {
  id: string;
  storedObjectId: string;
  obraId: string;
  fileName: string;
  mediaType: string;
  size: number;
  clientMutationId: string;
  format: "XML" | "PDF" | "IMAGEM";
  status: FiscalExtractionStatus;
  extractor: string | null;
  extractorVersion: string | null;
  clientSha256: string | null;
  serverSha256: string;
  candidates: FinanceFiscalCandidate[];
  warnings: string[];
  autorizacaoFiscal: "NAO_VERIFICADA";
  createdBy: string;
  deviceId: string | null;
  correlationId: string | null;
  createdAt: string;
  completedAt: string;
  replayed: boolean;
}

export interface FinanceLedgerEntry {
  id: string;
  obraId: string;
  notaFiscalId: string | null;
  fornecedorId: string | null;
  fornecedorNome: string | null;
  centroCustoId: string | null;
  categoriaId: string | null;
  responsavelId: string | null;
  responsavelNome: string | null;
  statusId: string;
  statusCodigo: string | null;
  statusNome: string | null;
  tipo: string;
  origem: string;
  numeroDocumento: string | null;
  descricao: string;
  dataCompetencia: string;
  dataEmissao: string | null;
  vencimentoEm: string;
  moeda: string;
  valorOriginal: number;
  desconto: number;
  juros: number;
  multa: number;
  valorLiquido: number;
  valorLiquidado: number;
  valorAberto: number;
  vencido: boolean;
  versao: number;
  criadoEm: string;
  atualizadoEm: string;
}

export interface FinanceLedgerDraft {
  obraId: string;
  notaFiscalId: string | null;
  fornecedorId: string | null;
  centroCustoId: string | null;
  categoriaId: string | null;
  responsavelId: string | null;
  statusId: string;
  tipo: string;
  origem: string;
  numeroDocumento: string | null;
  descricao: string;
  dataCompetencia: string;
  dataEmissao: string | null;
  vencimentoEm: string;
  moeda: string;
  valorOriginal: number;
  desconto: number;
  juros: number;
  multa: number;
  valorLiquido: number;
}

export interface FinanceSettlement {
  id: string;
  obraId: string;
  tipo: string;
  status: string;
  dataLiquidacao: string;
  moeda: string;
  valorTotal: number;
  meio: string;
  referenciaBancaria: string | null;
  observacoes: string | null;
  versao: number;
  criadoEm: string;
  atualizadoEm: string;
}

export interface FinanceOverview {
  obraId: string;
  referencia: string;
  moeda: string;
  previsto: number | null;
  comprometido: number;
  liquidado: number;
  aberto: number;
  vencido: number;
  aPagar: number;
  aReceber: number;
  quantidadeLancamentos: number;
  quantidadeVencidos: number;
  possuiDados: boolean;
  possuiOrcamentoVinculado: boolean;
}

export interface FinanceReportRow {
  grupoId: string | null;
  grupoNome: string;
  moeda: string;
  valorLiquido: number;
  valorLiquidado: number;
  valorAberto: number;
  quantidade: number;
}

export interface FinanceCharge {
  id: string;
  obraId: string;
  regraId: string | null;
  modeloEmailId: string;
  perfilRemetenteId: string;
  replyToPermitidoId: string | null;
  fornecedorId: string | null;
  alvoTipo: string;
  alvoId: string;
  responsavelId: string | null;
  destinatario: string;
  status: string;
  modo: string;
  vencimentoEm: string | null;
  ocorrenciaPrevistaEm: string | null;
  agendadaPara: string | null;
  proximaTentativaEm: string | null;
  tentativas: number;
  provider: string | null;
  providerMessageId: string | null;
  enviadaEm: string | null;
  erroCategoria: string | null;
  erroSanitizado: string | null;
  versaoLinha: number;
}

export interface FinanceChargePreview {
  cobrancaId: string;
  destinatario: string;
  replyTo: string | null;
  assunto: string;
  corpoTexto: string;
  hashConteudo: string;
  confirmadaEm: string;
}

export interface FinanceAuditEvent {
  id: string;
  type: string;
  occurredAt: string | null;
  origin: string | null;
  payload: Record<string, unknown>;
}
