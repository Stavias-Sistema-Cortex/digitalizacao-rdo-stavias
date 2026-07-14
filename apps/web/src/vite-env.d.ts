/// <reference types="vite/client" />
/// <reference types="vite-plugin-pwa/client" />

interface ImportMetaEnv {
  readonly VITE_CORTEX_API_BASE_URL?: string;
  readonly VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
