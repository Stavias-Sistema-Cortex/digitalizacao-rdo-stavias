# Arquitetura inicial

Este esqueleto cria somente o backend. A aplicação de campo, quando existir, deve ser uma PWA offline-first usando IndexedDB/Dexie para armazenar RDOs localmente e sincronizar com esta API quando houver internet.

## Fluxo lógico do projeto

```text
Campo offline
  -> IndexedDB futuro
  -> fila local de sincronização
  -> API NestJS
  -> Prisma
  -> PostgreSQL
  -> sala técnica, engenharia e manutenção
```

## Módulos principais

- `HealthModule`: confirma que a API está ativa.
- `RdoModule`: representa o fluxo central do Relatório Diário de Obra.
- `SyncModule`: define o contrato inicial de sincronização offline-first.
- `WeatherModule`: avalia risco de chuva por km/trecho usando previsões já sincronizadas.
- `MaintenanceModule`: cria solicitações quando uma máquina quebra ou causa parada.
- `PrismaModule`: centraliza a conexão com o PostgreSQL.

## Endpoints iniciais

- `GET /health`: retorna status básico da API.
- `POST /rdos`: cria um RDO em memória.
- `GET /rdos`: lista RDOs criados durante a execução atual.
- `GET /rdos/:id`: consulta um RDO específico.
- `PATCH /rdos/:id/status`: muda o status do RDO.
- `POST /sync/push`: recebe operações vindas do dispositivo offline.
- `GET /sync/pull?since=...`: retorna mudanças recebidas depois de uma data.
- `POST /weather/corridor-risk`: classifica risco climático por km.
- `POST /maintenance/requests`: abre uma solicitação de manutenção.
- `GET /maintenance/requests`: lista solicitações abertas durante a execução atual.

## Explicação arquivo por arquivo

- `package.json`: declara scripts e dependências do backend NestJS/Prisma.
- `tsconfig.json`: configura o TypeScript para decorators, tipagem estrita e saída em `dist`.
- `tsconfig.build.json`: remove testes e arquivos gerados do build final.
- `nest-cli.json`: informa ao Nest CLI que o código-fonte fica em `src`.
- `.env.example`: lista variáveis de ambiente esperadas sem valores reais.
- `.gitignore`: bloqueia segredos, dependências, builds, dados locais e estado do NeuroTrace.
- `prisma/schema.prisma`: modela projetos, trechos, frentes de serviço, RDOs, produção, equipamentos, clima, manutenção, sincronização e auditoria.
- `src/main.ts`: inicializa a API, valida DTOs e habilita CORS.
- `src/app.module.ts`: registra todos os módulos do backend.
- `src/prisma/prisma.module.ts`: exporta o Prisma como serviço global.
- `src/prisma/prisma.service.ts`: abre e fecha a conexão com o banco.
- `src/modules/health/health.controller.ts`: expõe o health check da API.
- `src/modules/health/health.module.ts`: registra o controller de saúde.
- `src/modules/rdo/dto/create-rdo.dto.ts`: valida o formato de entrada de um RDO, incluindo mão de obra, equipamentos, produção, ocorrências e clima.
- `src/modules/rdo/dto/update-rdo-status.dto.ts`: valida mudanças de status do RDO.
- `src/modules/rdo/rdo.types.ts`: define status e formato interno inicial de um RDO.
- `src/modules/rdo/rdo.service.ts`: guarda RDOs temporariamente em memória e concentra a regra de criação/status.
- `src/modules/rdo/rdo.controller.ts`: expõe os endpoints do fluxo de RDO.
- `src/modules/rdo/rdo.module.ts`: agrupa controller e service de RDO.
- `src/modules/sync/dto/sync-operation.dto.ts`: define como um dispositivo offline envia mudanças pendentes.
- `src/modules/sync/sync.types.ts`: define entidades, operações e respostas da sincronização.
- `src/modules/sync/sync.service.ts`: aceita eventos de sync em memória e retorna mudanças por data.
- `src/modules/sync/sync.controller.ts`: expõe endpoints de push/pull.
- `src/modules/sync/sync.module.ts`: agrupa controller e service de sincronização.
- `src/modules/weather/dto/evaluate-corridor-risk.dto.ts`: valida pontos meteorológicos por km.
- `src/modules/weather/weather.types.ts`: define níveis de risco climático.
- `src/modules/weather/weather.service.ts`: transforma chance/volume de chuva em alertas por trecho.
- `src/modules/weather/weather.controller.ts`: expõe a avaliação de risco por corredor.
- `src/modules/weather/weather.module.ts`: agrupa controller e service de meteorologia.
- `src/modules/maintenance/dto/create-maintenance-request.dto.ts`: valida pedidos de manutenção ligados a equipamentos.
- `src/modules/maintenance/maintenance.types.ts`: define prioridade, status e formato de solicitação.
- `src/modules/maintenance/maintenance.service.ts`: guarda solicitações temporárias de manutenção em memória.
- `src/modules/maintenance/maintenance.controller.ts`: expõe criação e listagem de solicitações.
- `src/modules/maintenance/maintenance.module.ts`: agrupa controller e service de manutenção.

## Observação importante

Os services ainda usam memória local para manter o esqueleto simples e fácil de entender. O próximo passo técnico é substituir os `Map` por chamadas reais ao `PrismaService`, depois criar migrações e testes.
