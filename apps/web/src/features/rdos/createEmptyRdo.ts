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

function currentLocalDate(): string {
  const now = new Date();
  const localDate = new Date(
    now.getTime() - now.getTimezoneOffset() * 60_000,
  );

  return localDate.toISOString().slice(0, 10);
}

export function createEmptyMaoObra(): MaoObraDraft {
  return {
    localId: createLocalId(),
    origemItemId: "",
    colaboradorId: "",
    nomeColaborador: "",
    cargo: "",
    tipoVinculo: "CONTRATADO",
    quantidade: 1,
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
    tipoVinculo: "PROPRIO",
    quantidade: 1,
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
    servicoNome: "",
    itemContratualId: "",
    quantidadeExecutada: "",
    unidade: "",
    trechoInicial: "",
    trechoFinal: "",
    localizacao: "",
    turno: "",
    statusValidacao: "REGISTRADA",
    custoRealizado: "",
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
    horaInicio: "07:00",
    horaFim: "17:00",
    percentualDia: 1,
    turno: "",
    funcao: "",
    centroCusto: "",
    tipoAlocacao: "TRABALHO",
    fonte: "RDO",
    status: "REGISTRADA",
    custoHora: "",
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
    creationContextVersion: null,
    apontadorColaboradorId: "",
    numeroRdo: "",
    dataRdo: currentLocalDate(),
    cliente: "",
    contrato: "",
    rodovia: "",
    cidade: "",
    uf: "",
    kmInicialProgramado: "",
    kmFinalProgramado: "",
    kmInicialInterditado: "",
    kmFinalInterditado: "",
    turno: "DIURNO",
    horaInicio: "07:00",
    horaFim: "17:00",
    condicaoManha: "",
    condicaoTarde: "",
    condicaoNoite: "NAO_APLICAVEL",
    pluviometriaMm: 0,
    observacoes: "",
    preenchidoPor: "",
    apontadorRdo: "",
    encarregadoObra: "",
    fiscalizacaoCampo: "",
    servicosExecutados: [createEmptyServicoExecutado()],
    alocacoesColaboradores: [
      createEmptyAlocacaoColaborador(),
    ],
    maoObra: [createEmptyMaoObra()],
    equipamentos: [createEmptyEquipamento()],
    materiais: [createEmptyMaterial()],
    controlesGeometricos: [createEmptyControleGeometrico()],
    attachments: [],
    syncStatus: "LOCAL_ONLY",
  };
}
