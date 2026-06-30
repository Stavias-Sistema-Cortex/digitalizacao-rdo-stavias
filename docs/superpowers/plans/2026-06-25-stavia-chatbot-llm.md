# Stav.IA Chatbot — Inteligência por LLM local — Plano de Implementação

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tornar a Stav.IA robusta a paráfrase e completa em contexto (um "oráculo" da obra), usando um LLM local (Ollama) para entender e redigir, com fallback determinístico e grounding rígido preservado.

**Architecture:** O LLM atua em dois pontos (entender → `StaviaQuestionInterpreter`; redigir → `StaviaModelClient`), sempre validado e com fallback para o pipeline determinístico atual. Toda decisão de segurança (grounding, citação de fontes, contradição, qualidade, acesso) permanece inalterada. A completude de contexto (filtro por entidade/função + fim do truncamento) é determinística e vale mesmo com o LLM desligado.

**Tech Stack:** Java 21, Spring Boot (spring-boot-starter-web → `RestClient`), Jackson (já no classpath), JUnit 5 + `MockRestServiceServer` (spring-test). Ollama via endpoint compatível com OpenAI. Sem dependências novas.

**Spec:** `docs/superpowers/specs/2026-06-25-stavia-chatbot-llm-design.md`

## Global Constraints

- **JDK obrigatório: 21.** O default do shell é o 25 e quebra o Mockito. Antes de qualquer build/teste, exporte:
  `export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home`
  Todos os comandos de teste rodam a partir de `apps/api` com o wrapper `./mvnw`.
- **Sem dependências novas** no `pom.xml` (usar `RestClient` do Spring e `ObjectMapper` do Jackson, já presentes).
- **Flags com default no comportamento atual ("ship dark"):** `cortex.stavia.generator-mode` default `deterministic`; `cortex.stavia.interpreter-mode` default `deterministic`. Com os defaults, o comportamento é idêntico ao de hoje.
- **Grounding é invariante:** uma resposta conclusiva cita ≥1 `sourceKey` existente no contexto autorizado; o `StaviaGroundingValidator` e o `StaviaEngine` não mudam. Toda derivação de chave de evidência usa **uma única fonte da verdade**: `StaviaEvidenceKeys.key(evidence)`.
- **Mensagens em português** (padrão do código).
- **Novos testes usam fakes feitos à mão** (anônimos), seguindo o padrão de `StaviaQueryServiceTest` — não introduzir Mockito novo.
- **Pacotes novos:** `com.projeto.cortex.intelligence.stavia.interpret` e `com.projeto.cortex.intelligence.stavia.llm`. Testes ficam no diretório plano `src/test/java/com/projeto/cortex/intelligence/stavia/`.

---

## File Structure

**Criar (main):**
- `interpret/Origin.java` — enum `{ LLM, DETERMINISTICO }`.
- `interpret/StaviaInterpretation.java` — record `{ StaviaClassification, StaviaQueryPlan, Origin }`.
- `interpret/StaviaQuestionInterpreter.java` — interface `Optional<StaviaInterpretation> interpret(StaviaQuestion)`.
- `interpret/DeterministicQuestionInterpreter.java` — embrulha classifier + planner.
- `interpret/StaviaInterpretationCoordinator.java` — modos + fallback.
- `interpret/StaviaEntityFilters.java` — extrai filtros tipados de `List<ResolvedEntity>`.
- `interpret/StaviaInterpretationPromptBuilder.java` — prompt de interpretação (intenções + few-shots).
- `interpret/LlmQuestionInterpreter.java` — Ollama + parse + validação.
- `llm/StaviaLlmProperties.java` — `@ConfigurationProperties("cortex.stavia.llm")`.
- `llm/OllamaChatClient.java` — transporte HTTP (RestClient) + circuit-breaker.
- `llm/OllamaUnavailableException.java` — runtime exception do transporte.
- `generation/OllamaStaviaModelClient.java` — `StaviaModelClient` via Ollama, com fallback determinístico.

**Modificar (main):**
- `prompt/StaviaPromptBuilder.java` — derivar `sourceKey` por `StaviaEvidenceKeys.key`.
- `StaviaEngine.java` — `limitSources` vira orçamento por intenção.
- `knowledge/team/TeamKnowledgeSource.java` — aplicar `StaviaEntityFilters`.
- `knowledge/allocation/AllocationKnowledgeSource.java` — aplicar `StaviaEntityFilters` (helper isolado).
- `planning/ResolvedEntity.java` — factories `collaboratorByName`, `roleByLabel`.
- `StaviaQueryService.java` — usar o coordinator (mantendo construtor atual).
- `version/StaviaVersions.java` — adicionar `INTERPRETATION` e versões LLM.
- `src/main/resources/application.yml` — bloco `interpreter-mode` + `llm`.

**Criar (test):** um arquivo por componente novo no diretório plano de testes.

---

### Task 1: Unificar a chave de evidência no prompt (conserto R1)

`StaviaPromptBuilder` monta `sourceKey = type + ":" + id`, mas o engine valida com `StaviaEvidenceKeys.key(...)`, que acrescenta `:version:X`/`:commit:X`. Para evidências com `versao`/`commitSequence`, as chaves divergem e a resposta do LLM viraria "não rastreável". Unificar na fonte da verdade.

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/prompt/StaviaPromptBuilder.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaPromptBuilderTest.java`

**Interfaces:**
- Consumes: `StaviaEvidenceKeys.key(StaviaEvidence) -> String` (existente).
- Produces: nenhum símbolo novo; muda o valor de `StaviaPromptEvidence.sourceKey()`.

- [ ] **Step 1: Escrever o teste que falha** — adicionar ao `StaviaPromptBuilderTest`:

```java
@Test
void shouldDeriveSourceKeyWithVersionSuffixLikeTheEngine() {
    StaviaPrompt prompt =
            builder.build(
                    new StaviaQuestion("Qual a receita?", "usuario-1", "obra-1"),
                    StaviaIntent.CONSULTAR_RECEITA,
                    List.of(
                            new StaviaEvidence(
                                    "PREVISAO_FINANCEIRA",
                                    "fin-1",
                                    "Snapshot financeiro.",
                                    Instant.parse("2026-06-22T12:00:00Z"),
                                    true,
                                    Map.of("versao", "7")
                            )
                    )
            );

    assertEquals(
            "PREVISAO_FINANCEIRA:fin-1:version:7",
            prompt.evidences().getFirst().sourceKey()
    );
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=StaviaPromptBuilderTest#shouldDeriveSourceKeyWithVersionSuffixLikeTheEngine`
Expected: FAIL — esperado `...:version:7`, obtido `PREVISAO_FINANCEIRA:fin-1`.

- [ ] **Step 3: Implementar** — em `StaviaPromptBuilder`, trocar o método `evidenceKey` e importar a fonte da verdade:

```java
// import no topo:
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceKeys;

// substituir o método privado evidenceKey(...) inteiro por:
private String evidenceKey(StaviaEvidence evidence) {
    return StaviaEvidenceKeys.key(evidence);
}
```

- [ ] **Step 4: Rodar e ver passar** (inclui o teste antigo `shouldBuildVersionedPromptWithAuthorizedEvidence`, que continua verde porque sua evidência não tem `versao`)

Run: `./mvnw test -Dtest=StaviaPromptBuilderTest`
Expected: PASS (2+ testes).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/prompt/StaviaPromptBuilder.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaPromptBuilderTest.java
git commit -m "fix(stavia): unify prompt evidence key with StaviaEvidenceKeys to keep LLM citations groundable"
```

---

### Task 2: Orçamento de evidências por intenção (fim do truncamento em 5)

Hoje `StaviaEngine.limitSources` corta em 5 para a maioria das intenções, escondendo dados de listagem/entidade. Elevar o teto para as intenções de listagem (equipe, alocação, frequência, banco de horas, ativo, ocorrência) para 50 (alinhado ao LIMIT 50 das fontes), preservando os limites de histórico (200) e resumo (25).

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/StaviaEngine.java:283-297`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaEngineBudgetTest.java` (novo)

**Interfaces:**
- Produces: comportamento — `StaviaEngine` mantém até 50 evidências para intenções de listagem.

- [ ] **Step 1: Escrever o teste que falha** — novo arquivo `StaviaEngineBudgetTest.java`:

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaEngineBudgetTest {

    @Test
    void shouldKeepUpToFiftyAllocationEvidences() {
        List<StaviaEvidence> evidences = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            evidences.add(new StaviaEvidence(
                    StaviaEvidenceTypes.ALOCACAO_COLABORADOR,
                    "ALOCACAO_COLABORADOR:aloc-" + i,
                    "Abner esteve na obra CW1 em 0" + (i % 9 + 1) + "/06/2026 por 8 hora(s).",
                    Instant.now(),
                    true,
                    Map.of("colaboradorNome", "Abner", "data", "2026-06-0" + (i % 9 + 1))));
        }

        StaviaEngine engine = new StaviaEngine(
                new StaviaIntentClassifier(),
                new StaviaEvidenceSelector(),
                new StaviaGroundingValidator(),
                new StaviaEvidenceQualityPolicy(),
                new StaviaContradictionPolicy(),
                new DeterministicStaviaResponseGenerator());

        StaviaAnswer answer = engine.answer(
                new StaviaQuestion("Onde o Abner trabalhou?", "u1", "obra-1"),
                new StaviaContext(Set.of(StaviaEngine.REQUIRED_PERMISSION), evidences),
                StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR);

        // Com truncamento antigo (5), só 5 alocações chegavam ao gerador, que exibe 10
        // e anuncia "omitidas". Com orçamento de 50, as 30 cabem (10 exibidas + 20 omitidas
        // no texto do gerador determinístico) — o ponto do teste é que o engine NÃO corta em 5.
        assertTrue(answer.sources().size() >= 30,
                "engine deveria reter >=30 evidências de alocação, reteve " + answer.sources().size());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=StaviaEngineBudgetTest`
Expected: FAIL — `sources().size()` é 5 (ou ≤10), não ≥30.

- [ ] **Step 3: Implementar** — substituir `limitSources` em `StaviaEngine.java`:

```java
private List<StaviaEvidence> limitSources(
        StaviaIntent intent,
        List<StaviaEvidence> selectedEvidence
) {
    int limit = switch (intent) {
        case CONSULTAR_HISTORICO -> 200;
        case CONSULTAR_EQUIPE,
                CONSULTAR_ATIVO,
                CONSULTAR_OCORRENCIA,
                CONSULTAR_ALOCACAO_COLABORADOR,
                CONSULTAR_FREQUENCIA,
                CONSULTAR_BANCO_HORAS,
                CONSULTAR_RDO,
                CONSULTAR_PROGRAMACAO -> 50;
        case RESUMIR_OBRA -> 25;
        default -> 5;
    };

    return selectedEvidence
            .stream()
            .limit(limit)
            .toList();
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=StaviaEngineBudgetTest,StaviaEngineTest`
Expected: PASS (o novo teste + os de regressão do engine).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/StaviaEngine.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaEngineBudgetTest.java
git commit -m "feat(stavia): budget evidences per intent so listing queries keep full context"
```

---

### Task 3: Factories de entidade + StaviaEntityFilters

`ResolvedEntity` já é genérico; adicionar factories para colaborador e função, e um value object que extrai filtros normalizados de uma lista de entidades.

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/planning/ResolvedEntity.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/interpret/StaviaEntityFilters.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaEntityFiltersTest.java`

**Interfaces:**
- Produces:
  - `ResolvedEntity.collaboratorByName(String) -> ResolvedEntity` (type `"COLABORADOR"`, resolvedBy `"NOME"`).
  - `ResolvedEntity.roleByLabel(String) -> ResolvedEntity` (type `"ROLE"`, resolvedBy `"FUNCAO"`).
  - `StaviaEntityFilters.from(List<ResolvedEntity>) -> StaviaEntityFilters`
  - `filters.collaboratorName() -> Optional<String>` (normalizado por `StaviaText.normalize`)
  - `filters.roles() -> Set<String>` (normalizadas)
  - `filters.matchesCollaborator(String name) -> boolean`, `filters.matchesRole(String role) -> boolean`
  - `filters.isEmpty() -> boolean`

- [ ] **Step 1: Escrever o teste que falha** — `StaviaEntityFiltersTest.java`:

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaEntityFiltersTest {

    @Test
    void shouldMatchCollaboratorAccentInsensitive() {
        StaviaEntityFilters filters = StaviaEntityFilters.from(
                List.of(ResolvedEntity.collaboratorByName("Abnér")));

        assertTrue(filters.matchesCollaborator("ABNER SILVA"));
        assertFalse(filters.matchesCollaborator("Joao"));
    }

    @Test
    void shouldMatchRoleByLabel() {
        StaviaEntityFilters filters = StaviaEntityFilters.from(
                List.of(ResolvedEntity.roleByLabel("apontador")));

        assertTrue(filters.matchesRole("Apontador de Campo"));
        assertFalse(filters.matchesRole("Encarregado"));
    }

    @Test
    void shouldBeEmptyWhenOnlyWorksite() {
        StaviaEntityFilters filters = StaviaEntityFilters.from(
                List.of(ResolvedEntity.worksiteById("obra-1")));

        assertTrue(filters.isEmpty());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=StaviaEntityFiltersTest`
Expected: FAIL — `StaviaEntityFilters` / factories não existem (compile error).

- [ ] **Step 3a: Implementar as factories** — adicionar em `ResolvedEntity.java`:

```java
public static ResolvedEntity collaboratorByName(String name) {
    return new ResolvedEntity("COLABORADOR", null, "NOME", name, false, List.of());
}

public static ResolvedEntity roleByLabel(String label) {
    return new ResolvedEntity("ROLE", null, "FUNCAO", label, false, List.of());
}
```

- [ ] **Step 3b: Implementar StaviaEntityFilters.java:**

```java
package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public record StaviaEntityFilters(
        String collaboratorNameNormalized,
        Set<String> rolesNormalized
) {

    public StaviaEntityFilters {
        rolesNormalized = rolesNormalized == null ? Set.of() : Set.copyOf(rolesNormalized);
    }

    public static StaviaEntityFilters from(List<ResolvedEntity> entities) {
        String collaborator = null;
        Set<String> roles = new LinkedHashSet<>();

        if (entities != null) {
            for (ResolvedEntity entity : entities) {
                if (entity == null || entity.value() == null) {
                    continue;
                }
                String normalized = StaviaText.normalize(entity.value());
                if (normalized.isBlank()) {
                    continue;
                }
                if ("COLABORADOR".equalsIgnoreCase(entity.type()) && collaborator == null) {
                    collaborator = normalized;
                } else if ("ROLE".equalsIgnoreCase(entity.type())) {
                    roles.add(normalized);
                }
            }
        }

        return new StaviaEntityFilters(collaborator, roles);
    }

    public Optional<String> collaboratorName() {
        return Optional.ofNullable(collaboratorNameNormalized);
    }

    public Set<String> roles() {
        return rolesNormalized;
    }

    public boolean isEmpty() {
        return collaboratorNameNormalized == null && rolesNormalized.isEmpty();
    }

    public boolean matchesCollaborator(String candidate) {
        if (collaboratorNameNormalized == null) {
            return true;
        }
        return StaviaText.normalize(candidate).contains(collaboratorNameNormalized);
    }

    public boolean matchesRole(String candidate) {
        if (rolesNormalized.isEmpty()) {
            return true;
        }
        String normalized = StaviaText.normalize(candidate);
        return rolesNormalized.stream().anyMatch(normalized::contains);
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=StaviaEntityFiltersTest`
Expected: PASS (3 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/planning/ResolvedEntity.java apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/interpret/StaviaEntityFilters.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaEntityFiltersTest.java
git commit -m "feat(stavia): add collaborator/role entity factories and StaviaEntityFilters"
```

---

### Task 4: TeamKnowledgeSource filtra por função/nome (caso "apontador")

`TeamKnowledgeSource` recebe todos os registros de mão de obra da obra (via `TeamReader`); filtrar em Java por `StaviaEntityFilters` derivado de `request.plan().entities()`. Atende ao AC6 ("quem é o apontador").

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/team/TeamKnowledgeSource.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/TeamKnowledgeSourceTest.java` (estender)

**Interfaces:**
- Consumes: `StaviaEntityFilters.from(...)`, `filters.matchesRole(...)`, `filters.matchesCollaborator(...)`; `TeamRecord.cargo()`, `TeamRecord.collaboratorName()`/`nomeColaborador()`.
- Produces: comportamento — quando há filtro de função/nome, só evidências compatíveis são retornadas.

- [ ] **Step 1: Ler o teste e a fonte atuais** para casar nomes de campos.

Run: `./mvnw test -Dtest=TeamKnowledgeSourceTest` (confirmar verde antes de mexer).

- [ ] **Step 2: Escrever o teste que falha** — adicionar ao `TeamKnowledgeSourceTest` um caso com `plan.entities()` contendo `ResolvedEntity.roleByLabel("apontador")` e dois `TeamRecord` (um "Apontador", um "Servente"); o `request` deve usar o construtor de 5 args do `StaviaKnowledgeRequest` com um `StaviaQueryPlan` que carrega a entidade. Asserir que só a evidência do apontador retorna. (Use o padrão de fake `TeamReader` já presente no teste; montar `StaviaQueryPlan` via `new StaviaQueryPlan(QueryDomain.EQUIPE, QueryOperation.READ_ATTRIBUTE, List.of(ResolvedEntity.roleByLabel("apontador")), TemporalFilter.none(), List.of(), List.of(), List.of(), List.of(), false, false, false)`.)

```java
@Test
void shouldFilterTeamByRole() {
    // fakeReader devolve dois registros: cargo "Apontador" e cargo "Servente"
    // (reaproveitar o helper de TeamRecord já existente no teste, variando o cargo)
    StaviaQueryPlan plan = new StaviaQueryPlan(
            QueryDomain.EQUIPE, QueryOperation.READ_ATTRIBUTE,
            List.of(ResolvedEntity.roleByLabel("apontador")),
            TemporalFilter.none(), List.of(), List.of(), List.of(), List.of(),
            false, false, false);

    StaviaKnowledgeRequest request = new StaviaKnowledgeRequest(
            new StaviaQuestion("Quem é o apontador da obra?", "u1", "obra-1"),
            StaviaIntent.CONSULTAR_EQUIPE, "obra-1",
            Set.of(StaviaEngine.REQUIRED_PERMISSION), plan);

    List<StaviaEvidence> evidences = source.retrieve(request);

    assertEquals(1, evidences.size());
    assertTrue(evidences.getFirst().summary().toLowerCase().contains("apontador"));
}
```

(Imports a adicionar conforme uso: `QueryDomain`, `QueryOperation`, `TemporalFilter`, `ResolvedEntity`, `StaviaQueryPlan`, `StaviaQuestion`, `StaviaIntent`, `StaviaEngine`, `Set`.)

- [ ] **Step 3: Rodar e ver falhar**

Run: `./mvnw test -Dtest=TeamKnowledgeSourceTest#shouldFilterTeamByRole`
Expected: FAIL — retornam 2 evidências (sem filtro).

- [ ] **Step 4: Implementar** — em `TeamKnowledgeSource.retrieve`, após obter os `TeamRecord`/evidências, aplicar o filtro:

```java
// import:
import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;

// dentro de retrieve(request), derivar uma vez:
StaviaEntityFilters filters = StaviaEntityFilters.from(request.plan().entities());

// e, ao mapear cada TeamRecord -> StaviaEvidence, pular os que não casam:
// (cargo = record.cargo(); nome = record.collaboratorName() ou record.nomeColaborador())
if (!filters.matchesRole(cargo) || !filters.matchesCollaborator(nome)) {
    continue; // ou .filter(...) no stream equivalente
}
```

(Adaptar ao estilo atual de montagem da lista — se for stream, usar `.filter(rec -> filters.matchesRole(rec.cargo()) && filters.matchesCollaborator(nomeDe(rec)))` antes do `.map`.)

- [ ] **Step 5: Rodar e ver passar**

Run: `./mvnw test -Dtest=TeamKnowledgeSourceTest`
Expected: PASS (todos, incluindo os antigos — sem filtro, `matches*` retornam true).

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/team/TeamKnowledgeSource.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/TeamKnowledgeSourceTest.java
git commit -m "feat(stavia): filter team knowledge by resolved role/collaborator entities"
```

---

### Task 5: AllocationKnowledgeSource filtra por colaborador/função

`AllocationKnowledgeSource` usa `JdbcTemplate` inline (sem reader). Extrair um helper estático `filterByEntities(List<StaviaEvidence>, StaviaEntityFilters)` (unit-testável sem banco) e aplicá-lo ao resultado do `jdbcTemplate.query`.

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/allocation/AllocationKnowledgeSource.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/AllocationKnowledgeSourceFilterTest.java` (novo)

**Interfaces:**
- Produces: `AllocationKnowledgeSource.filterByEntities(List<StaviaEvidence>, StaviaEntityFilters) -> List<StaviaEvidence>` (package-private static).

- [ ] **Step 1: Escrever o teste que falha** — `AllocationKnowledgeSourceFilterTest.java`:

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;
import com.projeto.cortex.intelligence.stavia.knowledge.allocation.AllocationKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AllocationKnowledgeSourceFilterTest {

    private StaviaEvidence alloc(String nome, String funcao) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.ALOCACAO_COLABORADOR,
                "ALOCACAO_COLABORADOR:" + nome + "-" + funcao,
                nome + " esteve na obra CW1.",
                Instant.now(), true,
                Map.of("colaboradorNome", nome, "funcao", funcao));
    }

    @Test
    void shouldKeepOnlyMatchingCollaborator() {
        List<StaviaEvidence> all = List.of(
                alloc("Abner Silva", "Apontador"),
                alloc("Joao Souza", "Servente"));

        List<StaviaEvidence> filtered = AllocationKnowledgeSource.filterByEntities(
                all, StaviaEntityFilters.from(List.of(ResolvedEntity.collaboratorByName("abner"))));

        assertEquals(1, filtered.size());
        assertEquals("Abner Silva", filtered.getFirst().attributes().get("colaboradorNome"));
    }

    @Test
    void shouldReturnAllWhenNoEntityFilter() {
        List<StaviaEvidence> all = List.of(
                alloc("Abner Silva", "Apontador"),
                alloc("Joao Souza", "Servente"));

        List<StaviaEvidence> filtered = AllocationKnowledgeSource.filterByEntities(
                all, StaviaEntityFilters.from(List.of(ResolvedEntity.worksiteById("obra-1"))));

        assertEquals(2, filtered.size());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=AllocationKnowledgeSourceFilterTest`
Expected: FAIL — `filterByEntities` não existe (compile error).

- [ ] **Step 3: Implementar** — em `AllocationKnowledgeSource`, adicionar o helper e chamá-lo no fim de `retrieve`:

```java
// import:
import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;

// novo método (package-private, static, testável):
static List<StaviaEvidence> filterByEntities(
        List<StaviaEvidence> evidences,
        StaviaEntityFilters filters
) {
    if (filters.isEmpty()) {
        return evidences;
    }
    return evidences.stream()
            .filter(e -> filters.matchesCollaborator(
                    String.valueOf(e.attributes().getOrDefault("colaboradorNome", ""))))
            .filter(e -> filters.matchesRole(
                    String.valueOf(e.attributes().getOrDefault("funcao", ""))))
            .toList();
}

// no fim de retrieve(request): em vez de `return jdbcTemplate.query(...)`,
// capturar o resultado e filtrar:
List<StaviaEvidence> rows = jdbcTemplate.query(sql, /* mapper e args atuais */);
return filterByEntities(rows, StaviaEntityFilters.from(request.plan().entities()));
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=AllocationKnowledgeSourceFilterTest`
Expected: PASS (2 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/allocation/AllocationKnowledgeSource.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/AllocationKnowledgeSourceFilterTest.java
git commit -m "feat(stavia): filter allocation evidence by resolved collaborator/role entities"
```

---

### Task 6: Contrato de interpretação + interpretador determinístico

**Files:**
- Create: `interpret/Origin.java`, `interpret/StaviaInterpretation.java`, `interpret/StaviaQuestionInterpreter.java`, `interpret/DeterministicQuestionInterpreter.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/DeterministicQuestionInterpreterTest.java`

**Interfaces:**
- Produces:
  - `enum Origin { LLM, DETERMINISTICO }`
  - `record StaviaInterpretation(StaviaClassification classification, StaviaQueryPlan plan, Origin origin)` com `intent() -> StaviaIntent` (= `classification.intent()`).
  - `interface StaviaQuestionInterpreter { Optional<StaviaInterpretation> interpret(StaviaQuestion q); }`
  - `DeterministicQuestionInterpreter(StaviaIntentClassifier, StaviaQueryPlanner)` — sempre `Optional.of(...)`, `origin=DETERMINISTICO`.

- [ ] **Step 1: Escrever o teste que falha** — `DeterministicQuestionInterpreterTest.java`:

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.Origin;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicQuestionInterpreterTest {

    private final DeterministicQuestionInterpreter interpreter =
            new DeterministicQuestionInterpreter(
                    new StaviaIntentClassifier(),
                    new StaviaQueryPlanner(new StaviaSemanticCatalog()));

    @Test
    void shouldProduceDeterministicInterpretation() {
        Optional<StaviaInterpretation> result =
                interpreter.interpret(new StaviaQuestion(
                        "Qual é o histórico de alterações dos RDOs?", "u1", "obra-1"));

        assertTrue(result.isPresent());
        assertEquals(StaviaIntent.CONSULTAR_HISTORICO, result.get().intent());
        assertEquals(Origin.DETERMINISTICO, result.get().origin());
        assertTrue(result.get().classification().confidence() > 0.0);
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=DeterministicQuestionInterpreterTest`
Expected: FAIL — tipos não existem (compile error).

- [ ] **Step 3: Implementar os quatro arquivos:**

`Origin.java`:
```java
package com.projeto.cortex.intelligence.stavia.interpret;

public enum Origin { LLM, DETERMINISTICO }
```

`StaviaInterpretation.java`:
```java
package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;

public record StaviaInterpretation(
        StaviaClassification classification,
        StaviaQueryPlan plan,
        Origin origin
) {

    public StaviaInterpretation {
        if (classification == null) {
            throw new IllegalArgumentException("A classificação deve ser informada.");
        }
        plan = plan == null ? StaviaQueryPlan.empty() : plan;
        origin = origin == null ? Origin.DETERMINISTICO : origin;
    }

    public StaviaIntent intent() {
        return classification.intent();
    }
}
```

`StaviaQuestionInterpreter.java`:
```java
package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;

import java.util.Optional;

public interface StaviaQuestionInterpreter {
    Optional<StaviaInterpretation> interpret(StaviaQuestion question);
}
```

`DeterministicQuestionInterpreter.java`:
```java
package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class DeterministicQuestionInterpreter implements StaviaQuestionInterpreter {

    private final StaviaIntentClassifier classifier;
    private final StaviaQueryPlanner planner;

    public DeterministicQuestionInterpreter(
            StaviaIntentClassifier classifier,
            StaviaQueryPlanner planner
    ) {
        this.classifier = classifier;
        this.planner = planner;
    }

    @Override
    public Optional<StaviaInterpretation> interpret(StaviaQuestion question) {
        StaviaClassification classification =
                classifier.classifyDetailed(question.text());
        StaviaQueryPlan plan = planner.plan(question, classification);
        StaviaIntent effectiveIntent =
                planner.effectiveIntent(classification.intent(), plan);
        double effectiveConfidence = planner.effectiveConfidence(
                classification.confidence(), classification.intent(), plan);

        return Optional.of(new StaviaInterpretation(
                new StaviaClassification(effectiveIntent, effectiveConfidence),
                plan,
                Origin.DETERMINISTICO));
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=DeterministicQuestionInterpreterTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/interpret/ apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/DeterministicQuestionInterpreterTest.java
git commit -m "feat(stavia): add interpretation contract and deterministic interpreter"
```

---

### Task 7: Coordinator (modos + fallback)

**Files:**
- Create: `interpret/StaviaInterpretationCoordinator.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaInterpretationCoordinatorTest.java`

**Interfaces:**
- Consumes: `DeterministicQuestionInterpreter`, `StaviaQuestionInterpreter` (LLM, opcional/nullable), `Origin`, `StaviaInterpretation`.
- Produces: `StaviaInterpretationCoordinator(DeterministicQuestionInterpreter, StaviaQuestionInterpreter llmOrNull, String mode, double doubtThreshold)` com `StaviaInterpretation interpret(StaviaQuestion)`. `mode ∈ {"deterministic","llm","llm-on-doubt"}`.

- [ ] **Step 1: Escrever o teste que falha** — `StaviaInterpretationCoordinatorTest.java`:

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.Origin;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationCoordinator;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaviaInterpretationCoordinatorTest {

    private final DeterministicQuestionInterpreter deterministic =
            new DeterministicQuestionInterpreter(
                    new StaviaIntentClassifier(),
                    new StaviaQueryPlanner(new StaviaSemanticCatalog()));

    private StaviaQuestion q() {
        return new StaviaQuestion("Quais RDOs pertencem a esta obra?", "u1", "obra-1");
    }

    @Test
    void shouldUseDeterministicWhenModeIsDeterministic() {
        StaviaQuestionInterpreter llm = question -> {
            throw new AssertionError("LLM não deveria ser chamado");
        };
        StaviaInterpretationCoordinator coordinator =
                new StaviaInterpretationCoordinator(deterministic, llm, "deterministic", 0.45);

        assertEquals(Origin.DETERMINISTICO, coordinator.interpret(q()).origin());
    }

    @Test
    void shouldFallBackWhenLlmReturnsEmpty() {
        StaviaQuestionInterpreter llm = question -> Optional.empty();
        StaviaInterpretationCoordinator coordinator =
                new StaviaInterpretationCoordinator(deterministic, llm, "llm", 0.45);

        assertEquals(Origin.DETERMINISTICO, coordinator.interpret(q()).origin());
    }

    @Test
    void shouldFallBackWhenLlmThrows() {
        StaviaQuestionInterpreter llm = question -> {
            throw new RuntimeException("ollama down");
        };
        StaviaInterpretationCoordinator coordinator =
                new StaviaInterpretationCoordinator(deterministic, llm, "llm", 0.45);

        assertEquals(Origin.DETERMINISTICO, coordinator.interpret(q()).origin());
    }

    @Test
    void shouldUseLlmWhenItSucceeds() {
        StaviaInterpretation llmInterpretation = new StaviaInterpretation(
                new StaviaClassification(StaviaIntent.CONSULTAR_EQUIPE, 0.9),
                StaviaQueryPlan.empty(), Origin.LLM);
        StaviaQuestionInterpreter llm = question -> Optional.of(llmInterpretation);
        StaviaInterpretationCoordinator coordinator =
                new StaviaInterpretationCoordinator(deterministic, llm, "llm", 0.45);

        assertEquals(Origin.LLM, coordinator.interpret(q()).origin());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=StaviaInterpretationCoordinatorTest`
Expected: FAIL — coordinator não existe.

- [ ] **Step 3: Implementar `StaviaInterpretationCoordinator.java`:**

```java
package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

public class StaviaInterpretationCoordinator {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StaviaInterpretationCoordinator.class);

    private final DeterministicQuestionInterpreter deterministic;
    private final StaviaQuestionInterpreter llm;
    private final String mode;
    private final double doubtThreshold;

    public StaviaInterpretationCoordinator(
            DeterministicQuestionInterpreter deterministic,
            StaviaQuestionInterpreter llm,
            String mode,
            double doubtThreshold
    ) {
        this.deterministic = deterministic;
        this.llm = llm;
        this.mode = mode == null ? "deterministic" : mode.toLowerCase(Locale.ROOT);
        this.doubtThreshold = doubtThreshold;
    }

    public StaviaInterpretation interpret(StaviaQuestion question) {
        StaviaInterpretation fallback = deterministic.interpret(question).orElseThrow();

        if (llm == null || "deterministic".equals(mode)) {
            return fallback;
        }

        if ("llm-on-doubt".equals(mode)
                && fallback.intent() != StaviaIntent.DESCONHECIDA
                && fallback.classification().confidence() >= doubtThreshold) {
            return fallback;
        }

        try {
            Optional<StaviaInterpretation> result = llm.interpret(question);
            if (result.isPresent()) {
                return result.get();
            }
            LOGGER.info("Intérprete LLM vazio; usando fallback determinístico.");
        } catch (RuntimeException exception) {
            LOGGER.warn("Intérprete LLM falhou ({}); usando fallback determinístico.",
                    exception.getMessage());
        }

        return fallback;
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=StaviaInterpretationCoordinatorTest`
Expected: PASS (4 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/interpret/StaviaInterpretationCoordinator.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaInterpretationCoordinatorTest.java
git commit -m "feat(stavia): add interpretation coordinator with modes and deterministic fallback"
```

---

### Task 8: Integrar o coordinator ao StaviaQueryService (compatível)

Substituir o bloco `classifyDetailed`+`plan`+`effectiveIntent`+`effectiveConfidence` por `coordinator.interpret(question)`, mantendo o construtor atual (que constrói um coordinator determinístico) para não quebrar os testes existentes.

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/StaviaQueryService.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaQueryServiceTest.java` (continua verde, sem alteração)

**Interfaces:**
- Consumes: `StaviaInterpretationCoordinator`, `StaviaInterpretation`.
- Produces: novo construtor `StaviaQueryService(..., StaviaInterpretationCoordinator)`; o construtor de 5 args atual passa a montar um coordinator determinístico internamente.

- [ ] **Step 1: Rodar a suíte do service (baseline verde)**

Run: `./mvnw test -Dtest=StaviaQueryServiceTest`
Expected: PASS (baseline antes de mexer).

- [ ] **Step 2: Implementar** — em `StaviaQueryService`:
  - Adicionar campo `private final StaviaInterpretationCoordinator coordinator;`.
  - No construtor de 5 args (`@Autowired` atual) já existe um `StaviaQueryPlanner` interno; montar:
    ```java
    this.coordinator = new StaviaInterpretationCoordinator(
            new DeterministicQuestionInterpreter(intentClassifier, queryPlanner),
            null, "deterministic", 0.45);
    ```
  - No corpo de `query(...)`, substituir:
    ```java
    StaviaClassification classification = intentClassifier.classifyDetailed(question.text());
    StaviaQueryPlan plan = queryPlanner.plan(question, classification);
    StaviaIntent intent = queryPlanner.effectiveIntent(classification.intent(), plan);
    ```
    por:
    ```java
    StaviaInterpretation interpretation = coordinator.interpret(question);
    StaviaQueryPlan plan = interpretation.plan();
    StaviaIntent intent = interpretation.intent();
    ```
  - Onde usa `queryPlanner.effectiveConfidence(...)` para o `StaviaQueryResult`, trocar por `interpretation.classification().confidence()`.
  - Adicionar um construtor que recebe o `StaviaInterpretationCoordinator` pronto (para a fiação Spring da Task 15) e delega os demais colaboradores.

  (Imports de `StaviaInterpretation`, `StaviaInterpretationCoordinator`, `DeterministicQuestionInterpreter`; remover imports não usados de `StaviaClassification` se aplicável.)

- [ ] **Step 3: Rodar e ver passar (regressão)**

Run: `./mvnw test -Dtest=StaviaQueryServiceTest`
Expected: PASS — todos os testes existentes verdes (caminho determinístico inalterado).

- [ ] **Step 4: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/StaviaQueryService.java
git commit -m "refactor(stavia): route query service through interpretation coordinator (deterministic default)"
```

---

### Task 9: Propriedades de LLM + application.yml

**Files:**
- Create: `llm/StaviaLlmProperties.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `version/StaviaVersions.java` (constantes de versão)
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaLlmPropertiesTest.java`

**Interfaces:**
- Produces: `StaviaLlmProperties` com getters `baseUrl()`, `model()`, `apiKey()`, `connectTimeoutMs()`, `readTimeoutMs()`, `maxEvidences()`, `confidenceThreshold()`, `breakerFailureThreshold()` (default 3), `breakerOpenSeconds()` (default 30) e defaults sãos.

- [ ] **Step 1: Escrever o teste que falha** — `StaviaLlmPropertiesTest.java`:

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StaviaLlmPropertiesTest {

    @Test
    void shouldExposeSaneDefaults() {
        StaviaLlmProperties props = new StaviaLlmProperties();
        assertEquals("http://localhost:11434/v1", props.getBaseUrl());
        assertEquals("qwen2.5:7b-instruct", props.getModel());
        assertEquals(3, props.getBreakerFailureThreshold());
        assertEquals(30, props.getBreakerOpenSeconds());
        assertEquals(50, props.getMaxEvidences());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=StaviaLlmPropertiesTest`
Expected: FAIL — classe não existe.

- [ ] **Step 3a: Implementar `StaviaLlmProperties.java`** (JavaBean com defaults; getters/setters; `@ConfigurationProperties`):

```java
package com.projeto.cortex.intelligence.stavia.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cortex.stavia.llm")
public class StaviaLlmProperties {

    private String baseUrl = "http://localhost:11434/v1";
    private String model = "qwen2.5:7b-instruct";
    private String apiKey = "ollama";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 20000;
    private int maxEvidences = 50;
    private double confidenceThreshold = 0.45;
    private int breakerFailureThreshold = 3;
    private int breakerOpenSeconds = 30;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
    public int getMaxEvidences() { return maxEvidences; }
    public void setMaxEvidences(int v) { this.maxEvidences = v; }
    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double v) { this.confidenceThreshold = v; }
    public int getBreakerFailureThreshold() { return breakerFailureThreshold; }
    public void setBreakerFailureThreshold(int v) { this.breakerFailureThreshold = v; }
    public int getBreakerOpenSeconds() { return breakerOpenSeconds; }
    public void setBreakerOpenSeconds(int v) { this.breakerOpenSeconds = v; }
}
```

- [ ] **Step 3b: Atualizar `application.yml`** — sob `cortex.stavia:` (após `generator-mode`):

```yaml
  stavia:
    generator-mode: ${CORTEX_STAVIA_GENERATOR_MODE:deterministic}
    interpreter-mode: ${CORTEX_STAVIA_INTERPRETER_MODE:deterministic}
    llm:
      base-url: ${CORTEX_STAVIA_LLM_BASE_URL:http://localhost:11434/v1}
      model: ${CORTEX_STAVIA_LLM_MODEL:qwen2.5:7b-instruct}
      api-key: ${CORTEX_STAVIA_LLM_API_KEY:ollama}
      connect-timeout-ms: 2000
      read-timeout-ms: ${CORTEX_STAVIA_LLM_READ_TIMEOUT_MS:20000}
      max-evidences: 50
      confidence-threshold: 0.45
      breaker-failure-threshold: 3
      breaker-open-seconds: 30
```

- [ ] **Step 3c: Versões** — adicionar em `StaviaVersions.java`:

```java
public static final String INTERPRETATION = "STAVIA-INTERPRETATION-0.1.0";
public static final String LLM_CHAT_CLIENT = "STAVIA-OLLAMA-CHAT-0.1.0";
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=StaviaLlmPropertiesTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/llm/StaviaLlmProperties.java apps/api/src/main/resources/application.yml apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/version/StaviaVersions.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaLlmPropertiesTest.java
git commit -m "feat(stavia): add LLM config properties and ship-dark flags"
```

---

### Task 10: OllamaChatClient — transporte HTTP (happy path)

**Files:**
- Create: `llm/OllamaChatClient.java`, `llm/OllamaUnavailableException.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/OllamaChatClientTest.java`

**Interfaces:**
- Produces:
  - `record OllamaChatClient.ChatMessage(String role, String content)`
  - `OllamaChatClient(RestClient.Builder builder, StaviaLlmProperties props, Clock clock)`
  - `String chat(List<ChatMessage> messages, double temperature)` — POST `/chat/completions`, body com `model`, `messages`, `temperature`, `response_format={"type":"json_object"}`; retorna `choices[0].message.content`. Lança `OllamaUnavailableException` em não-2xx/timeout/parse.
  - `class OllamaUnavailableException extends RuntimeException`.

- [ ] **Step 1: Escrever o teste que falha** — `OllamaChatClientTest.java` (usa `MockRestServiceServer` ligado ao `RestClient.Builder`):

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.OllamaUnavailableException;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpMethod.POST;

class OllamaChatClientTest {

    private final StaviaLlmProperties props = new StaviaLlmProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-25T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void shouldPostChatAndReturnContent() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
                .andExpect(method(POST))
                .andExpect(jsonPath("$.model").value("qwen2.5:7b-instruct"))
                .andExpect(jsonPath("$.response_format.type").value("json_object"))
                .andRespond(withSuccess(
                        "{\"choices\":[{\"message\":{\"content\":\"{\\\"ok\\\":true}\"}}]}",
                        MediaType.APPLICATION_JSON));

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);
        String content = client.chat(
                List.of(new OllamaChatClient.ChatMessage("user", "oi")), 0.0);

        assertEquals("{\"ok\":true}", content);
        server.verify();
    }

    @Test
    void shouldThrowOnServerError() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://localhost:11434/v1/chat/completions"))
                .andRespond(withServerError());

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);

        assertThrows(OllamaUnavailableException.class, () ->
                client.chat(List.of(new OllamaChatClient.ChatMessage("user", "oi")), 0.0));
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=OllamaChatClientTest`
Expected: FAIL — classes não existem.

- [ ] **Step 3: Implementar** —

`OllamaUnavailableException.java`:
```java
package com.projeto.cortex.intelligence.stavia.llm;

public class OllamaUnavailableException extends RuntimeException {
    public OllamaUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
    public OllamaUnavailableException(String message) {
        super(message);
    }
}
```

`OllamaChatClient.java` (circuit-breaker entra na Task 11; aqui só transporte + parse):
```java
package com.projeto.cortex.intelligence.stavia.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class OllamaChatClient {

    public record ChatMessage(String role, String content) {}

    private final RestClient restClient;
    private final StaviaLlmProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock;

    public OllamaChatClient(RestClient.Builder builder, StaviaLlmProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        this.restClient = builder.baseUrl(props.getBaseUrl()).build();
    }

    public String chat(List<ChatMessage> messages, double temperature) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", props.getModel());
        body.put("temperature", temperature);
        body.put("messages", messages.stream()
                .map(m -> Map.of("role", m.role(), "content", m.content()))
                .toList());
        body.put("response_format", Map.of("type", "json_object"));

        String raw;
        try {
            raw = restClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (RuntimeException exception) {
            throw new OllamaUnavailableException(
                    "Falha ao chamar o modelo local.", exception);
        }

        try {
            JsonNode root = mapper.readTree(raw);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new OllamaUnavailableException("Resposta do modelo sem conteúdo.");
            }
            return content.asText();
        } catch (OllamaUnavailableException e) {
            throw e;
        } catch (Exception exception) {
            throw new OllamaUnavailableException(
                    "Resposta do modelo ilegível.", exception);
        }
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=OllamaChatClientTest`
Expected: PASS (2 testes).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/llm/OllamaChatClient.java apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/llm/OllamaUnavailableException.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/OllamaChatClientTest.java
git commit -m "feat(stavia): add OllamaChatClient HTTP transport with JSON-mode requests"
```

---

### Task 11: Circuit-breaker no OllamaChatClient

Após `breakerFailureThreshold` falhas seguidas, abrir o breaker por `breakerOpenSeconds` (usando o `Clock` injetado): enquanto aberto, `chat(...)` lança imediatamente sem tocar a rede. Sucesso reseta o contador.

**Files:**
- Modify: `llm/OllamaChatClient.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/OllamaChatClientBreakerTest.java`

**Interfaces:**
- Produces: comportamento de breaker (sem novos métodos públicos).

- [ ] **Step 1: Escrever o teste que falha** — `OllamaChatClientBreakerTest.java` (Clock mutável):

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.OllamaUnavailableException;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class OllamaChatClientBreakerTest {

    private final StaviaLlmProperties props = new StaviaLlmProperties();

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-06-25T10:00:00Z");
        public ZoneOffset getZone() { return ZoneOffset.UTC; }
        public Clock withZone(java.time.ZoneId z) { return this; }
        public Instant instant() { return now; }
    }

    @Test
    void shouldOpenAfterThresholdAndNotCallServerUntilWindowPasses() {
        MutableClock clock = new MutableClock();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        // Exatamente 3 falhas esperadas (threshold). A 4ª chamada NÃO deve tocar o servidor.
        for (int i = 0; i < 3; i++) {
            server.expect(anything()).andRespond(withServerError());
        }

        OllamaChatClient client = new OllamaChatClient(builder, props, clock);
        List<OllamaChatClient.ChatMessage> msg =
                List.of(new OllamaChatClient.ChatMessage("user", "oi"));

        for (int i = 0; i < 3; i++) {
            assertThrows(OllamaUnavailableException.class, () -> client.chat(msg, 0.0));
        }
        // breaker aberto: 4ª chamada lança sem tocar o servidor (server.verify() não falha por falta de expectativa)
        assertThrows(OllamaUnavailableException.class, () -> client.chat(msg, 0.0));
        server.verify();

        // Após a janela, ele tenta de novo (precisa de nova expectativa)
        clock.now = clock.now.plus(Duration.ofSeconds(props.getBreakerOpenSeconds() + 1));
        server.expect(anything()).andRespond(withServerError());
        assertThrows(OllamaUnavailableException.class, () -> client.chat(msg, 0.0));
        server.verify();
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=OllamaChatClientBreakerTest`
Expected: FAIL — sem breaker, a 4ª chamada tenta tocar o servidor (sem expectativa) e/ou `verify()` falha.

- [ ] **Step 3: Implementar** — em `OllamaChatClient`, adicionar estado de breaker e checagem no início de `chat`:

```java
// campos:
private int consecutiveFailures = 0;
private java.time.Instant openUntil = java.time.Instant.MIN;

// no início de chat(...), antes de montar o body:
if (clock.instant().isBefore(openUntil)) {
    throw new OllamaUnavailableException("Modelo local indisponível (circuito aberto).");
}

// transformar as falhas: onde hoje lança OllamaUnavailableException no catch do retrieve,
// e no caminho de conteúdo ausente/ilegível, encaminhar por um helper:
private RuntimeException fail(String message, Throwable cause) {
    registerFailure();
    return cause == null
            ? new OllamaUnavailableException(message)
            : new OllamaUnavailableException(message, cause);
}

private void registerFailure() {
    consecutiveFailures++;
    if (consecutiveFailures >= props.getBreakerFailureThreshold()) {
        openUntil = clock.instant().plusSeconds(props.getBreakerOpenSeconds());
        consecutiveFailures = 0;
    }
}

// no sucesso (após obter content válido), antes do return:
consecutiveFailures = 0;
```

Substituir os `throw new OllamaUnavailableException(...)` dos caminhos de erro por `throw fail(...)` para que contem no breaker. (O lançamento por "circuito aberto" no topo NÃO chama `registerFailure`.)

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=OllamaChatClientBreakerTest,OllamaChatClientTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/llm/OllamaChatClient.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/OllamaChatClientBreakerTest.java
git commit -m "feat(stavia): add circuit breaker to OllamaChatClient to fail fast when model is down"
```

---

### Task 12: Prompt de interpretação (com few-shots do "apontador")

**Files:**
- Create: `interpret/StaviaInterpretationPromptBuilder.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaInterpretationPromptBuilderTest.java`

**Interfaces:**
- Produces: `StaviaInterpretationPromptBuilder.build(StaviaQuestion) -> List<OllamaChatClient.ChatMessage>` (1 system + 1 user). O system lista as intenções válidas (nomes do enum), os tipos de entidade (`COLABORADOR`, `ROLE`, `EQUIPAMENTO`, `RDO`, `OBRA`), o formato JSON e few-shots incluindo "Tem apontador?" e "Quem é o apontador da obra?".

- [ ] **Step 1: Escrever o teste que falha:**

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationPromptBuilder;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaInterpretationPromptBuilderTest {

    private final StaviaInterpretationPromptBuilder builder =
            new StaviaInterpretationPromptBuilder();

    @Test
    void shouldBuildSystemAndUserMessagesListingIntents() {
        List<OllamaChatClient.ChatMessage> messages =
                builder.build(new StaviaQuestion("Quem é o apontador da obra?", "u1", "obra-1"));

        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).role());
        assertEquals("user", messages.get(1).role());
        assertTrue(messages.get(0).content().contains("CONSULTAR_EQUIPE"));
        assertTrue(messages.get(0).content().contains("ROLE"));
        assertTrue(messages.get(0).content().toLowerCase().contains("apontador"));
        assertTrue(messages.get(1).content().contains("Quem é o apontador da obra?"));
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=StaviaInterpretationPromptBuilderTest`
Expected: FAIL — classe não existe.

- [ ] **Step 3: Implementar** — `StaviaInterpretationPromptBuilder.java`:

```java
package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Component
public class StaviaInterpretationPromptBuilder {

    private static final String SYSTEM = """
            Você classifica perguntas operacionais de obras e extrai entidades.
            Responda EXCLUSIVAMENTE com um JSON único, sem texto fora dele, no formato:
            {"intent":"<INTENT>","entities":[{"type":"<TIPO>","value":"<texto>"}],
             "attributes":["..."],"confidence":<0..1>}

            INTENT deve ser um destes valores exatos:
            %s

            TIPO de entidade deve ser um destes: COLABORADOR, ROLE, EQUIPAMENTO, RDO, OBRA.
            - COLABORADOR: nome de pessoa citado.
            - ROLE: cargo/função (ex.: apontador, encarregado, engenheiro).
            - EQUIPAMENTO: máquina/veículo/prefixo.
            Não invente entidades que não aparecem na pergunta. Se não houver, use [].

            Exemplos:
            Pergunta: "Tem apontador?"
            {"intent":"CONSULTAR_EQUIPE","entities":[{"type":"ROLE","value":"apontador"}],"attributes":[],"confidence":0.9}
            Pergunta: "Quem é o apontador da obra?"
            {"intent":"CONSULTAR_EQUIPE","entities":[{"type":"ROLE","value":"apontador"}],"attributes":[],"confidence":0.95}
            Pergunta: "Onde o Abner trabalhou?"
            {"intent":"CONSULTAR_ALOCACAO_COLABORADOR","entities":[{"type":"COLABORADOR","value":"Abner"}],"attributes":[],"confidence":0.9}
            Pergunta: "Qual a condição de clima mais recente?"
            {"intent":"CONSULTAR_RDO","entities":[],"attributes":["condicaoManha","condicaoTarde","condicaoNoite"],"confidence":0.85}
            """.formatted(intentList());

    public List<OllamaChatClient.ChatMessage> build(StaviaQuestion question) {
        return List.of(
                new OllamaChatClient.ChatMessage("system", SYSTEM),
                new OllamaChatClient.ChatMessage("user",
                        "Pergunta: \"" + question.text() + "\""));
    }

    private static String intentList() {
        return Arrays.stream(StaviaIntent.values())
                .map(Enum::name)
                .collect(Collectors.joining(", "));
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=StaviaInterpretationPromptBuilderTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/interpret/StaviaInterpretationPromptBuilder.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaInterpretationPromptBuilderTest.java
git commit -m "feat(stavia): add interpretation prompt builder with role/entity few-shots"
```

---

### Task 13: LlmQuestionInterpreter — parse + validação (AC1)

**Files:**
- Create: `interpret/LlmQuestionInterpreter.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/LlmQuestionInterpreterTest.java`

**Interfaces:**
- Consumes: `OllamaChatClient.chat(...)`, `StaviaInterpretationPromptBuilder.build(...)`, `StaviaSemanticCatalog`.
- Produces: `LlmQuestionInterpreter(OllamaChatClient, StaviaInterpretationPromptBuilder, StaviaSemanticCatalog)` implements `StaviaQuestionInterpreter`. `interpret(q)` retorna `Optional<StaviaInterpretation>` (`Origin.LLM`); `Optional.empty()` se: intent inválido, JSON ilegível, confiança fora de [0,1], ou exceção do transporte. Entidades viram `ResolvedEntity` (COLABORADOR→`collaboratorByName`, ROLE→`roleByLabel`); o `OBRA` (worksite) é injetado a partir de `question.obraId()`.

- [ ] **Step 1: Escrever o teste que falha** — usa um `OllamaChatClient` fake (subclasse anônima sobrescrevendo `chat`). Como `chat` não é `final`, dá pra sobrescrever; passe `null` nos colaboradores do construtor do fake e sobrescreva só `chat`.

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.interpret.LlmQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationPromptBuilder;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.springframework.web.client.RestClient;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmQuestionInterpreterTest {

    private LlmQuestionInterpreter interpreterReturning(String cannedJson) {
        OllamaChatClient fake = new OllamaChatClient(
                RestClient.builder(), new StaviaLlmProperties(), Clock.systemUTC()) {
            @Override
            public String chat(List<ChatMessage> messages, double temperature) {
                return cannedJson;
            }
        };
        return new LlmQuestionInterpreter(
                fake, new StaviaInterpretationPromptBuilder(), new StaviaSemanticCatalog());
    }

    private StaviaQuestion q(String text) {
        return new StaviaQuestion(text, "u1", "obra-1");
    }

    @Test
    void paraphrasesProduceSameIntentAndRole() {
        String json = "{\"intent\":\"CONSULTAR_EQUIPE\",\"entities\":[{\"type\":\"ROLE\",\"value\":\"apontador\"}],\"attributes\":[],\"confidence\":0.9}";

        Optional<StaviaInterpretation> a = interpreterReturning(json).interpret(q("Tem apontador?"));
        Optional<StaviaInterpretation> b = interpreterReturning(json).interpret(q("Quem é o apontador da obra?"));

        assertTrue(a.isPresent());
        assertTrue(b.isPresent());
        assertEquals(StaviaIntent.CONSULTAR_EQUIPE, a.get().intent());
        assertEquals(a.get().intent(), b.get().intent());
        assertTrue(a.get().plan().entities().stream()
                .anyMatch(e -> "ROLE".equals(e.type()) && "apontador".equals(e.value())));
    }

    @Test
    void shouldReturnEmptyOnInvalidIntent() {
        String json = "{\"intent\":\"NAO_EXISTE\",\"entities\":[],\"attributes\":[],\"confidence\":0.9}";
        assertTrue(interpreterReturning(json).interpret(q("qualquer")).isEmpty());
    }

    @Test
    void shouldReturnEmptyOnMalformedJson() {
        assertTrue(interpreterReturning("isso não é json").interpret(q("qualquer")).isEmpty());
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=LlmQuestionInterpreterTest`
Expected: FAIL — `LlmQuestionInterpreter` não existe.

- [ ] **Step 3: Implementar** — `LlmQuestionInterpreter.java`:

```java
package com.projeto.cortex.intelligence.stavia.interpret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class LlmQuestionInterpreter implements StaviaQuestionInterpreter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LlmQuestionInterpreter.class);

    private final OllamaChatClient chatClient;
    private final StaviaInterpretationPromptBuilder promptBuilder;
    private final StaviaSemanticCatalog catalog;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmQuestionInterpreter(
            OllamaChatClient chatClient,
            StaviaInterpretationPromptBuilder promptBuilder,
            StaviaSemanticCatalog catalog
    ) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
        this.catalog = catalog;
    }

    @Override
    public Optional<StaviaInterpretation> interpret(StaviaQuestion question) {
        try {
            String content = chatClient.chat(promptBuilder.build(question), 0.0);
            JsonNode root = mapper.readTree(content);

            StaviaIntent intent = parseIntent(root.path("intent").asText(null));
            if (intent == null) {
                return Optional.empty();
            }

            double confidence = root.path("confidence").asDouble(0.0);
            if (confidence < 0.0 || confidence > 1.0) {
                return Optional.empty();
            }

            List<ResolvedEntity> entities = new ArrayList<>();
            if (question.obraId() != null) {
                entities.add(ResolvedEntity.worksiteById(question.obraId()));
            }
            for (JsonNode entity : root.path("entities")) {
                String type = entity.path("type").asText("");
                String value = entity.path("value").asText("");
                if (value.isBlank()) {
                    continue;
                }
                switch (type) {
                    case "COLABORADOR" -> entities.add(ResolvedEntity.collaboratorByName(value));
                    case "ROLE" -> entities.add(ResolvedEntity.roleByLabel(value));
                    default -> { /* tipos não suportados são ignorados com segurança */ }
                }
            }

            StaviaQueryPlan plan = new StaviaQueryPlan(
                    domainFor(intent), QueryOperation.READ_ATTRIBUTE,
                    entities, TemporalFilter.none(),
                    List.of(), List.of(), List.of(), List.of(),
                    false, false, false);

            return Optional.of(new StaviaInterpretation(
                    new StaviaClassification(intent, confidence), plan, Origin.LLM));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            LOGGER.warn("Interpretação LLM descartada: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private StaviaIntent parseIntent(String value) {
        if (value == null) {
            return null;
        }
        try {
            return StaviaIntent.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private QueryDomain domainFor(StaviaIntent intent) {
        return switch (intent) {
            case CONSULTAR_RDO, CONSULTAR_PROGRAMACAO -> QueryDomain.RDO;
            case CONSULTAR_EQUIPE -> QueryDomain.EQUIPE;
            case CONSULTAR_ALOCACAO_COLABORADOR, CONSULTAR_FREQUENCIA, CONSULTAR_BANCO_HORAS ->
                    QueryDomain.COLABORADOR;
            case CONSULTAR_ATIVO -> QueryDomain.EQUIPAMENTO;
            case CONSULTAR_RECEITA, CONSULTAR_MARGEM, CONSULTAR_PREVISAO_FINANCEIRA,
                    CONSULTAR_PRODUCAO, CONSULTAR_RECEITA_EM_RISCO, CONSULTAR_PDOC ->
                    QueryDomain.FINANCEIRO;
            case CONSULTAR_OBRA, CONSULTAR_ESTADO_ATUAL -> QueryDomain.OBRA;
            default -> QueryDomain.DESCONHECIDO;
        };
    }
}
```

> Nota de implementação: confirmar os nomes das constantes de `QueryDomain` (ex.: `RDO`, `EQUIPE`, `COLABORADOR`, `EQUIPAMENTO`, `FINANCEIRO`, `OBRA`, `DESCONHECIDO`) abrindo `planning/QueryDomain.java`; ajustar o `switch` se algum nome divergir. O catálogo (`catalog`) será usado para interseccionar `attributes` numa iteração futura; nesta task ele já é injetado para manter a assinatura estável.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=LlmQuestionInterpreterTest`
Expected: PASS (3 testes) — incluindo a igualdade de interpretação entre "Tem apontador?" e "Quem é o apontador da obra?" (AC1).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/interpret/LlmQuestionInterpreter.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/LlmQuestionInterpreterTest.java
git commit -m "feat(stavia): add LLM question interpreter with strict validation and fallback signal"
```

---

### Task 14: OllamaStaviaModelClient (geração) com fallback determinístico (AC3)

**Files:**
- Create: `generation/OllamaStaviaModelClient.java`
- Modify: `generation/DeterministicStaviaModelClient.java` (sem mudança funcional; garantir que continua `@Component`)
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/OllamaStaviaModelClientTest.java`

**Interfaces:**
- Consumes: `OllamaChatClient`, `StaviaPrompt`, `DeterministicStaviaModelClient` (fallback).
- Produces: `OllamaStaviaModelClient(OllamaChatClient, DeterministicStaviaModelClient)` implements `StaviaModelClient`. `generate(prompt)` chama o modelo, parseia `{text, answerType, sourceKeys}` → `StaviaModelResponse`; em qualquer falha (transporte/JSON), delega a `deterministicFallback.generate(prompt)`. Anotado `@Component @Primary @ConditionalOnProperty(prefix="cortex.stavia", name="generator-mode", havingValue="prompt")`.

- [ ] **Step 1: Escrever o teste que falha:**

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.OllamaStaviaModelClient;
import com.projeto.cortex.intelligence.stavia.generation.StaviaModelResponse;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.OllamaUnavailableException;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptEvidence;
import com.projeto.cortex.intelligence.stavia.version.StaviaVersions;
import org.springframework.web.client.RestClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OllamaStaviaModelClientTest {

    private StaviaPrompt prompt() {
        return new StaviaPrompt(
                StaviaVersions.PROMPT, "Instrução.", "Qual foi o último RDO?", "CONSULTAR_RDO",
                List.of(new StaviaPromptEvidence(
                        "RDO:rdo-1", "RDO", "O RDO 1 foi registrado",
                        Instant.parse("2026-06-22T12:00:00Z"), true, Map.of())));
    }

    private OllamaStaviaModelClient clientReturning(String canned, boolean fail) {
        OllamaChatClient chat = new OllamaChatClient(
                RestClient.builder(), new StaviaLlmProperties(), Clock.systemUTC()) {
            @Override
            public String chat(List<ChatMessage> messages, double temperature) {
                if (fail) throw new OllamaUnavailableException("down");
                return canned;
            }
        };
        return new OllamaStaviaModelClient(chat, new DeterministicStaviaModelClient());
    }

    @Test
    void shouldParseModelJson() {
        String json = "{\"text\":\"O último RDO é o RDO 1.\",\"answerType\":\"FATO\",\"sourceKeys\":[\"RDO:rdo-1\"]}";
        StaviaModelResponse response = clientReturning(json, false).generate(prompt());

        assertEquals(StaviaAnswerType.FATO, response.answerType());
        assertEquals(List.of("RDO:rdo-1"), response.sourceKeys());
        assertTrue(response.text().contains("RDO 1"));
    }

    @Test
    void shouldFallBackToDeterministicOnFailure() {
        StaviaModelResponse response = clientReturning(null, true).generate(prompt());
        // o fallback determinístico concatena os resumos e cita a fonte
        assertEquals(List.of("RDO:rdo-1"), response.sourceKeys());
        assertTrue(response.text().contains("O RDO 1 foi registrado"));
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=OllamaStaviaModelClientTest`
Expected: FAIL — classe não existe.

- [ ] **Step 3: Implementar** — `OllamaStaviaModelClient.java`:

```java
package com.projeto.cortex.intelligence.stavia.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;
import com.projeto.cortex.intelligence.stavia.prompt.StaviaPromptEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@Primary
@ConditionalOnProperty(prefix = "cortex.stavia", name = "generator-mode", havingValue = "prompt")
public class OllamaStaviaModelClient implements StaviaModelClient {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(OllamaStaviaModelClient.class);

    private final OllamaChatClient chatClient;
    private final DeterministicStaviaModelClient fallback;
    private final ObjectMapper mapper = new ObjectMapper();

    public OllamaStaviaModelClient(
            OllamaChatClient chatClient,
            DeterministicStaviaModelClient fallback
    ) {
        this.chatClient = chatClient;
        this.fallback = fallback;
    }

    @Override
    public StaviaModelResponse generate(StaviaPrompt prompt) {
        try {
            List<OllamaChatClient.ChatMessage> messages = List.of(
                    new OllamaChatClient.ChatMessage("system", systemMessage(prompt)),
                    new OllamaChatClient.ChatMessage("user", userMessage(prompt)));

            String content = chatClient.chat(messages, 0.3);
            JsonNode root = mapper.readTree(content);

            String text = root.path("text").asText("");
            if (text.isBlank()) {
                return fallback.generate(prompt);
            }
            StaviaAnswerType answerType = parseAnswerType(root.path("answerType").asText(""));
            List<String> sourceKeys = new ArrayList<>();
            for (JsonNode key : root.path("sourceKeys")) {
                sourceKeys.add(key.asText());
            }
            return new StaviaModelResponse(text, answerType, sourceKeys);
        } catch (Exception exception) {
            LOGGER.warn("Geração LLM falhou ({}); usando gerador determinístico.",
                    exception.getMessage());
            return fallback.generate(prompt);
        }
    }

    private StaviaAnswerType parseAnswerType(String value) {
        try {
            return StaviaAnswerType.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return StaviaAnswerType.FATO;
        }
    }

    private String systemMessage(StaviaPrompt prompt) {
        return prompt.systemInstruction()
                + "\nResponda em JSON: {\"text\":\"...\",\"answerType\":\"FATO|INFERENCIA|RECOMENDACAO|INFORMACAO_INSUFICIENTE\",\"sourceKeys\":[\"...\"]}."
                + " Cite em sourceKeys apenas as chaves fornecidas nas evidências.";
    }

    private String userMessage(StaviaPrompt prompt) {
        StringBuilder builder = new StringBuilder();
        builder.append("Pergunta: ").append(prompt.userQuestion()).append("\n\nEvidências:\n");
        for (StaviaPromptEvidence evidence : prompt.evidences()) {
            builder.append("- sourceKey=").append(evidence.sourceKey())
                    .append(" | ").append(evidence.summary()).append("\n");
        }
        return builder.toString();
    }
}
```

> Confirmar os nomes de `StaviaAnswerType` (ex.: `FATO`, `INFERENCIA`, `RECOMENDACAO`, `INFORMACAO_INSUFICIENTE`) em `model/StaviaAnswerType.java`; ajustar a string do system e o `parseAnswerType` se divergir.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=OllamaStaviaModelClientTest`
Expected: PASS (2 testes — parse e fallback).

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/generation/OllamaStaviaModelClient.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/OllamaStaviaModelClientTest.java
git commit -m "feat(stavia): add Ollama-backed grounded model client with deterministic fallback"
```

---

### Task 15: Fiação Spring do coordinator + pipeline ponta-a-ponta + suíte completa

Conectar o `StaviaInterpretationCoordinator` como bean (lendo `interpreter-mode` e `confidence-threshold`), injetar o `OllamaChatClient`/`Clock` via configuração, e provar a robustez a paráfrase pelo serviço inteiro com um `OllamaChatClient` fake. Por fim, rodar a suíte toda no JDK 21.

**Files:**
- Create: `interpret/StaviaInterpretationConfiguration.java` (bean do coordinator + Clock + RestClient.Builder se necessário)
- Modify: `StaviaQueryService.java` (construtor `@Autowired` passa a receber o coordinator)
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaParaphrasePipelineTest.java`

**Interfaces:**
- Consumes: `StaviaInterpretationCoordinator`, `DeterministicQuestionInterpreter`, `LlmQuestionInterpreter`, `StaviaLlmProperties`.
- Produces: bean `StaviaInterpretationCoordinator` (mode de `cortex.stavia.interpreter-mode`); `OllamaChatClient` bean (RestClient.Builder + Clock.systemUTC); `StaviaQueryService` recebe o coordinator por construtor.

- [ ] **Step 1: Escrever o teste que falha** — pipeline com fake interpreter forçando o caminho LLM, provando que duas paráfrases caem na mesma intenção e ambas respondem (AC1/AC2 ponta-a-ponta). Construir o `StaviaQueryService` com um coordinator em modo `llm` cujo LLM é um fake determinístico:

```java
package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.Origin;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationCoordinator;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeOrchestrator;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import com.projeto.cortex.intelligence.stavia.access.StaviaAccessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StaviaParaphrasePipelineTest {

    private StaviaInterpretation roleInterpretation(String obraId) {
        StaviaQueryPlan plan = new StaviaQueryPlan(
                QueryDomain.EQUIPE, QueryOperation.READ_ATTRIBUTE,
                List.of(ResolvedEntity.worksiteById(obraId), ResolvedEntity.roleByLabel("apontador")),
                TemporalFilter.none(), List.of(), List.of(), List.of(), List.of(),
                false, false, false);
        return new StaviaInterpretation(
                new StaviaClassification(StaviaIntent.CONSULTAR_EQUIPE, 0.95), plan, Origin.LLM);
    }

    @Test
    void twoParaphrasesYieldSameIntentAndAnswer() {
        StaviaQuestionInterpreter llm = q -> Optional.of(roleInterpretation(q.obraId()));
        StaviaInterpretationCoordinator coordinator = new StaviaInterpretationCoordinator(
                new DeterministicQuestionInterpreter(
                        new StaviaIntentClassifier(),
                        new com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner(
                                new com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog())),
                llm, "llm", 0.45);

        StaviaKnowledgeSource team = new StaviaKnowledgeSource() {
            public String sourceName() { return "equipe-rdos"; }
            public String sourceVersion() { return "T-1"; }
            public boolean supports(StaviaKnowledgeRequest r) {
                return r.intent() == StaviaIntent.CONSULTAR_EQUIPE;
            }
            public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest r) {
                return List.of(new StaviaEvidence(
                        StaviaEvidenceTypes.EQUIPE, "EQUIPE:1",
                        "Apontador: Maria Souza no RDO-10.", Instant.now(), true,
                        Map.of("cargo", "Apontador", "colaboradorNome", "Maria Souza")));
            }
        };

        StaviaEngine engine = new StaviaEngine(
                new StaviaIntentClassifier(), new StaviaEvidenceSelector(),
                new StaviaGroundingValidator(), new StaviaEvidenceQualityPolicy(),
                new StaviaContradictionPolicy(), new DeterministicStaviaResponseGenerator());

        StaviaAccessPolicy policy = new StaviaAccessPolicy() {
            public Set<String> permissionsFor(String userId) {
                return Set.of(StaviaEngine.REQUIRED_PERMISSION);
            }
            public boolean canAccessWorksite(String userId, String worksiteId) { return true; }
        };

        StaviaQueryService service = new StaviaQueryService(
                new StaviaIntentClassifier(),
                new StaviaKnowledgeOrchestrator(List.of(team)),
                new StaviaContextBuilder(), engine, policy, coordinator);

        StaviaQueryResult a = service.query(
                new StaviaQuestion("Tem apontador?", "u1", "obra-1"));
        StaviaQueryResult b = service.query(
                new StaviaQuestion("Quem é o apontador dessa obra?", "u1", "obra-1"));

        assertEquals(StaviaIntent.CONSULTAR_EQUIPE, a.intent());
        assertEquals(a.intent(), b.intent());
        assertFalse(a.answer().insufficientData());
        assertFalse(b.answer().insufficientData());
    }
}
```

> Este teste consome o **novo construtor de 6 args** de `StaviaQueryService(intentClassifier, orchestrator, contextBuilder, engine, accessPolicy, coordinator)` — adicioná-lo nesta task.

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=StaviaParaphrasePipelineTest`
Expected: FAIL — construtor de 6 args de `StaviaQueryService` ainda não existe.

- [ ] **Step 3a: Implementar o construtor de 6 args** em `StaviaQueryService` (recebe o coordinator pronto e usa-o; os outros campos como hoje). Manter o de 5 args (determinístico) e o `@Autowired` migra para o de 6 args.

- [ ] **Step 3b: Implementar `StaviaInterpretationConfiguration.java`:**

```java
package com.projeto.cortex.intelligence.stavia.interpret;

import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.llm.StaviaLlmProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.time.Clock;

@Configuration
public class StaviaInterpretationConfiguration {

    @Bean
    public Clock staviaClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OllamaChatClient ollamaChatClient(StaviaLlmProperties props, Clock staviaClock) {
        return new OllamaChatClient(RestClient.builder(), props, staviaClock);
    }

    @Bean
    public StaviaInterpretationCoordinator staviaInterpretationCoordinator(
            DeterministicQuestionInterpreter deterministic,
            LlmQuestionInterpreter llm,
            StaviaLlmProperties props,
            @Value("${cortex.stavia.interpreter-mode:deterministic}") String mode
    ) {
        return new StaviaInterpretationCoordinator(
                deterministic, llm, mode, props.getConfidenceThreshold());
    }
}
```

> Em modo `deterministic` (default), o coordinator ignora o `llm` — então mesmo sem Ollama no ar o contexto Spring sobe e o comportamento é o atual. O `OllamaChatClient` é instanciado mas só é tocado quando `interpreter-mode`/`generator-mode` saem de `deterministic`.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=StaviaParaphrasePipelineTest`
Expected: PASS.

- [ ] **Step 5: Rodar a suíte inteira do pacote no JDK 21**

Run: `export JAVA_HOME=/opt/homebrew/Cellar/openjdk@21/21.0.11/libexec/openjdk.jdk/Contents/Home && ./mvnw -q test -Dtest='com.projeto.cortex.intelligence.stavia.*'`
Expected: PASS — toda a suíte Stav.IA verde (regressões + novos). Em particular `StaviaSpringContextTest` deve subir o contexto com os novos beans.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/interpret/StaviaInterpretationConfiguration.java apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/StaviaQueryService.java apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/StaviaParaphrasePipelineTest.java
git commit -m "feat(stavia): wire interpretation coordinator and prove paraphrase robustness end-to-end"
```

---

## Self-Review

**Cobertura do spec × tarefas:**
- §5 intérprete (intent+entidades) → Tasks 6, 7, 12, 13.
- §6 completude (entidade/role + fim do truncamento) → Tasks 2, 3, 4, 5.
- §7 cliente Ollama + config → Tasks 9, 10, 11, 14, 15.
- §8 erros/latência/breaker → Tasks 7 (fallback), 11 (breaker), 14 (fallback de geração).
- §9 testes sem modelo no ar → todas as tasks usam fakes/`MockRestServiceServer`.
- §12 R1 (chave de evidência) → Task 1. Wiring de bean ambíguo → Task 14 (`@Primary`).
- AC1 paráfrase → Tasks 13, 15. AC2 completude → Tasks 2, 5. AC3 grounding/fallback → Tasks 1, 14. AC4 fallback → Task 7. AC5 ship-dark → Tasks 8, 9, 15. AC6 role → Task 4. AC7 JDK21 → Global Constraints + Task 15.

**Pontos a validar durante a execução** (anotados nas tasks, não placeholders): nomes das constantes de `QueryDomain` (Task 13) e `StaviaAnswerType` (Task 14); estilo de montagem de lista em `TeamKnowledgeSource` (Task 4); campos exatos de `TeamRecord` (Task 4). Todos verificáveis abrindo o arquivo citado antes do passo de implementação.

**Consistência de tipos:** `StaviaInterpretation`, `Origin`, `StaviaEntityFilters`, `StaviaQuestionInterpreter` (Optional) usados de forma idêntica entre tasks 6–15. Construtores de `StaviaQueryService` (5 e 6 args) consistentes entre tasks 8 e 15.
