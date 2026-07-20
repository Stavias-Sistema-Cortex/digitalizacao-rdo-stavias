# Validacao StavIA Offline, Seguranca, Ontologia e Integracoes

Data: 2026-07-06

Escopo: worktree atual de `digitalizacao-rdo-stavias`, sem resetar mudancas existentes. A validacao cobriu testes automatizados, build/compile, API local com MySQL descartavel, PWA offline em preview de producao, calculos offline, seguranca, ontologia RDO e integracoes Academy/Zeladoria.

## Resultado executivo

- Status geral: aprovado sem falhas criticas confirmadas.
- Correcoes de codigo aplicadas: nenhuma; nao houve falha critica que exigisse alteracao funcional.
- Artefatos criados: este relatorio e o threat model em `/var/folders/vd/ww6lt79d0hq12dqd8f9xqt2h0000gn/T/codex-security-scans/digitalizacao-rdo-stavias/threat_model.md`.
- NeuroTrace MCP: nao estava exposto nesta sessao. Foi feita busca de ferramentas disponiveis e nenhuma ferramenta `neurotrace_*` estava callable.
- Browser interno Codex: nao estava disponivel. A prova PWA foi feita por fallback com Microsoft Edge headless via CDP.

## Comandos executados

### Web

```bash
cd apps/web && npm test -- --run src/features/stavia/staviaLocalEngine.test.ts src/features/stavia/staviaPanelAnswer.test.ts src/features/stavia/staviaRdoOntology.test.ts src/features/stavia/rdoOntologyParity.test.ts src/lib/sync/syncStorage.test.ts src/lib/db/localRdoService.test.ts src/lib/api/apiClient.test.ts
```

Resultado: passou. Foram 7 arquivos e 43 testes.

```bash
cd apps/web && npm run build
```

Resultado: passou. O build gerou PWA/service worker com precache de 53 entradas. Houve apenas aviso de chunks acima de 500 kB.

### API

```bash
cd apps/api && JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw -q -Dtest=JwtServiceTest,AuthServiceTest,CpfBloomFilterTest,LocalCorsConfigurationTest,SyncServiceSecurityTest,SyncServicePullVersionTest,StaviaSnapshotOntologyContractTest,StaviaControllerTest,RdoOntologyTest,RdoOntologyPlannerTest,RdoRecordKnowledgeSourceTest,RdoRecordSqlBuilderTest,StaviaQueryServiceTest,IntegracaoAdminServiceTest,IntegracaoAdminControllerTest,ExternalSourceAdapterTest test
```

Resultado: passou.

```bash
cd apps/api && JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw -q -DskipTests compile
```

Resultado: passou.

### Runtime offline/sync

```bash
./scripts/dev/smoke-stavia-sync.sh
```

Resultado: passou. Evidencias principais:

- API local subiu com `SPRING_PROFILES_ACTIVE=local` contra MySQL descartavel.
- CORS loopback/private network validado.
- Device sync autenticado registrado.
- RDO completo enviado por `/sync/push`.
- Conflito de versao rejeitado.
- Payload invalido rejeitado.
- `/sync/pull` e `/sync/ack` validados.
- Snapshot StavIA validado com todos os blocos de RDO.
- Respostas StavIA consultadas sobre blocos repetidos.
- Estados de mutacao observados: `APLICADA=1`, `DESCARTADA=1`, `ERRO=1`.

### Browser/PWA offline

Preview usado:

```bash
cd apps/web && npm run preview:local
```

Prova executada por Microsoft Edge headless via CDP, carregando `http://127.0.0.1:4173/`, aguardando `navigator.serviceWorker.ready`, semeando o filtro Bloom local de CPF, desligando rede por `Network.emulateNetworkConditions`, recarregando e fazendo login offline.

Resultado sanitizado:

```json
{
  "ok": true,
  "summary": {
    "url": "http://127.0.0.1:4173/",
    "serviceWorkerControlled": true,
    "cacheCount": 1,
    "offline": true,
    "rootHasContent": true,
    "offlineLogin": true,
    "sessionOrigin": "offline",
    "sessionHasToken": false,
    "indexedDbNames": ["cortex-web"]
  }
}
```

Interpretação: app shell carregou offline sob service worker, o login offline funcionou via Bloom filter, a sessao offline nao recebeu JWT e o IndexedDB local estava presente.

### Integracoes automaticas/manuais

As variaveis de Academy/Zeladoria estavam presentes no ambiente; valores nao foram impressos.

Foi executado smoke isolado com MySQL descartavel, API local, `CORTEX_IMPORT_ENABLED=true`, `CORTEX_SYNC_ENABLED=true`, scheduler com delay longo para evitar leitura duplicada durante o teste, e token admin local. Foram chamados:

- `GET /api/integracoes`
- `POST /api/integracoes/academy/testar-conexao`
- `POST /api/integracoes/zeladoria/testar-conexao`
- `POST /api/integracoes/academy/sincronizar`
- `POST /api/integracoes/zeladoria/sincronizar`

Resultado sanitizado:

```text
academy_test=http_200 status=SUCCESS
zeladoria_test=http_200 status=SUCCESS
academy_sync=http_200 status=SUCCESS
zeladoria_sync=http_200 status=SUCCESS
zld_asset_import        SUCCESS 130 130 0 0
acad_colaborador_import SUCCESS 478 478 0 0
```

As linhas finais representam `connector_name`, `status`, `records_read`, `records_inserted`, `records_updated`, `records_deactivated`. Nenhum dado pessoal ou segredo foi impresso. O scheduler foi validado por configuracao/codigo: `CortexSyncScheduler` e condicionado por `cortex.sync.enabled=true`, com `@Scheduled` usando `cortex.sync.initial-delay-ms` e `cortex.sync.fixed-delay-ms`.

## Ontologia RDO

Paridade backend/web gerada por leitura dos JSONs:

```json
{
  "apiVersion": "1.0.0",
  "webVersion": "1.0.0",
  "exactParity": true,
  "entities": 9,
  "attributes": 153,
  "matrix": [
    { "name": "rdo", "attributes": 42 },
    { "name": "material", "attributes": 9 },
    { "name": "maoObra", "attributes": 8 },
    { "name": "equipamento", "attributes": 9 },
    { "name": "controleGeometrico", "attributes": 21 },
    { "name": "execucaoServico", "attributes": 16 },
    { "name": "alocacaoColaborador", "attributes": 17 },
    { "name": "attachment", "attributes": 15 },
    { "name": "operationalEvent", "attributes": 16 }
  ]
}
```

O conjunto atual continua em `version=1.0.0` porque nenhum novo atributo foi adicionado nesta validacao. Os testes de contrato passaram cobrindo:

- paridade `apps/api/src/main/resources/stavia/rdo-ontology.json` vs `apps/web/src/features/stavia/rdoOntology.json`;
- contrato de snapshot/ontologia;
- planejamento por ontologia;
- respostas por `responderComSnapshotStavia(...)`, incluindo o teste "responde cada celula declarada pelo caminho real do motor local offline".

Conclusao: os 9 blocos e 153 atributos atuais estao consultaveis pelo caminho offline validado. Nao foi identificado campo operacional adicional, ja presente no formulario/DB/snapshot, que exigisse ampliacao nesta passada.

## Calculo offline

Arquivos verificados:

- `apps/web/src/features/rdos/rdoCalculations.ts`
- `apps/web/src/lib/db/localRdoService.ts`
- `apps/web/src/features/stavia/staviaSnapshotStorage.ts`

Controles observados:

- `calcularSobraMaterial` retorna sobra informada quando presente; caso contrario calcula `quantidadeUsinada - quantidadeAplicada`.
- `calcularControleGeometrico` calcula media de espessuras, area, volume e massa somente quando os insumos existem.
- `calculatedLengthFromKm` retorna `null` quando inicio/fim estao ausentes ou quando `kmFinal < kmInicial`.
- `staviaSnapshotStorage` preserva valores persistidos quando ja existem e so calcula derivados por fallback quando ha base suficiente.
- Dado ausente permanece `null`/indisponivel; nao foi identificado caminho transformando ausencia em zero nos calculos revisados.

Evidencia automatizada: `localRdoService.test.ts`, `staviaLocalEngine.test.ts` e os testes de painel/ontologia passaram no lote web focado.

## Seguranca

Threat model persistido em:

```text
/var/folders/vd/ww6lt79d0hq12dqd8f9xqt2h0000gn/T/codex-security-scans/digitalizacao-rdo-stavias/threat_model.md
```

Controles validados:

- `JwtAuthFilter` protege `/api/**` exceto `/api/auth/login`, `/api/auth/cpf-filter`, `/api/health` e preflight `OPTIONS`.
- Snapshot StavIA, consultas StavIA e integracoes ficam atras de JWT; integracoes exigem admin por `CurrentUserService.requireAdmin()`.
- Sync device ownership e pull/ack foram cobertos por testes e pelo smoke.
- ACL por obra/RDO existe em `CurrentUserService.requireWorksiteAccess` e `requireRdoAccess`.
- CPF offline usa Bloom filter derivado da Academy, nao lista de CPFs em claro.
- Sessao offline criada no browser nao tem token JWT.
- Fontes Academy/Zeladoria foram testadas em modo read-only; sync escreveu apenas no MySQL descartavel do smoke.

Riscos residuais:

- Medio: `localStorage` contem filtro Bloom e sessao offline. Isso e inerente ao requisito offline, mas exige disciplina de dispositivo/logout.
- Medio: CORS local permite origens de rede privada e `allowPrivateNetwork(true)`. Bom para campo/local, mas deve ser estreitado em producao nao-local.
- Medio: futuras regras de PWA nao devem cachear payloads autenticados, snapshots sensiveis ou anexos privados. O build atual validado precacheia o shell.
- Baixo: a sessao offline nao tem JWT e nao autoriza API ao reconectar; ainda assim a UX deve comunicar que e acesso local do dispositivo.
- Baixo: o tick automatico do scheduler nao foi forcado no smoke para evitar duplicar leitura externa; o bean, flags e caminho manual compartilhado foram validados.

## Achados e correcoes

Falhas criticas: nenhuma confirmada.

Correcoes aplicadas no codigo de produto: nenhuma. A unica mudanca no repo e este relatorio. A validacao tambem criou um artefato temporario de threat model fora do repo.

Observacoes de execucao:

- O Browser MCP interno nao estava disponivel; a prova browser foi feita via Edge/CDP.
- A primeira tentativa do script CDP falhou por detalhes do wrapper de automacao, nao por falha do app. A execucao final passou.
- O smoke de integracoes usou banco descartavel para evitar impacto no banco principal.

## Conclusao

O sistema foi validado como funcional no caminho offline principal: build PWA, service worker, login offline, IndexedDB, sync local/remoto, snapshot StavIA e respostas offline por `responderComSnapshotStavia(...)`. API, web, ontologia, calculos e integracoes passaram nos testes e smokes executados. Nao ha ampliacao obrigatoria de ontologia nesta passada porque os 153 atributos atuais estao em paridade backend/web e cobertos pelo caminho offline real.
