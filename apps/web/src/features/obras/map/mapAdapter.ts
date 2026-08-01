import {
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
import { mapboxAccessToken, type MapProvider } from "./mapProvider";

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
  destroy: () => void;
}

interface MountOptions {
  container: HTMLDivElement;
  provider: MapProvider;
  features: OperationalFeatureCollection;
  center: [number, number];
  mode: MapViewMode;
  onRuntimeError: (message: string) => void;
}

const SOURCE_ID = "cortex-operational";
/** Espera pelo estilo do provider — um JSON, não o mapa inteiro. */
const LIMITE_ESTILO_MS = 25_000;
/** Janela para o primeiro tile chegar depois que o estilo já está de pé. */
const LIMITE_TILES_MS = 12_000;

/**
 * A espessura acompanha o zoom para que o trecho continue legível de longe sem
 * cobrir a pista quando a câmera se aproxima.
 */
const LARGURA_LINHA = [
  "interpolate",
  ["linear"],
  ["zoom"],
  10,
  2,
  14,
  5,
  18,
  12,
];
const LARGURA_LINHA_CASING = [
  "interpolate",
  ["linear"],
  ["zoom"],
  10,
  4,
  14,
  9,
  18,
  18,
];

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
function popupContent(properties: Record<string, unknown>): HTMLDivElement {
  const container = document.createElement("div");
  container.className = "operational-map-popup";

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
  fitBounds(
    bounds: [[number, number], [number, number]],
    options: { padding: number; maxZoom: number; duration: number },
  ): unknown;
}

interface GlPopupCompativel {
  setLngLat(lngLat: { lng: number; lat: number }): GlPopupCompativel;
  setDOMContent(node: Node): GlPopupCompativel;
  addTo(map: never): unknown;
}

function addOperationalLayers(
  map: GlMapaOperacional,
  features: OperationalFeatureCollection,
  criarPopup: () => GlPopupCompativel,
): void {
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
  // Contorno escuro sob a linha colorida: é o que faz o trecho ler como uma
  // pista sobre a imagem, em vez de um risco solto por cima do mapa.
  map.addLayer({
    id: "cortex-lines-casing",
    type: "line",
    source: SOURCE_ID,
    filter: [
      "in",
      ["geometry-type"],
      ["literal", ["LineString", "Polygon"]],
    ],
    layout: { "line-cap": "round", "line-join": "round" },
    paint: {
      "line-color": corDoToken("--color-ink"),
      "line-width": LARGURA_LINHA_CASING,
      "line-opacity": 0.85,
    },
  } as never);
  map.addLayer({
    id: "cortex-lines",
    type: "line",
    source: SOURCE_ID,
    filter: [
      "in",
      ["geometry-type"],
      ["literal", ["LineString", "Polygon"]],
    ],
    layout: { "line-cap": "round", "line-join": "round" },
    paint: {
      "line-color": categoryColorExpression(),
      "line-width": LARGURA_LINHA,
      "line-opacity": 0.95,
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

  for (const layerId of ["cortex-polygons", "cortex-lines", "cortex-points"]) {
    map.on("click", layerId, (event) => {
      const properties = event.features?.[0]?.properties ?? {};
      criarPopup()
        .setLngLat(event.lngLat)
        .setDOMContent(popupContent(properties))
        .addTo(map as never);
    });
    map.on("mouseenter", layerId, () => {
      map.getCanvas().style.cursor = "pointer";
    });
    map.on("mouseleave", layerId, () => {
      map.getCanvas().style.cursor = "";
    });
  }
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
    let ultimaFalha: string | null = null;
    const temporizadores: number[] = [];

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
          if (destruido || map.areTilesLoaded()) return;
          options.onRuntimeError(
            "O mapa abriu, mas nenhum tile chegou. Pode ser a rede desta obra"
              + " bloqueando o servidor de mapas, ou um arquivo guardado com"
              + " defeito neste aparelho.",
          );
        }, LIMITE_TILES_MS),
      );

      let volume = false;
      try {
        volume = extensao.prepararVolume();
      } catch {
        volume = false;
      }
      addOperationalLayers(map, options.features, extensao.criarPopup);
      enquadrado = enquadrar(map, options.features);
      if (options.mode === "3d" && volume) {
        extensao.alternarVolume(true);
      }

      resolve({
        centerOn: (longitude, latitude) => {
          if (destruido) return;
          map.easeTo({ center: [longitude, latitude], zoom: 15 });
        },
        setViewMode: (mode) => {
          if (destruido) return;
          if (volume) {
            extensao.alternarVolume(mode === "3d");
          }
          map.easeTo({
            pitch: mode === "3d" ? 52 : 0,
            bearing: mode === "3d" ? -18 : 0,
          });
        },
        setFeatures: (features) => {
          if (destruido) return;
          map.getSource(SOURCE_ID)?.setData(features);
          // O enquadramento acontece uma vez, quando existe extensão para
          // enquadrar. Refazê-lo a cada leitura arrancaria a câmera de onde o
          // operador acabou de posicionar.
          if (!enquadrado) {
            enquadrado = enquadrar(map, features);
          }
        },
        destroy: encerrar,
      });
    });

    map.on("error", (event) => {
      const mensagem = mensagemDeFalha(event.error?.message);
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
    minzoom: 14,
    layout: { visibility: "none" },
    paint: {
      "fill-extrusion-color": corDoToken("--color-border-strong"),
      "fill-extrusion-opacity": 0.65,
      "fill-extrusion-height": [
        "coalesce",
        ["get", "render_height"],
        ["get", "height"],
        6,
      ],
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
    style: options.provider.styleUrl ?? "",
    center: options.center,
    zoom: 14,
    pitch: options.mode === "3d" ? 52 : 0,
    bearing: options.mode === "3d" ? -18 : 0,
    attributionControl: false,
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
    zoom: 14,
    pitch: options.mode === "3d" ? 52 : 0,
    bearing: 0,
    attributionControl: false,
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
