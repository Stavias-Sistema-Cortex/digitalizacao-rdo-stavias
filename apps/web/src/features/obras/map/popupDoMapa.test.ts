// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";

import {
  geometriaPodeSairDoMapa,
  lixeiraDoBalao,
  popupElement,
  redesenhoDoBalao,
  trechoPodeSerRedesenhado,
} from "./popupDoMapa";

function lixeira(elemento: HTMLElement): HTMLButtonElement | null {
  return elemento.querySelector("button.mapa-balao-remover");
}

function lapis(elemento: HTMLElement): HTMLButtonElement | null {
  return elemento.querySelector("button.mapa-balao-redesenhar");
}

/**
 * A porta para encerrar um ponto operacional.
 *
 * A primeira tentativa foi uma lista de todos os pontos abaixo do mapa, e ela
 * não funcionava: todo ponto operacional se chama "Ponto operacional", então a
 * tela repetia o mesmo rótulo dezenas de vezes com uma data ao lado. Ninguém
 * conseguia dizer qual daquelas linhas era a marcação errada — e escolher a
 * marcação errada é exatamente a decisão que a tela existia para apoiar.
 *
 * A lixeira mora no balão do próprio ponto, que é onde se sabe qual é ele.
 */
describe("lixeira no balão do ponto", () => {
  it("chama de volta com o ponto que foi aberto", () => {
    const aoRemover = vi.fn();
    const balao = popupElement(
      { categoria: "PONTO_OPERACIONAL", geometriaId: "ponto-7" },
      aoRemover,
    );

    lixeira(balao)?.click();

    expect(aoRemover).toHaveBeenCalledWith("ponto-7");
  });

  /*
   * O trecho passou a sair por aqui. A justificativa antiga — "o desenho
   * pertence ao RDO e sai junto com ele" — não se sustentava: o quilômetro
   * mora só no apontamento, e a geometria guarda a forma. Apagar a linha não
   * apaga medida nenhuma, e sem essa saída quem desenhava torto ficava com a
   * linha errada no mapa para sempre.
   */
  it("oferece lixeira ao trecho desenhado", () => {
    const aoRemover = vi.fn();

    const botao = lixeira(
      popupElement({ categoria: "TRECHO", geometriaId: "geo-1" }, aoRemover),
    );

    expect(botao).not.toBeNull();
    expect(botao?.getAttribute("aria-label")).toBe("Remover trecho desenhado");
  });

  it("nomeia o ponto operacional pelo que ele é", () => {
    const aoRemover = vi.fn();

    const botao = lixeira(
      popupElement(
        { categoria: "PONTO_OPERACIONAL", geometriaId: "geo-2" },
        aoRemover,
      ),
    );

    expect(botao?.getAttribute("aria-label")).toBe("Remover ponto operacional");
  });

  /*
   * A localização da obra continua fora: ela não é geometria removível, é o
   * cadastro da obra.
   */
  it("não oferece lixeira à localização da obra", () => {
    const aoRemover = vi.fn();

    expect(
      lixeira(
        popupElement(
          { categoria: "LOCALIZACAO_OBRA", geometriaId: "obra-1" },
          aoRemover,
        ),
      ),
    ).toBeNull();
  });

  /** Sem quem atenda, a lixeira seria um botão que não faz nada. */
  it("não desenha lixeira quando ninguém pode encerrar", () => {
    expect(
      lixeira(
        popupElement(
          { categoria: "PONTO_OPERACIONAL", geometriaId: "p1" },
          null,
        ),
      ),
    ).toBeNull();
  });

  /**
   * Sem identidade não há o que encerrar: o pedido chegaria ao servidor sem
   * dizer qual geometria sai do mapa.
   */
  it("não desenha lixeira sem o id da geometria nas propriedades", () => {
    expect(
      lixeira(popupElement({ categoria: "PONTO_OPERACIONAL" }, vi.fn())),
    ).toBeNull();
  });

  it("mantém o que o balão já dizia sobre o ponto", () => {
    const balao = popupElement(
      { categoria: "PONTO_OPERACIONAL", geometriaId: "p1", nome: "Frente 3" },
      vi.fn(),
    );

    expect(balao.querySelector("strong")?.textContent).toBe("Frente 3");
  });
});

/**
 * O eixo também sai pelo balão, por decisão do dono do sistema.
 *
 * <p>Apagar a régua não toca em RDO nenhum: os quilômetros moram nos
 * apontamentos. O que some são as linhas derivadas — sem régua não há onde
 * projetá-las — e o cadastro do eixo volta a ser oferecido na barra.
 */
describe("lixeira no balão do eixo", () => {
  it("oferece lixeira ao eixo da obra, com o nome certo", () => {
    const botao = lixeira(
      popupElement(
        { categoria: "EIXO_OBRA", geometriaId: "eixo-1" },
        vi.fn(),
      ),
    );

    expect(botao).not.toBeNull();
    expect(botao?.getAttribute("aria-label")).toBe("Remover eixo da obra");
  });

  it("chama de volta com o eixo que foi aberto", () => {
    const aoRemover = vi.fn();
    const balao = popupElement(
      { categoria: "EIXO_OBRA", geometriaId: "eixo-7" },
      aoRemover,
    );

    lixeira(balao)?.click();

    expect(aoRemover).toHaveBeenCalledWith("eixo-7");
  });
});

/**
 * O balão do eixo tem de dizer o que uma régua tem a dizer.
 *
 * <p>Ele abria com a grafia do banco — "EIXO_OBRA" no título e "EIXO OBRA"
 * repetido embaixo — e sem quilômetro nenhum, porque o eixo grava o km como
 * número e o balão só lia texto. A única geometria com papel próprio era a
 * pior descrita do mapa.
 */
describe("o balão do eixo", () => {
  const propriedadesDoEixo = {
    categoria: "EIXO_OBRA",
    geometriaId: "eixo-1",
    kmInicial: 206.822,
    kmFinal: 214.5,
    rodovia: "SP-330",
    validoDesde: "2026-08-11T12:00:00",
    fonte: "GESTAO_MAPA",
  };

  it("abre com nome de gente, não com a grafia do banco", () => {
    const balao = popupElement(propriedadesDoEixo, null);

    expect(balao.querySelector("strong")?.textContent).toBe("Eixo da obra");
    expect(balao.textContent).not.toContain("EIXO_OBRA");
    expect(balao.textContent).not.toContain("EIXO OBRA");
  });

  it("mostra o quilômetro numérico em pt-BR e a rodovia", () => {
    const texto = popupElement(propriedadesDoEixo, null).textContent ?? "";

    expect(texto).toContain("km 206,822 ao 214,5");
    expect(texto).toContain("SP-330");
  });

  it("escreve a data como se lê, não como se grava", () => {
    const texto = popupElement(propriedadesDoEixo, null).textContent ?? "";

    expect(texto).toContain("desde 11/08/2026");
    expect(texto).not.toContain("2026-08-11");
  });

  it("não repete o título na linha de detalhes", () => {
    const balao = popupElement(propriedadesDoEixo, null);
    const detalhes = balao.querySelector("span")?.textContent ?? "";

    expect(detalhes).not.toContain("Eixo obra");
  });
});

/**
 * A porta para corrigir um traçado torto.
 *
 * <p>A lixeira resolvia a linha errada de um jeito só: jogando fora o desenho
 * inteiro. Quem errou um extremo por cinquenta metros tinha que apagar a linha
 * e refazê-la do zero — remarcando o extremo que já estava certo e descrevendo
 * de novo, por inteiro, exatamente o mesmo trabalho. O lápis mora ao lado da
 * lixeira, no balão do próprio trecho, que é onde se sabe qual linha é.
 */
describe("lápis no balão do trecho", () => {
  it("chama de volta com o trecho que foi aberto", () => {
    const aoRedesenhar = vi.fn();
    const balao = popupElement(
      { categoria: "TRECHO", geometriaId: "geo-9" },
      vi.fn(),
      aoRedesenhar,
    );

    lapis(balao)?.click();

    expect(aoRedesenhar).toHaveBeenCalledWith("geo-9");
  });

  /*
   * O ponto operacional é uma coordenada só: remarcá-la é remarcar a posição,
   * não redesenhar uma forma — e é a lixeira que dá conta disso.
   */
  it("não oferece lápis ao ponto operacional", () => {
    expect(
      lapis(
        popupElement(
          { categoria: "PONTO_OPERACIONAL", geometriaId: "p1" },
          vi.fn(),
          vi.fn(),
        ),
      ),
    ).toBeNull();
  });

  /*
   * Uma linha encerrada continua desenhada como histórico, e o servidor recusa
   * alterá-la. O lápis ali seria um botão que só sabe falhar.
   */
  it("não oferece lápis ao trecho que já saiu do mapa", () => {
    expect(
      lapis(
        popupElement(
          {
            categoria: "TRECHO",
            geometriaId: "geo-9",
            validoAte: "2026-08-01T00:00:00.000Z",
          },
          vi.fn(),
          vi.fn(),
        ),
      ),
    ).toBeNull();
  });

  it("não desenha lápis quando ninguém pode corrigir", () => {
    expect(
      lapis(popupElement({ categoria: "TRECHO", geometriaId: "geo-9" }, vi.fn())),
    ).toBeNull();
  });

  /*
   * Os dois convivem no mesmo balão: corrigir é o que quase sempre se quer
   * diante de uma linha errada, e apagar continua sendo a saída de quem não
   * quer o desenho de jeito nenhum.
   */
  it("convive com a lixeira no mesmo trecho", () => {
    const balao = popupElement(
      { categoria: "TRECHO", geometriaId: "geo-9" },
      vi.fn(),
      vi.fn(),
    );

    expect(lapis(balao)).not.toBeNull();
    expect(lixeira(balao)).not.toBeNull();
  });
});

/**
 * O quilômetro chega ao balão vindo do apontamento.
 *
 * <p>Ele não está gravado na geometria — o servidor o projeta na leitura, a
 * partir da linha de execução do RDO. Antes disso o trecho aparecia desenhado
 * no mapa sem dizer de que quilômetro a que quilômetro ele fala, que é a
 * primeira coisa que se pergunta olhando para uma rodovia.
 */
describe("quilômetro no balão do trecho", () => {
  it("mostra os dois extremos", () => {
    const balao = popupElement(
      {
        categoria: "TRECHO",
        geometriaId: "geo-1",
        kmInicial: "206,822",
        kmFinal: "207,100",
      },
      vi.fn(),
    );

    expect(balao.textContent).toContain("km 206,822 ao 207,100");
  });

  /*
   * O trecho pela metade continua valendo: esconder o único extremo conhecido
   * por não estar completo apagaria a referência que a linha tem.
   */
  it("mostra o extremo que existe quando só há um", () => {
    expect(
      popupElement(
        { categoria: "TRECHO", geometriaId: "geo-1", kmInicial: "206,822" },
        vi.fn(),
      ).textContent,
    ).toContain("a partir do km 206,822");

    expect(
      popupElement(
        { categoria: "TRECHO", geometriaId: "geo-1", kmFinal: "207,100" },
        vi.fn(),
      ).textContent,
    ).toContain("até o km 207,100");
  });

  it("não escreve nada sobre quilômetro quando não há nenhum", () => {
    const balao = popupElement(
      { categoria: "TRECHO", geometriaId: "geo-1", rodovia: "SP-330" },
      vi.fn(),
    );

    expect(balao.textContent).not.toContain("km");
  });
});

/*
 * A linha que o eixo derivou não é desenho: ela nasce na leitura, do
 * quilômetro que mora no apontamento. Apagá-la teria de significar apagar o
 * apontamento, e isso não cabe a um clique numa linha do mapa.
 */
describe("linha derivada do eixo", () => {
  const derivada = {
    categoria: "TRECHO",
    derivadoDoEixo: true,
    geometriaId: "eixo:execucao-1",
    execucaoId: "execucao-1",
    objetoTipo: "RDO",
    objetoId: "rdo-1",
  };

  it("não oferece lixeira", () => {
    expect(geometriaPodeSairDoMapa(derivada)).toBe(false);
    expect(lixeiraDoBalao(derivada, () => {})).toBeNull();
  });

  it("oferece o lápis, que corrige o quilômetro no RDO", () => {
    expect(trechoPodeSerRedesenhado(derivada)).toBe(true);
    const botao = redesenhoDoBalao(derivada, () => {});
    expect(botao?.title).toContain("quilômetro");
  });

  it("sem a linha de serviço, o lápis não teria o que reescrever", () => {
    const semExecucao = { ...derivada, execucaoId: "" };
    expect(trechoPodeSerRedesenhado(semExecucao)).toBe(false);
    expect(redesenhoDoBalao(semExecucao, () => {})).toBeNull();
  });

  it("o trecho desenhado à mão continua com os dois", () => {
    const desenhado = {
      categoria: "TRECHO",
      geometriaId: "geo-1",
      objetoTipo: "RDO",
      objetoId: "rdo-1",
    };

    expect(geometriaPodeSairDoMapa(desenhado)).toBe(true);
    expect(trechoPodeSerRedesenhado(desenhado)).toBe(true);
  });
});
