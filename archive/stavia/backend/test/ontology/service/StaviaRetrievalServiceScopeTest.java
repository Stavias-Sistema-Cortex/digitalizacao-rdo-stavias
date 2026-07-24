package com.projeto.cortex.intelligence.stavia.ontology.service;

import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEntity;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalContext;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalIntent;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaviaRetrievalServiceScopeTest {

    @Test
    void doesNotResolveAnOntologyEntityOutsideTheAuthorizedWorksite() {
        StaviaOntologyService ontologyService = mock(StaviaOntologyService.class);
        StaviaRetrievalService retrievalService = new StaviaRetrievalService(ontologyService);
        OntologyEntity entityFromAuthorizedWorksite = new OntologyEntity(
                "entity-a", "RDO", "rdo", "rdo-a", "RDO A", null,
                "RASCUNHO", Map.of("obraId", "obra-a"), null, null
        );

        when(ontologyService.resolveEntityInWorksite("rdo-b", "obra-a"))
                .thenReturn(Optional.of(entityFromAuthorizedWorksite));
        when(ontologyService.relationsForEntities(anyList(), anyInt()))
                .thenReturn(List.of());
        when(ontologyService.eventsForEntities(anyList(), anyInt()))
                .thenReturn(List.of());
        when(ontologyService.statesForEntities(anyList(), anyInt()))
                .thenReturn(List.of());
        when(ontologyService.evidencesForEntities(anyList(), anyInt()))
                .thenReturn(List.of());
        when(ontologyService.decisionsForEntity("entity-a"))
                .thenReturn(List.of());

        StaviaOperationalContext context = retrievalService.retrieve(
                "rdo-b", "obra-a", null, StaviaOperationalIntent.SEARCH_ENTITY
        );

        assertEquals(List.of("entity-a"), context.entityIds());
        verify(ontologyService).resolveEntityInWorksite("rdo-b", "obra-a");
        verify(ontologyService, never()).resolveEntity("rdo-b");
        verify(ontologyService, never()).listEntitiesForWorksite(
                eq("obra-a"), eq(null), eq("rdo-b"), eq(10)
        );
    }
}
