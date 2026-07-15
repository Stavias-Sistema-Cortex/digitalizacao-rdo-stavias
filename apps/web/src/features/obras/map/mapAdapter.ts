import type { OperationalFeatureCollection } from "./mapGeometry";
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

function categoryColorExpression(): unknown[] {
  return [
    "match",
    ["get", "categoria"],
    "LOCALIZACAO_OBRA",
    "#fed203",
    "PERIMETRO_OBRA",
    "#17a398",
    "TRECHO",
    "#f7a531",
    "FRENTE_TRABALHO",
    "#ef6f6c",
    "EQUIPAMENTO",
    "#4e79a7",
    "EVENTO",
    "#8e6bbf",
    "RDO",
    "#35a853",
    "OCORRENCIA",
    "#d9534f",
    "PROGRAMACAO",
    "#6f7d75",
    "#0e5e57",
  ];
}

function popupContent(properties: Record<string, unknown>): HTMLDivElement {
  const container = document.createElement("div");
  container.className = "operational-map-popup";
  const title = document.createElement("strong");
  title.textContent = String(
    properties.nome ?? properties.categoria ?? "Elemento operacional",
  );
  const detail = document.createElement("span");
  detail.textContent = [properties.categoria, properties.fonte]
    .filter(Boolean)
    .map(String)
    .join(" · ");
  container.append(title, detail);
  return container;
}

function addMapLibreLayers(
  map: import("maplibre-gl").Map,
  features: OperationalFeatureCollection,
  maplibre: typeof import("maplibre-gl"),
): void {
  map.addSource(SOURCE_ID, {
    type: "geojson",
    data: features as never,
  });
  map.addLayer({
    id: "cortex-polygons",
    type: "fill",
    source: SOURCE_ID,
    filter: ["==", ["geometry-type"], "Polygon"],
    paint: {
      "fill-color": categoryColorExpression() as never,
      "fill-opacity": 0.28,
    },
  });
  map.addLayer({
    id: "cortex-lines",
    type: "line",
    source: SOURCE_ID,
    filter: [
      "in",
      ["geometry-type"],
      ["literal", ["LineString", "Polygon"]],
    ],
    paint: {
      "line-color": categoryColorExpression() as never,
      "line-width": 3,
      "line-opacity": 0.9,
    },
  });
  map.addLayer({
    id: "cortex-points",
    type: "circle",
    source: SOURCE_ID,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      "circle-color": categoryColorExpression() as never,
      "circle-radius": 7,
      "circle-stroke-color": "#ffffff",
      "circle-stroke-width": 2,
    },
  });

  for (const layerId of ["cortex-polygons", "cortex-lines", "cortex-points"]) {
    map.on("click", layerId, (event) => {
      const properties = event.features?.[0]?.properties ?? {};
      new maplibre.Popup({ closeButton: false, offset: 10 })
        .setLngLat(event.lngLat)
        .setDOMContent(popupContent(properties))
        .addTo(map);
    });
    map.on("mouseenter", layerId, () => {
      map.getCanvas().style.cursor = "pointer";
    });
    map.on("mouseleave", layerId, () => {
      map.getCanvas().style.cursor = "";
    });
  }
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
    bearing: 0,
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
      addMapLibreLayers(map, options.features, maplibre);
      resolve({
        centerOn: (longitude, latitude) =>
          map.easeTo({ center: [longitude, latitude], zoom: 15 }),
        setViewMode: (mode) =>
          map.easeTo({ pitch: mode === "3d" ? 52 : 0, bearing: 0 }),
        destroy: () => map.remove(),
      });
    });
    map.on("error", (event) => {
      const message = event.error?.message ?? "Falha ao carregar o mapa.";
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
  map.addSource(SOURCE_ID, {
    type: "geojson",
    data: features as never,
  });
  map.addLayer({
    id: "cortex-polygons",
    type: "fill",
    source: SOURCE_ID,
    filter: ["==", ["geometry-type"], "Polygon"],
    paint: {
      "fill-color": categoryColorExpression() as never,
      "fill-opacity": 0.28,
    },
  });
  map.addLayer({
    id: "cortex-lines",
    type: "line",
    source: SOURCE_ID,
    filter: [
      "in",
      ["geometry-type"],
      ["literal", ["LineString", "Polygon"]],
    ],
    paint: {
      "line-color": categoryColorExpression() as never,
      "line-width": 3,
      "line-opacity": 0.9,
    },
  });
  map.addLayer({
    id: "cortex-points",
    type: "circle",
    source: SOURCE_ID,
    filter: ["==", ["geometry-type"], "Point"],
    paint: {
      "circle-color": categoryColorExpression() as never,
      "circle-radius": 7,
      "circle-stroke-color": "#ffffff",
      "circle-stroke-width": 2,
    },
  });
  for (const layerId of ["cortex-polygons", "cortex-lines", "cortex-points"]) {
    map.on("click", layerId, (event) => {
      const properties = event.features?.[0]?.properties ?? {};
      new mapbox.Popup({ closeButton: false, offset: 10 })
        .setLngLat(event.lngLat)
        .setDOMContent(popupContent(properties))
        .addTo(map);
    });
    map.on("mouseenter", layerId, () => {
      map.getCanvas().style.cursor = "pointer";
    });
    map.on("mouseleave", layerId, () => {
      map.getCanvas().style.cursor = "";
    });
  }
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
      resolve({
        centerOn: (longitude, latitude) =>
          map.easeTo({ center: [longitude, latitude], zoom: 15 }),
        setViewMode: (mode) =>
          map.easeTo({ pitch: mode === "3d" ? 52 : 0, bearing: 0 }),
        destroy: () => map.remove(),
      });
    });
    map.on("error", (event) => {
      const message = event.error?.message ?? "Falha ao carregar o mapa.";
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

export function mountOperationalMap(
  options: MountOptions,
): Promise<OperationalMapController> {
  return options.provider.engine === "mapbox"
    ? mountMapbox(options)
    : mountMapLibre(options);
}
