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

  /**
   * Quem esticou a trena em campo mediu melhor do que a linha traçada por
   * cima do mapa. Ignorar o campo preenchido gravaria a estimativa e jogaria
   * fora a medição.
   */
  it("deixa a extensão medida em campo prevalecer sobre a da linha", () => {
    expect(
      propriedadesDaFormaDesenhada(cadastro({ extensaoM: "1500" }), 1_234),
    ).toMatchObject({ extensaoM: 1_500 });
    expect(
      propriedadesDaFormaDesenhada(cadastro({ extensaoM: "abc" }), 1_234),
    ).toMatchObject({ extensaoM: 1_234 });
  });

  /**
   * O balão do mapa precisa de um título, mas repetir o quilômetro nele
   * recriaria a divergência em prosa: corrigido o RDO, o rótulo continuaria
   * anunciando o km antigo.
   */
  it("rotula pela rodovia e pelo sentido, nunca pelo quilômetro", () => {
    expect(propriedadesDaFormaDesenhada(cadastro(), null).nome).toBe(
      "SP-310 · sentido NORTE",
    );
    expect(
      propriedadesDaFormaDesenhada(cadastro({ sentido: "" }), null).nome,
    ).toBe("SP-310");
    expect(propriedadesDaFormaDesenhada(cadastro(), null).nome).not.toContain(
      "172",
    );
  });
});

/**
 * O desenho não guarda o quilômetro, mas passa a dizer onde ele mora.
 *
 * <p>A geometria seguir sem km foi decisão deliberada: duas cópias divergem no
 * primeiro acerto de uma delas. Só que sem saber de qual linha falar, o mapa
 * também não tinha como <em>ler</em> o quilômetro do apontamento, e a
 * informação simplesmente não chegava à tela — o trecho aparecia desenhado sem
 * a primeira coisa que se pergunta olhando para uma rodovia.
 *
 * <p>O elo é o endereço, não o valor. Ele permite a leitura sem recriar a
 * divergência.
 */
describe("o elo entre o desenho e a linha do RDO", () => {
  it("registra qual execução o desenho representa", () => {
    const propriedades = propriedadesDaFormaDesenhada(
      cadastro(),
      null,
      "linha-abc",
    );

    expect(propriedades.execucaoId).toBe("linha-abc");
  });

  /*
   * O quilômetro continua fora: é ele que mora no apontamento, e gravá-lo aqui
   * seria refazer exatamente a divergência que a migração eliminou.
   */
  it("continua sem guardar o quilômetro", () => {
    const propriedades = propriedadesDaFormaDesenhada(
      cadastro({ kmInicial: "172", kmFinal: "171" }),
      null,
      "linha-abc",
    );

    expect(propriedades.kmInicial).toBeUndefined();
    expect(propriedades.kmFinal).toBeUndefined();
  });

  it("não inventa o elo quando ninguém o informou", () => {
    expect(propriedadesDaFormaDesenhada(cadastro(), null).execucaoId)
      .toBeUndefined();
    expect(propriedadesDaFormaDesenhada(cadastro(), null, "  ").execucaoId)
      .toBeUndefined();
  });
});
