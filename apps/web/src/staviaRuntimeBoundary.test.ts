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
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import path from "node:path";

import { describe, expect, it } from "vitest";
import {
  CORPORATE_ASSET_ALLOWLIST,
  CORPORATE_SOURCE_ALLOWLIST,
  findAssistantTokens,
  inspectDistCorporateContent,
  inspectPackageBuildScripts,
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
      path: "apps/web/src/features/auth/authSession.ts",
      content: `function clearRetiredPrivateLocalStorage() {\n  const LEGACY_PRIVATE_LOCAL_STORAGE_KEYS = [\n    "${LEGACY_LOCAL_STORAGE_KEYS[0]}",\n    "${LEGACY_LOCAL_STORAGE_KEYS[1]}",\n  ] as const;\n  const target = typeof window === "undefined" ? null : window.localStorage;\n  if (!target) return;\n  for (const key of LEGACY_PRIVATE_LOCAL_STORAGE_KEYS) {\n    target.removeItem(key);\n  }\n}\nclearRetiredPrivateLocalStorage();`,
    },
    {
      path: "apps/web/src/lib/db/cortexDb.ts",
      content: `const LEGACY_ASSISTANT_STORE = "${LEGACY_SNAPSHOT_STORE}"; if (database.objectStoreNames.contains(LEGACY_ASSISTANT_STORE)) database.deleteObjectStore(LEGACY_ASSISTANT_STORE);`,
    },
  ];
}

function validNormalRuntimeFixtures(): Array<{ path: string; content: string }> {
  return [
    {
      path: "compose.local.yml",
      content: [
        'CORTEX_CORS_ALLOWED_ORIGINS: http://localhost:${CORTEX_WEB_PORT:-5173}',
        'CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS: http://localhost:${CORTEX_WEB_PORT:-5173}',
        'ports:',
        '  - "127.0.0.1:${CORTEX_API_PORT:-8081}:8080"',
        '  - "127.0.0.1:${CORTEX_WEB_PORT:-5173}:8080"',
      ].join("\n"),
    },
    {
      path: "compose.production.example.yml",
      content: [
        "SPRING_PROFILES_ACTIVE: production,postgresql",
        "CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE: /run/secrets/cortex_cpf_hmac",
      ].join("\n"),
    },
    {
      path: "scripts/dev/run-api.sh",
      content: [
        'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
        'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
        'API_PORT="${PORT:-${SERVER_PORT:-8080}}"',
        'export SERVER_PORT="$API_PORT"',
        'API_HEALTH_URL="http://127.0.0.1:${API_PORT}/api/health"',
        'lsof -nP -iTCP:"$API_PORT" -sTCP:LISTEN',
        'curl -fsS "$API_HEALTH_URL"',
      ].join("\n"),
    },
    {
      path: "scripts/dev/run-compose.sh",
      content: [
        'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
        'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
        'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
        'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
        "export CORTEX_WEB_PORT CORTEX_API_PORT",
        'docker compose up -d',
        'echo "http://localhost:${CORTEX_WEB_PORT}"',
        'echo "http://127.0.0.1:${CORTEX_API_PORT}/api/health"',
        'echo "http://127.0.0.1:${CORTEX_API_PORT}/api/readiness"',
      ].join("\n"),
    },
    {
      path: "scripts/dev/run-api-docker.sh",
      content: [
        'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
        'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
        'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
        'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
        'docker run -p "127.0.0.1:${CORTEX_API_PORT}:8080" \\',
        '  -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" cortex-api:local',
      ].join("\n"),
    },
    {
      path: "scripts/dev/normal-runtime-env.sh",
      content: "unset_normal_runtime_activation_environment",
    },
    {
      path: "scripts/dev/start-postgres-activation.sh",
      content: [
        "postgresql-activation",
        "cortex_require_secret_file CORTEX_AUTH_OTP_HMAC_KEY_FILE",
      ].join("\n"),
    },
    {
      path: ".env.example",
      content: [
        "CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/StaviasCortex",
        "CORTEX_POSTGRES_DOCKER_URL=jdbc:postgresql://host.docker.internal:5432/StaviasCortex",
        "CORTEX_WEB_PORT=5173",
        "CORTEX_API_PORT=8081",
      ].join("\n"),
    },
    {
      path: ".env.postgresql.example",
      content: [
        "CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/StaviasCortex",
        "VITE_CORTEX_API_BASE_URL=/api",
      ].join("\n"),
    },
    {
      path: "apps/api/src/main/resources/application-local.yml",
      content: [
        "allowed-origins: ${CORTEX_CORS_ALLOWED_ORIGINS:http://localhost:${CORTEX_WEB_PORT:5173}}",
        "allowed-origins: ${CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS:http://localhost:${CORTEX_WEB_PORT:5173}}",
      ].join("\n"),
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
  for (const fixture of [
    ...validCleanupFixtures(),
    ...validNormalRuntimeFixtures(),
  ]) {
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

function writeStandaloneBoundaryWebRoot(
  runtimePath: string,
  runtimeContent: string,
): string {
  const webRoot = mkdtempSync(
    path.join(tmpdir(), "cortex-stavia-standalone-web-"),
  );
  for (const fixture of validCleanupFixtures()) {
    const destination = path.join(
      webRoot,
      fixture.path.replace(/^apps\/web\//, ""),
    );
    mkdirSync(path.dirname(destination), { recursive: true });
    writeFileSync(destination, fixture.content);
  }
  const runtimeDestination = path.join(webRoot, "src", runtimePath);
  mkdirSync(path.dirname(runtimeDestination), { recursive: true });
  writeFileSync(runtimeDestination, runtimeContent);
  const verifierDestination = path.join(
    webRoot,
    "scripts/verify-stavia-boundary.mjs",
  );
  mkdirSync(path.dirname(verifierDestination), { recursive: true });
  writeFileSync(
    verifierDestination,
    readFileSync(
      path.join(WEB_ROOT, "scripts/verify-stavia-boundary.mjs"),
      "utf8",
    ),
  );
  return webRoot;
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
    path.join(REPOSITORY_ROOT, ".env.example"),
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

  it("scans tracked env contracts without reading ignored runtime secrets", () => {
    const repositoryRoot = writeBoundaryRepository(
      "runtime.ts",
      "export const runtime = 'cortex';",
    );

    try {
      execFileSync("git", ["init", "-q"], {
        cwd: repositoryRoot,
        stdio: "ignore",
      });
      writeFileSync(
        path.join(repositoryRoot, ".gitignore"),
        ".env\n.env.local\n.env.untracked\n",
      );
      writeFileSync(
        path.join(repositoryRoot, ".env.example"),
        [
          "CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/StaviasCortex",
          "CORTEX_POSTGRES_DOCKER_URL=jdbc:postgresql://host.docker.internal:5432/StaviasCortex",
          "CORTEX_WEB_PORT=5173",
          "CORTEX_API_PORT=8081",
          "",
        ].join("\n"),
      );
      writeFileSync(
        path.join(repositoryRoot, ".env.local"),
        "VITE_STAVIA_API_URL=https://ignored-secret.invalid\n",
      );
      writeFileSync(
        path.join(repositoryRoot, ".env.untracked"),
        "STAVIA_ASSISTANT_TOKEN=ignored-runtime-secret\n",
      );
      execFileSync(
        "git",
        ["add", ".env.example", ".env.postgresql.example"],
        {
        cwd: repositoryRoot,
        stdio: "ignore",
        },
      );

      expect(() => verifySourceBoundary(repositoryRoot)).not.toThrow();

      writeFileSync(
        path.join(repositoryRoot, ".env.example"),
        [
          "CORTEX_POSTGRES_URL=jdbc:postgresql://127.0.0.1:5432/StaviasCortex",
          "CORTEX_POSTGRES_DOCKER_URL=jdbc:postgresql://host.docker.internal:5432/StaviasCortex",
          "CORTEX_WEB_PORT=5173",
          "CORTEX_API_PORT=8081",
          "VITE_STAVIA_API_URL=https://retired-runtime.invalid",
          "",
        ].join("\n"),
      );
      expect(() => verifySourceBoundary(repositoryRoot)).toThrow(
        /forbidden content token/i,
      );
    } finally {
      rmSync(repositoryRoot, { recursive: true, force: true });
    }
  });

  it("fails closed when an expected normal-runtime contract file is missing", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures(),
    ].filter((fixture) => fixture.path !== "scripts/dev/run-api.sh");

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api.sh: required normal-runtime contract file is missing",
    );
  });

  it("rejects every OTP token form from each normal-runtime launcher and compose", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];

    for (const runtimePath of [
      "scripts/dev/run-api.sh",
      "scripts/dev/run-compose.sh",
      "scripts/dev/run-api-docker.sh",
      "compose.local.yml",
      "compose.production.example.yml",
    ]) {
      const fixtures = [
        ...validCleanupFixtures(),
        ...validNormalRuntimeFixtures().map((fixture) =>
          fixture.path === runtimePath
            ? {
                ...fixture,
                content: `${fixture.content}\nexport CORTEX_AUTH_OTP_ROTATION_TOKEN_FILE=/run/secrets/otp`,
              }
            : fixture,
        ),
      ];

      expect(
        inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
        runtimePath,
      ).toContain(
        `${runtimePath}: normal PostgreSQL runtime must not contain OTP tokens, mounts, or exports`,
      );
    }
  });

  it("rejects a generic OTP variable from every normal-runtime launcher", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];

    for (const runtimePath of [
      "scripts/dev/run-api.sh",
      "scripts/dev/run-compose.sh",
      "scripts/dev/run-api-docker.sh",
    ]) {
      const fixtures = [
        ...validCleanupFixtures(),
        ...validNormalRuntimeFixtures().map((fixture) =>
          fixture.path === runtimePath
            ? {
                ...fixture,
                content: `${fixture.content}\nexport OTP=activation-only`,
              }
            : fixture,
        ),
      ];

      expect(
        inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
        runtimePath,
      ).toContain(
        `${runtimePath}: normal PostgreSQL runtime must not contain OTP tokens, mounts, or exports`,
      );
    }
  });

  it("rejects fixed operational ports even when configurable declarations are present", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-compose.sh"
          ? {
              ...fixture,
              content: `${fixture.content}\necho "http://localhost:5173"\ncurl http://127.0.0.1:8081/api/health`,
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-compose.sh: fixed operational port 5173/8081 bypasses the selected port variables",
    );
  });

  it("rejects a fixed selected-port assignment while allowing fallback declarations", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-compose.sh"
          ? {
              ...fixture,
              content: fixture.content.replace(
                'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                "CORTEX_API_PORT=8081",
              ),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-compose.sh: fixed operational port 5173/8081 bypasses the selected port variables",
    );
  });

  it("rejects a later API_PORT override after the selected fallback", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api.sh"
          ? {
              ...fixture,
              content: fixture.content.replace(
                'export SERVER_PORT="$API_PORT"',
                'API_PORT=8081\nexport SERVER_PORT="$API_PORT"',
              ),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api.sh: fixed operational port 5173/8081 bypasses the selected port variables",
    );
  });

  it("rejects a later SERVER_PORT override after exporting the selected API port", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api.sh"
          ? {
              ...fixture,
              content: fixture.content.replace(
                'export SERVER_PORT="$API_PORT"',
                'export SERVER_PORT="$API_PORT"\nSERVER_PORT=8081',
              ),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api.sh: fixed operational port 5173/8081 bypasses the selected port variables",
    );
  });

  it("requires selected port variables in executable launcher consumers", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api-docker.sh"
          ? {
              ...fixture,
              content: [
                'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
                'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                '# -p "127.0.0.1:${CORTEX_API_PORT}:8080"',
                '# -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT"',
                "docker run cortex-api:local",
              ].join("\n"),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api-docker.sh: selected ports are not consumed by the Docker bind/origin arguments",
    );
  });

  it("ignores reviewed Docker fragments parked in an uncalled function", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api-docker.sh"
          ? {
              ...fixture,
              content: [
                'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
                'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
                'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                "reviewed_docker_arguments() {",
                '  docker run -p "127.0.0.1:${CORTEX_API_PORT}:8080" \\',
                '    -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" cortex-api:local',
                "}",
                "docker run cortex-api:local",
              ].join("\n"),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api-docker.sh: selected ports are not consumed by the Docker bind/origin arguments",
    );
  });

  it("ignores uncalled Docker fragments in a multiline Bash function declaration", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api-docker.sh"
          ? {
              ...fixture,
              content: [
                'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
                'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
                'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                "reviewed_docker_arguments()",
                "{",
                '  docker run -p "127.0.0.1:${CORTEX_API_PORT}:8080" \\',
                '    -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" cortex-api:local',
                "}",
                "docker run cortex-api:local",
              ].join("\n"),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api-docker.sh: selected ports are not consumed by the Docker bind/origin arguments",
    );
  });

  it("ignores uncalled Docker fragments in a function-keyword declaration without parentheses", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api-docker.sh"
          ? {
              ...fixture,
              content: [
                'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
                'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
                'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                "function reviewed_docker_arguments",
                "{",
                '  docker run -p "127.0.0.1:${CORTEX_API_PORT}:8080" \\\\',
                '    -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" cortex-api:local',
                "}",
              ].join("\n"),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api-docker.sh: selected ports are not consumed by the Docker bind/origin arguments",
    );
  });

  it("ignores a commented multiline function declaration before its brace", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api-docker.sh"
          ? {
              ...fixture,
              content: [
                'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
                'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
                'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                "function reviewed_docker_arguments # intentionally uncalled",
                "{",
                '  docker run -p "127.0.0.1:${CORTEX_API_PORT}:8080" \\\\',
                '    -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" cortex-api:local',
                "}",
              ].join("\n"),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api-docker.sh: selected ports are not consumed by the Docker bind/origin arguments",
    );
  });

  for (const declaration of [
    "function reviewed_docker_arguments " + String.fromCharCode(92),
    "reviewed_docker_arguments() " + String.fromCharCode(92),
  ]) {
    it("ignores a backslash-continued function declaration before its brace", () => {
      const inspectStrict = inspectSourceBoundary as (
        files: Array<{ path: string; content: string }>,
        options: { requireNormalRuntimeFiles: boolean },
      ) => string[];
      const fixtures = [
        ...validCleanupFixtures(),
        ...validNormalRuntimeFixtures().map((fixture) =>
          fixture.path === "scripts/dev/run-api-docker.sh"
            ? {
                ...fixture,
                content: [
                  'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
                  'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                  'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
                  'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                  declaration,
                  "{",
                  '  docker run -p "127.0.0.1:${CORTEX_API_PORT}:8080" \\\\',
                  '    -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" cortex-api:local',
                  "}",
                ].join("\n"),
              }
            : fixture,
        ),
      ];

      expect(
        inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
      ).toContain(
        "scripts/dev/run-api-docker.sh: selected ports are not consumed by the Docker bind/origin arguments",
      );
    });
  }

  it("rejects selected Docker arguments hidden behind a shell condition", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api-docker.sh"
          ? {
              ...fixture,
              content: [
                'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
                'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
                'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                'false && docker run -p "127.0.0.1:${CORTEX_API_PORT}:8080" -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" cortex-api:local',
                "docker run cortex-api:local",
              ].join("\n"),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api-docker.sh: selected ports are not consumed by the Docker bind/origin arguments",
    );
  });

  it("requires the normal-runtime sanitizer to run outside an uncalled function", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api.sh"
          ? {
              ...fixture,
              content: fixture.content.replace(
                'source "$ROOT_DIR/scripts/dev/load-local-env.sh"\nsource "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                [
                  "normal_runtime_sources()",
                  "{",
                  '  source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
                  '  source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                  "}",
                ].join("\n"),
              ),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api.sh: normal-runtime environment sanitizer must run immediately after the local env loader",
    );
  });

  it("ignores reviewed Docker fragments parked in an unreachable shell guard", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api-docker.sh"
          ? {
              ...fixture,
              content: [
                'source "$ROOT_DIR/scripts/dev/load-local-env.sh"',
                'source "$ROOT_DIR/scripts/dev/normal-runtime-env.sh"',
                'CORTEX_WEB_PORT="${CORTEX_WEB_PORT:-5173}"',
                'CORTEX_API_PORT="${CORTEX_API_PORT:-8081}"',
                'if false; then docker run -p "127.0.0.1:${CORTEX_API_PORT}:8080" -e CORTEX_WEB_PORT="$CORTEX_WEB_PORT" cortex-api:local; fi',
                "docker run cortex-api:local",
              ].join("\n"),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api-docker.sh: selected ports are not consumed by the Docker bind/origin arguments",
    );
  });

  it("requires the selected shell API port to reach Spring Boot", () => {
    const inspectStrict = inspectSourceBoundary as (
      files: Array<{ path: string; content: string }>,
      options: { requireNormalRuntimeFiles: boolean },
    ) => string[];
    const fixtures = [
      ...validCleanupFixtures(),
      ...validNormalRuntimeFixtures().map((fixture) =>
        fixture.path === "scripts/dev/run-api.sh"
          ? {
              ...fixture,
              content: fixture.content.replace(
                'export SERVER_PORT="$API_PORT"\n',
                "",
              ),
            }
          : fixture,
      ),
    ];

    expect(
      inspectStrict(fixtures, { requireNormalRuntimeFiles: true }),
    ).toContain(
      "scripts/dev/run-api.sh: selected API port is not exported to Spring Boot",
    );
  });

  it("rejects assistant roles hidden behind the plural corporate brand", () => {
    const assistantRoleFixtures = [
      "StaviasLauncherProvider",
      "useStavias",
      "useStaviasLauncher",
      "StaviasAssistantContext",
      "StaviasApiClient",
      "StaviasChatControl",
      "StaviasAgent",
      "StaviasCopilot",
      "StaviasResponse",
      "StaviasAIChat",
      "StaviasRuntimeProvider",
      "useStaviasCortexLauncher",
      "Stavias Runtime Provider",
    ];

    for (const assistantRole of assistantRoleFixtures) {
      const homePage = readFileSync(
        path.join(WEB_ROOT, "src/features/home/HomePage.tsx"),
        "utf8",
      );
      expect(
        inspectSourceBoundary([
          ...validCleanupFixtures(),
          {
            path: "apps/web/src/features/home/HomePage.tsx",
            content: `${homePage}\nconst adversarialRole = "${assistantRole}";`,
          },
        ]),
        assistantRole,
      ).not.toEqual([]);
    }
  });

  it("keeps corporate Stavias occurrences scoped to approved roles and paths", () => {
    const homePage = readFileSync(
      path.join(WEB_ROOT, "src/features/home/HomePage.tsx"),
      "utf8",
    );
    const moreStavias = readFileSync(
      path.join(WEB_ROOT, "src/features/home/MaisStaviasCard.tsx"),
      "utf8",
    );
    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/src/features/home/HomePage.tsx",
          content: homePage,
        },
        {
          path: "apps/web/src/features/home/MaisStaviasCard.tsx",
          content: moreStavias,
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

  it("rejects unapproved plural corporate roles in generated text", () => {
    for (const assistantRole of [
      "StaviasAgent",
      "StaviasCopilot",
      "StaviasResponse",
      "StaviasAIChat",
      "Stavias Runtime Provider",
      "useStaviasCortexLauncher",
    ]) {
      expect(
        inspectDistCorporateContent(`const role = "${assistantRole}";`),
        assistantRole,
      ).not.toEqual([]);
    }
    expect(
      inspectDistCorporateContent(
        "label:`Portal Stavias`,href:`https://www.stavias.com.br`,children:`Mais Stavias`",
      ),
    ).toEqual([]);
    expect(
      inspectDistCorporateContent(
        "drawText(document,`STAVIAS`,margin,14,`bold`,16)",
      ),
    ).toEqual([]);
    expect(
      inspectDistCorporateContent(
        "url:\"assets/stavias-logo-4x2g.png\"",
      ),
    ).toEqual([]);
    for (const extendedApprovedFragment of [
      "AgentPortal Stavias",
      "Portal StaviasAgent",
      "Entrar no Stavias Córtex Assistant",
      "Portal Stavias-Assistant",
      "Portal Stavias—Assistant",
      "Assistant Portal Stavias",
      "Assistant—Portal Stavias",
    ]) {
      expect(
        inspectDistCorporateContent(extendedApprovedFragment),
        extendedApprovedFragment,
      ).not.toEqual([]);
    }
  });

  it("rejects source identifiers and copy that extend approved fragments", () => {
    const originalHomePage = readFileSync(
      path.join(WEB_ROOT, "src/features/home/HomePage.tsx"),
      "utf8",
    );
    const originalActivationPage = readFileSync(
      path.join(WEB_ROOT, "src/features/auth/ActivationPage.tsx"),
      "utf8",
    );

    for (const extendedIdentifier of [
      "MaisStaviasCardAgent",
      "AgentMaisStaviasCard",
    ]) {
      expect(
        inspectSourceBoundary([
          ...validCleanupFixtures(),
          {
            path: "apps/web/src/features/home/HomePage.tsx",
            content: originalHomePage.replaceAll(
              "MaisStaviasCard",
              extendedIdentifier,
            ),
          },
        ]),
        extendedIdentifier,
      ).not.toEqual([]);
    }
    for (const extendedCopy of [
      "Stavias Córtex Assistant",
      "Stavias Córtex-Assistant",
      "Stavias Córtex—Assistant",
      "Assistant Stavias Córtex",
      "Assistant—Stavias Córtex",
    ]) {
      expect(
        inspectSourceBoundary([
          ...validCleanupFixtures(),
          {
            path: "apps/web/src/features/auth/ActivationPage.tsx",
            content: originalActivationPage.replace(
              "Stavias Córtex",
              extendedCopy,
            ),
          },
        ]),
        extendedCopy,
      ).not.toEqual([]);
    }

    for (const adjacentAssistantCopy of [
      originalActivationPage.replace(
        "Stavias Córtex · Ambiente institucional restrito",
        `{"Assistant — "}\n          Stavias Córtex · Ambiente institucional restrito`,
      ),
      originalActivationPage.replace(
        "Stavias Córtex · Ambiente institucional restrito",
        `Stavias Córtex · Ambiente institucional restrito\n          {" — Assistant"}`,
      ),
    ]) {
      expect(
        inspectSourceBoundary([
          ...validCleanupFixtures(),
          {
            path: "apps/web/src/features/auth/ActivationPage.tsx",
            content: adjacentAssistantCopy,
          },
        ]),
      ).not.toEqual([]);
    }

    const moreStavias = readFileSync(
      path.join(WEB_ROOT, "src/features/home/MaisStaviasCard.tsx"),
      "utf8",
    ).replace(
      "↗ {link.label}",
      `↗ {link.label}{"-Assistant"}`,
    );
    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/src/features/home/MaisStaviasCard.tsx",
          content: moreStavias,
        },
      ]),
    ).not.toEqual([]);

    for (const composedGeneratedCopy of [
      "mylabel:`Portal Stavias`",
      "βlabel:`Portal Stavias`",
      "label:`Portal Stavias`+\"-Assistant\"",
      "\"Assistant-\"+label:`Portal Stavias`",
      "label:`Portal Stavias`+\n\"-Assistant\"",
      "label:`Portal Stavias`,children:[link.label,`-Assistant`]",
    ]) {
      expect(
        inspectDistCorporateContent(composedGeneratedCopy),
        composedGeneratedCopy,
      ).not.toEqual([]);
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

    for (const computedModuleLoad of [
      String.raw`await import(\`./lib/db/localDataScope\`)`,
      `await import("./lib/db/localDataScope.js")`,
      String.raw`require(\`./lib/db/localDataScope\`)`,
      `require("./lib/db/" + "localDataScope")`,
      `export * from "./localDataScope"; const cleanup = scope["clearUserScoped" + "LocalStorage"]; cleanup();`,
      `const p = "./lib/db/localD" + "ataScope"; const scope = await import(p); scope["clearUser" + "ScopedLocalStorage"]();`,
      `const scope = require("./lib/db/localD" + "ataScope.js"); scope["clearUser" + "ScopedLocalStorage"]();`,
      'const scope = await import(`./lib/db/local${"DataScope"}`); scope["clearUser" + "ScopedLocalStorage"]();',
      String.raw`import * as scope from "./lib/db/localD\u0061taScope"; scope["clearUser" + "ScopedLocalStorage"]();`,
      String.raw`export { cl\u0065arUserScopedLocalStorage } from "./lib/db/localD\u0061taScope";`,
    ]) {
      expect(
        inspectSourceBoundary([
          ...validCleanupFixtures(),
          {
            path: "apps/web/src/active-regression.ts",
            content: computedModuleLoad,
          },
        ]),
        computedModuleLoad,
      ).not.toEqual([]);
    }
  });

  it("rejects cleanup callers that can capture private legacy keys", () => {
    expect(existsSync(path.join(WEB_ROOT, "src/lib/db/localDataScope.ts"))).toBe(
      false,
    );
    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/src/active-regression.ts",
          content: `import { clearUserScopedLocalStorage } from "./lib/db/localDataScope"; clearUserScopedLocalStorage({ removeItem(key) { localStorage.getItem(key); } });`,
        },
      ]),
    ).not.toEqual([]);

    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/src/active-regression.ts",
          content: `import { clearUserScopedLocalStorage as cleanup } from "./lib/db/localDataScope"; cleanup();`,
        },
      ]),
    ).not.toEqual([]);

    expect(
      inspectSourceBoundary([
        ...validCleanupFixtures(),
        {
          path: "apps/web/src/active-regression.ts",
          content: `import * as scope from "./lib/db/localDataScope"; const cleanup = scope["clearUserScoped" + "LocalStorage"]; cleanup();`,
        },
      ]),
    ).not.toEqual([]);
  });

  it("keeps assistant sources, hooks, controls and CSS outside the web runtime", () => {
    expect(existsSync(path.join(WEB_ROOT, "src/features/stavia"))).toBe(false);

    expect(() => verifySourceBoundary(REPOSITORY_ROOT)).not.toThrow();
  });

  it("verifies a standalone web Docker context with canonical source paths", () => {
    const webRoot = writeStandaloneBoundaryWebRoot(
      "runtime.ts",
      "export const runtime = 'cortex';",
    );
    try {
      expect(() =>
        execFileSync(
          process.execPath,
          ["scripts/verify-stavia-boundary.mjs"],
          { cwd: webRoot, stdio: "pipe" },
        ),
      ).not.toThrow();
    } finally {
      rmSync(webRoot, { recursive: true, force: true });
    }
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
      "src/features/auth/authSession.ts",
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

  it("accepts compiled deletion-only cleanup regardless of minifier distance", () => {
    const distRoot = mkdtempSync(
      path.join(tmpdir(), "cortex-stavia-dist-boundary-"),
    );
    const compiledGap = "unrelated+=0;".repeat(40);
    const compiledCleanup = [
      "function clearRetired(){let unrelated=0;",
      `let retiredKeys=["${LEGACY_LOCAL_STORAGE_KEYS[0]}","${LEGACY_LOCAL_STORAGE_KEYS[1]}"];`,
      compiledGap,
      "let storage=typeof window>\"u\"?null:window.localStorage;if(storage)for(let key of retiredKeys)storage.removeItem(key)}",
      `const legacyStore="${LEGACY_SNAPSHOT_STORE}";`,
      "function migrate(database){database.objectStoreNames.contains(legacyStore)&&database.deleteObjectStore(legacyStore)}",
    ].join("");

    try {
      writeFileSync(path.join(distRoot, "index.js"), compiledCleanup);
      expect(() => verifyDist(distRoot)).not.toThrow();
    } finally {
      rmSync(distRoot, { recursive: true, force: true });
    }
  });

  it("accepts the retired store in a minified comma declaration", () => {
    const distRoot = mkdtempSync(
      path.join(tmpdir(), "cortex-stavia-dist-comma-declaration-"),
    );
    const compiledCleanup = [
      "function clearRetired(){",
      `let retiredKeys=["${LEGACY_LOCAL_STORAGE_KEYS[0]}","${LEGACY_LOCAL_STORAGE_KEYS[1]}"];`,
      "let storage=window.localStorage;for(let key of retiredKeys)storage.removeItem(key)}",
      `var unrelated=1,legacyStore="${LEGACY_SNAPSHOT_STORE}",other=2;`,
      "function migrate(database){database.objectStoreNames.contains(legacyStore)&&database.deleteObjectStore(legacyStore)}",
    ].join("");

    try {
      writeFileSync(path.join(distRoot, "index.js"), compiledCleanup);
      expect(() => verifyDist(distRoot)).not.toThrow();
    } finally {
      rmSync(distRoot, { recursive: true, force: true });
    }
  });

  it("ignores a short store identifier when it is only a substring of other symbols", () => {
    const distRoot = mkdtempSync(
      path.join(tmpdir(), "cortex-stavia-dist-short-identifier-"),
    );
    const compiledCleanup = [
      "function clearRetired(){",
      `let retiredKeys=["${LEGACY_LOCAL_STORAGE_KEYS[0]}","${LEGACY_LOCAL_STORAGE_KEYS[1]}"];`,
      "let storage=window.localStorage;for(let key of retiredKeys)storage.removeItem(key)}",
      `const a="${LEGACY_SNAPSHOT_STORE}";`,
      "function migrate(database){const metadata=database.name;database.objectStoreNames.contains(a)&&database.deleteObjectStore(a);return metadata}",
    ].join("");

    try {
      writeFileSync(path.join(distRoot, "index.js"), compiledCleanup);
      expect(() => verifyDist(distRoot)).not.toThrow();
    } finally {
      rmSync(distRoot, { recursive: true, force: true });
    }
  });

  it("rejects a compiled store identifier used outside contains and deleteObjectStore", () => {
    const distRoot = mkdtempSync(
      path.join(tmpdir(), "cortex-stavia-dist-active-store-use-"),
    );
    const compiledCleanup = [
      "function clearRetired(){",
      `let retiredKeys=["${LEGACY_LOCAL_STORAGE_KEYS[0]}","${LEGACY_LOCAL_STORAGE_KEYS[1]}"];`,
      "let storage=window.localStorage;for(let key of retiredKeys)storage.removeItem(key)}",
      `const a="${LEGACY_SNAPSHOT_STORE}";`,
      "function migrate(database){database.objectStoreNames.contains(a)&&database.deleteObjectStore(a);console.info(a)}",
    ].join("");

    try {
      writeFileSync(path.join(distRoot, "index.js"), compiledCleanup);
      expect(() => verifyDist(distRoot)).toThrow(
        /compiled store migration is not deletion-only/,
      );
    } finally {
      rmSync(distRoot, { recursive: true, force: true });
    }
  });

  it("rejects a second compiled consumer of the retired key collection", () => {
    const distRoot = mkdtempSync(
      path.join(tmpdir(), "cortex-stavia-dist-active-read-"),
    );
    const compiledCleanup = [
      "function clearRetired(){",
      `let retiredKeys=["${LEGACY_LOCAL_STORAGE_KEYS[0]}","${LEGACY_LOCAL_STORAGE_KEYS[1]}"];`,
      "let storage=window.localStorage;for(let key of retiredKeys)storage.removeItem(key);",
      "for(let key of retiredKeys){window.localStorage.getItem(key)}}",
      `const legacyStore="${LEGACY_SNAPSHOT_STORE}";`,
      "function migrate(database){database.objectStoreNames.contains(legacyStore)&&database.deleteObjectStore(legacyStore)}",
    ].join("");

    try {
      writeFileSync(path.join(distRoot, "index.js"), compiledCleanup);
      expect(() => verifyDist(distRoot)).toThrow(/deletion-only/);
    } finally {
      rmSync(distRoot, { recursive: true, force: true });
    }
  });

  it("makes every Vite build script run the explicit dist verifier", () => {
    const packageJson = JSON.parse(
      readFileSync(path.join(WEB_ROOT, "package.json"), "utf8"),
    ) as { scripts?: Record<string, string> };
    expect(inspectPackageBuildScripts(packageJson.scripts ?? {})).toEqual([]);
    for (const unsafeCommand of [
      "vite build",
      "vite --mode production build",
      "vite --config vite.config.ts build",
      "vite --mode production\nbuild",
      `vite --define 'process.env.X="a;b"' build`,
      `vite --define "process.env.X=a|b" build`,
      `sh -c 'vite --mode production build'`,
      `sh -c "bash -c 'vite --mode production build'"`,
      `bash -c "sh -c 'vite --mode production build'"`,
      `$'vite' build`,
      `bash -c $'vite --mode production build'`,
      `./node_modules/.bin/$'vite' build`,
      `vi$'te' build`,
      `${"${CORTEX_VITE_BIN:-vite}"} build`,
    ]) {
      expect(
        inspectPackageBuildScripts({ unsafe: unsafeCommand }),
        unsafeCommand,
      ).not.toEqual([]);
    }
    expect(
      inspectPackageBuildScripts({
        "build:unsafe":
          "vite --mode production build && node scripts/verify-stavia-boundary.mjs --dist && echo bypass",
      }),
    ).not.toEqual([]);
    expect(
      existsSync(
        path.join(WEB_ROOT, "scripts/verify-stavia-boundary.mjs"),
      ),
    ).toBe(true);
  });
});
