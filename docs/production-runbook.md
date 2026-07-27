# Runbook de produção — Córtex

## Topologia suportada

A PWA e a API devem ser publicadas na mesma origem HTTPS. O container web serve
os assets e encaminha `/api/*` para `cortex-api:8080`; isso mantém cookies de
sessão e CSRF host-only. O banco canônico é PostgreSQL `StaviasCortex`.
Academy e Zeladoria permanecem somente como fontes MySQL de leitura. Objetos
ficam em bucket S3 privado ou no volume persistente `cortex_object_data`.

`compose.production.example.yml` é um exemplo de topologia, não contém secrets
nem cria um banco de produção. Parta de `.env.postgresql.example`, mova os
valores para o secret manager do ambiente e aponte as entradas
`*_FILE` para arquivos locais de implantação com permissões restritas.
A senha PostgreSQL é montada como `CORTEX_POSTGRES_PASSWORD` sob
`/run/secrets` e carregada pelo Spring Config Tree; ela não deve ser injetada
como variável de ambiente. Para S3, use a cadeia padrão do AWS SDK com workload
identity/role da plataforma ou um arquivo de credenciais montado por override;
não publique `AWS_SECRET_ACCESS_KEY` no ambiente do container.

O processo não aceita `CORTEX_DB_*` como fallback do banco canônico: forneça
`CORTEX_POSTGRES_URL`, `CORTEX_POSTGRES_USER` e
`CORTEX_POSTGRES_PASSWORD_FILE` para o banco `StaviasCortex`. Academy e
Zeladoria exigem URLs, usuários e arquivos de senha próprios; os respectivos
usuários no banco de origem devem ter somente `SELECT` no schema autorizado. A
API grava exclusivamente no PostgreSQL canônico.

Por padrão, `cortex-web` publica `127.0.0.1:8080`, nunca HTTP em todas as
interfaces. O ingresso HTTPS gerenciado deve encaminhar essa porta ou definir
`CORTEX_WEB_BIND_ADDRESS` apenas na sua rede privada e confiável. PWA e API
continuam na mesma origem HTTPS externa.

## Modelo de entrada

No runtime `postgresql`, o CPF é somente um identificador: ele localiza a
identidade canônica já persistida em PostgreSQL e dispara um OTP para
`auth_identity.email_autenticacao`. A sessão só nasce após a confirmação desse
código de uso único ou por passkey, que aparece como alternativa online. O
runtime normal não consulta Academy ou Zeladoria durante essa autenticação;
essas fontes MySQL existem apenas para sincronização/bootstrap com permissões
de leitura.

O cofre offline é uma fronteira diferente: somente uma passkey previamente
registrada com PRF e um grant assinado podem abri-lo. CPF, PIN, e-mail e OTP não
são credenciais offline.

## Preparação de chaves

- CPF e OTP: materiais aleatórios independentes, pelo menos 32 bytes.
- Offline grant: chave privada RSA PKCS#8 e chave pública SubjectPublicKeyInfo
  em PEM. Monte ambas na API.
- Calcule o SHA-256 base64url sem padding do DER da chave pública e forneça em
  `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` no build web.
- Rotacione CPF HMAC usando `previous-key-*` durante a janela de transição; não
  reutilize a chave OTP, SMTP ou offline.

## Cutover

1. Restaure uma cópia de `StaviasCortex` e ensaie a atualização Flyway até a
   versão exigida pela release.
2. Confirme um ALFA ativo e, para cada usuário QA, uma identidade canônica com
   HMAC de CPF e `email_autenticacao` entregável. A primeira confirmação OTP
   pode ativar uma identidade `PENDENTE`; nunca crie uma sessão por CPF direto.
3. Configure SMTP real com STARTTLS, storage persistente,
   `CORTEX_PUBLIC_ORIGIN` HTTPS exata, passkeys e todos os secrets por arquivo.
   Nunca copie uma senha para `.env` nem para uma variável de ambiente.
4. Mantenha `CORTEX_POSTGRES_RUNTIME_READY=false` até concluir Flyway, o
   bootstrap ALFA e o preflight; então altere-o para `true` no ambiente de
   publicação.
5. Execute `bash scripts/security/test-local-compose-security.sh` e
   `docker compose --env-file .env.production -f compose.production.example.yml config`.
   O primeiro comando usa arquivos temporários sem conteúdo real e verifica o
   contrato de secrets, fontes e porta loopback.
6. Inicie com `CORTEX_SYNC_ENABLED=false`, aguarde `/api/readiness`, faça login
   por CPF + OTP, valide a passkey como alternativa e registre uma passkey PRF
   antes de validar o cofre offline depois de um login online real.
7. Inicie a PWA e execute `scripts/smoke-deploy.sh` na origem HTTPS final.
8. Depois de validar os usuários `SELECT`-only e uma importação QA, habilite
   `CORTEX_SYNC_ENABLED=true`; acompanhe `source_sync_run` no PostgreSQL antes
   de considerar a sincronização de Academy/Zeladoria automática em produção.

`CORTEX_SYNC_ENABLED` controla somente os pulls programados de Academy e
Zeladoria. Ele não desliga o replay automático da outbox offline da PWA, que
continua tentando mutações locais quando a conectividade retorna.

## Observabilidade operacional

Alertar para:

- readiness diferente de `READY`;
- aumento de 401/403 fora de um deploy ou revogação esperada;
- mutações de sync em erro/retry por mais de uma janela;
- cobranças `FALHA` ou leases vencidos;
- falhas de storage e diferença entre metadata e objeto;
- SMTP timeout/resultado ambíguo (não reenviar manualmente sem verificar o
  provider e a chave idempotente);
- ausência de cobertura/frescor na Memória, no grafo ou no rastreio de receita.

Logs devem carregar correlation ID, entidade e resultado, nunca CPF, OTP,
cookie, segredo, corpo de mensagem ou anexo.

## Incidentes

### Banco indisponível

Retire a instância do balanceador quando readiness falhar. Não desative Flyway
nem o gate de ALFA. Recupere o banco e confirme a fila idempotente antes de
recolocar tráfego.

### SMTP indisponível ou ambíguo

Pause `CORTEX_FINANCE_EMAIL_SCHEDULER_ENABLED`. Consulte o provider pelo ID de
tentativa antes de liberar retry; resultado ambíguo deve falhar fechado para não
duplicar cobrança.

### Storage indisponível

Bloqueie novos uploads, preserve a metadata e restaure o backend/volume. Nunca
marque objeto como concluído sem confirmar conteúdo e SHA-256.

### Revogação de acesso

Revogue a sessão/vínculo/grant no servidor. O backend bloqueia online de forma
imediata; pendências locais permanecem visíveis para resolução e não são
apagadas silenciosamente.
