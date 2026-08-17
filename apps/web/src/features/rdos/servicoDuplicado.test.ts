import { describe, expect, it } from "vitest";

import { createEmptyServicoExecutado } from "./createEmptyRdo";
import type { ServicoExecutadoDraft } from "./rdo.types";
import {
  duplicarServicoExecutado,
  servicoDuplicadoDe,
} from "./servicoDuplicado";

function fresagemNaCaixa(): ServicoExecutadoDraft {
  return {
    ...createEmptyServicoExecutado(),
    localId: "linha-1",
    serviceId: "cat-fresagem",
    priceVersionId: "preco-7",
    servicoNome: "FR-01 · Fresagem descontínua",
    itemContratualId: "item-3",
    unidade: "m3",
    quantidadeExecutada: 63,
    trechoInicial: "12+300",
    trechoFinal: "12+400",
    pista: "Sul",
    faixa: "2",
    localizacao: "Bordo direito",
    larguraM: 7,
    espessuraM: 0.09,
    turno: "NOTURNO",
    statusValidacao: "VALIDADA",
    retrabalho: true,
    producaoRejeitada: true,
    observacoes: "Fresagem refeita por falha do primeiro passe.",
  };
}

/** O lugar onde o serviço aconteceu: é o que a cópia existe para poupar. */
const O_LUGAR = [
  "trechoInicial",
  "trechoFinal",
  "pista",
  "faixa",
  "larguraM",
  "espessuraM",
  "localizacao",
  "turno",
] as const satisfies readonly (keyof ServicoExecutadoDraft)[];

/** O que foi feito ali: a linha nova responde por si. */
const O_QUE_FOI_FEITO = [
  "serviceId",
  "priceVersionId",
  "servicoNome",
  "itemContratualId",
  "unidade",
  "quantidadeExecutada",
  "statusValidacao",
  "retrabalho",
  "producaoRejeitada",
  "observacoes",
] as const satisfies readonly (keyof ServicoExecutadoDraft)[];

describe("servicoDuplicadoDe", () => {
  it("repete o trecho, a pista, a faixa e as medidas da caixa", () => {
    const original = fresagemNaCaixa();
    const copia = servicoDuplicadoDe(original, () => "linha-2");

    for (const campo of O_LUGAR) {
      expect(copia[campo], campo).toEqual(original[campo]);
    }
  });

  it("devolve em branco o serviço, a medição e o julgamento do dia", () => {
    const copia = servicoDuplicadoDe(fresagemNaCaixa(), () => "linha-2");
    const emBranco = createEmptyServicoExecutado();

    for (const campo of O_QUE_FOI_FEITO) {
      expect(copia[campo], campo).toEqual(emBranco[campo]);
    }
  });

  it("dá identidade própria à cópia", () => {
    const original = fresagemNaCaixa();
    const copia = servicoDuplicadoDe(original, () => "linha-2");

    expect(copia.localId).toBe("linha-2");
    expect(original.localId).toBe("linha-1");
  });

  /*
   * Sem esta conferência, um campo acrescentado ao rascunho entraria na cópia
   * pelo espalhamento sem que ninguém decidisse se ele descreve o lugar ou o
   * serviço — e o caso ruim é silencioso: o campo novo viaja para a linha nova
   * afirmando o que ela não afirmou. Falhar aqui é o pedido para escolher o
   * lado, não para reescrever a lista.
   */
  it("classifica todo campo do rascunho como lugar ou como serviço", () => {
    const decididos = new Set<string>([
      ...O_LUGAR,
      ...O_QUE_FOI_FEITO,
      "localId",
    ]);

    expect(
      Object.keys(fresagemNaCaixa()).filter((campo) => !decididos.has(campo)),
    ).toEqual([]);
  });
});

describe("duplicarServicoExecutado", () => {
  const primeiro = { ...fresagemNaCaixa(), localId: "a" };
  const segundo = { ...fresagemNaCaixa(), localId: "b" };

  it("põe a cópia logo abaixo do original", () => {
    const lista = duplicarServicoExecutado(
      [primeiro, segundo],
      "a",
      () => "copia",
    );

    expect(lista.map((item) => item.localId)).toEqual(["a", "copia", "b"]);
  });

  it("duplica o último sem perder a ordem", () => {
    const lista = duplicarServicoExecutado(
      [primeiro, segundo],
      "b",
      () => "copia",
    );

    expect(lista.map((item) => item.localId)).toEqual(["a", "b", "copia"]);
  });

  it("copia a linha pedida, e não a primeira da lista", () => {
    const outroTrecho = { ...segundo, trechoInicial: "20+000" };
    const lista = duplicarServicoExecutado(
      [primeiro, outroTrecho],
      "b",
      () => "copia",
    );

    expect(lista[2].trechoInicial).toBe("20+000");
  });

  it("devolve a mesma lista quando a linha não existe mais", () => {
    const lista = [primeiro, segundo];

    expect(duplicarServicoExecutado(lista, "sumiu", () => "copia")).toBe(lista);
  });
});
