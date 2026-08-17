import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { MaisStaviasCard } from "./MaisStaviasCard";

/**
 * O card é uma lista de endereços, e endereço errado só aparece no clique.
 *
 * <p>Nenhum teste olhava para ele: os destinos viviam apenas no arquivo de
 * origem e na lista de linhas aprovadas do verificador de marca — que confere
 * que a linha existe, não que ela leva a algum lugar. Trocar um domínio
 * passava sem nada acusar, e o defeito só apareceria para quem clicasse.
 */
describe("MaisStaviasCard", () => {
  const markup = renderToStaticMarkup(<MaisStaviasCard />);

  it.each([
    ["Portal Stavias", "https://portalstavias.com.br/stavias_academy"],
    ["Stavias Academy", "https://portalstavias.com.br/stavias_academy"],
    ["Central de Suporte", "https://suporte.stavias.com.br"],
  ])("leva %s para %s", (rotulo, destino) => {
    expect(markup).toContain(`href="${destino}"`);
    expect(markup).toContain(`>${rotulo}</a>`);
  });

  /*
   * Os dois primeiros levam ao mesmo endereço hoje, e a lista é renderizada por
   * chave: pelo destino, o React descartaria a segunda linha como duplicada e o
   * card perderia um item sem que nada acusasse.
   */
  it("mantém um item por rótulo mesmo com destinos repetidos", () => {
    expect(markup.split("<li>")).toHaveLength(4);
  });

  it("abre fora sem entregar a aba de origem ao destino", () => {
    // `target="_blank"` sem `rel` deixaria o site aberto mexer nesta janela
    // por `window.opener`. São dois atributos que andam juntos, e o par é
    // fácil de desfazer sem perceber ao editar um link.
    for (const link of markup.split("<a ").slice(1)) {
      expect(link).toContain('target="_blank"');
      expect(link).toContain('rel="noreferrer"');
    }
  });
});
