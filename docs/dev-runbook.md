# Córtex API — Developer Runbook

## Scope

The API currently supports:

- Health check
- Asset registry using local MySQL cortex_dev
- Import from dbstavias_zld.ativos
- Asset search by code, name, and category
- Sync run history
- Terminal scripts for developer visualization

## Required Environment Variables

Local API:

export CORTEX_DB_PASSWORD='your-local-cortex-password'

Import-enabled mode:

export CORTEX_IMPORT_ENABLED=true
export ZLD_DB_URL='jdbc:mysql://dbstavias_zld.mysql.dbaas.com.br:3306/dbstavias_zld?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export ZLD_DB_USER='your-zld-user'
export ZLD_DB_PASSWORD='your-zld-password'

Never commit real secrets.

## Run API in Safe Mode

cd apps/api
export CORTEX_DB_PASSWORD='your-local-cortex-password'
mvn spring-boot:run

Test:

curl -s http://localhost:8080/api/health | jq
curl -s "http://localhost:8080/api/assets?query=CBA" | jq
curl -i -X POST http://localhost:8080/api/assets/import/zld

Expected:

- /api/health returns UP
- /api/assets?query=CBA returns CBA assets
- /api/assets/import/zld returns 403 Forbidden

## Run API with Import Enabled

Stop the API first with CTRL + C.

Then:

cd apps/api

export CORTEX_DB_PASSWORD='your-local-cortex-password'
export CORTEX_IMPORT_ENABLED=true

export ZLD_DB_URL='jdbc:mysql://dbstavias_zld.mysql.dbaas.com.br:3306/dbstavias_zld?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC'
export ZLD_DB_USER='your-zld-user'
export ZLD_DB_PASSWORD='your-zld-password'

mvn spring-boot:run

Test:

curl -s -X POST http://localhost:8080/api/assets/import/zld | jq
curl -s "http://localhost:8080/api/assets/import/runs" | jq

Expected import result:

status: SUCCESS
recordsRead: 130
recordsProcessed: 130
recordsInserted: 0 if data already exists
recordsUpdated: 0 if nothing changed

## Terminal Scripts

From repo root:

./scripts/dev/assets.sh CBA
./scripts/dev/assets.sh VOLVO
./scripts/dev/assets.sh "CAM BASCULANTE"
./scripts/dev/import-assets.sh
./scripts/dev/sync-runs.sh

## Build Check

From repo root:

mvn -f apps/api/pom.xml clean compile

Expected:

BUILD SUCCESS

## Git Safety Rules

Do not commit:

- .env
- .env.*
- apps/api/target/
- .DS_Store
- .neurotrace/
- real passwords

Before committing:

git status

## Current Architecture Flow

dbstavias_zld.ativos
  -> AssetImportService
  -> cortex_dev.asset
  -> GET /api/assets
  -> terminal scripts / future web frontend

Sync history flow:

POST /api/assets/import/zld
  -> source_sync_run
  -> source_sync_checkpoint
