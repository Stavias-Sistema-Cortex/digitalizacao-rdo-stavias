import type {
  AlocacaoColaboradorDraft,
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  RdoDraft,
  ServicoExecutadoDraft,
} from "./rdo.types";

function createLocalId(): string {
  return crypto.randomUUID();
}

export function createEmptyMaoObra(): MaoObraDraft {
  return {
    localId: createLocalId(),
    origemItemId: "",
    sourceRdoId: "",
    origin: "MANUAL",
    availability: "UNKNOWN",
    selected: true,
    colaboradorId: "",
    nomeColaborador: "",
    cargo: "",
    tipoVinculo: "",
    quantidade: "",
    horaInicio: "",
    horaFim: "",
    observacoes: "",
  };
}

export function createEmptyEquipamento(): EquipamentoDraft {
  return {
    localId: createLocalId(),
    assetId: "",
    prefixo: "",
    descricao: "",
    tipoEquipamento: "",
    tipoVinculo: "",
    quantidade: "",
    horaInicio: "",
    horaFim: "",
    observacoes: "",
  };
}

export function createEmptyMaterial(): MaterialDraft {
  return {
    localId: createLocalId(),
    materialNome: "",
    unidade: "",
    quantidadePrevista: "",
    quantidadeUsinada: "",
    quantidadeAplicada: "",
    quantidadeSobra: "",
    notaFiscal: "",
    fornecedor: "",
    observacoes: "",
  };
}

export function createEmptyServicoExecutado(): ServicoExecutadoDraft {
  return {
    localId: createLocalId(),
    serviceId: "",
    priceVersionId: "",
    servicoNome: "",
    itemContratualId: "",
    quantidadeExecutada: "",
    unidade: "",
    trechoInicial: "",
    trechoFinal: "",
    pista: "",
    faixa: "",
    localizacao: "",
    turno: "",
    statusValidacao: "",
    retrabalho: false,
    producaoRejeitada: false,
    observacoes: "",
  };
}

export function createEmptyAlocacaoColaborador(): AlocacaoColaboradorDraft {
  return {
    localId: createLocalId(),
    colaboradorId: "",
    equipe: "",
    servicoNome: "",
    horaInicio: "",
    horaFim: "",
    percentualDia: "",
    turno: "",
    funcao: "",
    centroCusto: "",
    tipoAlocacao: "",
    fonte: "",
    status: "",
    observacoes: "",
  };
}

export function createEmptyControleGeometrico(): ControleGeometricoDraft {
  return {
    localId: createLocalId(),
    subtrecho: "",
    numero: "",
    estacaInicial: "",
    estacaFinal: "",
    kmInicial: "",
    kmFinal: "",
    pista: "",
    faixa: "",
    ordemServico: "",
    atividadeObservacoes: "",
    comprimentoM: "",
    larguraM: "",
    espessura1Cm: "",
    espessura2Cm: "",
    espessura3Cm: "",
    densidade: "",
    observacoes: "",
  };
}

export function createEmptyRdo(): RdoDraft {
  return {
    id: crypto.randomUUID(),
    obraId: "",
    programacaoId: "",
    previousRdoId: "",
    previousRdoNumber: "",
    creationContextVersion: null,
    apontadorColaboradorId: "",
    numeroRdo: "",
    dataRdo: "",
    cliente: "",
    contrato: "",
    rodovia: "",
    cidade: "",
    uf: "",
    kmInicialProgramado: "",
    kmFinalProgramado: "",
    kmInicialInterditado: "",
    kmFinalInterditado: "",
    turno: "",
    horaInicio: "",
    horaFim: "",
    condicaoManha: "",
    condicaoTarde: "",
    condicaoNoite: "",
    pluviometriaMm: "",
    observacoes: "",
    preenchidoPor: "",
    apontadorRdo: "",
    encarregadoObra: "",
    fiscalizacaoCampo: "",
    servicosExecutados: [],
    alocacoesColaboradores: [],
    maoObra: [],
    equipamentos: [],
    materiais: [],
    controlesGeometricos: [],
    attachments: [],
    importEvidence: null,
    syncStatus: "LOCAL_ONLY",
  };
}
