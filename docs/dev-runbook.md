# Córtex — runbook de desenvolvimento

## Pré-requisitos

- JDK 21 e Node 22;
- PostgreSQL 18 acessível com o banco canônico `StaviasCortex`;
- Docker Desktop apenas se a API/PWA forem executadas em containers;
- `.env` local ignorado pelo Git, criado a partir de `.env.example`;
- arquivos locais protegidos para senha PostgreSQL, HMAC de CPF, cursor de
  Memória e chaves offline. O HMAC de OTP é exigido somente na ativação
  explícita, não no runtime normal.

Academy e Zeladoria não são bancos do Córtex. Elas podem ser configuradas
somente como fontes MySQL externas de leitura e continuam desabilitadas por
padrão.

## Preparar o runtime PostgreSQL

Copie o modelo e preencha apenas URLs não secretas, IDs de chave e caminhos de
arquivos protegidos:

```bash
cp .env.example .env
```

O runtime normal usa `local,postgresql` e falha fechado sem schema V63, ALFA
real ativo e o gate explícito. A sequência completa é:

```bash
./scripts/dev/migrate-postgres-cortex.sh
# Execute o bootstrap somente com a identidade real autorizada e a fonte
# Academy somente leitura, conforme o runbook de clean start.
./scripts/dev/bootstrap-postgres-alfa.sh
CORTEX_POSTGRES_RUNTIME_READY=true \
  ./scripts/dev/check-postgres-runtime-release.sh
```

`start-postgres-activation.sh` não faz parte da entrada normal por CPF. Use-o
somente quando uma transição de ativação explicitamente autorizada exigir
e-mail/OTP no processo separado descrito abaixo.

Não crie ALFA, pessoa, obra, equipe, RDO, serviço, preço ou receita sintéticos
para fazer o runtime parecer pronto. Consulte
[cortex-postgresql-clean-start.md](operations/cortex-postgresql-clean-start.md)
para a transição e o rollback.

### Ambiente separado de ativação

`.env.example` descreve somente o runtime normal e, por isso, não contém OTP,
SMTP nem entrega financeira legada. Quando a transição de ativação for
necessária, forneça `CORTEX_AUTH_OTP_HMAC_KEY_FILE`, `CORTEX_EMAIL_PROVIDER`,
`CORTEX_SMTP_HOST`, `CORTEX_SMTP_USERNAME`, `CORTEX_SMTP_FROM` e
`CORTEX_SMTP_PASSWORD_FILE` somente ao processo
`start-postgres-activation.sh`, junto das variáveis PostgreSQL e da origem
pública exigidas pelo script. Não grave essas variáveis em `.env` ou
`.env.local`, pois esses arquivos são carregados pelos launchers normais.

## Stack Docker local

Depois de concluir os gates acima e definir
`CORTEX_POSTGRES_RUNTIME_READY=true`, execute. As portas ficam sempre em
loopback e podem ser escolhidas sem colidir com outro checkout:

```bash
CORTEX_WEB_PORT=15173 CORTEX_API_PORT=18081 \
  ./scripts/dev/run-compose.sh
```

O Compose não contém um MySQL primário e não injeta a senha PostgreSQL no
ambiente do container. `CORTEX_POSTGRES_DOCKER_URL` deve apontar para a mesma
instância canônica, por exemplo via `host.docker.internal` no macOS. O helper
valida somente os secrets do runtime normal; o HMAC de OTP pertence ao processo
separado de ativação.

- PWA: `http://localhost:${CORTEX_WEB_PORT:-5173}`
- API: `http://127.0.0.1:${CORTEX_API_PORT:-8081}`
- health pela mesma origem da PWA:
  `http://localhost:${CORTEX_WEB_PORT:-5173}/api/health`
- health direto da API:
  `http://127.0.0.1:${CORTEX_API_PORT:-8081}/api/health`
- readiness direto da API:
  `http://127.0.0.1:${CORTEX_API_PORT:-8081}/api/readiness`

```bash
docker compose -f compose.local.yml logs -f cortex-api cortex-web
./scripts/dev/stop-compose.sh
```

## Mapa da rodovia

O acompanhamento geoespacial da obra funciona sem credencial nenhuma. O padrão
é MapLibre GL sobre a malha vetorial aberta do OpenFreeMap, com extrusão de
edificações na visão 3D, e Leaflet sobre OpenStreetMap no painel ao lado.

`VITE_MAP_PROVIDER=maplibre` é o valor de fábrica; `VITE_MAPLIBRE_STYLE_URL`
aponta para um estilo próprio quando houver um. Imagem de satélite proprietária
é opcional: preencha `VITE_MAPTILER_API_KEY` ou `VITE_MAPBOX_ACCESS_TOKEN` no
`.env`, escolha o provider correspondente e reinicie. Sem a credencial, o
provider pago não é oferecido como pronto e a malha aberta continua ativa.

As chaves são tokens públicos de frontend e devem ser restringidas por origem
no painel do provider. Tiles já exibidos ficam disponíveis offline por sete
dias; tile nunca baixado permanece explicitamente indisponível.

Para conferir a composição num navegador real, sem banco nem sessão:

```bash
cd apps/web && node scripts/verify-mapa-rodovia-geometry.mjs
```

O script mede a geometria e captura PNGs em 1440×900, 1280×720 e 390×844 a
partir de fixtures de teste. Ele não usa e não produz dado operacional.

## Processos locais separados

Use dois terminais no mesmo worktree e escolha portas livres. O backend recebe
a porta web exata para derivar CORS e a origem WebAuthn; o frontend embute
somente `/api` e usa `CORTEX_API_TARGET` exclusivamente no proxy Vite:

```bash
export CORTEX_API_PORT=18081
export CORTEX_WEB_PORT=15173
PORT="$CORTEX_API_PORT" ./scripts/dev/run-api.sh

cd apps/web
npm ci
VITE_CORTEX_AUTH_MODE=postgresql \
VITE_CORTEX_API_BASE_URL=/api \
CORTEX_API_TARGET="http://127.0.0.1:$CORTEX_API_PORT" \
  npm exec vite -- --host localhost --port "$CORTEX_WEB_PORT" --strictPort
```

Acesse exatamente `http://localhost:$CORTEX_WEB_PORT` e valide
`curl -fsS "http://localhost:$CORTEX_WEB_PORT/api/health"`. Uma porta
`localhost` diferente é outra origem de navegador e, portanto, outra sessão,
cookie, CSRF e origem WebAuthn. Não reutilize uma aba aberta por outro checkout.

No runtime normal `postgresql`, o CPF resolve somente a identidade canônica já
persistida em `StaviasCortex` e emite a sessão opaca; passkey continua como
alternativa. Por decisão da operação colaborativa, esse endpoint não aplica um
rate limit da aplicação nem emite `429`; uma proteção de borda, se necessária,
deve ser configurada fora do contrato de login. Não há consulta MySQL ao Academy
ou à Zeladoria durante uma autenticação do navegador e não há requisito de
segredo OTP. A ativação por e-mail/OTP continua uma transição separada e
explícita por `start-postgres-activation.sh`.

## Dados externos e importação

`CORTEX_IMPORT_ENABLED=false` é o padrão. Bootstrap e sincronização de
Academy/Zeladoria só funcionam depois de configurar e verificar explicitamente
URL, usuário com `SELECT` apenas no schema autorizado e arquivo de senha próprio
para cada fonte. As flags `CORTEX_SYNC_ACADEMY_ENABLED` e
`CORTEX_SYNC_ZELADORIA_ENABLED` são independentes e não criam essas credenciais
nem comprovam que a sincronização funciona. Em produção, a Academy exige
`CORTEX_ACADEMY_DB_PASSWORD_FILE` e URL JDBC MySQL com
`sslMode=VERIFY_IDENTITY`; senha inline é exclusiva dos perfis local/test.
Quando o scheduler Academy está ativo, readiness exige que a execução mais
recente de `acad_colaborador_import` esteja `SUCCESS`, finalizada e dentro de
`CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS` (padrão `900000`, três intervalos
de cinco minutos). Com o scheduler desligado, esse gate não consulta
`source_sync_run`.
Nunca copie credenciais das fontes para Compose, documentação, logs ou fixtures.

## Testes

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw -Ppostgresql-it verify

cd ../web
npm test -- --run
npm run lint
npm run build
```

Os testes PostgreSQL usam containers descartáveis e não consultam nem alteram
o banco local do operador.

## Offline e sincronização

- IDs e `clientMutationId` permanecem estáveis no IndexedDB.
- Recarregar offline não autoriza API; somente um grant assinado abre o cofre
  local.
- O CPF offline apenas localiza e valida o grant colaborativo assinado; ele não
  autoriza API. PIN, e-mail e OTP não desbloqueiam o cache. Para o cofre PRF,
  registre uma passkey enquanto houver conexão.
- Com o app aberto, online e com sessão online ativa, o replay idempotente é
  solicitado após escrita local, abertura/montagem, reconexão, retorno da aba ao
  primeiro plano, mudança de sessão, intervalo e retry agendado. Falhas e
  conflitos ficam visíveis, nunca são descartados silenciosamente.
- Uma PWA ou navegador fechados não recebem promessa universal de background
  sync; o replay retoma quando a aplicação volta a executar nas condições
  acima.
- Para diagnosticar, confirme `/api/health`, `/api/readiness`, sessão,
  escopo/capability, rota, recibo idempotente e evento ontológico.

## Financeiro

A superfície ativa contém somente:

- Rastreio de receita;
- Serviços e preços versionados;
- PDOR de receita.

Receita é calculada a partir da execução aceita no RDO e do preço aplicável
persistido. Não semeie custos, margens, compras, rateios ou gráficos
demonstrativos.

## Antes de commitar

```bash
git status --short
git diff --check
```

Não versione `.env*`, arquivos de segredo, `target/`, `dist/`,
`node_modules/`, dumps ou dados pessoais.
