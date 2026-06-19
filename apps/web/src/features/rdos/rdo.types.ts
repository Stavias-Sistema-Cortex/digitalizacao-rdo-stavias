export type NumericInput = number | "";

export type RdoSyncStatus =
  | "LOCAL_ONLY"
  | "PENDING_SYNC"
  | "SYNCING"
  | "SYNCED"
  | "ERROR"
  | "CONFLICT";

export type TurnoRdo = "DIURNO" | "NOTURNO";

export type CondicaoClimatica =
  | ""
  | "BOM"
  | "NUBLADO"
  | "CHUVA"
  | "IMPOSSIBILITADO"
  | "NAO_APLICAVEL";

export interface MaoObraDraft {
  localId: string;
  colaboradorId: string;
  nomeColaborador: string;
  cargo: string;
  tipoVinculo: string;
  quantidade: NumericInput;
}

export interface EquipamentoDraft {
  localId: string;
  assetId: string;
  prefixo: string;
  descricao: string;
  tipoEquipamento: string;
  tipoVinculo: string;
  quantidade: NumericInput;
}

export interface MaterialDraft {
  localId: string;
  materialNome: string;
  unidade: string;
  quantidadePrevista: NumericInput;
  quantidadeUsinada: NumericInput;
  quantidadeAplicada: NumericInput;
}

export interface ControleGeometricoDraft {
  localId: string;
  subtrecho: string;
  kmInicial: string;
  kmFinal: string;
  comprimentoM: NumericInput;
  larguraM: NumericInput;
  espessura1Cm: NumericInput;
  espessura2Cm: NumericInput;
  espessura3Cm: NumericInput;
  densidade: NumericInput;
}

export interface RdoDraft {
  id: string;
  obraId: string;
  programacaoId: string;
  numeroRdo: string;
  dataRdo: string;
  turno: TurnoRdo;
  horaInicio: string;
  horaFim: string;
  condicaoManha: CondicaoClimatica;
  condicaoTarde: CondicaoClimatica;
  condicaoNoite: CondicaoClimatica;
  pluviometriaMm: NumericInput;
  observacoes: string;
  maoObra: MaoObraDraft[];
  equipamentos: EquipamentoDraft[];
  materiais: MaterialDraft[];
  controlesGeometricos: ControleGeometricoDraft[];
  syncStatus: RdoSyncStatus;
}
