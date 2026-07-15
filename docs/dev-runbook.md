# Córtex API — Runbook de Desenvolvimento

Este documento explica como rodar e testar o backend do Córtex localmente.

## 1. Escopo atual do backend

A plataforma atualmente suporta:

- Health check
- Cadastro de ativos
- Importação de ativos a partir de dbstavias_zld.ativos
- Busca de ativos por código, nome e categoria
- Cadastro de colaboradores
- Importação de colaboradores a partir de dbstavias_acad.usuarios
- Busca de colaboradores por código, nome, email, grupo e perfil
- Histórico de execuções de sincronização
- Scripts de terminal para visualização em desenvolvimento
- Docker
- Docker Compose local
- CI básico no GitHub Actions
- autenticação por CPF com JWT e autorização Alfa/Beta por obra
- Equipes, Mensagens offline-first, anexos protegidos e sincronização por outbox
- mapas operacionais configuráveis (MapTiler/Mapbox), PDOR e Stav.IA com evidências

## 2. Portas usadas em desenvolvimento

API local com banco MySQL local:

- API: http://localhost:8080
- Web: http://127.0.0.1:5173
- Banco: cortex_dev no MySQL local

API via Docker Compose:

- API: http://localhost:8081
- Banco: MySQL Docker na porta 3307

Importante:

Os dados importados manualmente da Stavias normalmente estão no banco local cortex_dev usado pela API em 8080.

O Docker Compose usa outro banco, dentro do Docker. Esse banco começa vazio.

Então:

- Use 8080 para testar dados reais já importados localmente.
- Use 8081 para testar se a stack Docker sobe corretamente.

## 3. Variáveis de ambiente

Para rodar a API local:

export CORTEX_DB_PASSWORD='sua-senha-local-do-cortex'
export CORTEX_AUTH_JWT_SECRET='gere-um-segredo-longo-local'

# Produção: mantenha o limite ativo; o perfil local já o desliga por padrão.
export CORTEX_AUTH_LOGIN_RATE_LIMIT_ENABLED=true

Para ativar importações:

export CORTEX_IMPORT_ENABLED=true

Para importar ativos da ZLD:

export ZLD_DB_URL='jdbc:mysql://<host-zld>:3306/<database-zld>?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export ZLD_DB_USER='usuario-zld'
export ZLD_DB_PASSWORD='senha-zld'

Para importar colaboradores da Academy:

export ACAD_DB_URL='jdbc:mysql://<host-academy>:3306/<database-academy>?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export ACAD_DB_USER='usuario-acad'
export ACAD_DB_PASSWORD='senha-acad'

Nunca commitar senhas reais.

Para mapas, configure no ambiente que inicia o Vite somente uma chave pública:

export VITE_MAP_PROVIDER=maptiler
export VITE_MAPTILER_API_KEY='sua-chave-publica'

Ou, para o adapter Mapbox:

export VITE_MAP_PROVIDER=mapbox
export VITE_MAPBOX_ACCESS_TOKEN='seu-token-publico'

## 4. Rodar API local

Da raiz do repo:

./scripts/dev/run-api.sh

A API sobe em:

http://localhost:8080

Testar health check:

curl -s http://localhost:8080/api/health | jq

Resultado esperado:

status: UP

## 4.1 Rodar o frontend local

Em outro terminal:

cd apps/web
npm install
npm run dev:local

Abra http://127.0.0.1:5173. O frontend aponta para a API local em 8080; não
coloque CPF, token JWT, senha de banco ou credenciais Academy em variáveis
`VITE_*`, pois elas são incorporadas ao bundle do navegador.

## 4.2 Verificações essenciais

Backend (JDK 21):

JAVA_HOME=$(/usr/libexec/java_home -v 21) PATH="$JAVA_HOME/bin:$PATH" \
  ./apps/api/mvnw clean test

Frontend:

cd apps/web
npm test
npm run lint
npm run build

## 5. Rodar API com Docker Compose

Da raiz do repo:

./scripts/dev/run-compose.sh

A API sobe em:

http://localhost:8081

O MySQL Docker sobe em:

localhost:3307

Testar:

curl -s http://localhost:8081/api/health | jq

Parar:

./scripts/dev/stop-compose.sh

Observação:

O banco Docker é separado do banco local. Por isso, buscas podem retornar vazias no Compose mesmo quando funcionam na API local.

## 6. Endpoints de ativos

Listar ativos:

GET /api/assets

Buscar ativos:

GET /api/assets?query=CBA
GET /api/assets?query=VOLVO
GET /api/assets?query=CAM BASCULANTE

Importar ativos da ZLD:

POST /api/assets/import/zld

Em modo seguro, esse endpoint deve retornar:

403 Forbidden

Isso é correto quando:

CORTEX_IMPORT_ENABLED=false

## 7. Scripts de ativos

Buscar ativos:

./scripts/dev/assets.sh CBA
./scripts/dev/assets.sh VOLVO
./scripts/dev/assets.sh "CAM BASCULANTE"

Importar ativos:

./scripts/dev/import-assets.sh

Ver histórico de sync:

./scripts/dev/sync-runs.sh

Para usar com Docker Compose na porta 8081:

CORTEX_API_BASE_URL=http://localhost:8081 ./scripts/dev/assets.sh CBA

## 8. Endpoints de colaboradores

Listar colaboradores:

GET /api/colaboradores

Buscar colaboradores:

GET /api/colaboradores?query=paulo
GET /api/colaboradores?query=liderança
GET /api/colaboradores?query=admin

Importar colaboradores da Academy:

POST /api/colaboradores/import/acad

Em modo seguro, esse endpoint deve retornar:

403 Forbidden

Isso é correto quando:

CORTEX_IMPORT_ENABLED=false

## 9. Scripts de colaboradores

Buscar colaboradores:

./scripts/dev/colaboradores.sh paulo
./scripts/dev/colaboradores.sh liderança
./scripts/dev/colaboradores.sh admin

Importar colaboradores:

./scripts/dev/import-colaboradores.sh

Para usar com Docker Compose na porta 8081:

CORTEX_API_BASE_URL=http://localhost:8081 ./scripts/dev/colaboradores.sh paulo

Observação:

Se o Compose estiver usando banco vazio, o resultado pode ser vazio. Para ver dados reais importados da Academy, use a API local em 8080.

## 10. Fluxo de dados dos ativos

dbstavias_zld.ativos
  -> AssetImportService
  -> cortex_dev.asset
  -> GET /api/assets
  -> scripts/dev/assets.sh
  -> frontend futuramente

Campos principais:

ativos.id       -> source_pk
ativos.prefixo  -> external_code
ativos.tipo     -> category
ativos.modelo   -> name

## 11. Fluxo de dados dos colaboradores

dbstavias_acad.usuarios
  -> ColaboradorImportService
  -> cortex_dev.colaborador
  -> GET /api/colaboradores
  -> scripts/dev/colaboradores.sh
  -> frontend futuramente

Campos principais:

usuarios.id_usuario  -> pk_origem
usuarios.nome        -> nome
usuarios.email       -> email
usuarios.ativo       -> ativo
usuarios.id_grupo    -> id_grupo_origem
grupos.nome          -> nome_grupo
usuarios.id_perfil   -> id_perfil_origem
perfil.nome_perfil   -> nome_perfil
usuarios.criado_em   -> criado_em_origem

CPF não é exposto pela API do Córtex nesta versão.

## 12. Histórico de sincronização

Todas as importações registram execução em:

source_sync_run

E checkpoint em:

source_sync_checkpoint

Ver histórico pelo terminal:

./scripts/dev/sync-runs.sh

## 13. Build local

Compilar API:

mvn -f apps/api/pom.xml clean compile

Resultado esperado:

BUILD SUCCESS

## 14. Git safety

Não commitar:

- .env
- .env.*
- apps/api/target/
- .DS_Store
- .neurotrace/
- senhas reais
- credenciais da Stavias

Antes de commitar:

git status

## 15. Estado atual do backend

O backend já possui:

- cadastro de ativos
- importação real da ZLD
- cadastro de colaboradores
- importação real da Academy
- proteção de importação por variável de ambiente
- scripts de terminal
- Dockerfile
- Docker Compose local
- CI no GitHub Actions

Ainda não possui:

- frontend web
- autenticação
- permissões
- RDO digital
- modo offline/PWA
- Mapbox
- deploy real
