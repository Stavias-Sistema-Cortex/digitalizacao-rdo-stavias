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
    void shouldTolerateOneCharacterMistakeInAFullCollaboratorName() {
        StaviaEntityFilters filters = StaviaEntityFilters.from(
                List.of(
                        ResolvedEntity.collaboratorByName(
                                "Aber Pereira Lanza"
                        )
                )
        );

        assertTrue(
                filters.matchesCollaborator(
                        "ABNER PEREIRA LANZA"
                )
        );
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
