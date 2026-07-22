import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  readdirSync,
  rmSync,
  statSync,
  writeFileSync,
} from "node:fs";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import path from "node:path";

import { describe, expect, it } from "vitest";
import {
  CORPORATE_ASSET_ALLOWLIST,
  CORPORATE_SOURCE_ALLOWLIST,
  findAssistantTokens,
  inspectSourceBoundary,
  isViteRuntimeSourceFile,
  verifyDist,
  verifySourceBoundary,
} from "../scripts/verify-stavia-boundary.mjs";

const WEB_ROOT = fileURLToPath(new URL("..", import.meta.url));
const REPOSITORY_ROOT = path.resolve(WEB_ROOT, "../..");

const LEGACY_LOCAL_STORAGE_KEYS = [
  "cortex:stavia:chat:operacional",
  "cortex:stavia:last-context",
] as const;
const LEGACY_SNAPSHOT_STORE = "stavia_snapshots";

function validCleanupFixtures(): Array<{ path: string; content: string }> {
  return [
    {
      path: "apps/web/src/lib/db/localDataScope.ts",
      content: `const LEGACY_PRIVATE_LOCAL_STORAGE_KEYS = [\n  "${LEGACY_LOCAL_STORAGE_KEYS[0]}",\n  "${LEGACY_LOCAL_STORAGE_KEYS[1]}",\n] as const;\nexport function clear(target) {\n  for (const key of LEGACY_PRIVATE_LOCAL_STORAGE_KEYS) {\n    target.removeItem(key);\n  }\n}`,
    },
    {
      path: "apps/web/src/lib/db/cortexDb.ts",
      content: `const LEGACY_ASSISTANT_STORE = "${LEGACY_SNAPSHOT_STORE}"; if (database.objectStoreNames.contains(LEGACY_ASSISTANT_STORE)) database.deleteObjectStore(LEGACY_ASSISTANT_STORE);`,
    },
  ];
}

function writeBoundaryRepository(
  runtimePath: string,
  runtimeContent: string,
): string {
  const repositoryRoot = mkdtempSync(
    path.join(tmpdir(), "cortex-stavia-boundary-"),
  );
  for (const fixture of validCleanupFixtures()) {
    const destination = path.join(repositoryRoot, fixture.path);
    mkdirSync(path.dirname(destination), { recursive: true });
    writeFileSync(destination, fixture.content);
  }
  const runtimeDestination = path.join(
    repositoryRoot,
    "apps/web/src",
    runtimePath,
  );
  mkdirSync(path.dirname(runtimeDestination), { recursive: true });
  writeFileSync(runtimeDestination, runtimeContent);
  return repositoryRoot;
}

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
  return listFiles(path.join(WEB_ROOT, "src")).filter(isViteRuntimeSourceFile);
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

  it("scans every Vite source extension and only excludes terminal test files", () => {
    const runtimeVariants = [
      "runtime.css",
      "runtime.json",
      "runtime.ts",
      "runtime.tsx",
      "runtime.js",
      "runtime.jsx",
      "runtime.mjs",
      "runtime.mts",
      "runtime.cjs",
      "runtime.cts",
      "runtime.test-helper.ts",
    ];
    const scanResults = runtimeVariants.map((runtimePath) => {
      const repositoryRoot = writeBoundaryRepository(
        runtimePath,
        "StaviaLauncherProvider",
      );
      try {
        expect(() => verifySourceBoundary(repositoryRoot)).toThrow(
          /forbidden content token/i,
        );
        return runtimePath;
      } finally {
        rmSync(repositoryRoot, { recursive: true, force: true });
      }
    });

    expect(scanResults).toEqual(runtimeVariants);

    for (const terminalTestPath of [
      "runtime.test.ts",
      "runtime.spec.tsx",
      "runtime.test.jsx",
      "runtime.spec.mts",
    ]) {
      const repositoryRoot = writeBoundaryRepository(
        terminalTestPath,
        "StaviaLauncherProvider",
      );
      try {
        expect(() => verifySourceBoundary(repositoryRoot)).not.toThrow();
      } finally {
        rmSync(repositoryRoot, { recursive: true, force: true });
      }
    }
  });

  it("rejects assistant roles hidden behind the plural corporate brand", () => {
    const assistantRoleFixtures = [
      "StaviasLauncherProvider",
      "useStavias",
      "useStaviasLauncher",
      "StaviasAssistantContext",
      "StaviasApiClient",
      "StaviasChatControl",
    ];

    for (const assistantRole of assistantRoleFixtures) {
      expect(assistantTokens(assistantRole), assistantRole).not.toEqual([]);
      expect(
        inspectSourceBoundary([
          ...validCleanupFixtures(),
          {
            path: "apps/web/src/features/home/HomePage.tsx",
            content: assistantRole,
          },
        ]),
        assistantRole,
      ).not.toEqual([]);
    }
  });

  it("keeps corporate Stavias occurrences scoped to approved roles and paths", () => {
    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/src/features/home/HomePage.tsx",
          content: `import { MaisStaviasCard } from "./MaisStaviasCard";`,
        },
        {
          path: "apps/web/src/features/home/MaisStaviasCard.tsx",
          content: `export function MaisStaviasCard() { return "https://www.stavias.com.br"; }`,
        },
        {
          path: "apps/web/public/stavias-cortex-logo.png",
          content: "",
        },
      ]),
    ).toEqual([]);

    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/src/features/obras/UnapprovedBrand.ts",
          content: `export const brand = "Stavias";`,
        },
      ]),
    ).not.toEqual([]);
    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/public/stavias-unapproved-logo.png",
          content: "",
        },
      ]),
    ).not.toEqual([]);
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

    const cleanupFixtures = validCleanupFixtures();
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

  it("rejects exported or imported aliases of the private legacy key collection", () => {
    const exportedCleanup = validCleanupFixtures();
    exportedCleanup[0] = {
      ...exportedCleanup[0],
      content: exportedCleanup[0].content.replace(
        "const LEGACY_PRIVATE_LOCAL_STORAGE_KEYS",
        "export const LEGACY_PRIVATE_LOCAL_STORAGE_KEYS",
      ),
    };
    expect(inspectSourceBoundary(exportedCleanup)).not.toEqual([]);

    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/src/active-regression.ts",
          content: `import { LEGACY_PRIVATE_LOCAL_STORAGE_KEYS as keys } from "./lib/db/localDataScope"; localStorage.getItem(keys[0]);`,
        },
      ]),
    ).not.toEqual([]);
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

  it("makes every Vite build script run the explicit dist verifier", () => {
    const packageJson = JSON.parse(
      readFileSync(path.join(WEB_ROOT, "package.json"), "utf8"),
    ) as { scripts?: Record<string, string> };
    const viteBuildScripts = Object.entries(packageJson.scripts ?? {}).filter(
      ([, command]) => /\bvite build\b/.test(command),
    );

    expect(viteBuildScripts.length).toBeGreaterThan(0);
    for (const [name, command] of viteBuildScripts) {
      expect(command, name).toMatch(
        /vite build\s*&&\s*node scripts\/verify-stavia-boundary\.mjs --dist$/,
      );
    }
    expect(
      existsSync(
        path.join(WEB_ROOT, "scripts/verify-stavia-boundary.mjs"),
      ),
    ).toBe(true);
  });
});
