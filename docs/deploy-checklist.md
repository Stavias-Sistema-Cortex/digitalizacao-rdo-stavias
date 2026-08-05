# Córtex — checklist de deploy

Esta é a trava operacional do artefato atual: API Java 21, PWA, autenticação
online direta por CPF canônico (passkey como alternativa), grants colaborativos
offline e cofre por passkey PRF, Mensagens, armazenamento compartilhado, RDO,
Financeiro orientado à receita, Memória e grafo ontológico. Marque um item
somente com evidência da mesma revisão que será publicada.

## 1. Banco e migrações

- [ ] Backup restaurável do banco atual foi criado e testado.
- [ ] Um PostgreSQL 18 vazio aplicou Flyway V44–V63 sem `repair` ou edição de
  migration.
- [ ] Uma cópia representativa de `StaviasCortex` atualizou até V63.
- [ ] O usuário da API tem somente os privilégios necessários no schema.
- [ ] `CORTEX_POSTGRES_URL`, `CORTEX_POSTGRES_USER` e
  `CORTEX_POSTGRES_PASSWORD_FILE` apontam para o PostgreSQL canônico; nenhum
  `CORTEX_DB_*` é usado como fallback.
- [ ] Existe ao menos um `colaborador` ALFA ativo com `auth_identity` ATIVA,
  HMAC de CPF atual e origem Academy persistidos no PostgreSQL.
- [ ] Os registros ALFA explícitos anteriores permanecem ALFA após a migração.
- [ ] Antes do acesso de cada colaborador QA, um bootstrap/sync explicitamente
  auditado do Academy persistiu no PostgreSQL o HMAC de CPF e o estado ativo. O
  login normal consulta somente PostgreSQL, nunca MySQL em tempo de requisição.

A API em perfil `production` falha na inicialização quando o último requisito
de ALFA não é atendido. `/api/readiness` também consulta o banco e revalida esse
estado; `/api/health` mede somente o processo.

## 2. HTTPS, cookies, CPF e passkeys

- [ ] O proxy termina HTTPS e publica PWA e `/api` na mesma origem.
- [ ] `cortex-web` fica em `127.0.0.1` por padrão; qualquer override de
  `CORTEX_WEB_BIND_ADDRESS` pertence somente à rede privada do ingresso TLS.
- [ ] `CORTEX_PUBLIC_ORIGIN`, CORS e WebAuthn contêm somente a origem HTTPS
  exata, sem curinga, path, query ou credenciais.
- [ ] `CORTEX_AUTH_WEBAUTHN_RP_ID` é o hostname público sem esquema/porta.
- [ ] Cookies estão `Secure`; `SameSite` foi escolhido para a topologia real.
- [ ] O par PEM do offline grant está montado e o fingerprint público usado no
  build da PWA corresponde exatamente a esse par.
- [ ] O login por CPF normaliza o identificador, resolve somente a identidade
  Academy já espelhada em PostgreSQL e não depende de OTP/e-mail, senha ou de
  um gate de rate limit da aplicação. Proteções de borda, se adotadas, são
  configuradas e testadas no ingresso sem mudar essa política colaborativa.
- [ ] Sucesso por CPF emite somente cookie opaco + CSRF no hostname final; a
  resposta e os logs não expõem material de lookup, CPF persistido ou segredo.
- [ ] E-mail/OTP está indisponível no runtime normal e permanece isolado no
  perfil explícito `postgresql-activation`, com seu segredo próprio.
- [ ] Login por passkey foi exercitado como alternativa online explícita; o
  RP ID e a origem WebAuthn correspondem exatamente à origem publicada.
- [ ] Depois de login online, o grant colaborativo assinado permite reabertura
  offline somente com o CPF correspondente; o cofre PRF continua disponível
  apenas para uma passkey registrada explicitamente. E-mail, OTP e PIN não são
  fallbacks offline; sem grant/passkey a identidade continua bloqueada.

## 3. Secrets e providers

- [ ] CPF HMAC e chave privada offline vêm de arquivos secretos montados; os
  equivalentes inline estão vazios. OTP HMAC é requisito apenas do deployment
  de ativação explícita, nunca do runtime normal. O runtime normal
  `production,postgresql` não instancia `EmailGateway`, não configura SMTP e
  não executa o scheduler legado de cobranças.
- [ ] As URLs, usuários e arquivos de senha de Academy/Zeladoria são distintos
  do PostgreSQL canônico, montados por Config Tree, e os usuários das fontes
  têm somente `SELECT` no schema explicitamente autorizado.
- [ ] `CORTEX_AUTH_DEV_ADMIN_ENABLED=false` e
  `CORTEX_AUTH_PROVISIONING_ENABLED=false` no processo web.
- [ ] Quando a ativação explícita for executada, `CORTEX_EMAIL_PROVIDER=smtp`;
  provider `fake` não é usado para essa transição real. As variáveis e o
  arquivo secreto SMTP pertencem somente a esse processo de ativação (ou a um
  deployment legado explicitamente marcado `legacy-finance`), nunca ao login
  ou ao Compose normal.
- [ ] Nenhuma senha, OTP, CPF, token de sessão ou conteúdo de mensagem aparece
  em logs, imagem, compose ou repositório.

## 4. Arquivos e persistência

- [ ] O storage é S3 privado ou volume local explicitamente persistente e
  absoluto; diretórios temporários são rejeitados.
- [ ] Upload, download autorizado, reinício do container e novo download
  preservam o mesmo objeto e SHA-256.
- [ ] Limite do proxy, API e `VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES` estão
  alinhados.
- [ ] Backup/restore inclui banco e objetos; retenção e exclusão foram definidas.

## 5. Financeiro, Mensagens e autorização

- [ ] ALFA acessa todas as obras sem grants artificiais.
- [ ] BETA sem vínculo recebe 403; BETA vinculado, mas sem a capability financeira
  exata, também recebe 403 e não recebe valores em Home, sync, export, Memória
  ou grafo.
- [ ] Criação e atualização offline reconectam uma única vez pelo mesmo
  `clientMutationId`.
- [ ] RDO, anexos, rastreio de receita, preços e PDOR usam dados persistidos e
  o mesmo escopo autorizado da consulta.
- [ ] A navegação e as rotas ativas do Financeiro expõem somente `Rastreio de
  receita`, `Serviços e preços` e `PDOR`; custo, margem, compras, rateios, notas,
  pagamentos, cobranças e outros módulos legados permanecem inacessíveis.
- [ ] Memória e grafo retornam somente eventos/arestas autorizados, com
  cobertura e frescor explícitos; fatos apenas locais não são apresentados como
  confirmados pelo servidor.
- [ ] `CORTEX_SYNC_ACADEMY_ENABLED` e `CORTEX_SYNC_ZELADORIA_ENABLED`
  permaneceram `false` até a validação QA de cada fonte; URLs TLS, usuários
  `SELECT`-only e arquivos de senha foram verificados antes de habilitar cada
  scheduler, e seus resultados aparecem em `source_sync_run` no PostgreSQL.
- [ ] O replay offline da PWA foi exercitado com o app aberto e uma sessão
  online ativa em escrita local/reconexão/abertura/foreground. Não foi registrada
  promessa de execução universal com navegador ou PWA fechados.

## 6. Build e testes da revisão

```bash
export VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="$(
  openssl pkey -pubin \
    -in "${CORTEX_AUTH_OFFLINE_GRANT_PUBLIC_KEY_FILE:?Aponte para o PEM público do offline grant}" \
    -outform DER |
    openssl dgst -sha256 -binary |
    openssl base64 -A |
    tr '+/' '-_' |
  tr -d '='
)"
export VITE_CORTEX_API_BASE_URL=/api
export VITE_CORTEX_AUTH_MODE=postgresql
export VITE_CORTEX_MESSAGE_MAX_ATTACHMENT_BYTES=26214400

cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test

cd ../web
npm test -- --run
npm run verify:operational-layout
npm run lint
sh ./validate-docker-build-args.sh
npm run build
npm run typecheck:functions
npm run build:functions
node scripts/verify-stavia-boundary.mjs

cd ../..
docker build -t cortex-api:release apps/api
docker build -t cortex-web:release \
  --build-arg VITE_CORTEX_AUTH_MODE=postgresql \
  --build-arg \
    VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="${VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256:?Calcule e exporte o fingerprint SHA-256 base64url da chave pública offline}" \
  apps/web
./scripts/security/test-local-compose-security.sh
./scripts/security/test-production-publication.sh
docker compose -f compose.production.example.yml config
git diff --check
```

- [ ] Maven completo passou em JDK 21.
- [ ] PostgreSQL 18 descartável passou com migrations V44–V63 e os fluxos
  Cortex 3.0.
- [ ] O contrato de Compose de produção passou com secrets temporários, fontes
  MySQL somente leitura e porta web loopback.
- [ ] Vitest, lint e build PWA passaram.
- [ ] As duas imagens Docker buildaram e executam como configuradas.
- [ ] Desktop, tablet e mobile foram verificados sem overflow ou console error.
- [ ] Não há novos dados de negócio hardcoded, fixtures de produção ou totals
  fabricados.

## 7. Publicação e smoke

1. Faça merge em `develop` somente depois dos três gates. Registre o SHA exato.
2. Publique a imagem API e use seu digest imutável para executar Flyway no
   Neon com a conta migradora isolada.
3. Acione o deploy hook do Render com `ref=$GITHUB_SHA`; aguarde
   `/api/health` e `/api/readiness` devolverem a mesma `revision`, com
   `status=UP` e `status=READY`.
4. Somente então construa e publique Pages por Wrangler com
   `--branch develop --commit-hash "$GITHUB_SHA"`.
5. Execute `CORTEX_BASE_URL=https://host ./scripts/smoke-deploy.sh`.
6. Com uma sessão QA real, repita passando `CORTEX_SMOKE_COOKIE_JAR` e
   `CORTEX_SMOKE_OBRA_ID` para validar o escopo financeiro.
7. Monitore login, 401/403, sync, rastreio de receita, PDOR, latência e storage.

## 8. Rollback

- Preserve a imagem anterior e o backup pré-migração.
- Não altere nem apague migrations aplicadas. Rollback de aplicação só é seguro
  se a versão anterior tolerar as tabelas aditivas V45–V63.
- Nunca use `flyway repair` para mascarar checksum divergente.

## 9. Piloto Cloudflare Pages, Render, Neon e R2

- [ ] O banco canônico é exatamente `StaviasCortex`, com TLS Neon e usuário de
  menor privilégio configurados apenas como secrets do Render.
- [ ] `cortex-api` está no Render como web service Docker `free` na região
  `ohio`, branch `develop`, `autoDeployTrigger` desligado e health check
  `/api/readiness`.
- [ ] O auto-deploy do serviço `cortex-api` está desligado **no painel do
  Render**, e não apenas no `render.yaml`. O arquivo só governa serviços
  sincronizados como Blueprint; um serviço criado à mão ignora
  `autoDeployTrigger` e continua subindo sozinho a cada push em `develop`.
  Ver "Auto-deploy do Render fora de ordem", abaixo.
- [ ] O environment protegido `production` do GitHub possui os secrets
  `CORTEX_NEON_MIGRATION_PASSWORD`, `RENDER_DEPLOY_HOOK_URL` e
  `CLOUDFLARE_API_TOKEN`; URL Neon sem credencial, usuário migrador,
  `CORTEX_RENDER_ORIGIN`, conta/projeto Cloudflare e fingerprint público são
  variables protegidas. `CORTEX_RENDER_ORIGIN` aponta diretamente para o
  serviço Render.
- [ ] A role migradora é estável, dona dos objetos que Flyway altera e possui
  `NOSUPERUSER`, `NOCREATEDB`, `NOCREATEROLE`, `NOREPLICATION` e
  `NOBYPASSRLS`; `cortex_runtime` não herda essa role nem recebe DDL.
- [ ] Flyway foi executado com a imagem API por digest e a credencial migradora
  existiu somente no processo isolado. Render Free não tem pre-deploy command
  e o processo web não recebeu essa credencial.
- [ ] O usuário não-root da imagem API pertence ao GID 1000 e lê os arquivos
  secretos do Render sem torná-los públicos ou alterar seu modo.
- [ ] O deploy hook recebeu o SHA completo de `develop`; health e readiness
  confirmaram `revision == GITHUB_SHA` antes de publicar a PWA.
- [ ] O Pages mantém `/api/*` na mesma origem através da Function existente;
  o workflow grava `CORTEX_API_ORIGIN` como secret a partir da variável
  protegida `CORTEX_RENDER_ORIGIN` e nenhum proxy adicional foi publicado.
- [ ] O deploy automático de produção do Pages está desligado. O workflow
  constrói `apps/web/dist`, valida Functions, confirma `develop` como branch
  de produção e publica o SHA exato por Wrangler.
- [ ] O deployment Pages verificado é de produção, veio de `develop`, concluiu
  com sucesso e expõe o mesmo SHA em `/api/health` e `/api/readiness`.
- [ ] Novos anexos usam bucket Cloudflare R2 Standard privado. O endpoint
  HTTPS R2 foi validado, `CORTEX_STORAGE_S3_SEND_SSE_HEADER=false` foi usado
  somente para ele e as credenciais R2 existem apenas como secrets do Render.
- [ ] Sem Neon, Render ou R2, a API falha fechada: não há storage local,
  provider fake, dados fabricados ou fallback de conexão.
- [ ] Academy e Zeladoria permanecem com `CORTEX_IMPORT_ENABLED=false`,
  `CORTEX_SYNC_ACADEMY_ENABLED=false` e
  `CORTEX_SYNC_ZELADORIA_ENABLED=false` até que cada fonte read-only tenha um
  caminho público seguro e passe sua validação QA.

## Auto-deploy do Render fora de ordem

O deploy de produção tem uma ordem obrigatória, imposta pelo workflow: publicar
a imagem da API, **migrar o Neon com essa mesma imagem** e só então acionar o
Render e esperar a revisão subir. A migração é quem grava a evidência pública do
release; sem ela no banco, `PostgresqlRuntimeReadinessGuard` recusa iniciar,
porque `RENDER_GIT_COMMIT` não encontra linha correspondente.

Quando o serviço do Render está com auto-deploy ligado, ele clona o `develop` e
sobe a API por conta própria, minutos antes de a migração rodar. O resultado é um
deploy que falha assim:

```
==> Checking out commit <sha> in branch develop
java.lang.IllegalStateException: PostgreSQL Córtex não está pronto para
  validar o marcador público de release.
    at PostgresqlRuntimeReadinessGuard.requireReleaseEvidence(...)
==> Exited with status 1
```

Esse traço **não indica aplicação quebrada**: é o guarda cumprindo o papel dele,
impedindo a API de atender contra um banco sem evidência daquele release. O
deploy ordenado do workflow, que vem em seguida, sobe normalmente — mas o
diagnóstico se repete a cada release e consome tempo de quem investiga.

Como confirmar qual dos dois deploys falhou: compare o horário do log do Render
com o início do run de produção. Um crash **antes** do passo "Migrate Neon with
the immutable API image" é o auto-deploy fora de ordem. Um crash **depois** dele
é falha real e pede investigação.

Correção, no painel do Render (não há como fazer pelo repositório): desligar o
auto-deploy do serviço `cortex-api`, ou conectá-lo ao Blueprint para que o
`autoDeployTrigger: off` do `render.yaml` passe a valer. O deploy continua
acontecendo a cada merge — pelo deploy hook do workflow, na ordem correta.

## Limite de evidência externa

Build e testes locais comprovam o contrato do runtime normal, mas não comprovam
S3/Graph nem a entrega SMTP do processo isolado de ativação. Esses itens só
podem ser declarados validados depois de um smoke no ambiente com credenciais
próprias; sem elas, o handoff deve registrar a dependência como não verificada.
