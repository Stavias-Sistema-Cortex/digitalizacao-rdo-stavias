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

const LOCAL_STORAGE_CLEANUP_PATH = "apps/web/src/lib/db/localDataScope.ts";
const DATABASE_MIGRATION_PATH = "apps/web/src/lib/db/cortexDb.ts";
const VERIFIER_PATHS = new Set([
  "apps/web/scripts/verify-stavia-boundary.mjs",
  "apps/web/scripts/verify-stavia-boundary.d.mts",
]);
const VERIFIER_REFERENCE = "scripts/verify-stavia-boundary.mjs";
const TEXT_EXTENSIONS = new Set([
  ".css",
  ".cjs",
  ".html",
  ".js",
  ".json",
  ".mjs",
  ".md",
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
  return [...text.matchAll(/stavia(?!s)|stav[._-]ia(?!s)/gi)].map((match) => ({
    index: match.index ?? 0,
    token: match[0],
  }));
}

function sourceFiles(repositoryRoot = REPOSITORY_ROOT) {
  const webRoot = path.join(repositoryRoot, "apps/web");
  const runtime = listFiles(path.join(webRoot, "src")).filter((file) => {
    const basename = path.basename(file);
    return (
      /\.(?:css|json|ts|tsx)$/.test(file) &&
      !basename.includes(".test.") &&
      !basename.includes(".spec.")
    );
  });
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
  if (!localCleanup.includes("target.removeItem(key)")) {
    violations.push(`${LOCAL_STORAGE_CLEANUP_PATH}: missing target.removeItem(key)`);
  }
  if (/\b(?:getItem|setItem)\s*\(/.test(localCleanup)) {
    violations.push(`${LOCAL_STORAGE_CLEANUP_PATH}: active legacy storage access`);
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
    const content = maskVerifiedLegacySource(file).replaceAll(
      VERIFIER_REFERENCE,
      "[assistant-boundary-verifier]",
    );
    for (const match of findAssistantTokens(content)) {
      violations.push(`${file.path}: forbidden content token ${match.token}`);
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
