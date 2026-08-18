import { describe, expect, it } from "vitest";

import { applyRdoCreationContext } from "./rdoCreationContext";
import {
  createEmptyRdo,
  createEmptyMaoObra,
  createEmptyEquipamento,
} from "./createEmptyRdo";
import { rascunhoClonadoDe } from "./rascunhoClonado";
import type { RdoDraft } from "./rdo.types";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

/**
 * A equipe que o clone traz não pode ser atropelada pelo RDO anterior.
 *
 * <p>Este é o ponto em que a clonagem enganava, e o mais difícil de ver: mesmo
 * que o clone copiasse as pessoas, `applyRdoCreationContext` reconstruía a mão
 * de obra a partir do RDO <em>anterior</em> — o que antecede a data nova, não o
 * que foi escolhido para copiar. Quem clonava o RDO de segunda para repetir a
 * mesma frente recebia a equipe de sexta, e não tinha como saber de onde ela
 * tinha vindo.
 *
 * <p>Rascunho vazio continua herdando do anterior: é o caminho de quem cria um
 * RDO do zero, e é o que poupa a digitação diária.
 */

const OBRA_ID = "00000000-0000-4000-8000-000000000801";

function contexto(): RdoCreationContextLookup {
  return {
    obra: {
      id: OBRA_ID,
      codigoContrato: "CW386",
      codigoCw: "CW386",
      nome: "Recapeamento SP-310",
      cliente: "DER",
      cidade: "Pirassununga",
      uf: "SP",
      rodovia: "SP-310",
      status: "ATIVA",
    },
    data: "2026-08-12",
    nextNumberSuggestion: "RDO-0018",
    previousRdo: {
      id: "rdo-de-sexta",
      numeroRdo: "RDO-0016",
      dataRdo: "2026-08-07",
    },
    previousWorkforce: [
      {
        sourceRdoId: "rdo-de-sexta",
        sourceItemId: "mo-sexta",
        collaboratorId: "col-sexta",
        nameSnapshot: "Equipe de sexta",
        availability: "AVAILABLE",
      },
    ],
    programacoes: [],
    colaboradores: [
      { id: "col-sexta", nome: "Equipe de sexta" },
      { id: "col-segunda", nome: "Equipe de segunda" },
    ],
    equipamentos: [],
    provenance: {
      receiptVersion: 7,
      worksiteId: OBRA_ID,
      selectedDate: "2026-08-12",
      previousRdoId: "rdo-de-sexta",
    },
  } as unknown as RdoCreationContextLookup;
}

function rdoDeSegunda(): RdoDraft {
  const base = createEmptyRdo();
  return {
    ...base,
    id: "rdo-de-segunda",
    obraId: OBRA_ID,
    dataRdo: "2026-08-10",
    maoObra: [
      {
        ...createEmptyMaoObra(),
        localId: "mo-segunda",
        colaboradorId: "col-segunda",
        nomeColaborador: "Equipe de segunda",
        selected: true,
      },
    ],
    equipamentos: [
      {
        ...createEmptyEquipamento(),
        localId: "eq-segunda",
        assetId: "asset-re01",
        prefixo: "RE-01",
        descricao: "Retroescavadeira",
      },
    ],
  };
}

describe("equipe do RDO clonado", () => {
  it("sobrevive ao contexto de criação", () => {
    const clone = rascunhoClonadoDe(rdoDeSegunda(), () => crypto.randomUUID());

    const pronto = applyRdoCreationContext(clone, contexto());

    expect(pronto.maoObra.map((item) => item.colaboradorId)).toEqual([
      "col-segunda",
    ]);
  });

  /*
   * A frota vem junto com a equipe, e hoje sobrevive por omissão: o contexto
   * não tem herança de equipamentos para atropelá-la. Este teste existe para o
   * dia em que alguém escrever essa herança — o clone tem de continuar
   * mandando, pelo mesmo motivo da mão de obra.
   */
  it("a frota do clone também sobrevive ao contexto", () => {
    const clone = rascunhoClonadoDe(rdoDeSegunda(), () => crypto.randomUUID());

    const pronto = applyRdoCreationContext(clone, contexto());

    expect(pronto.equipamentos.map((item) => item.assetId)).toEqual([
      "asset-re01",
    ]);
    expect(pronto.equipamentos[0].prefixo).toBe("RE-01");
  });

  /*
   * O RDO anterior continua sendo declarado — ele é a cadeia do documento, e
   * não muda por causa de quem trabalhou. O que não vale mais é ele decidir a
   * equipe de um rascunho que já tem uma.
   */
  it("não deixa de declarar o RDO anterior por causa disso", () => {
    const clone = rascunhoClonadoDe(rdoDeSegunda(), () => crypto.randomUUID());

    const pronto = applyRdoCreationContext(clone, contexto());

    expect(pronto.previousRdoId).toBe("rdo-de-sexta");
    expect(pronto.numeroRdo).toBe("RDO-0018");
  });

  /*
   * O caminho normal: RDO criado do zero continua herdando a equipe do
   * anterior, que é o que poupa a digitação de todo dia.
   */
  it("continua herdando do anterior quando o rascunho está vazio", () => {
    const pronto = applyRdoCreationContext(createEmptyRdo(), contexto());

    expect(pronto.maoObra.map((item) => item.colaboradorId)).toEqual([
      "col-sexta",
    ]);
  });
});
