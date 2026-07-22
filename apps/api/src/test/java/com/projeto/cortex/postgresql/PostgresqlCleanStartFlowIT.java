package com.projeto.cortex.postgresql;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.PostgresqlEmailOtpIdentityLookup;
import com.projeto.cortex.auth.otp.AuthRateLimiter;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.otp.EmailOtpChallengeIssuer;
import com.projeto.cortex.auth.otp.EmailOtpChallengeService;
import com.projeto.cortex.auth.otp.OtpCryptography;
import com.projeto.cortex.auth.otp.OtpDeliveryRequested;
import com.projeto.cortex.auth.otp.OtpPolicy;
import com.projeto.cortex.auth.otp.PostgresqlEmailIdentifierNormalizer;
import com.projeto.cortex.auth.otp.PostgresqlEmailOtpChallengeRepository;
import com.projeto.cortex.auth.postgresql.PostgresqlAuthPersistenceTestSupport;
import com.projeto.cortex.auth.session.AuthSessionProperties;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.PostgresqlAuthSessionRepository;
import com.projeto.cortex.common.PostgresqlActivationGateFilter;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Disposable clean-start rehearsal: it never opens Academy/MySQL, SMTP, an
 * owner-provided PostgreSQL database, or a servlet server.
 */
class PostgresqlCleanStartFlowIT extends PostgresqlAuthPersistenceTestSupport {

    private static final String V44_TABLE_INVENTORY =
            "/postgresql/v44-required-tables.txt";
    private static final Set<String> RETIRED_ASSISTANT_TABLES = Set.of(
            assistantTable("context_snapshots"),
            assistantTable("queries"),
            assistantTable("contexto_obra")
    );

    @Test
    void migratesAnEmptyDatabaseThenAllowsOnlyActivationRoutesAndIssuesAnAlfaSession()
            throws Exception {
        try (PostgreSQLContainer<?> database = database()) {
            database.start();
            JdbcTemplate jdbc = migratedJdbc(database);
            assertCurrentMigrationChainWasApplied(database);
            assertCurrentTableInventory(jdbc);
            assertThat(jdbc.queryForObject(
                    "SELECT count(*) FROM colaborador", Integer.class
            )).isZero();

            String collaboratorId = "00000000-0000-4000-8000-000000000301";
            String syntheticEmail = "initial.alfa@fixture.invalid";
            insertIdentity(jdbc, collaboratorId, syntheticEmail, "PENDENTE", true);

            assertActivationGateSurface();

            PostgresqlEmailIdentifierNormalizer normalizer =
                    new PostgresqlEmailIdentifierNormalizer();
            OtpCryptography cryptography = new OtpCryptography(
                    new byte[32], new FixedCodeRandom(), normalizer
            );
            PostgresqlEmailOtpChallengeRepository challenges =
                    new PostgresqlEmailOtpChallengeRepository(jdbc);
            ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
            EmailOtpChallengeIssuer issuer = new EmailOtpChallengeIssuer(
                    new PostgresqlEmailOtpIdentityLookup(jdbc),
                    challenges,
                    cryptography,
                    new OtpPolicy(300, 5, 5, 100, 900),
                    events,
                    normalizer
            );
            AuthRateLimiter rateLimiter = mock(AuthRateLimiter.class);
            when(rateLimiter.allow(anyString(), anyString())).thenReturn(true);
            EmailOtpChallengeService otp = new EmailOtpChallengeService(
                    rateLimiter,
                    issuer,
                    challenges,
                    cryptography,
                    new OtpPolicy(300, 5, 5, 100, 900)
            );

            otp.request("  INITIAL.ALFA@FIXTURE.INVALID  ", "203.0.113.30");
            verify(events).publishEvent(
                    org.mockito.ArgumentMatchers.any(OtpDeliveryRequested.class)
            );
            String challengeId = jdbc.queryForObject(
                    "SELECT id FROM auth_email_challenge WHERE colaborador_id = ?",
                    String.class,
                    collaboratorId
            );

            Optional<AuthenticatedIdentity> verified = transactions(jdbc)
                    .execute(status -> otp.verify(
                            challengeId, "123456"
                    ));
            assertThat(verified).contains(new AuthenticatedIdentity(
                    collaboratorId, "Synthetic Operator", PapelAcesso.ALFA
            ));

            AuthSessionService sessions = new AuthSessionService(
                    new PostgresqlAuthSessionRepository(jdbc),
                    new AuthSessionProperties(300)
            );
            var issued = sessions.issue(verified.orElseThrow());
            assertThat(sessions.resolve(issued.sessionToken()))
                    .hasValueSatisfying(session -> {
                        assertThat(session.collaboratorId()).isEqualTo(collaboratorId);
                        assertThat(session.role()).isEqualTo(PapelAcesso.ALFA);
                    });
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM auth_identity WHERE colaborador_id = ?",
                    String.class,
                    collaboratorId
            )).isEqualTo("ATIVA");
        }
    }

    private void assertCurrentMigrationChainWasApplied(PostgreSQLContainer<?> database)
            throws Exception {
        List<String> appliedVersions = new java.util.ArrayList<>();
        try (Connection connection = DriverManager.getConnection(
                database.getJdbcUrl(), database.getUsername(), database.getPassword()
        ); Statement statement = connection.createStatement(); ResultSet rows =
                     statement.executeQuery("""
                             SELECT version, success
                             FROM flyway_schema_history
                             WHERE type = 'SQL'
                             ORDER BY installed_rank
                             """)) {
            while (rows.next()) {
                assertThat(rows.getBoolean("success")).isTrue();
                appliedVersions.add(rows.getString("version"));
            }
        }
        assertThat(appliedVersions).containsExactly("44", "45", "45.1", "46");
    }

    private void assertCurrentTableInventory(JdbcTemplate jdbc) throws Exception {
        Set<String> expectedTables = v44Tables();
        assertThat(expectedTables).containsAll(RETIRED_ASSISTANT_TABLES);
        expectedTables.removeAll(RETIRED_ASSISTANT_TABLES);
        expectedTables.add("graph_projection_checkpoint");

        Set<String> actualTables = new TreeSet<>(jdbc.queryForList("""
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """, String.class));

        assertThat(expectedTables).hasSize(114).doesNotContainAnyElementsOf(RETIRED_ASSISTANT_TABLES);
        assertThat(actualTables)
                .doesNotContainAnyElementsOf(RETIRED_ASSISTANT_TABLES)
                .containsExactlyElementsOf(expectedTables);
    }

    private Set<String> v44Tables() throws Exception {
        InputStream resource = PostgresqlCleanStartFlowIT.class.getResourceAsStream(
                V44_TABLE_INVENTORY
        );
        assertThat(resource).as("immutable V44 table inventory").isNotNull();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(
                resource,
                StandardCharsets.UTF_8
        ))) {
            Set<String> tables = new TreeSet<>();
            lines.lines()
                    .map(String::trim)
                    .filter(line -> !line.isEmpty())
                    .filter(line -> !line.startsWith("#"))
                    .forEach(tables::add);
            return tables;
        }
    }

    private static String assistantTable(String suffix) {
        return String.join("", "sta", "via", "_", suffix);
    }

    private void assertActivationGateSurface() throws Exception {
        PostgresqlActivationGateFilter filter = new PostgresqlActivationGateFilter();
        assertAllowed(filter, "GET", "/api/health");
        assertAllowed(filter, "GET", "/api/readiness");
        assertAllowed(filter, "POST", "/api/auth/email/challenges");
        assertAllowed(filter, "POST", "/api/auth/email/challenges/"
                + UUID.randomUUID() + "/verify");
        assertDenied(filter, "POST", "/api/auth/login");
        assertDenied(filter, "GET", "/api/obras");
        assertDenied(filter, "POST", "/api/sync/push");
    }

    private void assertAllowed(
            PostgresqlActivationGateFilter filter,
            String method,
            String path
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void assertDenied(
            PostgresqlActivationGateFilter filter,
            String method,
            String path
    ) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isNull();
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsString()).contains("CORTEX_ACTIVATION_ONLY");
    }

    private static final class FixedCodeRandom extends SecureRandom {

        @Override
        public int nextInt(int bound) {
            return 123456;
        }
    }
}
