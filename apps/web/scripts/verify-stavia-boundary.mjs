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

const CORPORATE_SOURCE_RULES = new Map([
  ["apps/web/index.html", [["Córtex Stavias", 1]]],
  ["apps/web/vite.config.ts", [["Córtex Stavias", 1]]],
  [
    "apps/web/src/components/shell/CortexShell.tsx",
    [
      ["staviasTile", 2],
      ["../../assets/stavias-s-tile.png", 1],
      ["/stavias-cortex-logo.png", 1],
      ["Stavias Córtex", 2],
    ],
  ],
  [
    "apps/web/src/features/auth/ActivationPage.tsx",
    [["Stavias Córtex", 2]],
  ],
  [
    "apps/web/src/features/auth/LoginPage.css",
    [
      ["Tela de login do Sistema Córtex (Stavias).", 1],
      ["O amarelo Stavias fica reservado à ação e aos acentos.", 1],
    ],
  ],
  [
    "apps/web/src/features/auth/LoginPage.tsx",
    [
      ["staviasTile", 2],
      ["../../assets/login/stavias-canteiro.png", 1],
      ["../../assets/stavias-s-tile.png", 1],
      ["Stavias Córtex", 1],
      ["alt=\"Stavias\"", 1],
      ["© 2026 Stavias — Sistema Córtex", 1],
    ],
  ],
  [
    "apps/web/src/features/auth/OfflineUnlockPage.tsx",
    [
      ["staviasTile", 2],
      ["../../assets/stavias-s-tile.png", 1],
      ["alt=\"Stavias\"", 1],
    ],
  ],
  [
    "apps/web/src/features/home/HomePage.tsx",
    [["MaisStaviasCard", 3]],
  ],
  [
    "apps/web/src/features/home/MaisStaviasCard.tsx",
    [
      ["Links externos das plataformas Stavias", 1],
      ["STAVIAS_LINKS", 2],
      ["Portal Stavias", 1],
      ["https://www.stavias.com.br", 1],
      ["Stavias Academy", 1],
      ["https://academy.stavias.com.br", 1],
      ["https://suporte.stavias.com.br", 1],
      ["MaisStaviasCard", 1],
      ["Mais Stavias", 1],
    ],
  ],
  [
    "apps/web/src/features/rdos/RdoLocalList.tsx",
    [["Stavias · Sistema Córtex", 1]],
  ],
  [
    "apps/web/src/index.css",
    [
      ["Amarelo Stavias", 1],
      ["Modo compacto: só o tile da Stavias e os ícones dos botões.", 1],
    ],
  ],
  [
    "compose.production.example.yml",
    [
      ["Stavias Córtex", 1],
      ["authenticated Stavias From mailbox", 1],
    ],
  ],
  [".env.postgresql.example", [["StaviasCortex", 1]]],
  ["scripts/dev/migrate-postgres-cortex.sh", [["StaviasCortex", 1]]],
  ["scripts/dev/postgres-cortex-common.sh", [["StaviasCortex", 1]]],
  ["scripts/smoke-deploy.sh", [["Córtex Stavias", 1]]],
]);
export const CORPORATE_SOURCE_ALLOWLIST = new Set(CORPORATE_SOURCE_RULES.keys());
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

function isIdentifierCharacter(character) {
  return typeof character === "string" && /[\p{L}\p{N}_$]/u.test(character);
}

function isCompleteApprovedOccurrence(content, index, fragment) {
  const startsWithIdentifier = isIdentifierCharacter(fragment[0]);
  const endsWithIdentifier = isIdentifierCharacter(fragment.at(-1));
  if (
    startsWithIdentifier &&
    index > 0 &&
    isIdentifierCharacter(content[index - 1])
  ) {
    return false;
  }
  if (!endsWithIdentifier) {
    return true;
  }

  const afterIndex = index + fragment.length;
  if (isIdentifierCharacter(content[afterIndex])) {
    return false;
  }
  if (!/\s/.test(fragment)) {
    return true;
  }
  let nextNonSpace = afterIndex;
  while (/\s/.test(content[nextNonSpace] ?? "")) {
    nextNonSpace += 1;
  }
  return !isIdentifierCharacter(content[nextNonSpace]);
}

function maskCompleteCorporateFragments(content, fragments) {
  let masked = content;
  for (const fragment of [...fragments].sort(
    (left, right) => right.length - left.length,
  )) {
    let searchFrom = 0;
    while (searchFrom < masked.length) {
      const index = masked.indexOf(fragment, searchFrom);
      if (index < 0) {
        break;
      }
      if (isCompleteApprovedOccurrence(masked, index, fragment)) {
        masked =
          masked.slice(0, index) +
          "#".repeat(fragment.length) +
          masked.slice(index + fragment.length);
      }
      searchFrom = index + fragment.length;
    }
  }
  return masked;
}

function maskAllowedCorporateSource(pathname, content, violations) {
  const rules = CORPORATE_SOURCE_RULES.get(pathname);
  if (!rules) {
    return content;
  }

  for (const [fragment, expectedCount] of rules) {
    const actualCount = occurrenceCount(content, fragment);
    if (actualCount !== expectedCount) {
      violations.push(
        `${pathname}: corporate fragment ${JSON.stringify(fragment)} expected ${expectedCount}, found ${actualCount}`,
      );
    }
  }
  return maskCompleteCorporateFragments(
    content,
    rules.map(([fragment]) => fragment),
  );
}

const CORPORATE_DIST_FRAGMENTS = [
  "Córtex Stavias",
  "Stavias · Sistema Córtex",
  "Portal Stavias",
  "https://www.stavias.com.br",
  "Stavias Academy",
  "https://academy.stavias.com.br",
  "https://suporte.stavias.com.br",
  "Mais Stavias",
  "/assets/stavias-canteiro-",
  "/assets/stavias-s-tile-",
  "/stavias-cortex-logo.png",
  "stavias-cortex-logo.png",
  "assets/stavias-canteiro-",
  "assets/stavias-s-tile-",
  "assets/stavias-logo-",
  "Entrar no Stavias Córtex",
  "alt:`Stavias Córtex`",
  "alt:`Stavias`",
  "© 2026 Stavias — Sistema Córtex",
  "Stavias Córtex · Ambiente institucional restrito",
];

function maskAllowedCorporateDist(content) {
  return maskCompleteCorporateFragments(content, CORPORATE_DIST_FRAGMENTS);
}

export function inspectDistCorporateContent(content) {
  const masked = maskAllowedCorporateDist(content);
  return [
    ...findAssistantTokens(masked).map(
      (match) => `forbidden content token ${match.token}`,
    ),
    ...findCorporateTokens(masked).map(
      (match) =>
        `corporate Stavias occurrence is not an approved artifact fragment (${match.token})`,
    ),
  ];
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
  if (
    !/export\s+function\s+clearUserScopedLocalStorage\s*\(\s*\)\s*(?::\s*void\s*)?\{/.test(
      localCleanup,
    ) ||
    !/typeof\s+window\s*===\s*["']undefined["']\s*\?\s*null\s*:\s*window\.localStorage/.test(
      localCleanup,
    )
  ) {
    violations.push(
      `${LOCAL_STORAGE_CLEANUP_PATH}: cleanup API must be zero-argument and browser-local`,
    );
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
  for (const file of runtimeFiles) {
    if (file.path === LOCAL_STORAGE_CLEANUP_PATH) {
      continue;
    }
    if (
      /(?:import\s*\*\s+as\s+[A-Za-z_$][\w$]*\s+from|import\s*\(|require\s*\()\s*["'][^"']*localDataScope(?:\.ts)?["']/.test(
        file.content,
      )
    ) {
      violations.push(
        `${file.path}: cleanup module may not be loaded through namespace, dynamic, or CommonJS access`,
      );
    }
    const remainingReferences = file.content
      .replace(
        /import\s*\{\s*clearUserScopedLocalStorage\s*\}\s*from\s*["'][^"']+["']\s*;?/g,
        "[approved-cleanup-import]",
      )
      .replace(
        /\bclearUserScopedLocalStorage\s*\(\s*\)/g,
        "[approved-zero-argument-cleanup-call]",
      );
    if (/\bclearUserScopedLocalStorage\b/.test(remainingReferences)) {
      violations.push(
        `${file.path}: cleanup function may only be imported by name and called without arguments`,
      );
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

const VERIFIED_DIST_BUILD_SUFFIX =
  /&&\s*node scripts\/verify-stavia-boundary\.mjs --dist\s*$/;

function tokenizeShellCommands(command) {
  const commands = [];
  let tokens = [];
  let token = "";
  let tokenStarted = false;
  let quote = null;

  const flushToken = () => {
    if (tokenStarted) {
      tokens.push(token);
      token = "";
      tokenStarted = false;
    }
  };
  const flushCommand = () => {
    flushToken();
    if (tokens.length > 0) {
      commands.push(tokens);
      tokens = [];
    }
  };

  for (let index = 0; index < command.length; index += 1) {
    const character = command[index];
    if (quote) {
      if (character === quote) {
        quote = null;
      } else if (
        quote === '"' &&
        character === "\\" &&
        index + 1 < command.length
      ) {
        index += 1;
        token += command[index];
      } else {
        token += character;
      }
      tokenStarted = true;
      continue;
    }
    if (character === '"' || character === "'") {
      quote = character;
      tokenStarted = true;
      continue;
    }
    if (character === "\\" && index + 1 < command.length) {
      index += 1;
      if (command[index] !== "\n") {
        token += command[index];
        tokenStarted = true;
      }
      continue;
    }
    if (/\s/.test(character)) {
      flushToken();
      continue;
    }
    if (character === ";" || character === "&" || character === "|") {
      flushCommand();
      continue;
    }
    token += character;
    tokenStarted = true;
  }
  flushCommand();
  return commands;
}

function tokenSequenceInvokesViteBuild(tokens) {
    const viteIndex = tokens.findIndex((tokenValue) =>
      /(?:^|\/)vite(?:\.cmd)?$/.test(tokenValue),
    );
    return viteIndex >= 0 && tokens.slice(viteIndex + 1).includes("build");
}

function invokesViteBuild(command) {
  return tokenizeShellCommands(command).some(
    (tokens) =>
      tokenSequenceInvokesViteBuild(tokens) ||
      tokens.some(
        (tokenValue) =>
          /\s/.test(tokenValue) &&
          tokenizeShellCommands(tokenValue).some(tokenSequenceInvokesViteBuild),
      ),
  );
}

export function inspectPackageBuildScripts(scripts) {
  const violations = [];
  for (const [name, command] of Object.entries(scripts)) {
    const isBuildEntry = /^build(?::|$)/.test(name);
    const hasViteBuild = invokesViteBuild(command);
    if (!isBuildEntry && !hasViteBuild) {
      continue;
    }
    if (!VERIFIED_DIST_BUILD_SUFFIX.test(command)) {
      violations.push(
        `apps/web/package.json#${name}: build must end with the source/dist boundary verifier`,
      );
    }
  }
  return violations;
}

export function inspectSourceBoundary(files) {
  const violations = inspectLegacySource(files);
  const packageFile = files.find(
    (file) => file.path === "apps/web/package.json",
  );
  if (packageFile) {
    try {
      const packageJson = JSON.parse(packageFile.content);
      violations.push(
        ...inspectPackageBuildScripts(packageJson.scripts ?? {}),
      );
    } catch (error) {
      violations.push(
        `apps/web/package.json: cannot validate build scripts (${error instanceof Error ? error.message : error})`,
      );
    }
  }
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
    const corporateMasked = maskAllowedCorporateSource(
      file.path,
      content,
      violations,
    );
    for (const match of findCorporateTokens(corporateMasked)) {
      violations.push(
        `${file.path}: corporate Stavias occurrence is not explicitly allowlisted (${match.token})`,
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
    for (const violation of inspectDistCorporateContent(content)) {
      violations.push(`${file.path}: ${violation}`);
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
