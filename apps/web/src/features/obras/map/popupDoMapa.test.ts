// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";

import { popupElement } from "./popupDoMapa";

function lixeira(elemento: HTMLElement): HTMLButtonElement | null {
  return elemento.querySelector("button.mapa-balao-remover");
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
