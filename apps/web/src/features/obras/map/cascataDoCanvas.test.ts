// @vitest-environment jsdom
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { afterEach, describe, expect, it } from "vitest";

/**
 * A cascata entre a nossa folha e a do renderizador.
 *
 * Este é o defeito que apagou o painel vetorial por semanas, e nenhum teste de
 * comportamento podia pegá-lo: o mapa montava, carregava o estilo, desenhava no
 * buffer e reportava-se pronto. O que falhava era CSS. O MapLibre carimba
 * `.maplibregl-map` no mesmo elemento que a nossa `.operational-map-canvas` e
 * declara `position: relative; overflow: hidden`. A folha dele entra por
 * `import()` dinâmico, na montagem — depois da nossa. Empatada a
 * especificidade, vence a última: o contêiner perdia o posicionamento
 * absoluto, colapsava para altura zero e recortava fora o canvas e os
 * controles. Nenhum erro, nenhuma mensagem, e a obra sem mapa.
 *
 * O teste reproduz a ordem real de carregamento e mede o resultado.
 */

const RAIZ = path.dirname(fileURLToPath(import.meta.url));

function lerCss(caminho: string): string {
  return readFileSync(path.resolve(RAIZ, caminho), "utf8");
}

const CSS_DO_PAINEL = lerCss("./OperationalMap.css");

/** O trecho da folha do renderizador que disputa o mesmo elemento. */
const CSS_DO_RENDERIZADOR = `
  .maplibregl-map { position: relative; overflow: hidden; -webkit-tap-highlight-color: rgb(0 0 0 / 0); }
  .mapboxgl-map { position: relative; overflow: hidden; }
  .maplibregl-canvas { position: absolute; left: 0; top: 0; }
`;

function montarPainel(): HTMLDivElement {
  document.documentElement.style.setProperty("--color-canvas", "#eef2f0");

  const nossa = document.createElement("style");
  nossa.textContent = CSS_DO_PAINEL;
  document.head.append(nossa);

  // Depois da nossa, como acontece na montagem: `import()` dinâmico do
  // `maplibre-gl.css` só resolve quando o painel vai abrir.
  const deles = document.createElement("style");
  deles.textContent = CSS_DO_RENDERIZADOR;
  document.head.append(deles);

  const secao = document.createElement("section");
  secao.className = "operational-map";
  const wrap = document.createElement("div");
  wrap.className = "operational-map-canvas-wrap";
  const container = document.createElement("div");
  container.className = "operational-map-canvas";
  // O renderizador carimba a própria classe no contêiner que recebe.
  container.classList.add("maplibregl-map");

  wrap.append(container);
  secao.append(wrap);
  document.body.append(secao);
  return container;
}

afterEach(() => {
  document.head.replaceChildren();
  document.body.replaceChildren();
});

describe("cascata do contêiner do mapa", () => {
  it("mantém o contêiner absoluto mesmo com a folha do renderizador por cima", () => {
    const container = montarPainel();

    expect(getComputedStyle(container).position).toBe("absolute");
  });

  it("mantém caixa própria, para o overflow do renderizador não recortar o canvas", () => {
    // `overflow: hidden` sobre uma caixa de altura zero é o que apagava o
    // canvas e os controles sem emitir erro nenhum.
    const container = montarPainel();
    const estilo = getComputedStyle(container);

    expect(estilo.height).toBe("100%");
    expect(estilo.width).toBe("100%");
  });

  it("o mesmo vale para o Mapbox, que carimba a própria classe no contêiner", () => {
    const container = montarPainel();
    container.classList.remove("maplibregl-map");
    container.classList.add("mapboxgl-map");

    expect(getComputedStyle(container).position).toBe("absolute");
  });
});
