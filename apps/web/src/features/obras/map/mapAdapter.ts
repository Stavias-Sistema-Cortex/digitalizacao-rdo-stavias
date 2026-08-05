import {
  falhaDeBasemap,
  falhaImpedeOMapa,
  mensagemDeFalha,
  type FalhaDoRenderizador,
} from "./falhasDoMapa";
import {
  categoryColorExpression,
  corDoToken,
  rotuloDaFonte,
} from "./mapCategories";
import { limitesDaColecao, type OperationalFeatureCollection } from "./mapGeometry";
import {
  FASES_EM_ORDEM,
  PROPRIEDADE_FASE,
  TOKEN_POR_FASE,
} from "./execucaoDoTrecho";
import { mapboxAccessToken, type MapProvider } from "./mapProvider";
import { lixeiraDoBalao } from "./popupDoMapa";
import {
  idDaFeicao,
  rastreadorDeBalao,
  type RastreadorDeBalao,
} from "./balaoDoPainel";

import type { CameraDaObra } from "./cameraDaObra";

export type MapViewMode = "2d" | "3d";

export interface OperationalMapController {
  centerOn: (longitude: number, latitude: number) => void;
  setViewMode: (mode: MapViewMode) => void;
  /**
   * Troca as geometrias exibidas sem recriar o mapa.
   *
   * Cada rodada de sincronização relê as camadas da obra. Reconstruir o
   * renderizador a cada leitura fazia o painel recomeçar do zero antes de
   * terminar de abrir, e em campo ele nunca chegava a pintar.
   */
  setFeatures: (features: OperationalFeatureCollection) => void;
  /** Impõe o enquadramento vindo do outro mapa, sem reemitir movimento. */
  applyCamera: (camera: CameraDaObra) => void;
  /** Avisa a cada parada de movimento; devolve o cancelamento da inscrição. */
  onCameraChange: (
    listener: (camera: CameraDaObra) => void,
  ) => () => void;
  destroy: () => void;
}

interface MountOptions {
  container: HTMLDivElement;
  provider: MapProvider;
  features: OperationalFeatureCollection;
  center: [number, number];
  mode: MapViewMode;
  onRuntimeError: (message: string) => void;
  /**
   * O mapa não chegou a pintar. Disparado no máximo uma vez por montagem, para
   * que o painel possa trocar para o basemap embutido em vez de exibir um
   * retângulo vazio.
   */
  onFalhaDeBasemap?: () => void;
  /** Pedido de remoção do ponto operacional aberto no balão. */
  onRemoverPonto?: ((id: string) => void) | null;
}

const SOURCE_ID = "cortex-operational";
/** Espera pelo estilo do provider — um JSON, não o mapa inteiro. */
const LIMITE_ESTILO_MS = 25_000;
/** Janela para o primeiro tile chegar depois que o estilo já está de pé. */
const LIMITE_TILES_MS = 12_000;
/** Lado da amostra lida do canvas para saber se algo foi pintado. */
const LADO_DA_AMOSTRA = 12;
/**
 * Falhas de fonte que separam o tile perdido da fonte que não serve.
 *
 * Um 404 solto numa borda de zoom é rotina e não justifica trocar o basemap;
 * a repetição, sim — é a rede da obra retendo a malha inteira.
 */
const FALHAS_ATE_TROCAR_A_FONTE = 3;

/**
 * Lê o canvas e diz se ele é um retângulo de uma cor só.
 *
 * É a única pergunta que o renderizador nunca responde. `load`, `idle` e
 * `areTilesLoaded()` descrevem o que ele *tentou* fazer, e todos os três
 * respondem afirmativamente quando uma fonte inteira falhou — fonte quebrada
 * não deixa tile pendente. O pixel não tem essa complacência: ou tem imagem,
 * ou é o retângulo mudo que aparece na tela do operador.
 *
 * A amostra é minúscula e a leitura acontece uma vez por montagem. Qualquer
 * problema na leitura devolve `false`: uma medição que não deu certo não é
 * motivo para derrubar um mapa que talvez esteja inteiro.
 */
export function canvasEstaMudo(canvas: HTMLCanvasElement): boolean {
  try {
    if (canvas.width === 0 || canvas.height === 0) {
      return true;
    }
    const amostra = document.createElement("canvas");
    amostra.width = LADO_DA_AMOSTRA;
    amostra.height = LADO_DA_AMOSTRA;
    const contexto = amostra.getContext("2d", { willReadFrequently: true });
    if (!contexto) {
      return false;
    }
    contexto.drawImage(canvas, 0, 0, LADO_DA_AMOSTRA, LADO_DA_AMOSTRA);
    const { data } = contexto.getImageData(
      0,
      0,
      LADO_DA_AMOSTRA,
      LADO_DA_AMOSTRA,
    );
    for (let i = 4; i < data.length; i += 4) {
      if (
        data[i] !== data[0] ||
        data[i + 1] !== data[1] ||
        data[i + 2] !== data[2] ||
        data[i + 3] !== data[3]
      ) {
        return false;
      }
    }
    return true;
  } catch {
    return false;
  }
}

/**
 * A espessura acompanha o zoom para que o trecho continue legível de longe sem
 * cobrir a pista quando a câmera se aproxima.
 */
const PARADAS_DE_LARGURA: readonly (readonly [number, number])[] = [
  [10, 2],
  [14, 5],
  [18, 12],
];
/** O contorno é a mesma rampa engrossada; o fator mantém a proporção. */
const FATOR_DO_CASING = 1.8;

/*
 * O nome do serviço, procurado nas chaves que os registros realmente usam.
 *
 * As propriedades da geometria são livres: a captura de campo e a gestão do
 * mapa gravaram chaves diferentes conforme a época. `coalesce` atravessa as
 * conhecidas na mesma ordem da leitura do popup, e cai em texto vazio quando
 * ninguém disse qual serviço é — rótulo vazio não é desenhado.
 */
export const ROTULO_DO_SERVICO = [
  "coalesce",
  ["get", "servico"],
  ["get", "descricao"],
  ["get", "nome"],
  "",
];

/** De onde o traçado passa a caber com texto legível por cima. */
const ZOOM_MINIMO_DOS_ROTULOS = 14;

/**
 * A pilha de fontes que o estilo do provider realmente serve.
 *
 * Camada de texto sem `text-font` herda o padrão da especificação — "Open Sans
 * Regular, Arial Unicode MS Regular" —, e nenhum provider é obrigado a servir
 * essa pilha. O nosso não serve: cada caractere do rótulo virava um 404 no
 * endpoint de glyphs, um por codepoint, e o renderizador caía no desenho local
 * caractere a caractere. Reaproveitar a fonte de uma camada de símbolo que o
 * próprio estilo já usa garante que o `.pbf` pedido é um que existe.
 *
 * Devolve `null` quando o estilo não tem símbolo nenhum — estilo sem rótulo é
 * estilo sem glyphs servidos, e aí a camada de texto não deve ser criada.
 */
export function fonteDeTextoDoEstilo(
  layers: readonly EstiloDeCamada[] | undefined,
): string[] | null {
  for (const layer of layers ?? []) {
    if (layer?.type !== "symbol") {
      continue;
    }
    const fonte = layer.layout?.["text-font"];
    if (
      Array.isArray(fonte) &&
      fonte.length > 0 &&
      fonte.every((nome) => typeof nome === "string" && nome.trim())
    ) {
      return fonte as string[];
    }
  }
  return null;
}

/*
 * Ritmo do tracejado do trecho encerrado.
 *
 * O `line-dasharray` do MapLibre é medido em múltiplos da largura da linha, não
 * em pixels: com um valor fixo, o traço bem desenhado de longe vira um
 * pontilhado gigante quando a linha engrossa na aproximação. Encurtar os
 * múltiplos conforme o zoom sobe mantém o traço com o mesmo ritmo na tela em
 * qualquer distância. É a única propriedade da linha que não aceita expressão
 * por dado — mas aceita `step` por zoom, que é o que basta aqui.
 */
export const DASH_POR_ZOOM = [
  "step",
  ["zoom"],
  ["literal", [3.4, 2.2]],
  14,
  ["literal", [2.6, 1.8]],
  17,
  ["literal", [2, 1.4]],
];

/**
 * Cor da linha: fase de execução quando a feição tem uma, cor da categoria
 * quando não tem. O trecho cresce um pouco por dia, e é a fase que faz o mapa
 * contar isso — o avanço de hoje em amarelo vivo, a semana em amarelo
 * queimado, o consolidado em teal. Perímetros e demais linhas sem fase seguem
 * lidos pela categoria, como sempre.
 */
export function corDaLinhaExpression(): unknown[] {
  return [
    "case",
    ["has", PROPRIEDADE_FASE],
    [
      "match",
      ["get", PROPRIEDADE_FASE],
      ...FASES_EM_ORDEM.flatMap((fase) => [
        fase,
        corDoToken(TOKEN_POR_FASE[fase]),
      ]),
      corDoToken(TOKEN_POR_FASE.CONSOLIDADA),
    ],
    categoryColorExpression(),
  ];
}

/**
 * Fator de espessura por fase: o avanço de hoje é mais grosso que o
 * consolidado, para quem abre o mapa no fim do dia achar a jornada com o olho.
 */
function fatorDaFaseExpression(): unknown[] {
  return [
    "case",
    ["==", ["get", PROPRIEDADE_FASE], "HOJE"],
    1.6,
    ["==", ["get", PROPRIEDADE_FASE], "RECENTE"],
    1.25,
    ["==", ["get", PROPRIEDADE_FASE], "ENCERRADA"],
    0.6,
    1,
  ];
}

/**
 * Espessura da linha: rampa de zoom com o fator de fase aplicado em cada parada.
 *
 * A ordem importa e não é estética. O MapLibre só aceita `["zoom"]` como
 * entrada direta de um `step`/`interpolate` no topo da expressão; multiplicar a
 * rampa inteira pelo fator (`["*", rampa, fator]`) enterra o zoom dentro de um
 * operador e o estilo é recusado na hora de somar a camada — foi o que apagou o
 * tracejado dos encerrados e acendeu o aviso de erro sobre o mapa. Aplicando o
 * fator em cada parada, o zoom continua no topo e a hierarquia sobrevive em
 * qualquer aproximação.
 */
export function larguraDaLinhaExpression(fatorExtra = 1): unknown[] {
  const fase = fatorDaFaseExpression();
  return [
    "interpolate",
    ["linear"],
    ["zoom"],
    ...PARADAS_DE_LARGURA.flatMap(([zoom, largura]) => [
      zoom,
      ["*", largura * fatorExtra, fase],
    ]),
  ];
}

/**
 * Enquadra a câmera na extensão real das geometrias da obra.
 *
 * Centrar num ponto e arbitrar um zoom deixa o trecho fora da tela sempre que
 * ele é longo; o enquadramento pela extensão mostra a obra inteira e só ela.
 * Uma obra representada por um único ponto não tem extensão, e aí o centro
 * informado continua valendo — daí o retorno, que diz se houve enquadramento.
 */
function enquadrar(
  map: GlMapaOperacional,
  features: OperationalFeatureCollection,
): boolean {
  const limites = limitesDaColecao(features);
  if (!limites || (limites.oeste === limites.leste && limites.sul === limites.norte)) {
    return false;
  }
  map.fitBounds(
    [
      [limites.oeste, limites.sul],
      [limites.leste, limites.norte],
    ],
    { padding: 48, maxZoom: 17, duration: 0 },
  );
  return true;
}

/**
 * Detalhe da camada clicada.
 *
 * Mostra o que identifica a atividade no campo — serviço, RDO e data — antes de
 * qualquer metadado técnico, e omite silenciosamente o que a geometria não
 * registrou em vez de imprimir rótulos vazios.
 */
function popupContent(
  properties: Record<string, unknown>,
  aoRemoverPonto: ((id: string) => void) | null,
): HTMLDivElement {
  const container = document.createElement("div");
  container.className = "operational-map-popup mapa-balao";

  const title = document.createElement("strong");
  title.textContent = String(
    properties.nome ??
      properties.servicoNome ??
      properties.categoria ??
      "Elemento operacional",
  );

  const detail = document.createElement("span");
  detail.textContent = [
    properties.numeroRdo ? `RDO ${String(properties.numeroRdo)}` : null,
    properties.data ?? properties.validoDesde
      ? String(properties.data ?? properties.validoDesde).slice(0, 10)
      : null,
    properties.categoria ? String(properties.categoria).replaceAll("_", " ") : null,
  ]
    .filter(Boolean)
    .map(String)
    .join(" · ");

  container.append(title, detail);

  if (properties.fonte) {
    const origem = document.createElement("small");
    origem.textContent = `Origem: ${rotuloDaFonte(properties.fonte)}`;
    container.append(origem);
  }
  // A mesma lixeira do painel Leaflet, pela mesma regra: apagar o ponto num
  // mapa apaga nos dois, porque os dois leem a mesma geometria.
  const lixeira = lixeiraDoBalao(properties, aoRemoverPonto);
  if (lixeira) container.append(lixeira);
  return container;
}

/**
 * O subconjunto de API usado pelas camadas operacionais é idêntico no MapLibre
 * e no Mapbox; estes contratos estruturais mínimos permitem um único ciclo de
 * montagem para os dois runtimes. O popup e o volume das edificações são as
 * únicas diferenças reais e ficam a cargo de quem monta.
 */
interface GlEventoDeCamada {
  features?: { properties?: Record<string, unknown> | null }[];
  lngLat: { lng: number; lat: number };
}

interface GlFonteGeoJson {
  setData(data: unknown): unknown;
}

interface GlMapaOperacional {
  addSource(id: string, source: never): unknown;
  addLayer(layer: never): unknown;
  getSource(id: string): GlFonteGeoJson | undefined;
  on(
    type: string,
    layerId: string,
    listener: (event: GlEventoDeCamada) => void,
  ): unknown;
  on(type: string, listener: (event: { error?: FalhaDoRenderizador }) => void): unknown;
  once(type: string, listener: () => void): unknown;
  getCanvas(): HTMLCanvasElement;
  resize(): unknown;
  remove(): unknown;
  areTilesLoaded(): boolean;
  easeTo(options: {
    center?: [number, number];
    zoom?: number;
    pitch?: number;
    bearing?: number;
  }): unknown;
  jumpTo(options: { center?: [number, number]; zoom?: number }): unknown;
  getCenter(): { lng: number; lat: number };
  getZoom(): number;
  off(type: string, listener: (...args: never[]) => void): unknown;
  fitBounds(
    bounds: [[number, number], [number, number]],
    options: { padding: number; maxZoom: number; duration: number },
  ): unknown;
  getStyle?(): { layers?: readonly EstiloDeCamada[] } | undefined;
}

interface EstiloDeCamada {
  type?: string;
  layout?: Record<string, unknown>;
}

interface GlPopupCompativel {
  setLngLat(lngLat: { lng: number; lat: number }): GlPopupCompativel;
  setDOMContent(node: Node): GlPopupCompativel;
  addTo(map: never): unknown;
  remove?(): unknown;
}

function addOperationalLayers(
  map: GlMapaOperacional,
  features: OperationalFeatureCollection,
  criarPopup: () => GlPopupCompativel,
  aoRemoverPonto: ((id: string) => void) | null,
): RastreadorDeBalao {
  map.addSource(SOURCE_ID, {
    type: "geojson",
    data: features,
  } as never);
  map.addLayer({
    id: "cortex-polygons",
    type: "fill",
    source: SOURCE_ID,
    filter: ["==", ["geometry-type"], "Polygon"],
    paint: {
      "fill-color": categoryColorExpression(),
      "fill-opacity": 0.28,
    },
  } as never);
  /*
   * Contorno escuro sob a linha colorida: é o que faz o trecho ler como uma
   * pista sobre a imagem, em vez de um risco solto por cima do mapa.
   *
   * Os encerrados ficam de fora. O contorno é sólido e mais largo que a linha,
   * então sob um traço interrompido ele reaparecia justamente nos vãos: o
   * tracejado virava uma linha escura contínua com marcas claras por cima, que
   * é o oposto do que "encerrado" precisa comunicar.
   */
  map.addLayer({
    id: "cortex-lines-casing",
    type: "line",
    source: SOURCE_ID,
    filter: [
      "all",
      ["in", ["geometry-type"], ["literal", ["LineString", "Polygon"]]],
      ["!=", ["get", PROPRIEDADE_FASE], "ENCERRADA"],
    ],
    layout: { "line-cap": "round", "line-join": "round" },
    paint: {
      "line-color": corDoToken("--color-ink"),
      "line-width": larguraDaLinhaExpression(FATOR_DO_CASING),
      "line-opacity": 0.85,
    },
  } as never);
  /*
   * Duas camadas de linha porque o tracejado do MapLibre não é dirigível por
   * dado: encerrado é tracejado, o resto é sólido, e a divisão acontece no
   * filtro. As duas leem cor e largura da mesma expressão de fase.
   */
  map.addLayer({
    id: "cortex-lines",
    type: "line",
    source: SOURCE_ID,
    filter: [
      "all",
      ["in", ["geometry-type"], ["literal", ["LineString", "Polygon"]]],
      ["!=", ["get", PROPRIEDADE_FASE], "ENCERRADA"],
    ],
    layout: { "line-cap": "round", "line-join": "round" },
    paint: {
      "line-color": corDaLinhaExpression(),
      "line-width": larguraDaLinhaExpression(),
      "line-opacity": 0.95,
    },
  } as never);
  map.addLayer({
    id: "cortex-lines-encerradas",
    type: "line",
    source: SOURCE_ID,
    filter: [
      "all",
      ["in", ["geometry-type"], ["literal", ["LineString", "Polygon"]]],
      ["==", ["get", PROPRIEDADE_FASE], "ENCERRADA"],
    ],
    // Ponta reta: a ponta arredondada acrescenta meio círculo em cada extremo
    // do traço, e num tracejado isso engorda a marca até os vãos fecharem.
    layout: { "line-cap": "butt", "line-join": "round" },
    paint: {
      "line-color": corDoToken(TOKEN_POR_FASE.ENCERRADA),
      "line-width": larguraDaLinhaExpression(),
      "line-opacity": 0.9,
      "line-dasharray": DASH_POR_ZOOM,
    },
  } as never);
  map.addLayer({
    id: "cortex-points",
    type: "circle",
    source: SOURCE_ID,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      "circle-color": categoryColorExpression(),
      "circle-radius": 7,
      "circle-stroke-color": corDoToken("--color-surface"),
      "circle-stroke-width": 2,
    },
  } as never);
  /*
   * O serviço escrito sobre o próprio traço.
   *
   * A cor já diz quando o segmento avançou; o que ela não diz é o que foi
   * executado ali. Quem lê o mapa em campo precisava clicar risco por risco
   * para descobrir. O rótulo entra quando a câmera chega perto o bastante para
   * que exista espaço sobre a linha — de longe seria uma sopa de texto sobre um
   * traçado que ainda cabe inteiro na tela.
   */
  const fonteDoRotulo = fonteDeTextoDoEstilo(map.getStyle?.()?.layers);
  if (fonteDoRotulo) {
    map.addLayer({
      id: "cortex-lines-rotulos",
      type: "symbol",
      source: SOURCE_ID,
      minzoom: ZOOM_MINIMO_DOS_ROTULOS,
      filter: ["in", ["geometry-type"], ["literal", ["LineString"]]],
      layout: {
        "symbol-placement": "line-center",
        "text-field": ROTULO_DO_SERVICO,
        "text-font": fonteDoRotulo,
        "text-size": 11,
        "text-letter-spacing": 0.02,
        "text-max-angle": 40,
        "text-padding": 6,
        "text-allow-overlap": false,
      },
      paint: {
        "text-color": corDoToken("--color-ink"),
        "text-halo-color": corDoToken("--color-surface"),
        "text-halo-width": 1.6,
      },
    } as never);
  }

  const balao = rastreadorDeBalao();

  for (const layerId of [
    "cortex-polygons",
    "cortex-lines",
    "cortex-lines-encerradas",
    "cortex-points",
  ]) {
    map.on("click", layerId, (event) => {
      const properties = event.features?.[0]?.properties ?? {};
      const popup = criarPopup()
        .setLngLat(event.lngLat)
        .setDOMContent(popupContent(properties, aoRemoverPonto));
      popup.addTo(map as never);
      balao.abrir(popup, idDaFeicao(properties));
    });
    map.on("mouseenter", layerId, () => {
      map.getCanvas().style.cursor = "pointer";
    });
    map.on("mouseleave", layerId, () => {
      map.getCanvas().style.cursor = "";
    });
  }

  return balao;
}

/** O que cada runtime acrescenta ao ciclo comum de montagem. */
interface ExtensaoDoProvider {
  criarPopup: () => GlPopupCompativel;
  /** Chamada assim que o estilo carrega; diz se há volume a alternar. */
  prepararVolume: () => boolean;
  alternarVolume: (visivel: boolean) => void;
}

/**
 * Ciclo de montagem comum aos dois renderizadores.
 *
 * A prontidão é medida pelo estilo, não pelo evento `load`. O `load` só dispara
 * quando o sprite e **todos** os tiles em vista terminam de resolver; um único
 * tile preso em `loading` — resposta guardada quebrada, host de mapa bloqueado
 * na rede da obra — segura o evento para sempre, e o painel que já estava
 * pintando era descartado por tempo esgotado. As camadas operacionais precisam
 * do estilo, e só dele.
 */
function prepararMapa(
  map: GlMapaOperacional,
  options: MountOptions,
  extensao: ExtensaoDoProvider,
): Promise<OperationalMapController> {
  return new Promise((resolve, reject) => {
    let pronto = false;
    let destruido = false;
    let enquadrado = false;
  let impondoCamera = false;
    let ultimaFalha: string | null = null;
    // O primeiro `idle` é a prova de que algo pintou. Antes dele, uma falha de
    // rede do basemap significa painel vazio; depois dele, é degradação.
    let ocioso = false;
    let basemapAvisado = false;
    // Um tile perdido é rotina; a fonte inteira mal servida é outra coisa.
    let falhasDeFonte = 0;
    const temporizadores: number[] = [];

    map.once("idle", () => {
      ocioso = true;
    });

    const avisarBasemap = () => {
      if (basemapAvisado || destruido) return;
      basemapAvisado = true;
      options.onFalhaDeBasemap?.();
    };

    const encerrar = () => {
      if (destruido) return;
      destruido = true;
      for (const id of temporizadores) {
        window.clearTimeout(id);
      }
      map.remove();
    };

    const desistir = (mensagem: string) => {
      if (pronto || destruido) return;
      encerrar();
      reject(new Error(mensagem));
    };

    temporizadores.push(
      window.setTimeout(() => {
        desistir(
          ultimaFalha ??
            "O estilo do mapa não chegou a tempo. Verifique a conexão desta obra ou tente baixar de novo.",
        );
      }, LIMITE_ESTILO_MS),
    );

    map.once("style.load", () => {
      if (destruido) return;
      pronto = true;
      for (const id of temporizadores.splice(0)) {
        window.clearTimeout(id);
      }

      /*
       * Vigia o pior estado possível: o estilo sobe e nada pinta.
       *
       * Acontece quando os tiles não chegam de forma legível e o renderizador
       * não emite erro para todos esses casos. Sem esta checagem o operador
       * fica diante de um retângulo cinza sem uma palavra, e nem ele nem o
       * suporte conseguem dizer o que falhou.
       */
      const canvas = map.getCanvas();
      if (canvas.width === 0 || canvas.height === 0) {
        // Contêiner medido em zero na montagem: redimensionar é o que faz o
        // canvas assumir o tamanho real em vez de ficar sem superfície.
        map.resize();
      }
      temporizadores.push(
        window.setTimeout(() => {
          if (destruido) return;
          /*
           * O veredito do pixel vem antes de qualquer outro sinal.
           *
           * Havendo geometria da obra para desenhar, um canvas de uma cor só
           * significa que nada pintou — nem a malha, nem o próprio trecho, que
           * não depende de rede alguma. Nesse estado não há aviso que sirva: o
           * painel troca para o basemap embutido, que desenha. Sem geometria
           * não há como distinguir mapa vazio de mapa mudo, e aí a medição não
           * é usada.
           */
          if (
            options.features.features.length > 0 &&
            canvasEstaMudo(map.getCanvas())
          ) {
            avisarBasemap();
            return;
          }
          /*
           * O fundo liso: a geometria da obra desenhou e a malha não.
           *
           * Acontece quando o estilo passa na sonda e os tiles são barrados
           * depois — a rede deixa o JSON entrar e retém o resto. O canvas não
           * fica de uma cor só, porque o trecho pinta sobre ele, e por isso a
           * medição de pixel não acusa. O que acusa é a insistência: uma fonte
           * que erra repetidamente não vai entregar mapa, e um trecho flutuando
           * sobre nada não localiza obra nenhuma. Melhor o basemap embutido.
           */
          if (falhasDeFonte >= FALHAS_ATE_TROCAR_A_FONTE) {
            avisarBasemap();
            return;
          }
          // `areTilesLoaded()` responde verdadeiro quando a própria fonte
          // falhou — fonte quebrada não tem tile pendente. Uma falha já
          // registrada, portanto, fala mais alto do que esse sinal.
          if (ultimaFalha === null && map.areTilesLoaded()) return;
          options.onRuntimeError(
            ultimaFalha ??
              ("O mapa abriu, mas nenhum tile chegou. Pode ser a rede desta obra"
                + " bloqueando o servidor de mapas, ou um arquivo guardado com"
                + " defeito neste aparelho."),
          );
        }, LIMITE_TILES_MS),
      );

      let volume = false;
      try {
        volume = extensao.prepararVolume();
      } catch {
        volume = false;
      }
      const balao = addOperationalLayers(
        map,
        options.features,
        extensao.criarPopup,
        options.onRemoverPonto ?? null,
      );
      enquadrado = enquadrar(map, options.features);
      if (options.mode === "3d" && volume) {
        extensao.alternarVolume(true);
      }

      resolve({
        centerOn: (longitude, latitude) => {
          if (destruido) return;
          map.easeTo({
            center: [longitude, latitude],
            zoom: ZOOM_AO_CENTRALIZAR,
          });
        },
        setViewMode: (mode) => {
          if (destruido) return;
          if (volume) {
            extensao.alternarVolume(mode === "3d");
          }
          map.easeTo({
            pitch: mode === "3d" ? INCLINACAO_3D : 0,
            bearing: mode === "3d" ? -18 : 0,
          });
        },
        setFeatures: (features) => {
          if (destruido) return;
          // Antes de trocar os dados: o balão da feição que saiu ficaria
          // ancorado no vazio, e é ele que a pessoa lê como "o ponto continua".
          balao.fecharSeSumiu(features);
          map.getSource(SOURCE_ID)?.setData(features);
          // O enquadramento acontece uma vez, quando existe extensão para
          // enquadrar. Refazê-lo a cada leitura arrancaria a câmera de onde o
          // operador acabou de posicionar.
          if (!enquadrado) {
            enquadrado = enquadrar(map, features);
          }
        },
        applyCamera: (camera) => {
          if (destruido) return;
          // `jumpTo`, e não `easeTo`: uma animação faria o mapa emitir uma
          // sequência de paradas intermediárias, e cada uma delas voltaria ao
          // outro mapa como movimento novo. O espelho precisa ser instantâneo
          // para não virar conversa.
          impondoCamera = true;
          map.jumpTo({
            center: [camera.longitude, camera.latitude],
            zoom: camera.zoom,
          });
        },
        onCameraChange: (listener) => {
          const aoParar = () => {
            // O movimento que este mapa acabou de sofrer veio do outro: ecoá-lo
            // de volta faria o par oscilar sem nunca assentar.
            if (impondoCamera) {
              impondoCamera = false;
              return;
            }
            if (destruido) return;
            const centro = map.getCenter();
            listener({
              longitude: centro.lng,
              latitude: centro.lat,
              zoom: map.getZoom(),
            });
          };
          map.on("moveend", aoParar as never);
          return () => {
            map.off("moveend", aoParar as never);
          };
        },
        destroy: encerrar,
      });
    });

    map.on("error", (event) => {
      const mensagem = mensagemDeFalha(event.error?.message);
      // Falha de rede do basemap antes de qualquer pintura: este é o caminho
      // do retângulo mudo. O aviso sai daqui — não do vigia — porque a fonte
      // quebrada satisfaz todos os sinais de "carregado" do renderizador.
      if (falhaDeBasemap(event.error, options.provider.styleUrl)) {
        falhasDeFonte += 1;
        if (!ocioso) {
          avisarBasemap();
        }
      }
      if (pronto) {
        options.onRuntimeError(mensagem);
        return;
      }
      ultimaFalha = mensagem;
      if (falhaImpedeOMapa(event.error, options.provider.styleUrl)) {
        desistir(mensagem);
      }
    });
  });
}

const BUILDING_LAYER_ID = "cortex-3d-buildings";

/**
 * Extrusão das edificações do estilo vetorial.
 *
 * Só é aplicada quando o estilo realmente traz a camada `building` com altura;
 * em estilo raster ou satélite a inclinação da câmera continua funcionando sem
 * volume, e nenhuma altura é inventada.
 */
/*
 * Enquadramento e inclinação de abertura do painel vetorial.
 *
 * O painel abria em zoom 14, que na escala de uma rodovia mostra o município e
 * quase nada da obra: quem abre quer ver o canteiro, não a região. A inclinação
 * mais acentuada e o zoom mais fechado são o que fazem o relevo construído
 * aparecer — em 14 as extrusões existem e não se leem.
 */
const ZOOM_DE_ABERTURA = 16;
const ZOOM_AO_CENTRALIZAR = 16;
const INCLINACAO_3D = 60;
const ROTACAO_3D = -18;

/*
 * Onde as edificações começam a existir.
 *
 * Uma faixa abaixo do zoom de abertura, para que afastar um pouco não apague o
 * relevo de uma vez — que era o efeito de amarrar o mínimo ao próprio
 * enquadramento inicial.
 */
const ZOOM_MINIMO_DAS_EDIFICACOES = 13;

/**
 * Altura publicada pelo estilo vetorial, com o piso de 6 m para a edificação
 * que existe no dado sem altura declarada — sem inventar andar em nenhuma.
 */
const ALTURA_DA_EDIFICACAO = [
  "coalesce",
  ["get", "render_height"],
  ["get", "height"],
  6,
];

function addBuildingExtrusion(map: import("maplibre-gl").Map): boolean {
  const style = map.getStyle();
  const source = style?.layers?.find(
    (layer) =>
      "source-layer" in layer &&
      layer["source-layer"] === "building" &&
      "source" in layer,
  );
  if (!source || !("source" in source) || typeof source.source !== "string") {
    return false;
  }
  if (map.getLayer(BUILDING_LAYER_ID)) {
    return true;
  }

  map.addLayer({
    id: BUILDING_LAYER_ID,
    type: "fill-extrusion",
    source: source.source,
    "source-layer": "building",
    minzoom: ZOOM_MINIMO_DAS_EDIFICACOES,
    layout: { visibility: "none" },
    paint: {
      /*
       * O volume ganha leitura pela própria altura: com uma cor só, um bloco
       * de galpão e um prédio de dez andares viravam a mesma mancha cinza e o
       * relevo não informava nada. A rampa escurece conforme sobe, então a
       * silhueta do entorno passa a ser legível de relance — que é o ponto de
       * inclinar a câmera.
       */
      "fill-extrusion-color": [
        "interpolate",
        ["linear"],
        ALTURA_DA_EDIFICACAO,
        0,
        corDoToken("--color-border"),
        12,
        corDoToken("--color-border-strong"),
        45,
        corDoToken("--color-ink"),
      ],
      "fill-extrusion-opacity": 0.82,
      "fill-extrusion-vertical-gradient": true,
      "fill-extrusion-height": ALTURA_DA_EDIFICACAO,
      "fill-extrusion-base": [
        "coalesce",
        ["get", "render_min_height"],
        ["get", "min_height"],
        0,
      ],
    } as never,
  });
  return true;
}

async function mountMapLibre(
  options: MountOptions,
): Promise<OperationalMapController> {
  const maplibre = await import("maplibre-gl");
  await import("maplibre-gl/dist/maplibre-gl.css");
  const map = new maplibre.Map({
    container: options.container,
    style: (options.provider.estiloInline ??
      options.provider.styleUrl ??
      "") as never,
    center: options.center,
    zoom: ZOOM_DE_ABERTURA,
    pitch: options.mode === "3d" ? INCLINACAO_3D : 0,
    bearing: options.mode === "3d" ? ROTACAO_3D : 0,
    attributionControl: false,
    // Sem preservar o buffer, o conteúdo desenhado é descartado logo após a
    // composição e o canvas lido devolve vazio — a medição do retângulo mudo
    // acusaria toda montagem, inclusive as boas.
    canvasContextAttributes: { preserveDrawingBuffer: true },
  });
  map.addControl(new maplibre.NavigationControl(), "top-right");
  map.addControl(
    new maplibre.AttributionControl({ compact: true }),
    "bottom-right",
  );

  return prepararMapa(map as unknown as GlMapaOperacional, options, {
    criarPopup: () =>
      new maplibre.Popup({
        closeButton: false,
        offset: 10,
      }) as unknown as GlPopupCompativel,
    prepararVolume: () =>
      options.provider.capabilities.buildingExtrusion
        ? addBuildingExtrusion(map)
        : false,
    alternarVolume: (visivel) => {
      map.setLayoutProperty(
        BUILDING_LAYER_ID,
        "visibility",
        visivel ? "visible" : "none",
      );
    },
  });
}

async function mountMapbox(
  options: MountOptions,
): Promise<OperationalMapController> {
  const mapboxModule = await import("mapbox-gl");
  await import("mapbox-gl/dist/mapbox-gl.css");
  const mapbox = mapboxModule.default;
  mapbox.accessToken = mapboxAccessToken();
  const map = new mapbox.Map({
    container: options.container,
    style: options.provider.styleUrl ?? "",
    center: options.center,
    zoom: ZOOM_DE_ABERTURA,
    pitch: options.mode === "3d" ? INCLINACAO_3D : 0,
    bearing: 0,
    attributionControl: false,
    preserveDrawingBuffer: true,
  });
  map.addControl(new mapbox.NavigationControl(), "top-right");
  map.addControl(
    new mapbox.AttributionControl({ compact: true }),
    "bottom-right",
  );

  return prepararMapa(map as unknown as GlMapaOperacional, options, {
    criarPopup: () =>
      new mapboxModule.Popup({
        closeButton: false,
        offset: 10,
      }) as unknown as GlPopupCompativel,
    // O provider Mapbox aqui é sempre satélite: não há camada `building`
    // vetorial para extrudar, e inventar volume sobre a imagem seria falso.
    prepararVolume: () => false,
    alternarVolume: () => undefined,
  });
}

/**
 * O construtor do renderizador lança de forma síncrona quando o navegador não
 * oferece WebGL, antes de qualquer evento de erro do mapa. A normalização fica
 * aqui para que nenhum caminho de falha vaze o texto cru para a interface.
 */
export function mountOperationalMap(
  options: MountOptions,
): Promise<OperationalMapController> {
  const montagem =
    options.provider.engine === "mapbox"
      ? mountMapbox(options)
      : mountMapLibre(options);

  return montagem.catch((motivo: unknown) => {
    throw new Error(
      mensagemDeFalha(motivo instanceof Error ? motivo.message : String(motivo)),
    );
  });
}
