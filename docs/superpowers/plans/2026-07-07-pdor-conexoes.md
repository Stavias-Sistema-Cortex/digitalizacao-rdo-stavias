# PDOR + Conexões Reais — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Renomear PDOC→PDOR remodelando o motor probabilístico para prever RECEITA final da obra, conectar obras à ontologia e eliminar valores inventados da Home (chips, links).

**Architecture:** Rename mecânico verificado por compilador (pacotes/classes/endpoints/tabela via migration V25 drop+create) seguido de remodelagem semântica dirigida por tabela de mapeamento custo→receita (inputs do loader, contexto/resultado do motor, direção do risco invertida). Ontologia via `CortexOperationalMemoryService` (padrão da Previsão Financeira). Web: rename do motor local da Stav.IA + configuração para chips/links.

**Tech Stack:** Spring Boot/JPA/JdbcTemplate/Flyway (API), React 19 + Vite + Vitest (web), JUnit/Mockito.

**Spec:** `docs/superpowers/specs/2026-07-07-pdor-conexoes-design.md` — ler antes.

## Global Constraints

- **JDK 21 para a API**: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` antes de qualquer `./mvnw`.
- **Commits sem trailer Co-Authored-By**, mensagens pt-BR.
- **NUNCA editar migrations existentes** (V1–V24 — checksum do Flyway). Mudança de schema só via **V25** nova.
- Ambiente dev: sem retrocompatibilidade com strings/linhas "PDOC" antigas.
- Copy pt-BR em tudo que o usuário vê; risco do PDOR = **não atingir** o contrato (nunca "estouro/exceder").
- Versões do modelo: `PDOR-0.3.0` / `PDOR-ASSUMPTIONS-0.3.0` / `STAVIA-PDOR-SOURCE-0.2.0`.
- Vitest roda em node: testes web só de funções puras.
- Working tree do usuário: NUNCA commitar `apps/web/src/components/shell/CortexShell.tsx` nem `apps/web/src/features/integracoes/IntegracoesPage.css` (WIP dele).
- Comandos web em `apps/web`; API em `apps/api`.

## Tabela de mapeamento custo→receita (VINCULANTE para Tasks 1–3)

| Conceito PDOC (custo) | Conceito PDOR (receita) | Fonte real |
|---|---|---|
| `approvedBudget` (orçamento) | `contractValue` (valor contratual) | `SUM(item_contratual.valor_total)` status ATIVO (já é a fonte atual do budget) |
| `actualCost` (custo realizado) | `measuredRevenue` (receita medida) | `SUM(execucao_servico_rdo.receita_operacional_estimativa)` com `status_validacao IN ('REGISTRADA','VALIDADA')` e `producao_rejeitada = 0` |
| `committedCost` (comprometido) | `validatedRevenue` (receita validada) | idem com `status_validacao = 'VALIDADA'` |
| `costP10/P50/P80/P95` | `revenueP10/P50/P80/P95` | mesmas distribuições, baseline = contrato |
| `eac_cpi/eac_cpi_spi/eac_bottom_up/eac_ponderado` | `rac_rci/rac_rci_spi/rac_bottom_up/rac_ponderado` | mesmas fórmulas com substituição: AC→measuredRevenue, BAC→contractValue, EV/PV inalterados (produção física × contrato) |
| `cpi` | `rci` (índice de captura de receita = EV/receita medida... manter fórmula EV/AC com AC=measuredRevenue) | idem |
| `probabilityAnyOverrun` = P(final > budget) | `probabilityBelowContract` = P(final < contrato) | `probabilityBelow(sorted, contract)` |
| `probabilityOverFivePercent` = P(final > 1.05×budget) | `probabilityBelow95Pct` = P(final < 0.95×contrato) | fator 0.95 |
| `probabilityOverTenPercent` = P(final > 1.10×budget) | `probabilityBelow90Pct` = P(final < 0.90×contrato) | fator 0.90 |
| colunas `p*_custo`, `eac_*`, `prob_qualquer_excedente`, `prob_exceder_5_pct`, `prob_exceder_10_pct` | `p*_receita`, `rac_*`, `prob_abaixo_contrato`, `prob_abaixo_95_pct`, `prob_abaixo_90_pct` | migration V25 |
| response `custoEstimadoFinal`, `eacs` | `receitaEstimadaFinal`, `racs` | mesmo slot (ponderado) |

---

### Task 1: Rename mecânico completo + migration V25 + persistência de receita

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V25__create_pdor_snapshot.sql`
- Move/Rename: `apps/api/src/main/java/com/projeto/cortex/pdoc/` → `.../pdor/` (todas as classes `Pdoc*`→`Pdor*`); `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/pdoc/` → `.../knowledge/pdor/`; `apps/api/src/test/java/com/projeto/cortex/pdoc/` → `.../pdor/` (+ testes `Pdoc*`→`Pdor*`); `intelligence/PdocEngine.java`→`PdorEngine.java`; `intelligence/PdocContextBuilder.java`→`PdorContextBuilder.java`
- Modify: todos os `.java` sob `apps/api/src` que contêm o token (56 arquivos — `git grep -l -i pdoc -- 'apps/api/src/**/*.java'`), incluindo `StaviaIntent.CONSULTAR_PDOC`→`CONSULTAR_PDOR`, `StaviaEvidenceTypes.PDOC`→`PDOR` (valor `"PDOR"`), `StaviaVersions.PDOC_SOURCE`→`PDOR_SOURCE` = `"STAVIA-PDOR-SOURCE-0.2.0"`, SQL de `StaviaSnapshotService` (`pdoc_snapshot`→`pdor_snapshot`, campo de resposta `pdocs`→`pdors`)

**Interfaces:**
- Produces: pacote `com.projeto.cortex.pdor` com endpoints `/api/obras/{obraId}/pdor/{calcular,atual,historico}`; tabela `pdor_snapshot` com colunas de receita; classes `PdorEngine`, `PdorApplicationService`, `PdorSnapshot`, `PdorResultadoResponse` (campos ainda com semântica transicional — Tasks 2–3 remodelam valores). Consumido por TODAS as tasks seguintes.

- [ ] **Step 1: Migration V25** — copie a estrutura de `V16__create_pdoc_snapshot.sql` aplicando a tabela de mapeamento (NÃO edite a V16):

```sql
-- V25__create_pdor_snapshot.sql
DROP TABLE IF EXISTS pdoc_snapshot;

CREATE TABLE pdor_snapshot (
    id CHAR(36) NOT NULL,

    obra_id CHAR(36) NOT NULL,
    codigo_obra VARCHAR(80) NOT NULL,

    executado_em DATETIME(6) NOT NULL,
    data_referencia DATE NOT NULL,

    versao_modelo VARCHAR(40) NOT NULL,
    versao_premissas VARCHAR(80) NOT NULL,

    status_execucao VARCHAR(40) NOT NULL,
    tipo_disparo VARCHAR(40) NOT NULL,
    evento_origem_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,

    chave_idempotencia CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,

    inputs_json JSON NOT NULL,
    origem_inputs_json JSON NOT NULL,
    warnings_json JSON NOT NULL,

    modo_calculo VARCHAR(40),
    calibracao VARCHAR(40),
    fase_obra VARCHAR(40),
    nivel_risco VARCHAR(40),

    p10_receita DECIMAL(18,2),
    p50_receita DECIMAL(18,2),
    p80_receita DECIMAL(18,2),
    p95_receita DECIMAL(18,2),

    rac_rci DECIMAL(18,2),
    rac_rci_spi DECIMAL(18,2),
    rac_bottom_up DECIMAL(18,2),
    rac_ponderado DECIMAL(18,2),
    rci DECIMAL(12,6),
    spi DECIMAL(12,6),

    prob_abaixo_contrato DECIMAL(9,6),
    prob_abaixo_95_pct DECIMAL(9,6),
    prob_abaixo_90_pct DECIMAL(9,6),

    score_heuristico DECIMAL(9,6),
    confianca DECIMAL(9,6),

    simulacao_convergiu TINYINT(1),
    iteracoes_simulacao INT,

    drivers_json JSON NOT NULL,
    erro_execucao TEXT,

    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),

    CONSTRAINT uq_pdor_snapshot_chave_idempotencia
        UNIQUE (chave_idempotencia),
    CONSTRAINT fk_pdor_snapshot_obra
        FOREIGN KEY (obra_id) REFERENCES obra (id),
    CONSTRAINT fk_pdor_snapshot_evento_origem
        FOREIGN KEY (evento_origem_id) REFERENCES cortex_evento_operacional (id)
)
ENGINE = InnoDB
DEFAULT CHARACTER SET = utf8mb4
COLLATE = utf8mb4_unicode_ci;
```

Confira na V16 os índices secundários existentes (após a linha 55) e replique-os com prefixo `pdor` (ex.: `idx_pdor_snapshot_obra_data`).

- [ ] **Step 2: Renames de diretório/arquivo**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git mv apps/api/src/main/java/com/projeto/cortex/pdoc apps/api/src/main/java/com/projeto/cortex/pdor
git mv apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/pdoc apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/pdor
git mv apps/api/src/test/java/com/projeto/cortex/pdoc apps/api/src/test/java/com/projeto/cortex/pdor
for f in $(git ls-files 'apps/api/src' | grep 'Pdoc'); do git mv "$f" "${f//Pdoc/Pdor}"; done
```

- [ ] **Step 3: Substituição de tokens** (só em `apps/api/src`, NUNCA em `db/migration`):

```bash
git ls-files 'apps/api/src/**/*.java' | xargs grep -l -i pdoc | xargs perl -pi -e 's/Pdoc/Pdor/g; s/PDOC/PDOR/g; s/pdoc/pdor/g'
git grep -n -i pdoc -- 'apps/api/src' ':!apps/api/src/main/resources/db/migration'
```

Expected: o segundo comando não retorna NADA (fora migrations antigas).

- [ ] **Step 4: Ajustes pós-substituição obrigatórios**
1. `StaviaVersions.PDOR_SOURCE` = `"STAVIA-PDOR-SOURCE-0.2.0"` (bump 0.1.0→0.2.0; a substituição cega deixa 0.1.0).
2. `PdorEngine.MODEL_VERSION` = `"PDOR-0.3.0"`, `ASSUMPTIONS_VERSION` = `"PDOR-ASSUMPTIONS-0.3.0"`.
3. `PdorMigrationTest` (ex-PdocMigrationTest): se referenciar `V16__create_pdoc_snapshot.sql` pelo nome (a substituição terá quebrado para `V16__create_pdor...`), aponte para `V25__create_pdor_snapshot.sql` e adapte os asserts ao DDL novo.
4. SQLs em Java que citam colunas antigas (`p50_custo`, `eac_*`, `prob_exceder_*`) em `PdorSnapshotRepository`, `PdorApplicationService`, `StaviaSnapshotService`: renomear colunas conforme a tabela de mapeamento (os NOMES de campo Java `p50`, `eacCpi`→`racRci` etc. acompanham). O snapshot Java `PdorSnapshot` renomeia acessores `eacCpi()`→`racRci()`, `eacCpiSpi()`→`racRciSpi()`, `eacBottomUp()`→`racBottomUp()`, `eacWeighted()`→`racWeighted()`.
5. `PdorResultadoResponse`: `custoEstimadoFinal`→`receitaEstimadaFinal`; mapa `eacs`→`racs` com chaves `"rci","rciSpi","bottomUp","ponderado"`; `probabilidadeQualquerExcedente`→`probabilidadeAbaixoContrato`, `probabilidadeExceder5Pct`→`probabilidadeAbaixo95Pct`, `probabilidadeExceder10Pct`→`probabilidadeAbaixo90Pct`.
6. `git grep -rn "pdoc\|Pdoc\|PDOC" apps/api/src ':!*/db/migration/*'` de novo → vazio.

- [ ] **Step 5: Compilar, testar, commitar**

Run: `cd apps/api && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./mvnw test -q`
Expected: verde (asserts que citavam nomes antigos foram renomeados junto pela substituição; falhas restantes indicam ajuste do Step 4 faltando — corrija).

```bash
git add -A apps/api && git commit -m "Renomeia PDOC para PDOR com tabela e persistência de receita (V25)"
```

---

### Task 2: Loader de inputs com fontes reais de receita

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/RealPdorInputLoader.java` (agregados financeiros, ~linhas 630-720 do original)
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/PdorInputBundle.java` (record `SourceValues`)
- Test: `apps/api/src/test/java/com/projeto/cortex/pdor/RealPdorInputLoaderMysqlIntegrationTest.java` (asserts adaptados)

**Interfaces:**
- Consumes: Task 1 (nomes Pdor*).
- Produces: `SourceValues` com `contractValue`, `measuredRevenue`, `validatedRevenue` no lugar de `approvedBudget`, `actualCost`, `committedCost` (demais campos inalterados); `PdorInputBundle.toSourceSnapshot()` repassa na mesma ordem. Consumido pela Task 3.

- [ ] **Step 1: Renomear os 3 campos de `SourceValues`** (`approvedBudget`→`contractValue`, `actualCost`→`measuredRevenue`, `committedCost`→`validatedRevenue`) e propagar nos construtores/usos (`toSourceSnapshot`, loader, flags `hasBudgetData`→`hasContractData`).

- [ ] **Step 2: Trocar as fontes SQL no loader.** O agregado que hoje soma `custo_realizado`/`custo_total` passa a somar receita:

```sql
SELECT
    COALESCE(SUM(e.receita_operacional_estimativa), 0) AS receita_medida
FROM execucao_servico_rdo e
JOIN rdo r ON r.id = e.rdo_id
WHERE r.obra_id = ?
  AND e.data_execucao <= ?
  AND e.status_validacao IN ('REGISTRADA', 'VALIDADA')
  AND e.producao_rejeitada = 0
```

e, para `validatedRevenue`, a mesma consulta com `e.status_validacao = 'VALIDADA'`. O `contractValue` mantém a fonte atual (`SUM(item_contratual.valor_total)` ATIVO). Remover as consultas de custo que ficarem sem uso (`alocacao_colaborador.custo_total` etc. — só se nenhum outro input as consome; confira com `git grep`). Chaves do mapa `inputs` renomeadas de forma correspondente (`approvedBudget`→`contractValue` etc.) — são gravadas em `inputs_json`, dev-only.

- [ ] **Step 3: Adaptar o teste de integração MySQL** — os cenários que semeavam custo passam a semear `receita_operacional_estimativa` + `status_validacao`, asserts nos novos nomes.

Run: `./mvnw test -Dtest=RealPdorInputLoaderMysqlIntegrationTest -q` (se o ambiente MySQL de teste não subir localmente, rode a suite unitária e marque o teste de integração para a verificação final).

- [ ] **Step 4: Suite + commit**

Run: `./mvnw test -q` → verde.

```bash
git add -A apps/api && git commit -m "Alimenta o PDOR com fontes reais de receita medida e validada"
```

---

### Task 3: Motor com semântica de receita e risco invertido

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/PdorEngine.java` (records `PdorContext`/`PdorResult`/`EvmMetrics`, cálculo Monte Carlo ~linhas 420-435, riskLevel)
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/PdorContextBuilder.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/PdorApplicationService.java` (mapeamento resultado→snapshot)
- Test: `apps/api/src/test/java/com/projeto/cortex/pdor/PdorApplicationServiceTest.java` + novo `PdorEngineRiskDirectionTest.java`

**Interfaces:**
- Consumes: `SourceValues` da Task 2.
- Produces: `PdorContext(contractValue, measuredRevenue, validatedRevenue, ...)`; `PdorResult` com `revenueP10/P50/P80/P95`, `simulationProbabilityBelowContract`, `simulationProbabilityBelow95Pct`, `simulationProbabilityBelow90Pct`, `RevenueMetrics` (ex-EvmMetrics: `rci`, `racRci`, `racRciSpi`, `racBottomUp`, `weightedRac`). Consumido pela Task 4 (evento/snapshot).

- [ ] **Step 1: Teste da direção do risco (falhando)**

```java
package com.projeto.cortex.pdor;

import com.projeto.cortex.intelligence.PdorEngine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdorEngineRiskDirectionTest {

    @Test
    void receitaMedidaMuitoAbaixoDoRitmoEsperadoElevaProbabilidadeDeShortfall() {
        PdorEngine engine = new PdorEngine();

        PdorEngine.PdorResult atrasado = engine.calculate(contexto(0.30));
        PdorEngine.PdorResult saudavel = engine.calculate(contexto(1.00));

        assertTrue(
                atrasado.simulationProbabilityBelowContract()
                        > saudavel.simulationProbabilityBelowContract(),
                "obra capturando receita abaixo do ritmo deve ter mais risco de shortfall"
        );
        assertTrue(
                atrasado.revenueP50().compareTo(saudavel.revenueP50()) < 0,
                "P50 de receita da obra atrasada deve ser menor"
        );
    }

    private PdorEngine.PdorContext contexto(double fatorCaptura) {
        BigDecimal contrato = new BigDecimal("1000000.00");
        double fisico = 0.5;
        BigDecimal medida = contrato
                .multiply(BigDecimal.valueOf(fisico * fatorCaptura));

        return new PdorEngine.PdorContext(
                "obra-1",
                LocalDate.of(2026, 7, 1),
                contrato,
                medida,
                medida,
                0.5,
                fisico,
                fisico * fatorCaptura,
                0.0, 0.0, 0.0, 0.0,
                0, 0, 0,
                1.0, 0, 1.0,
                5000
        );
    }
}
```

Ajuste a ordem dos argumentos ao record real (a assinatura do `PdorContext` da Task 1 preserva a ordem do `PdocContext` original com os 3 primeiros valores renomeados).

Run: `./mvnw test -Dtest=PdorEngineRiskDirectionTest -q` → FAIL (métodos `simulationProbabilityBelowContract`/`revenueP50` não existem).

- [ ] **Step 2: Remodelar o motor.** Aplicar a tabela de mapeamento:
1. `PdorContext`: `approvedBudget`→`contractValue`, `actualCost`→`measuredRevenue`, `committedCost`→`validatedRevenue`.
2. `PdorResult`: `costP10..P95`→`revenueP10..P95`; `simulationProbabilityAnyOverrun`→`simulationProbabilityBelowContract`; `...OverFivePercent`→`...Below95Pct`; `...OverTenPercent`→`...Below90Pct`; `calibratedProbabilityOverFivePercent`→`calibratedProbabilityBelow95Pct`.
3. `EvmMetrics`→`RevenueMetrics` com `actualCost`→`measuredRevenue`, `cpi`→`rci`, `estimateAtCompletion*`→`rac*`, `costVariance`→`revenueVariance` (fórmulas idênticas, EV/PV = produção física/planejada × contrato).
4. Monte Carlo (linhas ~424-426 do original): trocar

```java
probabilityAbove(sortedOutcomes, budget),
probabilityAbove(sortedOutcomes, budget * FIVE_PERCENT),
probabilityAbove(sortedOutcomes, budget * TEN_PERCENT)
```

por

```java
probabilityBelow(sortedOutcomes, contract),
probabilityBelow(sortedOutcomes, contract * BELOW_95_PCT),
probabilityBelow(sortedOutcomes, contract * BELOW_90_PCT)
```

com `private static final double BELOW_95_PCT = 0.95;` e `BELOW_90_PCT = 0.90;` e o helper (espelho do `probabilityAbove` existente):

```java
private static double probabilityBelow(double[] sortedOutcomes, double threshold) {
    int count = 0;
    for (double outcome : sortedOutcomes) {
        if (outcome < threshold) {
            count++;
        } else {
            break;
        }
    }
    return (double) count / sortedOutcomes.length;
}
```

(adapte ao estilo do `probabilityAbove` real — se ele usa busca binária, espelhe a busca).
5. **Direção da simulação**: onde os fatores de risco (produtividade, material, equipamento) hoje INFLAM o custo final, eles devem DEFLACIONAR a receita final (obra pior → captura menos receita). Localize a composição do outcome no Monte Carlo: se `outcome = baseline * (1 + efeitos)`, vira `outcome = baseline * (1 - efeitos)` com piso 0. Documente no código: "risco reduz receita capturada".
6. `RiskLevel`: derivação usa as novas probabilidades de shortfall (mesmos cortes numéricos).
7. `PdorContextBuilder`/`PdorApplicationService`: propagar renames; snapshot grava nas colunas novas (já renomeadas na Task 1).

- [ ] **Step 3: Rodar e ajustar**

Run: `./mvnw test -Dtest=PdorEngineRiskDirectionTest -q` → PASS. Depois `./mvnw test -q` → verde (testes existentes do motor renomeados na Task 1 podem precisar de asserts com direção invertida — ajuste conforme a semântica nova, nunca afrouxe asserts sem justificar no relatório).

- [ ] **Step 4: Commit**

```bash
git add -A apps/api && git commit -m "Remodela o motor PDOR para prever receita final com risco de shortfall"
```

---

### Task 4: PDOR na ontologia (objeto, relação, evento)

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/PdorApplicationService.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/pdor/PdorApplicationServiceTest.java`

**Interfaces:**
- Consumes: `CortexOperationalMemoryService.registrarObjeto(tipo, id, codigo, nome, status, fonte)`, `.registrarRelacaoAtiva(tipoOrigem, idOrigem, tipoDestino, idDestino, relacao, fonte, descricao)`, `.registrarEvento(tipoEntidade, entidadeId, tipoEvento, fonte, payload)` (padrão de `PrevisaoFinanceiraService.registrarEventoSnapshot`, linhas 430-483).
- Produces: após persistir snapshot, ontologia recebe objeto `PDOR` + relação `ANALISA` com a obra + evento `PDOR_CALCULADO`/`PDOR_INSUFICIENTE`.

- [ ] **Step 1: Teste (falhando)** — no `PdorApplicationServiceTest`, mockar `CortexOperationalMemoryService` e verificar após um cálculo bem-sucedido:

```java
verify(memoryService).registrarObjeto(
        eq("PDOR"), any(String.class), any(), any(), any(), eq("PDOR"));
verify(memoryService).registrarRelacaoAtiva(
        eq("PDOR"), any(String.class), eq("OBRA"), any(String.class),
        eq("ANALISA"), eq("PDOR"), any());

@SuppressWarnings("unchecked")
ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
verify(memoryService).registrarEvento(
        eq("PDOR"), any(String.class), eq("PDOR_CALCULADO"), eq("PDOR"), payload.capture());
assertEquals("obra-1", payload.getValue().get("obraId"));
assertNotNull(payload.getValue().get("receitaEstimadaFinal"));
```

Adapte a construção do service ao construtor real (injete o mock novo; se o service ainda não recebe `CortexOperationalMemoryService`, o teste guia a injeção).

- [ ] **Step 2: Implementar** — método privado `registrarNoGrafo(PdorSnapshot snapshot, Obra obra)` chamado após o insert bem-sucedido, espelhando `PrevisaoFinanceiraService.registrarEventoSnapshot`:

```java
private static final String FONTE = "PDOR";

private void registrarNoGrafo(PdorSnapshot snapshot, Obra obra) {
    memoryService.registrarObjeto(
            "OBRA", obra.getId(), codigoObra(obra), obra.getNome(), obra.getStatus(), FONTE);
    memoryService.registrarObjeto(
            "PDOR", snapshot.id(), snapshot.codigoObra(),
            "Previsão de receita " + snapshot.codigoObra(),
            snapshot.statusExecucao(), FONTE);
    memoryService.registrarRelacaoAtiva(
            "PDOR", snapshot.id(), "OBRA", obra.getId(),
            "ANALISA", FONTE, "Snapshot PDOR analisa a receita da obra.");

    String tipoEvento = "CALCULADO".equals(snapshot.statusExecucao())
            ? "PDOR_CALCULADO"
            : "PDOR_INSUFICIENTE";

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("schemaVersion", 1);
    payload.put("obraId", obra.getId());
    payload.put("snapshotId", snapshot.id());
    payload.put("dataReferencia", snapshot.dataReferencia());
    payload.put("statusExecucao", snapshot.statusExecucao());
    payload.put("receitaEstimadaFinal", snapshot.racWeighted());
    payload.put("p50Receita", snapshot.p50());
    payload.put("p80Receita", snapshot.p80());
    payload.put("probabilidadeAbaixoContrato", snapshot.probAbaixoContrato());

    memoryService.registrarEvento("PDOR", snapshot.id(), tipoEvento, FONTE, payload);
}
```

Adapte os acessores aos nomes reais do `PdorSnapshot` pós-Task 1 (ex.: `probAbaixoContrato()` — confira). Um helper `codigoObra` já existe no padrão da previsão; se não houver `Obra` disponível no fluxo, use os campos do snapshot (`obraId`, `codigoObra`) e registre o objeto OBRA só com eles.

- [ ] **Step 3: Rodar e commitar**

Run: `./mvnw test -q` → verde.

```bash
git add -A apps/api && git commit -m "Registra snapshots PDOR na ontologia com objeto, relação e evento"
```

---

### Task 5: Stav.IA (servidor) fala receita

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/intent/StaviaIntentClassifier.java` (bloco `CONSULTAR_PDOR`, ~linhas 122-130 do original)
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/interpret/LlmQuestionInterpreter.java` (catálogo de intents)
- Modify: labels/prompts que citarem custo no contexto PDOR (localizar com `git grep -n "custo" apps/api/src/main/java/com/projeto/cortex/intelligence/stavia | grep -i pdor`)
- Test: testes existentes do classificador/interpretador (renomeados na Task 1)

**Interfaces:**
- Consumes: `StaviaIntent.CONSULTAR_PDOR` (Task 1).
- Produces: classificador reconhece vocabulário de receita.

- [ ] **Step 1: Teste (falhando)** — no teste do classificador, adicionar casos:

```java
assertEquals(StaviaIntent.CONSULTAR_PDOR, classifier.classify("qual a previsão de receita da obra?"));
assertEquals(StaviaIntent.CONSULTAR_PDOR, classifier.classify("vamos bater o contrato?"));
assertEquals(StaviaIntent.CONSULTAR_PDOR, classifier.classify("risco de receita da BR-262"));
```

(adapte à API real do classificador — método/assinatura conforme os testes existentes).

- [ ] **Step 2: Keywords novas** — substituir a lista do bloco `CONSULTAR_PDOR` por:

```java
StaviaIntent.CONSULTAR_PDOR,
"pdor",
"previsao de receita",
"predicao de receita",
"receita prevista",
"receita final",
"risco de receita",
"vai bater o contrato",
"bater o contrato",
"atingir o contrato",
"captura de receita",
"shortfall",
```

Remover as keywords de custo desse bloco (mantenha as de custo em outros intents que legitimamente tratem custo, ex.: previsão financeira). No `LlmQuestionInterpreter`, atualizar a descrição do intent no catálogo para "previsão probabilística de RECEITA final da obra (PDOR)".

- [ ] **Step 3: Rodar e commitar**

Run: `./mvnw test -q` → verde.

```bash
git add -A apps/api && git commit -m "Ensina a Stav.IA o vocabulário de receita do PDOR"
```

---

### Task 6: Stav.IA (web, motor local) fala PDOR

**Files:**
- Modify: `apps/web/src/features/stavia/staviaLocalEngine.ts`, `stavia.types.ts`, `staviaSnapshotStorage.ts`, `staviaLocalEngine.test.ts`, `staviaPanelAnswer.test.ts`

**Interfaces:**
- Consumes: payload do `StaviaSnapshotService` do servidor com campo `pdors` (renomeado na Task 1).
- Produces: motor local com topic/intent `PDOR`, evidência `PDOR_LOCAL`, tipos `StaviaSnapshotPdor`, textos pt-BR de receita.

- [ ] **Step 1: Substituição de tokens no diretório stavia do web**

```bash
cd /Users/joaolucas/digitalizacao-rdo-stavias
git ls-files 'apps/web/src/features/stavia/*.ts' | xargs grep -l -i pdoc | xargs perl -pi -e 's/Pdoc/Pdor/g; s/PDOC/PDOR/g; s/pdoc/pdor/g'
git grep -n -i pdoc -- 'apps/web/src' 
```

Expected: segundo comando vazio.

- [ ] **Step 2: Textos pt-BR de receita** — revisar as strings visíveis renomeadas e ajustar o sentido (não só o token):
- "Snapshot PDOR salvo no dispositivo" (ok pós-rename);
- qualquer resposta/label local que fale "custo"/"estouro" no contexto PDOR vira receita/shortfall (localizar: `grep -n "custo\|estouro" apps/web/src/features/stavia/staviaLocalEngine.ts` e ajustar apenas as do tópico PDOR);
- keywords locais do tópico (linhas ~345/554 do original: `"pdoc"`→`"pdor"` já feito; adicionar `"receita prevista"`, `"bater o contrato"` na mesma lista).

- [ ] **Step 3: Campos numéricos do snapshot local** — em `stavia.types.ts`, o tipo `StaviaSnapshotPdor` (ex-`StaviaSnapshotPdoc`) deve espelhar os campos novos do payload do servidor (`receitaEstimadaFinal`, `p50Receita`, `p80Receita`, `probabilidadeAbaixoContrato` — confira o shape real emitido pelo `StaviaSnapshotService` pós-Task 1 e alinhe). Atualizar os formatadores do `staviaLocalEngine` que exibiam custo.

- [ ] **Step 4: Rodar e commitar**

Run: `cd apps/web && npm run lint && npm test && npm run build` → tudo verde (testes de stavia adaptados junto).

```bash
git add apps/web/src/features/stavia && git commit -m "Motor local da Stav.IA passa a falar PDOR e receita"
```

---

### Task 7: Obra registrada na ontologia ao criar/importar

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/obras/ObraService.java` (criarObra)
- Modify: `apps/api/src/main/java/com/projeto/cortex/obras/ObraSeedImportService.java` (loop de import)
- Test: `apps/api/src/test/java/com/projeto/cortex/obras/ObraServiceTest.java`

**Interfaces:**
- Consumes: `CortexOperationalMemoryService.registrarObjeto(String tipo, String id, String codigo, String nome, String status, String fonte)` (já injetado em ambos desde a iniciativa da Home).
- Produces: toda obra criada/importada vira objeto ontológico `OBRA`.

- [ ] **Step 1: Teste (falhando)** — em `ObraServiceTest`, novo caso:

```java
@Test
void criarObraRegistraObjetoOntologico() {
    ObraRepository repository = mock(ObraRepository.class);
    CortexOperationalMemoryService memory =
            mock(CortexOperationalMemoryService.class);
    when(repository.existsByCodigoContrato("CT-1")).thenReturn(false);
    when(repository.save(any(Obra.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

    new ObraService(repository, memory).criarObra(new ObraRequest(
            "CT-1", null, "Obra Nova", "DNIT", null,
            "Campo Grande", "MS", "BR-262",
            null, null, null, "Obs"
    ));

    verify(memory).registrarObjeto(
            eq("OBRA"), any(String.class), eq("CT-1"),
            eq("Obra Nova"), eq("ATIVA"), eq("OBRAS"));
}
```

(Confira a ordem real do construtor de `ObraRequest` no arquivo — igual ao teste existente.)

- [ ] **Step 2: Implementar** — em `criarObra`, imediatamente antes do `registrarEvento` existente:

```java
memoryService.registrarObjeto(
        ObraSyncEvento.TIPO_ENTIDADE,
        salva.getId(),
        salva.getCodigoContrato(),
        salva.getNome(),
        salva.getStatus(),
        "OBRAS"
);
```

No `ObraSeedImportService`, a mesma chamada antes do `registrarEvento` do loop (dentro do try/catch por linha existente).

- [ ] **Step 3: Rodar e commitar**

Run: `./mvnw test -q` → verde.

```bash
git add -A apps/api && git commit -m "Registra obras como objetos ontológicos ao criar e importar"
```

---

### Task 8: Chips de status dinâmicos na Home

**Files:**
- Create: `apps/web/src/features/home/obraStatusLabels.ts`
- Modify: `apps/web/src/features/home/homeFilters.ts` (+ `homeFilters.test.ts`)
- Modify: `apps/web/src/features/home/HomePage.tsx`

**Interfaces:**
- Consumes: `ObraLocalRecord.status`.
- Produces: `buildStatusChips(obras: ObraLocalRecord[]): { value: string; label: string }[]` (sempre inclui `{ value: "TODAS", label: "Todas" }` primeiro; demais = status normalizados distintos em ordem alfabética); `filterObrasByStatus(obras, statusValue)` substitui `filterObrasByChip`; `obraStatusLabel(status: string): string`.

- [ ] **Step 1: Testes (falhando)** — substituir os casos de `filterObrasByChip` em `homeFilters.test.ts` por:

```ts
import {
  buildStatusChips,
  filterObrasByStatus,
} from "./homeFilters";
import { obraStatusLabel } from "./obraStatusLabels";

describe("buildStatusChips", () => {
  it("deriva chips dos status distintos com Todas primeiro", () => {
    const obras = [
      obra("1", "ATIVA"),
      obra("2", "ATIVA"),
      obra("3", "CONCLUIDA"),
      obra("4", "PAUSADA"),
    ];

    expect(buildStatusChips(obras)).toEqual([
      { value: "TODAS", label: "Todas" },
      { value: "ATIVA", label: "Em Execução" },
      { value: "CONCLUIDA", label: "Concluída" },
      { value: "PAUSADA", label: "Pausada" },
    ]);
  });

  it("sem obras retorna só Todas", () => {
    expect(buildStatusChips([])).toEqual([
      { value: "TODAS", label: "Todas" },
    ]);
  });
});

describe("filterObrasByStatus", () => {
  it("filtra por status normalizado e TODAS não filtra", () => {
    const obras = [obra("1", "ATIVA"), obra("2", "Concluída")];
    expect(
      filterObrasByStatus(obras, "CONCLUIDA").map((o) => o.id),
    ).toEqual(["2"]);
    expect(filterObrasByStatus(obras, "TODAS")).toHaveLength(2);
  });
});

describe("obraStatusLabel", () => {
  it("usa o mapa para status conhecidos e capitaliza desconhecidos", () => {
    expect(obraStatusLabel("ATIVA")).toBe("Em Execução");
    expect(obraStatusLabel("EM_MOBILIZACAO")).toBe("Em Mobilizacao");
  });
});
```

(Manter o helper `obra(...)` e os testes de UF/rodovia existentes.)

- [ ] **Step 2: obraStatusLabels.ts**

```ts
// Rótulos pt-BR para status de obra. Status fora do mapa aparecem
// capitalizados a partir do valor normalizado — nada é inventado nem oculto.
const STATUS_LABELS: Record<string, string> = {
  ATIVA: "Em Execução",
  EM_EXECUCAO: "Em Execução",
  CONCLUIDA: "Concluída",
  ENCERRADA: "Encerrada",
  PLANEJADA: "A Começar",
  A_COMECAR: "A Começar",
  DESATIVADA: "Desativada",
  ARQUIVADA: "Arquivada",
  SUSPENSA: "Suspensa",
  PAUSADA: "Pausada",
};

export function normalizeObraStatus(status: string): string {
  return status
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toUpperCase()
    .trim()
    .replace(/\s+/g, "_");
}

export function obraStatusLabel(status: string): string {
  const normalized = normalizeObraStatus(status);
  const known = STATUS_LABELS[normalized];

  if (known) {
    return known;
  }

  return normalized
    .split("_")
    .filter(Boolean)
    .map(
      (word) =>
        word.charAt(0) + word.slice(1).toLowerCase(),
    )
    .join(" ");
}
```

- [ ] **Step 3: homeFilters.ts** — remover `ObraStatusChip`, `OBRA_STATUS_CHIPS` e `CHIP_STATUSES`; adicionar:

```ts
import {
  normalizeObraStatus,
  obraStatusLabel,
} from "./obraStatusLabels";

export interface StatusChip {
  value: string;
  label: string;
}

export function buildStatusChips(
  obras: ObraLocalRecord[],
): StatusChip[] {
  const distinct = [
    ...new Set(
      obras.map((obra) => normalizeObraStatus(obra.status)),
    ),
  ]
    .filter(Boolean)
    .sort();

  return [
    { value: "TODAS", label: "Todas" },
    ...distinct.map((status) => ({
      value: status,
      label: obraStatusLabel(status),
    })),
  ];
}

export function filterObrasByStatus(
  obras: ObraLocalRecord[],
  statusValue: string,
): ObraLocalRecord[] {
  if (statusValue === "TODAS") {
    return obras;
  }

  return obras.filter(
    (obra) =>
      normalizeObraStatus(obra.status) === statusValue,
  );
}
```

(`filterObrasByUf`/`filterObrasByRodovia` ficam como estão.)

- [ ] **Step 4: HomePage.tsx** — trocar `chip`/`OBRA_STATUS_CHIPS`/`filterObrasByChip` por:

```tsx
const [statusChip, setStatusChip] = useState("TODAS");

const statusChips = useMemo(
  () => buildStatusChips(obras),
  [obras],
);
```

render dos chips mapeando `statusChips` (mesmas classes `chip`/`chip--active`), e o filtro composto usa `filterObrasByStatus(obras, statusChip)` no lugar do antigo. Se o status selecionado deixar de existir após reload, `statusChips` não o conterá — resetar para `"TODAS"` num `useEffect` quando `statusChips.every(c => c.value !== statusChip)`.

- [ ] **Step 5: Rodar e commitar**

Run: `npm run lint && npm test && npm run build` → verde.

```bash
git add apps/web/src/features/home && git commit -m "Deriva chips de status das obras reais com rótulos configuráveis"
```

---

### Task 9: Links "Mais Stavias" configuráveis

**Files:**
- Create: `apps/web/src/features/home/staviasLinks.ts` (+ `staviasLinks.test.ts`)
- Modify: `apps/web/src/features/home/MaisStaviasCard.tsx`
- Modify: `apps/web/.env.example` (se existir; senão criar) documentando `VITE_STAVIAS_LINKS`

**Interfaces:**
- Produces: `parseStaviasLinks(raw: string | undefined): { label: string; href: string }[]` — parse defensivo; fallback `[{ label: "Portal Stavias", href: "https://www.stavias.com.br" }]`.

- [ ] **Step 1: Teste (falhando)**

```ts
import { describe, expect, it } from "vitest";

import { parseStaviasLinks } from "./staviasLinks";

describe("parseStaviasLinks", () => {
  it("faz parse de JSON válido com https", () => {
    expect(
      parseStaviasLinks(
        '[{"label":"Academy","href":"https://academy.example.com"}]',
      ),
    ).toEqual([
      { label: "Academy", href: "https://academy.example.com" },
    ]);
  });

  it("cai no fallback com JSON inválido, vazio ou itens malformados", () => {
    const fallback = [
      { label: "Portal Stavias", href: "https://www.stavias.com.br" },
    ];
    expect(parseStaviasLinks(undefined)).toEqual(fallback);
    expect(parseStaviasLinks("not json")).toEqual(fallback);
    expect(parseStaviasLinks("[]")).toEqual(fallback);
    expect(
      parseStaviasLinks('[{"label":"x","href":"javascript:alert(1)"}]'),
    ).toEqual(fallback);
  });
});
```

- [ ] **Step 2: staviasLinks.ts**

```ts
export interface StaviasLink {
  label: string;
  href: string;
}

const FALLBACK_LINKS: StaviasLink[] = [
  {
    label: "Portal Stavias",
    href: "https://www.stavias.com.br",
  },
];

export function parseStaviasLinks(
  raw: string | undefined,
): StaviasLink[] {
  if (!raw?.trim()) {
    return FALLBACK_LINKS;
  }

  try {
    const parsed: unknown = JSON.parse(raw);

    if (!Array.isArray(parsed)) {
      return FALLBACK_LINKS;
    }

    const links = parsed.filter(
      (item): item is StaviasLink =>
        typeof item === "object" &&
        item !== null &&
        typeof (item as StaviasLink).label === "string" &&
        (item as StaviasLink).label.trim() !== "" &&
        typeof (item as StaviasLink).href === "string" &&
        /^https:\/\//.test((item as StaviasLink).href),
    );

    return links.length > 0 ? links : FALLBACK_LINKS;
  } catch {
    return FALLBACK_LINKS;
  }
}

export function staviasLinks(): StaviasLink[] {
  return parseStaviasLinks(
    import.meta.env.VITE_STAVIAS_LINKS as string | undefined,
  );
}
```

- [ ] **Step 3: MaisStaviasCard.tsx** — remover `STAVIAS_LINKS` hardcoded; usar `staviasLinks()` (chamado uma vez no corpo do componente). Render igual (links `target="_blank" rel="noreferrer"`).

- [ ] **Step 4: Documentar** — em `apps/web/.env.example` (criar se não existir):

```
# Links do card "Mais Stavias" (JSON: [{"label":"...","href":"https://..."}]).
# Sem a variável, só o Portal Stavias aparece.
VITE_STAVIAS_LINKS=
```

- [ ] **Step 5: Rodar e commitar**

Run: `npm run lint && npm test && npm run build` → verde.

```bash
git add apps/web && git commit -m "Torna os links do Mais Stavias configuráveis com fallback oficial"
```

---

### Task 10: Verificação integrada

**Files:** nenhum (verificação; usar a skill `verify` e o padrão da verificação da Home — `.superpowers/sdd/task-15-report.md` documenta o ambiente local que funcionou).

- [ ] **Step 1: Suites completas**

```bash
cd apps/web && npm run lint && npm test && npm run build
cd ../../apps/api && export JAVA_HOME=$(/usr/libexec/java_home -v 21) && ./mvnw test -q
```

- [ ] **Step 2: Fluxo real com API+DB** (compose local; migrations V25 aplicam ao subir):
1. `POST /api/obras/{id}/pdor/calcular` (admin) → snapshot em `pdor_snapshot` com receita (p50_receita ≤ valor contratual em obra atrasada); `GET .../pdor/atual` responde `receitaEstimadaFinal`/`probabilidadeAbaixoContrato`.
2. Evento `PDOR_CALCULADO` em `cortex_evento_operacional` com payload de receita; objetos `OBRA` e `PDOR` + relação `ANALISA` nas tabelas de ontologia.
3. `POST /api/obras` → objeto `OBRA` registrado (além do evento já existente).
4. Stav.IA (painel web, com API): perguntar "qual a previsão de receita da obra X?" → intent `CONSULTAR_PDOR` + resposta com dados do snapshot; offline (motor local): mesma pergunta responde do snapshot local `pdors`.
5. Home: chips refletem só os status realmente presentes nas obras sincronizadas; card Mais Stavias mostra só "Portal Stavias" sem a env var e os links da env quando definida (testar com `VITE_STAVIAS_LINKS` no `npm run dev:local`).
6. `git grep -rn -i pdoc apps/api/src apps/web/src ':!*/db/migration/*'` → vazio.

- [ ] **Step 3: Commit final de ajustes, se houver**

```bash
git add -A && git commit -m "Ajustes finais do PDOR após verificação integrada"
```

---

## Self-review do plano (executado na escrita)

- **Cobertura da spec:** Seção 1 → Tasks 1-3; ontologia do PDOR → Task 4; Seção 2 servidor → Task 5 (+ renames na Task 1); Seção 2 web → Task 6; Seção 3 → Task 7; Seção 4 chips → Task 8; links → Task 9; constantes nomeadas → sem mudança de valor, documentadas nos próprios arquivos ao tocá-los (Task 1/9); verificação → Task 10.
- **Placeholders:** nenhum TBD; onde o plano manda "adaptar ao real" é sobre assinaturas que a Task 1 rename define mecanicamente e o compilador valida — a regra dada é sempre concreta (qual campo, qual direção).
- **Consistência de tipos:** `contractValue/measuredRevenue/validatedRevenue` idênticos nas Tasks 2-3; `probabilityBelowContract/Below95Pct/Below90Pct` nas Tasks 1 (colunas), 3 (motor) e 4 (payload `probabilidadeAbaixoContrato`); `buildStatusChips/filterObrasByStatus/obraStatusLabel` consistentes entre Tasks 8 (Steps 1-4).
