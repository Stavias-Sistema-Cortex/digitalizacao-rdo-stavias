import { describe, expect, it } from "vitest";

import {
  createEmptyAlocacaoColaborador,
  createEmptyEquipamento,
  createEmptyMaoObra,
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "./createEmptyRdo";

describe("RDO sem defaults operacionais inventados", () => {
  it("inicia a identificação e as condições como escolhas explícitas", () => {
    const draft = createEmptyRdo();

    expect(draft).toMatchObject({
      obraId: "",
      dataRdo: "",
      turno: "",
      horaInicio: "",
      horaFim: "",
      condicaoNoite: "",
      pluviometriaMm: "",
      creationContextVersion: null,
    });
    expect(draft.servicosExecutados).toEqual([]);
    expect(draft.alocacoesColaboradores).toEqual([]);
    expect(draft.maoObra).toEqual([]);
    expect(draft.equipamentos).toEqual([]);
    expect(draft.materiais).toEqual([]);
    expect(draft.controlesGeometricos).toEqual([]);
  });

  it("também deixa quantidade, vínculo e status vazios ao adicionar uma linha", () => {
    expect(createEmptyMaoObra()).toMatchObject({
      tipoVinculo: "",
      quantidade: "",
      horaInicio: "",
      horaFim: "",
      selected: true,
      sourceRdoId: "",
      origin: "MANUAL",
    });
    expect(createEmptyEquipamento()).toMatchObject({
      tipoVinculo: "",
      quantidade: "",
    });
    expect(createEmptyAlocacaoColaborador()).toMatchObject({
      horaInicio: "",
      horaFim: "",
      percentualDia: "",
      tipoAlocacao: "",
      fonte: "",
      status: "",
    });
    expect(createEmptyServicoExecutado()).toMatchObject({
      statusValidacao: "",
      quantidadeExecutada: "",
    });
  });
});
