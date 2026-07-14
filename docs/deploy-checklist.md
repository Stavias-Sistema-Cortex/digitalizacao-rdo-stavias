# Córtex — checklist de deploy

Esta é a trava operacional do artefato atual: API Java 21, PWA, autenticação
OTP/passkey, Mensagens, armazenamento compartilhado, Financeiro, cobranças por
e-mail e StavIA. Marque um item somente com evidência da mesma revisão que será
publicada.

## 1. Banco e migrações

- [ ] Backup restaurável do banco atual foi criado e testado.
- [ ] Um MySQL vazio aplicou Flyway V1–V33 sem `repair` ou edição de migration.
- [ ] Uma cópia representativa do banco atual atualizou até V33.
- [ ] O usuário da API tem somente os privilégios necessários no schema.
- [ ] Existe ao menos um `colaborador` ALFA ativo com `auth_identity` ATIVA e
  `email_verificado_em` preenchido.
- [ ] Os registros ALFA explícitos anteriores permanecem ALFA após a migração.

A API em perfil `production` falha na inicialização quando o último requisito
de ALFA não é atendido. `/api/readiness` também consulta o banco e revalida esse
estado; `/api/health` mede somente o processo.

## 2. HTTPS, cookies e passkeys

- [ ] O proxy termina HTTPS e publica PWA e `/api` na mesma origem.
- [ ] `CORTEX_PUBLIC_ORIGIN`, CORS e WebAuthn contêm somente a origem HTTPS
  exata, sem curinga, path, query ou credenciais.
- [ ] `CORTEX_AUTH_WEBAUTHN_RP_ID` é o hostname público sem esquema/porta.
- [ ] Cookies estão `Secure`; `SameSite` foi escolhido para a topologia real.
- [ ] O par PEM do offline grant está montado e o fingerprint público usado no
  build da PWA corresponde exatamente a esse par.
- [ ] Login OTP, logout, registro de passkey, login por passkey e acesso offline
  foram exercitados no hostname final.

## 3. Secrets e providers

- [ ] CPF HMAC, OTP HMAC, chave privada offline e senha SMTP vêm de arquivos
  secretos montados; os equivalentes inline estão vazios.
- [ ] `CORTEX_AUTH_DEV_ADMIN_ENABLED=false` e
  `CORTEX_AUTH_PROVISIONING_ENABLED=false` no processo web.
- [ ] `CORTEX_EMAIL_PROVIDER=smtp`; provider `fake` não existe no runtime de
  produção.
- [ ] From, usuário, host, STARTTLS e `CORTEX_EMAIL_SENDER_PROFILE_KEY`
  correspondem à caixa Stavias autenticada.
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
  exata, também recebe 403 e não recebe valores em Home, sync, export ou StavIA.
- [ ] Compra offline reconecta uma única vez pelo mesmo `clientMutationId`.
- [ ] Nota, anexo, pagamento/recebimento, relatório e CSV usam dados persistidos
  e o mesmo escopo da consulta.
- [ ] Cobrança manual/agendada/automática exige preview aprovado, remetente
  configurado e produz no máximo um envio por chave idempotente.
- [ ] Uma falha SMTP fica registrada com motivo sanitizado e retry limitado.
- [ ] StavIA retorna IDs/frescor de evidência e declara indisponibilidade quando
  o fato existe somente no dispositivo local.

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
docker compose -f compose.production.example.yml config
git diff --check
```

- [ ] Maven completo passou em JDK 21.
- [ ] MySQL de integração passou com migrations V1–V33.
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
5. Monitore login, 401/403, sync, fila de cobrança, SMTP, latência e storage.

## 8. Rollback

- Preserve a imagem anterior e o backup pré-migração.
- Não altere nem apague migrations aplicadas. Rollback de aplicação só é seguro
  se a versão anterior tolerar as tabelas aditivas V31–V33.
- Pause o scheduler de cobrança antes de rollback operacional para evitar dois
  consumidores de versões diferentes.
- Nunca use `flyway repair` para mascarar checksum divergente.

## Limite de evidência externa

Build e fake SMTP comprovam contrato, segurança e idempotência; não comprovam
entrega pela caixa Stavias real. S3/SMTP/Graph só podem ser declarados validados
depois de um smoke no ambiente com credenciais próprias. Sem essas credenciais,
o handoff deve registrar essa dependência como não verificada.
