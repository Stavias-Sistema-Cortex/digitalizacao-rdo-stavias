package com.projeto.cortex.config;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresqlRuntimeReadinessGuardTest {

    @Test
    void configuredRuntimeRequiresTheCompleteV70Chain() throws Exception {
        assertThat(PostgresqlSchemaVersion.REQUIRED).isEqualTo("70");
    }

    @Test
    void executesAsAnEarlyBeanFactoryPreflight() {
        PostgresqlRuntimeReadinessGuard guard = guard(mock(JdbcTemplate.class), true, released());

        assertThat(BeanFactoryPostProcessor.class)
                .isAssignableFrom(PostgresqlRuntimeReadinessGuard.class);
        assertThat(guard.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 2);
    }

    @Test
    void configuredRuntimePreflightUsesTheJvmTrustStoreForVerifyFullTls() {
        PostgresqlRuntimeReadinessGuard guard = new PostgresqlRuntimeReadinessGuard();
        guard.setEnvironment(new MockEnvironment()
                .withProperty(
                        "spring.datasource.url",
                        "jdbc:postgresql://ep-contract.us-east-2.aws.neon.tech/"
                                + "Sta" + "vias"
                                + "Cortex?sslmode=verify-full&channelBinding=require"
                )
                .withProperty("spring.datasource.username", "cortex_contract")
                .withProperty("spring.datasource.password", "contract-password"));

        JdbcTemplate jdbcTemplate = ReflectionTestUtils.invokeMethod(
                guard,
                "postgresqlJdbcTemplate"
        );
        DriverManagerDataSource dataSource = (DriverManagerDataSource)
                jdbcTemplate.getDataSource();

        assertThat(dataSource.getConnectionProperties())
                .containsEntry(
                        "sslfactory",
                        "org.postgresql.ssl.DefaultJavaSSLFactory"
                );
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
    void refusesWhenTheExplicitV70RowIsAbsent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);

        assertThatThrownBy(() -> guard(jdbcTemplate, true, released()).verifyReadiness())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cadeia de migrações até V70");
        verify(jdbcTemplate).queryForObject(contains("version = '70'"), eq(Integer.class));
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
    void acceptsOnlyV70AcademyIdentityOwnerFlagAndReleasedSurfaceTogether() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1, 1);

        assertThatCode(() -> guard(jdbcTemplate, true, released()).verifyReadiness())
                .doesNotThrowAnyException();
    }

    @Test
    void readinessAcceptsOnlyAnActiveAcademyIdentityWithCurrentHmac() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
                contains("FROM public.flyway_schema_history"),
                eq(Integer.class)
        )).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("FROM public.colaborador c"),
                eq(Integer.class)
        )).thenReturn(1);

        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());

        assertThatCode(readinessGuard::verifyReadiness)
                .doesNotThrowAnyException();
        verify(jdbcTemplate).queryForObject(
                contains("JOIN public.auth_identity ai"),
                eq(Integer.class)
        );
        verify(jdbcTemplate).queryForObject(
                contains("c.banco_origem = 'dbstavias_acad'"),
                eq(Integer.class)
        );
        verify(jdbcTemplate).queryForObject(
                contains("ai.status = 'ATIVA'"),
                eq(Integer.class)
        );
        verify(jdbcTemplate).queryForObject(
                contains("ai.cpf_lookup_key_id IS NOT NULL"),
                eq(Integer.class)
        );
        verify(jdbcTemplate).queryForObject(
                contains("ai.cpf_lookup_hmac IS NOT NULL"),
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
    void productionRuntimeFailsClosedWhenReleaseMarkerIsMissing() {
        String revision = "a".repeat(40);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of());
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(releaseEnvironment(revision));

        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público")
                .hasNoCause();
    }

    @Test
    void startupPreflightFailsClosedWhenReleaseMarkerIsMissing() {
        String revision = "a".repeat(40);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of());
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(releaseEnvironment(revision));

        assertThatThrownBy(() -> readinessGuard.postProcessBeanFactory(
                mock(ConfigurableListableBeanFactory.class)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público")
                .hasNoCause();
        verify(jdbcTemplate).queryForList(
                contains("WHERE revision = ?"),
                eq(revision)
        );
    }

    @Test
    void publicEvidenceReturnsOnlyTheCanonicalDatabaseReleaseFields() throws Exception {
        String revision = "a".repeat(40);
        String marker = releaseMarker(revision);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("FROM public.cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of(Map.of(
                "revision", revision,
                "marker", marker
        )));
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(releaseEnvironment(revision));

        readinessGuard.verifyRuntimeReadiness();
        Object evidence = readinessGuard.publicEvidence();

        assertThat(evidence).isEqualTo(Map.of(
                "databaseReleaseRevision", revision,
                "databaseReleaseMarker", marker
        ));
        verify(jdbcTemplate, times(1)).queryForList(
                contains("WHERE revision = ?"),
                eq(revision)
        );
    }

    @Test
    void endpointReadinessUsesTheManagedJdbcTemplate() {
        String revision = "a".repeat(40);
        String marker = releaseMarker(revision);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of(Map.of(
                "revision", revision,
                "marker", marker
        )));
        @SuppressWarnings("unchecked")
        ObjectProvider<JdbcTemplate> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(jdbcTemplate);
        ConfigurableListableBeanFactory beanFactory =
                mock(ConfigurableListableBeanFactory.class);
        when(beanFactory.getBeanProvider(JdbcTemplate.class))
                .thenReturn(provider);
        PostgresqlRuntimeReadinessGuard readinessGuard =
                new PostgresqlRuntimeReadinessGuard();
        readinessGuard.setEnvironment(releaseEnvironment(revision).withProperty(
                "cortex.postgresql.runtime-ready",
                "true"
        ));
        ReflectionTestUtils.setField(
                readinessGuard,
                "beanFactory",
                beanFactory
        );

        assertThat(readinessGuard.verifiedPublicEvidence()).isEqualTo(Map.of(
                "databaseReleaseRevision", revision,
                "databaseReleaseMarker", marker
        ));
        verify(beanFactory, times(1)).getBeanProvider(JdbcTemplate.class);
        verify(jdbcTemplate, times(1)).queryForList(
                contains("WHERE revision = ?"),
                eq(revision)
        );
    }

    @Test
    void concurrentReadinessCheckFailsFastWhileOneEvaluationIsInFlight() {
        String revision = "a".repeat(40);
        String marker = releaseMarker(revision);
        CountDownLatch evaluationStarted = new CountDownLatch(1);
        CountDownLatch releaseEvaluation = new CountDownLatch(1);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenAnswer(invocation -> {
            evaluationStarted.countDown();
            await(releaseEvaluation);
            return List.of(Map.of(
                    "revision", revision,
                    "marker", marker
            ));
        });
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(releaseEnvironment(revision));

        CompletableFuture<Void> first = CompletableFuture.runAsync(
                readinessGuard::verifyRuntimeReadiness
        );
        assertThatCode(() -> {
            if (!evaluationStarted.await(1, TimeUnit.SECONDS)) {
                throw new IllegalStateException("evaluation did not start");
            }
        }).doesNotThrowAnyException();

        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PostgreSQL indisponível para readiness.")
                .hasNoCause();
        releaseEvaluation.countDown();
        assertThatCode(() -> first.get(2, TimeUnit.SECONDS))
                .doesNotThrowAnyException();
        assertThat(readinessGuard.publicEvidence()).isEqualTo(Map.of(
                "databaseReleaseRevision", revision,
                "databaseReleaseMarker", marker
        ));
        verify(jdbcTemplate, times(1)).queryForList(
                contains("WHERE revision = ?"),
                eq(revision)
        );
    }

    @Test
    void failedEvaluationUsesShortBackoffBeforeRetryingTheDatabase() {
        String revision = "a".repeat(40);
        String marker = releaseMarker(revision);
        AtomicLong nanoTime = new AtomicLong(1L);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of());
        PostgresqlRuntimeReadinessGuard readinessGuard =
                new PostgresqlRuntimeReadinessGuard(
                        jdbcTemplate,
                        "70",
                        true,
                        released(),
                        nanoTime::get
                );
        readinessGuard.setEnvironment(releaseEnvironment(revision));

        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público");
        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PostgreSQL indisponível para readiness.")
                .hasNoCause();
        verify(jdbcTemplate, times(1)).queryForList(
                contains("WHERE revision = ?"),
                eq(revision)
        );

        nanoTime.addAndGet(
                PostgresqlRuntimeReadinessGuard.RUNTIME_FAILURE_BACKOFF_NANOS
                        + 1L
        );
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of(Map.of(
                "revision", revision,
                "marker", marker
        )));

        assertThatCode(readinessGuard::verifyRuntimeReadiness)
                .doesNotThrowAnyException();
        verify(jdbcTemplate, times(2)).queryForList(
                contains("WHERE revision = ?"),
                eq(revision)
        );
    }

    @Test
    void expiredReleaseEvidenceIsRecheckedAndFailsClosed() {
        String revision = "a".repeat(40);
        String marker = releaseMarker(revision);
        AtomicLong nanoTime = new AtomicLong(1L);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of(Map.of(
                "revision", revision,
                "marker", marker
        )));
        PostgresqlRuntimeReadinessGuard readinessGuard =
                new PostgresqlRuntimeReadinessGuard(
                        jdbcTemplate,
                        "70",
                        true,
                        released(),
                        nanoTime::get
                );
        readinessGuard.setEnvironment(releaseEnvironment(revision));
        readinessGuard.verifyRuntimeReadiness();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of());

        nanoTime.addAndGet(
                PostgresqlRuntimeReadinessGuard.RUNTIME_SNAPSHOT_TTL_NANOS + 1L
        );

        assertThatThrownBy(readinessGuard::publicEvidence)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público")
                .hasNoCause();
        verify(jdbcTemplate, times(2)).queryForList(
                contains("WHERE revision = ?"),
                eq(revision)
        );
    }

    @Test
    void renderRuntimeUsesRenderGitCommitAsItsReleaseRevision() {
        String revision = "a".repeat(40);
        String marker = releaseMarker(revision);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of(Map.of(
                "revision", revision,
                "marker", marker
        )));
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(new MockEnvironment().withProperty(
                "RENDER_GIT_COMMIT",
                revision
        ));

        assertThat(readinessGuard.publicEvidence()).isEqualTo(Map.of(
                "databaseReleaseRevision", revision,
                "databaseReleaseMarker", marker
        ));
        verify(jdbcTemplate).queryForList(
                contains("WHERE revision = ?"),
                eq(revision)
        );
    }

    @Test
    void renderRuntimeIgnoresTheMigrationRevisionOverride() {
        String migrationRevision = "a".repeat(40);
        String renderRevision = "b".repeat(40);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(migrationRevision)
        )).thenReturn(List.of(Map.of(
                "revision", migrationRevision,
                "marker", releaseMarker(migrationRevision)
        )));
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(renderRevision)
        )).thenReturn(List.of(Map.of(
                "revision", renderRevision,
                "marker", releaseMarker(renderRevision)
        )));
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(new MockEnvironment()
                .withProperty("CORTEX_RELEASE_REVISION", migrationRevision)
                .withProperty("RENDER_GIT_COMMIT", renderRevision));

        assertThat(readinessGuard.publicEvidence()).isEqualTo(Map.of(
                "databaseReleaseRevision", renderRevision,
                "databaseReleaseMarker", releaseMarker(renderRevision)
        ));
    }

    @Test
    void requiredReleaseMarkerFailsClosedWithoutEitherRevisionSource() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(new MockEnvironment().withProperty(
                "cortex.postgresql.release-marker.required",
                "true"
        ));

        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público")
                .hasNoCause();
        verify(jdbcTemplate, never()).queryForList(
                contains("cortex_release_marker"),
                org.mockito.ArgumentMatchers.<Object>any()
        );
    }

    @Test
    void productionRuntimeRejectsMalformedReleaseEvidenceWithoutEchoingIt() {
        String revision = "a".repeat(40);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of(Map.of(
                "revision", "jdbc:postgresql://do-not-leak",
                "marker", "secret-do-not-leak"
        )));
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(releaseEnvironment(revision));

        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público")
                .hasMessageNotContaining("jdbc:postgresql")
                .hasMessageNotContaining("secret-do-not-leak")
                .hasNoCause();
    }

    @Test
    void productionRuntimeRejectsAMarkerDerivedFromAnotherRevision() {
        String revision = "a".repeat(40);
        String otherRevision = "b".repeat(40);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenReturn(List.of(Map.of(
                "revision", revision,
                "marker", releaseMarker(otherRevision)
        )));
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(releaseEnvironment(revision));

        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público")
                .hasMessageNotContaining(revision)
                .hasMessageNotContaining(otherRevision)
                .hasNoCause();
    }

    @Test
    void productionRuntimeRejectsAnInvalidProcessRevisionBeforeDatabaseLookup() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(releaseEnvironment("A".repeat(40)));

        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público")
                .hasMessageNotContaining("A".repeat(40))
                .hasNoCause();
        verify(jdbcTemplate, never()).queryForList(
                contains("cortex_release_marker"),
                org.mockito.ArgumentMatchers.<Object>any()
        );
    }

    @Test
    void productionRuntimeRedactsReleaseMarkerQueryErrors() {
        String revision = "a".repeat(40);
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("cortex_release_marker"),
                eq(revision)
        )).thenThrow(new DataAccessResourceFailureException(
                "jdbc:postgresql://do-not-leak?password=secret-do-not-leak"
        ));
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());
        readinessGuard.setEnvironment(releaseEnvironment(revision));

        assertThatThrownBy(readinessGuard::verifyRuntimeReadiness)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("marcador público")
                .hasMessageNotContaining("jdbc:postgresql")
                .hasMessageNotContaining("secret-do-not-leak")
                .hasNoCause();
    }

    @Test
    void localRuntimeDoesNotRequireReleaseEvidenceWithoutAReleaseRevision() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        PostgresqlRuntimeReadinessGuard readinessGuard =
                guard(jdbcTemplate, true, released());

        assertThatCode(readinessGuard::verifyRuntimeReadiness)
                .doesNotThrowAnyException();
        assertThat(readinessGuard.publicEvidence()).isEmpty();
        verify(jdbcTemplate, never()).queryForList(
                contains("cortex_release_marker"),
                org.mockito.ArgumentMatchers.<Object>any()
        );
    }

    /*
     * A pontualidade da Academy saiu da readiness.
     *
     * Estes testes afirmavam o contrário: com o agendador ligado, uma
     * sincronização ausente, falha, futura ou com mais de quinze minutos
     * derrubava a readiness — e com ela a API inteira, RDO, mapa e mensagens
     * incluídos, que não dependem da Academy. A janela não sumiu; virou estado
     * relatado da integração, coberto em IntegracaoAdminServiceTest.
     *
     * O que continua exigido aqui é estrutural, e tem teste próprio acima:
     * pelo menos uma identidade Academy ativa com HMAC atual de CPF.
     */
    @Test
    void readinessNaoConsultaAPontualidadeDaSincronizacaoAcademy() {
        JdbcTemplate jdbcTemplate = readyPostgresql();

        assertThatCode(
                guardWithAcademySync(jdbcTemplate)::verifyRuntimeReadiness
        ).doesNotThrowAnyException();

        verify(jdbcTemplate, never()).queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        );
    }

    @Test
    void academySemNenhumaSincronizacaoConcluidaNaoDerrubaMaisORuntime() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        )).thenReturn(List.of());

        assertThatCode(
                guardWithAcademySync(jdbcTemplate)::verifyRuntimeReadiness
        ).doesNotThrowAnyException();
    }

    @Test
    void academyIndisponivelNaoDerrubaMaisORuntime() {
        JdbcTemplate jdbcTemplate = readyPostgresql();
        when(jdbcTemplate.queryForList(
                contains("source_sync_run"),
                eq("acad_colaborador_import")
        )).thenThrow(new DataAccessResourceFailureException(
                "jdbc:mysql://academy.invalid/db?password=do-not-leak"
        ));

        assertThatCode(
                guardWithAcademySync(jdbcTemplate)::verifyRuntimeReadiness
        ).doesNotThrowAnyException();
    }

    private PostgresqlRuntimeReadinessGuard guard(
            JdbcTemplate jdbcTemplate,
            boolean runtimeReady,
            PostgresqlRuntimeSurfaceRegistry registry
    ) {
        return new PostgresqlRuntimeReadinessGuard(
                jdbcTemplate, "70", runtimeReady, registry
        );
    }

    private PostgresqlRuntimeReadinessGuard guardWithAcademySync(
            JdbcTemplate jdbcTemplate
    ) {
        return new PostgresqlRuntimeReadinessGuard(
                jdbcTemplate,
                "70",
                true,
                released()
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

    private MockEnvironment releaseEnvironment(String revision) {
        return new MockEnvironment().withProperty(
                "RENDER_GIT_COMMIT",
                revision
        );
    }

    private String releaseMarker(String revision) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    ("cortex-release-v1:" + revision)
                            .getBytes(StandardCharsets.UTF_8)
            );
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private PostgresqlRuntimeSurfaceRegistry released() {
        return new PostgresqlRuntimeSurfaceRegistry();
    }
}
