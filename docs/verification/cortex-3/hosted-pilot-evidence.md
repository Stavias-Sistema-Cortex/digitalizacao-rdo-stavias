# Hosted pilot local release evidence

- Verified release candidate: `21d64c1bf5e930eae1968fb7e6d86090aac657cd`
- Requested base candidate: `6f7ce4769ffcefa77ae6f5cf225a2d01cb00c191`
- Captured: `2026-07-27T18:58:40-03:00`
- Machine timezone: `America/Sao_Paulo` (`UTC-03:00`)

The requested base candidate failed the strict Java and web source-boundary
allowlists because four new hosted-migration files contain the canonical
database name `StaviasCortex`. Commit `21d64c1` adds exact line/count-scoped
allowlist entries and classifies tracked SQL as text in the web verifier. The
base SHA must not be published without that correction.

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
| API image | repository root | `docker build -t cortex-api:hosted-candidate apps/api` | Built as user `cortex`; 166,751,238 bytes; image `sha256:07602b01191ce88a1f13e726ce8546da9d4b5fc4f9993c58ed1c2390e324936c` |
| Web image | repository root | `docker build -t cortex-web:hosted-candidate --build-arg VITE_CORTEX_API_BASE_URL=/api --build-arg VITE_CORTEX_AUTH_MODE=postgresql --build-arg VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256=y4dPPatQjmG1FsEkgmK9vpzULEIIXq0aunFfyBvNIyw apps/web` | Built as user `nginx`; 24,586,657 bytes; image `sha256:2b364eb4bc940d81ec2d7dbdca4542f2c678e70f1272cc64983aed84c8549eb1` |

The web image fingerprint is a public SHA-256 value already tracked by the CI
workflow. No private key, credential, connection string, or ignored environment
file was used or recorded.

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

## Scope and remaining live proof

No live Neon migration, Render deployment, Cloudflare Pages/R2 action, or live
cloud smoke test has happened. These results cover local gates and local image
builds only; the next phase must verify the same reviewed SHA in remote CI
before any cloud credential or deployment action.

Non-blocking warnings observed:

- Flyway recommends an upgrade because this version is tested through
  PostgreSQL 17, while the PostgreSQL 18.4 integration suite passed.
- ESLint reported the existing `react-hooks/exhaustive-deps` warning in
  `FinanceInvoicesPanel.tsx`.
- Docker's generic secret heuristic labeled `VITE_CORTEX_AUTH_MODE`; the actual
  value was the non-secret mode `postgresql`.
