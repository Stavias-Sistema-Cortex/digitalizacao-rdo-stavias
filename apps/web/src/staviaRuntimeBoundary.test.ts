import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
} from "node:fs";
import { fileURLToPath } from "node:url";
import path from "node:path";

import { describe, expect, it } from "vitest";

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
  const withoutLegacyCleanup = LEGACY_LOCAL_STORAGE_KEYS.reduce(
    (current, key) => current.replaceAll(key, "[legacy-local-cleanup]"),
    text,
  )
    .replaceAll(LEGACY_SNAPSHOT_STORE, "[legacy-store-cleanup]")
    .replace(/stavias/gi, "[corporate-brand]");

  return [
    ...withoutLegacyCleanup.matchAll(/\bstavia\b/gi),
    ...withoutLegacyCleanup.matchAll(/\bStavia[A-Z][A-Za-z]*/g),
    ...withoutLegacyCleanup.matchAll(/stav\.ia/gi),
    ...withoutLegacyCleanup.matchAll(/features\/stavia/gi),
    ...withoutLegacyCleanup.matchAll(/obras-stavia-button/gi),
    ...withoutLegacyCleanup.matchAll(/\/stavia\//gi),
  ].map((match) => match[0]);
}

function corporateOccurrences(file: string): string[] {
  return readFileSync(file, "utf8")
    .split("\n")
    .filter((line) => /stavias/i.test(line));
}

describe("StavIA runtime boundary", () => {
  it("keeps assistant sources, hooks, controls and CSS outside the web runtime", () => {
    expect(existsSync(path.join(WEB_ROOT, "src/features/stavia"))).toBe(false);

    const violations = runtimeSourceFiles().flatMap((file) =>
      assistantTokens(readFileSync(file, "utf8")).map(
        (token) => `${relativeToRepository(file)}: ${token}`,
      ),
    );

    expect(violations).toEqual([]);
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

  it("keeps assistant assets, code and named chunks out of an existing Vite dist", () => {
    const distRoot = path.join(WEB_ROOT, "dist");
    if (!existsSync(distRoot)) {
      return;
    }

    const distFiles = listFiles(distRoot);
    const pathViolations = distFiles
      .map((file) => path.relative(distRoot, file).split(path.sep).join("/"))
      .filter((file) => /stavia(?!s)/i.test(file));
    const textFiles = distFiles.filter((file) =>
      /\.(?:css|html|js|json|mjs|svg|webmanifest)$/.test(file),
    );
    const textViolations = textFiles.flatMap((file) =>
      assistantTokens(readFileSync(file, "utf8")).map(
        (token) => `${path.relative(distRoot, file)}: ${token}`,
      ),
    );
    const distText = textFiles
      .map((file) => readFileSync(file, "utf8"))
      .join("\n");
    for (const key of LEGACY_LOCAL_STORAGE_KEYS) {
      expect(distText.split(key)).toHaveLength(2);
    }
    const localCleanupStart = distText.indexOf(LEGACY_LOCAL_STORAGE_KEYS[0]);
    const localCleanupBundle = distText.slice(
      Math.max(0, localCleanupStart - 40),
      localCleanupStart + 320,
    );
    expect(localCleanupBundle).toContain(LEGACY_LOCAL_STORAGE_KEYS[1]);
    expect(localCleanupBundle).toContain(".removeItem(");
    expect(localCleanupBundle).not.toMatch(/\.(?:getItem|setItem)\(/);
    const legacyStoreBundles = textFiles
      .map((file) => ({ file, content: readFileSync(file, "utf8") }))
      .filter(({ content }) => content.includes(LEGACY_SNAPSHOT_STORE));

    expect(pathViolations).toEqual([]);
    expect(textViolations).toEqual([]);
    expect(legacyStoreBundles).toHaveLength(1);
    const legacyBundle = legacyStoreBundles[0].content;
    const declaration = legacyBundle.match(
      /(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*[`"']stavia_snapshots[`"']/,
    );
    expect(declaration).not.toBeNull();
    const minifiedName = declaration?.[1] ?? "";
    expect(legacyBundle.split(minifiedName)).toHaveLength(4);
    expect(legacyBundle).toContain(
      `objectStoreNames.contains(${minifiedName})`,
    );
    expect(legacyBundle).toContain(`deleteObjectStore(${minifiedName})`);
    expect(legacyBundle).not.toMatch(
      new RegExp(
        `(?:createObjectStore|objectStore|get|put|transaction)\\(${minifiedName}\\)`,
      ),
    );
  });
});
