# Checkpoint Técnico: Validação da Integração PDOC

Data da sessão: 2026-06-22  
Raiz do repositório: `/Users/joaolucas/digitalizacao-rdo-stavias`  
Branch atual: `develop`

## 1. Objetivo da Etapa

O objetivo original da integração do PDOC era iniciar o fluxo end-to-end com dados reais do Córtex, preservando o núcleo matemático existente em `PdocEngine` e o normalizador `PdocContextBuilder`, adicionando snapshots imutáveis, serviço de aplicação, endpoints HTTP e validação inicial para a obra `CW38386`.

O escopo desta sessão foi estabilizar, revisar e validar o código já criado. O foco foi compilar, corrigir bugs de integração, revisar migration, idempotência, identificação da obra, comportamento de dados insuficientes, transações e cobertura de testes.

Ficou explicitamente fora do escopo: PWA/frontend, Stav.IA, Mapbox, alertas, scheduler/agendamento, histórico visual, novas fórmulas do motor, calibração histórica real e qualquer simulação de dados financeiros ausentes.

Estado esperado ao final: backend compila, testes passam, V16 foi validada contra MySQL 8.4 em banco temporário compatível, snapshots `SUCCESS`/`INSUFFICIENT_DATA` têm comportamento coberto por teste, idempotência é determinística, e a próxima sessão pode continuar estabilização sem repetir auditoria ampla.

## 2. Estado Atual do Repositório

Branch atual:

```text
develop
```

Caminho da raiz:

```text
/Users/joaolucas/digitalizacao-rdo-stavias
```

`git status --short` antes deste checkpoint:

```text
 M apps/api/src/main/java/com/projeto/cortex/obras/ObraRepository.java
?? apps/api/src/main/java/com/projeto/cortex/pdoc/
?? apps/api/src/main/resources/db/migration/V16__create_pdoc_snapshot.sql
?? apps/api/src/test/java/com/projeto/cortex/pdoc/
```

Arquivos modificados:

- `apps/api/src/main/java/com/projeto/cortex/obras/ObraRepository.java`

Arquivos novos não rastreados:

- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocApplicationService.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocController.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocDataAvailability.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocErrorResponse.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocExceptionHandler.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocExecutionStatus.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocHistoricoResponse.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocInputBundle.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocInputLoader.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocInputOrigin.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocResultadoResponse.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocSnapshot.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocSnapshotRepository.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocTriggerType.java`
- `apps/api/src/main/java/com/projeto/cortex/pdoc/RealPdocInputLoader.java`
- `apps/api/src/main/resources/db/migration/V16__create_pdoc_snapshot.sql`
- `apps/api/src/test/java/com/projeto/cortex/pdoc/PdocApplicationServiceTest.java`
- `apps/api/src/test/java/com/projeto/cortex/pdoc/PdocMigrationTest.java`
- `apps/api/src/test/java/com/projeto/cortex/pdoc/PdocSnapshotRepositoryTest.java`
- `apps/api/src/test/java/com/projeto/cortex/pdoc/PdocSpringContextTest.java`
- `docs/checkpoints/pdoc-integration-validation-checkpoint.md`

Migrations existentes:

- `V1__create_asset_registry.sql`
- `V2__create_cadastro_colaboradores.sql`
- `V3__add_cpf_to_colaborador.sql`
- `V4__create_obras.sql`
- `V5__create_programacao_operacional.sql`
- `V6__add_chave_negocio_to_programacao_operacional.sql`
- `V7__add_unique_index_to_programacao_chave_negocio.sql`
- `V8__create_rdo.sql`
- `V9__create_cortex_operational_memory.sql`
- `V10__create_offline_sync_queue.sql`
- `V11__refine_offline_sync_queue.sql`
- `V12__refine_cortex_operational_memory.sql`
- `V13__add_sync_mutation_result_payloads.sql`
- `V14__refine_sync_event_reference_and_charset.sql`
- `V15__backfill_cortex_related_objects.sql`
- `V16__create_pdoc_snapshot.sql`

Versões atuais:

- PDOC: `PDOC-0.2.0`
- Premissas: `PDOC-ASSUMPTIONS-0.2.0`

## 3. Arquitetura Encontrada

`PdocEngine` fica em:

- `apps/api/src/main/java/com/projeto/cortex/intelligence/PdocEngine.java`

`PdocContextBuilder` fica em:

- `apps/api/src/main/java/com/projeto/cortex/intelligence/PdocContextBuilder.java`

Entidades e tabelas reais:

- Obra: entidade `Obra`, repository `ObraRepository`, tabela `obra`.
- Programação: tabela `programacao_operacional`.
- RDO: tabelas `rdo`, `rdo_material`, `rdo_equipamento`, `rdo_controle_geometrico`.
- Produção: derivável de `programacao_operacional` e, quando houver RDO real, `rdo_controle_geometrico`.
- Custos: não há tabela/campo real nas migrations atuais para orçamento aprovado, custo realizado ou custo comprometido.
- Eventos: `cortex_evento_operacional`, `cortex_objeto`, `cortex_relacao`, `cortex_estado_entidade`.
- Sincronização: `sync_dispositivo`, `sync_mutacao_cliente`, `sync_estado_dispositivo`.

Identificação de obras:

- `obra.id` (`CHAR(36)`, UUID textual).
- `obra.codigo_contrato` com unique constraint.
- `obra.codigo_cw` indexado, mas não único.
- `obra.codigo_interno` sem unique constraint explícita.
- Busca atual aceita UUID/códigos e limita a `arquivado_em IS NULL`.
- Ambiguidade é tratada no serviço com `409 CONFLICT`; não seleciona arbitrariamente.

Estrutura de packages:

- Núcleo matemático: `com.projeto.cortex.intelligence`.
- Integração HTTP/persistência PDOC: `com.projeto.cortex.pdoc`.
- Obras: `com.projeto.cortex.obras`.
- Stav.IA existente: `com.projeto.cortex.intelligence.stavia`.

Padrões:

- Repositories existentes usam Spring Data JPA para entidades principais e JDBC para leituras agregadas/operacionais.
- O novo `PdocSnapshotRepository` usa `JdbcTemplate`, porque snapshot é record de persistência, não entidade JPA.
- Services são classes Spring com orquestração de caso de uso.
- Controllers são `@RestController` com DTOs records.
- Erros HTTP usam `ResponseStatusException` e handler dedicado em português.
- Testes usam JUnit 5, AssertJ, Mockito e `ApplicationContextRunner`. Não foram adicionadas dependências novas nem Testcontainers.

## 4. Dados Reais Disponíveis e Ausentes

Dados ausentes não podem ser transformados silenciosamente em zero. Quando o dado é obrigatório para previsão financeira, a ausência leva a `INSUFFICIENT_DATA` e os campos calculados ficam nulos.

| Input PDOC | Fonte no banco | Tabela | Coluna ou cálculo | Classificação | Tratamento implementado | Limitações |
|---|---|---|---|---|---|---|
| Orçamento total aprovado | Não existe | N/A | N/A | Ausente | `approvedBudget = null`, obrigatório, warning, causa `INSUFFICIENT_DATA` | Sem tabela/campo financeiro nas migrations |
| Custo realizado | Não existe | N/A | N/A | Ausente | `actualCost = null`, obrigatório, warning, causa `INSUFFICIENT_DATA` | Sem medição/custo real |
| Custo comprometido | Não existe | N/A | N/A | Ausente | `committedCost = null`, obrigatório, warning, causa `INSUFFICIENT_DATA` | Não estimar por proxy |
| Avanço físico | Programação + RDO | `programacao_operacional`, `rdo_controle_geometrico` | produção real / quantidade planejada | Derivável se houver RDO real | Usa quantidades escolhidas por métrica | Sem RDO real pode ficar ausente |
| Avanço financeiro | Custos | N/A | custo realizado / orçamento aprovado | Ausente | Não calculado sem financeiros | Não converter para zero |
| Prazo previsto | Programação | `programacao_operacional` | `MIN(data_programacao)`, `MAX(data_programacao)` | Derivável | Entra em inputs informativos | Não é cronograma contratual validado |
| Prazo decorrido | Programação + data referência | `programacao_operacional` | dias entre primeira data e referência | Derivável | Usado para produtividade | Depende da data referência resolvida |
| Produção planejada | Programação | `programacao_operacional` | soma de `area_m2`, `volume_m3` ou `extensao_m` | Derivável | Escolhe área, depois volume, depois extensão | Linhas incompletas geram warning |
| Produção real | RDO | `rdo_controle_geometrico` | soma de `area_m2`, `volume_m3`, `massa_tonelada` | Derivável/ausente | Só usa quando positiva | Seeds podem não ter RDOs reais |
| RDOs | RDO | `rdo` | contagem e datas por obra | Disponível se houver registros | Entra como `rdoRows`, atrasos e origem | Sem seeds de RDO para `CW38386` na base inicial |
| Ocorrências | Observações de RDO | `rdo.observacoes` | contagem de observações não vazias | Ambíguo | Warning de ambiguidade, não severidade formal | Não há tabela de ocorrência/criticidade |
| Horas de equipamentos | RDO equipamento | `rdo_equipamento` | `TIMESTAMPDIFF(hora_inicio, hora_fim) * quantidade` | Ambíguo | Usado como referência operacional | Não é baseline planejado validado |
| Paralisações | Não estruturado | `rdo_equipamento` | N/A | Ausente | `equipmentDowntimeHours30d = null`/0 no source interno somente se não calcular | Não há status/campo de parada |
| Materiais previstos/reais | RDO material | `rdo_material` | soma `quantidade_prevista` e `quantidade_aplicada` | Derivável se ambos positivos | Entra se disponível, warning se incompleto | Unidades diferentes não normalizadas |
| Produtividade | Programação + RDO | `programacao_operacional`, `rdo_controle_geometrico` | quantidade/dias decorridos | Derivável | Entra se planejado e real positivos | Proxy operacional, não medição validada |
| RDOs atrasados | Programação + RDO | `programacao_operacional`, `rdo` | datas programadas sem RDO | Derivável | Contagem operacional | Depende de correspondência por data |
| Eventos sync pendentes | Sync | `sync_mutacao_cliente` | status `PENDENTE` ligado à obra/RDO | Disponível | Entra em inputs | Ligação depende de `entidade_id` |
| Horas desde último sync | Sync | `sync_mutacao_cliente` | `TIMESTAMPDIFF(HOUR, MAX(...), now)` | Derivável/ausente | Warning quando ausente | Usa relógio do banco |
| Mudanças de escopo | Não estruturado | N/A | N/A | Ausente | Não implementado como input específico | Pode surgir futuramente de eventos/relacões |

## 5. Informações Encontradas Sobre CW38386

Como a obra foi localizada:

- Seed em `data/seeds/obras_seed.csv`.
- Programações em `data/seeds/programacoes_seed.csv`.
- Busca no código deve aceitar `CW38386` via `codigo_contrato`, `codigo_cw`, `codigo_interno` ou `id`.

Identificadores encontrados:

- `codigo_contrato`: `CW38386`
- `codigo_cw`: vazio/nulo no seed informado
- `codigo_interno`: `4ª Intervenção`
- Nome da obra: `4ª Intervenção`

Dados de seed/programação:

- Quantidade de programações: 172.
- Período: `2025-12-10` a `2026-06-08`.
- Área planejada aproximada: `135744.718 m²`.
- Volume planejado aproximado: `12564.478 m³`.
- Registros incompletos: 25 linhas incompletas.

RDOs encontrados:

- No checkpoint anterior e na auditoria, não havia RDOs reais nos seeds para `CW38386`.
- O loader está preparado para ler `rdo` e tabelas filhas caso existam em ambiente real.

Dados financeiros encontrados:

- Nenhum campo/tabela real de orçamento aprovado, custo realizado ou custo comprometido foi encontrado nas migrations V1-V15.

Dados ainda insuficientes:

- Orçamento total aprovado.
- Custo realizado.
- Custo comprometido.
- Produção real por RDO, quando o ambiente não tiver RDOs reais.
- Paralisações estruturadas de equipamentos.
- Ocorrências com severidade formal.

Comportamento esperado ao tentar calcular o PDOC:

- Para `CW38386`, com os dados conhecidos de seed e sem financeiros reais, `POST /api/obras/CW38386/pdoc/calcular` deve persistir snapshot `INSUFFICIENT_DATA`.
- Percentis, EACs, probabilidades, score e confiança devem ficar nulos.
- Warnings devem indicar financeiros ausentes e lacunas operacionais.
- O snapshot deve entrar no histórico e pode ser o resultado atual.

## 6. Decisões Arquiteturais Tomadas

| Decisão | Justificativa | Estado |
|---|---|---|
| Preservar `PdocEngine` | Núcleo matemático isolado, já testado, sem Spring/banco | Implementada e validada por testes existentes |
| Preservar `PdocContextBuilder` | Normaliza dados antes do motor | Implementada e validada por testes existentes |
| Criar `com.projeto.cortex.pdoc` | Separar integração HTTP/persistência do núcleo | Implementada, compila |
| Separar loader/service/repository/controller/DTOs | Reduz acoplamento entre fonte de dados, orquestração e API | Implementada, parcialmente validada |
| Snapshots imutáveis | Auditoria/histórico e idempotência | Implementada por insert-only, validada por teste |
| Idempotência por SHA-256 | Repetir mesmas entradas retorna mesmo snapshot | Implementada e validada por teste |
| Unique constraint final | Proteção contra concorrência | Implementada em V16 e validada por teste fake de corrida |
| `INSUFFICIENT_DATA` | Ausência financeira não vira zero/simulação | Implementada e validada por teste |
| JSON + colunas tipadas | JSON preserva inputs/origens; colunas tipadas facilitam consulta | Implementada em V16, validada por teste e MySQL |
| Obra por UUID ou código | Ergonomia para API e operação | Implementada e validada por teste |
| Ambiguidade gera erro | Evita selecionar obra arbitrariamente | Implementada e validada por teste |
| Erros em português | API visível ao usuário/operador | Implementada e parcialmente validada |
| Sem `@Transactional` no fluxo | Evita rollback-only trap nesta etapa; insert único por JDBC | Implementada por ausência de transação explícita; exige revisão futura |
| Textos visíveis em português | Consistência do produto | Implementada nos DTOs/erros principais |

## 7. Arquivos Criados e Alterados

| Arquivo | Estado | Responsabilidade | Mudanças principais | Dependências | Riscos | Compila | Testes |
|---|---|---|---|---|---|---|---|
| `ObraRepository.java` | Modificado | Repository JPA de obra | Adiciona `findAtivasByIdentificador` retornando lista | JPA/Obra | Limita a não arquivadas; códigos não únicos | Sim | Ambiguidade/UUID/código no service test |
| `V16__create_pdoc_snapshot.sql` | Criado | Schema de snapshots PDOC | Cria `pdoc_snapshot`, FKs, JSON, índices, unique | MySQL/Flyway | Collation depende de compatibilidade com migrations antigas | Sim em MySQL 8.4 | `PdocMigrationTest` + validação MySQL |
| `PdocApplicationService.java` | Criado | Orquestra caso de uso | Localiza obra, carrega inputs, idempotência, cálculo, snapshot, DTO | ObraRepository, loader, repository, engine | Classe grande; sem transação explícita | Sim | `PdocApplicationServiceTest` |
| `PdocController.java` | Criado | Endpoints HTTP | POST calcular, GET atual, GET histórico | Service | Sem teste MVC real | Sim | Retorno DTO validado por reflection |
| `PdocDataAvailability.java` | Criado | Enum disponibilidade | Labels em português | Nenhuma | Baixo | Sim | Coberto via DTO/origem |
| `PdocErrorResponse.java` | Criado | DTO de erro | Erro em português | Nenhuma | Baixo | Sim | Parcial via handler não exercitado por MVC |
| `PdocExceptionHandler.java` | Criado | Handler de exceções | Mapeia `ResponseStatusException` e `IllegalArgumentException` | Controller | Sem teste HTTP completo | Sim | Spring context |
| `PdocExecutionStatus.java` | Criado | Enum status | `SUCCESS`, `INSUFFICIENT_DATA`, `FAILED` | V16 | Baixo | Sim | DTO status |
| `PdocHistoricoResponse.java` | Criado | DTO histórico | Paginação | DTO resultado | Baixo | Sim | Histórico paginado |
| `PdocInputBundle.java` | Criado | Inputs/origens/source values | Converte para `PdocSourceSnapshot` só se calculável | Builder | Usa doubles no source interno | Sim | Service tests |
| `PdocInputLoader.java` | Criado | Contrato de loader | Interface | Obra | Baixo | Sim | Mock/fake tests |
| `PdocInputOrigin.java` | Criado | Metadados de origem | Mapa serializável | Enum availability | Baixo | Sim | DTO origem |
| `PdocResultadoResponse.java` | Criado | DTO de resultado | Labels português, campos calculados e JSON | Snapshot | EAC map com nulos em insuficiência | Sim | DTO tests |
| `PdocSnapshot.java` | Criado | Record de persistência | Campos do snapshot | Jackson JsonNode | Não é entidade JPA | Sim | Indireto |
| `PdocSnapshotRepository.java` | Criado | Persistência JDBC | Insert/select/count/history | JdbcTemplate, ObjectMapper | SQL real só parcialmente testado | Sim | Repository test |
| `PdocTriggerType.java` | Criado | Enum disparo | Parse case-insensitive | Controller | Baixo | Sim | Indireto |
| `RealPdocInputLoader.java` | Criado | Loader real via JDBC | Agrega programação/RDO/material/equipamento/sync | JdbcTemplate | Classe muito grande; precisa testes diretos | Sim | Ainda sem teste direto com DB |
| `PdocApplicationServiceTest.java` | Criado | Testes do serviço | Válido, insuficiente, idempotência, concorrência, histórico | Mockito/fakes | Fake repository não substitui DB real | Sim | Passando |
| `PdocMigrationTest.java` | Criado | Contrato estático V16 | Verifica colunas/índices/FKs/JSON | Files | Não executa Flyway | Sim | Passando |
| `PdocSnapshotRepositoryTest.java` | Criado | Contrato insert JDBC | Garante JSON sem `CAST` | Capturing JdbcTemplate | Não lê de MySQL real | Sim | Passando |
| `PdocSpringContextTest.java` | Criado | Wiring Spring PDOC | Controller/service/handler sobem | ApplicationContextRunner | Slice com mocks | Sim | Passando |
| `docs/checkpoints/pdoc-integration-validation-checkpoint.md` | Criado | Continuidade técnica | Este checkpoint | Nenhuma | Pode ficar desatualizado se houver novas mudanças | N/A | N/A |

## 8. Migration V16

Tabela: `pdoc_snapshot`.

Colunas:

| Coluna | Tipo | Nulo | Default | Observação |
|---|---:|---:|---:|---|
| `id` | `CHAR(36)` | Não | Nenhum | PK |
| `obra_id` | `CHAR(36)` | Não | Nenhum | FK para `obra(id)` |
| `codigo_obra` | `VARCHAR(80)` | Não | Nenhum | Código denormalizado |
| `executado_em` | `DATETIME(6)` | Não | Nenhum | Data/hora lógica da execução |
| `data_referencia` | `DATE` | Não | Nenhum | Data dos inputs |
| `versao_modelo` | `VARCHAR(40)` | Não | Nenhum | `PDOC-0.2.0` |
| `versao_premissas` | `VARCHAR(80)` | Não | Nenhum | `PDOC-ASSUMPTIONS-0.2.0` |
| `status_execucao` | `VARCHAR(40)` | Não | Nenhum | CHECK `SUCCESS`, `INSUFFICIENT_DATA`, `FAILED` |
| `tipo_disparo` | `VARCHAR(40)` | Não | Nenhum | CHECK `MANUAL`, `EVENT`, `SCHEDULED`, `API` |
| `evento_origem_id` | `CHAR(36) ascii_bin` | Sim | `NULL` | FK opcional para evento |
| `chave_idempotencia` | `CHAR(64) ascii_bin` | Não | Nenhum | SHA-256 hex |
| `inputs_json` | `JSON` | Não | Nenhum | Inputs efetivamente usados |
| `origem_inputs_json` | `JSON` | Não | Nenhum | Origem/disponibilidade |
| `warnings_json` | `JSON` | Não | Nenhum | Lista de warnings |
| `modo_calculo` | `VARCHAR(40)` | Sim | `NULL` | Modo do engine |
| `calibracao` | `VARCHAR(40)` | Sim | `NULL` | Status de calibração |
| `fase_obra` | `VARCHAR(40)` | Sim | `NULL` | Fase calculada |
| `nivel_risco` | `VARCHAR(40)` | Sim | `NULL` | Risco |
| `p10_custo` | `DECIMAL(18,2)` | Sim | `NULL` | Percentil |
| `p50_custo` | `DECIMAL(18,2)` | Sim | `NULL` | Percentil / estimativa final |
| `p80_custo` | `DECIMAL(18,2)` | Sim | `NULL` | Percentil |
| `p95_custo` | `DECIMAL(18,2)` | Sim | `NULL` | Percentil |
| `eac_cpi` | `DECIMAL(18,2)` | Sim | `NULL` | EAC |
| `eac_cpi_spi` | `DECIMAL(18,2)` | Sim | `NULL` | EAC |
| `eac_bottom_up` | `DECIMAL(18,2)` | Sim | `NULL` | EAC |
| `eac_ponderado` | `DECIMAL(18,2)` | Sim | `NULL` | EAC |
| `cpi` | `DECIMAL(12,6)` | Sim | `NULL` | Métrica EVM |
| `spi` | `DECIMAL(12,6)` | Sim | `NULL` | Métrica EVM |
| `prob_qualquer_excedente` | `DECIMAL(9,6)` | Sim | `NULL` | Probabilidade |
| `prob_exceder_5_pct` | `DECIMAL(9,6)` | Sim | `NULL` | Probabilidade |
| `prob_exceder_10_pct` | `DECIMAL(9,6)` | Sim | `NULL` | Probabilidade |
| `score_heuristico` | `DECIMAL(9,6)` | Sim | `NULL` | Score |
| `confianca` | `DECIMAL(9,6)` | Sim | `NULL` | Confiança |
| `simulacao_convergiu` | `TINYINT(1)` | Sim | `NULL` | Boolean |
| `iteracoes_simulacao` | `INT` | Sim | `NULL` | Iterações |
| `drivers_json` | `JSON` | Não | Nenhum | Drivers array |
| `erro_execucao` | `TEXT` | Sim | `NULL` | Erro/falta de dados |
| `criado_em` | `DATETIME(6)` | Não | `CURRENT_TIMESTAMP(6)` | Data de criação |

Primary key:

- `PRIMARY KEY (id)`

Unique constraints:

- `uq_pdoc_snapshot_chave_idempotencia UNIQUE (chave_idempotencia)`

Foreign keys:

- `fk_pdoc_snapshot_obra`: `obra_id -> obra(id) ON DELETE RESTRICT`
- `fk_pdoc_snapshot_evento_origem`: `evento_origem_id -> cortex_evento_operacional(id) ON DELETE RESTRICT`

Índices:

- `idx_pdoc_snapshot_obra_execucao (obra_id, executado_em, criado_em, id)`
- `idx_pdoc_snapshot_obra_referencia (obra_id, data_referencia)`
- `idx_pdoc_snapshot_codigo_obra_execucao (codigo_obra, executado_em)`
- `idx_pdoc_snapshot_status (status_execucao, executado_em)`

Validação:

- Aplicada com sucesso em MySQL 8.4 via `compose.local.yml`, em banco temporário com `DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`.
- Primeira tentativa com banco temporário `utf8mb4_0900_ai_ci` falhou em V8, antes da V16, por incompatibilidade de FK entre migrations antigas. Isso não foi corrigido porque migrations antigas não devem ser editadas.
- V16 pode ser editada diretamente enquanto não houver evidência de aplicação em ambiente compartilhado. Não há evidência nesta sessão de que V16 já tenha sido aplicada em ambiente compartilhado.

## 9. Fluxo Completo Implementado

Cálculo válido:

1. `POST /api/obras/{obraId}/pdoc/calcular`.
2. Controller parseia `dataReferencia`, `tipoDisparo`, `eventoOrigemId`.
3. Service localiza obra por UUID/códigos ativos.
4. Loader real agrega dados operacionais.
5. Service monta payload de idempotência e calcula SHA-256.
6. Busca snapshot existente por chave.
7. Se não existir e `inputs.canCalculate() == true`, monta `PdocSourceSnapshot`.
8. `PdocContextBuilder` cria `PdocContext`.
9. `PdocEngine` calcula resultado.
10. Service monta snapshot `SUCCESS`.
11. Repository insere em `pdoc_snapshot`.
12. DTO `PdocResultadoResponse` é retornado.

Dados insuficientes:

1. Loader registra financeiros obrigatórios ausentes.
2. `missingRequiredFields` não vazio.
3. Service não chama builder nem engine.
4. Monta snapshot `INSUFFICIENT_DATA`.
5. Campos de previsão ficam nulos.
6. Repository persiste snapshot.
7. DTO retorna status e warnings em português.

Obra inexistente:

1. `findAtivasByIdentificador` retorna lista vazia.
2. Service lança `ResponseStatusException` `404 NOT_FOUND`.
3. Handler retorna `PdocErrorResponse` em português.

Snapshot duplicado:

1. Chave já existe.
2. Service retorna snapshot existente.
3. `snapshotExistente = true`.

Corrida concorrente:

1. Duas requisições geram mesma chave.
2. Ambas podem não encontrar snapshot inicialmente.
3. Uma insere.
4. A outra recebe `DuplicateKeyException`.
5. Service busca por chave e retorna o existente.
6. Exceção interna não escapa se o snapshot existir.

Falha inesperada:

- Falha durante builder/engine vira snapshot `FAILED`.
- Falhas antes da construção do snapshot, como obra inexistente ou erro de loader/DB, ainda podem escapar como erro HTTP/infra e não registram tentativa. Isso é risco residual.

## 10. Idempotência

Algoritmo:

- SHA-256 sobre JSON canônico em UTF-8.
- Saída hex lowercase de 64 caracteres.

Campos incluídos no hash:

- `obraId`
- `referenceDate`
- `modelVersion`
- `assumptionsVersion`
- `inputs`
- `inputAvailability`
- `missingRequiredFields`

Inputs incluídos:

- `approvedBudget`
- `actualCost`
- `committedCost`
- `totalPlannedQuantity`
- `plannedExecutedQuantity`
- `actualExecutedQuantity`
- `expectedMaterialConsumption`
- `actualMaterialConsumption`
- `expectedProductivity`
- `actualProductivity`
- `equipmentDowntimeHours30d`
- `plannedEquipmentHours30d`
- `delayedRdos`
- `criticalOccurrences`
- `pendingSyncEvents`
- `hoursSinceLastSync`
- `referenceDate`
- `quantityMetric`
- `programacaoRows`
- `rdoRows`
- `scheduleStartDate`
- `scheduleEndDate`

Serialização/canonicalização:

- Propriedades de mapas são ordenadas por chave (`TreeMap`).
- Listas são canonicalizadas e ordenadas por representação JSON.
- `LocalDate`/datas usam `toString()` ISO.
- `BigDecimal` usa `stripTrailingZeros().toPlainString()`.
- Timestamps voláteis como `executado_em` e `criado_em` não entram no hash.
- Versão do modelo e versão das premissas entram no hash.

Proteção no banco:

- `UNIQUE (chave_idempotencia)`.

Concorrência:

- `DuplicateKeyException` no insert é capturada.
- Service recupera snapshot existente pela chave.
- Pendente: teste de concorrência real com threads/banco; há teste de unique race via fake repository.

## 11. Comportamento de `INSUFFICIENT_DATA`

Campos obrigatórios que causam status:

- `approvedBudget`
- `actualCost`
- `committedCost`
- `totalPlannedQuantity`
- `plannedExecutedQuantity`
- `actualExecutedQuantity`

No estado real conhecido, os três financeiros causam insuficiência.

Warnings gerados:

- Orçamento aprovado ausente.
- Custo realizado ausente.
- Custo comprometido ausente.
- Quantidades incompletas, quando houver.
- Ausência de RDO, material, produtividade, equipamento/sync conforme o loader encontra lacunas.

Campos nulos no snapshot/DTO:

- `p10`, `p50`, `p80`, `p95`
- EACs `cpi`, `cpiSpi`, `bottomUp`, `ponderado`
- Probabilidades
- `scoreHeuristico`
- `confianca`
- `simulacaoConvergiu`
- `iteracoesSimulacao`

DTO:

- `statusExecucao = INSUFFICIENT_DATA`
- `statusExecucaoLabel = Dados insuficientes`
- `erroExecucao` com texto em português.
- `warnings` e `origemDados` expostos como JSON.

HTTP:

- `POST calcular` retorna resposta normal com snapshot, não erro HTTP, quando a obra existe mas faltam dados.
- Snapshot entra no histórico.
- Pode ser resultado atual.
- Mesma entrada insuficiente retorna snapshot existente.
- Novos dados alteram inputs/hash e permitem nova execução.

## 12. Endpoints

### `POST /api/obras/{obraId}/pdoc/calcular`

- Implementado: sim.
- Compilado: sim.
- Testado: service-level; sem teste MVC real.
- Path param: `obraId`, aceita UUID textual, `codigo_contrato`, `codigo_cw` ou `codigo_interno`.
- Query params:
  - `dataReferencia` opcional, ISO date.
  - `tipoDisparo` opcional: `MANUAL`, `EVENT`, `SCHEDULED`, `API`.
  - `eventoOrigemId` opcional.
- Request body: nenhum.
- Response DTO: `PdocResultadoResponse`.
- HTTP:
  - `200` com snapshot `SUCCESS`, `INSUFFICIENT_DATA` ou existente.
  - `400` para identificador vazio ou tipo inválido.
  - `404` para obra inexistente.
  - `409` para identificador ambíguo.
- Exemplo resumido `INSUFFICIENT_DATA`:

```json
{
  "statusExecucao": "INSUFFICIENT_DATA",
  "statusExecucaoLabel": "Dados insuficientes",
  "custoEstimadoFinal": null,
  "p50": null,
  "probabilidadeExceder5Pct": null,
  "erroExecucao": "Dados insuficientes para calcular o PDOC. Campos ausentes: approvedBudget, actualCost, committedCost."
}
```

### `GET /api/obras/{obraId}/pdoc/atual`

- Implementado: sim.
- Compilado: sim.
- Testado: service-level.
- Retorna último snapshot por `executado_em DESC, criado_em DESC, id DESC`.
- `404` se obra existe mas não há snapshot.

### `GET /api/obras/{obraId}/pdoc/historico`

- Implementado: sim.
- Compilado: sim.
- Testado: service-level.
- Query params:
  - `page`, default `0`, não pode ser negativo.
  - `size`, default `20`, deve ficar entre `1` e `100`.
- Response DTO: `PdocHistoricoResponse`.
- Ordenação: `executado_em DESC, criado_em DESC, id DESC`.

## 13. Build e Testes

Comandos executados em ordem:

1. `./mvnw test`
   - Resultado: falha de compilação.
   - Horário no log: `2026-06-22T15:51:00-03:00`.
   - Erro: `PdocApplicationService` chamava `baseSnapshot` com quantidade errada de argumentos; `ArrayNode` caía onde era esperado `Integer`.
   - Correção: inserir argumento `null` faltante para `iteracoes_simulacao` nos snapshots `INSUFFICIENT_DATA` e `FAILED`.

2. `./mvnw test`
   - Resultado: compilou, mas falhou em teste existente.
   - Horário no log: `2026-06-22T15:51:25-03:00`.
   - Testes: `96`, falhas `0`, erros `1`.
   - Erro: Mockito/Byte Buddy não suporta Java 25 (`Java 25 (69) is not supported by the current version of Byte Buddy`).
   - Causa: ambiente usando JDK 25, enquanto o `pom.xml` declara Java 21.
   - Correção operacional: executar com JDK 21 instalado.

3. `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw test`
   - Resultado: sucesso.
   - Horário no log: `2026-06-22T15:51:50-03:00`.
   - Testes: `96`, falhas `0`, erros `0`, ignorados `0`.
   - Warning relevante: Mockito carrega agente Java dinamicamente; warning não bloqueia.

4. `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw test`
   - Após adicionar testes/correções.
   - Resultado inicial: falha.
   - Testes: `110`, falhas `2`.
   - Falhas:
     - Comparação de `BigDecimal` por escala no teste.
     - `PdocApplicationService` sem construtor inequívoco para Spring no slice test.
   - Correções:
     - Ajustar teste para `isEqualByComparingTo`.
     - Adicionar `@Autowired` ao construtor público do service.

5. `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw test`
   - Resultado: sucesso.
   - Horário no log: `2026-06-22T15:59:02-03:00`.
   - Testes: `110`, falhas `0`, erros `0`, ignorados `0`.

6. Validação MySQL 8.4 com `docker compose -f compose.local.yml up -d cortex-mysql` e aplicação manual V1-V16.
   - Primeira tentativa com schema `utf8mb4_0900_ai_ci`: falhou em V8 por FK incompatível antes da V16.
   - Segunda tentativa com schema `utf8mb4_unicode_ci`: V1-V16 aplicaram; `SHOW CREATE TABLE pdoc_snapshot` confirmou tabela, FKs, JSON, CHECKs, índices.
   - Container foi desligado com `docker compose -f compose.local.yml down`.

7. `JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw clean verify`
   - Resultado: sucesso.
   - Horário no log: `2026-06-22T16:01:06-03:00`.
   - Testes: `110`, falhas `0`, erros `0`, ignorados `0`.
   - JAR gerado e repackaged pelo Spring Boot.

8. `git diff --check`
   - Resultado: sem saída.

## 14. Erros Encontrados e Correções

| Ordem | Erro | Causa raiz | Arquivo | Correção | Teste/evidência | Risco residual |
|---:|---|---|---|---|---|---|
| 1 | Compilação falhava em `baseSnapshot` | Argumento faltante nas chamadas | `PdocApplicationService.java` | Adicionado `null` antes de `drivers` | `./mvnw test` compila | Baixo |
| 2 | Mockito falhava no JDK 25 | Byte Buddy do Spring Boot 3.3.5 não suporta Java 25 | Ambiente | Usar JDK 21 | `./mvnw test` com JDK 21 passa | Médio se ambiente default seguir JDK 25 |
| 3 | Service poderia não inicializar no Spring | Dois construtores sem `@Autowired` | `PdocApplicationService.java` | `@Autowired` no construtor público | `PdocSpringContextTest` | Baixo |
| 4 | Obra ambígua podia gerar exceção interna | Query retornava `Optional` apesar de códigos não únicos | `ObraRepository.java`, service | Repository retorna lista; service gera `409` | `shouldRejectAmbiguousIdentifier` | Médio para obras arquivadas/históricas |
| 5 | Idempotência rasa | Ordenação só de mapa superior | `PdocApplicationService.java` | Canonicalização profunda | `shouldUseCanonicalIdempotencyPayload` | Baixo/médio; teste real DB concorrente pendente |
| 6 | JSON insert dependia de `CAST(? AS JSON)` | Sintaxe frágil para MySQL/JDBC | `PdocSnapshotRepository.java` | Parâmetros JSON normais | `PdocSnapshotRepositoryTest` | Baixo |
| 7 | Validação temporária falhou em V8 | Collation default `0900` incompatível com migrations antigas | Migrations antigas | Não alterar antigas; documentar risco | Validação com `unicode_ci` passa V1-V16 | Médio |

## 15. Testes Existentes e Faltantes

Implementados e passando:

- Inicialização do contexto Spring PDOC: `PdocSpringContextTest`.
- Migration V16: `PdocMigrationTest`.
- Snapshot válido: `PdocApplicationServiceTest.shouldCreateValidSnapshot`.
- Snapshot insuficiente: `shouldCreateInsufficientDataSnapshotWithoutSilentFinancialZero`.
- Ausência de zero silencioso: mesmo teste valida nulos financeiros e resultados nulos.
- Imutabilidade/histórico não sobrescrito: `shouldCreateNewSnapshotWhenInputChangesAndKeepHistoryImmutable`.
- Idempotência mesma entrada: `shouldReturnExistingSnapshotForSameInput`.
- Concorrência/unique simulada: `shouldRecoverExistingSnapshotWhenUniqueConstraintWinsRace`.
- Obra inexistente: `shouldReturnNotFoundForMissingWorksite`.
- UUID: `shouldAcceptUuidAndCodeIdentifiers`.
- Código: `shouldAcceptUuidAndCodeIdentifiers`.
- Ambiguidade: `shouldRejectAmbiguousIdentifier`.
- Resultado atual: `shouldReturnCurrentAndPagedHistoryOrderedByExecutionDate`.
- Histórico paginado: mesmo teste.
- Ordenação: mesmo teste.
- DTOs em português: status labels e warnings em service tests.
- JSON arrays/objetos: service/repository tests.
- Percentis nulos quando não houve cálculo: insufficient data test.
- Endpoint não expõe entidade: reflection em `controllerMethodsShouldNotExposePersistenceEntity`.

Implementados e falhando:

- Nenhum no último `./mvnw test` e `./mvnw clean verify` com JDK 21.

Ainda não implementados:

- Teste MVC real com `MockMvc` para rotas/status HTTP.
- Teste direto de `RealPdocInputLoader` contra base SQL real.
- Teste de concorrência real com banco e threads.
- Teste Flyway programático usando API Flyway.
- Teste com seed real `CW38386` carregado em banco.

## 16. Riscos Restantes

| Risco | Classificação | Avaliação |
|---|---|---|
| Compilação | Baixo | Passa com JDK 21; falha com JDK 25 por Mockito/Byte Buddy |
| SQL/MySQL V16 | Baixo | V16 aplicada em MySQL 8.4 com schema compatível |
| Flyway | Médio | Não houve execução via plugin/perfil Flyway; aplicação SQL manual validou sintaxe |
| Transações | Médio | Sem `@Transactional`; evita rollback-only, mas não registra falhas antes do snapshot |
| Rollback-only trap | Baixo | Não há captura dentro de transação marcada rollback-only no fluxo novo |
| Concorrência | Médio | Unique + catch testado por fake; falta teste real DB |
| Hash não determinístico | Baixo | Canonicalização profunda implementada e testada |
| Identificadores ambíguos | Médio | Tratado com `409`; falta teste JPA real |
| Obras inativas/arquivadas | Médio | Busca limita a `arquivadoEm IS NULL`; histórico futuro de obras concluídas pode exigir outro fluxo |
| JSON | Baixo | Campos JSON em V16 e serialização testados |
| Precisão financeira | Médio | DECIMAL definido; engine usa BigDecimal em custos, mas inputs reais ausentes |
| Nullability | Baixo | Campos calculados nullable para insuficiência |
| Exposição de entidades | Baixo | Controller retorna DTOs |
| Mensagens em inglês | Médio | DTO/erros principais em português; nomes de campos técnicos seguem inglês |
| Classes grandes | Médio | `RealPdocInputLoader` 804 linhas; `PdocApplicationService` 568 linhas |
| Responsabilidades misturadas | Médio | Loader mistura SQL, warnings e derivação; service mistura orquestração/idempotência/mapping |
| Ausência de dados financeiros | Alto | Impede previsão válida; comportamento correto é `INSUFFICIENT_DATA` |
| Ausência de RDOs reais | Médio | Reduz produção real e completude |
| Compatibilidade sync offline | Médio | Lê `sync_mutacao_cliente`; não integra com fila offline ou eventos novos |
| Collation das migrations antigas | Médio | V8 falha em schema novo `utf8mb4_0900_ai_ci`; não editar antigas |

## 17. Diff Resumido

`git diff --stat` no momento final antes deste checkpoint mostra apenas arquivos rastreados:

```text
 .../main/java/com/projeto/cortex/obras/ObraRepository.java  | 13 +++++++++++++
 1 file changed, 13 insertions(+)
```

Observação: os arquivos PDOC e testes estão untracked, então não aparecem em `git diff --stat` até serem adicionados ao índice.

Resumo por área:

- `ObraRepository`: adiciona busca por identificador retornando lista.
- `com.projeto.cortex.pdoc`: adiciona controller, service, loader, repository, DTOs, enums e handler.
- `V16`: adiciona tabela de snapshots.
- `src/test/java/com/projeto/cortex/pdoc`: adiciona testes de service, migration, repository e Spring context.
- `docs/checkpoints`: adiciona este checkpoint.

Mudanças ainda não revisadas:

- `RealPdocInputLoader` não tem teste direto com banco.
- Endpoints não têm teste MVC real.

Arquivos que não devem ser commitados:

- Nenhum temporário identificado.
- `target/` foi gerado por Maven, mas não aparece em `git status --short`.

## 18. Próximo Passo Exato

Próximo passo principal: estabilizar a validação real do módulo PDOC contra banco e endpoint, sem novas funcionalidades.

Arquivos a ler primeiro:

1. `docs/checkpoints/pdoc-integration-validation-checkpoint.md`
2. `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocApplicationService.java`
3. `apps/api/src/main/java/com/projeto/cortex/pdoc/RealPdocInputLoader.java`
4. `apps/api/src/main/java/com/projeto/cortex/pdoc/PdocSnapshotRepository.java`
5. `apps/api/src/main/resources/db/migration/V16__create_pdoc_snapshot.sql`
6. `apps/api/src/test/java/com/projeto/cortex/pdoc/PdocApplicationServiceTest.java`

Primeiro comando:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git status --short
```

Ordem de correção:

1. Confirmar estado do diff e arquivos untracked.
2. Rodar testes com JDK 21.
3. Se falhar, corrigir causa raiz.
4. Adicionar teste MVC ou loader real se necessário.
5. Validar V16/Flyway de forma reprodutível.

Condição objetiva de conclusão:

- `./mvnw test` e `./mvnw clean verify` passam com JDK 21.
- V16 validada contra MySQL ou via Flyway em ambiente local.
- Nenhuma previsão válida é retornada quando financeiros estão ausentes.
- Diff revisado e sem whitespace.

## 19. Comandos Para a Nova Sessão

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git status --short
git diff --check
git diff --stat

cd apps/api
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw test
JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home ./mvnw clean verify
```

Validação MySQL manual usada nesta sessão:

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
docker compose -f compose.local.yml up -d cortex-mysql

cd apps/api
DB="cortex_pdoc_validation_$(date +%s)"
MYSQL_PWD="$CORTEX_MYSQL_ROOT_PASSWORD" mysql -h 127.0.0.1 -P 3307 -uroot -e "DROP DATABASE IF EXISTS \`$DB\`; CREATE DATABASE \`$DB\` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
for n in $(seq 1 16); do
  f=$(ls src/main/resources/db/migration/V${n}__*.sql)
  echo "Applying $(basename "$f")"
  MYSQL_PWD="$CORTEX_MYSQL_ROOT_PASSWORD" mysql -h 127.0.0.1 -P 3307 -uroot "$DB" < "$f" || exit 1
done
MYSQL_PWD="$CORTEX_MYSQL_ROOT_PASSWORD" mysql -h 127.0.0.1 -P 3307 -uroot "$DB" -e "SHOW CREATE TABLE pdoc_snapshot;"
MYSQL_PWD="$CORTEX_MYSQL_ROOT_PASSWORD" mysql -h 127.0.0.1 -P 3307 -uroot -e "DROP DATABASE \`$DB\`;"

cd /Users/joaolucas/digitalizacao-rdo-stavias
docker compose -f compose.local.yml down
```

## 20. Restrições Para Continuidade

- Não reescrever `PdocEngine`.
- Não alterar fórmulas sem bug comprovado.
- Não inventar dados financeiros.
- Não usar zero silencioso.
- Não implementar PAW.
- O termo correto é PWA.
- Não implementar Stav.IA.
- Não implementar Mapbox.
- Não implementar alertas.
- Não implementar scheduler.
- Não implementar frontend.
- Não editar migrations antigas.
- Não avançar enquanto build e testes não passarem.
- Todo texto visível deve estar em português.
- Não adicionar Testcontainers ou dependências novas sem necessidade e justificativa.

## 21. Prompt Recomendado Para a Nova Sessão

```text
Leia AGENTS.md se existir no repositório e trate as instruções coladas na conversa como obrigatórias. Leia integralmente docs/checkpoints/pdoc-integration-validation-checkpoint.md. Confirme o estado pelo código real com git status --short, git diff --check e os arquivos mencionados no checkpoint, mas não repita toda a auditoria desde zero.

Não implemente novas funcionalidades. O objetivo é continuar a estabilização da integração PDOC já criada. Execute o build e os testes com JDK 21, corrija problemas pela causa raiz e valide especificamente migration V16, idempotência, transações e comportamento de dados insuficientes. Não reescreva PdocEngine/PdocContextBuilder, não invente dados financeiros, não use zero silencioso, não implemente PWA, Stav.IA, Mapbox, alertas, scheduler ou frontend.

Revise o diff ao final, apresente evidências objetivas dos comandos executados e deixe claro o que foi validado, o que não foi e quais riscos permanecem.
```
