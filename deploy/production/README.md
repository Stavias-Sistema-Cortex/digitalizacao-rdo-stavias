# Ambiente `production` do Córtex

Este diretório é a definição executável do ambiente de produção do repositório
`Stavias-Sistema-Cortex/digitalizacao-rdo-stavias`.

## O que é publicado

- imagens imutáveis da API e da PWA no GHCR;
- PostgreSQL 18 como banco canônico `StaviasCortex`;
- API e PWA sem portas públicas próprias;
- uma única entrada HTTPS para a PWA e `/api`;
- Academy e Zeladoria somente como fontes MySQL de leitura;
- login principal por CPF canônico e senha individual, com passkey opcional;
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
CORTEX_RELEASE_SHA=63f315df6bd0377b162851d22bdd4f644a938c2b \
CORTEX_DATABASE_RELEASE_MARKER=igfgku3z2pPhpBClBEpnaMTZs8HLLhfa-vuAM6E269Q \
CORTEX_API_IMAGE=ghcr.io/stavias-sistema-cortex/digitalizacao-rdo-stavias-api@sha256:6c64976326ee90cfc57b487e34174095a5fd74a705979cc0cb23f8fa74bf4244 \
CORTEX_WEB_IMAGE=ghcr.io/stavias-sistema-cortex/digitalizacao-rdo-stavias-web@sha256:79b7d935c480954c1a93e1e827fa4b7126c546210d5b77b2a57535a5b680b1e9 \
  bash scripts/deploy/prepare-local-production.sh
```

Os valores acima exemplificam uma única revisão já publicada. Em cada ensaio,
use o SHA, o marcador canônico e os dois digests produzidos pelo mesmo run de
release. O validador rejeita tags móveis, imagens de outro repositório, SHA
abreviado, marcador de outra revisão e qualquer origem diferente de
`https://cortex.portalstavias.com.br`.

O comando:

1. copia somente os segredos necessários e gera uma chave exclusiva para os
   códigos temporários em um diretório ignorado pelo Git;
2. baixa as imagens imutáveis do GHCR e confere o label OCI de revisão antes de
   acessar o banco;
3. cria um backup lógico restaurável do PostgreSQL canônico atual;
4. restaura esse backup no PostgreSQL 18 isolado e compara a contagem de todas
   as tabelas públicas com o manifesto da origem;
5. executa Flyway e grava o marcador público da mesma revisão;
6. inicia API, PWA e a borda HTTPS sem construir imagens no servidor;
7. executa o smoke test direto no candidato pela origem final, fixada em
   loopback e sem alterar o Apache.

No modo padrão `rehearsal`, o projeto é `cortex-production-rehearsal` e publica
somente `127.0.0.1:18444`. O curl de validação fixa
`cortex.portalstavias.com.br` nesse loopback, usando a autoridade interna do
Caddy, enquanto o Apache e o tráfego real continuam intocados. O modo
`cutover` usa o projeto `cortex-production` e `127.0.0.1:18443`, mas só deve ser
executado pelo procedimento explícito de corte e rollback.

O ensaio grava artefatos em `.runtime/production-rehearsal/`; o cutover usa
`.runtime/production/`. Ambos ficam fora do Git. O dump, o log do restore e os
manifestos de contagem usam modo `600`.
Volumes de banco, objetos e certificados são persistentes. O script não apaga
nem sobrescreve um banco de destino que já contenha dados.

## Backup externo criptografado

Antes de tornar o servidor local canônico, monte um destino realmente externo
(NFS/SSHFS corporativo ou mídia removível guardada fora do servidor) em um
diretório root-only. O script recusa o mesmo device dos volumes Docker. Gere a
identidade `age` fora do servidor, mantenha somente o recipient público no host
e coloque a identidade privada no cofre operacional.

```bash
sudo env \
  CORTEX_REMOTE_RETENTION=preserve \
  CORTEX_EXPECTED_RELEASE_SHA="$(git rev-parse HEAD)" \
  CORTEX_BACKUP_DESTINATION=/mnt/cortex-offhost \
  CORTEX_BACKUP_AGE_RECIPIENT_FILE=/etc/cortex-backup/recipient.txt \
  CORTEX_COMPOSE_FILE="$PWD/deploy/production/compose.yml" \
  CORTEX_COMPOSE_ENV_FILE=/srv/cortex/runtime/production.env \
  CORTEX_COMPOSE_PROJECT_NAME=cortex-production \
  CORTEX_DOCKER_BIN=/usr/bin/docker \
  CORTEX_AGE_BIN=/usr/bin/age \
  CORTEX_STAT_BIN=/usr/bin/stat \
  CORTEX_BACKUP_RETENTION_COUNT=14 \
  bash scripts/deploy/backup-local-production.sh
```

O dump PostgreSQL custom-format, o tar de objetos e o manifesto detalhado de
SHA-256 são cifrados separadamente com `age`. O manifesto visível contém apenas
totais, tamanhos, digests, revisão e IDs imutáveis das imagens. Artefatos só
ganham o nome final depois que todos terminam; falha remove plaintext e
parciais, sem tocar no último backup completo.

Ao menos uma vez por mês, disponibilize temporariamente a identidade privada e
execute um restore descartável. O verificador recusa backup com mais de 26
horas por padrão, revalida todos os hashes, restaura PostgreSQL em container e
volume temporários sem rede, grava evidência redigida e remove apenas esses
recursos descartáveis:

```bash
sudo env \
  CORTEX_REMOTE_RETENTION=preserve \
  CORTEX_EXPECTED_RELEASE_SHA="$(git rev-parse HEAD)" \
  CORTEX_BACKUP_AGE_IDENTITY_FILE=/run/cortex-restore/identity.txt \
  CORTEX_RESTORE_SCRATCH_ROOT=/srv/cortex/restore-scratch \
  CORTEX_DOCKER_BIN=/usr/bin/docker \
  CORTEX_AGE_BIN=/usr/bin/age \
  CORTEX_BACKUP_MAX_AGE_SECONDS=93600 \
  bash scripts/deploy/verify-local-production-backup.sh \
    /mnt/cortex-offhost/cortex-AAAA.manifest.json
```

Automatize o primeiro comando com um timer `systemd` diário usando um
`EnvironmentFile` root-only. A unidade deve exigir que `/mnt/cortex-offhost`
esteja montado (`RequiresMountsFor=`) e nunca carregar a identidade privada de
restore no job diário.

## Atualização automática do servidor local

O workflow de produção publica somente imagens imutáveis no GHCR e o artefato
`cortex-local-release-<sha>`. O handoff JSON é atestado pelo GitHub e vincula o
SHA, os dois digests OCI, o marker do banco e o SHA-256 de um Git bundle com o
código exato. Neon, Render e Cloudflare não participam desse caminho.

Instale uma única vez, a partir de uma release já conferida no servidor:

```bash
sudo bash scripts/deploy/install-cortex-local-release-agent.sh
```

O instalador fixa e verifica o GitHub CLI, copia o verificador e instala
`cortex-local-release-update.service` e `.timer`. A cada cinco minutos o host:

1. consulta o último `production.yml` verde de `develop`;
2. baixa o artefato exato usando a credencial privada já usada pelo GHCR;
3. verifica a atestação, o SHA do bundle, o commit e os digests das imagens;
4. materializa uma árvore Git root-only e executa o atualizador fail-closed;
5. preserva dump PostgreSQL, environment anterior e todos os volumes;
6. exige health/readiness, marker, storage e revisão exatos;
7. restaura automaticamente a release anterior se a ativação falhar.

O journal do updater impede duas atualizações simultâneas e recupera quedas de
energia. Um checkpoint `ACTIVATED` anterior só é arquivado quando a release
atual, seus volumes e seus backups são novamente provados. Consulte sem expor
credenciais:

```bash
systemctl status cortex-local-release-update.timer
journalctl -u cortex-local-release-update.service --since today
```

O push pode ser feito fora da rede Stavias. A rede interna continua necessária
somente para instalar, auditar ou reparar o serviço por SSH; o próprio servidor
consulta GitHub e GHCR pela Internet.

Essa automação pertence ao único WebServerLinux, não ao computador de quem fez
o push. Depois de instalada, qualquer colaborador autorizado continua usando a
mesma URL canônica em qualquer máquina Stavias compatível. Clientes que ainda
estão com um bundle anterior permanecem fail-closed e tentam novamente após a
atualização, sem precisar eleger um navegador como aprovador da release.

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
`source_sync_run`. As duas fontes usam senhas montadas em arquivos separados;
o Córtex nunca executa `INSERT`, `UPDATE` ou `DELETE` nesses bancos.

No Academy, somente `usuarios.ativo=false` invalida o CPF e revoga o acesso.
Uma pessoa ausente de um snapshot não é desativada por inferência. Na
Zeladoria, o snapshot completo representa o cadastro atual de assets: uma
alteração atualiza a mesma identidade estável; uma exclusão na origem torna o
asset inativo no Córtex sem apagar seu histórico; o reaparecimento o reativa.

Com o agendador ligado, `CORTEX_SYNC_ACADEMY_READINESS_MAX_AGE_MS` e
`CORTEX_SYNC_ZELADORIA_READINESS_MAX_AGE_MS` (ambos `900000` por padrão)
definem quando a última leitura bem-sucedida passa a ser relatada como
`ATRASADA` em Administração → Integrações. Isso não derruba a readiness do
runtime: uma fonte MySQL legada indisponível não pode levar junto RDO, mapa,
mensagens e financeiro. O que a readiness ainda exige é estrutural — ao menos
uma identidade Academy ativa com HMAC atual de CPF, isto é, que exista alguém
capaz de entrar.

Na produção local, os certificados legados sem identidade de hostname são
fixados separadamente com `sslMode=VERIFY_CA`,
`/etc/secrets/cortex-academy-truststore.p12` e
`/etc/secrets/cortex-zeladoria-truststore.p12`. Cada PKCS12 contém exatamente
uma entrada confiável de certificado folha não-CA, sem chave privada, e a URL
inclui `trustCertificateKeyStoreType=PKCS12` e
`fallbackToSystemTrustStore=false`. Os arquivos não fazem parte do repositório
e são montados somente leitura.

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
