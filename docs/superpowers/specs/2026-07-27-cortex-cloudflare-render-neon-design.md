# Córtex: deploy gratuito com Cloudflare, Render e Neon

Data: 27/07/2026

## Objetivo

Publicar o Córtex sem depender da máquina local e sem custo inicial, preservando os dados reais, a autenticação existente, a sincronização offline e uma rota clara de evolução para planos pagos.

O primeiro deploy é um piloto operacional. Os níveis gratuitos não oferecem SLA e a API pode ter inicialização lenta após ficar ociosa.

## Topologia

```text
Navegador
  |
  | HTTPS, mesma origem
  v
Cloudflare Pages
  |-- frontend estático
  `-- /api/* -> Pages Function -> Render API
                                  |
                                  | TLS
                                  v
                              Neon Postgres

Render API -> Cloudflare R2 para anexos
```

- Frontend: Cloudflare Pages, com URL de preview `*.pages.dev`.
- API: serviço Docker gratuito no Render.
- Banco: Neon Free, projeto `Sistema Córtex`, PostgreSQL 18, região AWS US East 2 (Ohio), branch `production`.
- Anexos: Cloudflare R2 Standard dentro da franquia gratuita.
- Autenticação: continua sendo a autenticação própria do Córtex. Neon Auth permanece desligado.

## Fluxo HTTP e autenticação

O navegador usa caminhos relativos `/api/*`. Uma Pages Function encaminha essas chamadas para uma origem fixa do Render. Isso mantém frontend e API sob a mesma origem percebida pelo navegador e evita uma nova arquitetura de CORS ou cookies.

A função:

- aceita somente o destino configurado em `CORTEX_API_ORIGIN`;
- preserva método, corpo e cabeçalhos necessários;
- não registra cookies, tokens ou corpos sensíveis;
- repassa respostas e `Set-Cookie`;
- limita sua rota a `/api/*` por `_routes.json`.

O Render continua exposto por HTTPS e mantém todas as verificações de sessão, CSRF, autorização e limites da API. A URL do Pages é cadastrada como origem exata permitida pela API.

Como ainda não há domínio próprio na conta Cloudflare, cadastros permanentes de passkey ficam adiados. Passkeys criadas no hostname `pages.dev` não devem ser tratadas como definitivas.

## Banco e migração

O banco local `StaviasCortex` possui atualmente cerca de 44 MB, 125 tabelas e usa `pg_trgm`; cabe no limite gratuito de 0,5 GB do Neon.

A migração será feita em duas passagens:

1. ensaio em branch temporária do Neon, validando restore, Flyway e testes de leitura;
2. corte final na branch `production`, com pausa curta de escrita local, novo `pg_dump`, restore e validação de contagens.

As credenciais do Neon nunca entram no Git. A conexão é fornecida ao Render por variáveis secretas separadas:

- `CORTEX_POSTGRES_URL`;
- `CORTEX_POSTGRES_USER`;
- `CORTEX_POSTGRES_PASSWORD`.

O JDBC usa TLS obrigatório. A aplicação usa o endpoint com pool; tarefas de migração usam a conexão direta.

## Anexos

O backend já implementa armazenamento S3 compatível, mas envia explicitamente o cabeçalho AWS SSE `AES256`, que o endpoint S3 do R2 não aceita.

Será adicionada uma opção de configuração testada para omitir esse cabeçalho apenas em provedores que já criptografam objetos em repouso, como o R2. Para AWS S3, o comportamento atual permanece.

Configuração prevista:

- `CORTEX_STORAGE_PROVIDER=s3`;
- `CORTEX_STORAGE_S3_BUCKET`;
- `CORTEX_STORAGE_S3_REGION=auto`;
- `CORTEX_STORAGE_S3_ENDPOINT`;
- `CORTEX_STORAGE_S3_PREFIX=production`;
- credenciais S3 fornecidas como secrets do Render.

## Git e entrega

O `develop` local está 55 commits atrás de `origin/develop` e contém muitas alterações não commitadas. A entrega seguirá esta ordem:

1. criar `feat/cortex-render-cloudflare-deploy`;
2. commitar esta especificação isoladamente;
3. criar um commit de segurança com todo o estado local restante;
4. integrar `origin/develop` com merge de três vias;
5. resolver os conflitos preservando as mudanças já validadas;
6. implementar somente os ajustes de deploy;
7. executar os gates antes de publicar.

Nenhuma alteração local será descartada para simplificar o merge.

## Gates

Antes do primeiro deploy:

- testes completos da API;
- integrações PostgreSQL e Flyway;
- testes completos do frontend;
- lint e build do frontend;
- varredura de segredos;
- teste do proxy `/api/*`;
- teste unitário da compatibilidade R2;
- `pg_restore --list` e restore de ensaio;
- comparação de tabelas e registros essenciais.

Depois do deploy:

- `/api/health` e `/api/readiness`;
- login e sessão;
- cadastro e edição de RDO;
- inclusão e exclusão de colaboradores;
- sincronização offline e replay;
- anexos;
- Obras, Financeiro e Memória;
- horários e autoria dos eventos.

## Falhas e rollback

O corte final só acontece depois dos testes no endereço de preview.

- Frontend: rollback para o deploy anterior do Cloudflare.
- API: rollback para a imagem anterior do Render.
- Banco: manter o dump local final e o PostgreSQL local em modo de contingência até a validação.
- Migrações: não executar downgrade destrutivo; corrigir com nova migração forward-only.
- DNS/domínio: não será alterado nesta fase.

Se Neon ou Render atingirem os limites gratuitos, o sistema deve falhar de forma visível. Não haverá troca automática e silenciosa para dados locais ou falsos.

## Limites aceitos no piloto

- Render Free pode dormir após inatividade e iniciar lentamente.
- Neon Free escala a computação para zero e limita o banco a 0,5 GB.
- O hostname inicial é `pages.dev`.
- Não há SLA de produção.
- O uso e o tamanho do banco devem ser monitorados antes de ampliar o número de usuários.
