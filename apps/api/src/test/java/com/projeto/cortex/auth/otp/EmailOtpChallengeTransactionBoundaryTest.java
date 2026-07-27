package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class EmailOtpChallengeTransactionBoundaryTest {

    @Test
    void rateLimitingRunsWithoutAnOuterTransaction() throws Exception {
        Transactional transactional = EmailOtpChallengeService.class
                .getMethod(
                        "request",
                        String.class,
                        String.class,
                        String.class
                )
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.NEVER);
    }

    @Test
    void challengeIssuanceStartsOnlyAfterRateLimiting() throws Exception {
        Transactional transactional = EmailOtpChallengeIssuer.class
                .getMethod(
                        "issue",
                        String.class,
                        String.class,
                        String.class
                )
                .getAnnotation(Transactional.class);

        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation())
                .isEqualTo(Propagation.REQUIRES_NEW);
    }
}
