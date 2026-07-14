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
                WHERE status IN (
                    'PENDENTE',
                    'CONSUMIDO',
                    'EXPIRADO',
                    'BLOQUEADO'
                )
                  AND expira_em < TIMESTAMPADD(
                      SECOND,
                      -?,
                      CURRENT_TIMESTAMP(6)
                  )
                ORDER BY expira_em, id
                LIMIT ?
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
                WHERE atualizado_em < TIMESTAMPADD(
                    SECOND,
                    -?,
                    CURRENT_TIMESTAMP(6)
                )
                ORDER BY atualizado_em, bucket_key
                LIMIT ?
                """,
                policy.rateLimitRetentionSeconds(),
                policy.batchSize()
        );
    }
}
