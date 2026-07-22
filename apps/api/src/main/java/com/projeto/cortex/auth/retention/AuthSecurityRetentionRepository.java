package com.projeto.cortex.auth.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Database-time retention queries with a strict per-run deletion bound. */
@Repository
public class AuthSecurityRetentionRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthSecurityRetentionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredChallenges(
            AuthSecurityRetentionPolicy policy
    ) {
        return jdbcTemplate.update("""
                DELETE FROM auth_email_challenge
                WHERE id IN (
                    SELECT id
                    FROM auth_email_challenge
                    WHERE status IN (
                        'PENDENTE', 'CONSUMIDO', 'EXPIRADO', 'BLOQUEADO'
                    )
                      AND expira_em < CURRENT_TIMESTAMP(6)
                          - (? * INTERVAL '1 second')
                    ORDER BY expira_em, id
                    LIMIT ?
                )
                """,
                policy.challengeRetentionSeconds(),
                policy.batchSize()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteStaleRateLimitBuckets(
            AuthSecurityRetentionPolicy policy
    ) {
        return jdbcTemplate.update("""
                DELETE FROM auth_rate_limit_bucket
                WHERE bucket_key IN (
                    SELECT bucket_key
                    FROM auth_rate_limit_bucket
                    WHERE atualizado_em < CURRENT_TIMESTAMP(6)
                        - (? * INTERVAL '1 second')
                    ORDER BY atualizado_em, bucket_key
                    LIMIT ?
                )
                """,
                policy.rateLimitRetentionSeconds(),
                policy.batchSize()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredWebAuthnChallenges(
            AuthSecurityRetentionPolicy policy
    ) {
        return jdbcTemplate.update("""
                DELETE FROM auth_webauthn_challenge
                WHERE id IN (
                    SELECT id
                    FROM auth_webauthn_challenge
                    WHERE expira_em < CURRENT_TIMESTAMP(6)
                        - (? * INTERVAL '1 second')
                    ORDER BY expira_em, id
                    LIMIT ?
                )
                """,
                policy.challengeRetentionSeconds(),
                policy.batchSize()
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredSessions(
            AuthSecurityRetentionPolicy policy
    ) {
        return jdbcTemplate.update("""
                DELETE FROM auth_session
                WHERE id IN (
                    SELECT id
                    FROM auth_session
                    WHERE expira_em < CURRENT_TIMESTAMP(6)
                        - (? * INTERVAL '1 second')
                       OR revogado_em < CURRENT_TIMESTAMP(6)
                        - (? * INTERVAL '1 second')
                    ORDER BY expira_em, id
                    LIMIT ?
                )
                """,
                policy.challengeRetentionSeconds(),
                policy.challengeRetentionSeconds(),
                policy.batchSize()
        );
    }
}
