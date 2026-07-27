import { describe, expect, it } from "vitest";

import { createEmptyRdo } from "../createEmptyRdo";
import {
  buildRdoExportProjection,
  type RdoWorkbookSnapshot,
} from "./rdoExportProjection";

function snapshot(): RdoWorkbookSnapshot {
  return {
    obra: {
      id: "obra-42",
      nome: "Obra Norte",
      codigoContrato: "CW-007",
    },
    rdo: {
      ...createEmptyRdo(),
      id: "rdo-42",
      obraId: "obra-42",
      numeroRdo: "RDO-0042",
      dataRdo: "2026-07-22",
      previousRdoId: "rdo-41",
      previousRdoNumber: "RDO-0041",
      condicaoManha: "BOM",
      condicaoTarde: "NUBLADO",
      condicaoNoite: "CHUVA",
      apontadorRdo: "Ana Apontadora",
      maoObra: [
        {
          localId: "worker-1",
          origemItemId: "origin-1",
          sourceRdoId: "rdo-41",
          origin: "PREVIOUS_RDO",
          availability: "AVAILABLE",
          selected: true,
          colaboradorId: "col-1",
          nomeColaborador: "Ana Apontadora",
          cargo: "Apontador",
          tipoVinculo: "PROPRIO",
          quantidade: 1,
          horaInicio: "07:30",
          horaFim: "17:15",
          observacoes: "",
        },
        {
          localId: "worker-2",
          origemItemId: "",
          sourceRdoId: "",
          origin: "MANUAL",
          availability: "AVAILABLE",
          selected: true,
          colaboradorId: "col-2",
          nomeColaborador: "Olívia Operadora",
          cargo: "Operador",
          tipoVinculo: "TERCEIRIZADO",
          quantidade: 2,
          horaInicio: "07:30",
          horaFim: "17:15",
          observacoes: "",
        },
      ],
      equipamentos: [{
        localId: "equipment-1",
        assetId: "asset-1",
        prefixo: "EQ-7",
        descricao: "Escavadeira",
        tipoEquipamento: "ESCAVADEIRA",
        tipoVinculo: "PROPRIO",
        quantidade: 1,
        horaInicio: "07:30",
        horaFim: "17:15",
        observacoes: "",
      }],
      materiais: [{
        localId: "material-1",
        materialNome: "CBUQ",
        unidade: "t",
        quantidadePrevista: "",
        quantidadeUsinada: "",
        quantidadeAplicada: 12,
        quantidadeSobra: "",
        notaFiscal: "NF-42",
        fornecedor: "Fornecedor",
        observacoes: "",
      }],
      controlesGeometricos: [{
        localId: "geometry-1",
        subtrecho: "km 10 ao km 11",
        numero: "01",
        estacaInicial: "10+000",
        estacaFinal: "11+000",
        kmInicial: "10",
        kmFinal: "11",
        pista: "Direita",
        faixa: "1",
        ordemServico: "OS-17",
        atividadeObservacoes: "Regularização do subleito",
        comprimentoM: 1_000,
        larguraM: 7.2,
        espessura1Cm: 4,
        espessura2Cm: 5,
        espessura3Cm: 6,
        densidade: 12.75,
        observacoes: "",
      }],
      servicosExecutados: [{
        localId: "service-1",
        servicoNome: "Recomposição asfáltica",
        itemContratualId: "item-1",
        quantidadeExecutada: 12,
        unidade: "t",
        trechoInicial: "11+000",
        trechoFinal: "11+250",
        localizacao: "Pista direita",
        turno: "DIURNO",
        statusValidacao: "VALIDADA",
        retrabalho: false,
        producaoRejeitada: false,
        observacoes: "",
      }],
      observacoes: "Continuidade da equipe",
    },
  };
}

describe("RDO export projection", () => {
  it("preserves the printable values consumed by the workbook mapping", () => {
    const projection = buildRdoExportProjection(snapshot());

    expect(projection.workforce).toHaveLength(2);
    expect(projection.worked.map((row) => row.activity)).toContain(
      "Recomposição asfáltica | Quantidade: 12 t",
    );
    expect(projection.materials[0]).toMatchObject({
      description: "CBUQ (A)", unit: "t",
    });
    expect(projection.observations).toContain("Continuidade da equipe");
  });
});
