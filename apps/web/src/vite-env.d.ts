/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_CORTEX_API_BASE_URL?: string;
  readonly VITE_MAP_PROVIDER?: "maptiler" | "mapbox";
  readonly VITE_MAPTILER_API_KEY?: string;
  readonly VITE_MAPBOX_ACCESS_TOKEN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
