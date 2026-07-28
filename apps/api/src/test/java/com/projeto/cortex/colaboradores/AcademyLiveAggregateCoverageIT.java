package com.projeto.cortex.colaboradores;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfNormalizer;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.integracoes.AcademyUserSnapshot;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Opt-in, PII-silent QA proof against the live read-only Academy source.
 *
 * <p>The source is never mutated. The snapshot is written only to a disposable
 * PostgreSQL 18 Testcontainer and the test prints aggregate counts plus one
 * membership hash.</p>
 */
@Testcontainers
@EnabledIfEnvironmentVariable(
        named = "CORTEX_ACADEMY_QA_ENABLED",
        matches = "(?i)true"
)
class AcademyLiveAggregateCoverageIT {

    private static final String SOURCE_DATABASE = "dbstavias_acad";
    private static final Set<String> REQUIRED_SOURCE_TABLES = Set.of(
            "usuarios",
            "grupos",
            "perfil"
    );

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("academy_live_aggregate_qa");

    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateDisposableDatabase() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void importsEveryEligibleActiveIdentityAndDeniesIneligibleRecords()
            throws Exception {
        SourceConfiguration source = sourceConfiguration();
        AcademySourceAdapter liveSource = new AcademySourceAdapter(
                source.url(),
                source.username(),
                source.password()
        );
        AcademyUserSnapshot snapshot = liveSource.fetchCompleteSnapshot(500);
        assertTrue(
                snapshot.complete(),
                "Academy QA requires a complete source snapshot."
        );

        SourceAggregates expected = sourceAggregates(snapshot);
        AcademySourceAdapter replaySource = mock(AcademySourceAdapter.class);
        when(replaySource.fetchCompleteSnapshot(anyInt()))
                .thenReturn(snapshot);
        AuthIdentityRepository identities = identities();
        ColaboradorImportService importer = new ColaboradorImportService(
                jdbc,
                replaySource,
                mock(CortexOperationalMemoryService.class),
                identities
        );

        ColaboradorImportResult result =
                importer.importarUsuariosDaAcademy();
        assertEquals(
                "SUCCESS",
                result.status(),
                "Academy QA import did not complete successfully."
        );
        assertEquals(
                expected.total(),
                result.registrosLidos(),
                "Academy QA source-read aggregate differs."
        );

        TargetAggregates actual = targetAggregates();
        assertEquals(
                expected.total(),
                actual.total(),
                "Academy QA total collaborator aggregate differs."
        );
        assertEquals(
                expected.active(),
                actual.active(),
                "Academy QA active collaborator aggregate differs."
        );
        assertEquals(
                expected.inactive(),
                actual.inactive(),
                "Academy QA inactive collaborator aggregate differs."
        );
        assertEquals(
                expected.eligibleActive(),
                actual.activeIdentities(),
                "Academy QA active identity aggregate differs."
        );
        assertEquals(
                0,
                actual.inactiveWithActiveIdentity(),
                "Academy QA left an inactive collaborator login-enabled."
        );
        assertEquals(
                0,
                actual.invalidActiveWithActiveIdentity(),
                "Academy QA enabled an active collaborator with invalid CPF."
        );
        assertEquals(
                expected.membershipSha256(),
                actual.membershipSha256(),
                "Academy QA source/target membership hashes differ."
        );

        int activeLookupFailures = activeLookupFailures(
                snapshot,
                identities
        );
        assertEquals(
                0,
                activeLookupFailures,
                "Academy QA active CPF lookup coverage is incomplete."
        );

        GrantAggregates grants = grantAggregates(source);

        System.out.printf(
                Locale.ROOT,
                "Academy QA aggregates: total=%d active=%d "
                        + "eligible_active=%d inactive=%d invalid_active=%d "
                        + "active_identities=%d denied_inactive=%d "
                        + "mapping_sha256=%s grant_statements=%d "
                        + "unexpected_grants=%d schema_wide_select=%d "
                        + "broad_scope_grants=%d delegating_grants=%d "
                        + "role_grants=%d other_unexpected=%d%n",
                expected.total(),
                expected.active(),
                expected.eligibleActive(),
                expected.inactive(),
                expected.invalidActive(),
                actual.activeIdentities(),
                actual.inactiveWithActiveIdentity(),
                actual.membershipSha256(),
                grants.totalStatements(),
                grants.unexpectedStatements(),
                grants.schemaWideSelectStatements(),
                grants.broadScopeStatements(),
                grants.delegatingStatements(),
                grants.roleGrantStatements(),
                grants.otherUnexpectedStatements()
        );

        assertEquals(
                REQUIRED_SOURCE_TABLES,
                grants.selectTables(),
                "Academy QA role does not have the exact three SELECT grants."
        );
        assertEquals(
                0,
                grants.unexpectedStatements(),
                "Academy QA role has privileges beyond USAGE and the three "
                        + "required SELECT grants."
        );
    }

    private static SourceAggregates sourceAggregates(
            AcademyUserSnapshot snapshot
    ) throws Exception {
        int active = 0;
        int eligibleActive = 0;
        int invalidActive = 0;
        List<String> membership = new ArrayList<>();

        for (AcademySourceAdapter.UsuarioAcademyRecord user
                : snapshot.users()) {
            boolean eligible = user.ativo() && validCpf(user.cpf());
            if (user.ativo()) {
                active++;
                if (eligible) {
                    eligibleActive++;
                } else {
                    invalidActive++;
                }
            }
            membership.add(
                    user.idUsuario()
                            + "|"
                            + user.ativo()
                            + "|"
                            + eligible
            );
        }
        return new SourceAggregates(
                snapshot.users().size(),
                active,
                eligibleActive,
                snapshot.users().size() - active,
                invalidActive,
                aggregateSha256(membership)
        );
    }

    private static TargetAggregates targetAggregates() throws Exception {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT
                    colaborador.pk_origem,
                    colaborador.ativo,
                    colaborador.cpf_hash,
                    CASE
                        WHEN identity.status = 'ATIVA'
                         AND identity.cpf_lookup_key_id IS NOT NULL
                         AND identity.cpf_lookup_hmac IS NOT NULL
                        THEN TRUE
                        ELSE FALSE
                    END AS active_identity
                FROM colaborador
                LEFT JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE colaborador.banco_origem = 'dbstavias_acad'
                  AND colaborador.tabela_origem = 'usuarios'
                """);

        int active = 0;
        int activeIdentities = 0;
        int inactiveWithActiveIdentity = 0;
        int invalidActiveWithActiveIdentity = 0;
        List<String> membership = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            boolean collaboratorActive =
                    Boolean.TRUE.equals(row.get("ativo"));
            boolean activeIdentity =
                    Boolean.TRUE.equals(row.get("active_identity"));
            if (collaboratorActive) {
                active++;
            }
            if (activeIdentity) {
                activeIdentities++;
            }
            if (!collaboratorActive && activeIdentity) {
                inactiveWithActiveIdentity++;
            }
            if (collaboratorActive
                    && row.get("cpf_hash") == null
                    && activeIdentity) {
                invalidActiveWithActiveIdentity++;
            }
            membership.add(
                    row.get("pk_origem")
                            + "|"
                            + collaboratorActive
                            + "|"
                            + activeIdentity
            );
        }
        return new TargetAggregates(
                rows.size(),
                active,
                rows.size() - active,
                activeIdentities,
                inactiveWithActiveIdentity,
                invalidActiveWithActiveIdentity,
                aggregateSha256(membership)
        );
    }

    private static int activeLookupFailures(
            AcademyUserSnapshot snapshot,
            AuthIdentityRepository identities
    ) {
        int failures = 0;
        for (AcademySourceAdapter.UsuarioAcademyRecord user
                : snapshot.users()) {
            if (!user.ativo() || !validCpf(user.cpf())) {
                continue;
            }
            String expectedId =
                    AcademyCollaboratorIdentity.fromAcademyUserId(
                            user.idUsuario()
                    );
            boolean matches = identities.findActiveAcademyByCpf(user.cpf())
                    .map(identity ->
                            expectedId.equals(identity.colaboradorId())
                    )
                    .orElse(false);
            if (!matches) {
                failures++;
            }
        }
        return failures;
    }

    private static GrantAggregates grantAggregates(
            SourceConfiguration source
    ) {
        try {
            return readGrantAggregates(source);
        } catch (Exception ignored) {
            throw new IllegalStateException(
                    "Academy QA grant verification failed."
            );
        }
    }

    private static GrantAggregates readGrantAggregates(
            SourceConfiguration source
    ) throws Exception {
        List<String> statements = new ArrayList<>();

        try (Connection connection = DriverManager.getConnection(
                source.url(),
                source.username(),
                source.password()
        )) {
            connection.setReadOnly(true);
            try (
                    Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                            "SHOW GRANTS FOR CURRENT_USER"
                    )
            ) {
                while (resultSet.next()) {
                    statements.add(resultSet.getString(1));
                }
            }
        }
        return classifyGrantStatements(statements);
    }

    static GrantAggregates classifyGrantStatements(
            List<String> statements
    ) {
        int unexpectedStatements = 0;
        int schemaWideSelectStatements = 0;
        int broadScopeStatements = 0;
        int delegatingStatements = 0;
        int roleGrantStatements = 0;
        int otherUnexpectedStatements = 0;
        Set<String> selectTables = new HashSet<>();

        for (String statement : statements) {
            String normalized = normalizeGrant(statement);
            String prefix = grantPrefix(normalized);
            boolean delegatesPrivileges =
                    hasDelegatingOption(normalized);
            if ("grant usage on *.*".equals(prefix)
                    && !delegatesPrivileges) {
                continue;
            }
            String table = exactSelectTable(prefix);
            if (!delegatesPrivileges
                    && table != null
                    && selectTables.add(table)) {
                continue;
            }

            unexpectedStatements++;
            if (delegatesPrivileges) {
                delegatingStatements++;
            } else if (isSchemaWideSelect(prefix)) {
                schemaWideSelectStatements++;
                broadScopeStatements++;
            } else if (isBroadScopeGrant(prefix)) {
                broadScopeStatements++;
            } else if (isRoleGrant(prefix)) {
                roleGrantStatements++;
            } else {
                otherUnexpectedStatements++;
            }
        }
        return new GrantAggregates(
                statements.size(),
                unexpectedStatements,
                schemaWideSelectStatements,
                broadScopeStatements,
                delegatingStatements,
                roleGrantStatements,
                otherUnexpectedStatements,
                Set.copyOf(selectTables)
        );
    }

    static String sourceDatabase() {
        return SOURCE_DATABASE;
    }

    private static String normalizeGrant(String grant) {
        if (grant == null) {
            return "";
        }
        return grant
                .replace("`", "")
                .trim()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String grantPrefix(String normalized) {
        int accountStart = normalized.indexOf(" to ");
        return accountStart < 0
                ? normalized
                : normalized.substring(0, accountStart);
    }

    private static String exactSelectTable(String grantPrefix) {
        String expectedPrefix =
                "grant select on " + SOURCE_DATABASE + ".";
        if (!grantPrefix.startsWith(expectedPrefix)) {
            return null;
        }
        String table = grantPrefix.substring(expectedPrefix.length());
        return REQUIRED_SOURCE_TABLES.contains(table) ? table : null;
    }

    private static boolean isSchemaWideSelect(String grantPrefix) {
        return (
                "grant select on "
                        + SOURCE_DATABASE
                        + ".*"
        ).equals(grantPrefix);
    }

    private static boolean isBroadScopeGrant(String grantPrefix) {
        return grantPrefix.contains(" on *.*")
                || grantPrefix.contains(
                        " on " + SOURCE_DATABASE + ".*"
                )
                || grantPrefix.startsWith(
                        "grant all privileges on "
                );
    }

    private static boolean hasDelegatingOption(String normalizedGrant) {
        return normalizedGrant.contains(" with grant option")
                || normalizedGrant.contains(" with admin option");
    }

    private static boolean isRoleGrant(String grantPrefix) {
        return grantPrefix.startsWith("grant ")
                && !grantPrefix.contains(" on ");
    }

    private static SourceConfiguration sourceConfiguration()
            throws Exception {
        String url = requiredEnvironment(
                "CORTEX_ACADEMY_DB_URL",
                "ACAD_DB_URL"
        );
        String username = requiredEnvironment(
                "CORTEX_ACADEMY_DB_USER",
                "ACAD_DB_USER"
        );
        String inlinePassword = firstEnvironment(
                "CORTEX_ACADEMY_DB_PASSWORD",
                "ACAD_DB_PASSWORD"
        );
        String passwordFile = firstEnvironment(
                "CORTEX_ACADEMY_DB_PASSWORD_FILE"
        );
        if (inlinePassword != null && passwordFile != null) {
            throw new IllegalStateException(
                    "Academy QA requires exactly one password source."
            );
        }

        String password = inlinePassword;
        if (passwordFile != null) {
            Path path = Path.of(passwordFile);
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(
                            path,
                            LinkOption.NOFOLLOW_LINKS
                    )
                    || !Files.isReadable(path)) {
                throw new IllegalStateException(
                        "Academy QA password file is unavailable."
                );
            }
            password = Files.readString(
                    path,
                    StandardCharsets.UTF_8
            ).strip();
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "Academy QA password is unavailable."
            );
        }
        return new SourceConfiguration(url, username, password);
    }

    private static AuthIdentityRepository identities() {
        return new AuthIdentityRepository(
                jdbc,
                new HmacCpfLookupDigestService(
                        "academy-live-aggregate-qa",
                        null,
                        "test-only-academy-live-aggregate-hmac-0001",
                        null
                )
        );
    }

    private static boolean validCpf(String cpf) {
        try {
            CpfNormalizer.requireValid(cpf);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private static String aggregateSha256(List<String> membership)
            throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        membership.stream()
                .sorted(Comparator.naturalOrder())
                .forEach(value -> {
                    digest.update(value.getBytes(StandardCharsets.UTF_8));
                    digest.update((byte) '\n');
                });
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String requiredEnvironment(String... names) {
        String value = firstEnvironment(names);
        if (value == null) {
            throw new IllegalStateException(
                    "Academy QA source configuration is incomplete."
            );
        }
        return value;
    }

    private static String firstEnvironment(String... names) {
        for (String name : names) {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    private record SourceConfiguration(
            String url,
            String username,
            String password
    ) {
    }

    private record SourceAggregates(
            int total,
            int active,
            int eligibleActive,
            int inactive,
            int invalidActive,
            String membershipSha256
    ) {
    }

    private record TargetAggregates(
            int total,
            int active,
            int inactive,
            int activeIdentities,
            int inactiveWithActiveIdentity,
            int invalidActiveWithActiveIdentity,
            String membershipSha256
    ) {
    }

    record GrantAggregates(
            int totalStatements,
            int unexpectedStatements,
            int schemaWideSelectStatements,
            int broadScopeStatements,
            int delegatingStatements,
            int roleGrantStatements,
            int otherUnexpectedStatements,
            Set<String> selectTables
    ) {
    }
}
