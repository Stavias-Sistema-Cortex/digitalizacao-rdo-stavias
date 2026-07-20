package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class OtpSecurityConfigurationTest {

    private static final String TEST_KEY =
            "test-only-otp-hmac-key-material-00000001";

    @TempDir
    Path tempDir;

    @Test
    void testProfileAcceptsInlineSecretAndBuildsValidatedPolicy() {
        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "cortex.auth.otp.hmac-key-inline=" + TEST_KEY
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OtpCryptography.class);
                    assertThat(context.getBean(OtpPolicy.class))
                            .extracting(
                                    OtpPolicy::ttlSeconds,
                                    OtpPolicy::maxAttempts,
                                    OtpPolicy::rateLimitMaxRequests,
                                    OtpPolicy::globalRateLimitMaxRequests,
                                    OtpPolicy::rateLimitWindowSeconds
                            ).containsExactly(600, 5, 5, 100, 900);
                });
    }

    @Test
    void productionRejectsMissingOrInlineSecret() {
        contextRunner()
                .withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context).hasFailed());
        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "cortex.auth.otp.hmac-key-inline=" + TEST_KEY
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseInstanceOf(IllegalStateException.class)
                            .rootCause()
                            .hasMessageContaining("arquivo secreto")
                            .hasMessageNotContaining(TEST_KEY);
                });
    }

    @Test
    void productionAcceptsOnlySecretFile() throws Exception {
        Path keyFile = tempDir.resolve("otp-key");
        Files.writeString(keyFile, TEST_KEY);

        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=prod",
                        "cortex.auth.otp.hmac-key-file=" + keyFile
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(OtpCryptography.class);
                });
    }

    @Test
    void mixedProductionAndTestProfilesRemainProductionLike() {
        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=prod,test",
                        "cortex.auth.otp.hmac-key-inline=" + TEST_KEY
                )
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void rejectsUnsafePolicyBounds() {
        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=test",
                        "cortex.auth.otp.hmac-key-inline=" + TEST_KEY,
                        "cortex.auth.otp.ttl-seconds=10",
                        "cortex.auth.otp.max-attempts=0",
                        "cortex.auth.otp.rate-limit-max-requests=0"
                )
                .run(context -> assertThat(context).hasFailed());
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(OtpSecurityConfiguration.class);
    }
}
