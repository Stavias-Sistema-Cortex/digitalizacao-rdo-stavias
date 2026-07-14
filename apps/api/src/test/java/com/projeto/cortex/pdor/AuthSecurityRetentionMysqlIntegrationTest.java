package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.retention.AuthSecurityRetentionPolicy;
import com.projeto.cortex.auth.retention.AuthSecurityRetentionRepository;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class AuthSecurityRetentionMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void deletesOnlyOldRowsAndNeverExceedsTheConfiguredBatch() {
        database = PdorMysqlTestDatabase.create("auth_retention");
        database.migrate();
        DataSource dataSource = database.dataSource();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        AuthSecurityRetentionRepository repository =
                new AuthSecurityRetentionRepository(jdbc);
        AuthSecurityRetentionPolicy policy =
                new AuthSecurityRetentionPolicy(
                        3_600,
                        3_600,
                        2,
                        3_600_000,
                        3_600_000
                );

        insertChallenge(jdbc, "00000000-0000-0000-0000-000000000001", -2);
        insertChallenge(jdbc, "00000000-0000-0000-0000-000000000002", -2);
        insertChallenge(jdbc, "00000000-0000-0000-0000-000000000003", -2);
        insertChallenge(jdbc, "00000000-0000-0000-0000-000000000004", 1);
        insertBucket(jdbc, "a".repeat(64), -2);
        insertBucket(jdbc, "b".repeat(64), -2);
        insertBucket(jdbc, "c".repeat(64), -2);
        insertBucket(jdbc, "d".repeat(64), 0);

        assertThat(explainKey(jdbc, """
                EXPLAIN DELETE FROM auth_email_challenge
                WHERE status IN (
                    'PENDENTE',
                    'CONSUMIDO',
                    'EXPIRADO',
                    'BLOQUEADO'
                )
                  AND expira_em < TIMESTAMPADD(
                      SECOND,
                      -3600,
                      CURRENT_TIMESTAMP(6)
                  )
                ORDER BY expira_em, id
                LIMIT 2
                """)).isEqualTo(
                        "idx_auth_email_challenge_retention"
                );
        assertThat(explainKey(jdbc, """
                EXPLAIN DELETE FROM auth_rate_limit_bucket
                WHERE atualizado_em < TIMESTAMPADD(
                    SECOND,
                    -3600,
                    CURRENT_TIMESTAMP(6)
                )
                ORDER BY atualizado_em, bucket_key
                LIMIT 2
                """)).isEqualTo(
                        "idx_auth_rate_limit_bucket_updated"
                );

        assertThat(repository.deleteExpiredChallenges(policy)).isEqualTo(2);
        assertThat(repository.deleteStaleRateLimitBuckets(policy))
                .isEqualTo(2);
        assertThat(rowCount(jdbc, "auth_email_challenge")).isEqualTo(2);
        assertThat(rowCount(jdbc, "auth_rate_limit_bucket")).isEqualTo(2);

        assertThat(repository.deleteExpiredChallenges(policy)).isEqualTo(1);
        assertThat(repository.deleteStaleRateLimitBuckets(policy))
                .isEqualTo(1);
        assertThat(rowCount(jdbc, "auth_email_challenge")).isEqualTo(1);
        assertThat(rowCount(jdbc, "auth_rate_limit_bucket")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'auth_rate_limit_bucket'
                  AND index_name = 'idx_auth_rate_limit_bucket_updated'
                """,
                Integer.class
        )).isEqualTo(2);
    }

    private void insertChallenge(
            JdbcTemplate jdbc,
            String id,
            int expiryOffsetDays
    ) {
        jdbc.update("""
                INSERT INTO auth_email_challenge (
                    id,
                    colaborador_id,
                    identifier_digest,
                    codigo_digest,
                    expira_em,
                    tentativas,
                    max_tentativas,
                    status
                ) VALUES (
                    ?,
                    NULL,
                    ?,
                    ?,
                    TIMESTAMPADD(DAY, ?, CURRENT_TIMESTAMP(6)),
                    0,
                    5,
                    'PENDENTE'
                )
                """,
                id,
                "e".repeat(64),
                "f".repeat(64),
                expiryOffsetDays
        );
    }

    private void insertBucket(
            JdbcTemplate jdbc,
            String key,
            int updateOffsetDays
    ) {
        jdbc.update("""
                INSERT INTO auth_rate_limit_bucket (
                    bucket_key,
                    janela_inicio,
                    contador,
                    bloqueado_ate,
                    atualizado_em
                ) VALUES (
                    ?,
                    TIMESTAMPADD(DAY, ?, CURRENT_TIMESTAMP(6)),
                    1,
                    NULL,
                    TIMESTAMPADD(DAY, ?, CURRENT_TIMESTAMP(6))
                )
                """,
                key,
                updateOffsetDays,
                updateOffsetDays
        );
    }

    private int rowCount(JdbcTemplate jdbc, String table) {
        String trustedTable = switch (table) {
            case "auth_email_challenge" -> "auth_email_challenge";
            case "auth_rate_limit_bucket" -> "auth_rate_limit_bucket";
            default -> throw new IllegalArgumentException("Tabela inválida.");
        };
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + trustedTable,
                Integer.class
        );
    }

    private String explainKey(JdbcTemplate jdbc, String explainSql) {
        return jdbc.queryForObject(
                explainSql,
                (resultSet, rowNumber) -> resultSet.getString("key")
        );
    }
}
