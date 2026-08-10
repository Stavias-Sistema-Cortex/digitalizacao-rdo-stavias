// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";

import { popupElement } from "./popupDoMapa";

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
