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
});
