# Córtex Cloudflare, Render and Neon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Publish the real Córtex PWA through Cloudflare Pages, run its Java API on Render Free, migrate the canonical PostgreSQL database to Neon Free, and store new attachments in Cloudflare R2.

**Architecture:** The browser uses one Cloudflare Pages origin. A narrowly routed Pages Function proxies `/api/*` to the Render API, which connects over TLS to the `StaviasCortex` database on Neon and uses the existing S3 adapter for R2. Migration and release remain fail-closed: local work is preserved before merging, secrets never enter Git, data is rehearsed before cutover, and the local database remains the rollback source.

**Tech Stack:** React 19, Vite 7, TypeScript, Vitest, Cloudflare Pages Functions/Wrangler 4.114.0, Spring Boot 3, Java 21, Maven, Flyway, PostgreSQL 18, Render Blueprint, Cloudflare R2 S3 API.

## Global Constraints

- Initial operating cost is zero; paid upgrades require a separate decision.
- Canonical database name is exactly `StaviasCortex`.
- Neon project is `Sistema Córtex`, PostgreSQL 18, AWS US East 2 (Ohio), branch `production`.
- Neon Auth remains disabled; Córtex authentication remains authoritative.
- Cloudflare Pages is the only browser-facing application origin.
- The initial hostname is `*.pages.dev`; permanent passkey enrollment waits for a custom domain.
- Render uses the `free` web-service plan in the `ohio` region.
- Render Free has no pre-deploy command; Flyway runs as an explicit release step before the API starts.
- New attachment content uses private Cloudflare R2 Standard storage.
- Existing AWS S3 behavior continues to send `AES256`; only validated R2 endpoints may omit the unsupported SSE request header.
- Secrets and connection strings never enter Git, command output, browser screenshots, build arguments, or application logs.
- No local or fake-data fallback is enabled when Neon, Render, or R2 is unavailable.
- Existing local changes are preserved; no `git restore`, reset, or wholesale ours/theirs conflict resolution is allowed.

---

## File Map

### Files to create

- `apps/web/functions/api/[[path]].ts` — Cloudflare Pages catch-all route for `/api/*`.
- `apps/web/src/lib/deploy/pagesApiProxy.ts` — testable fixed-origin HTTP proxy.
- `apps/web/src/lib/deploy/pagesApiProxy.test.ts` — proxy security and forwarding tests.
- `apps/web/tsconfig.functions.json` — Pages Function TypeScript boundary.
- `apps/web/public/_routes.json` — invoke Functions only for `/api/*`.
- `apps/web/public/_redirects` — Pages SPA fallback.
- `apps/web/public/_headers` — production security headers for Pages assets.
- `render.yaml` — Render Free API service contract.
- `scripts/security/test-hosted-deployment-contract.sh` — static hosted-deployment fail-closed checks.
- `scripts/deploy/prepare-neon-database.sql` — idempotent database and runtime-role grants without embedded secrets.
- `scripts/deploy/migrate-local-postgres-to-neon.sh` — guarded custom-format dump, restore, and count comparison.
- `scripts/security/test-neon-migration-contract.sh` — migration-script safety contract.
- `docs/operations/cortex-hosted-pilot.md` — concise hosted pilot runbook and rollback commands.

### Files to modify

- `apps/web/package.json` and `apps/web/package-lock.json` — pin Wrangler and Workers types; add Function checks.
- `apps/web/vite.config.ts` — keep the API base at `/api` and align production header constants with Pages.
- `apps/api/src/main/java/com/projeto/cortex/storage/StorageProperties.java` — expose the R2-safe SSE-header switch.
- `apps/api/src/main/java/com/projeto/cortex/storage/StorageDeploymentPolicy.java` — allow the switch only for an HTTPS R2 endpoint.
- `apps/api/src/main/java/com/projeto/cortex/storage/ObjectStorageConfiguration.java` — pass the switch to the S3 adapter.
- `apps/api/src/main/java/com/projeto/cortex/storage/S3ObjectStorage.java` — conditionally add the `AES256` request header.
- `apps/api/src/main/resources/application.yml` — bind `CORTEX_STORAGE_S3_SEND_SSE_HEADER`.
- `apps/api/src/test/java/com/projeto/cortex/storage/S3ObjectStorageTest.java` — preserve AWS behavior and cover R2 behavior.
- `apps/api/src/test/java/com/projeto/cortex/storage/StorageDeploymentPolicyTest.java` — reject unsafe header omission.
- `scripts/security/test-production-publication.sh` — invoke the hosted deployment contract.
- `docs/deploy-checklist.md` — add Neon, Render, Pages, R2, and cutover gates.

---

### Task 1: Preserve the worktree and converge with `origin/develop`

**Files:**
- Modify: all currently changed and untracked application files, preserved as a safety commit.
- Consume after merge: `.github/workflows/production.yml`, `deploy/production/*`, `scripts/deploy/*`, and remote Flyway V61.

**Interfaces:**
- Consumes: current branch `feat/cortex-render-cloudflare-deploy` at specification commit `dd460b3`.
- Produces: a recoverable local snapshot commit followed by a reviewed merge commit containing `origin/develop`.

- [ ] **Step 1: Record the immutable starting point**

Run:

```bash
git status --short --branch
git rev-parse HEAD
git rev-parse origin/develop
git diff --stat
git ls-files --others --exclude-standard
```

Expected: current branch is `feat/cortex-render-cloudflare-deploy`; `origin/develop` is 55 commits ahead of the old local base; no ignored `.env.local` or runtime secret appears in the untracked list.

- [ ] **Step 2: Scan the complete local patch before staging**

Run:

```bash
git diff --check
git diff -- . ':(exclude)*.png' ':(exclude)*.xlsx' |
  rg -n -i 'BEGIN (RSA |EC |OPENSSH )?PRIVATE KEY|aws_secret_access_key|postgres(ql)?://[^[:space:]]+:[^[:space:]@]+@'
```

Expected: `git diff --check` exits zero and the secret-pattern command prints no real credential.

- [ ] **Step 3: Create the worktree safety commit**

Run:

```bash
git add -A
git diff --cached --check
git status --short
git commit -m "chore: snapshot Cortex 3 deployment candidate"
```

Expected: all non-ignored local work is recoverable from one commit; `.env.local`, `.runtime`, database dumps, and secret files remain absent.

- [ ] **Step 4: Merge the fetched remote branch**

Run:

```bash
git merge --no-ff origin/develop
git diff --name-only --diff-filter=U
```

Expected: Git performs a three-way merge. The second command lists only genuine conflicts.

- [ ] **Step 5: Resolve conflicts by behavior**

For each conflicted file:

```bash
git diff --cc -- path/to/conflicted-file
git show HEAD:path/to/conflicted-file
git show origin/develop:path/to/conflicted-file
```

Preserve the already validated event timestamps, event actor, RDO workforce editing, sync mutation identity, collapsed Obras ontology, UI weight/spacing, and concise copy. Preserve remote V61, hosted-production security contracts, and the updated PostgreSQL test corrections. Do not resolve any file using an unreviewed whole-file `--ours` or `--theirs`.

- [ ] **Step 6: Run focused regression tests for every resolved subsystem**

Run focused Maven and Vitest test classes selected from the conflict list. At minimum:

```bash
cd apps/web
npm test -- --run \
  src/features/rdos/RdoWorkforceEditor.test.tsx \
  src/features/rdos/RdoCreatePage.workforceContext.test.tsx \
  src/features/home/memory/MemoryLedger.test.tsx \
  src/features/obras/ObrasPage.sync.test.tsx \
  src/uiPolish.test.ts

cd ../api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw --batch-mode \
  -Dtest=OperationalMemoryQueryServiceIT,VinculoColaboradorObraServiceTest,CanonicalOperationsCoverageTest \
  test
```

Expected: all selected tests pass.

- [ ] **Step 7: Complete the merge commit**

Run:

```bash
git diff --name-only --diff-filter=U
git diff --cached --check
git commit
```

Expected: no unmerged path remains and the merge commit records both parents.

---

### Task 2: Add the same-origin Cloudflare Pages API proxy

**Files:**
- Create: `apps/web/functions/api/[[path]].ts`
- Create: `apps/web/src/lib/deploy/pagesApiProxy.ts`
- Create: `apps/web/src/lib/deploy/pagesApiProxy.test.ts`
- Create: `apps/web/tsconfig.functions.json`
- Create: `apps/web/public/_routes.json`
- Create: `apps/web/public/_redirects`
- Create: `apps/web/public/_headers`
- Modify: `apps/web/package.json`
- Modify: `apps/web/package-lock.json`
- Modify: `apps/web/vite.config.ts`

**Interfaces:**
- Consumes: Pages secret `CORTEX_API_ORIGIN`, an origin such as `https://cortex-api.onrender.com` with no path, query, or fragment.
- Produces: `proxyApiRequest(request, environment, fetchImpl): Promise<Response>` and Cloudflare export `onRequest`.

- [ ] **Step 1: Write failing fixed-origin proxy tests**

Create `apps/web/src/lib/deploy/pagesApiProxy.test.ts` with these cases:

```ts
import { describe, expect, it, vi } from "vitest";
import { proxyApiRequest } from "./pagesApiProxy";

describe("proxyApiRequest", () => {
  it("preserves the api path, query, method, body, origin, and cookies", async () => {
    const upstreamFetch = vi.fn(async (request: Request) => {
      expect(request.url).toBe(
        "https://cortex-api.onrender.com/api/rdos?limit=20",
      );
      expect(request.method).toBe("POST");
      expect(request.headers.get("origin")).toBe(
        "https://cortex-stavias.pages.dev",
      );
      expect(request.headers.get("cookie")).toBe("CORTEX_SESSION=opaque");
      expect(await request.text()).toBe('{"numero":"RDO-0002"}');
      return new Response('{"ok":true}', {
        status: 201,
        headers: {
          "content-type": "application/json",
          "set-cookie": "CORTEX_SESSION=next; Secure; HttpOnly; SameSite=Lax",
        },
      });
    });

    const response = await proxyApiRequest(
      new Request(
        "https://cortex-stavias.pages.dev/api/rdos?limit=20",
        {
          method: "POST",
          headers: {
            origin: "https://cortex-stavias.pages.dev",
            cookie: "CORTEX_SESSION=opaque",
            "content-type": "application/json",
          },
          body: '{"numero":"RDO-0002"}',
        },
      ),
      { CORTEX_API_ORIGIN: "https://cortex-api.onrender.com" },
      upstreamFetch,
    );

    expect(response.status).toBe(201);
    expect(response.headers.get("set-cookie")).toContain("CORTEX_SESSION=");
  });

  it.each([
    "",
    "http://cortex-api.onrender.com",
    "https://cortex-api.onrender.com/base",
    "https://cortex-api.onrender.com?target=other",
    "https://user:password@cortex-api.onrender.com",
  ])("rejects an unsafe upstream origin: %s", async (origin) => {
    await expect(
      proxyApiRequest(
        new Request("https://cortex-stavias.pages.dev/api/health"),
        { CORTEX_API_ORIGIN: origin },
        vi.fn(),
      ),
    ).rejects.toThrow("CORTEX_API_ORIGIN");
  });
});
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```bash
cd apps/web
npm test -- --run src/lib/deploy/pagesApiProxy.test.ts
```

Expected: FAIL because `pagesApiProxy.ts` does not exist.

- [ ] **Step 3: Implement the minimal proxy**

Create `apps/web/src/lib/deploy/pagesApiProxy.ts`:

```ts
export type ApiProxyEnvironment = {
  CORTEX_API_ORIGIN: string;
};

export type FetchLike = (request: Request) => Promise<Response>;

function configuredOrigin(value: string): string {
  const candidate = value?.trim();
  if (!candidate) {
    throw new Error("CORTEX_API_ORIGIN não configurada.");
  }
  const url = new URL(candidate);
  if (
    url.protocol !== "https:" ||
    url.username ||
    url.password ||
    url.pathname !== "/" ||
    url.search ||
    url.hash
  ) {
    throw new Error("CORTEX_API_ORIGIN inválida.");
  }
  return url.origin;
}

export async function proxyApiRequest(
  request: Request,
  environment: ApiProxyEnvironment,
  fetchImpl: FetchLike = fetch,
): Promise<Response> {
  const source = new URL(request.url);
  if (!source.pathname.startsWith("/api/")) {
    return new Response("Not found", { status: 404 });
  }
  const target = new URL(source.pathname + source.search, configuredOrigin(
    environment.CORTEX_API_ORIGIN,
  ));
  const upstream = await fetchImpl(new Request(target, request));
  return new Response(upstream.body, {
    status: upstream.status,
    statusText: upstream.statusText,
    headers: upstream.headers,
  });
}
```

Create `apps/web/functions/api/[[path]].ts`:

```ts
import {
  proxyApiRequest,
  type ApiProxyEnvironment,
} from "../../src/lib/deploy/pagesApiProxy";

export const onRequest: PagesFunction<ApiProxyEnvironment> = (context) =>
  proxyApiRequest(context.request, context.env);
```

- [ ] **Step 4: Add the Function type boundary and pinned tooling**

Create `apps/web/tsconfig.functions.json`:

```json
{
  "compilerOptions": {
    "target": "ES2023",
    "lib": ["ES2023", "WebWorker"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "types": ["@cloudflare/workers-types"],
    "strict": true,
    "noEmit": true,
    "skipLibCheck": true
  },
  "include": ["functions/**/*.ts", "src/lib/deploy/pagesApiProxy.ts"]
}
```

Install and add scripts:

```bash
cd apps/web
npm install --save-dev wrangler@4.114.0 @cloudflare/workers-types@5.20260727.1
npm pkg set \
  'scripts.typecheck:functions=tsc -p tsconfig.functions.json' \
  'scripts.build:functions=wrangler pages functions build --outdir=./dist/functions-worker'
```

- [ ] **Step 5: Add Pages routes, SPA fallback, and security headers**

Create `apps/web/public/_routes.json`:

```json
{
  "version": 1,
  "include": ["/api/*"],
  "exclude": []
}
```

Create `apps/web/public/_redirects`:

```text
/* /index.html 200
```

Create `apps/web/public/_headers` with the same CSP and headers used by `vite.config.ts`:

```text
/*
  Content-Security-Policy: default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'; form-action 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: https:; font-src 'self' data:; connect-src 'self' https: wss:; worker-src 'self' blob:; manifest-src 'self'
  Permissions-Policy: camera=(), microphone=(), geolocation=(self)
  Referrer-Policy: strict-origin-when-cross-origin
  X-Content-Type-Options: nosniff
  X-Frame-Options: DENY
```

- [ ] **Step 6: Run proxy, type, Function-build, and web-build checks**

Run:

```bash
cd apps/web
npm test -- --run src/lib/deploy/pagesApiProxy.test.ts
npm run typecheck:functions
npm run build:functions
npm run build
test -f dist/_routes.json
test -f dist/_redirects
test -f dist/_headers
```

Expected: all commands pass; Function compilation creates `dist/functions-worker`; the three Pages control files are copied into `dist`.

- [ ] **Step 7: Commit the Pages boundary**

Run:

```bash
git add apps/web/functions apps/web/src/lib/deploy \
  apps/web/tsconfig.functions.json apps/web/public/_routes.json \
  apps/web/public/_redirects apps/web/public/_headers \
  apps/web/package.json apps/web/package-lock.json apps/web/vite.config.ts
git commit -m "feat(web): add Cloudflare Pages API boundary"
```

---

### Task 3: Make S3 writes compatible with encrypted-at-rest R2

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StorageProperties.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/StorageDeploymentPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/ObjectStorageConfiguration.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/storage/S3ObjectStorage.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/test/java/com/projeto/cortex/storage/S3ObjectStorageTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/storage/StorageDeploymentPolicyTest.java`

**Interfaces:**
- Consumes: `CORTEX_STORAGE_S3_SEND_SSE_HEADER`, default `true`.
- Produces: `new S3ObjectStorage(client, bucket, prefix, sendSseHeader)`; false is valid only for an HTTPS `*.r2.cloudflarestorage.com` endpoint.

- [ ] **Step 1: Write failing S3 request tests**

Extend `S3ObjectStorageTest`:

```java
@Test
void omitsTheUnsupportedSseHeaderForR2() {
    S3Client client = mock(S3Client.class);
    when(client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
            .thenReturn(PutObjectResponse.builder().build());
    S3ObjectStorage storage = new S3ObjectStorage(
            client,
            "cortex-private",
            "production",
            false
    );

    storage.put(
            "objects/ab/id/hash",
            new ByteArrayInputStream(new byte[]{1}),
            1,
            "application/octet-stream"
    );

    ArgumentCaptor<PutObjectRequest> request =
            ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(client).putObject(request.capture(), any(RequestBody.class));
    assertThat(request.getValue().serverSideEncryption()).isNull();
}
```

Update the existing AWS test to pass `true` and keep asserting `AES256`.

- [ ] **Step 2: Write failing deployment-policy tests**

Extend `StorageDeploymentPolicyTest`:

```java
@Test
void sseHeaderCanOnlyBeDisabledForTheHttpsR2Endpoint() {
    StorageProperties properties = new StorageProperties();
    properties.setProvider("s3");
    properties.getS3().setBucket("cortex-private");
    properties.getS3().setRegion("auto");
    properties.getS3().setSendSseHeader(false);

    assertThatThrownBy(() -> StorageDeploymentPolicy.validate(properties, false))
            .hasMessageContaining("R2");

    properties.getS3().setEndpoint(
            "https://account-id.r2.cloudflarestorage.com"
    );
    assertThatCode(() -> StorageDeploymentPolicy.validate(properties, false))
            .doesNotThrowAnyException();

    properties.getS3().setEndpoint(
            "https://r2.cloudflarestorage.com.attacker.example"
    );
    assertThatThrownBy(() -> StorageDeploymentPolicy.validate(properties, false))
            .hasMessageContaining("R2");
}
```

- [ ] **Step 3: Run focused tests and verify they fail**

Run:

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw --batch-mode \
  -Dtest=S3ObjectStorageTest,StorageDeploymentPolicyTest test
```

Expected: compilation fails because the new property and constructor do not exist.

- [ ] **Step 4: Add the property with the secure default**

In `StorageProperties.S3` add:

```java
private boolean sendSseHeader = true;

public boolean isSendSseHeader() {
    return sendSseHeader;
}

public void setSendSseHeader(boolean sendSseHeader) {
    this.sendSseHeader = sendSseHeader;
}
```

In `application.yml` add below `path-style`:

```yaml
send-sse-header: ${CORTEX_STORAGE_S3_SEND_SSE_HEADER:true}
```

- [ ] **Step 5: Implement conditional request construction**

Change the `S3ObjectStorage` constructor to store a `boolean sendSseHeader`. Build the request first and add encryption only when enabled:

```java
PutObjectRequest.Builder request = PutObjectRequest.builder()
        .bucket(bucket)
        .key(resolveKey(key))
        .contentLength(length)
        .contentType(requireSimpleValue(mediaType, "mediaType"));
if (sendSseHeader) {
    request.serverSideEncryption(ServerSideEncryption.AES256);
}
client.putObject(
        request.build(),
        RequestBody.fromInputStream(inputStream, length)
);
```

Pass `configuration.isSendSseHeader()` from `ObjectStorageConfiguration`.

- [ ] **Step 6: Add fail-closed R2 endpoint validation**

In `StorageDeploymentPolicy.validateS3`, when the header is disabled:

```java
URI endpoint = URI.create(strip(properties.getS3().getEndpoint()));
String host = endpoint.getHost();
if (!"https".equalsIgnoreCase(endpoint.getScheme())
        || host == null
        || !host.endsWith(".r2.cloudflarestorage.com")
        || endpoint.getUserInfo() != null
        || endpoint.getQuery() != null
        || endpoint.getFragment() != null) {
    throw new IllegalStateException(
            "O header SSE só pode ser omitido para um endpoint HTTPS R2."
    );
}
```

- [ ] **Step 7: Run focused and storage-wide tests**

Run:

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw --batch-mode \
  -Dtest='com.projeto.cortex.storage.*Test' test
```

Expected: all storage tests pass; AWS remains encrypted by explicit request and R2 omits only the unsupported header.

- [ ] **Step 8: Commit the storage compatibility change**

Run:

```bash
git add apps/api/src/main/java/com/projeto/cortex/storage \
  apps/api/src/main/resources/application.yml \
  apps/api/src/test/java/com/projeto/cortex/storage
git commit -m "feat(api): support encrypted Cloudflare R2 storage"
```

---

### Task 4: Define the Render Free API contract

**Files:**
- Create: `render.yaml`
- Create: `scripts/security/test-hosted-deployment-contract.sh`
- Create: `docs/operations/cortex-hosted-pilot.md`
- Modify: `scripts/security/test-production-publication.sh`
- Modify: `docs/deploy-checklist.md`

**Interfaces:**
- Consumes: Pages origin; Neon JDBC values; R2 values; existing CPF HMAC and offline-key files.
- Produces: Render service `cortex-api`, `plan: free`, `region: ohio`, readiness check `/api/readiness`.

- [ ] **Step 1: Write the failing hosted contract**

Create `scripts/security/test-hosted-deployment-contract.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
render_file="$repo_root/render.yaml"
pages_root="$repo_root/apps/web"

for required in \
  "$render_file" \
  "$pages_root/functions/api/[[path]].ts" \
  "$pages_root/public/_routes.json" \
  "$pages_root/public/_headers" \
  "$pages_root/public/_redirects"; do
  [[ -f "$required" ]] || {
    echo "missing hosted deployment file: $required" >&2
    exit 1
  }
done

grep -Fq 'plan: free' "$render_file"
grep -Fq 'region: ohio' "$render_file"
grep -Fq 'runtime: docker' "$render_file"
grep -Fq 'healthCheckPath: /api/readiness' "$render_file"
grep -Fq 'SPRING_PROFILES_ACTIVE' "$render_file"
grep -Fq 'value: production,postgresql' "$render_file"
grep -Fq 'CORTEX_POSTGRES_RUNTIME_READY' "$render_file"
grep -Fq 'CORTEX_STORAGE_S3_SEND_SSE_HEADER' "$render_file"
grep -Fq 'value: "false"' "$render_file"
grep -Fq 'CORTEX_AUTH_DEV_ADMIN_ENABLED' "$render_file"
grep -Fq 'CORTEX_AUTH_PROVISIONING_ENABLED' "$render_file"

if grep -Eiq \
  '(postgres(ql)?://[^[:space:]]+:[^[:space:]@]+@|BEGIN .*PRIVATE KEY|AWS_SECRET_ACCESS_KEY:[[:space:]]*[^[:space:]]+)' \
  "$render_file"; then
  echo "render.yaml contains an inline credential" >&2
  exit 1
fi

echo "Hosted Render and Cloudflare contracts passed."
```

Make it executable:

```bash
chmod +x scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-hosted-deployment-contract.sh
```

Expected: FAIL because `render.yaml` does not exist.

- [ ] **Step 2: Add the Render Blueprint**

Create `render.yaml`:

```yaml
services:
  - type: web
    name: cortex-api
    runtime: docker
    plan: free
    region: ohio
    dockerfilePath: ./apps/api/Dockerfile
    dockerContext: ./apps/api
    autoDeployTrigger: off
    healthCheckPath: /api/readiness
    envVars:
      - key: SPRING_PROFILES_ACTIVE
        value: production,postgresql
      - key: CORTEX_POSTGRES_URL
        sync: false
      - key: CORTEX_POSTGRES_USER
        sync: false
      - key: CORTEX_POSTGRES_PASSWORD
        sync: false
      - key: CORTEX_POSTGRES_RUNTIME_READY
        value: "true"
      - key: CORTEX_CORS_ALLOWED_ORIGINS
        sync: false
      - key: CORTEX_AUTH_COOKIE_SECURE
        value: "true"
      - key: CORTEX_AUTH_COOKIE_SAME_SITE
        value: Lax
      - key: CORTEX_AUTH_WEBAUTHN_RP_ID
        sync: false
      - key: CORTEX_AUTH_WEBAUTHN_RP_NAME
        value: Córtex
      - key: CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS
        sync: false
      - key: CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID
        sync: false
      - key: CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE
        value: /etc/secrets/cortex-cpf-hmac
      - key: CORTEX_AUTH_OFFLINE_GRANT_KEY_ID
        sync: false
      - key: CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE
        value: /etc/secrets/cortex-offline-private.pem
      - key: CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE
        value: /etc/secrets/cortex-offline-public.pem
      - key: CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID
        sync: false
      - key: CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE
        value: /etc/secrets/cortex-memory-cursor-hmac
      - key: CORTEX_AUTH_DEV_ADMIN_ENABLED
        value: "false"
      - key: CORTEX_AUTH_PROVISIONING_ENABLED
        value: "false"
      - key: CORTEX_STORAGE_PROVIDER
        value: s3
      - key: CORTEX_STORAGE_S3_BUCKET
        sync: false
      - key: CORTEX_STORAGE_S3_REGION
        value: auto
      - key: CORTEX_STORAGE_S3_ENDPOINT
        sync: false
      - key: CORTEX_STORAGE_S3_PREFIX
        value: production
      - key: CORTEX_STORAGE_S3_PATH_STYLE
        value: "true"
      - key: CORTEX_STORAGE_S3_SEND_SSE_HEADER
        value: "false"
      - key: AWS_ACCESS_KEY_ID
        sync: false
      - key: AWS_SECRET_ACCESS_KEY
        sync: false
      - key: CORTEX_IMPORT_ENABLED
        value: "false"
      - key: CORTEX_SYNC_ENABLED
        value: "false"
```

- [ ] **Step 3: Integrate the hosted contract into the existing gate**

Add this call near the start of `scripts/security/test-production-publication.sh`:

```bash
bash "$repo_root/scripts/security/test-hosted-deployment-contract.sh"
```

- [ ] **Step 4: Write the operator runbook**

Create `docs/operations/cortex-hosted-pilot.md` with these exact sections:

1. architecture and free-tier limits;
2. required Pages, Render, Neon, and R2 values by variable name;
3. four Render secret filenames under `/etc/secrets`;
4. explicit Flyway-before-deploy command;
5. Pages build settings: root `apps/web`, command `npm ci && npm run build`, output `dist`;
6. smoke-test endpoints;
7. local dump and Render/Pages rollback commands;
8. warning that passkeys remain temporary on `pages.dev`;
9. warning that Academy and Zeladoria pulls remain disabled until their read-only databases are publicly reachable through a secure path.

Do not place any credential value, account ID, endpoint ID, or connection string in the runbook.

- [ ] **Step 5: Run and commit the deployment contract**

Run:

```bash
bash scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-production-publication.sh
git diff --check
git add render.yaml scripts/security/test-hosted-deployment-contract.sh \
  scripts/security/test-production-publication.sh \
  docs/operations/cortex-hosted-pilot.md docs/deploy-checklist.md
git commit -m "chore: define hosted Cortex pilot"
```

Expected: both contract scripts pass and no secret is staged.

---

### Task 5: Add a guarded Neon migration path

**Files:**
- Create: `scripts/deploy/prepare-neon-database.sql`
- Create: `scripts/deploy/migrate-local-postgres-to-neon.sh`
- Create: `scripts/security/test-neon-migration-contract.sh`
- Modify: `docs/operations/cortex-hosted-pilot.md`

**Interfaces:**
- Consumes: `CORTEX_SOURCE_PGURI`, `CORTEX_NEON_ADMIN_PGURI`, and `CORTEX_NEON_RUNTIME_PASSWORD` from the operator environment.
- Produces: empty Neon database `StaviasCortex`, login role `cortex_runtime`, restored schema/data, and equal core-table counts.

- [ ] **Step 1: Write the failing migration safety contract**

Create `scripts/security/test-neon-migration-contract.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
script="$repo_root/scripts/deploy/migrate-local-postgres-to-neon.sh"
sql="$repo_root/scripts/deploy/prepare-neon-database.sql"

[[ -x "$script" ]] || {
  echo "Neon migration script is missing or not executable" >&2
  exit 1
}
[[ -f "$sql" ]] || {
  echo "Neon database preparation SQL is missing" >&2
  exit 1
}

bash -n "$script"
grep -Fq 'set -euo pipefail' "$script"
grep -Fq 'umask 077' "$script"
grep -Fq 'mktemp -d' "$script"
grep -Fq 'trap cleanup EXIT' "$script"
grep -Fq 'pg_dump' "$script"
grep -Fq -- '--format=custom' "$script"
grep -Fq -- '--no-owner' "$script"
grep -Fq -- '--no-acl' "$script"
grep -Fq 'pg_restore' "$script"
grep -Fq -- '--exit-on-error' "$script"
grep -Fq 'sslmode=require' "$script"

if grep -Eq -- '--clean|--if-exists|dropdb|DROP DATABASE' "$script"; then
  echo "Migration script contains a destructive target operation" >&2
  exit 1
fi
if grep -Eq 'set -x|echo .*(PGURI|PASSWORD)' "$script"; then
  echo "Migration script can disclose a credential" >&2
  exit 1
fi

echo "Neon migration safety contract passed."
```

Run it and expect failure because the migration files do not exist.

- [ ] **Step 2: Create the idempotent database and role SQL**

Create `scripts/deploy/prepare-neon-database.sql`:

```sql
\set ON_ERROR_STOP on

SELECT 'CREATE DATABASE "StaviasCortex"'
WHERE NOT EXISTS (
    SELECT 1 FROM pg_database WHERE datname = 'StaviasCortex'
)
\gexec

SELECT format(
    'CREATE ROLE cortex_runtime LOGIN PASSWORD %L',
    :'runtime_password'
)
WHERE NOT EXISTS (
    SELECT 1 FROM pg_roles WHERE rolname = 'cortex_runtime'
)
\gexec

ALTER ROLE cortex_runtime PASSWORD :'runtime_password';
GRANT CONNECT ON DATABASE "StaviasCortex" TO cortex_runtime;
```

After the restore, the migration script connects to `StaviasCortex` and applies:

```sql
GRANT USAGE ON SCHEMA public TO cortex_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON ALL TABLES IN SCHEMA public TO cortex_runtime;
GRANT USAGE, SELECT, UPDATE
    ON ALL SEQUENCES IN SCHEMA public TO cortex_runtime;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA public TO cortex_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO cortex_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO cortex_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT EXECUTE ON FUNCTIONS TO cortex_runtime;
```

- [ ] **Step 3: Implement the guarded dump and restore**

Create `scripts/deploy/migrate-local-postgres-to-neon.sh` with:

```bash
#!/usr/bin/env bash
set -euo pipefail
umask 077

: "${CORTEX_SOURCE_PGURI:?Set the local PostgreSQL URI}"
: "${CORTEX_NEON_ADMIN_PGURI:?Set the Neon owner URI with sslmode=require}"
: "${CORTEX_NEON_RUNTIME_PASSWORD:?Set the runtime-role password}"

[[ "$CORTEX_NEON_ADMIN_PGURI" == *"sslmode=require"* ]] || {
  echo "Neon admin URI must require TLS." >&2
  exit 1
}

for command in psql pg_dump pg_restore; do
  command -v "$command" >/dev/null 2>&1 || {
    echo "Missing PostgreSQL command: $command" >&2
    exit 1
  }
done

work_dir="$(mktemp -d "${TMPDIR:-/tmp}/cortex-neon-migration.XXXXXX")"
cleanup() {
  find "$work_dir" -type f -delete 2>/dev/null || true
  rmdir "$work_dir" 2>/dev/null || true
}
trap cleanup EXIT

dump_file="$work_dir/StaviasCortex.dump"
target_admin_uri="${CORTEX_NEON_ADMIN_PGURI%/*}/StaviasCortex?sslmode=require"

psql "$CORTEX_NEON_ADMIN_PGURI" \
  -v runtime_password="$CORTEX_NEON_RUNTIME_PASSWORD" \
  -f "$(git rev-parse --show-toplevel)/scripts/deploy/prepare-neon-database.sql"

target_tables="$(psql "$target_admin_uri" -Atqc \
  "select count(*) from pg_tables where schemaname='public'")"
[[ "$target_tables" == "0" ]] || {
  echo "Target StaviasCortex database is not empty; migration stopped." >&2
  exit 1
}

pg_dump \
  --dbname="$CORTEX_SOURCE_PGURI" \
  --format=custom \
  --no-owner \
  --no-acl \
  --file="$dump_file"

pg_restore \
  --dbname="$target_admin_uri" \
  --exit-on-error \
  --no-owner \
  --no-acl \
  "$dump_file"
```

Append the grants from Step 2 and compare these exact tables on source and target:

```text
auth_identity
auth_capacidade_administrativa
colaborador
obra
rdo
rdo_mao_obra
vinculo_colaborador_obra
cortex_evento_operacional
finance_lancamento
stored_object
```

For each table, query `count(*)` on both URIs, print only `table|source_count|target_count`, and exit nonzero on the first mismatch.

- [ ] **Step 4: Run the migration safety contract**

Run:

```bash
chmod +x scripts/deploy/migrate-local-postgres-to-neon.sh \
  scripts/security/test-neon-migration-contract.sh
bash scripts/security/test-neon-migration-contract.sh
```

Expected: PASS without reading or printing any secret value.

- [ ] **Step 5: Commit migration tooling**

Run:

```bash
git add scripts/deploy/prepare-neon-database.sql \
  scripts/deploy/migrate-local-postgres-to-neon.sh \
  scripts/security/test-neon-migration-contract.sh \
  docs/operations/cortex-hosted-pilot.md
git commit -m "chore: add guarded Neon migration tooling"
```

---

### Task 6: Run the complete local release gates

**Files:**
- Verify: entire repository.
- Record: `docs/verification/cortex-3/hosted-pilot-evidence.md`

**Interfaces:**
- Consumes: merged application, Pages proxy, R2 adapter, Render contract, migration scripts.
- Produces: fresh evidence that the exact release candidate is safe to push and host.

- [ ] **Step 1: Run the API and PostgreSQL 18 gates**

Run:

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw --batch-mode test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw --batch-mode \
  -Ppostgresql-it verify
```

Expected: both commands exit zero, including Flyway V61 on disposable PostgreSQL 18.

- [ ] **Step 2: Run the PWA and Pages gates**

Run:

```bash
cd apps/web
npm ci
npm test -- --run
npm run lint
npm run typecheck:functions
npm run build:functions
npm run build
```

Expected: full Vitest, ESLint, TypeScript, Wrangler Function build, and Vite production build pass.

- [ ] **Step 3: Run deployment and secret gates**

Run from the repository root:

```bash
bash scripts/security/test-production-publication.sh
bash scripts/security/test-local-compose-security.sh
bash scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-neon-migration-contract.sh
bash scripts/security/scan-cortex-secrets.sh
```

Expected: all scripts exit zero and no credential is reported.

- [ ] **Step 4: Build both production containers**

Run:

```bash
docker build -t cortex-api:hosted-candidate apps/api
docker build \
  --build-arg VITE_CORTEX_API_BASE_URL=/api \
  --build-arg VITE_CORTEX_AUTH_MODE=postgresql \
  --build-arg VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="$VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256" \
  -t cortex-web:hosted-candidate apps/web
```

Expected: both Docker images build without a secret in an image layer or build log.

- [ ] **Step 5: Record concise verification evidence**

Create `docs/verification/cortex-3/hosted-pilot-evidence.md` with:

- candidate commit SHA;
- date and machine timezone;
- exact gate commands and exit codes;
- PostgreSQL schema version;
- database size and core counts;
- statement that no live cloud smoke test has happened yet.

- [ ] **Step 6: Commit the evidence**

Run:

```bash
git add docs/verification/cortex-3/hosted-pilot-evidence.md
git commit -m "docs: record hosted pilot release gates"
```

---

### Task 7: Rehearse the database migration on a Neon branch

**Files:**
- Execute: `scripts/deploy/migrate-local-postgres-to-neon.sh`
- Verify: Neon branch `migration-rehearsal`
- Update: `docs/verification/cortex-3/hosted-pilot-evidence.md`

**Interfaces:**
- Consumes: current local `StaviasCortex`, Neon owner credential copied without displaying it, and a generated runtime-role password.
- Produces: a disposable Neon branch with matching data and successful Flyway V61.

- [ ] **Step 1: Confirm secret handling before copying credentials**

At this checkpoint, request explicit approval to copy the Neon connection string and create a runtime password. Do not place either value in a tool result, chat response, shell history, file under the repository, or screenshot.

- [ ] **Step 2: Create the Neon rehearsal branch**

In the Neon console, create branch `migration-rehearsal` from `production`. Use its direct connection, not the pooled endpoint, for restore and Flyway.

- [ ] **Step 3: Run the guarded restore**

Export the three required variables only in a transient terminal session and run:

```bash
bash scripts/deploy/migrate-local-postgres-to-neon.sh
```

Expected count baseline from the current local database includes:

```text
auth_identity|461
auth_capacidade_administrativa|2
colaborador|480
obra|1
rdo|1
stored_object|0
```

If counts change before cutover, the script compares against the live source values instead of these historical numbers.

- [ ] **Step 4: Apply the merged Flyway migration**

Run the candidate API migrator against rehearsal:

```bash
cd apps/api
SPRING_PROFILES_ACTIVE=postgresql-migrate \
CORTEX_MAIN_CLASS=com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication \
JAVA_HOME=$(/usr/libexec/java_home -v 21) \
./mvnw --batch-mode spring-boot:run
```

The PostgreSQL connection variables point to the rehearsal `StaviasCortex`. Expected: Flyway advances to V61 without `repair`, `clean`, or baseline.

- [ ] **Step 5: Run database readiness and authentication smoke tests**

Run read-only SQL checks:

```sql
SELECT version, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 1;

SELECT COUNT(*) FROM auth_identity;
SELECT COUNT(*) FROM auth_capacidade_administrativa;
SELECT COUNT(*) FROM sync_mutacao_cliente WHERE status = 'ERRO';
```

Expected: latest version is `61`, successful; authentication counts match source. The historical error row remains evidence and is not silently deleted.

- [ ] **Step 6: Record and review rehearsal evidence**

Append branch name, restore duration, schema version, count comparison, and any warnings to `hosted-pilot-evidence.md`. Do not record the Neon host, username, password, project ID, branch ID, or connection string.

- [ ] **Step 7: Commit the rehearsal evidence**

Run:

```bash
git add docs/verification/cortex-3/hosted-pilot-evidence.md
git commit -m "docs: record Neon migration rehearsal"
```

---

### Task 8: Publish the reviewed branch and provision the free services

**Files:**
- Publish: current Git branch.
- Configure: Cloudflare R2, Render `cortex-api`, Cloudflare Pages `cortex-stavias`.

**Interfaces:**
- Consumes: green local gates, successful Neon rehearsal, existing GitHub connection in Cloudflare, and Render workspace `My Workspace`.
- Produces: deployed API and frontend preview with no production data cutover yet.

- [ ] **Step 1: Push the feature branch**

Run:

```bash
git status --short --branch
git push -u origin feat/cortex-render-cloudflare-deploy
```

Expected: the remote branch is created and the worktree is clean.

- [ ] **Step 2: Wait for and inspect GitHub checks**

Require the API, PostgreSQL, PWA, deployment, and secret gates to pass for the pushed SHA. Do not deploy a different SHA.

- [ ] **Step 3: Request confirmation before persistent cloud credentials**

Creating R2 API credentials and copying Neon/Render secrets creates persistent access. Request explicit confirmation immediately before these actions.

- [ ] **Step 4: Create the empty R2 bucket and scoped token**

Create private Standard bucket `cortex-stavias-production`. Create an R2 token restricted to object read/write for that bucket. Record its endpoint, access key ID, and secret only in the transient handoff to Render.

- [ ] **Step 5: Create the Render API from `render.yaml`**

In Render, create a Blueprint/Web Service from the exact feature-branch SHA. Confirm:

- service `cortex-api`;
- `free` plan;
- Ohio;
- Docker context `apps/api`;
- health check `/api/readiness`;
- auto deploy disabled.

Enter all `sync: false` values in the dashboard. Upload the four existing secret files:

```text
cortex-cpf-hmac
cortex-offline-private.pem
cortex-offline-public.pem
cortex-memory-cursor-hmac
```

Use the Neon `cortex_runtime` role, never the Neon owner role, for the API.
For this preview, point the three PostgreSQL variables to the restored
`migration-rehearsal/StaviasCortex`, using the rehearsal branch's runtime role.

- [ ] **Step 6: Create Cloudflare Pages through Git integration**

Exit the current “Create a Worker” flow without pressing its Deploy button. Create a Pages project:

```text
Project name: cortex-stavias
Production branch: feat/cortex-render-cloudflare-deploy
Root directory: apps/web
Build command: npm ci && npm run build
Build output directory: dist
```

Set build variables:

```text
VITE_CORTEX_API_BASE_URL=/api
VITE_CORTEX_AUTH_MODE=postgresql
VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=<current public-key fingerprint>
VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES=26214400
```

Set the Pages Function secret `CORTEX_API_ORIGIN` to the Render HTTPS origin.

- [ ] **Step 7: Complete the circular origin configuration**

After Cloudflare assigns the stable `pages.dev` hostname, update these Render variables to that exact HTTPS origin:

```text
CORTEX_CORS_ALLOWED_ORIGINS
CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS
```

Set `CORTEX_AUTH_WEBAUTHN_RP_ID` to the hostname without `https://`. Keep permanent passkey enrollment disabled by operating policy until a custom domain exists.

- [ ] **Step 8: Deploy the rehearsal-data preview**

Deploy Render and Pages using the reviewed SHA. Expected: Render readiness is
`READY` against `migration-rehearsal`, and authenticated smoke tests can read
the rehearsed dataset. Do not make persistent business changes in rehearsal.
Do not weaken readiness to make the deploy green.

---

### Task 9: Perform the final Neon cutover

**Files:**
- Execute: migration and Flyway scripts.
- Update: hosted-pilot evidence.

**Interfaces:**
- Consumes: verified preview services and local canonical database.
- Produces: Neon `production/StaviasCortex` as canonical database with the exact final dataset.

- [ ] **Step 1: Drain local offline writes**

On every participating browser/device:

1. open the local Córtex;
2. click synchronization;
3. verify the outbox has no pending local change;
4. close editing screens.

The server currently contains one historical `ERRO` mutation for immutable RDO provenance; preserve it. It is not an unsent browser outbox item.

- [ ] **Step 2: Freeze local writes**

Stop the local API after the outbox is drained. Keep PostgreSQL running read-only for the final dump and rollback. Record the freeze timestamp in America/Sao_Paulo.

Verify `SELECT COUNT(*) FROM stored_object` is still zero. If it is nonzero,
stop the cutover: the corresponding private object payloads must be copied to
R2 and verified by their stored hashes before the database can move.

- [ ] **Step 3: Restore the final dump into Neon production**

Run the guarded migration script against Neon branch `production`. It must reject a nonempty target. If rehearsal data was mistakenly placed in production, stop and request approval before any cleanup.

- [ ] **Step 4: Apply Flyway V61 and runtime grants**

Run the explicit migrator with the Neon owner credential, then rerun the runtime grants. Verify:

```sql
SELECT version, success
FROM flyway_schema_history
ORDER BY installed_rank DESC
LIMIT 1;
```

Expected: `61|true`.

- [ ] **Step 5: Start or redeploy Render**

Replace the three rehearsal PostgreSQL variables with the production
`StaviasCortex` runtime JDBC URL, user, and password. Render uses
`cortex_runtime`. Wait until `/api/readiness` returns 2xx and JSON status
`READY`. Do not change `CORTEX_POSTGRES_RUNTIME_READY=true` or the required
schema version to bypass a failure.

- [ ] **Step 6: Redeploy Pages after API readiness**

Trigger the Pages deployment for the same reviewed SHA. Confirm the Pages Function points to the ready Render service.

---

### Task 10: Validate the published system and close the rollback window

**Files:**
- Update: `docs/verification/cortex-3/hosted-pilot-evidence.md`
- Update: `docs/deploy-checklist.md`

**Interfaces:**
- Consumes: live Pages URL, ready Render API, migrated Neon database, empty R2 bucket.
- Produces: evidence-backed deploy decision and a retained rollback dump.

- [ ] **Step 1: Run unauthenticated boundary checks**

Verify:

```bash
curl -fsS https://<pages-host>/api/health
curl -fsS https://<pages-host>/api/readiness
curl -I https://<pages-host>/
```

Expected: health/readiness succeed through Pages; the root includes the configured security headers. The final evidence stores status and headers, not the temporary hostname if it exposes an account-specific identifier.

- [ ] **Step 2: Run authenticated browser smoke tests**

Using the Pages hostname:

1. login with an existing PostgreSQL identity;
2. open Home and Memória;
3. confirm event time uses the current device timezone and the responsible user is visible;
4. open Obras and expand the ontology toggle;
5. create/edit a draft RDO;
6. remove and add an RDO collaborator;
7. synchronize and confirm no duplicate `clientMutationId`;
8. open Financeiro;
9. upload and download a small permitted attachment to exercise R2;
10. sign out and confirm the session cookie is cleared.

- [ ] **Step 3: Verify canonical data after live writes**

Query Neon read-only:

```sql
SELECT COUNT(*) FROM rdo;
SELECT COUNT(*) FROM rdo_mao_obra;
SELECT COUNT(*) FROM cortex_evento_operacional;
SELECT COUNT(*) FROM sync_mutacao_cliente WHERE status = 'ERRO';
SELECT COUNT(*) FROM stored_object;
```

Expected: the browser smoke changes are represented once; the old historical error is preserved; the test attachment creates one private stored-object record.

- [ ] **Step 4: Verify free-tier usage and logs**

Inspect:

- Neon database size, compute, and network usage;
- Render memory, startup duration, readiness, and errors;
- Pages Function errors and invocation count;
- R2 stored bytes and Class A/B operations.

No log may contain a cookie, CPF, database URI, R2 secret, HMAC key, or private key.

- [ ] **Step 5: Decide release or rollback**

Release criteria:

- all smoke checks pass;
- the API is ready after a cold start;
- canonical counts are correct;
- no new failed sync mutation exists;
- the R2 object is private and downloadable only through authorized API access.

Rollback on any failed criterion:

1. disable the Pages preview or point it to a maintenance response;
2. stop Render;
3. resume the local API against the unchanged local PostgreSQL;
4. keep the failed Neon state for diagnosis;
5. do not run a destructive database downgrade.

- [ ] **Step 6: Finalize evidence and commit**

Record the deployed commit SHA, gate results, smoke outcomes, count comparison, and rollback readiness. Do not record secrets or account identifiers.

Run:

```bash
git add docs/verification/cortex-3/hosted-pilot-evidence.md \
  docs/deploy-checklist.md
git commit -m "docs: verify hosted Cortex pilot"
git status --short --branch
```

Expected: verification is committed and the branch is clean.
