# Córtex — runbook de desenvolvimento

## Pré-requisitos

- JDK 21 e Node 22;
- PostgreSQL 18 acessível com o banco canônico `StaviasCortex`;
- Docker Desktop apenas se a API/PWA forem executadas em containers;
- `.env` local ignorado pelo Git, criado a partir de `.env.example`;
- arquivos locais protegidos para senha PostgreSQL, HMACs e chaves offline.

Academy e Zeladoria não são bancos do Córtex. Elas podem ser configuradas
somente como fontes MySQL externas de leitura e continuam desabilitadas por
padrão.

## Preparar o runtime PostgreSQL

Copie o modelo e preencha apenas URLs não secretas, IDs de chave e caminhos de
arquivos protegidos:

```bash
cp .env.example .env
```

O runtime normal usa `local,postgresql` e falha fechado sem schema V60, ALFA
real ativo e o gate explícito. A sequência completa é:

```bash
./scripts/dev/migrate-postgres-cortex.sh
# Execute o bootstrap somente com a identidade real autorizada e a fonte
# Academy somente leitura, conforme o runbook de clean start.
./scripts/dev/bootstrap-postgres-alfa.sh
./scripts/dev/start-postgres-activation.sh
CORTEX_POSTGRES_RUNTIME_READY=true \
  ./scripts/dev/check-postgres-runtime-release.sh
```

Não crie ALFA, pessoa, obra, equipe, RDO, serviço, preço ou receita sintéticos
para fazer o runtime parecer pronto. Consulte
[cortex-postgresql-clean-start.md](operations/cortex-postgresql-clean-start.md)
para a transição e o rollback.

## Stack Docker local

Depois de concluir os gates acima e definir
`CORTEX_POSTGRES_RUNTIME_READY=true`, execute:

```bash
./scripts/dev/run-compose.sh
```

O Compose não contém um MySQL primário e não injeta a senha PostgreSQL no
ambiente do container. `CORTEX_POSTGRES_DOCKER_URL` deve apontar para a mesma
instância canônica, por exemplo via `host.docker.internal` no macOS.

- PWA: `http://localhost:5173`
- API: `http://127.0.0.1:8081`
- health: `http://127.0.0.1:8081/api/health`
- readiness: `http://127.0.0.1:8081/api/readiness`

```bash
docker compose -f compose.local.yml logs -f cortex-api cortex-web
./scripts/dev/stop-compose.sh
```

## Processos locais separados

Com o mesmo `.env` validado:

```bash
./scripts/dev/run-api.sh

cd apps/web
npm ci
npm run dev:local
```

A API usa a porta 8080 e a PWA usa 5173. O modo de autenticação web é
`postgresql`: o fluxo online normal é CPF + OTP de e-mail, com passkey como
alternativa. Importações e dev-admin permanecem desabilitados, e o sync
canônico fica ativo.

O perfil local aceita e-mail fake apenas para testes automatizados. Ele não é
prova de login humano e não oferece uma caixa de entrada HTTP. Para validar o
OTP humano, configure SMTP real ou um mail sink controlado; para um smoke local
sem e-mail, use a passkey alternativa. Registre o resultado sem e-mail, OTP,
cookie ou identificador pessoal.

## Dados externos e importação

`CORTEX_IMPORT_ENABLED=false` é o padrão. Habilite a importação somente em uma
execução explícita com credenciais read-only por arquivo e autorização do
operador. Nunca copie credenciais Academy/Zeladoria para Compose, documentação,
logs ou fixtures.

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
- CPF, PIN, e-mail e OTP são deliberadamente online-only. Antes do primeiro
  acesso offline, registre uma passkey PRF enquanto houver conexão.
- A reconexão inicia replay automático idempotente. Falhas e conflitos ficam
  visíveis, nunca são descartados silenciosamente.
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
