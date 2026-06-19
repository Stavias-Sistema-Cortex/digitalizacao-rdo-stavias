import type { LocalRdoRecord } from "../../lib/db/db.types";
import {
  createEmptyControleGeometrico,
  createEmptyEquipamento,
  createEmptyMaoObra,
  createEmptyMaterial,
} from "./createEmptyRdo";
import type {
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  NumericInput,
  RdoDraft,
} from "./rdo.types";

function asObject(value: unknown): Record<string, unknown> {
  if (
    typeof value === "object" &&
    value !== null &&
    !Array.isArray(value)
  ) {
    return value as Record<string, unknown>;
  }

  return {};
}

function asArray(value: unknown): unknown[] {
  return Array.isArray(value) ? value : [];
}

function asString(value: unknown, fallback = ""): string {
  return typeof value === "string" ? value : fallback;
}

function asNumericInput(value: unknown): NumericInput {
  return typeof value === "number" ? value : "";
}

function mapMaoObra(value: unknown): MaoObraDraft[] {
  return asArray(value).map((rawItem) => {
    const item = asObject(rawItem);
    const empty = createEmptyMaoObra();

    return {
      ...empty,
      colaboradorId: asString(item.colaboradorId),
      nomeColaborador: asString(item.nomeColaborador),
      cargo: asString(item.cargo),
      tipoVinculo: asString(
        item.tipoVinculo,
        empty.tipoVinculo,
      ),
      quantidade: asNumericInput(item.quantidade),
    };
  });
}

function mapEquipamentos(
  value: unknown,
): EquipamentoDraft[] {
  return asArray(value).map((rawItem) => {
    const item = asObject(rawItem);
    const empty = createEmptyEquipamento();

    return {
      ...empty,
      assetId: asString(item.assetId),
      prefixo: asString(item.prefixo),
      descricao: asString(item.descricao),
      tipoEquipamento: asString(item.tipoEquipamento),
      tipoVinculo: asString(
        item.tipoVinculo,
        empty.tipoVinculo,
      ),
      quantidade: asNumericInput(item.quantidade),
    };
  });
}

function mapMateriais(value: unknown): MaterialDraft[] {
  return asArray(value).map((rawItem) => {
    const item = asObject(rawItem);
    const empty = createEmptyMaterial();

    return {
      ...empty,
      materialNome: asString(item.materialNome),
      unidade: asString(item.unidade),
      quantidadePrevista: asNumericInput(
        item.quantidadePrevista,
      ),
      quantidadeUsinada: asNumericInput(
        item.quantidadeUsinada,
      ),
      quantidadeAplicada: asNumericInput(
        item.quantidadeAplicada,
      ),
    };
  });
}

function mapControles(
  value: unknown,
): ControleGeometricoDraft[] {
  return asArray(value).map((rawItem) => {
    const item = asObject(rawItem);
    const empty = createEmptyControleGeometrico();

    return {
      ...empty,
      subtrecho: asString(item.subtrecho),
      kmInicial: asString(item.kmInicial),
      kmFinal: asString(item.kmFinal),
      comprimentoM: asNumericInput(item.comprimentoM),
      larguraM: asNumericInput(item.larguraM),
      espessura1Cm: asNumericInput(item.espessura1Cm),
      espessura2Cm: asNumericInput(item.espessura2Cm),
      espessura3Cm: asNumericInput(item.espessura3Cm),
      densidade: asNumericInput(item.densidade),
    };
  });
}

export function localRecordToDraft(
  record: LocalRdoRecord,
): RdoDraft {
  const payload = asObject(record.payload);

  return {
    id: record.id,
    obraId: record.obraId,
    programacaoId: record.programacaoId ?? "",
    numeroRdo: record.numeroRdo,
    dataRdo: record.dataRdo,
    turno:
      asString(payload.turno) === "NOTURNO"
        ? "NOTURNO"
        : "DIURNO",
    horaInicio: asString(payload.horaInicio),
    horaFim: asString(payload.horaFim),
    condicaoManha: asString(
      payload.condicaoManha,
    ) as RdoDraft["condicaoManha"],
    condicaoTarde: asString(
      payload.condicaoTarde,
    ) as RdoDraft["condicaoTarde"],
    condicaoNoite: asString(
      payload.condicaoNoite,
      "NAO_APLICAVEL",
    ) as RdoDraft["condicaoNoite"],
    pluviometriaMm:
      payload.pluviometriaMm === null
        ? ""
        : asNumericInput(payload.pluviometriaMm),
    observacoes: asString(payload.observacoes),
    maoObra: mapMaoObra(payload.maoObra),
    equipamentos: mapEquipamentos(payload.equipamentos),
    materiais: mapMateriais(payload.materiais),
    controlesGeometricos: mapControles(
      payload.controlesGeometricos,
    ),
    syncStatus: record.syncStatus,
  };
}
