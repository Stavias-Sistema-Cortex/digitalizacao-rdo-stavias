import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

import { stripSourceMapReferencesPlugin } from "./securityDeliveryPolicy";

const cortexApiTarget =
  process.env.CORTEX_API_TARGET ??
  "http://127.0.0.1:8080";

const apiProxy = {
  "/api": {
    target: cortexApiTarget,
    changeOrigin: true,
  },
};

const contentSecurityPolicy = [
  "default-src 'self'",
  "base-uri 'self'",
  "object-src 'none'",
  "frame-ancestors 'none'",
  "form-action 'self'",
  "script-src 'self'",
  "style-src 'self' 'unsafe-inline'",
  "img-src 'self' data: blob: https:",
  "font-src 'self' data:",
  "connect-src 'self' https: wss:",
  "worker-src 'self' blob:",
  "manifest-src 'self'",
].join("; ");

const securityHeaders = {
  "Content-Security-Policy": contentSecurityPolicy,
  "Permissions-Policy": "camera=(), microphone=(), geolocation=(self)",
  "Referrer-Policy": "strict-origin-when-cross-origin",
  "X-Content-Type-Options": "nosniff",
  "X-Frame-Options": "DENY",
};

// O React Refresh injeta um preâmbulo inline apenas no servidor de
// desenvolvimento. A política de produção/preview permanece sem unsafe-inline.
const developmentSecurityHeaders = {
  ...securityHeaders,
  "Content-Security-Policy": contentSecurityPolicy.replace(
    "script-src 'self'",
    "script-src 'self' 'unsafe-inline'",
  ),
};

export default defineConfig({
  build: {
    sourcemap: false,
  },

  test: {
    environment: "node",
    setupFiles: ["./src/test/setup.ts"],
  },

  plugins: [
    react(),
    stripSourceMapReferencesPlugin(),

    VitePWA({
      registerType: "prompt",

      includeAssets: [
        "favicon.png",
        "icons.svg",
        "pwa-192x192.png",
        "pwa-512x512.png",
        "pwa-maskable-512x512.png",
      ],

      manifest: {
        name: "Córtex Stavias",
        short_name: "Córtex",
        description:
          "Plataforma operacional offline-first para gestão de obras e RDOs.",
        lang: "pt-BR",
        start_url: "/",
        scope: "/",
        display: "standalone",
        orientation: "any",
        background_color: "#f7f8f5",
        theme_color: "#183b2a",

        icons: [
          {
            src: "/pwa-192x192.png",
            sizes: "192x192",
            type: "image/png",
            purpose: "any",
          },
          {
            src: "/pwa-512x512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "any",
          },
          {
            src: "/pwa-maskable-512x512.png",
            sizes: "512x512",
            type: "image/png",
            purpose: "maskable",
          },
        ],
      },

      workbox: {
        navigateFallback: null,
        globPatterns: [
          "**/*.{js,mjs,css,html,svg,png,ico,webp,woff,woff2,ttf,xlsx}",
        ],
        // The PDF.js worker is required to parse an RDO PDF before the
        // browser reconnects, and is slightly larger than Workbox's 2 MiB
        // default cache limit.
        maximumFileSizeToCacheInBytes: 3 * 1024 * 1024,

        cleanupOutdatedCaches: true,

        runtimeCaching: [
          {
            urlPattern: ({ request, url }) =>
              request.mode === "navigate" &&
              !/^\/api(?:\/|$)/.test(url.pathname),

            handler: "NetworkFirst",

            options: {
              cacheName: "cortex-pages",
              networkTimeoutSeconds: 3,
              precacheFallback: {
                fallbackURL: "/index.html",
              },
            },
          },

          {
            urlPattern: ({ request }) =>
              request.destination === "style" ||
              request.destination === "script" ||
              request.destination === "worker",

            handler: "StaleWhileRevalidate",

            options: {
              cacheName: "cortex-static-resources",
            },
          },

          /*
           * Tiles do mapa da rodovia, em duas regras.
           *
           * Ambas precedem a regra genérica de imagem porque o Workbox roteia
           * pelo primeiro match e o Leaflet busca tile por `<img>`: sem esta
           * ordem, os tiles cairiam no cache de imagens da aplicação e
           * disputariam suas 100 entradas.
           *
           * Só é guardado o tile que já foi realmente exibido: não há prefetch,
           * e um tile nunca baixado permanece indisponível offline em vez de
           * ser apresentado como conhecido.
           */

          /*
           * Estilo, glifos e tiles vetoriais, buscados por `fetch`.
           *
           * O MapLibre precisa LER o corpo dessas respostas. Uma resposta
           * opaca (status 0) não é legível, e guardá-la sob CacheFirst deixa o
           * mapa em branco para sempre: o provider monta, reporta pronto e
           * simplesmente não pinta, sem erro para o operador ver. Só resposta
           * legível entra neste cache.
           */
          {
            urlPattern: ({ request, url }) =>
              request.destination !== "image" &&
              (url.host === "tiles.openfreemap.org" ||
                url.host === "api.maptiler.com" ||
                url.host === "api.mapbox.com" ||
                url.host.endsWith(".tile.openstreetmap.org") ||
                url.host === "tile.openstreetmap.org"),

            handler: "CacheFirst",

            options: {
              cacheName: "cortex-map-vetorial",

              cacheableResponse: {
                statuses: [200],
              },

              expiration: {
                maxEntries: 500,
                maxAgeSeconds: 604800,
              },
            },
          },

          /*
           * Tiles raster do Leaflet, buscados por `<img>`.
           *
           * Aqui a resposta opaca é normal e renderiza mesmo assim, então
           * status 0 continua guardável — é o que sustenta o mapa offline.
           */
          {
            urlPattern: ({ request, url }) =>
              request.destination === "image" &&
              (url.host === "tiles.openfreemap.org" ||
                url.host === "api.maptiler.com" ||
                url.host === "api.mapbox.com" ||
                url.host.endsWith(".tile.openstreetmap.org") ||
                url.host === "tile.openstreetmap.org"),

            handler: "CacheFirst",

            options: {
              cacheName: "cortex-map-tiles",

              cacheableResponse: {
                statuses: [0, 200],
              },

              expiration: {
                maxEntries: 500,
                maxAgeSeconds: 604800,
              },
            },
          },

          {
            urlPattern: ({ request }) =>
              request.destination === "image",

            handler: "CacheFirst",

            options: {
              cacheName: "cortex-images",

              expiration: {
                maxEntries: 100,
                maxAgeSeconds: 60 * 60 * 24 * 30,
              },
            },
          },

          {
            urlPattern: ({ request }) =>
              request.destination === "font",

            handler: "CacheFirst",

            options: {
              cacheName: "cortex-fonts",

              expiration: {
                maxEntries: 20,
                maxAgeSeconds: 60 * 60 * 24 * 365,
              },
            },
          },
        ],
      },

      /*
       * A PWA deve ser validada no build de produção.
       * Manter desativado em desenvolvimento evita service workers antigos
       * interferindo no Vite durante alterações de código.
       */
      devOptions: {
        enabled: false,
      },
    }),
  ],

  server: {
    host: "localhost",
    port: 5173,
    strictPort: true,
    headers: developmentSecurityHeaders,

    proxy: apiProxy,
  },

  preview: {
    host: "localhost",
    port: 4173,
    strictPort: true,
    headers: securityHeaders,
    proxy: apiProxy,
  },
});
