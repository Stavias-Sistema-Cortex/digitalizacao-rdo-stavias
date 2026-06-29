import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

const cortexApiTarget =
  process.env.CORTEX_API_TARGET ??
  "http://127.0.0.1:8080";

const apiProxy = {
  "/api": {
    target: cortexApiTarget,
    changeOrigin: true,
  },
};

export default defineConfig({
  plugins: [
    react(),

    VitePWA({
      registerType: "autoUpdate",

      includeAssets: [
        "favicon.svg",
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
        navigateFallback: "/index.html",

        globPatterns: [
          "**/*.{js,css,html,svg,png,ico,webp,woff,woff2,ttf}",
        ],

        cleanupOutdatedCaches: true,

        runtimeCaching: [
          {
            urlPattern: ({ request }) =>
              request.mode === "navigate",

            handler: "NetworkFirst",

            options: {
              cacheName: "cortex-pages",
              networkTimeoutSeconds: 3,
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
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,

    proxy: apiProxy,
  },

  preview: {
    host: "127.0.0.1",
    port: 4173,
    strictPort: true,
    proxy: apiProxy,
  },
});
