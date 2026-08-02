export type MapProviderId = "maplibre" | "maptiler" | "mapbox";
export type MapEngine = "maplibre" | "mapbox";

export interface MapProviderEnvironment {
  VITE_MAP_PROVIDER?: string;
  VITE_MAPLIBRE_STYLE_URL?: string;
  VITE_MAPTILER_API_KEY?: string;
  VITE_MAPBOX_ACCESS_TOKEN?: string;
}

export interface MapProvider {
  id: MapProviderId;
  label: string;
  engine: MapEngine;
  configured: boolean;
  styleUrl: string | null;
  /**
   * Estilo embutido no aplicativo, usado no lugar de `styleUrl`. Existe para o
   * provider reserva: quando o servidor de estilo está inalcançável, nenhum
   * URL resolveria — o estilo precisa já estar aqui dentro.
   */
  estiloInline?: Record<string, unknown> | null;
  /** Verdadeiro no provider de contingência, acionado por falha do principal. */
  reserva?: boolean;
  missingConfiguration: string | null;
  fallbackReason: string | null;
  /** Verdadeiro quando o provider não exige credencial alguma. */
  keyless: boolean;
  capabilities: {
    perspective3d: boolean;
    geoJsonLayers: boolean;
    cameraControl: boolean;
    /** Extrusão de edificações, disponível apenas em estilo vetorial. */
    buildingExtrusion: boolean;
    satellite: boolean;
  };
}

/**
 * Estilo vetorial aberto do OpenFreeMap, servido sem chave nem cadastro.
 *
 * É o padrão do produto: mantém o mapa operacional utilizável em qualquer
 * instalação, inclusive antes de contratar qualquer provider.
 */
const MAPLIBRE_DEFAULT_STYLE = "https://tiles.openfreemap.org/styles/liberty";

const VECTOR_CAPABILITIES = Object.freeze({
  perspective3d: true,
  geoJsonLayers: true,
  cameraControl: true,
  buildingExtrusion: true,
  satellite: false,
});

const SATELLITE_CAPABILITIES = Object.freeze({
  perspective3d: true,
  geoJsonLayers: true,
  cameraControl: true,
  buildingExtrusion: false,
  satellite: true,
});

function trimmed(value: string | undefined): string {
  return value?.trim() ?? "";
}

function mapLibreProvider(
  environment: MapProviderEnvironment,
  fallbackReason: string | null,
): MapProvider {
  const style = trimmed(environment.VITE_MAPLIBRE_STYLE_URL);
  return {
    id: "maplibre",
    label: "MapLibre (aberto)",
    engine: "maplibre",
    configured: true,
    styleUrl: style || MAPLIBRE_DEFAULT_STYLE,
    missingConfiguration: null,
    fallbackReason,
    keyless: true,
    capabilities: VECTOR_CAPABILITIES,
  };
}

function mapTilerProvider(environment: MapProviderEnvironment): MapProvider {
  const key = trimmed(environment.VITE_MAPTILER_API_KEY);
  const params = new URLSearchParams();
  if (key) {
    params.set("key", key);
  }

  return {
    id: "maptiler",
    label: "MapTiler satélite",
    engine: "maplibre",
    configured: Boolean(key),
    styleUrl: key
      ? `https://api.maptiler.com/maps/satellite/style.json?${params.toString()}`
      : null,
    missingConfiguration: key
      ? null
      : "Configure VITE_MAPTILER_API_KEY para usar a imagem de satélite do MapTiler.",
    fallbackReason: null,
    keyless: false,
    capabilities: SATELLITE_CAPABILITIES,
  };
}

function mapboxProvider(environment: MapProviderEnvironment): MapProvider {
  const token = trimmed(environment.VITE_MAPBOX_ACCESS_TOKEN);

  return {
    id: "mapbox",
    label: "Mapbox satélite",
    engine: "mapbox",
    configured: Boolean(token),
    styleUrl: token ? "mapbox://styles/mapbox/satellite-streets-v12" : null,
    missingConfiguration: token
      ? null
      : "Configure VITE_MAPBOX_ACCESS_TOKEN para ativar o provider Mapbox.",
    fallbackReason: null,
    keyless: false,
    capabilities: SATELLITE_CAPABILITIES,
  };
}

export function resolveMapProviderForId(
  id: MapProviderId,
  environment: MapProviderEnvironment = import.meta.env,
): MapProvider {
  if (id === "mapbox") return mapboxProvider(environment);
  if (id === "maptiler") return mapTilerProvider(environment);
  return mapLibreProvider(environment, null);
}

export function resolveMapProvider(
  environment: MapProviderEnvironment = import.meta.env,
): MapProvider {
  const requested = trimmed(environment.VITE_MAP_PROVIDER).toLowerCase();

  if (requested === "mapbox" || requested === "maptiler") {
    const provider = resolveMapProviderForId(requested, environment);
    // Provider pago pedido sem credencial cai para a malha aberta em vez de
    // deixar a página sem mapa nenhum.
    return provider.configured
      ? provider
      : mapLibreProvider(
          environment,
          `${provider.label} não está configurado; usando a malha aberta do MapLibre.`,
        );
  }

  if (requested && requested !== "maplibre") {
    return mapLibreProvider(
      environment,
      `Provider "${requested}" não reconhecido; usando a malha aberta do MapLibre.`,
    );
  }

  return mapLibreProvider(environment, null);
}

/**
 * Providers oferecidos ao operador, na ordem em que aparecem no seletor.
 *
 * Só entra o que está pronto para uso. Um provider pago sem credencial não
 * desenha nada: escolhê-lo apagava o mapa e devolvia um pedido de configuração
 * que o operador em campo não tem como atender. A malha aberta é keyless e
 * está sempre presente, então a lista nunca fica vazia.
 */
export function availableMapProviders(
  environment: MapProviderEnvironment = import.meta.env,
): MapProvider[] {
  return (["maplibre", "maptiler", "mapbox"] as const)
    .map((id) => resolveMapProviderForId(id, environment))
    .filter((provider) => provider.configured);
}

/**
 * O basemap de contingência: OSM raster, com o estilo embutido.
 *
 * Entra sozinho quando a fonte vetorial do provider principal está
 * inalcançável — o caso observado em campo, em que o painel subia como um
 * retângulo mudo. Usa `tile.openstreetmap.org`, o mesmo host que o painel
 * Leaflet ao lado já consome: se metade da tela desenha, a outra também tem
 * como desenhar. O estilo vive aqui dentro porque, com o servidor de estilos
 * fora de alcance, nenhum URL de estilo resolveria. Sem camadas de texto,
 * nenhum glifo é necessário.
 */
export function providerReservaOsm(): MapProvider {
  return {
    id: "maplibre",
    label: "OSM (reserva)",
    engine: "maplibre",
    configured: true,
    styleUrl: null,
    estiloInline: {
      version: 8,
      name: "Córtex reserva OSM",
      sources: {
        osm: {
          type: "raster",
          tiles: ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
          tileSize: 256,
          maxzoom: 19,
          attribution: "© OpenStreetMap contributors",
        },
      },
      layers: [{ id: "osm", type: "raster", source: "osm" }],
    },
    reserva: true,
    missingConfiguration: null,
    fallbackReason: null,
    keyless: true,
    capabilities: {
      perspective3d: false,
      geoJsonLayers: true,
      cameraControl: true,
      buildingExtrusion: false,
      satellite: false,
    },
  };
}

export function mapboxAccessToken(
  environment: MapProviderEnvironment = import.meta.env,
): string {
  return trimmed(environment.VITE_MAPBOX_ACCESS_TOKEN);
}
