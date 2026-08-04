import { describe, expect, it } from "vitest";

import {
  execucaoDoTrechoDesenhado,
  propriedadesDaFormaDesenhada,
} from "./trechoAlimentaORdo";
import type { CadastroTrecho } from "../trecho/trechoCadastrado";

function cadastro(values: Partial<CadastroTrecho> = {}): CadastroTrecho {
  return {
    rodovia: "SP-310",
    sentido: "NORTE",
    faixa: "DIREITA",
    kmInicial: "172",
    kmFinal: "171",
    extensaoM: "",
    status: "EM_EXECUCAO",
    ...values,
  };
}

/**
 * O quilômetro morava em dois lugares.
 *
 * As propriedades da geometria e a linha de execução do RDO declaravam o mesmo
 * trecho por caminhos separados, sem nada que os reconciliasse — havia até uma
 * função dedicada a comparar os dois e avisar quando discordavam, o que
 * administra o sintoma em vez de resolver a causa. Corrigir num lado deixava o
 * outro mentindo, e ninguém sabia qual ler.
 */
describe("o desenho alimenta o RDO", () => {
  it("leva o quilômetro do desenho para a linha de execução", () => {
    const execucao = execucaoDoTrechoDesenhado({
      cadastro: cadastro(),
      localId: "linha-1",
    });

    expect(execucao.trechoInicial).toBe("172");
    expect(execucao.trechoFinal).toBe("171");
    expect(execucao.faixa).toBe("DIREITA");
    expect(execucao.localizacao).toBe("SP-310");
  });

  /**
   * Quem desenha marca onde o trabalho aconteceu, não quanto foi medido nem
   * contra qual item ele será faturado. Preencher isso aqui faria números
   * inventados descerem para o Financeiro como se alguém os tivesse declarado.
   */
  it("não inventa quantidade nem item contratual", () => {
    const execucao = execucaoDoTrechoDesenhado({
      cadastro: cadastro(),
      localId: "linha-1",
    });

    expect(execucao.quantidadeExecutada).toBe("");
    expect(execucao.unidade).toBe("");
    expect(execucao.itemContratualId).toBe("");
    expect(execucao.serviceId).toBe("");
    expect(execucao.priceVersionId).toBe("");
  });

  /** Declarar não é validar: validar é ato de outra pessoa. */
  it("nasce registrada, nunca validada", () => {
    expect(
      execucaoDoTrechoDesenhado({ cadastro: cadastro(), localId: "l" })
        .statusValidacao,
    ).toBe("REGISTRADA");
  });

  it("usa o nome do serviço quando a tela sabe qual é", () => {
    expect(
      execucaoDoTrechoDesenhado({
        cadastro: cadastro(),
        localId: "l",
        servicoNome: "Fresagem",
      }).servicoNome,
    ).toBe("Fresagem");
    expect(
      execucaoDoTrechoDesenhado({ cadastro: cadastro(), localId: "l" })
        .servicoNome,
    ).toBe("Trecho desenhado no mapa");
  });

  /**
   * A geometria guarda a forma, e nada que o apontamento já afirme. Manter o
   * quilômetro aqui recriaria a segunda declaração que este trabalho existe
   * para eliminar.
   */
  it("tira o quilômetro das propriedades da geometria", () => {
    const propriedades = propriedadesDaFormaDesenhada(cadastro(), 1_000);

    expect(propriedades).not.toHaveProperty("kmInicial");
    expect(propriedades).not.toHaveProperty("kmFinal");
    expect(propriedades).toMatchObject({
      rodovia: "SP-310",
      sentido: "NORTE",
      faixa: "DIREITA",
      status: "EM_EXECUCAO",
    });
  });

  /** Extensão da linha descreve a própria geometria, então fica. */
  it("mantém a extensão medida na linha desenhada", () => {
    expect(propriedadesDaFormaDesenhada(cadastro(), 1_234)).toMatchObject({
      extensaoM: 1_234,
    });
    expect(propriedadesDaFormaDesenhada(cadastro(), null)).not.toHaveProperty(
      "extensaoM",
    );
  });
});
