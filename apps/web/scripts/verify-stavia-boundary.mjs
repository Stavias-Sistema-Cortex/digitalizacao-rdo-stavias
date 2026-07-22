import {
  existsSync,
  readFileSync,
  readdirSync,
  statSync,
} from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const SCRIPT_PATH = fileURLToPath(import.meta.url);
const WEB_ROOT = path.resolve(path.dirname(SCRIPT_PATH), "..");
const REPOSITORY_ROOT = path.resolve(WEB_ROOT, "../..");

export const LEGACY_LOCAL_STORAGE_KEYS = [
  "cortex:stavia:chat:operacional",
  "cortex:stavia:last-context",
];
export const LEGACY_SNAPSHOT_STORE = "stavia_snapshots";

export const CORPORATE_SOURCE_ALLOWLIST = new Set([
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
export const CORPORATE_ASSET_ALLOWLIST = new Set([
  "apps/web/public/stavias-cortex-logo.png",
  "apps/web/src/assets/login/stavias-canteiro.png",
  "apps/web/src/assets/login/stavias-logo.png",
  "apps/web/src/assets/stavias-s-tile.png",
]);

const LOCAL_STORAGE_CLEANUP_PATH = "apps/web/src/lib/db/localDataScope.ts";
const DATABASE_MIGRATION_PATH = "apps/web/src/lib/db/cortexDb.ts";
const VERIFIER_PATHS = new Set([
  "apps/web/scripts/verify-stavia-boundary.mjs",
  "apps/web/scripts/verify-stavia-boundary.d.mts",
]);
const VERIFIER_REFERENCE = "scripts/verify-stavia-boundary.mjs";
const VITE_SOURCE_EXTENSIONS = new Set([
  ".css",
  ".cjs",
  ".cts",
  ".js",
  ".json",
  ".jsx",
  ".mjs",
  ".mts",
  ".ts",
  ".tsx",
]);
const TEXT_EXTENSIONS = new Set([
  ".css",
  ".cjs",
  ".cts",
  ".html",
  ".js",
  ".json",
  ".jsx",
  ".mjs",
  ".md",
  ".mts",
  ".ps1",
  ".sh",
  ".svg",
  ".ts",
  ".tsx",
  ".txt",
  ".webmanifest",
  ".xml",
  ".yaml",
  ".yml",
]);

function toPosix(value) {
  return value.split(path.sep).join("/");
}

function listFiles(root) {
  if (!existsSync(root)) {
    return [];
  }
  return readdirSync(root, { withFileTypes: true }).flatMap((entry) => {
    const entryPath = path.join(root, entry.name);
    return entry.isDirectory() ? listFiles(entryPath) : [entryPath];
  });
}

function isTextFile(file) {
  const basename = path.basename(file);
  return (
    TEXT_EXTENSIONS.has(path.extname(file).toLowerCase()) ||
    basename.startsWith(".env") ||
    basename === "Dockerfile"
  );
}

function occurrenceCount(text, value) {
  return text.split(value).length - 1;
}

export function findAssistantTokens(text) {
  const assistantRole =
    /useStavias\b|stavias(?:[./_-]*)(?:assistant|launcher|provider|hook|context|api|client|control|button|chat|query|prompt|completion)/gi;
  const singularAssistant = /stavia(?!s)|stav[._-]ia(?!s)/gi;
  return [...text.matchAll(singularAssistant), ...text.matchAll(assistantRole)]
    .map((match) => ({
      index: match.index ?? 0,
      token: match[0],
    }))
    .sort((left, right) => left.index - right.index);
}

function findCorporateTokens(text) {
  return [...text.matchAll(/stavias/gi)].map((match) => ({
    index: match.index ?? 0,
    token: match[0],
  }));
}

export function isViteRuntimeSourceFile(file) {
  const extension = path.extname(file).toLowerCase();
  if (!VITE_SOURCE_EXTENSIONS.has(extension)) {
    return false;
  }
  const basename = path.basename(file).toLowerCase();
  return !new RegExp(
    `\\.(?:test|spec)${extension.replace(".", "\\.")}$`,
  ).test(basename);
}

function sourceFiles(repositoryRoot = REPOSITORY_ROOT) {
  const webRoot = path.join(repositoryRoot, "apps/web");
  const source = listFiles(path.join(webRoot, "src"));
  const runtime = source.filter(isViteRuntimeSourceFile);
  const sourceAssets = source.filter(
    (file) => !isViteRuntimeSourceFile(file) && !isTextFile(file),
  );
  const publicFiles = listFiles(path.join(webRoot, "public"));
  const webSupport = listFiles(webRoot).filter((file) => {
    const relative = path.relative(webRoot, file);
    const firstSegment = relative.split(path.sep)[0];
    return ![
      "archive",
      "dist",
      "node_modules",
      "public",
      "src",
    ].includes(firstSegment);
  });
  const repositorySupport = readdirSync(repositoryRoot, {
    withFileTypes: true,
  })
    .filter(
      (entry) =>
        entry.isFile() &&
        (entry.name.startsWith(".env") ||
          /^compose(?:\..+)?\.ya?ml$/.test(entry.name)),
    )
    .map((entry) => path.join(repositoryRoot, entry.name));
  const scripts = listFiles(path.join(repositoryRoot, "scripts"));

  return [...new Set([
    ...runtime,
    ...sourceAssets,
    ...publicFiles,
    ...webSupport,
    ...repositorySupport,
    ...scripts,
  ])];
}

export function inspectLegacySource(files) {
  const violations = [];
  const runtimeFiles = files.filter((file) => !VERIFIER_PATHS.has(file.path));
  const byPath = new Map(runtimeFiles.map((file) => [file.path, file.content]));

  for (const key of LEGACY_LOCAL_STORAGE_KEYS) {
    const occurrences = runtimeFiles.flatMap((file) =>
      Array.from({ length: occurrenceCount(file.content, key) }, () => file.path),
    );
    if (
      occurrences.length !== 1 ||
      occurrences[0] !== LOCAL_STORAGE_CLEANUP_PATH
    ) {
      violations.push(
        `${key}: expected one declaration in ${LOCAL_STORAGE_CLEANUP_PATH}, found ${occurrences.join(", ") || "none"}`,
      );
    }
  }

  const localCleanup = byPath.get(LOCAL_STORAGE_CLEANUP_PATH) ?? "";
  const keyDeclaration = localCleanup.match(
    /(^|\n)\s*(export\s+)?const\s+([A-Za-z_$][\w$]*)\s*=\s*\[\s*["']cortex:stavia:chat:operacional["']\s*,\s*["']cortex:stavia:last-context["']\s*,?\s*\]\s*(?:as\s+const)?\s*;/m,
  );
  const keyCollectionName = keyDeclaration?.[3] ?? "";
  if (!keyDeclaration || keyDeclaration[2]) {
    violations.push(
      `${LOCAL_STORAGE_CLEANUP_PATH}: legacy key collection must be a private const`,
    );
  }
  if (
    keyCollectionName !== "LEGACY_PRIVATE_LOCAL_STORAGE_KEYS" ||
    occurrenceCount(localCleanup, keyCollectionName) !== 2
  ) {
    violations.push(
      `${LOCAL_STORAGE_CLEANUP_PATH}: legacy key collection has consumers outside its removal loop`,
    );
  }
  const removalLoop = keyCollectionName
    ? new RegExp(
        `for\\s*\\(\\s*const\\s+key\\s+of\\s+${keyCollectionName}\\s*\\)\\s*\\{\\s*target\\.removeItem\\(key\\);?\\s*\\}`,
      )
    : null;
  if (!removalLoop?.test(localCleanup)) {
    violations.push(`${LOCAL_STORAGE_CLEANUP_PATH}: missing fixed removal loop`);
  }
  if (/\b(?:getItem|setItem)\s*\(/.test(localCleanup)) {
    violations.push(`${LOCAL_STORAGE_CLEANUP_PATH}: active legacy storage access`);
  }
  if (keyCollectionName) {
    for (const file of runtimeFiles) {
      if (
        file.path !== LOCAL_STORAGE_CLEANUP_PATH &&
        new RegExp(`\\b${keyCollectionName}\\b`).test(file.content)
      ) {
        violations.push(
          `${file.path}: imports or aliases the private legacy key collection`,
        );
      }
    }
  }

  const storeOccurrences = runtimeFiles.flatMap((file) =>
    Array.from(
      { length: occurrenceCount(file.content, LEGACY_SNAPSHOT_STORE) },
      () => file.path,
    ),
  );
  if (
    storeOccurrences.length !== 1 ||
    storeOccurrences[0] !== DATABASE_MIGRATION_PATH
  ) {
    violations.push(
      `${LEGACY_SNAPSHOT_STORE}: expected one declaration in ${DATABASE_MIGRATION_PATH}, found ${storeOccurrences.join(", ") || "none"}`,
    );
  }

  const migration = byPath.get(DATABASE_MIGRATION_PATH) ?? "";
  if (
    !/const LEGACY_ASSISTANT_STORE = ["']stavia_snapshots["'];/.test(migration) ||
    occurrenceCount(migration, "LEGACY_ASSISTANT_STORE") !== 3 ||
    !migration.includes("objectStoreNames.contains(LEGACY_ASSISTANT_STORE)") ||
    !migration.includes("deleteObjectStore(LEGACY_ASSISTANT_STORE)")
  ) {
    violations.push(`${DATABASE_MIGRATION_PATH}: incomplete deletion-only migration`);
  }
  if (
    /(?:createObjectStore|objectStore|get|put|transaction)\(\s*LEGACY_ASSISTANT_STORE/.test(
      migration,
    )
  ) {
    violations.push(`${DATABASE_MIGRATION_PATH}: active legacy store access`);
  }

  return violations;
}

function maskVerifiedLegacySource(file) {
  let content = file.content;
  if (file.path === LOCAL_STORAGE_CLEANUP_PATH) {
    for (const key of LEGACY_LOCAL_STORAGE_KEYS) {
      content = content.replace(key, "[legacy-local-cleanup]");
    }
  }
  if (file.path === DATABASE_MIGRATION_PATH) {
    content = content.replace(LEGACY_SNAPSHOT_STORE, "[legacy-store-cleanup]");
  }
  return content;
}

export function inspectSourceBoundary(files) {
  const violations = inspectLegacySource(files);
  for (const file of files) {
    if (VERIFIER_PATHS.has(file.path)) {
      continue;
    }
    const pathTokens = findAssistantTokens(file.path);
    for (const match of pathTokens) {
      violations.push(`${file.path}: forbidden path token ${match.token}`);
    }
    const corporatePathTokens = findCorporateTokens(file.path);
    if (
      corporatePathTokens.length > 0 &&
      !CORPORATE_SOURCE_ALLOWLIST.has(file.path) &&
      !CORPORATE_ASSET_ALLOWLIST.has(file.path)
    ) {
      violations.push(
        `${file.path}: corporate Stavias path is not explicitly allowlisted`,
      );
    }
    const content = maskVerifiedLegacySource(file).replaceAll(
      VERIFIER_REFERENCE,
      "[assistant-boundary-verifier]",
    );
    for (const match of findAssistantTokens(content)) {
      violations.push(`${file.path}: forbidden content token ${match.token}`);
    }
    if (
      findCorporateTokens(content).length > 0 &&
      !CORPORATE_SOURCE_ALLOWLIST.has(file.path)
    ) {
      violations.push(
        `${file.path}: corporate Stavias content is not explicitly allowlisted`,
      );
    }
  }
  return violations;
}

export function verifySourceBoundary(repositoryRoot = REPOSITORY_ROOT) {
  const files = sourceFiles(repositoryRoot).map((file) => ({
    path: toPosix(path.relative(repositoryRoot, file)),
    content: isTextFile(file) ? readFileSync(file, "utf8") : "",
  }));
  const violations = inspectSourceBoundary(files);
  if (violations.length > 0) {
    throw new Error(`StavIA source boundary failed:\n${violations.join("\n")}`);
  }
}

export function verifyDist(distRoot = path.join(WEB_ROOT, "dist")) {
  if (!existsSync(distRoot) || !statSync(distRoot).isDirectory()) {
    throw new Error(`Vite dist is required and was not found: ${distRoot}`);
  }

  const distFiles = listFiles(distRoot);
  if (distFiles.length === 0) {
    throw new Error(`Vite dist is empty: ${distRoot}`);
  }

  const violations = [];
  for (const file of distFiles) {
    const relative = toPosix(path.relative(distRoot, file));
    for (const match of findAssistantTokens(relative)) {
      violations.push(`${relative}: forbidden path token ${match.token}`);
    }
    if (
      findCorporateTokens(relative).length > 0 &&
      !/^(?:stavias-cortex-logo\.png|assets\/(?:stavias-s-tile|stavias-canteiro|stavias-logo)-[A-Za-z0-9_-]+\.[A-Za-z0-9]+)$/.test(
        relative,
      )
    ) {
      violations.push(`${relative}: corporate Stavias artifact is not allowlisted`);
    }
  }

  const textFiles = distFiles
    .filter(isTextFile)
    .map((file) => ({
      path: toPosix(path.relative(distRoot, file)),
      content: readFileSync(file, "utf8"),
    }));
  const combined = textFiles.map((file) => file.content).join("\n");

  for (const key of LEGACY_LOCAL_STORAGE_KEYS) {
    if (occurrenceCount(combined, key) !== 1) {
      violations.push(`${key}: expected exactly one compiled cleanup occurrence`);
    }
  }
  const firstKey = combined.indexOf(LEGACY_LOCAL_STORAGE_KEYS[0]);
  const cleanupWindow = combined.slice(Math.max(0, firstKey - 80), firstKey + 420);
  if (
    !cleanupWindow.includes(LEGACY_LOCAL_STORAGE_KEYS[1]) ||
    !cleanupWindow.includes(".removeItem(") ||
    /\.(?:getItem|setItem)\(/.test(cleanupWindow)
  ) {
    violations.push("compiled localStorage cleanup is not deletion-only");
  }

  if (occurrenceCount(combined, LEGACY_SNAPSHOT_STORE) !== 1) {
    violations.push(`${LEGACY_SNAPSHOT_STORE}: expected one compiled migration occurrence`);
  }
  const storeFile = textFiles.find((file) =>
    file.content.includes(LEGACY_SNAPSHOT_STORE),
  );
  if (storeFile) {
    const declaration = storeFile.content.match(
      /(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*=\s*[`"']stavia_snapshots[`"']/,
    );
    const name = declaration?.[1] ?? "";
    if (
      !name ||
      occurrenceCount(storeFile.content, name) !== 3 ||
      !storeFile.content.includes(`objectStoreNames.contains(${name})`) ||
      !storeFile.content.includes(`deleteObjectStore(${name})`) ||
      new RegExp(
        `(?:createObjectStore|objectStore|get|put|transaction)\\(\\s*${name}\\b`,
      ).test(storeFile.content)
    ) {
      violations.push(`${storeFile.path}: compiled store migration is not deletion-only`);
    }
  }

  for (const file of textFiles) {
    let content = file.content;
    for (const key of LEGACY_LOCAL_STORAGE_KEYS) {
      content = content.replace(key, "[legacy-local-cleanup]");
    }
    content = content.replace(LEGACY_SNAPSHOT_STORE, "[legacy-store-cleanup]");
    for (const match of findAssistantTokens(content)) {
      violations.push(`${file.path}: forbidden content token ${match.token}`);
    }
  }

  if (violations.length > 0) {
    throw new Error(`StavIA dist boundary failed:\n${violations.join("\n")}`);
  }
}

function runCli() {
  verifySourceBoundary();
  if (process.argv.includes("--dist")) {
    verifyDist();
  }
  process.stdout.write(
    process.argv.includes("--dist")
      ? "StavIA source and dist boundary verified.\n"
      : "StavIA source boundary verified.\n",
  );
}

if (path.resolve(process.argv[1] ?? "") === SCRIPT_PATH) {
  try {
    runCli();
  } catch (error) {
    process.stderr.write(`${error instanceof Error ? error.message : error}\n`);
    process.exitCode = 1;
  }
}
