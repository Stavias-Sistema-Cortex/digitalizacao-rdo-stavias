export type MapProviderId = "maptiler" | "mapbox";
export type MapEngine = "maplibre" | "mapbox";

export interface MapProviderEnvironment {
  VITE_MAP_PROVIDER?: string;
  VITE_MAPTILER_API_KEY?: string;
  VITE_MAPBOX_ACCESS_TOKEN?: string;
}

export interface MapProvider {
  id: MapProviderId;
  label: string;
  engine: MapEngine;
  configured: boolean;
  styleUrl: string | null;
  missingConfiguration: string | null;
  fallbackReason: string | null;
  capabilities: {
    perspective3d: boolean;
    geoJsonLayers: boolean;
    cameraControl: boolean;
  };
}

const CAPABILITIES = Object.freeze({
  perspective3d: true,
  geoJsonLayers: true,
  cameraControl: true,
});

function trimmed(value: string | undefined): string {
  return value?.trim() ?? "";
}

function mapTilerProvider(
  environment: MapProviderEnvironment,
  fallbackReason: string | null,
): MapProvider {
  const key = trimmed(environment.VITE_MAPTILER_API_KEY);
  const params = new URLSearchParams();
  if (key) {
    params.set("key", key);
  }

  return {
    id: "maptiler",
    label: "MapTiler Satellite",
    engine: "maplibre",
    configured: Boolean(key),
    styleUrl: key
      ? `https://api.maptiler.com/maps/satellite/style.json?${params.toString()}`
      : null,
    missingConfiguration: key
      ? null
      : "Configure VITE_MAPTILER_API_KEY para carregar o mapa de satélite.",
    fallbackReason,
    capabilities: CAPABILITIES,
  };
}

function mapboxProvider(
  environment: MapProviderEnvironment,
): MapProvider {
  const token = trimmed(environment.VITE_MAPBOX_ACCESS_TOKEN);

  return {
    id: "mapbox",
    label: "Mapbox Satellite Streets",
    engine: "mapbox",
    configured: Boolean(token),
    styleUrl: token
      ? "mapbox://styles/mapbox/satellite-streets-v12"
      : null,
    missingConfiguration: token
      ? null
      : "Configure VITE_MAPBOX_ACCESS_TOKEN para ativar o provider Mapbox.",
    fallbackReason: null,
    capabilities: CAPABILITIES,
  };
}

export function resolveMapProviderForId(
  id: MapProviderId,
  environment: MapProviderEnvironment = import.meta.env,
): MapProvider {
  return id === "mapbox"
    ? mapboxProvider(environment)
    : mapTilerProvider(environment, null);
}

export function resolveMapProvider(
  environment: MapProviderEnvironment = import.meta.env,
): MapProvider {
  const requested = trimmed(environment.VITE_MAP_PROVIDER).toLowerCase();

  if (requested === "mapbox") {
    return resolveMapProviderForId("mapbox", environment);
  }

  if (requested && requested !== "maptiler") {
    return mapTilerProvider(
      environment,
      `Provider "${requested}" não reconhecido; usando o fallback MapTiler.`,
    );
  }

  return resolveMapProviderForId("maptiler", environment);
}

export function mapboxAccessToken(
  environment: MapProviderEnvironment = import.meta.env,
): string {
  return trimmed(environment.VITE_MAPBOX_ACCESS_TOKEN);
}
