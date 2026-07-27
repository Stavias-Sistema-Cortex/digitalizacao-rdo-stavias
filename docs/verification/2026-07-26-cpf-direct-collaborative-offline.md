# CPF direto, offline colaborativo e origem única — evidência

Data: 2026-07-26
Worktree: `feat/cortex-pdf-gate-execution`

## Contrato entregue

- O runtime web normal usa `POST /api/auth/login` para resolver no PostgreSQL
  canônico `StaviasCortex` a identidade Academy previamente espelhada. Não há
  lookup MySQL ao Academy/Zeladoria durante a autenticação do navegador.
- A sessão continua opaca em cookie, mutações continuam exigindo CSRF e
  WebAuthn continua validando RP ID e origem exata.
- Builds locais e de Compose embutem somente `VITE_CORTEX_API_BASE_URL=/api`.
  `CORTEX_API_TARGET` controla apenas o proxy Vite de desenvolvimento/preview.
- O Compose local publica em loopback e aceita `CORTEX_WEB_PORT` (5173 por
  padrão) e `CORTEX_API_PORT` (8081 por padrão). CORS e WebAuthn derivam a
  origem `http://localhost:<porta-web>`.
- O runtime normal PostgreSQL não exige segredo OTP. A ativação continua
  explícita, isolada e exige seu OTP HMAC no script dedicado.
- Grants colaborativos assinados e passkey PRF são mecanismos offline
  separados. Nenhum concede autorização de API.

## Replay e fontes externas

O replay automático da outbox é solicitado após escrita local, montagem/abertura,
reconexão, retorno ao foreground, mudança de sessão, intervalo e retry, somente
com a aplicação executando, online e com sessão online ativa. Não existe
garantia universal para uma PWA ou navegador fechados.

Academy e Zeladoria são fontes read-only de bootstrap/sync. O sync de fontes
não deve ser declarado funcional sem URL, usuário `SELECT`-only e arquivo de
senha explicitamente configurados e verificados para cada fonte.

## Evidência TDD

RED:

```text
npm --prefix apps/web test -- --run src/lib/api/apiClient.test.ts
1 arquivo; 13 testes; 2 falhas esperadas.
```

As falhas provaram que os builds ainda embutiam hosts absolutos, o Compose
fixava portas/origem e o runtime normal ainda exigia OTP.

```text
node apps/web/scripts/verify-stavia-boundary.mjs --source
exit 1: contratos de /api, portas, origem e ausência de OTP não atendidos.
```

GREEN focado:

```text
npm --prefix apps/web test -- --run src/lib/api/apiClient.test.ts
1 arquivo; 13 testes; 13 passaram.

node apps/web/scripts/verify-stavia-boundary.mjs --source
StavIA source boundary verified.
```

## Gates finais e smoke

### Web

```text
npm --prefix apps/web test -- --run
148 arquivos; 806 testes; 806 passaram.

npm --prefix apps/web run lint
exit 0.

VITE_CORTEX_AUTH_MODE=postgresql VITE_CORTEX_API_BASE_URL=/api \
  npm --prefix apps/web run build
exit 0; TypeScript/Vite/PWA concluídos; verifier do dist passou;
110 entradas no precache.
```

O `dist` não contém `127.0.0.1:8080/api` nem
`127.0.0.1:8081/api`.

### API/PostgreSQL

O wrapper Maven não existe na raiz do repositório. Os comandos equivalentes
foram executados no diretório `apps/api`, onde fica o wrapper canônico:

```text
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw clean test
1041 testes; 0 falhas; 0 erros; 54 ignorados; BUILD SUCCESS.

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -Ppostgresql-it -DforkCount=1 -DreuseForks=true verify
157 testes de integração; 0 falhas; 0 erros; 0 ignorados; BUILD SUCCESS.
```

O teste Java de contrato local exigia ainda as portas literais antigas. Com
aprovação explícita do coordenador, sua expectativa foi atualizada somente
para as interpolações de porta; o teste focado passou `5/5` e o gate integral
foi repetido com sucesso.

### Segurança e Compose

```text
./scripts/security/scan-cortex-secrets.sh
Cortex secret scan passed: no unreviewed literal candidates.

bash scripts/security/test-local-compose-security.sh
Local PostgreSQL/loopback, production secret-mount, source-read-only wiring,
and container hardening contracts passed.

node apps/web/scripts/verify-stavia-boundary.mjs --source
StavIA source boundary verified.

git diff --check
exit 0.
```

O `docker compose config`, com caminhos inertes e portas livres, produziu:

```text
API publicada em 127.0.0.1:18082
web publicada em 127.0.0.1:15174
CORS = http://localhost:15174
WebAuthn allowed origin = http://localhost:15174
nenhum CORTEX_AUTH_OTP_HMAC_KEY_FILE no runtime normal
```

### Smoke e dependências externas

As portas 15174/18082 estavam livres; 5173/8080 estavam ocupadas por processos
externos e não foram tocadas.

```text
PORT=18082 CORTEX_WEB_PORT=15174 ./scripts/dev/run-api.sh
exit 1: CORTEX_POSTGRES_URL must target
jdbc:postgresql://HOST[:PORT]/StaviasCortex.
```

Este worktree não continha `.env` nem `.env.local`. Sem URL, usuário e arquivos
de segredo reais do PostgreSQL canônico, a API não pôde ser iniciada. O Vite
do próprio worktree foi iniciado em 15174 com base `/api` e target 18082:
`/` respondeu 200 com o shell `Córtex Stavias`; `/api/health` respondeu
502/`ECONNREFUSED`, pois a API real não passou do preflight. O Vite foi
encerrado e ambas as portas ficaram livres.

Consequentemente, esta execução não declara:

- health/readiness real nem login CPF contra `StaviasCortex`;
- passkey com identidade provisionada e autenticador real;
- bootstrap/sync Academy ou Zeladoria, pois faltam credenciais read-only
  explicitamente verificadas.

O PostgreSQL 18.4 passou todos os testes, com aviso do Flyway de que sua faixa
oficialmente testada vai até PostgreSQL 17.

## Correção dos helpers normais

O ciclo RED adicional protegeu cada launcher separadamente, sem concatenar
fontes:

```text
node apps/web/scripts/verify-stavia-boundary.mjs --source
exit 1; 16 violações esperadas:
- run-compose.sh exigia OTP e não derivava as URLs das portas escolhidas;
- run-api-docker.sh exigia/montava OTP e publicava 8081 fixo;
- compose.production.example.yml montava OTP no runtime normal;
- .env.example fixava origem 5173 e não declarava ambas as portas.

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -Dtest=PostgresqlLocalRuntimeContractTest test
9 testes; 5 falhas esperadas; BUILD FAILURE.
```

Depois da correção:

```text
node apps/web/scripts/verify-stavia-boundary.mjs --source
StavIA source boundary verified.

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -Dtest=PostgresqlLocalRuntimeContractTest test
9 testes; 0 falhas; 0 erros; BUILD SUCCESS.

npm --prefix apps/web test -- --run src/lib/api/apiClient.test.ts
1 arquivo; 13 testes; 13 passaram.

bash scripts/security/test-local-compose-security.sh
Local PostgreSQL/loopback, normal-runtime OTP/e-mail isolation, production
secret-mount, source-read-only wiring, and container hardening contracts
passed.

bash -n scripts/dev/run-compose.sh scripts/dev/run-api-docker.sh \
  scripts/security/test-local-compose-security.sh
exit 0.

git diff --check
exit 0.
```

O `docker compose config` da verificação de segurança foi renderizado sem
fornecer OTP HMAC ou SMTP: o runtime `production,postgresql` não declara nem
monta essas chaves e não habilita o scheduler legado de cobranças.
`start-postgres-activation.sh` continua exigindo OTP e SMTP explicitamente em
seu processo isolado; nenhum deles participa do login normal por CPF/passkey.

## Fronteira final revenue-only do runtime normal

O RED estático registrou 5 falhas: quatro regressões intencionais
(arquivo obrigatório ausente, token OTP alternativo, porta operacional fixa e
uso de variável apenas em comentário) e uma fixture antiga que ainda omitia as
declarações de porta já exigidas. O contrato Java registrou 2 falhas esperadas
por SMTP/scheduler ainda presentes no Compose e no template normal.

O `PostgresqlCortexRuntimeIT` novo também foi reproduzido sobre o commit-base
`0762fbd`, em worktree temporário, sem SMTP/OTP de teste: o contexto
`postgresql` falhou fechado porque ainda dependia do grafo de e-mail. Depois do
perfilamento, o mesmo teste inicia sem essas propriedades e comprova ausência
de todos os beans Spring de cobrança legada, `EmailConfiguration` e
`EmailGateway`.

Evidência GREEN:

```text
npm --prefix apps/web test -- --run src/staviaRuntimeBoundary.test.ts
1 arquivo; 27 testes; 27 passaram.

node apps/web/scripts/verify-stavia-boundary.mjs --source
StavIA source boundary verified.

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -Dtest='PostgresqlLocalRuntimeContractTest,EmailConfigurationTest,PostgresqlMinimalLauncherContractTest' test
24 testes; 0 falhas; 0 erros.

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -Ppostgresql-it -DforkCount=1 -DreuseForks=true \
  -Dit.test=PostgresqlCortexRuntimeIT verify
1045 testes unitários; 0 falhas; 7 testes do runtime PostgreSQL; 0 falhas.

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -Ppostgresql-it -DforkCount=1 -DreuseForks=true \
  -Dit.test=PostgresqlCortex3FlowIT \
  failsafe:integration-test failsafe:verify
1 fluxo PostgreSQL V44–V60; preço de serviço/RDO/receita preservados.

bash scripts/security/test-local-compose-security.sh
Local PostgreSQL/loopback, normal-runtime OTP/e-mail isolation, production
secret-mount, source-read-only wiring, and container hardening contracts
passed.

bash scripts/security/scan-cortex-secrets.sh
Cortex secret scan passed: no unreviewed literal candidates.

git diff --check
exit 0.
```

O perfil de e-mail ficou
`!postgresql | postgresql-activation | legacy-finance`; o provider fake exige
também `local | test`. Assim, `local,postgresql` e `production,postgresql` não
criam e-mail, enquanto a ativação explícita e o deployment legado continuam
compatíveis. O launcher shell agora exporta a porta selecionada como
`SERVER_PORT`, alinhando o health check com a porta realmente usada pelo
Spring Boot.

## Prova executável dos launchers normais

O RED adversarial registrou três falhas estáticas reais: `OTP` genérico não era
detectado, uma segunda atribuição `API_PORT=8081` passava pelo contrato e os
argumentos Docker revisados podiam ficar estacionados em uma função nunca
chamada. O contrato shell também saiu com código 1 porque os ambientes-filho
herdavam nomes OTP/SMTP/e-mail carregados pelo `.env`.

O runtime normal agora carrega um sanitizador imediatamente depois do loader
local. Antes de Maven, Docker ou Compose, ele remove todo nome de ambiente com
`OTP` e as famílias `CORTEX_EMAIL_*`, `CORTEX_SMTP_*` e
`CORTEX_FINANCE_EMAIL_*`. PostgreSQL, HMAC de CPF, grant offline, cursor,
portas e sync permanecem disponíveis. `.env.example` contém somente o runtime
normal; a ativação recebe OTP/SMTP em um processo separado.

GREEN:

```text
npm --prefix apps/web test -- --run src/staviaRuntimeBoundary.test.ts
1 arquivo; 30 testes; 30 passaram.

node apps/web/scripts/verify-stavia-boundary.mjs --source
StavIA source boundary verified.

bash scripts/security/test-normal-runtime-launchers.sh
Normal launcher child environments and selected port arguments passed.

JAVA_HOME=$(/usr/libexec/java_home -v 21) \
  ./mvnw -Dtest='PostgresqlLocalRuntimeContractTest,EmailConfigurationTest,PostgresqlMinimalLauncherContractTest' test
24 testes; 0 falhas; 0 erros.

bash scripts/security/test-local-compose-security.sh
Local PostgreSQL/loopback, normal-runtime OTP/e-mail isolation, production
secret-mount, source-read-only wiring, and container hardening contracts
passed.

bash scripts/security/scan-cortex-secrets.sh
Cortex secret scan passed: no unreviewed literal candidates.
```

O contrato shell copia os launchers para um diretório temporário, cria somente
arquivos descartáveis, coloca `docker`, `lsof` e `mvnw` falsos no `PATH` e
executa o fluxo real dos scripts. Ele comprovou os binds
`127.0.0.1:18091:8080`, `CORTEX_WEB_PORT=15473` e `SERVER_PORT=18092` sem abrir
portas, iniciar containers, matar processos ou ler credenciais locais.
