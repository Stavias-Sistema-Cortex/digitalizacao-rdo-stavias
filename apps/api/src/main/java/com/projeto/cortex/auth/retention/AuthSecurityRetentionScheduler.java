package com.projeto.cortex.auth.retention;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class AuthSecurityRetentionScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            AuthSecurityRetentionScheduler.class
    );

    private final AuthSecurityRetentionRepository repository;
    private final AuthSecurityRetentionPolicy policy;

    public AuthSecurityRetentionScheduler(
            AuthSecurityRetentionRepository repository,
            AuthSecurityRetentionPolicy policy
    ) {
        this.repository = repository;
        this.policy = policy;
    }

    @Scheduled(
            initialDelayString = "${cortex.auth.retention.initial-delay-ms:3600000}",
            fixedDelayString = "${cortex.auth.retention.fixed-delay-ms:300000}"
    )
    public void runRetention() {
        int challengesDeleted = repository.deleteExpiredChallenges(policy);
        int bucketsDeleted = repository.deleteStaleRateLimitBuckets(policy);
        if (challengesDeleted > 0 || bucketsDeleted > 0) {
            LOGGER.info(
                    "Authentication retention removed {} challenge rows and {} rate-limit rows.",
                    challengesDeleted,
                    bucketsDeleted
            );
        }
    }
}
