# Stavias Córtex

Plataforma operacional offline-first para obras, RDO, Mensagens, Financeiro e
StavIA. O backend usa Java 21, Spring Boot, MySQL/Flyway e armazenamento local
persistente ou S3; a PWA usa React, TypeScript, Vite e IndexedDB.

## Desenvolvimento local

```bash
cp .env.example .env
# preencha somente secrets locais
docker compose --env-file .env -f compose.local.yml up --build
```

- PWA: `http://127.0.0.1:5173`
- health da API: `http://127.0.0.1:8081/api/health`
- readiness com banco: `http://127.0.0.1:8081/api/readiness`

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
verificado.

## Fundação PostgreSQL limpa

O PostgreSQL `StaviasCortex` é uma fundação canônica nova, não um cutover
automático do runtime MySQL. Academy e Zeladoria continuam somente como fontes
MySQL de leitura. Veja o
[runbook de clean start PostgreSQL](docs/operations/cortex-postgresql-clean-start.md)
antes de provisionar, migrar, fazer bootstrap ou ativar esse ambiente. O
runtime operacional PostgreSQL permanece propositalmente bloqueado até uma
release posterior registrar um slice seguro e verificado.
