package com.projeto.cortex.auth.password;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class PasswordSecurityConfigurationTest {

    private static final String TEST_KEY =
            "test-only-password-setup-hmac-material-0001";

    @TempDir
    Path tempDir;

    @Test
    void testRuntimeAcceptsExplicitInlineSecret() {
        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=test,postgresql-common",
                        "cortex.auth.password-setup.hmac-key-inline="
                                + TEST_KEY
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            PasswordSetupCodeCryptography.class
                    );
                });
    }

    @Test
    void productionRejectsMissingOrInlineSecretWithoutDisclosingIt() {
        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=production,postgresql-common"
                )
                .run(context -> assertThat(context).hasFailed());

        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=production,postgresql-common",
                        "cortex.auth.password-setup.hmac-key-inline="
                                + TEST_KEY
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
    void productionAcceptsProtectedSecretFile() throws Exception {
        Path keyFile = tempDir.resolve("password-setup-hmac");
        Files.writeString(keyFile, TEST_KEY);

        contextRunner()
                .withPropertyValues(
                        "spring.profiles.active=production,postgresql-common",
                        "cortex.auth.password-setup.hmac-key-file=" + keyFile
                )
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(
                            PasswordSetupCodeCryptography.class
                    );
                });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner().withUserConfiguration(
                PasswordSecurityConfiguration.class
        );
    }
}
