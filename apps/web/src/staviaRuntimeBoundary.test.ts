import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
} from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

import { describe, expect, it } from "vitest";
import {
  findAssistantTokens,
  inspectSourceBoundary,
  verifyDist,
  verifySourceBoundary,
} from "../scripts/verify-stavia-boundary.mjs";

const WEB_ROOT = fileURLToPath(new URL("..", import.meta.url));
const REPOSITORY_ROOT = path.resolve(WEB_ROOT, "../..");

const CORPORATE_SOURCE_ALLOWLIST = new Set([
  "apps/web/index.html",
  "apps/web/vite.config.ts",
  "apps/web/src/components/shell/CortexShell.tsx",
  "apps/web/src/features/auth/ActivationPage.tsx",
  "apps/web/src/features/auth/LoginPage.css",
  "apps/web/src/features/auth/LoginPage.tsx",
  "apps/web/src/features/auth/OfflineUnlockPage.tsx",
  "apps/web/src/features/home/HomePage.tsx",
  "apps/web/src/features/home/MaisStaviasCard.tsx",
  "apps/web/src/features/rdos/RdoLocalList.tsx",
  "apps/web/src/index.css",
  "compose.production.example.yml",
  ".env.postgresql.example",
  "scripts/dev/migrate-postgres-cortex.sh",
  "scripts/dev/postgres-cortex-common.sh",
  "scripts/smoke-deploy.sh",
]);

const CORPORATE_ASSET_ALLOWLIST = new Set([
  "apps/web/public/stavias-cortex-logo.png",
  "apps/web/src/assets/login/stavias-canteiro.png",
  "apps/web/src/assets/login/stavias-logo.png",
  "apps/web/src/assets/stavias-s-tile.png",
]);

const LEGACY_LOCAL_STORAGE_KEYS = [
  "cortex:stavia:chat:operacional",
  "cortex:stavia:last-context",
] as const;
const LEGACY_SNAPSHOT_STORE = "stavia_snapshots";

function relativeToRepository(file: string): string {
  return path.relative(REPOSITORY_ROOT, file).split(path.sep).join("/");
}

function listFiles(root: string): string[] {
  if (!existsSync(root)) {
    return [];
  }

  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(root, entry.name);
    return entry.isDirectory() ? listFiles(entryPath) : [entryPath];
  });
}

function runtimeSourceFiles(): string[] {
  return listFiles(path.join(WEB_ROOT, "src")).filter((file) => {
    const basename = path.basename(file);
    return (
      /\.(?:css|json|ts|tsx)$/.test(file) &&
      !basename.includes(".test.") &&
      !basename.includes(".spec.")
    );
  });
}

function supportFiles(): string[] {
  const explicit = [
    path.join(WEB_ROOT, "index.html"),
    path.join(WEB_ROOT, "package.json"),
    path.join(WEB_ROOT, "vite.config.ts"),
    path.join(WEB_ROOT, ".env.example"),
    path.join(REPOSITORY_ROOT, ".env.postgresql.example"),
    path.join(REPOSITORY_ROOT, "compose.production.example.yml"),
  ];
  return [
    ...explicit.filter(existsSync),
    ...listFiles(path.join(REPOSITORY_ROOT, "scripts")),
  ].filter((file) => !statSync(file).isDirectory());
}

function assistantTokens(text: string): string[] {
  return findAssistantTokens(text).map((match) => match.token);
}

function corporateOccurrences(file: string): string[] {
  return readFileSync(file, "utf8")
    .split("\n")
    .filter((line) => /stavias/i.test(line));
}

describe("StavIA runtime boundary", () => {
  it("recognizes assistant spellings in source, support and path fixtures", () => {
    const forbiddenFixtures = [
      "useStaviaLauncher()",
      "staviaLauncherContext.ts",
      "StaviaLauncherProvider",
      "features/stavia/client.ts",
      "fetch('/api/stavia/query')",
      "VITE_STAVIA_API_URL=https://example.invalid",
      "Abrir na StavIA",
      "abrir-stavia.html",
      "stav.ia-client.css",
    ];

    for (const fixture of forbiddenFixtures) {
      expect(assistantTokens(fixture), fixture).not.toEqual([]);
    }
  });

  it("does not hide a second or active use of a legacy identifier", () => {
    for (const key of LEGACY_LOCAL_STORAGE_KEYS) {
      expect(
        assistantTokens(
          `const cleanup = "${key}"; localStorage.getItem("${key}")`,
        ),
        key,
      ).not.toEqual([]);
    }

    expect(
      assistantTokens(
        `const cleanup = "${LEGACY_SNAPSHOT_STORE}"; database.objectStore("${LEGACY_SNAPSHOT_STORE}")`,
      ),
    ).not.toEqual([]);

    const cleanupFixtures = [
      {
        path: "apps/web/src/lib/db/localDataScope.ts",
        content: `const keys = ["${LEGACY_LOCAL_STORAGE_KEYS[0]}", "${LEGACY_LOCAL_STORAGE_KEYS[1]}"]; function clear(target) { for (const key of keys) target.removeItem(key); }`,
      },
      {
        path: "apps/web/src/lib/db/cortexDb.ts",
        content: `const LEGACY_ASSISTANT_STORE = "${LEGACY_SNAPSHOT_STORE}"; if (database.objectStoreNames.contains(LEGACY_ASSISTANT_STORE)) database.deleteObjectStore(LEGACY_ASSISTANT_STORE);`,
      },
    ];
    const activeRegressions = [
      `localStorage.getItem("${LEGACY_LOCAL_STORAGE_KEYS[0]}")`,
      `localStorage.setItem("${LEGACY_LOCAL_STORAGE_KEYS[1]}", "private")`,
      `database.objectStore("${LEGACY_SNAPSHOT_STORE}")`,
      `database.createObjectStore("${LEGACY_SNAPSHOT_STORE}")`,
    ];
    for (const activeRegression of activeRegressions) {
      const fixtureViolations = inspectSourceBoundary([
        ...cleanupFixtures,
        {
          path: "apps/web/src/active-regression.ts",
          content: activeRegression,
        },
      ]);
      expect(fixtureViolations, activeRegression).not.toEqual([]);
    }
  });

  it("keeps assistant sources, hooks, controls and CSS outside the web runtime", () => {
    expect(existsSync(path.join(WEB_ROOT, "src/features/stavia"))).toBe(false);

    expect(() => verifySourceBoundary(REPOSITORY_ROOT)).not.toThrow();
  });

  it("allows the corporate STAVIAS brand only in the explicit source and asset allowlists", () => {
    const corporateSourceFiles = [...runtimeSourceFiles(), ...supportFiles()]
      .filter((file) => corporateOccurrences(file).length > 0)
      .map(relativeToRepository);
    const sourceViolations = corporateSourceFiles
      .filter((file) => !CORPORATE_SOURCE_ALLOWLIST.has(file));

    const corporateAssetFiles = [
      ...listFiles(path.join(WEB_ROOT, "public")),
      ...listFiles(path.join(WEB_ROOT, "src/assets")),
    ]
      .map(relativeToRepository)
      .filter((file) => /stavia/i.test(file));
    const assetViolations = corporateAssetFiles
      .filter((file) => !CORPORATE_ASSET_ALLOWLIST.has(file));

    expect(sourceViolations).toEqual([]);
    expect(assetViolations).toEqual([]);
    expect([...CORPORATE_SOURCE_ALLOWLIST].sort()).toEqual(
      corporateSourceFiles.sort(),
    );
    expect([...CORPORATE_ASSET_ALLOWLIST].sort()).toEqual(
      corporateAssetFiles.sort(),
    );
  });

  it("keeps legacy assistant identifiers deletion-only", () => {
    const localCleanupPath = path.join(
      WEB_ROOT,
      "src/lib/db/localDataScope.ts",
    );
    const localCleanup = readFileSync(localCleanupPath, "utf8");
    for (const key of LEGACY_LOCAL_STORAGE_KEYS) {
      expect(localCleanup.split(key)).toHaveLength(2);
    }
    expect(localCleanup).not.toMatch(/\b(?:getItem|setItem)\s*\(/);
    expect(localCleanup).toContain("target.removeItem(key)");

    const databaseSource = readFileSync(
      path.join(WEB_ROOT, "src/lib/db/cortexDb.ts"),
      "utf8",
    );
    expect(databaseSource.split(LEGACY_SNAPSHOT_STORE)).toHaveLength(2);
    expect(databaseSource).toMatch(
      /const LEGACY_ASSISTANT_STORE = ["']stavia_snapshots["'];/,
    );
    expect(databaseSource.split("LEGACY_ASSISTANT_STORE")).toHaveLength(4);
    expect(databaseSource).toContain(
      "objectStoreNames.contains(LEGACY_ASSISTANT_STORE)",
    );
    expect(databaseSource).toContain(
      "deleteObjectStore(LEGACY_ASSISTANT_STORE)",
    );
    expect(databaseSource).not.toMatch(
      /(?:createObjectStore|objectStore)\(\s*["']stavia_snapshots["']\s*[,)]/,
    );
  });

  it("makes missing dist an error and verifies a real dist when present", () => {
    const distRoot = path.join(WEB_ROOT, "dist");
    expect(() => verifyDist(path.join(WEB_ROOT, "dist-does-not-exist"))).toThrow(
      /dist is required/,
    );
    if (!existsSync(distRoot)) {
      return;
    }

    expect(() => verifyDist(distRoot)).not.toThrow();
  });

  it("makes the build run an explicit verifier after Vite emits dist", () => {
    const packageJson = JSON.parse(
      readFileSync(path.join(WEB_ROOT, "package.json"), "utf8"),
    ) as { scripts?: Record<string, string> };
    const build = packageJson.scripts?.build ?? "";

    expect(build).toMatch(
      /vite build\s*&&\s*node scripts\/verify-stavia-boundary\.mjs --dist$/,
    );
    expect(
      existsSync(
        path.join(WEB_ROOT, "scripts/verify-stavia-boundary.mjs"),
      ),
    ).toBe(true);
  });
});
