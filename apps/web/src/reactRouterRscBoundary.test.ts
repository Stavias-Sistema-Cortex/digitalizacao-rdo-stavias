import { existsSync, readFileSync, readdirSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

import { describe, expect, it } from "vitest";

const WEB_ROOT = fileURLToPath(new URL("..", import.meta.url));
const SOURCE_ROOT = path.join(WEB_ROOT, "src");

// GHSA-qwww-vcr4-c8h2 only applies when unstable React Server Components
// router APIs are used. The Cortex web client is a Vite SPA, so keep that
// boundary explicit after the patched React Router migration.
const UNSTABLE_RSC_ROUTER_APIS = [
  "RSCHydratedRouter",
  "RSCStaticRouter",
  "RSCStaticRouterProvider",
  "createCallServer",
  "createClientRoutes",
  "createRSCStaticHandler",
  "handleRSCRequest",
  "matchRSCServerRequest",
  "unstable_RSC",
] as const;

function sourceFiles(root: string): string[] {
  if (!existsSync(root)) return [];

  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(root, entry.name);
    if (entry.isDirectory()) return sourceFiles(entryPath);
    return /\.(?:[cm]?[jt]sx?)$/.test(entry.name) &&
      !/\.(?:test|spec)\.[cm]?[jt]sx?$/.test(entry.name)
      ? [entryPath]
      : [];
  });
}

function routerImports(source: string): string[] {
  const matches = source.matchAll(
    /import\s*(?:type\s*)?\{(?<specifiers>[\s\S]*?)\}\s*from\s*["']react-router(?:-dom)?["']/g,
  );
  return [...matches].map((match) => match.groups?.specifiers ?? "");
}

describe("React Router RSC boundary", () => {
  it("recognizes unstable RSC router imports, including aliases", () => {
    const fixtures = [
      "import { RSCHydratedRouter } from 'react-router-dom';",
      "import { unstable_RSC as rsc } from 'react-router';",
    ];

    for (const fixture of fixtures) {
      const imports = routerImports(fixture).join("\n");
      expect(imports).toMatch(
        new RegExp(`\\b(?:${UNSTABLE_RSC_ROUTER_APIS.join("|")})\\b`),
      );
    }
  });

  it("does not import unstable React Server Components router APIs", () => {
    const runtimeSources = sourceFiles(SOURCE_ROOT).map((file) =>
      readFileSync(file, "utf8"),
    );
    const importedSpecifiers = runtimeSources.flatMap(routerImports);

    expect(importedSpecifiers).not.toEqual([]);

    for (const content of runtimeSources) {
      expect(content).not.toMatch(
        /from\s*["']react-router-dom["']/,
      );
    }

    const rscApiPattern = new RegExp(
      `\\b(?:${UNSTABLE_RSC_ROUTER_APIS.join("|")})\\b`,
    );
    for (const specifiers of importedSpecifiers) {
      expect(specifiers).not.toMatch(rscApiPattern);
    }
  });
});
