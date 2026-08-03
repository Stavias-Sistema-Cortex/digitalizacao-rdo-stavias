import { describe, expect, it } from "vitest";

import {
  falhaDeBasemap,
  falhaImpedeOMapa,
  mensagemDeFalha,
} from "./falhasDoMapa";

const ESTILO = "https://api.maptiler.com/maps/satellite/style.json?key=segredo";

describe("falhaImpedeOMapa", () => {
  it("derruba o mapa quando o próprio estilo não chega", () => {
    expect(
      falhaImpedeOMapa(
        { message: "Failed to fetch (0)", url: ESTILO },
        ESTILO,
      ),
    ).toBe(true);
  });

  it("reconhece o estilo mesmo quando a URL só aparece na mensagem", () => {
    expect(
      falhaImpedeOMapa({ message: `AJAXError: 404: ${ESTILO}` }, ESTILO),
    ).toBe(true);
  });

  it("derruba o mapa quando o WebGL não inicia", () => {
    expect(
      falhaImpedeOMapa(
        { message: "Failed to initialize WebGL context" },
        ESTILO,
      ),
    ).toBe(true);
  });

  it("mantém o mapa de pé quando falta um ícone do sprite", () => {
    // Era isto que apagava o painel inteiro: um ícone ausente do estilo do
    // provider chega como `error` e não impede nada de ser desenhado.
    expect(
      falhaImpedeOMapa(
        { message: 'Image "office" could not be loaded.' },
        ESTILO,
      ),
    ).toBe(false);
  });

  it("mantém o mapa de pé quando um tile isolado falha", () => {
    expect(
      falhaImpedeOMapa(
        {
          message: "AJAXError: 404",
          url: "https://tiles.example.org/planet/14/1/2.pbf",
        },
        "https://tiles.example.org/styles/liberty",
      ),
    ).toBe(false);
  });
});

describe("mensagemDeFalha", () => {
  it("nunca devolve a URL do estilo, que carrega a chave do provider", () => {
    const mensagem = mensagemDeFalha(`Erro inesperado ao ler ${ESTILO}`);

    expect(mensagem).not.toContain("segredo");
    expect(mensagem).not.toContain("http");
  });

  it("explica a falta de WebGL em vez de despejar o contexto", () => {
    expect(mensagemDeFalha("WebGL context could not be created")).toContain(
      "aceleração gráfica",
    );
  });

  it("explica a falha de rede sem repetir o texto do runtime", () => {
    expect(mensagemDeFalha("Failed to fetch")).toContain("Sem rede");
  });

  it("devolve texto utilizável quando sobra apenas a URL", () => {
    expect(mensagemDeFalha(ESTILO)).toBe("Falha ao carregar o mapa.");
  });
});

describe("falhaDeBasemap", () => {
  const ESTILO_ABERTO = "https://tiles.openfreemap.org/styles/liberty";

  it("reconhece a fonte de tiles inalcançável — o retângulo mudo de campo", () => {
    // O caso reproduzido: o TileJSON da fonte falha, o estilo carrega, `load`
    // dispara e `areTilesLoaded()` responde verdadeiro. Sem esta classificação
    // o painel subia vazio e sem uma palavra.
    expect(
      falhaDeBasemap(
        {
          message: "AJAXError: Failed to fetch (0): https://tiles.openfreemap.org/planet",
          url: "https://tiles.openfreemap.org/planet",
        },
        ESTILO_ABERTO,
      ),
    ).toBe(true);
  });

  it("reconhece o servidor que responde e recusa a fonte", () => {
    // O host de mapa aberto nega sob carga em vez de cair. Some da tela do
    // mesmo jeito, e antes só a rede caída era classificada aqui.
    expect(
      falhaDeBasemap(
        {
          message: "Forbidden",
          status: 403,
          url: "https://tiles.openfreemap.org/planet",
        },
        ESTILO_ABERTO,
      ),
    ).toBe(true);
  });

  it("o estilo que não chega segue no caminho fatal, não na contingência", () => {
    expect(
      falhaDeBasemap(
        { message: "Failed to fetch (0)", url: ESTILO_ABERTO },
        ESTILO_ABERTO,
      ),
    ).toBe(false);
  });

  it("WebGL indisponível não é problema de basemap", () => {
    expect(
      falhaDeBasemap({ message: "Failed to initialize WebGL" }, ESTILO_ABERTO),
    ).toBe(false);
  });

  it("erro de conteúdo do estilo não aciona a contingência", () => {
    expect(
      falhaDeBasemap(
        { message: "Image \"marker\" could not be loaded" },
        ESTILO_ABERTO,
      ),
    ).toBe(false);
    expect(falhaDeBasemap(undefined, ESTILO_ABERTO)).toBe(false);
  });
});
