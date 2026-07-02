import type { LocalRdoRecord } from "../../lib/db/db.types";
import {
  createEmptyAlocacaoColaborador,
  createEmptyControleGeometrico,
  createEmptyEquipamento,
  createEmptyMaoObra,
  createEmptyMaterial,
  createEmptyServicoExecutado,
} from "./createEmptyRdo";
import type {
  AlocacaoColaboradorDraft,
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  NumericInput,
  RdoDraft,
  ServicoExecutadoDraft,
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
      localId: asString(item.localId, empty.localId),
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
      localId: asString(item.localId, empty.localId),
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
      localId: asString(item.localId, empty.localId),
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
      quantidadeSobra: asNumericInput(item.quantidadeSobra),
      notaFiscal: asString(item.notaFiscal),
      fornecedor: asString(item.fornecedor),
      observacoes: asString(item.observacoes),
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
      localId: asString(item.localId, empty.localId),
      subtrecho: asString(item.subtrecho),
      numero: asString(item.numero),
      estacaInicial: asString(item.estacaInicial),
      estacaFinal: asString(item.estacaFinal),
      kmInicial: asString(item.kmInicial),
      kmFinal: asString(item.kmFinal),
      pista: asString(item.pista),
      faixa: asString(item.faixa),
      ordemServico: asString(item.ordemServico),
      atividadeObservacoes: asString(item.atividadeObservacoes),
      comprimentoM: asNumericInput(item.comprimentoM),
      larguraM: asNumericInput(item.larguraM),
      espessura1Cm: asNumericInput(item.espessura1Cm),
      espessura2Cm: asNumericInput(item.espessura2Cm),
      espessura3Cm: asNumericInput(item.espessura3Cm),
      densidade: asNumericInput(item.densidade),
      observacoes: asString(item.observacoes),
    };
  });
}

function mapServicosExecutados(
  value: unknown,
): ServicoExecutadoDraft[] {
  return asArray(value).map((rawItem) => {
    const item = asObject(rawItem);
    const empty = createEmptyServicoExecutado();

    return {
      ...empty,
      localId: asString(item.localId, empty.localId),
      servicoNome: asString(item.servicoNome),
      itemContratualId: asString(item.itemContratualId),
      quantidadeExecutada: asNumericInput(
        item.quantidadeExecutada,
      ),
      unidade: asString(item.unidade),
      trechoInicial: asString(item.trechoInicial),
      trechoFinal: asString(item.trechoFinal),
      localizacao: asString(item.localizacao),
      turno: asString(
        item.turno,
        empty.turno,
      ) as ServicoExecutadoDraft["turno"],
      statusValidacao: asString(
        item.statusValidacao,
        empty.statusValidacao,
      ) as ServicoExecutadoDraft["statusValidacao"],
      custoRealizado: asNumericInput(item.custoRealizado),
      retrabalho: item.retrabalho === true,
      producaoRejeitada: item.producaoRejeitada === true,
      observacoes: asString(item.observacoes),
    };
  });
}

function mapAlocacoesColaboradores(
  value: unknown,
): AlocacaoColaboradorDraft[] {
  return asArray(value).map((rawItem) => {
    const item = asObject(rawItem);
    const empty = createEmptyAlocacaoColaborador();

    return {
      ...empty,
      localId: asString(item.localId, empty.localId),
      colaboradorId: asString(item.colaboradorId),
      equipe: asString(item.equipe),
      servicoNome: asString(item.servicoNome),
      horaInicio: asString(item.horaInicio, empty.horaInicio),
      horaFim: asString(item.horaFim, empty.horaFim),
      percentualDia: asNumericInput(item.percentualDia),
      turno: asString(
        item.turno,
        empty.turno,
      ) as AlocacaoColaboradorDraft["turno"],
      funcao: asString(item.funcao),
      centroCusto: asString(item.centroCusto),
      tipoAlocacao: asString(
        item.tipoAlocacao,
        empty.tipoAlocacao,
      ) as AlocacaoColaboradorDraft["tipoAlocacao"],
      fonte: asString(item.fonte, empty.fonte),
      status: asString(
        item.status,
        empty.status,
      ) as AlocacaoColaboradorDraft["status"],
      custoHora: asNumericInput(item.custoHora),
      observacoes: asString(item.observacoes),
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
    cliente: asString(payload.cliente),
    contrato: asString(payload.contrato),
    rodovia: asString(payload.rodovia),
    cidade: asString(payload.cidade),
    uf: asString(payload.uf),
    kmInicialProgramado: asString(
      payload.kmInicialProgramado,
    ),
    kmFinalProgramado: asString(payload.kmFinalProgramado),
    kmInicialInterditado: asString(
      payload.kmInicialInterditado,
    ),
    kmFinalInterditado: asString(
      payload.kmFinalInterditado,
    ),
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
    preenchidoPor: asString(payload.preenchidoPor),
    apontadorRdo: asString(payload.apontadorRdo),
    encarregadoObra: asString(payload.encarregadoObra),
    fiscalizacaoCampo: asString(payload.fiscalizacaoCampo),
    servicosExecutados: mapServicosExecutados(
      payload.servicosExecutados,
    ),
    alocacoesColaboradores: mapAlocacoesColaboradores(
      payload.alocacoesColaboradores,
    ),
    maoObra: mapMaoObra(payload.maoObra),
    equipamentos: mapEquipamentos(payload.equipamentos),
    materiais: mapMateriais(payload.materiais),
    controlesGeometricos: mapControles(
      payload.controlesGeometricos,
    ),
    syncStatus: record.syncStatus,
  };
}
