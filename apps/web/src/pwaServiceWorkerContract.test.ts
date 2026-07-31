import { execFileSync } from "node:child_process";
import {
  mkdtempSync,
  readFileSync,
  rmSync,
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

import { afterAll, beforeAll, describe, expect, it } from "vitest";

const WEB_ROOT = fileURLToPath(new URL("..", import.meta.url));
const VITE_BIN = path.join(
  WEB_ROOT,
  "node_modules",
  "vite",
  "bin",
  "vite.js",
);

type RegisteredRoute = {
  route: unknown;
  strategy: StrategyDouble | undefined;
  method: string | undefined;
};

class StrategyDouble {
  readonly kind: string;
  readonly options: Record<string, unknown>;

  constructor(kind: string, options: Record<string, unknown> = {}) {
    this.kind = kind;
    this.options = options;
  }
}

class NavigationRouteDouble {
  readonly kind = "NavigationRoute";
}

type WorkerHarness = {
  lifecycle: {
    clientsClaim: number;
    skipWaiting: number;
  };
  messageListeners: Array<(event: { data?: unknown }) => void>;
  registrations: RegisteredRoute[];
};

function executeGeneratedWorker(workerSource: string): WorkerHarness {
  const registrations: RegisteredRoute[] = [];
  const messageListeners: Array<(event: { data?: unknown }) => void> = [];
  const lifecycle = {
    clientsClaim: 0,
    skipWaiting: 0,
  };
  const strategy = (kind: string) =>
    class extends StrategyDouble {
      constructor(options: Record<string, unknown> = {}) {
        super(kind, options);
      }
    };
  const plugin = (kind: string) =>
    class extends StrategyDouble {
      constructor(options: Record<string, unknown> = {}) {
        super(kind, options);
      }
    };

  const workbox = {
    CacheFirst: strategy("CacheFirst"),
    CacheableResponsePlugin: plugin("CacheableResponsePlugin"),
    ExpirationPlugin: plugin("ExpirationPlugin"),
    NavigationRoute: NavigationRouteDouble,
    NetworkFirst: strategy("NetworkFirst"),
    PrecacheFallbackPlugin: plugin("PrecacheFallbackPlugin"),
    StaleWhileRevalidate: strategy("StaleWhileRevalidate"),
    cleanupOutdatedCaches() {},
    clientsClaim() {
      lifecycle.clientsClaim += 1;
    },
    createHandlerBoundToURL(url: string) {
      return { kind: "PrecacheHandler", url };
    },
    precacheAndRoute() {},
    registerRoute(
      route: unknown,
      registeredStrategy?: StrategyDouble,
      method?: string,
    ) {
      registrations.push({
        route,
        strategy: registeredStrategy,
        method,
      });
    },
    skipWaiting() {
      lifecycle.skipWaiting += 1;
    },
  };
  const workerGlobal = {
    addEventListener(
      type: string,
      listener: (event: { data?: unknown }) => void,
    ) {
      if (type === "message") {
        messageListeners.push(listener);
      }
    },
    define(
      _dependencies: string[],
      factory: (runtime: typeof workbox) => void,
    ) {
      factory(workbox);
    },
    location: {
      origin: "https://cortex-stavias.pages.dev",
    },
    skipWaiting() {
      lifecycle.skipWaiting += 1;
    },
  };

  vm.runInNewContext(workerSource, {
    define: workerGlobal.define,
    self: workerGlobal,
  });

  return { lifecycle, messageListeners, registrations };
}

describe("generated PWA service worker contract", () => {
  let distRoot: string;
  let worker: WorkerHarness;

  beforeAll(() => {
    distRoot = mkdtempSync(
      path.join(tmpdir(), "cortex-pwa-worker-contract-"),
    );
    execFileSync(
      process.execPath,
      [VITE_BIN, "build", "--outDir", distRoot],
      {
        cwd: WEB_ROOT,
        env: {
          ...process.env,
          VITE_CORTEX_API_BASE_URL: "/api",
          VITE_CORTEX_AUTH_MODE: "postgresql",
          VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256: "A".repeat(43),
        },
        stdio: "pipe",
      },
    );
    worker = executeGeneratedWorker(
      readFileSync(path.join(distRoot, "sw.js"), "utf8"),
    );
  }, 30_000);

  afterAll(() => {
    rmSync(distRoot, { recursive: true, force: true });
  });

  it("leaves a new worker waiting instead of replacing active offline clients", () => {
    expect(worker.lifecycle).toEqual({
      clientsClaim: 0,
      skipWaiting: 0,
    });
    expect(worker.messageListeners).toHaveLength(1);

    const [handleMessage] = worker.messageListeners;
    handleMessage({ data: { type: "NOT_AN_UPDATE" } });
    expect(worker.lifecycle.skipWaiting).toBe(0);
    handleMessage({ data: { type: "SKIP_WAITING" } });
    expect(worker.lifecycle.skipWaiting).toBe(1);
  });

  it("uses NetworkFirst with an offline shell only for non-API navigations", () => {
    const networkNavigations = worker.registrations.filter(
      ({ strategy: registeredStrategy }) =>
        registeredStrategy?.kind === "NetworkFirst",
    );
    expect(networkNavigations).toHaveLength(1);

    const [navigation] = networkNavigations;
    expect(navigation.method).toBe("GET");
    expect(navigation.route).toBeTypeOf("function");
    const matches = navigation.route as (context: {
      request: { mode: string };
      url: URL;
    }) => boolean;
    expect(matches({
      request: { mode: "navigate" },
      url: new URL("https://cortex-stavias.pages.dev/obras"),
    })).toBe(true);
    expect(matches({
      request: { mode: "navigate" },
      url: new URL("https://cortex-stavias.pages.dev/api"),
    })).toBe(false);
    expect(matches({
      request: { mode: "navigate" },
      url: new URL("https://cortex-stavias.pages.dev/api/health"),
    })).toBe(false);
    expect(matches({
      request: { mode: "cors" },
      url: new URL("https://cortex-stavias.pages.dev/obras"),
    })).toBe(false);

    const plugins = navigation.strategy?.options.plugins;
    expect(Array.isArray(plugins)).toBe(true);
    expect(
      (plugins as StrategyDouble[]).some(
        (candidate) =>
          candidate.kind === "PrecacheFallbackPlugin" &&
          candidate.options.fallbackURL === "/index.html",
      ),
    ).toBe(true);
    expect(
      worker.registrations.some(
        ({ route }) => route instanceof NavigationRouteDouble,
      ),
    ).toBe(false);
  });

  it("guarda apenas os tiles de mapa realmente exibidos, com validade limitada", () => {
    // MapLibre e Mapbox buscam tile por `fetch`: destination vazio.
    const contexto = (href: string, destination = "") => ({
      request: { mode: "no-cors", destination },
      url: new URL(href),
    });
    const tileRoutes = worker.registrations.filter(({ route }) => {
      if (typeof route !== "function") return false;
      const matches = route as (context: ReturnType<typeof contexto>) => boolean;
      return matches(contexto("https://api.maptiler.com/maps/x.png"));
    });
    expect(tileRoutes).toHaveLength(1);

    const [tiles] = tileRoutes;
    const matches = tiles.route as (
      context: ReturnType<typeof contexto>,
    ) => boolean;
    expect(matches(contexto("https://api.mapbox.com/v4/tile.pbf"))).toBe(true);
    expect(
      matches(contexto("https://a.tile.openstreetmap.org/14/1/1.png")),
    ).toBe(true);
    // A própria origem continua fora desta regra.
    expect(matches(contexto("https://cortex-stavias.pages.dev/obras"))).toBe(
      false,
    );

    expect(tiles.strategy?.kind).toBe("CacheFirst");
    expect(tiles.strategy?.options.cacheName).toBe("cortex-map-tiles");

    const plugins = (tiles.strategy?.options.plugins ?? []) as StrategyDouble[];
    const expiration = plugins.find(
      (candidate) => candidate.kind === "ExpirationPlugin",
    );
    expect(expiration?.options).toMatchObject({
      maxEntries: 500,
      maxAgeSeconds: 604800,
    });
    // Tiles cruzam origem e chegam opacos; sem status 0 nada seria guardado.
    const cacheable = plugins.find(
      (candidate) => candidate.kind === "CacheableResponsePlugin",
    );
    expect(cacheable?.options).toMatchObject({ statuses: [0, 200] });
  });

  it("roteia o tile <img> do Leaflet para o cache de tiles, não para o de imagens", () => {
    // O Leaflet carrega tile por <img>, então o pedido chega com
    // destination "image". O Workbox usa o PRIMEIRO match: se a regra genérica
    // de imagem vier antes, o tile disputa as 100 entradas do cache da
    // aplicação e some do mapa offline.
    const tileDeImagem = {
      request: { mode: "no-cors", destination: "image" },
      url: new URL("https://a.tile.openstreetmap.org/14/1/1.png"),
    };

    const primeiro = worker.registrations.find(
      ({ route }) =>
        typeof route === "function" &&
        (route as (context: typeof tileDeImagem) => boolean)(tileDeImagem),
    );

    expect(primeiro?.strategy?.options.cacheName).toBe("cortex-map-tiles");

    // E uma imagem da própria aplicação continua no cache de imagens.
    const imagemDoApp = {
      request: { mode: "no-cors", destination: "image" },
      url: new URL("https://cortex-stavias.pages.dev/pwa-192x192.png"),
    };
    const paraImagem = worker.registrations.find(
      ({ route }) =>
        typeof route === "function" &&
        (route as (context: typeof imagemDoApp) => boolean)(imagemDoApp),
    );
    expect(paraImagem?.strategy?.options.cacheName).toBe("cortex-images");
  });
});
