package com.projeto.cortex.config;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresqlRuntimeReadinessGuardTest {

    @Test
    void configuredRuntimeRequiresTheCompleteV63Chain() throws Exception {
        assertThat(PostgresqlSchemaVersion.REQUIRED).isEqualTo("63");
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
    void refusesWhenTheExplicitV63RowIsAbsent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        assertThatThrownBy(() -> guard(jdbcTemplate, true, released()).verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cadeia de migrações até V63");
        verify(jdbcTemplate).queryForObject(contains("version = '63'"), eq(Integer.class));
    }

    @Test
    void refusesWithoutAnActiveAcademyIdentityWithCurrentHmacMaterial() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 0);

        assertThatThrownBy(() -> guard(jdbcTemplate, true, released()).verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Academy ativa")
                .hasMessageContaining("HMAC atual");
    }

    @Test
    void acceptsOnlyV63AcademyIdentityOwnerFlagAndReleasedSurfaceTogether() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1);

        assertThatCode(() -> guard(jdbcTemplate, true, released()).verifyReadiness())
                .doesNotThrowAnyException();
    }

    @Test
    void readinessAcceptsOnlyAnActiveAcademyIdentityWithCurrentHmac() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                contains("flyway_schema_history"),
                eq(Integer.class)
        )).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("ai.cpf_lookup_hmac IS NOT NULL"),
                eq(Integer.class)
        )).thenReturn(1);

        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());

        assertThatCode(readinessGuard::verifyReadiness)
                .doesNotThrowAnyException();
        verify(jdbcTemplate).queryForObject(
                contains("c.banco_origem = 'dbstavias_acad'"),
                eq(Integer.class)
        );
    }

    @Test
    void probesPostgresqlWithANativeTrueBooleanForEndpointReadiness() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject("SELECT TRUE", Boolean.class)).thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1);

        assertThatCode(() -> guard(jdbcTemplate, true, released())
                .verifyRuntimeReadiness()).doesNotThrowAnyException();
    }

    @Test
    void disabledAcademySchedulerDoesNotRequireOrQuerySyncFreshness() {
        JdbcTemplate jdbcTemplate = readyPostgresql();

        assertThatCode(() -> guardWithAcademySync(
                jdbcTemplate,
                false,
                900_000L
        ).verifyRuntimeReadiness()).doesNotThrowAnyException();

        verify(jdbcTemplate, never()).queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        );
    }

    @Test
    void enabledAcademySchedulerMayStartBeforeItsFirstCompletedRun() {
        JdbcTemplate jdbcTemplate = readyPostgresql();

        assertThatCode(() -> guardWithAcademySync(
                jdbcTemplate,
                true,
                900_000L
        ).verifyReadiness()).doesNotThrowAnyException();

        verify(jdbcTemplate, never()).queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        );
    }

    @Test
    void endpointReadinessRefusesWhenThereIsNoCompletedRun() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        )).thenReturn(List.of());

        assertThatThrownBy(() -> guardWithAcademySync(
                jdbcTemplate,
                true,
                900_000L
        ).verifyRuntimeReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sincronização Academy")
                .hasMessageContaining("recente")
                .hasMessageContaining("bem-sucedida");
    }

    @Test
    void enabledAcademySchedulerRefusesAStaleSuccessfulRun() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        LocalDateTime databaseNow = LocalDateTime.of(2026, 7, 28, 12, 0);
        when(jdbcTemplate.queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        )).thenReturn(List.of(academyRun(
                "SUCCESS",
                databaseNow.minusMinutes(16),
                databaseNow
        )));

        assertThatThrownBy(() -> guardWithAcademySync(
                jdbcTemplate,
                true,
                900_000L
        ).verifyRuntimeReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sincronização Academy")
                .hasMessageContaining("recente");
    }

    @Test
    void enabledAcademySchedulerAcceptsAFreshSuccessfulRun() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        LocalDateTime databaseNow = LocalDateTime.of(2026, 7, 28, 12, 0);
        when(jdbcTemplate.queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        )).thenReturn(List.of(academyRun(
                "SUCCESS",
                databaseNow.minusMinutes(5),
                databaseNow
        )));

        assertThatCode(() -> guardWithAcademySync(
                jdbcTemplate,
                true,
                900_000L
        ).verifyRuntimeReadiness()).doesNotThrowAnyException();

        verify(jdbcTemplate).queryForList(
                contains("ORDER BY started_at DESC, id DESC"),
                eq("acad_colaborador_import")
        );
    }

    @Test
    void enabledAcademySchedulerFailsClosedAndRedactsQueryErrors() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        )).thenThrow(new DataAccessResourceFailureException(
                "jdbc:mysql://academy.invalid/db?password=do-not-leak"
        ));

        assertThatThrownBy(() -> guardWithAcademySync(
                jdbcTemplate,
                true,
                900_000L
        ).verifyRuntimeReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PostgreSQL Córtex não está pronto para validar a sincronização Academy.")
                .hasMessageNotContaining("jdbc:mysql")
                .hasMessageNotContaining("do-not-leak")
                .hasNoCause();
    }

    @Test
    void endpointReadinessRefusesAFutureSuccessfulRun() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        LocalDateTime databaseNow = LocalDateTime.of(2026, 7, 28, 12, 0);
        when(jdbcTemplate.queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        )).thenReturn(List.of(academyRun(
                "SUCCESS",
                databaseNow.plusSeconds(1),
                databaseNow
        )));

        assertThatThrownBy(() -> guardWithAcademySync(
                jdbcTemplate,
                true,
                900_000L
        ).verifyRuntimeReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sincronização Academy")
                .hasMessageContaining("recente");
    }

    private PostgresqlRuntimeReadinessGuard guard(
            JdbcTemplate jdbcTemplate,
            boolean runtimeReady,
            PostgresqlRuntimeSurfaceRegistry registry
    ) {
        return new PostgresqlRuntimeReadinessGuard(
                jdbcTemplate, "63", runtimeReady, registry
        );
    }

    private PostgresqlRuntimeReadinessGuard guardWithAcademySync(
            JdbcTemplate jdbcTemplate,
            boolean academySyncEnabled,
            long readinessMaxAgeMs
    ) {
        return new PostgresqlRuntimeReadinessGuard(
                jdbcTemplate,
                "63",
                true,
                released(),
                academySyncEnabled,
                readinessMaxAgeMs
        );
    }

    private JdbcTemplate readyPostgresql() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                "SELECT TRUE",
                Boolean.class
        )).thenReturn(true);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1);
        return jdbcTemplate;
    }

    private Map<String, Object> academyRun(
            String status,
            LocalDateTime finishedAt,
            LocalDateTime databaseNow
    ) {
        return Map.of(
                "status", status,
                "finished_at", Timestamp.valueOf(finishedAt),
                "database_now", Timestamp.valueOf(databaseNow)
        );
    }

    private PostgresqlRuntimeSurfaceRegistry released() {
        return new PostgresqlRuntimeSurfaceRegistry();
    }
}
