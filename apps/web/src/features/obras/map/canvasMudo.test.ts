// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";

import { canvasEstaMudo } from "./mapAdapter";

/**
 * O canvas do renderizador é WebGL e o jsdom não desenha; o que se testa aqui
 * é a leitura, com o contexto 2D da amostra devolvendo pixels controlados.
 */
function canvasComPixels(pixels: number[], largura = 600, altura = 400) {
  const amostra = {
    width: 0,
    height: 0,
    getContext: () => ({
      drawImage: () => undefined,
      getImageData: () => ({ data: Uint8ClampedArray.from(pixels) }),
    }),
  };
  vi.spyOn(document, "createElement").mockReturnValueOnce(
    amostra as unknown as HTMLElement,
  );
  return { width: largura, height: altura } as HTMLCanvasElement;
}

describe("canvasEstaMudo", () => {
  it("acusa o retângulo de uma cor só", () => {
    const uniforme = Array.from({ length: 12 * 12 * 4 }, (_, i) =>
      [240, 242, 238, 255][i % 4],
    );
    expect(canvasEstaMudo(canvasComPixels(uniforme))).toBe(true);
  });

  it("não acusa o canvas que tem imagem", () => {
    const comImagem = Array.from({ length: 12 * 12 * 4 }, (_, i) =>
      [240, 242, 238, 255][i % 4],
    );
    comImagem[40] = 12;
    expect(canvasEstaMudo(canvasComPixels(comImagem))).toBe(false);
  });

  it("acusa o canvas sem superfície nenhuma", () => {
    expect(canvasEstaMudo({ width: 0, height: 0 } as HTMLCanvasElement)).toBe(
      true,
    );
  });

  it("não acusa quando a medição em si não pôde ser feita", () => {
    // Uma leitura que falhou não é prova de mapa vazio, e derrubar um mapa
    // possivelmente inteiro por causa dela seria pior do que não medir.
    vi.spyOn(document, "createElement").mockImplementationOnce(() => {
      throw new Error("sem canvas");
    });
    expect(
      canvasEstaMudo({ width: 600, height: 400 } as HTMLCanvasElement),
    ).toBe(false);
  });
});
