# Córtex — checklist de deploy

Esta é a trava operacional do artefato atual: API Java 21, PWA, autenticação
online por CPF + OTP de e-mail (passkey como alternativa), cofre offline por
passkey PRF, Mensagens, armazenamento compartilhado, RDO, Financeiro orientado
à receita, Memória e grafo ontológico. Marque um item somente com evidência da
mesma revisão que será publicada.

## 1. Banco e migrações

- [ ] Backup restaurável do banco atual foi criado e testado.
- [ ] Um PostgreSQL 18 vazio aplicou Flyway V44–V60 sem `repair` ou edição de
  migration.
- [ ] Uma cópia representativa de `StaviasCortex` atualizou até V60.
- [ ] O usuário da API tem somente os privilégios necessários no schema.
- [ ] `CORTEX_POSTGRES_URL`, `CORTEX_POSTGRES_USER` e
  `CORTEX_POSTGRES_PASSWORD_FILE` apontam para o PostgreSQL canônico; nenhum
  `CORTEX_DB_*` é usado como fallback.
- [ ] Existe ao menos um `colaborador` ALFA ativo com `auth_identity` ATIVA e
  `email_verificado_em` preenchido.
- [ ] Os registros ALFA explícitos anteriores permanecem ALFA após a migração.
- [ ] Antes do acesso de cada colaborador QA, a sincronização/bootstrap da
  Academy (ou o processo de provisionamento explicitamente auditado) persistiu
  no PostgreSQL o HMAC de CPF e um `email_autenticacao` entregável. O login
  normal não consulta MySQL em tempo de requisição.

A API em perfil `production` falha na inicialização quando o último requisito
de ALFA não é atendido. `/api/readiness` também consulta o banco e revalida esse
estado; `/api/health` mede somente o processo.

## 2. HTTPS, cookies, OTP e passkeys

- [ ] O proxy termina HTTPS e publica PWA e `/api` na mesma origem.
- [ ] `cortex-web` fica em `127.0.0.1` por padrão; qualquer override de
  `CORTEX_WEB_BIND_ADDRESS` pertence somente à rede privada do ingresso TLS.
- [ ] `CORTEX_PUBLIC_ORIGIN`, CORS e WebAuthn contêm somente a origem HTTPS
  exata, sem curinga, path, query ou credenciais.
- [ ] `CORTEX_AUTH_WEBAUTHN_RP_ID` é o hostname público sem esquema/porta.
- [ ] Cookies estão `Secure`; `SameSite` foi escolhido para a topologia real.
- [ ] O par PEM do offline grant está montado e o fingerprint público usado no
  build da PWA corresponde exatamente a esse par.
- [ ] CPF válido, CPF inválido e CPF sem identidade recebem a mesma resposta
  pública de solicitação; nenhum deles cria sessão somente pelo CPF.
- [ ] O OTP chega ao `auth_identity.email_autenticacao` canônico, é de uso
  único, expira e só então emite cookie opaco + CSRF no hostname final.
- [ ] As rotas públicas de OTP aceitam no máximo 4 KiB tanto no proxy quanto
  antes do MVC (inclusive em corpo chunked), e a verificação aplica orçamento
  de tentativas por origem e global antes de consultar o desafio armazenado.
- [ ] Login por passkey foi exercitado como alternativa online explícita; o
  endpoint legado de CPF direto permanece inacessível fora de local/teste.
- [ ] Depois de login online e registro de passkey com PRF, a mesma identidade
  abre o cofre offline sem rede. CPF, PIN, e-mail e OTP não desbloqueiam o
  cofre; uma identidade sem grant/passkey continua bloqueada.

## 3. Secrets e providers

- [ ] CPF HMAC, OTP HMAC, chave privada offline e senha SMTP vêm de arquivos
  secretos montados; os equivalentes inline estão vazios.
- [ ] As URLs, usuários e arquivos de senha de Academy/Zeladoria são distintos
  do PostgreSQL canônico, montados por Config Tree, e os usuários das fontes
  têm somente `SELECT` no schema explicitamente autorizado.
- [ ] `CORTEX_AUTH_DEV_ADMIN_ENABLED=false` e
  `CORTEX_AUTH_PROVISIONING_ENABLED=false` no processo web.
- [ ] `CORTEX_EMAIL_PROVIDER=smtp`; provider `fake` não existe no runtime de
  produção.
- [ ] From, usuário, host, porta, STARTTLS e
  `CORTEX_EMAIL_SENDER_PROFILE_KEY` correspondem à caixa Stavias autenticada;
  existe um e-mail institucional verificável para cada usuário que fará login
  por OTP.
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
  depois de habilitado, os resultados aparecem em `source_sync_run` no
  PostgreSQL. O replay offline da PWA permanece automático e separado desse
  scheduler.

## 6. Build e testes da revisão

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test

cd ../web
npm test -- --run
npm run lint
npm run build

cd ../..
docker build -t cortex-api:release apps/api
docker build -t cortex-web:release apps/web
./scripts/security/test-local-compose-security.sh
docker compose -f compose.production.example.yml config
git diff --check
```

- [ ] Maven completo passou em JDK 21.
- [ ] PostgreSQL 18 descartável passou com migrations V44–V60 e os fluxos
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
  se a versão anterior tolerar as tabelas aditivas V45–V60.
- Nunca use `flyway repair` para mascarar checksum divergente.

## Limite de evidência externa

Build e fake SMTP comprovam contrato, segurança e idempotência; não comprovam
entrega pela caixa Stavias real. S3/SMTP/Graph só podem ser declarados validados
depois de um smoke no ambiente com credenciais próprias. Sem essas credenciais,
o handoff deve registrar essa dependência como não verificada.
