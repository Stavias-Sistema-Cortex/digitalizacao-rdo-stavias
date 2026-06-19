import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),

    VitePWA({
      registerType: "autoUpdate",

      includeAssets: [
        "favicon.svg",
        "icons.svg",
      ],

      manifest: {
        name: "Córtex Stavias",
        short_name: "Córtex",
        description:
          "Plataforma operacional offline-first para gestão de obras e RDOs.",
        start_url: "/",
        scope: "/",
        display: "standalone",
        orientation: "any",
        background_color: "#f7f8f5",
        theme_color: "#183b2a",

        icons: [
          {
            src: "/favicon.svg",
            sizes: "any",
            type: "image/svg+xml",
            purpose: "any",
          },
          {
            src: "/favicon.svg",
            sizes: "any",
            type: "image/svg+xml",
            purpose: "maskable",
          },
        ],
      },

      workbox: {
        navigateFallback: "/index.html",

        globPatterns: [
          "**/*.{js,css,html,svg,png,ico,webp,woff2}",
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
                maxAgeSeconds:
                  60 * 60 * 24 * 30,
              },
            },
          },
        ],
      },

      devOptions: {
        enabled: true,
        type: "module",
      },
    }),
  ],

  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,

    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },

  preview: {
    host: "127.0.0.1",
    port: 4173,
    strictPort: true,
  },
});
EOFcat > vite.config.ts <<'EOF'
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),

    VitePWA({
      registerType: "autoUpdate",

      includeAssets: [
        "favicon.svg",
        "icons.svg",
      ],

      manifest: {
        name: "Córtex Stavias",
        short_name: "Córtex",
        description:
          "Plataforma operacional offline-first para gestão de obras e RDOs.",
        start_url: "/",
        scope: "/",
        display: "standalone",
        orientation: "any",
        background_color: "#f7f8f5",
        theme_color: "#183b2a",

        icons: [
          {
            src: "/favicon.svg",
            sizes: "any",
            type: "image/svg+xml",
            purpose: "any",
          },
          {
            src: "/favicon.svg",
            sizes: "any",
            type: "image/svg+xml",
            purpose: "maskable",
          },
        ],
      },

      workbox: {
        navigateFallback: "/index.html",

        globPatterns: [
          "**/*.{js,css,html,svg,png,ico,webp,woff2}",
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
                maxAgeSeconds:
                  60 * 60 * 24 * 30,
              },
            },
          },
        ],
      },

      devOptions: {
        enabled: true,
        type: "module",
      },
    }),
  ],

  server: {
    host: "127.0.0.1",
    port: 5173,
    strictPort: true,

    proxy: {
      "/api": {
        target: "http://127.0.0.1:8080",
        changeOrigin: true,
      },
    },
  },

  preview: {
    host: "127.0.0.1",
    port: 4173,
    strictPort: true,
  },
});
