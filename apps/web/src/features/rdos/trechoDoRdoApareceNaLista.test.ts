import { describe, expect, it } from "vitest";

import { trechosDoRdo, extensaoDoRdoM } from "./trechosDoRdo";
import type { LocalRdoRecord } from "../../lib/db/db.types";

/**
 * O RDO com quilômetro preenchido não pode aparecer com "Trechos 0".
 *
 * <p>A lista contava {@code controlesGeometricos}, a etapa que saiu do RDO. O
 * quilômetro passou a morar na linha de execução — decisão tomada justamente
 * para ele existir num lugar só — e ninguém trouxe a contagem junto. O
 * resultado é um cartão que diz "Trechos 0 · Extensão 0 m" sobre um RDO
 * sincronizado, com o trecho visível na tela de edição.
 *
 * <p>O bloco antigo continua sendo lido: RDO gravado antes da mudança ainda o
 * carrega, e o histórico não se reescreve por causa de uma troca de estrutura.
 */

function rdo(payload: Record<string, unknown>): LocalRdoRecord {
  return { payload } as LocalRdoRecord;
}

describe("trechos declarados pelo RDO", () => {
  it("conta a linha de execução que tem quilômetro", () => {
    const registro = rdo({
      servicosExecutados: [
        { servicoNome: "Fresagem", trechoInicial: "206,822", trechoFinal: "207,100" },
      ],
    });

    expect(trechosDoRdo(registro)).toHaveLength(1);
  });

  it("não conta a linha de serviço que não declara trecho", () => {
    const registro = rdo({
      servicosExecutados: [
        { servicoNome: "Limpeza", quantidadeExecutada: 3 },
      ],
    });

    expect(trechosDoRdo(registro)).toHaveLength(0);
  });

  /*
   * O quilômetro é digitado em campo, com vírgula. Lido como "206.822" pela
   * conversão antiga, ele viraria duzentos e seis mil metros de extensão.
   */
  it("mede a extensão lendo o quilômetro com vírgula", () => {
    const registro = rdo({
      servicosExecutados: [
        { trechoInicial: "206,822", trechoFinal: "207,100" },
      ],
    });

    expect(Math.round(extensaoDoRdoM(registro))).toBe(278);
  });

  /*
   * A pista Sul tem quilometragem decrescente: começa no 400 e termina no 398.
   * A subtração ingênua devolvia zero exatamente ali.
   */
  it("mede a pista de quilometragem decrescente", () => {
    const registro = rdo({
      servicosExecutados: [{ trechoInicial: "400", trechoFinal: "398" }],
    });

    expect(Math.round(extensaoDoRdoM(registro))).toBe(2000);
  });

  it("soma as várias linhas do mesmo RDO", () => {
    const registro = rdo({
      servicosExecutados: [
        { trechoInicial: "10", trechoFinal: "11" },
        { trechoInicial: "20", trechoFinal: "20,5" },
      ],
    });

    expect(Math.round(extensaoDoRdoM(registro))).toBe(1500);
  });

  /*
   * RDO gravado antes de o controle geométrico sair do formulário. O bloco
   * continua no payload, e continua valendo.
   */
  it("continua lendo o controle geométrico do RDO antigo", () => {
    const registro = rdo({
      controlesGeometricos: [
        { numero: "1", kmInicial: "100", kmFinal: "100,4" },
      ],
    });

    expect(trechosDoRdo(registro)).toHaveLength(1);
    expect(Math.round(extensaoDoRdoM(registro))).toBe(400);
  });

  it("prefere o comprimento já medido ao cálculo pelo quilômetro", () => {
    const registro = rdo({
      controlesGeometricos: [
        { kmInicial: "100", kmFinal: "101", comprimentoM: 850 },
      ],
    });

    expect(extensaoDoRdoM(registro)).toBe(850);
  });

  it("devolve zero para o RDO que não declara trecho nenhum", () => {
    expect(extensaoDoRdoM(rdo({}))).toBe(0);
    expect(trechosDoRdo(rdo({}))).toEqual([]);
  });
});
