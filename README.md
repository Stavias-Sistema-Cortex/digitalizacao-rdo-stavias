# Stavias Córtex

Plataforma operacional offline-first para obras, RDO, Mensagens, Financeiro,
memória operacional e grafo ontológico. O backend usa Java 21, Spring Boot,
PostgreSQL/Flyway e armazenamento local persistente ou S3; a PWA usa React,
TypeScript, Vite e IndexedDB. A StavIA está arquivada fora do runtime ativo.

## Desenvolvimento local

```bash
cp .env.example .env
# preencha somente secrets locais
./scripts/dev/run-compose.sh
```

- PWA: `http://localhost:5173`
- health da API: `http://127.0.0.1:8081/api/health`
- readiness com banco: `http://127.0.0.1:8081/api/readiness`

`compose.local.yml` usa somente o runtime PostgreSQL canônico. Ele não cria
ALFA, obra, RDO ou receita fictícios: migrações até V60, bootstrap de uma identidade
real e o preflight de release devem ser concluídos antes de definir
`CORTEX_POSTGRES_RUNTIME_READY=true`.

Para executar sem containers, veja [docs/dev-runbook.md](docs/dev-runbook.md).

## Verificação

```bash
cd apps/api && JAVA_HOME=$(/usr/libexec/java_home -v 21) ./mvnw test
cd ../web && npm test -- --run && npm run lint && npm run build
```

## Produção

Comece por [docs/deploy-checklist.md](docs/deploy-checklist.md) e
[docs/production-runbook.md](docs/production-runbook.md). O arquivo
`compose.production.example.yml` é apenas uma topologia de referência: não cria
banco nem contém credenciais. Produção falha fechada sem secrets por arquivo,
origem HTTPS exata, SMTP autenticado, storage durável e um ALFA ativo com e-mail
verificado. O web container fica em loopback por padrão e deve ser publicado por
um ingresso HTTPS gerenciado; Academy e Zeladoria recebem somente credenciais
de leitura para importação, enquanto todo estado do Córtex fica no PostgreSQL.
O acesso online normal usa CPF + OTP entregue ao e-mail canônico persistido no
PostgreSQL; passkey é uma alternativa online. O cofre offline exige uma passkey
PRF previamente registrada e nunca aceita CPF ou OTP como desbloqueio.

## Runtime PostgreSQL canônico

O PostgreSQL `StaviasCortex` é a fonte canônica do runtime Cortex 3.0; Academy e
Zeladoria continuam somente como fontes MySQL de leitura. A ativação normal
continua fail-closed: exige schema completo, `CORTEX_POSTGRES_RUNTIME_READY=true`
e o conjunto exato de superfícies operacionais registradas. Veja o
[runbook de clean start PostgreSQL](docs/operations/cortex-postgresql-clean-start.md)
antes de provisionar, migrar, fazer bootstrap ou ativar esse ambiente.

Para verificar o contrato de deploy sem usar credenciais reais, execute
`bash scripts/security/test-local-compose-security.sh`. Ele renderiza o Compose
com secrets temporários, confirma que não há fallback para `CORTEX_DB_*` e que
as fontes externas não substituem o PostgreSQL canônico.
