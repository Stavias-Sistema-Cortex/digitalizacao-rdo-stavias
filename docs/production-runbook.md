# Runbook de produção — Córtex

## Topologia suportada

A PWA e a API devem ser publicadas na mesma origem HTTPS. O container web serve
os assets e encaminha `/api/*` para `cortex-api:8080`; isso mantém cookies de
sessão e CSRF host-only. O banco canônico é PostgreSQL `StaviasCortex`.
Academy e Zeladoria permanecem somente como fontes MySQL de leitura para
bootstrap/sync explicitamente configurados. Elas nunca participam de uma
requisição de autenticação do navegador. Objetos ficam em bucket S3 privado ou
no volume persistente `cortex_object_data`.

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
Zeladoria exigem URLs, usuários e arquivos de senha próprios, explicitamente
verificados antes de habilitar sync; os respectivos usuários no banco de origem
devem ter somente `SELECT` no schema autorizado. Sem essa configuração, não se
deve afirmar que o sync de fontes funciona. A API grava exclusivamente no
PostgreSQL canônico.

Por padrão, `cortex-web` publica `127.0.0.1:8080`, nunca HTTP em todas as
interfaces. O ingresso HTTPS gerenciado deve encaminhar essa porta ou definir
`CORTEX_WEB_BIND_ADDRESS` apenas na sua rede privada e confiável. PWA e API
continuam na mesma origem HTTPS externa.

## Modelo de entrada

No runtime `postgresql`, o CPF é somente um identificador: ele localiza a
identidade Academy canônica já persistida em PostgreSQL e emite a sessão opaca
existente. Passkey permanece como alternativa online. Por política explícita de
dispositivo colaborativo, esse endpoint não tem rate limit da aplicação nem
retorna `429`; qualquer proteção de borda deve ser adotada e validada no
ingresso. O runtime normal não consulta Academy ou Zeladoria durante essa
autenticação e não carrega configuração de OTP; e-mail/OTP pertence somente à
ativação explícita `postgresql-activation`.

O template normal `.env.example` não contém variáveis de OTP ou SMTP. A
ativação deve receber seu ambiente próprio diretamente do gerenciador de
segredos/orquestrador ao iniciar `start-postgres-activation.sh`; esse ambiente
não pode ser compartilhado com `run-api.sh`, `run-compose.sh` ou
`run-api-docker.sh`.

O acesso offline é uma fronteira diferente. Um grant colaborativo assinado pode
ser localizado e validado somente pelo CPF correspondente, enquanto o cofre PRF
exige uma passkey previamente registrada. Nenhum dos dois cria autorização de
API; PIN, e-mail e OTP não são fallbacks offline.

## Preparação de chaves

- CPF HMAC: material aleatório com pelo menos 32 bytes no runtime normal.
- OTP HMAC: material independente, montado somente no deployment explícito de
  ativação.
- Offline grant: chave privada RSA PKCS#8 e chave pública SubjectPublicKeyInfo
  em PEM. Monte ambas na API.
- Calcule o SHA-256 base64url sem padding do DER da chave pública e forneça em
  `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` no build web.
- Rotacione CPF HMAC usando `previous-key-*` durante a janela de transição; não
  reutilize a chave OTP, SMTP ou offline.

## Cutover

1. Restaure uma cópia de `StaviasCortex` e ensaie a atualização Flyway até a
   versão exigida pela release.
2. Confirme um ALFA ativo e, para cada usuário QA, uma identidade canônica ativa
   com HMAC de CPF, persistida pelo bootstrap/sync autorizado.
3. Configure storage persistente, `CORTEX_PUBLIC_ORIGIN` HTTPS exata, passkeys e
   todos os secrets do runtime normal por arquivo. OTP é configurado somente ao
   executar a ativação explícita; o SMTP usado por essa transição também fica
   somente nesse processo. O runtime normal `production,postgresql` não carrega
   `EmailGateway`, SMTP nem o scheduler legado de cobranças. Nunca copie uma
   senha para `.env` nem para uma variável de ambiente.
4. Mantenha `CORTEX_POSTGRES_RUNTIME_READY=false` até concluir Flyway, o
   bootstrap ALFA e o preflight; então altere-o para `true` no ambiente de
   publicação.
5. Execute `bash scripts/security/test-local-compose-security.sh` e
   `docker compose --env-file .env.production -f compose.production.example.yml config`.
   O primeiro comando usa arquivos temporários sem conteúdo real e verifica o
   contrato de secrets, fontes e porta loopback.
6. Inicie com `CORTEX_SYNC_ACADEMY_ENABLED=false` e
   `CORTEX_SYNC_ZELADORIA_ENABLED=false`, aguarde `/api/readiness`, faça login
   direto por CPF canônico, valide a passkey como alternativa e exercite
   separadamente o grant colaborativo e uma passkey PRF registrada.
7. Inicie a PWA e execute `scripts/smoke-deploy.sh` na origem HTTPS final.
8. Depois de configurar e validar explicitamente as URLs, usuários
   `SELECT`-only, arquivos de senha e uma importação QA, habilite somente a
   flag da fonte validada: `CORTEX_SYNC_ACADEMY_ENABLED=true` ou
   `CORTEX_SYNC_ZELADORIA_ENABLED=true`. Para Academy, confirme que a role
   possui `SELECT` somente em `usuarios`, `grupos` e `perfil`; execute o teste
   de conexão e compare apenas contagens agregadas, sem CPF, e-mail ou nome.
   Antes de habilitar, registre uma execução QA recente e bem-sucedida. Com
   Academy ativa, `/api/readiness` exige que o último
   `source_sync_run.connector_name=acad_colaborador_import` esteja `SUCCESS`,
   tenha `finished_at` e não seja mais antigo que
   `CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS` (padrão `900000`). Com Academy
   desativada, esse requisito de frescor não é aplicado.

   Execute o gate agregado, sem imprimir dados pessoais, a partir da raiz do
   checkout:

   ```bash
   bash -lc '
     source scripts/dev/load-local-env.sh
     export CORTEX_ACADEMY_QA_ENABLED=true
     cd apps/api
     exec ./mvnw -q -Dtest=AcademyLiveAggregateCoverageIT test
   '
   ```

   Docker é obrigatório. O gate lê a Academy somente em `SELECT`, grava apenas
   em um PostgreSQL 18 descartável e falha se a conta tiver qualquer
   privilégio além de `USAGE` e `SELECT` individual nas três tabelas.

As flags separadas controlam somente os pulls programados e não substituem as
credenciais read-only verificadas. A Academy exige senha montada em arquivo e,
em produção, URL JDBC MySQL com `sslMode=VERIFY_IDENTITY`. Para o servidor
legado sem identidade de hostname, a única exceção é `VERIFY_CA` com o PKCS12
de um único certificado folha montado exatamente em
`/etc/secrets/cortex-academy-truststore.p12`, tipo `PKCS12` e fallback ao
truststore do sistema desativado. As flags não desligam o replay da outbox
offline da PWA. Esse replay é solicitado em escrita local,
abertura, reconexão, retorno ao foreground, mudança de sessão e timers somente
enquanto a aplicação executa, está online e possui sessão online ativa; não há
garantia universal de background sync com navegador ou PWA fechados.

## Observabilidade operacional

Alertar para:

- readiness diferente de `READY`;
- aumento de 401/403 fora de um deploy ou revogação esperada;
- mutações de sync em erro/retry por mais de uma janela;
- falhas de storage e diferença entre metadata e objeto;
- ausência de cobertura/frescor na Memória, no grafo ou no rastreio de receita.

Logs devem carregar correlation ID, entidade e resultado, nunca CPF, OTP,
cookie, segredo, corpo de mensagem ou anexo.

## Incidentes

### Banco indisponível

Retire a instância do balanceador quando readiness falhar. Não desative Flyway
nem o gate de ALFA. Recupere o banco e confirme a fila idempotente antes de
recolocar tráfego.

### Storage indisponível

Bloqueie novos uploads, preserve a metadata e restaure o backend/volume. Nunca
marque objeto como concluído sem confirmar conteúdo e SHA-256.

### Revogação de acesso

Revogue a sessão/vínculo/grant no servidor. O backend bloqueia online de forma
imediata; pendências locais permanecem visíveis para resolução e não são
apagadas silenciosamente.
