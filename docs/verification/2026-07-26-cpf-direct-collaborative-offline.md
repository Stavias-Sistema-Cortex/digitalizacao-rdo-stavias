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

O helper fora do escopo `scripts/dev/run-compose.sh` ainda exige OTP e imprime
portas fixas; a documentação desta entrega usa o Compose parametrizado
diretamente. O PostgreSQL 18.4 passou todos os testes, com aviso do Flyway de
que sua faixa oficialmente testada vai até PostgreSQL 17.
