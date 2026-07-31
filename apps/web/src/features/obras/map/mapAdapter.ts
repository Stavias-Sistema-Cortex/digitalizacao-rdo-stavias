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
const MAX_MENSAGEM_ERRO = 160;

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
 * Traduz a falha do renderizador para uma frase utilizável.
 *
 * O erro cru do WebGL traz um despejo de atributos de contexto que não diz nada
 * a quem está em campo; a causa conhecida vira instrução e o resto é truncado
 * em vez de vazar para a tela.
 */
function mensagemDeFalha(bruta: string | undefined): string {
  const texto = bruta?.trim();
  if (!texto) {
    return "Falha ao carregar o mapa.";
  }
  if (/webgl/i.test(texto)) {
    return "Este navegador não conseguiu iniciar o WebGL, exigido pela visualização 3D. Use o painel Leaflet ao lado ou habilite a aceleração gráfica.";
  }
  if (/failed to fetch|networkerror|load failed/i.test(texto)) {
    return "Não foi possível baixar os tiles do mapa. Sem rede, apenas o que já foi visto fica disponível.";
  }
  return texto.length > MAX_MENSAGEM_ERRO
    ? `${texto.slice(0, MAX_MENSAGEM_ERRO)}…`
    : texto;
}

/**
 * Enquadra a câmera na extensão real das geometrias da obra.
 *
 * Centrar num ponto e arbitrar um zoom deixa o trecho fora da tela sempre que
 * ele é longo; o enquadramento pela extensão mostra a obra inteira e só ela.
 * Uma obra representada por um único ponto não tem extensão, e aí o centro
 * informado continua valendo.
 */
interface CameraEnquadravel {
  fitBounds: (
    bounds: [[number, number], [number, number]],
    options: { padding: number; maxZoom: number; duration: number },
  ) => unknown;
}

function enquadrar(
  map: CameraEnquadravel,
  features: OperationalFeatureCollection,
): void {
  const limites = limitesDaColecao(features);
  if (!limites || (limites.oeste === limites.leste && limites.sul === limites.norte)) {
    return;
  }
  map.fitBounds(
    [
      [limites.oeste, limites.sul],
      [limites.leste, limites.norte],
    ],
    { padding: 48, maxZoom: 17, duration: 0 },
  );
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
 * e no Mapbox; estes contratos estruturais mínimos permitem um único construtor
 * de camadas para os dois runtimes. O popup é a única diferença real e fica a
 * cargo de quem monta.
 */
interface GlEventoDeCamada {
  features?: { properties?: Record<string, unknown> | null }[];
  lngLat: { lng: number; lat: number };
}

interface GlCompativel {
  addSource(id: string, source: never): unknown;
  addLayer(layer: never): unknown;
  on(
    type: string,
    layerId: string,
    listener: (event: GlEventoDeCamada) => void,
  ): unknown;
  getCanvas(): HTMLCanvasElement;
}

interface GlPopupCompativel {
  setLngLat(lngLat: { lng: number; lat: number }): GlPopupCompativel;
  setDOMContent(node: Node): GlPopupCompativel;
  addTo(map: never): unknown;
}

function addOperationalLayers(
  map: GlCompativel,
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

function addMapLibreLayers(
  map: import("maplibre-gl").Map,
  features: OperationalFeatureCollection,
  maplibre: typeof import("maplibre-gl"),
): void {
  addOperationalLayers(
    map as unknown as GlCompativel,
    features,
    () =>
      new maplibre.Popup({
        closeButton: false,
        offset: 10,
      }) as unknown as GlPopupCompativel,
  );
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

  return new Promise((resolve, reject) => {
    let loaded = false;
    const timer = window.setTimeout(() => {
      if (!loaded) {
        map.remove();
        reject(new Error("O provider demorou demais para carregar."));
      }
    }, 15_000);

    map.once("load", () => {
      loaded = true;
      window.clearTimeout(timer);
      let volume = false;
      if (options.provider.capabilities.buildingExtrusion) {
        try {
          volume = addBuildingExtrusion(map);
        } catch {
          volume = false;
        }
      }
      addMapLibreLayers(map, options.features, maplibre);
      enquadrar(map, options.features);
      resolve({
        centerOn: (longitude, latitude) =>
          map.easeTo({ center: [longitude, latitude], zoom: 15 }),
        setViewMode: (mode) => {
          if (mode === "3d" && volume) {
            map.setLayoutProperty(BUILDING_LAYER_ID, "visibility", "visible");
          } else if (volume) {
            map.setLayoutProperty(BUILDING_LAYER_ID, "visibility", "none");
          }
          map.easeTo({
            pitch: mode === "3d" ? 52 : 0,
            bearing: mode === "3d" ? -18 : 0,
          });
        },
        destroy: () => map.remove(),
      });
    });
    map.on("error", (event) => {
      const message = mensagemDeFalha(event.error?.message);
      if (!loaded) {
        window.clearTimeout(timer);
        map.remove();
        reject(new Error(message));
      } else {
        options.onRuntimeError(message);
      }
    });
  });
}

function addMapboxLayers(
  map: import("mapbox-gl").Map,
  features: OperationalFeatureCollection,
  mapbox: typeof import("mapbox-gl"),
): void {
  addOperationalLayers(
    map as unknown as GlCompativel,
    features,
    () =>
      new mapbox.Popup({
        closeButton: false,
        offset: 10,
      }) as unknown as GlPopupCompativel,
  );
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

  return new Promise((resolve, reject) => {
    let loaded = false;
    const timer = window.setTimeout(() => {
      if (!loaded) {
        map.remove();
        reject(new Error("O provider demorou demais para carregar."));
      }
    }, 15_000);
    map.once("load", () => {
      loaded = true;
      window.clearTimeout(timer);
      addMapboxLayers(map, options.features, mapboxModule);
      enquadrar(map, options.features);
      resolve({
        centerOn: (longitude, latitude) =>
          map.easeTo({ center: [longitude, latitude], zoom: 15 }),
        setViewMode: (mode) =>
          map.easeTo({ pitch: mode === "3d" ? 52 : 0, bearing: 0 }),
        destroy: () => map.remove(),
      });
    });
    map.on("error", (event) => {
      const message = mensagemDeFalha(event.error?.message);
      if (!loaded) {
        window.clearTimeout(timer);
        map.remove();
        reject(new Error(message));
      } else {
        options.onRuntimeError(message);
      }
    });
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
