# Ambiente `production` do Córtex

Este diretório é a definição executável do ambiente de produção do repositório
`Stavias-Sistema-Cortex/digitalizacao-rdo-stavias`.

## O que é publicado

- imagens imutáveis da API e da PWA no GHCR;
- PostgreSQL 18 como banco canônico `StaviasCortex`;
- API e PWA sem portas públicas próprias;
- uma única entrada HTTPS para a PWA e `/api`;
- Academy e Zeladoria somente como fontes MySQL de leitura;
- login principal por CPF canônico e passkey como alternativa;
- segredos somente por arquivos montados;
- papéis PostgreSQL separados para administração, Flyway e runtime da API.

O workflow `.github/workflows/production.yml` usa o GitHub Environment
`production`, limitado à branch `develop`. Cada publicação gera imagens com a
tag imutável `sha-<revisão>`, a tag móvel `production` e atestados de
proveniência do GitHub.

## Produção local verificável

Prepare uma cópia isolada e persistente do ambiente:

```bash
CORTEX_SOURCE_ENV_FILE=/caminho/seguro/.env.local \
  bash scripts/deploy/prepare-local-production.sh
```

O comando:

1. copia somente os segredos necessários para um diretório ignorado pelo Git;
2. cria um backup lógico restaurável do PostgreSQL canônico atual;
3. restaura esse backup no PostgreSQL 18 isolado;
4. executa Flyway;
5. constrói e inicia API, PWA e a borda HTTPS;
6. executa o smoke test pela origem final.

O endereço local é `https://cortex.localhost:18443`. O domínio reservado
`.localhost` resolve para loopback, mas preserva o contrato de hostname exigido
pelo perfil de produção e por WebAuthn. Caddy usa uma autoridade local
interna, portanto `curl` precisa de `--cacert` ou, somente para o primeiro smoke
isolado, `-k`. Em um host público, substitua a entrada Caddy por TLS gerenciado e
configure a origem HTTPS real; não publique HTTP.

Arquivos gerados ficam em `.runtime/production/` e nunca são versionados.
Volumes de banco, objetos e certificados são persistentes. O script não apaga
nem sobrescreve um banco de destino que já contenha dados.

## Publicação no GitHub

O Environment pode ser recriado de forma idempotente por um administrador:

```bash
bash scripts/deploy/configure-github-production-environment.sh
```

Antes da primeira publicação, configure no Environment a variável pública
`VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256`. O valor deve corresponder à chave
pública montada na API. As chaves privadas, senhas das fontes e senha do
PostgreSQL pertencem ao secret manager do host; elas não participam do build das
imagens.

O GHCR é o registro de release, não um host de aplicação. Para disponibilizar a
origem na Internet ainda é necessário um servidor ou plataforma de contêineres
com domínio, TLS e volumes persistentes. A composição deste diretório é o
contrato que esse host deve executar.

## Operação

`CORTEX_SYNC_ACADEMY_ENABLED=false` e
`CORTEX_SYNC_ZELADORIA_ENABLED=false` permanecem como padrão. Isso não desliga
a outbox offline da PWA. Habilite uma fonte por vez, somente depois de validar
o usuário MySQL `SELECT`-only e uma importação QA registrada em
`source_sync_run`. A Academy usa senha montada em arquivo e JDBC MySQL com
`sslMode=VERIFY_IDENTITY`.

Com o agendador ligado, `CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS` (padrão
`900000`) define quando o último `acad_colaborador_import` bem-sucedido passa a
ser relatado como `ATRASADA` em Administração → Integrações. **Não** derruba a
readiness do runtime: a Academy é um MySQL legado externo, e uma
indisponibilidade dela não pode levar junto RDO, mapa, mensagens e financeiro,
que não dependem dela. O que a readiness ainda exige é estrutural — ao menos
uma identidade Academy ativa com HMAC atual de CPF, isto é, que exista alguém
capaz de entrar.

No Render, o único fallback para um servidor sem identidade de hostname é
`sslMode=VERIFY_CA` com `/etc/secrets/cortex-academy-truststore.p12`: PKCS12,
uma única entrada confiável de certificado folha não-CA e
`fallbackToSystemTrustStore=false`. O arquivo não faz parte do repositório.

Para inspecionar sem revelar segredos:

```bash
docker compose \
  --env-file .runtime/production/production.env \
  -f deploy/production/compose.yml ps
```

Para interromper sem apagar dados:

```bash
docker compose \
  --env-file .runtime/production/production.env \
  -f deploy/production/compose.yml down
```
