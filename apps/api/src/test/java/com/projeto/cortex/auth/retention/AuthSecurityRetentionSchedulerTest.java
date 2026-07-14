package com.projeto.cortex.auth.retention;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

class AuthSecurityRetentionSchedulerTest {

    @Test
    void runsOneBoundedBatchForEachSecurityTable() {
        AuthSecurityRetentionRepository repository = mock(
                AuthSecurityRetentionRepository.class
        );
        AuthSecurityRetentionPolicy policy =
                new AuthSecurityRetentionPolicy(
                        86_400,
                        7_200,
                        250,
                        3_600_000,
                        3_600_000
                );
        AuthSecurityRetentionScheduler scheduler =
                new AuthSecurityRetentionScheduler(repository, policy);

        scheduler.runRetention();

        verify(repository).deleteExpiredChallenges(policy);
        verify(repository).deleteStaleRateLimitBuckets(policy);
    }
}
