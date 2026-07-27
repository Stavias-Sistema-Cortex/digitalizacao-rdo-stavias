package com.projeto.cortex.intelligence.stavia.ontology.service;

import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEntity;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalContext;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalIntent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaResponseFormatterTest {

    private final StaviaResponseFormatter formatter = new StaviaResponseFormatter();

    @Test
    void shouldFormatEntityExplanationInPortuguese() {
        OntologyEntity entity = new OntologyEntity(
                "ENT-1",
                "OBRA",
                "obra",
                "obra-1",
                "Obra de teste",
                "Descrição de teste",
                "ATIVA",
                Map.of("obraId", "obra-1"),
                Instant.now(),
                Instant.now()
        );

        StaviaOperationalContext context = new StaviaOperationalContext(
                "obra-1",
                StaviaOperationalIntent.EXPLAIN_ENTITY,
                "Explique a obra",
                "this_week",
                List.of(entity),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        String answer = formatter.format(context, null);

        assertTrue(answer.contains("Entidade encontrada"));
        assertTrue(answer.contains("Obra de teste"));
        assertTrue(answer.contains("IDs usados"));
    }
}
