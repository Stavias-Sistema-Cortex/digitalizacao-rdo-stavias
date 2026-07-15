# Córtex — runbook de desenvolvimento

## Pré-requisitos

- JDK 21 (Java 25 não é o runtime suportado para o gate Maven);
- Node 22 e npm;
- Docker Desktop para MySQL/compose;
- `.env` local ignorado pelo Git, criado a partir de `.env.example`.

No mínimo, preencha senhas do MySQL e uma chave CPF HMAC local aleatória. Não
use CPF, e-mail, senha ou token real em fixture, log ou commit.

## Stack Docker local

```bash
cp .env.example .env
# edite os valores locais
./scripts/dev/run-compose.sh
```

Serviços:

- PWA: `http://127.0.0.1:5173`
- API: `http://127.0.0.1:8081`
- MySQL: `127.0.0.1:3307`
- readiness: `http://127.0.0.1:8081/api/readiness`

O compose usa e-mail fake somente no perfil `local` e um volume persistente
para anexos. Não há endpoint para ler OTPs fake: testes capturam o gateway por
injeção; códigos nunca são expostos por uma rota de produção.

```bash
docker compose -f compose.local.yml logs -f cortex-api cortex-web
./scripts/dev/stop-compose.sh
```

## Processos locais separados

```bash
./scripts/dev/run-api.sh

cd apps/web
npm ci
npm run dev:local
```

A API local usa porta 8080 e a PWA 5173. `run-api.sh` valida banco e CPF HMAC,
ativa o perfil `local` e não cria JWT. Autenticação online usa desafio OTP,
cookie de sessão opaco e CSRF; passkeys usam WebAuthn.

## Dados externos e importação

Importação é opt-in. Configure `CORTEX_IMPORT_ENABLED=true` e as variáveis
`CORTEX_ZELADORIA_DB_*` / `CORTEX_ACADEMY_DB_*` apenas quando o acesso às fontes
for intencional. Com importação desativada, rotas administrativas respondem
403. Nunca copie credenciais externas para compose ou documentação.

Scripts úteis em `scripts/dev/` incluem busca de ativos/colaboradores,
importações explícitas, histórico de sync e `smoke-stavia-sync.sh`. O smoke cria
MySQL e dados `example.invalid` descartáveis, valida CORS/sessão/sync/StavIA e
remove tudo ao encerrar.

## Testes

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test

cd ../web
npm test -- --run
npm run lint
npm run build
```

Para habilitar os testes MySQL locais já anotados, exporte somente durante a
execução:

```bash
export CORTEX_MYSQL_ROOT_PASSWORD='senha-local-do-container'
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
```

Cada teste cria e apaga seu próprio schema. Flyway deve aplicar V1–V33 sem
`repair`.

## Offline e sync

- Mensagens, anexos e compras usam IDs/clientMutationId estáveis no IndexedDB.
- Recarregar offline não autoriza chamadas à API; o grant offline serve apenas
  ao cofre local verificado.
- Na reconexão, acompanhe o estado no indicador de sync. Falhas permanecem
  visíveis e podem ser repetidas; não são descartadas silenciosamente.
- Para diagnosticar erro de sync, confirme nesta ordem: `/api/health`,
  `/api/readiness`, sessão, scope/capability, rota e recibo idempotente.

## Financeiro e autorização

ALFA possui acesso global. BETA exige vínculo ativo com a obra e capability
financeira exata. O frontend só reflete o resultado de
`/api/financeiro/capacidades`; a autoridade permanece no backend.

Não semeie fornecedores, notas, totais ou gráficos para “preencher” a tela.
Estados vazios e “sem vínculo orçamentário” são comportamento correto quando a
consulta real não oferece dados.

## Antes de commitar

```bash
git status --short
git diff --check
```

Não versionar `.env*`, `target/`, `dist/`, `node_modules/`, `.DS_Store`,
`.neurotrace/`, secrets, dumps ou dados pessoais. Para publicação, siga
`docs/deploy-checklist.md` e `docs/production-runbook.md`.
