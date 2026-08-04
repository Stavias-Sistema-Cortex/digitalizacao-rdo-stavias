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

  /**
   * Só o ponto operacional sai por aqui. Um trecho desenhado pertence ao RDO
   * do dia e sai junto com ele; oferecer a lixeira na linha do trecho abriria
   * um segundo jeito de apagar o mesmo trabalho, por fora do apontamento.
   */
  it("não oferece lixeira ao trecho nem à obra", () => {
    const aoRemover = vi.fn();

    expect(
      lixeira(
        popupElement(
          { categoria: "TRECHO", geometriaId: "geo-1" },
          aoRemover,
        ),
      ),
    ).toBeNull();
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
