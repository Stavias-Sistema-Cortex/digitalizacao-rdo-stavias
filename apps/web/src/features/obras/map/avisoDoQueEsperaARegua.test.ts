import { describe, expect, it } from "vitest";

import { avisoDoQueEsperaARegua } from "./avisoDoQueEsperaARegua";
import type { ApontamentosSemLinha } from "./obraMapApi";

function aviso(
  parcial: Partial<ApontamentosSemLinha> = {},
): ApontamentosSemLinha {
  return {
    motivo: "SEM_EIXO",
    total: 1,
    kmInicial: 200,
    kmFinal: 202,
    primeiraData: "2026-08-08",
    ultimaData: "2026-08-08",
    eixoKmInicial: null,
    eixoKmFinal: null,
    ...parcial,
  };
}

/*
 * O caso que motivou a mensagem: o RDO declarou o trecho interditado do km 200
 * ao 202, a obra não tem eixo, e o mapa abria vazio sem dizer nada. Quem
 * apontou o quilômetro não tinha como saber se o dado estava errado, se a
 * sincronização falhara ou se faltava um cadastro.
 */
describe("o que espera a régua da obra", () => {
  it("sem eixo, diz o que está esperando e qual gesto resolve", () => {
    const texto = avisoDoQueEsperaARegua(aviso());

    expect(texto?.titulo).toContain("espera a régua");
    expect(texto?.detalhe).toContain("km 200 ao 202");
    expect(texto?.detalhe).toContain("eixo");
    expect(texto?.pedeOEixo).toBe(true);
  });

  /*
   * A data do RDO é a informação que faltava. O cabeçalho da tela só sabia
   * dizer quando o mapa foi lido — dia 12 —, e o trabalho era do dia 8; a
   * diferença parecia contradição e não estava escrita em lugar nenhum.
   */
  it("nomeia a data do RDO, não a data em que o mapa foi lido", () => {
    expect(avisoDoQueEsperaARegua(aviso())?.detalhe).toContain(
      "do RDO de 08/08/2026",
    );
  });

  it("mais de um dia vira intervalo, um dia só não", () => {
    expect(
      avisoDoQueEsperaARegua(
        aviso({ total: 3, ultimaData: "2026-08-10" }),
      )?.detalhe,
    ).toContain("de RDOs entre 08/08/2026 e 10/08/2026");

    expect(avisoDoQueEsperaARegua(aviso())?.detalhe).not.toContain("entre");
  });

  /*
   * O segundo silêncio, tão mudo quanto o primeiro: a régua existe, mas não
   * alcança o trecho apontado. O gesto aqui é outro — estender o eixo ou
   * corrigir o km —, então a frase não pode ser a mesma.
   */
  it("fora do eixo, mostra as duas faixas e não pede cadastro", () => {
    const texto = avisoDoQueEsperaARegua(
      aviso({
        motivo: "FORA_DO_EIXO",
        eixoKmInicial: 206.822,
        eixoKmFinal: 214.5,
      }),
    );

    expect(texto?.titulo).toContain("fora do eixo");
    expect(texto?.detalhe).toContain("km 200 ao 202");
    expect(texto?.detalhe).toContain("km 206,822 ao 214,5");
    expect(texto?.pedeOEixo).toBe(false);
  });

  /* O verbo acompanha: "espera/esperam", "ficou/ficaram". */
  it("um trecho só é falado no singular, vários no plural", () => {
    expect(avisoDoQueEsperaARegua(aviso())?.titulo).toContain(
      "1 trecho apontado por quilômetro espera a régua",
    );
    expect(avisoDoQueEsperaARegua(aviso({ total: 4 }))?.titulo).toContain(
      "4 trechos apontados por quilômetro esperam a régua",
    );
    expect(
      avisoDoQueEsperaARegua(
        aviso({ motivo: "FORA_DO_EIXO", total: 2, eixoKmInicial: 100, eixoKmFinal: 110 }),
      )?.titulo,
    ).toContain("2 trechos apontados por quilômetro ficaram fora");
  });

  /* Sem nada esperando, não há aviso: mapa cheio não precisa de desculpa. */
  it("cala quando tudo virou linha", () => {
    expect(avisoDoQueEsperaARegua(undefined)).toBeNull();
    expect(avisoDoQueEsperaARegua(aviso({ total: 0 }))).toBeNull();
  });
});
