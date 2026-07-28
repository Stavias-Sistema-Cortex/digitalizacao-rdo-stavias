# Hosted pilot local release evidence

- Verified release candidate: `0f09f61cc3e8d5b763681fe5e567292f52ff790c`
- Dedicated deploy ref: `feat/cortex-hosted-candidate-2026-07-27-v2`
- Requested base candidate: `6f7ce4769ffcefa77ae6f5cf225a2d01cb00c191`
- Captured: `2026-07-27T18:58:40-03:00`
- Revalidated: `2026-07-27T21:19:29-03:00`
- Machine timezone: `America/Sao_Paulo` (`UTC-03:00`)

The requested base candidate failed the strict Java and web source-boundary
allowlists because four new hosted-migration files contain the canonical
database name `StaviasCortex`. Commit `21d64c1` adds exact line/count-scoped
allowlist entries and classifies tracked SQL as text in the web verifier. The
base SHA must not be published without that correction.

The first dedicated candidate, `21d64c1`, passed local gates but its exact
GitHub run exposed a timezone-dependent integration-test fixture on the UTC
runner. Candidate `0f09f61` keeps the production query behavior unchanged and
uses one dedicated PostgreSQL test connection with an explicit
`America/Sao_Paulo` session timezone. The previous ref remains preserved; the
v2 ref was created and fully revalidated instead of rewriting it.

## Exact build-source binding

The reviewed build source for the hosted pilot is immutable:

- Ref: `refs/heads/feat/cortex-hosted-candidate-2026-07-27-v2`
- SHA: `0f09f61cc3e8d5b763681fe5e567292f52ff790c`

The local ref was created without switching the active checkout. The checkout
remained on the documentation branch at `0f09f61` during ref creation, while
the dedicated v2 ref resolved exactly to the same commit.

These read-only checks exited `0`:

```bash
test "$(git rev-parse refs/heads/feat/cortex-hosted-candidate-2026-07-27-v2)" \
  = "0f09f61cc3e8d5b763681fe5e567292f52ff790c"
git diff --quiet \
  0f09f61cc3e8d5b763681fe5e567292f52ff790c..HEAD \
  -- apps/api apps/web render.yaml
test -z "$(git diff --name-only \
  0f09f61cc3e8d5b763681fe5e567292f52ff790c..HEAD \
  -- apps/api apps/web render.yaml)"
```

Therefore `apps/api`, `apps/web`, and `render.yaml` are byte-for-byte unchanged
between the verified candidate and the documentation HEAD. The API, PostgreSQL,
web, deployment, migration, image, and secret gates were all rerun for v2.

Task 8 must push and provision Cloudflare Pages and Render from this dedicated
ref at this exact SHA. Later commits on
`feat/cortex-render-cloudflare-deploy` contain evidence or documentation and
are not approved build sources. Stop Task 8 if the local ref, remote ref,
provider branch, or provider-reported deploy commit differs from the SHA above.

## Local gates

All commands below exited `0` on the corrected candidate tree.

| Area | Working directory | Exact command | Result |
| --- | --- | --- | --- |
| API | `apps/api` | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw --batch-mode test` | 1,064 tests; 0 failures; 0 errors; 54 skipped |
| PostgreSQL IT | `apps/api` | `JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw --batch-mode -Ppostgresql-it verify` | PostgreSQL 18.4; 163 IT; 0 failures/errors/skips; 19 migrations through V61 |
| Web install | `apps/web` | `npm ci` | 627 packages; audit reported 0 vulnerabilities |
| Web tests | `apps/web` | `npm test -- --run` | 160 files; 937 tests; all passed |
| Web lint | `apps/web` | `npm run lint` | 0 errors; 1 warning |
| Pages typecheck | `apps/web` | `npm run typecheck:functions` | Passed |
| Pages build | `apps/web` | `npm run build:functions` | Worker compiled |
| PWA build | `apps/web` | `npm run build` | Vite/PWA build and source/dist boundary passed |
| Publication contract | repository root | `bash scripts/security/test-production-publication.sh` | Passed |
| Compose security | repository root | `bash scripts/security/test-local-compose-security.sh` | Passed |
| Hosted contract | repository root | `bash scripts/security/test-hosted-deployment-contract.sh` | Passed |
| Neon migration contract | repository root | `bash scripts/security/test-neon-migration-contract.sh` | Static and behavioral contracts passed |
| Secret scan | repository root | `bash scripts/security/scan-cortex-secrets.sh` | No unreviewed literal candidates |
| API image | repository root | `docker build -t cortex-api:hosted-candidate-v2 apps/api` | Built as user `cortex`; 166,750,544 bytes; image `sha256:f0622cec081826be62b4ee9d8753b074004dc5b14019cfe2d690b09038a16480` |
| Web image | repository root | `docker build -t cortex-web:hosted-candidate-v2 --build-arg VITE_CORTEX_API_BASE_URL=/api --build-arg VITE_CORTEX_AUTH_MODE=postgresql --build-arg VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=y4dPPatQjmG1FsEkgmK9vpzULEIIXq0aunFfyBvNIyw apps/web` | Built as user `nginx`; 24,586,657 bytes; image `sha256:97f0b974824dccc5d0225a1e2467d6684199f00aa5877652b7e0419686170d1e` |

The web image fingerprint is a public SHA-256 value already tracked by the CI
workflow. No private key, credential, connection string, or ignored environment
file was used or recorded.

## Exact remote gates

GitHub Actions run
[`30316655562`](https://github.com/Stavias-Sistema-Cortex/digitalizacao-rdo-stavias/actions/runs/30316655562)
completed successfully against ref
`feat/cortex-hosted-candidate-2026-07-27-v2` and exact head SHA
`0f09f61cc3e8d5b763681fe5e567292f52ff790c`.

| Remote job | Result | Duration |
| --- | --- | ---: |
| Release gate · API and PostgreSQL 18 | Passed | 5 minutes 32 seconds |
| Release gate · PWA | Passed | 1 minute 31 seconds |
| Release gate · deployment and secrets | Passed | 26 seconds |
| Publish immutable production images | Skipped as required outside `develop` | 0 seconds |

Both the documentation branch and the dedicated v2 deploy ref were resolved
from the remote at the exact candidate SHA before this run. No production image
was published and no Render, Cloudflare Pages, or R2 resource was created by
the workflow.

## Local PostgreSQL snapshot

The following aggregate-only values were read from the unchanged canonical
local PostgreSQL container. No write, migration, repair, clean, baseline, or
deletion was executed against it.

- Latest schema: `61`, successful
- Database size: `36,124,351` bytes

| Core table | Rows |
| --- | ---: |
| `auth_identity` | 461 |
| `auth_capacidade_administrativa` | 2 |
| `colaborador` | 480 |
| `obra` | 1 |
| `rdo` | 2 |
| `rdo_mao_obra` | 0 |
| `vinculo_colaborador_obra` | 0 |
| `cortex_evento_operacional` | 1,588 |
| `finance_lancamento` | 0 |
| `stored_object` | 0 |

## Neon migration rehearsal

- Captured: `2026-07-27T20:43:13-03:00`
- Successful branch: `migration-rehearsal-2`, created from `production`
- PostgreSQL: `18.4`
- Guarded dump and restore duration: approximately 4 minutes 56 seconds
- Latest Flyway migration: `61`, successful
- Runtime role: login enabled; superuser, database creation, role creation,
  replication, and row-level-security bypass all disabled
- Runtime authentication read: 461 identities
- Historical synchronization errors preserved: 1

The first disposable branch, `migration-rehearsal`, completed the transactional
restore but rejected the runtime-role transaction. It remains isolated for
review and expires automatically. No automatic clean, drop, repair, baseline,
or reuse was attempted.

The live failure exposed two migration-tooling defects. The migrator-role check
passed a `psql` variable through `-c`, where it was not substituted, and the
runtime-role normalization attempted to restate `NOSUPERUSER`, which Neon
correctly reserves for a superuser. The corrected contract passes the role
query through standard input, creates the runtime role with PostgreSQL's safe
defaults, changes only login, inheritance, and password, and then verifies all
restricted attributes through `pg_roles`. The behavioral migration contract
failed before each correction and passed afterward.

The successful guarded restore compared the same exported source snapshot with
the Neon target:

| Core table | Source | Neon |
| --- | ---: | ---: |
| `auth_identity` | 461 | 461 |
| `auth_capacidade_administrativa` | 2 | 2 |
| `colaborador` | 480 | 480 |
| `obra` | 1 | 1 |
| `rdo` | 1 | 1 |
| `rdo_mao_obra` | 0 | 0 |
| `vinculo_colaborador_obra` | 0 | 0 |
| `cortex_evento_operacional` | 1,861 | 1,861 |
| `finance_lancamento` | 0 | 0 |
| `stored_object` | 0 | 0 |

The isolated migration application validated the schema without `repair`,
`clean`, or `baseline`. Read-only SQL then confirmed Flyway V61, the
authentication counts, the historical error row, the restricted runtime-role
attributes, and a runtime-role read from `auth_identity`.

The protected rollback dump remains outside the checkout with owner-only
permissions. The local PostgreSQL source was not cleaned, dropped, migrated, or
otherwise replaced.

## Scope and remaining live proof

A live Neon rehearsal and the exact remote release gates have now passed. No
Render deployment, Cloudflare Pages/R2 action, or hosted application smoke test
has happened. The next phase requires explicit confirmation immediately before
creating persistent R2 credentials and handing Neon/R2 secrets to Render.

Non-blocking warnings observed:

- Flyway recommends an upgrade because this version is tested through
  PostgreSQL 17, while the PostgreSQL 18.4 integration suite passed.
- Neon reported warnings for grants on extension-owned `pg_trgm` functions;
  the runtime transaction, restricted-role verification, and application-table
  grants still completed successfully.
- ESLint reported the existing `react-hooks/exhaustive-deps` warning in
  `FinanceInvoicesPanel.tsx`.
- Docker's generic secret heuristic labeled `VITE_CORTEX_AUTH_MODE`; the actual
  value was the non-secret mode `postgresql`.
