# Córtex API — Checklist de Deploy

Este checklist define o que precisa estar verdadeiro antes de fazer deploy da API do Córtex.

## 1. Escopo atual que pode ser deployado

O backend atual inclui:

- API Spring Boot
- Conexão com MySQL por variáveis de ambiente
- Migrações de banco com Flyway
- Asset Registry
- Busca de ativos
- Endpoint de saúde da API
- Histórico de sincronização
- Build de imagem Docker
- Endpoint de importação desativado por padrão

O backend atual ainda não inclui:

- Frontend web
- Autenticação
- Permissões de usuário
- Módulo de RDO digital
- Modo offline/PWA
- Visualização com Mapbox
- Sincronização agendada em produção
- Pipeline de CI/CD

## 2. Variáveis de ambiente necessárias

Obrigatórias para a API iniciar:

- CORTEX_DB_URL
- CORTEX_DB_USER
- CORTEX_DB_PASSWORD
- CORTEX_AUTH_JWT_SECRET

Opcionais para importação da fonte ZLD:

- CORTEX_IMPORT_ENABLED
- ZLD_DB_URL
- ZLD_DB_USER
- ZLD_DB_PASSWORD

Valor seguro padrão para produção:

- CORTEX_IMPORT_ENABLED=false

Nunca commitar senhas reais.

## 3. Requisitos de banco de dados

A API deployada precisa se conectar a um banco MySQL contendo o schema do Córtex.

Tabelas necessárias:

- asset
- asset_alias
- source_sync_run
- source_sync_checkpoint
- flyway_schema_history

O Flyway precisa executar com sucesso na inicialização da aplicação.

Antes do deploy, confirmar localmente:

mvn -f apps/api/pom.xml clean compile

## 4. Requisitos de Docker

A imagem da API precisa buildar com sucesso:

docker build -t cortex-api:local apps/api

O container precisa rodar em modo seguro:

docker run --rm -p 8081:8080 \
  -e CORTEX_DB_URL='jdbc:mysql://host.docker.internal:3306/cortex_dev?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC' \
  -e CORTEX_DB_USER='cortex_app' \
  -e CORTEX_DB_PASSWORD="$CORTEX_DB_PASSWORD" \
  -e CORTEX_AUTH_JWT_SECRET="$CORTEX_AUTH_JWT_SECRET" \
  -e CORTEX_IMPORT_ENABLED='false' \
  cortex-api:local

Testes esperados:

curl -s http://localhost:8081/api/health
curl -s "http://localhost:8081/api/assets?query=CBA"
curl -i -X POST http://localhost:8081/api/assets/import/zld

Resultado esperado:

- /api/health retorna UP
- /api/assets retorna dados de ativos
- /api/assets/import/zld retorna 403 Forbidden

## 5. Requisitos mínimos de segurança em produção

Antes de qualquer deploy público:

- O endpoint de importação precisa estar desativado por padrão
- Nenhuma senha real pode estar commitada
- Arquivos .env precisam estar ignorados
- Arquivos target precisam estar ignorados
- A imagem Docker precisa buildar corretamente
- O endpoint de saúde precisa funcionar
- A URL do banco precisa vir por variável de ambiente
- As credenciais externas da Stavias não devem ser necessárias a menos que a importação seja ativada de propósito

## 6. Decisão do primeiro ambiente de deploy

Antes de deployar, escolher um alvo:

- Docker local
- Servidor interno da empresa
- Máquina virtual em cloud
- Render, Fly.io ou Railway
- AWS, GCP ou Azure

Para este projeto, o primeiro alvo mais seguro é:

Ambiente interno ou privado, não internet pública.

Motivo:

O backend se conecta a dados operacionais da empresa e ainda não possui autenticação.

## 7. Não expor publicamente até existir

Não expor a API publicamente até que existam:

- Autenticação
- Autorização por papéis
- Endpoint de importação protegido
- Política de CORS
- Usuário de banco de produção com privilégios limitados
- Gerenciador de segredos ou variáveis privadas de ambiente
- HTTPS
- Política básica de logs
- Estratégia de backup

## 8. Modo recomendado de deploy agora

Por enquanto, deployar apenas em modo seguro:

CORTEX_IMPORT_ENABLED=false

Isso permite:

- health check
- listagem de ativos
- busca de ativos
- visualização do histórico de sync

Isso bloqueia:

- importação manual a partir de dbstavias_zld

## 9. Melhorias futuras para deploy

Próximas melhorias:

- Docker Compose para API + MySQL
- profile de produção
- autenticação da API
- usuário read-only para a fonte externa de importação
- job de sincronização agendado
- validação automática no GitHub
- integração com frontend
