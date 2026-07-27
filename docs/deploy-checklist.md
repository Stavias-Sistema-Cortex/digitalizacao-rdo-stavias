# Córtex — checklist de deploy

Esta é a trava operacional do artefato atual: API Java 21, PWA, autenticação
online direta por CPF canônico (passkey como alternativa), grants colaborativos
offline e cofre por passkey PRF, Mensagens, armazenamento compartilhado, RDO,
Financeiro orientado à receita, Memória e grafo ontológico. Marque um item
somente com evidência da mesma revisão que será publicada.

## 1. Banco e migrações

- [ ] Backup restaurável do banco atual foi criado e testado.
- [ ] Um PostgreSQL 18 vazio aplicou Flyway V44–V61 sem `repair` ou edição de
  migration.
- [ ] Uma cópia representativa de `StaviasCortex` atualizou até V61.
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
- [ ] `CORTEX_SYNC_ENABLED` foi deixado `false` até validar uma importação QA;
  as URLs, usuários `SELECT`-only e arquivos de senha de cada fonte foram
  verificados explicitamente antes de habilitá-lo, e os resultados aparecem em
  `source_sync_run` no PostgreSQL.
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

cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test

cd ../web
npm test -- --run
npm run lint
npm run build

cd ../..
docker build -t cortex-api:release apps/api
docker build -t cortex-web:release \
  --build-arg VITE_CORTEX_AUTH_MODE=postgresql \
  --build-arg \
    VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="${VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256:?Calcule e exporte o fingerprint SHA-256 base64url da chave pública offline}" \
  apps/web
./scripts/security/test-local-compose-security.sh
docker compose -f compose.production.example.yml config
git diff --check
```

- [ ] Maven completo passou em JDK 21.
- [ ] PostgreSQL 18 descartável passou com migrations V44–V61 e os fluxos
  Cortex 3.0.
- [ ] O contrato de Compose de produção passou com secrets temporários, fontes
  MySQL somente leitura e porta web loopback.
- [ ] Vitest, lint e build PWA passaram.
- [ ] As duas imagens Docker buildaram e executam como configuradas.
- [ ] Desktop, tablet e mobile foram verificados sem overflow ou console error.
- [ ] Não há novos dados de negócio hardcoded, fixtures de produção ou totals
  fabricados.

## 7. Publicação e smoke

1. Publique primeiro a API; aguarde `/api/readiness` responder `READY`.
2. Publique a PWA com `/api` na mesma origem e o fingerprint correto.
3. Execute `CORTEX_BASE_URL=https://host ./scripts/smoke-deploy.sh`.
4. Com uma sessão QA real, repita passando `CORTEX_SMOKE_COOKIE_JAR` e
   `CORTEX_SMOKE_OBRA_ID` para validar o escopo financeiro.
5. Monitore login, 401/403, sync, rastreio de receita, PDOR, latência e storage.

## 8. Rollback

- Preserve a imagem anterior e o backup pré-migração.
- Não altere nem apague migrations aplicadas. Rollback de aplicação só é seguro
  se a versão anterior tolerar as tabelas aditivas V45–V61.
- Nunca use `flyway repair` para mascarar checksum divergente.

## Limite de evidência externa

Build e testes locais comprovam o contrato do runtime normal, mas não comprovam
S3/Graph nem a entrega SMTP do processo isolado de ativação. Esses itens só
podem ser declarados validados depois de um smoke no ambiente com credenciais
próprias; sem elas, o handoff deve registrar a dependência como não verificada.
