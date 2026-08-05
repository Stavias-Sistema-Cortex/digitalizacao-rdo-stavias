import { describe, expect, it, vi } from "vitest";

import type { OperationalFeatureCollection } from "./mapGeometry";
import { rastreadorDeBalao } from "./balaoDoPainel";

function colecao(ids: string[]): OperationalFeatureCollection {
  return {
    type: "FeatureCollection",
    features: ids.map((id) => ({
      type: "Feature",
      id,
      geometry: { type: "Point", coordinates: [-47.5, -22.4] },
      properties: { geometriaId: id, categoria: "PONTO_OPERACIONAL" },
    })),
  } as unknown as OperationalFeatureCollection;
}

function balaoFalso() {
  return { remove: vi.fn() };
}

/**
 * O painel vetorial não fechava o balão sozinho.
 *
 * Remover um ponto apagava o círculo e deixava o balão ancorado no mesmo
 * pixel, com a lixeira dentro. Quem removeu lia isso como "o ponto continua no
 * mapa" e clicava de novo — e o segundo pedido caía num registro já encerrado,
 * que volta em silêncio. O laço não terminava.
 */
describe("rastreador de balão do painel vetorial", () => {
  it("fecha o balão quando a feição sai da leitura", () => {
    const rastreador = rastreadorDeBalao();
    const balao = balaoFalso();

    rastreador.abrir(balao, "ponto-1");
    rastreador.fecharSeSumiu(colecao(["ponto-2"]));

    expect(balao.remove).toHaveBeenCalledTimes(1);
  });

  it("mantém o balão da feição que continua no mapa", () => {
    const rastreador = rastreadorDeBalao();
    const balao = balaoFalso();

    rastreador.abrir(balao, "ponto-1");
    rastreador.fecharSeSumiu(colecao(["ponto-1", "ponto-2"]));

    expect(balao.remove).not.toHaveBeenCalled();
  });

  /** Dois balões abertos ao mesmo tempo deixariam um deles sem dono. */
  it("fecha o anterior ao abrir outro", () => {
    const rastreador = rastreadorDeBalao();
    const primeiro = balaoFalso();
    const segundo = balaoFalso();

    rastreador.abrir(primeiro, "ponto-1");
    rastreador.abrir(segundo, "ponto-2");

    expect(primeiro.remove).toHaveBeenCalledTimes(1);
    expect(segundo.remove).not.toHaveBeenCalled();
  });

  /**
   * Sem identidade não há como conferir contra a leitura nova; fechar por
   * engano o balão de uma feição que continua no mapa seria pior.
   */
  it("deixa aberto o balão sem identidade", () => {
    const rastreador = rastreadorDeBalao();
    const balao = balaoFalso();

    rastreador.abrir(balao, null);
    rastreador.fecharSeSumiu(colecao([]));

    expect(balao.remove).not.toHaveBeenCalled();
  });

  it("não tenta fechar duas vezes o mesmo balão", () => {
    const rastreador = rastreadorDeBalao();
    const balao = balaoFalso();

    rastreador.abrir(balao, "ponto-1");
    rastreador.fecharSeSumiu(colecao([]));
    rastreador.fecharSeSumiu(colecao([]));
    rastreador.fechar();

    expect(balao.remove).toHaveBeenCalledTimes(1);
  });
});
