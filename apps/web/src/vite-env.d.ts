/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_CORTEX_API_BASE_URL?: string;
  readonly VITE_CORTEX_AUTH_MODE?: "legacy" | "postgresql";
  readonly VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256?: string;
  readonly VITE_MAP_PROVIDER?: string;
  readonly VITE_MAPLIBRE_STYLE_URL?: string;
  readonly VITE_MAPTILER_API_KEY?: string;
  readonly VITE_MAPBOX_ACCESS_TOKEN?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
