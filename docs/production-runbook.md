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

No runtime `postgresql`, o CPF é somente o identificador: ele localiza a
identidade Academy canônica já persistida em PostgreSQL, e a senha individual
é verificada por hash Argon2id no banco canônico. O primeiro acesso e a
redefinição usam um código de oito dígitos emitido por um ALFA autorizado,
válido por 30 minutos e no máximo cinco tentativas. O código é mostrado uma vez
ao administrador; o banco guarda somente seu HMAC. Não é criado usuário MySQL
por colaborador. Passkey permanece como alternativa online.

Login e definição de senha compartilham rate limit persistido: por padrão, 250
requisições por origem e 600 globais a cada 15 minutos. Isso comporta uma
mobilização atrás do mesmo roteador sem retirar o teto contra abuso; ajuste
somente por `CORTEX_AUTH_LOGIN_RATE_LIMIT_MAX_REQUESTS` e
`CORTEX_AUTH_LOGIN_GLOBAL_RATE_LIMIT_MAX_REQUESTS`. O runtime normal não
consulta Academy ou Zeladoria durante a autenticação e não carrega configuração
de OTP; e-mail/OTP pertence somente à ativação explícita
`postgresql-activation`.

O template normal `.env.example` não contém variáveis de OTP ou SMTP. A
ativação deve receber seu ambiente próprio diretamente do gerenciador de
segredos/orquestrador ao iniciar `start-postgres-activation.sh`; esse ambiente
não pode ser compartilhado com `run-api.sh`, `run-compose.sh` ou
`run-api-docker.sh`.

O acesso offline é uma fronteira diferente. Enquanto o navegador alcança a VM
pela LAN, ele usa o login online normal. Depois de um login online por CPF e
senha, o perfil desse navegador recebe um cofre colaborativo v3 cifrado que o
mesmo CPF e senha podem abrir sem alcançar a API por até sete dias
(`CORTEX_AUTH_OFFLINE_GRANT_TTL_SECONDS=604800`). A passkey/PRF permanece como
alternativa independente. Nenhum dos dois cria autorização de API; PIN, e-mail
e OTP não são fallbacks offline.

A senha, o hash do servidor e o grant assinado não são persistidos em claro no
registro v3. Grants v2 existentes, limitados a 24 horas, permanecem fisicamente
preservados para rollback, mas não são apresentados como v3 pronto, ampliados
ou renovados. O login online com senha confirma a gravação v3 antes de
substituir o v2 correspondente.

## Preparação de chaves

- CPF HMAC: material aleatório com pelo menos 32 bytes no runtime normal.
- Password setup HMAC: material aleatório independente, com pelo menos 32
  bytes, montado em `CORTEX_AUTH_PASSWORD_SETUP_HMAC_KEY_FILE`.
- OTP HMAC: material independente, montado somente no deployment explícito de
  ativação.
- Offline grant: chave privada RSA PKCS#8 e chave pública SubjectPublicKeyInfo
  em PEM. Monte ambas na API.
- Calcule o SHA-256 base64url sem padding do DER da chave pública e forneça em
  `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` no build web.
- Rotacione CPF HMAC usando `previous-key-*` durante a janela de transição; não
  reutilize a chave de código temporário, OTP, SMTP ou offline.

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
6. Confirme `CORTEX_AUTH_OFFLINE_GRANT_TTL_SECONDS=604800` no runtime e inicie
   com `CORTEX_SYNC_ACADEMY_ENABLED=false` e
   `CORTEX_SYNC_ZELADORIA_ENABLED=false`, aguarde `/api/readiness`, faça login
   por CPF e senha individual, valide a emissão e o consumo de um código
   temporário, valide a passkey como alternativa e exercite
   separadamente o cofre colaborativo v3 por CPF e senha, inclusive fechamento
   e reabertura sem API, e uma passkey PRF registrada.
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
de uma única âncora X.509 aprovada — o certificado folha ou, quando o servidor
envia uma cadeia legada, a CA privada que a assina — montado exatamente em
`/etc/secrets/cortex-academy-truststore.p12`, tipo `PKCS12` e fallback ao
truststore do sistema desativado. As flags não desligam o replay da outbox
offline da PWA. Esse replay é solicitado em escrita local,
abertura, reconexão, retorno ao foreground, mudança de sessão e timers somente
enquanto a aplicação executa, está online e possui sessão online ativa; não há
garantia universal de background sync com navegador ou PWA fechados.

## Releases depois do corte local

O workflow `production.yml` testa a revisão de `develop`, publica as imagens
multi-arquitetura da API e da PWA no GHCR e atesta os respectivos digests. Ele
não migra nem implanta nenhum provedor hospedado. Ao final, gera o artefato
`cortex-local-release-<sha>` com um JSON de handoff também atestado. Os antigos
serviços hospedados permanecem preservados como rollback e não são consultados
nem alterados por esse workflow; manter esses recursos não os torna parte do
caminho de publicação local. Uma rotina hospedada já provisionada pode continuar
existindo até uma aposentadoria remota, posterior e explicitamente aprovada.

Baixe o artefato de um run verde exato em um diretório protegido e verifique a
atestação antes de aceitar as coordenadas:

```bash
umask 077
mkdir -p /caminho/protegido/release
gh run download RUN_ID \
  --name "cortex-local-release-SHA_COMPLETO" \
  --dir /caminho/protegido/release

CORTEX_LOCAL_RELEASE_HANDOFF=/caminho/protegido/release/cortex-local-release-handoff.json \
CORTEX_LOCAL_RELEASE_ENV_OUTPUT=/caminho/protegido/release/release.env \
CORTEX_EXPECTED_SOURCE_REPOSITORY=Stavias-Sistema-Cortex/digitalizacao-rdo-stavias \
  bash scripts/deploy/verify-local-release-handoff.sh
```

O verificador exige a proveniência do GitHub, confere SHA, repositório, digests
imutáveis e o marcador determinístico do banco, e só então grava quatro
variáveis em `release.env` com modo `600`. Combine esse arquivo com a
configuração root-only do servidor e execute
`update-local-production-release.sh stage-web`; depois de confirmar que o novo
service worker controla o mesmo perfil, execute `activate`. O atualizador faz
backup, Flyway no PostgreSQL local, troca atômica, probes pelo Apache e rollback
fail-closed. Não copie credenciais hospedadas para o servidor e não execute os
scripts históricos de migração ou implantação hospedada nesse fluxo.

Os workflows de keep-warm permanecem manualmente visíveis apenas como registro
de aposentadoria. Eles não possuem agenda, gatilho de push, ping nem implantação
de worker. Isso não remove nem reconfigura um recurso hospedado já existente.

## Observabilidade operacional

Alertar para:

- readiness diferente de `READY`;
- aumento de 401/403 fora de um deploy ou revogação esperada;
- mutações de sync em erro/retry por mais de uma janela;
- falhas de storage e diferença entre metadata e objeto;
- ausência de cobertura/frescor na Memória, no grafo ou no rastreio de receita.

Logs devem carregar correlation ID, entidade e resultado, nunca CPF, OTP,
cookie, segredo, corpo de mensagem ou anexo.

### Evidência redigida antes do cutover local

Antes de atualizar imagens, restaurar banco ou trocar o upstream do Apache,
capture o estado exato do runtime atual. O diretório de evidências deve existir,
ter modo `700` e permanecer fora do checkout; o arquivo final será escrito
atomicamente com modo `600`.

```bash
CORTEX_BASE_URL=https://cortex.portalstavias.com.br \
CORTEX_EXPECTED_RELEASE_SHA="$(git rev-parse HEAD)" \
CORTEX_CUTOVER_EVIDENCE_FILE=/caminho/protegido/pre-cutover.json \
CORTEX_COMPOSE_FILE=/caminho/protegido/compose.yml \
CORTEX_COMPOSE_ENV_FILE=/caminho/protegido/production.env \
CORTEX_COMPOSE_PROJECT_NAME=cortex-production \
  bash scripts/deploy/capture-local-cutover-state.sh
```

O comando é somente leitura. Ele exige API, PWA, banco e object storage na
mesma revisão esperada, confere os containers reais e grava somente SHA,
saúde, IDs de imagem, contagem de reinícios, nomes de volumes e a classificação
redigida do destino PostgreSQL (`LOCAL`, `NEON` ou `OTHER`). URL JDBC,
hostname, usuário, senha e demais variáveis do container nunca entram no
arquivo. Uma revisão diferente ou readiness incompleta interrompe o processo;
não se deve contornar esse bloqueio para prosseguir com a migração.

### Cópia verificada de R2 para o volume local

A migração de objetos é uma execução isolada e copy-only. A credencial da
origem deve ter somente as permissões equivalentes a listar/ler o bucket e
nunca permissão de exclusão. O código recebe a origem por uma interface que só
expõe leitura: não existe operação de escrita ou remoção de R2 no processo.

Depois de preparar o ensaio e antes de qualquer troca do Apache, execute:

```bash
docker compose \
  --env-file .runtime/production-rehearsal/production.env \
  -f deploy/production/compose.yml \
  --profile object-migration \
  up --abort-on-container-exit \
  --exit-code-from cortex-object-migrate \
  cortex-object-migrate
```

O serviço não publica porta, usa o PostgreSQL apenas para `SELECT`, lê R2 em
páginas, grava no volume `cortex_object_data` e reabre cada objeto para
conferir tamanho, media type e SHA-256. Objetos `LOCAL` já catalogados são
somente verificados no destino. Repetir o comando é seguro: um objeto já
íntegro não é regravado.

O resultado agregado fica em
`.runtime/production-rehearsal/evidence/object-storage-result.json`, modo
`600`, com contagens, bytes e digest do manifesto. Ele deliberadamente não
contém storage key, nome, CPF, proprietário, obra, endpoint ou credencial.
Qualquer `missing`, `mismatched` ou `failed` torna o processo não-zero e impede
o cutover. Não corrija a pendência apagando a origem; R2 continua intacto como
rollback até uma aprovação posterior e separada.

### Corte atômico do Apache e rollback local

O VirtualHost HTTPS deve carregar um único include por symlink, por exemplo
`/etc/apache2/cortex-runtime.conf`. Guarde os alvos reais em um diretório
root-only (`700`) e os arquivos em modo `600`. O alvo atual continua apontando
para o canário até a execução aprovada. O servidor deve resolver apenas
localmente `cortex.portalstavias.com.br` para `127.0.0.1`; isso permite que o
Apache valide o certificado interno do Caddy no upstream `:18443` sem desligar
verificação TLS e não altera o DNS público.

O include de manutenção deve produzir HTTP 503 com `Retry-After`, sem redirecionar
para Render ou Cloudflare. O include candidato deve usar `SSLProxyVerify require`,
`SSLProxyCheckPeerName on`, a CA privada gerada em
`.runtime/production/caddy-local-root.crt`, `ProxyPreserveHost On` e
`ProxyPass`/`ProxyPassReverse` para
`https://cortex.portalstavias.com.br:18443/`. Execute `apache2ctl configtest`
depois de criar cada arquivo e antes do corte.

Com os arquivos protegidos prontos, a chamada é deliberadamente explícita:

```bash
sudo env \
  CORTEX_CUTOVER_APPROVED=true \
  CORTEX_REMOTE_RETENTION=preserve \
  CORTEX_EXPECTED_RELEASE_SHA="$(git rev-parse HEAD)" \
  CORTEX_PRE_CUTOVER_EVIDENCE_FILE=/srv/cortex/evidence/pre-cutover.json \
  CORTEX_DATABASE_COPY_EVIDENCE_FILE=/srv/cortex/runtime/evidence/database-copy-result.json \
  CORTEX_OBJECT_COPY_EVIDENCE_FILE=/srv/cortex/runtime/evidence/object-storage-result.json \
  CORTEX_CUTOVER_EVIDENCE_FILE=/srv/cortex/evidence/cutover.json \
  CORTEX_ROLLBACK_EVIDENCE_FILE=/srv/cortex/evidence/rollback.json \
  CORTEX_APACHE_CONFIG_LINK=/etc/apache2/cortex-runtime.conf \
  CORTEX_APACHE_MAINTENANCE_CONFIG=/srv/cortex/apache/maintenance.conf \
  CORTEX_APACHE_CANDIDATE_CONFIG=/srv/cortex/apache/local-production.conf \
  CORTEX_APACHE_BACKUP_DIR=/srv/cortex/apache/backups \
  CORTEX_CANDIDATE_BASE_URL=https://cortex.portalstavias.com.br:18443 \
  CORTEX_PUBLIC_BASE_URL=https://cortex.portalstavias.com.br \
  CORTEX_PREPARE_LOCAL_PRODUCTION_BIN="$PWD/scripts/deploy/prepare-local-production.sh" \
  CORTEX_APACHECTL_BIN=/usr/sbin/apache2ctl \
  CORTEX_DOCKER_BIN=/usr/bin/docker \
  CORTEX_CURL_BIN=/usr/bin/curl \
  CORTEX_COMPOSE_FILE="$PWD/deploy/production/compose.yml" \
  CORTEX_COMPOSE_ENV_FILE=/srv/cortex/runtime/production.env \
  CORTEX_COMPOSE_PROJECT_NAME=cortex-production \
  CORTEX_PRODUCTION_RUNTIME_DIR=/srv/cortex/runtime \
  bash scripts/deploy/cutover-local-production.sh
```

O comando muda primeiro para manutenção, tira o dump final sob snapshot,
restaura e confere todas as contagens, copia o delta de objetos, exige zero
pendências de hash, valida o candidato diretamente em loopback, troca o symlink
atomicamente e só então valida a origem pública. Qualquer falha após a
manutenção restaura e recarrega o include anterior e apenas executa `compose
stop`; volumes locais, Neon, Render, Cloudflare Pages e R2 permanecem intactos.

O rollback manual usa o estado root-only criado pelo corte e é idempotente:

```bash
sudo --preserve-env=CORTEX_REMOTE_RETENTION,CORTEX_EXPECTED_RELEASE_SHA,\
CORTEX_ROLLBACK_EVIDENCE_FILE,CORTEX_APACHE_CONFIG_LINK,\
CORTEX_APACHE_BACKUP_DIR,CORTEX_APACHECTL_BIN,CORTEX_DOCKER_BIN,\
CORTEX_COMPOSE_FILE,CORTEX_COMPOSE_ENV_FILE,CORTEX_COMPOSE_PROJECT_NAME \
  bash scripts/deploy/rollback-local-production.sh
```

Ele restaura o alvo exato anterior, exige `configtest`, recarrega o Apache e
para os containers sem `down` e sem `-v`. O estado e as evidências não carregam
URL JDBC, nomes de objeto ou segredos.

### Backup diário e restore drill

Não aceite o disco do próprio Docker como backup. O destino deve estar montado
em outro device e protegido por modo `700`; o script compara o device do
destino com os mountpoints reais dos volumes PostgreSQL e de objetos. O job
diário recebe apenas o recipient público de uma identidade `age` guardada fora
do servidor.

`backup-local-production.sh` descobre os nomes e image IDs pelos containers em
execução, exige API saudável na revisão informada e cria:

- dump PostgreSQL custom-format cifrado;
- tar integral do volume de objetos cifrado;
- manifesto detalhado de SHA-256 cifrado;
- manifesto redigido `600`, gravado por último.

A retenção mínima é dois e só roda depois de o novo conjunto estar completo.
Nunca inclui arquivos secretos nem executa remoção em Neon, Render, Cloudflare
ou R2. Configure o comando e o timer conforme
`deploy/production/README.md`.

O restore drill é parte do aceite, não mera inspeção do tar. Com a identidade
privada temporariamente presente, `verify-local-production-backup.sh` confere a
autenticação `age`, os hashes e a composição exata do arquivo, sobe a mesma
imagem PostgreSQL em `--network none`, restaura o dump, calcula um digest das
contagens de todas as tabelas públicas e apaga somente o container, o volume e
o plaintext descartáveis. Preserve o `.restore.json` junto ao backup e remova a
identidade do servidor ao terminar.

## Relógio da operação

O relógio civil do Córtex é o de Brasília (`America/Sao_Paulo`), e a imagem da
API já sobe com ele fixado (`ENV TZ` + `-Duser.timezone`). Os carimbos civis —
memória operacional de geração 1, `atualizado_em`, vigências de geometria e o
`CURRENT_TIMESTAMP` das sessões JDBC — são gravados sem fuso, e todos os
leitores (a interpretação `AT TIME ZONE 'America/Sao_Paulo'` da Memória e a
preservação de dígitos na PWA) assumem que nasceram nesse relógio. Não rode o
contêiner da API com o fuso zerado: em UTC, cada tela civil passa a mostrar um
horário três horas adiantado. Eventos gravados enquanto o servidor local rodou
em UTC permanecem com esse desvio no histórico; os novos saem certos a partir
da release com o fuso fixado.

## Acesso humano às tabelas

O PostgreSQL canônico não publica porta; a inspeção humana das tabelas usa
uma ponte de loopback no servidor (`scripts/deploy/db-view-bridge.sh`),
acessada por túnel SSH e pgAdmin/DBeaver. São dois roles distintos:

- `cortex_readonly` — `SELECT`-only, é o acesso padrão;
- `cortex_editor` — correção manual de dados sob autorização explícita,
  provisionado por `scripts/deploy/db-editor-role.sh`. Ele altera linhas,
  mas não o schema, e não escreve no histórico Flyway nem no marcador de
  release.

O procedimento completo dos dois — preparação, ponte, passo a passo para
colaboradores e as práticas obrigatórias ao editar — está em
[`docs/operations/visualizar-tabelas-producao.md`](operations/visualizar-tabelas-producao.md).
Nunca publique 5432 externamente nem use `cortex_admin`, `cortex_migrator`
ou `cortex_runtime` em ferramenta gráfica.

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
