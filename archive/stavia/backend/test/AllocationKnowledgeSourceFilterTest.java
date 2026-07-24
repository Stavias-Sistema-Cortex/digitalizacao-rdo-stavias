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
