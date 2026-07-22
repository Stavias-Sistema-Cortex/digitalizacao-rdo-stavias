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

const CORPORATE_SOURCE_LINES = new Map([
  ["apps/web/index.html", ["<title>Córtex Stavias</title>"]],
  ["apps/web/vite.config.ts", ["name: \"Córtex Stavias\","]],
  [
    "apps/web/src/components/shell/CortexShell.tsx",
    [
      "import staviasTile from \"../../assets/stavias-s-tile.png\";",
      "src=\"/stavias-cortex-logo.png\"",
      "alt=\"Stavias Córtex\"",
      "src={staviasTile}",
      "alt=\"Stavias Córtex\"",
    ],
  ],
  [
    "apps/web/src/features/auth/ActivationPage.tsx",
    [
      "alt=\"Stavias Córtex\"",
      "Stavias Córtex · Ambiente institucional restrito",
    ],
  ],
  [
    "apps/web/src/features/auth/LoginPage.css",
    [
      "* Tela de login do Sistema Córtex (Stavias).",
      "* desse card. O amarelo Stavias fica reservado à ação e aos acentos.",
    ],
  ],
  [
    "apps/web/src/features/auth/LoginPage.tsx",
    [
      "import canteiroBackdrop from \"../../assets/login/stavias-canteiro.png\";",
      "import staviasTile from \"../../assets/stavias-s-tile.png\";",
      "<h1 className=\"visually-hidden\">Entrar no Stavias Córtex</h1>",
      "src={staviasTile}",
      "alt=\"Stavias\"",
      "<p className=\"login__footer\">© 2026 Stavias — Sistema Córtex</p>",
    ],
  ],
  [
    "apps/web/src/features/auth/OfflineUnlockPage.tsx",
    [
      "import staviasTile from \"../../assets/stavias-s-tile.png\";",
      "src={staviasTile}",
      "alt=\"Stavias\"",
    ],
  ],
  [
    "apps/web/src/features/home/HomePage.tsx",
    [
      "import { MaisStaviasCard } from \"./MaisStaviasCard\";",
      "<MaisStaviasCard />",
    ],
  ],
  [
    "apps/web/src/features/home/MaisStaviasCard.tsx",
    [
      "// Links externos das plataformas Stavias; ajustar URLs conforme o ambiente.",
      "const STAVIAS_LINKS: { label: string; href: string }[] = [",
      "label: \"Portal Stavias\",",
      "href: \"https://www.stavias.com.br\",",
      "label: \"Stavias Academy\",",
      "href: \"https://academy.stavias.com.br\",",
      "href: \"https://suporte.stavias.com.br\",",
      "export function MaisStaviasCard() {",
      "<h3>Mais Stavias</h3>",
      "{STAVIAS_LINKS.map((link) => (",
    ],
  ],
  [
    "apps/web/src/features/rdos/RdoLocalList.tsx",
    ["<p className=\"eyebrow\">Stavias · Sistema Córtex</p>"],
  ],
  [
    "apps/web/src/index.css",
    [
      "/* Amarelo Stavias = ação principal, como no \"Entrar\" do login. */",
      "/* Modo compacto: só o tile da Stavias e os ícones dos botões. */",
    ],
  ],
  [
    "compose.production.example.yml",
    [
      "CORTEX_AUTH_WEBAUTHN_RP_NAME: ${CORTEX_AUTH_WEBAUTHN_RP_NAME:-Stavias Córtex}",
      "CORTEX_SMTP_FROM: ${CORTEX_SMTP_FROM:?Set the authenticated Stavias From mailbox}",
    ],
  ],
  [
    ".env.postgresql.example",
    ["CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/StaviasCortex"],
  ],
  [
    "scripts/dev/migrate-postgres-cortex.sh",
    ["# Explicit transition 1/4: install V44 in an already provisioned StaviasCortex."],
  ],
  [
    "scripts/dev/postgres-cortex-common.sh",
    ["printf '%s' 'StaviasCortex'"],
  ],
  [
    "scripts/smoke-deploy.sh",
    ["request \"$BASE_URL/manifest.webmanifest\" | grep -q '\"name\":\"Córtex Stavias\"'"],
  ],
]);
export const CORPORATE_SOURCE_ALLOWLIST = new Set(CORPORATE_SOURCE_LINES.keys());
export const CORPORATE_ASSET_ALLOWLIST = new Set([
  "apps/web/public/stavias-cortex-logo.png",
  "apps/web/src/assets/login/stavias-canteiro.png",
  "apps/web/src/assets/login/stavias-logo.png",
  "apps/web/src/assets/stavias-s-tile.png",
]);

const LOCAL_STORAGE_CLEANUP_PATH =
  "apps/web/src/features/auth/authSession.ts";
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

function identifierOccurrenceCount(text, identifier) {
  const escaped = identifier.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  return (
    text.match(
      new RegExp(
        `(?<![A-Za-z0-9_$])${escaped}(?![A-Za-z0-9_$])`,
        "g",
      ),
    )?.length ?? 0
  );
}

function findMatchingBrace(content, openingBrace) {
  let depth = 0;
  let quote = "";
  let escaped = false;
  for (let index = openingBrace; index < content.length; index += 1) {
    const character = content[index];
    if (quote) {
      if (escaped) {
        escaped = false;
      } else if (character === "\\") {
        escaped = true;
      } else if (character === quote) {
        quote = "";
      }
      continue;
    }
    if (character === '"' || character === "'" || character === "`") {
      quote = character;
      continue;
    }
    if (character === "{") {
      depth += 1;
    } else if (character === "}") {
      depth -= 1;
      if (depth === 0) {
        return index;
      }
    }
  }
  return -1;
}

function compiledEnclosingFunction(content, contentIndex) {
  let functionIndex = content.lastIndexOf("function", contentIndex);
  while (functionIndex >= 0) {
    const header = content.slice(functionIndex).match(
      /^function(?:\s+[A-Za-z_$][\w$]*)?\s*\([^)]*\)\s*\{/,
    );
    if (header) {
      const openingBrace = functionIndex + header[0].lastIndexOf("{");
      const closingBrace = findMatchingBrace(content, openingBrace);
      if (openingBrace < contentIndex && closingBrace > contentIndex) {
        return content.slice(openingBrace + 1, closingBrace);
      }
    }
    functionIndex = content.lastIndexOf("function", functionIndex - 1);
  }
  return "";
}

function normalizeStaticReferenceText(text) {
  const decoded = text
    .replace(/\\u\{([0-9a-f]+)\}/gi, (_, value) =>
      String.fromCodePoint(Number.parseInt(value, 16)),
    )
    .replace(/\\u([0-9a-f]{4})/gi, (_, value) =>
      String.fromCharCode(Number.parseInt(value, 16)),
    )
    .replace(/\\x([0-9a-f]{2})/gi, (_, value) =>
      String.fromCharCode(Number.parseInt(value, 16)),
    );
  return decoded.replace(/[^A-Za-z0-9_]/g, "");
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

function findForbiddenAssistantTerms(text) {
  return [...text.matchAll(/\b(?:assistant|copilot)\b/giu)].map((match) => ({
    index: match.index ?? 0,
    token: match[0],
  }));
}

function maskAllowedCorporateSource(pathname, content, violations) {
  const expectedLines = CORPORATE_SOURCE_LINES.get(pathname);
  if (!expectedLines) {
    return content;
  }

  const expectedCounts = new Map();
  for (const line of expectedLines) {
    expectedCounts.set(line, (expectedCounts.get(line) ?? 0) + 1);
  }
  const actualCorporateLines = content
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => findCorporateTokens(line).length > 0);
  const actualCounts = new Map();
  for (const line of actualCorporateLines) {
    actualCounts.set(line, (actualCounts.get(line) ?? 0) + 1);
  }
  for (const line of new Set([...expectedCounts.keys(), ...actualCounts.keys()])) {
    const expectedCount = expectedCounts.get(line) ?? 0;
    const actualCount = actualCounts.get(line) ?? 0;
    if (actualCount !== expectedCount) {
      violations.push(
        `${pathname}: corporate line ${JSON.stringify(line)} expected ${expectedCount}, found ${actualCount}`,
      );
    }
  }
  return content
    .split(/(\r?\n)/)
    .map((line) =>
      expectedCounts.has(line.trim()) ? "#".repeat(line.length) : line,
    )
    .join("");
}

const CORPORATE_DIST_PATTERNS = [
  /<title>Córtex Stavias<\/title>/g,
  /"name":"Córtex Stavias"/g,
  /(?<![\p{L}\p{N}_$])url:"stavias-cortex-logo\.png"/gu,
  /(?<![\p{L}\p{N}_$])url:"assets\/(?:stavias-s-tile|stavias-canteiro|stavias-logo)-[A-Za-z0-9_-]+\.png"/gu,
  /`\/assets\/(?:stavias-s-tile|stavias-canteiro|stavias-logo)-[A-Za-z0-9_-]+\.png`/g,
  /(?<![\p{L}\p{N}_$])src:`\/stavias-cortex-logo\.png`/gu,
  /(?<![\p{L}\p{N}_$])children:`Stavias · Sistema Córtex`/gu,
  /(?<![\p{L}\p{N}_$])label:`Portal Stavias`/gu,
  /(?<![\p{L}\p{N}_$])href:`https:\/\/www\.stavias\.com\.br`/gu,
  /(?<![\p{L}\p{N}_$])label:`Stavias Academy`/gu,
  /(?<![\p{L}\p{N}_$])href:`https:\/\/academy\.stavias\.com\.br`/gu,
  /(?<![\p{L}\p{N}_$])href:`https:\/\/suporte\.stavias\.com\.br`/gu,
  /(?<![\p{L}\p{N}_$])children:`Mais Stavias`/gu,
  /(?<![\p{L}\p{N}_$])children:`Entrar no Stavias Córtex`/gu,
  /(?<![\p{L}\p{N}_$])alt:`Stavias`/gu,
  /(?<![\p{L}\p{N}_$])alt:`Stavias Córtex`/gu,
  /(?<![\p{L}\p{N}_$])children:`© 2026 Stavias — Sistema Córtex`/gu,
  /(?<![\p{L}\p{N}_$])children:`Stavias Córtex · Ambiente institucional restrito`/gu,
];

function maskAllowedCorporateDist(content) {
  return CORPORATE_DIST_PATTERNS.reduce(
    (masked, pattern) => masked.replace(pattern, "[approved-corporate-brand]"),
    content,
  );
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
    ...findForbiddenAssistantTerms(masked).map(
      (match) => `forbidden assistant role term ${match.token}`,
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
    !/function\s+clearRetiredPrivateLocalStorage\s*\(\s*\)\s*(?::\s*void\s*)?\{/.test(
      localCleanup,
    ) ||
    !/typeof\s+window\s*===\s*["']undefined["']\s*\?\s*null\s*:\s*window\.localStorage/.test(
      localCleanup,
    )
  ) {
    violations.push(
      `${LOCAL_STORAGE_CLEANUP_PATH}: private cleanup must be zero-argument and browser-local`,
    );
  }
  if (occurrenceCount(localCleanup, "clearRetiredPrivateLocalStorage") !== 2) {
    violations.push(
      `${LOCAL_STORAGE_CLEANUP_PATH}: private cleanup must have one declaration and one caller`,
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
    const remainingReferences = file.content;
    if (/localDataScope|clearUserScoped/i.test(remainingReferences)) {
      violations.push(
        `${file.path}: cleanup module and symbol may not be referenced through computed or facade access`,
      );
    }
    const normalizedReferences = normalizeStaticReferenceText(
      remainingReferences,
    );
    if (
      /localDataScope|clearUserScopedLocalStorage/i.test(normalizedReferences)
    ) {
      violations.push(
        `${file.path}: cleanup module and symbol may not be assembled through strings, templates, or escapes`,
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
    content = content.replaceAll(
      "LEGACY_ASSISTANT_STORE",
      "[legacy-retired-store]",
    );
  }
  return content;
}

const VERIFIED_DIST_BUILD_SUFFIX =
  /&&\s*node scripts\/verify-stavia-boundary\.mjs --dist\s*$/;
const EXPECTED_PACKAGE_SCRIPTS = new Map([
  ["dev", "vite"],
  [
    "dev:local",
    "CORTEX_API_TARGET=http://127.0.0.1:8080 vite --host 127.0.0.1 --port 5173",
  ],
  [
    "dev:compose",
    "CORTEX_API_TARGET=http://127.0.0.1:8081 vite --host 127.0.0.1 --port 5173",
  ],
  [
    "build",
    "tsc -b && vite build && node scripts/verify-stavia-boundary.mjs --dist",
  ],
  [
    "build:local",
    "tsc -b && VITE_CORTEX_API_BASE_URL=http://127.0.0.1:8080/api vite build && node scripts/verify-stavia-boundary.mjs --dist",
  ],
  [
    "build:compose",
    "tsc -b && VITE_CORTEX_API_BASE_URL=http://127.0.0.1:8081/api vite build && node scripts/verify-stavia-boundary.mjs --dist",
  ],
  ["lint", "eslint ."],
  [
    "verify:mensagens-geometry",
    "node scripts/verify-mensagens-geometry.mjs",
  ],
  [
    "verify:retired-runtime-boundary",
    "node scripts/verify-stavia-boundary.mjs",
  ],
  ["test", "vitest run"],
  ["preview", "vite preview"],
  [
    "preview:local",
    "CORTEX_API_TARGET=http://127.0.0.1:8080 vite preview --host 127.0.0.1 --port 4173",
  ],
  [
    "preview:compose",
    "CORTEX_API_TARGET=http://127.0.0.1:8081 vite preview --host 127.0.0.1 --port 4173",
  ],
]);

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

function invokesViteBuild(command, seen = new Set()) {
  if (seen.has(command)) {
    return false;
  }
  seen.add(command);
  return tokenizeShellCommands(command).some(
    (tokens) =>
      tokenSequenceInvokesViteBuild(tokens) ||
      tokens.some(
        (tokenValue) =>
          /\s/.test(tokenValue) &&
          invokesViteBuild(tokenValue, seen),
      ),
  );
}

export function inspectPackageBuildScripts(scripts) {
  const violations = [];
  for (const name of new Set([
    ...EXPECTED_PACKAGE_SCRIPTS.keys(),
    ...Object.keys(scripts),
  ])) {
    const expected = EXPECTED_PACKAGE_SCRIPTS.get(name);
    const actual = scripts[name];
    if (actual !== expected) {
      violations.push(
        `apps/web/package.json#${name}: script must match the reviewed runtime command exactly`,
      );
    }
  }
  for (const [name, command] of Object.entries(scripts)) {
    const isBuildEntry = /^build(?::|$)/.test(name);
    const hasViteBuild = invokesViteBuild(command);
    const hasBuildOperation = /build/i.test(command);
    if (!isBuildEntry && !hasViteBuild && !hasBuildOperation) {
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
      "[retired-runtime-boundary-verifier]",
    );
    for (const match of findAssistantTokens(content)) {
      violations.push(`${file.path}: forbidden content token ${match.token}`);
    }
    for (const match of findForbiddenAssistantTerms(content)) {
      violations.push(
        `${file.path}: forbidden assistant role term ${match.token}`,
      );
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
  const cleanupFile = textFiles.find((file) =>
    LEGACY_LOCAL_STORAGE_KEYS.every((key) => file.content.includes(key)),
  );
  const compiledKeyIndex = cleanupFile?.content.indexOf(
    LEGACY_LOCAL_STORAGE_KEYS[0],
  ) ?? -1;
  const compiledCleanupFunction = cleanupFile
    ? compiledEnclosingFunction(cleanupFile.content, compiledKeyIndex)
    : "";
  const compiledKeyDeclaration = compiledCleanupFunction.match(
    /(?:^|[,;]\s*|\b(?:const|let|var)\s+)([A-Za-z_$][\w$]*)\s*=\s*\[\s*[`"']cortex:stavia:chat:operacional[`"']\s*,\s*[`"']cortex:stavia:last-context[`"']\s*\]/,
  );
  const compiledKeyCollection = compiledKeyDeclaration?.[1] ?? "";
  const compiledRemovalLoop = compiledKeyCollection
    ? new RegExp(
        `for\\s*\\(\\s*(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s+of\\s+${compiledKeyCollection}\\s*\\)\\s*([A-Za-z_$][\\w$]*)\\.removeItem\\(\\s*\\1\\s*\\)`,
      )
    : null;
  if (
    !cleanupFile ||
    !compiledCleanupFunction ||
    identifierOccurrenceCount(
      compiledCleanupFunction,
      compiledKeyCollection,
    ) !== 2 ||
    !compiledRemovalLoop?.test(compiledCleanupFunction) ||
    /\.(?:getItem|setItem)\(/.test(compiledCleanupFunction) ||
    !compiledCleanupFunction.includes("window.localStorage")
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
