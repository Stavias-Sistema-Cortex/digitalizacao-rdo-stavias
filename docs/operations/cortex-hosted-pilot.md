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
- Use project `Sistema Córtex`, PostgreSQL 18, region Ohio, and branch
  `production`. Confirm all four values in the Neon console before migration;
  do not infer the project or branch from a copied connection string.

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

## 4. Migração guardada do PostgreSQL local para Neon

Preserve o PostgreSQL local como rollback canônico. Não faça reset, limpeza ou
drop local e não remova o dump revisado após o cutover. Antes da primeira
release, configure no ambiente do operador, sem imprimir os valores:

- `CORTEX_SOURCE_PGURI`: URI PostgreSQL local para `StaviasCortex`.
- `CORTEX_NEON_ADMIN_PGURI`: URI owner do projeto Neon com
  `sslmode=require`, `verify-ca` ou `verify-full`, sem parâmetros duplicados.
- `CORTEX_NEON_RUNTIME_PASSWORD`: senha nova da role `cortex_runtime`.
- `CORTEX_NEON_MIGRATOR_ROLE`: nome não secreto, em minúsculas, da role
  migradora que criará objetos futuros. Ela deve existir e a conta owner deve
  ser membro; o script verifica as duas condições antes do dump.
- `CORTEX_NEON_ROLLBACK_DIR`: diretório absoluto, protegido e fora do checkout
  para o dump local de rollback.

Use um conjunto coerente de clientes PostgreSQL 18. O script procura
instalações PostgreSQL 18 conhecidas; para outra instalação, informe somente o
diretório não secreto por `CORTEX_POSTGRES_BIN_DIR`. Prepare um diretório novo
para cada tentativa, fora do checkout:

```bash
umask 077
install -d -m 700 /absolute/protected/cortex-neon-rollbacks
export CORTEX_NEON_ROLLBACK_DIR="$(
  mktemp -d /absolute/protected/cortex-neon-rollbacks/production.XXXXXX
)"
bash scripts/deploy/migrate-local-postgres-to-neon.sh
```

Não use `set -x` e não passe URI ou senha como argumento de comando. O script
interpreta as URIs sem imprimi-las, rejeita parâmetros duplicados ou TLS fraco,
preserva `channel_binding`, certificados e opções Neon seguras, e cria
`PGSERVICEFILE` e `PGPASSFILE` temporários com modo `0600`. Variáveis libpq
herdadas são removidas antes de executar qualquer cliente PostgreSQL; um
`TMPDIR` dentro do checkout também é rejeitado.

O bootstrap idempotente cria somente `StaviasCortex`. Antes de tocar em role,
grant, restore ou dados, o gate rejeita tabelas, views, materialized views,
sequences, functions, types, relações e schemas não-sistema. Ele nunca limpa
um alvo não vazio.

O dump PostgreSQL 18 usa um snapshot exportado por uma transação
`REPEATABLE READ READ ONLY` que permanece aberta. `pg_dump` e as contagens da
origem importam esse mesmo snapshot, evitando divergência por escritas
concorrentes sem exigir congelamento informal. O dump custom validado fica em
`$CORTEX_NEON_ROLLBACK_DIR/StaviasCortex-pre-neon.dump`, com modo `0600`, e
permanece preservado mesmo se uma etapa posterior falhar.

O restore usa transação única. Somente depois dele uma segunda transação cria
ou normaliza `cortex_runtime` como `NOSUPERUSER`, `NOCREATEDB`,
`NOCREATEROLE`, `NOREPLICATION` e `NOBYPASSRLS`, aplica os grants e configura
default privileges `FOR ROLE` da role migradora. A saída normal contém somente
`tabela|origem|alvo` para as dez tabelas centrais, todas qualificadas por
`public`; qualquer divergência encerra com status não zero.

Falha de restore deixa o alvo vazio e permite nova tentativa com um diretório
de rollback novo, preservando o dump anterior. Falha posterior ao restore ou
divergência deixa um alvo parcial deliberadamente não reutilizável: interrompa,
preserve a origem e o dump, isole o alvo para revisão e reprovisione um alvo
Neon vazio pelo control plane sob aprovação operacional. Nunca adicione
`--clean`, drop ou limpeza automática ao script. Se Neon estiver indisponível,
a migração falha; não habilite banco local, dados falsos ou outro fallback no
serviço hospedado.

## 5. Release Flyway antes do deploy

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

## 6. Build do Pages

No Cloudflare Pages, configure root `apps/web`, comando `npm ci && npm run
build` e output `dist`. Mantenha a Function e as regras de proxy Pages atuais;
o navegador continua usando a mesma origem Pages para `/api`.

## 7. Smoke test

Após o Render informar que a release está pronta, verifique:

```text
/api/health
/api/readiness
```

Depois do deploy Pages, execute o smoke na origem Pages e confirme uma sessão
QA real para login, autorização, upload/download autorizado em R2 e os fluxos
offline. Falha de Neon, Render ou R2 é falha visível, não motivo para fallback.

## 8. Rollback

O script de migração cria e valida o dump de rollback com o mesmo toolset
PostgreSQL 18 usado pelo snapshot. O dump fica no diretório externo protegido
configurado por `CORTEX_NEON_ROLLBACK_DIR`; não o mova para o checkout, não o
anexe a tickets e não reduza suas permissões. Guarde também o PostgreSQL local
intacto.

Para reverter, preserve o serviço e a build Pages anteriores, restaure o dump
somente em um banco de recuperação revisado e faça deploy manual da revisão
anterior no Render e no Pages. Não apague migrations nem execute `flyway
repair` para ocultar um checksum divergente.

## 9. Limites de passkeys

Enquanto o piloto usar `pages.dev`, passkeys permanecem temporárias. Antes de
uma origem final, valide novamente RP ID, origem WebAuthn e as credenciais.

## 10. Academy e Zeladoria

Academy e Zeladoria permanecem com pulls desabilitados até que seus bancos
somente leitura estejam publicamente alcançáveis por um caminho seguro. Não
habilite importação ou sync para compensar indisponibilidade dessas fontes.
