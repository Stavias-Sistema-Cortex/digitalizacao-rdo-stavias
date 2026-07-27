# Córtex — piloto hospedado

## 1. Arquitetura e limites do plano gratuito

Cloudflare Pages é a única origem exposta ao navegador e encaminha somente
`/api/*` para o serviço `cortex-api` no Render. A API é um web service Docker
no plano `free`, região `ohio`, com health check em `/api/readiness`. O plano
Render Free não tem pre-deploy command: Flyway é uma etapa de release explícita
e concluída antes de iniciar uma nova API. A API usa Neon TLS para o banco
canônico `StaviasCortex` e Cloudflare R2 Standard privado para novos anexos.

Não habilite fallback local, storage local, provider fake ou dados simulados
quando Neon, Render ou R2 não estiverem disponíveis. Preserve somente os
nomes de variáveis em comandos, logs, screenshots e tickets; nunca exponha
valores de segredo, URL de conexão, identificador de conta ou endpoint.

## 2. Valores exigidos por plataforma

### Cloudflare Pages

- Secret: `CORTEX_API_ORIGIN`.
- Build variables: `VITE_CORTEX_API_BASE_URL`, `VITE_CORTEX_AUTH_MODE`,
  `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` e
  `VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES`.

### Render

- Neon/runtime: `CORTEX_POSTGRES_URL`, `CORTEX_POSTGRES_USER`,
  `CORTEX_POSTGRES_PASSWORD`, `CORTEX_POSTGRES_RUNTIME_READY`.
- Browser/auth: `CORTEX_CORS_ALLOWED_ORIGINS`, `CORTEX_AUTH_COOKIE_SECURE`,
  `CORTEX_AUTH_COOKIE_SAME_SITE`, `CORTEX_AUTH_WEBAUTHN_RP_ID`,
  `CORTEX_AUTH_WEBAUTHN_RP_NAME`, `CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS`,
  `CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_ID`,
  `CORTEX_AUTH_CPF_HMAC_CURRENT_KEY_FILE`,
  `CORTEX_AUTH_OFFLINE_GRANT_KEY_ID`,
  `CORTEX_AUTH_OFFLINE_GRANT_PRIVATE_KEY_FILE`,
  `CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE`,
  `CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_ID`,
  `CORTEX_MEMORY_CURSOR_HMAC_CURRENT_KEY_FILE`,
  `CORTEX_AUTH_DEV_ADMIN_ENABLED`, `CORTEX_AUTH_PROVISIONING_ENABLED`.
- R2: `CORTEX_STORAGE_PROVIDER`, `CORTEX_STORAGE_S3_BUCKET`,
  `CORTEX_STORAGE_S3_REGION`, `CORTEX_STORAGE_S3_ENDPOINT`,
  `CORTEX_STORAGE_S3_PREFIX`, `CORTEX_STORAGE_S3_PATH_STYLE`,
  `CORTEX_STORAGE_S3_SEND_SSE_HEADER`, `AWS_ACCESS_KEY_ID` e
  `AWS_SECRET_ACCESS_KEY`.
- Disabled remote pulls: `CORTEX_IMPORT_ENABLED` e `CORTEX_SYNC_ENABLED`.

### Neon

- Provide the TLS JDBC connection only through `CORTEX_POSTGRES_URL`.
- Provide the least-privilege runtime account only through
  `CORTEX_POSTGRES_USER` and `CORTEX_POSTGRES_PASSWORD`.

### Cloudflare R2

- Use a private Standard bucket through `CORTEX_STORAGE_S3_BUCKET` and the
  S3-compatible HTTPS value in `CORTEX_STORAGE_S3_ENDPOINT`.
- Use `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY` only as Render secrets;
  the R2 bucket must never be public.

## 3. Arquivos secretos do Render

Configure estes quatro arquivos secretos exatamente sob `/etc/secrets`:

- `/etc/secrets/cortex-cpf-hmac`
- `/etc/secrets/cortex-offline-private.pem`
- `/etc/secrets/cortex-offline-public.pem`
- `/etc/secrets/cortex-memory-cursor-hmac`

## 4. Release Flyway antes do deploy

Render Free não executa pre-deploy command. Após criar a imagem da revisão,
execute a migração com um arquivo de ambiente exclusivo de migração, antes de
liberar a API. Esse arquivo não é o conjunto de ambiente do runtime e usa uma
conta PostgreSQL migradora dedicada, nunca a conta runtime.

O arquivo protegido `cortex-render-migration.env` contém estes nomes (os
valores não são exibidos, versionados ou impressos):

- `SPRING_PROFILES_ACTIVE=postgresql-migrate`
- `CORTEX_POSTGRES_RUNTIME_READY=false`
- `CORTEX_POSTGRES_URL` com o datasource Neon TLS canônico `StaviasCortex`
- `CORTEX_POSTGRES_USER` com o usuário migrador dedicado
- `CORTEX_POSTGRES_PASSWORD` com a credencial migradora dedicada
- `CORTEX_MAIN_CLASS=com.projeto.cortex.postgresql.migrate.PostgresqlMigrationApplication`

Execute:

```bash
docker run --rm \
  --env-file /absolute/path/to/cortex-render-migration.env \
  cortex-api:release
```

O arquivo informado é local, protegido e não é versionado. Não use `set -x`,
não imprima variáveis e interrompa o release se Flyway falhar. Só então faça o
deploy manual do `cortex-api`; `autoDeployTrigger` permanece desligado.

## 5. Build do Pages

No Cloudflare Pages, configure root `apps/web`, comando `npm ci && npm run
build` e output `dist`. Mantenha a Function e as regras de proxy Pages atuais;
o navegador continua usando a mesma origem Pages para `/api`.

## 6. Smoke test

Após o Render informar que a release está pronta, verifique:

```text
/api/health
/api/readiness
```

Depois do deploy Pages, execute o smoke na origem Pages e confirme uma sessão
QA real para login, autorização, upload/download autorizado em R2 e os fluxos
offline. Falha de Neon, Render ou R2 é falha visível, não motivo para fallback.

## 7. Rollback

Crie e valide um dump local antes do cutover:

```bash
pg_dump --format=custom --file cortex-pre-render.dump StaviasCortex
```

Para reverter, preserve o serviço e a build Pages anteriores, restaure o dump
somente em um banco de recuperação revisado e faça deploy manual da revisão
anterior no Render e no Pages. Não apague migrations nem execute `flyway
repair` para ocultar um checksum divergente.

## 8. Limites de passkeys

Enquanto o piloto usar `pages.dev`, passkeys permanecem temporárias. Antes de
uma origem final, valide novamente RP ID, origem WebAuthn e as credenciais.

## 9. Academy e Zeladoria

Academy e Zeladoria permanecem com pulls desabilitados até que seus bancos
somente leitura estejam publicamente alcançáveis por um caminho seguro. Não
habilite importação ou sync para compensar indisponibilidade dessas fontes.
