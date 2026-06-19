import type {
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  RdoDraft,
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
    colaboradorId: "",
    nomeColaborador: "",
    cargo: "",
    tipoVinculo: "CONTRATADO",
    quantidade: 1,
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
  };
}

export function createEmptyControleGeometrico(): ControleGeometricoDraft {
  return {
    localId: createLocalId(),
    subtrecho: "",
    kmInicial: "",
    kmFinal: "",
    comprimentoM: "",
    larguraM: "",
    espessura1Cm: "",
    espessura2Cm: "",
    espessura3Cm: "",
    densidade: "",
  };
}

export function createEmptyRdo(): RdoDraft {
  return {
    id: crypto.randomUUID(),
    obraId: "",
    programacaoId: "",
    numeroRdo: "",
    dataRdo: currentLocalDate(),
    turno: "DIURNO",
    horaInicio: "07:00",
    horaFim: "17:00",
    condicaoManha: "",
    condicaoTarde: "",
    condicaoNoite: "NAO_APLICAVEL",
    pluviometriaMm: 0,
    observacoes: "",
    maoObra: [createEmptyMaoObra()],
    equipamentos: [createEmptyEquipamento()],
    materiais: [createEmptyMaterial()],
    controlesGeometricos: [createEmptyControleGeometrico()],
    syncStatus: "LOCAL_ONLY",
  };
}
