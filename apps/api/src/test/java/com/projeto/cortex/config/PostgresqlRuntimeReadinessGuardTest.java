package com.projeto.cortex.config;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresqlRuntimeReadinessGuardTest {

    @Test
    void configuredRuntimeRequiresTheCompleteV52Chain() throws Exception {
        var field = PostgresqlRuntimeReadinessGuard.class.getDeclaredField(
                "CLEAN_START_REQUIRED_SCHEMA_VERSION"
        );
        field.setAccessible(true);

        assertThat(field.get(null)).isEqualTo("52");
    }

    @Test
    void executesAsAnEarlyBeanFactoryPreflight() {
        PostgresqlRuntimeReadinessGuard guard = guard(mock(JdbcTemplate.class), true, released());

        assertThat(BeanFactoryPostProcessor.class)
                .isAssignableFrom(PostgresqlRuntimeReadinessGuard.class);
        assertThat(guard.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
    }

    @Test
    void refusesWhenOwnerRuntimeFlagIsFalse() {
        assertThatThrownBy(() -> guard(mock(JdbcTemplate.class), false, released())
                .verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CORTEX_POSTGRES_RUNTIME_READY");
    }

    @Test
    void trueFlagStillRefusesTheEmptyCleanStartSurfaceRegistry() {
        PostgresqlRuntimeSurfaceRegistry emptyRegistry =
                new PostgresqlRuntimeSurfaceRegistry(Set.of());

        assertThat(emptyRegistry.releasedSurfaces()).isEmpty();
        assertThatThrownBy(() -> guard(mock(JdbcTemplate.class), true, emptyRegistry)
                .verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conjunto completo e exato");
    }

    @Test
    void registryPublishesTheExactImmutableFiveSurfaceContract() {
        PostgresqlRuntimeSurfaceRegistry registry =
                new PostgresqlRuntimeSurfaceRegistry();

        assertThat(registry.releasedSurfaces()).containsExactlyInAnyOrder(
                "authentication",
                "finance",
                "memory-ontology",
                "rdo",
                "sync"
        );
        assertThatThrownBy(() -> registry.releasedSurfaces().add("unexpected"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void refusesIncompleteOrUnexpectedSurfaceSetsBeforeDatabaseWork() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PostgresqlRuntimeSurfaceRegistry incomplete =
                new PostgresqlRuntimeSurfaceRegistry(Set.of(
                        "authentication", "finance", "memory-ontology", "rdo"
                ));
        PostgresqlRuntimeSurfaceRegistry unexpected =
                new PostgresqlRuntimeSurfaceRegistry(Set.of(
                        "authentication", "finance", "memory-ontology", "rdo",
                        "sync", "unexpected"
                ));

        assertThatThrownBy(() -> guard(jdbcTemplate, true, incomplete)
                .verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conjunto completo e exato");
        assertThatThrownBy(() -> guard(jdbcTemplate, true, unexpected)
                .verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("conjunto completo e exato");
    }

    @Test
    void refusesWhenTheExplicitV52RowIsAbsent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        assertThatThrownBy(() -> guard(jdbcTemplate, true, released()).verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cadeia de migrações até V52");
        verify(jdbcTemplate).queryForObject(contains("version = '52'"), eq(Integer.class));
    }

    @Test
    void refusesWithoutAVerifiedActiveAlfa() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 0);

        assertThatThrownBy(() -> guard(jdbcTemplate, true, released()).verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ALFA ativo")
                .hasMessageContaining("e-mail verificado");
    }

    @Test
    void acceptsOnlyV52VerifiedAlfaOwnerFlagAndReleasedSurfaceTogether() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1);

        assertThatCode(() -> guard(jdbcTemplate, true, released()).verifyReadiness())
                .doesNotThrowAnyException();
    }

    @Test
    void probesPostgresqlWithANativeTrueBooleanForEndpointReadiness() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT TRUE", Boolean.class)).thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1);

        assertThatCode(() -> guard(jdbcTemplate, true, released())
                .verifyRuntimeReadiness()).doesNotThrowAnyException();
    }

    private PostgresqlRuntimeReadinessGuard guard(
            JdbcTemplate jdbcTemplate,
            boolean runtimeReady,
            PostgresqlRuntimeSurfaceRegistry registry
    ) {
        return new PostgresqlRuntimeReadinessGuard(
                jdbcTemplate, "52", runtimeReady, registry
        );
    }

    private PostgresqlRuntimeSurfaceRegistry released() {
        return new PostgresqlRuntimeSurfaceRegistry();
    }
}
