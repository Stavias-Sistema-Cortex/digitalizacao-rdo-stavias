package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class ZeladoriaProductionSecurityTest {

    private static final String INLINE_SECRET =
            "test-only-zeladoria-inline-secret";
    private static final String REDACTED_URL =
            "jdbc:mysql://zeladoria-sensitive.internal:3306/zeladoria";
    private static final String REDACTED_USER =
            "zeladoria_sensitive_user";

    @TempDir
    Path tempDir;

    @Test
    void productionRejectsInlinePasswordEvenWithSchedulerDisabled() {
        productionContext(
                "cortex.sources.zeladoria.password=" + INLINE_SECRET
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("arquivo")
                    .hasMessageNotContaining(INLINE_SECRET);
        });
    }

    @Test
    void productionEnabledRequiresFileBackedPassword() {
        productionContext(
                "cortex.sync.zeladoria.enabled=true",
                "cortex.sources.zeladoria.url="
                        + REDACTED_URL
                        + "?sslMode=VERIFY_IDENTITY",
                "cortex.sources.zeladoria.username=" + REDACTED_USER
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("Zeladoria")
                    .hasMessageContaining("PASSWORD_FILE")
                    .hasMessageNotContaining(REDACTED_URL)
                    .hasMessageNotContaining(REDACTED_USER);
        });
    }

    @Test
    void productionEnabledRequiresIdentityOrPinnedCaVerification()
            throws Exception {
        Path passwordFile = passwordFile();

        productionContext(
                "cortex.sync.zeladoria.enabled=true",
                "cortex.sources.zeladoria.url=" + REDACTED_URL,
                "cortex.sources.zeladoria.username=" + REDACTED_USER,
                "cortex.sources.zeladoria.password-file=" + passwordFile
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("VERIFY_IDENTITY")
                    .hasMessageContaining("VERIFY_CA")
                    .hasMessageNotContaining(REDACTED_URL)
                    .hasMessageNotContaining(REDACTED_USER)
                    .hasMessageNotContaining(passwordFile.toString());
        });
    }

    @Test
    void productionEnabledAcceptsFileAndVerifyIdentity() throws Exception {
        Path passwordFile = passwordFile();

        productionContext(
                "cortex.sync.zeladoria.enabled=true",
                "cortex.sources.zeladoria.url="
                        + REDACTED_URL
                        + "?sslMode=VERIFY_IDENTITY",
                "cortex.sources.zeladoria.username=" + REDACTED_USER,
                "cortex.sources.zeladoria.password-file=" + passwordFile
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void productionRejectsInlineAndFileTogether() throws Exception {
        Path passwordFile = passwordFile();

        productionContext(
                "cortex.sources.zeladoria.password=" + INLINE_SECRET,
                "cortex.sources.zeladoria.password-file=" + passwordFile
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("nunca ambos")
                    .hasMessageNotContaining(INLINE_SECRET)
                    .hasMessageNotContaining(passwordFile.toString());
        });
    }

    @Test
    void productionRejectsUnreadableFileWithoutLeakingItsPath() {
        Path missingFile = tempDir.resolve("sensitive-zeladoria-secret");

        productionContext(
                "cortex.sync.zeladoria.enabled=true",
                "cortex.sources.zeladoria.url="
                        + REDACTED_URL
                        + "?sslMode=VERIFY_IDENTITY",
                "cortex.sources.zeladoria.username=" + REDACTED_USER,
                "cortex.sources.zeladoria.password-file=" + missingFile
        ).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .rootCause()
                    .hasMessageContaining("indisponivel")
                    .hasMessageNotContaining(missingFile.toString())
                    .hasMessageNotContaining(REDACTED_URL)
                    .hasMessageNotContaining(REDACTED_USER);
        });
    }

    @Test
    void productionDisabledCanStartBeforeZeladoriaConfiguration() {
        productionContext(
                "cortex.sync.zeladoria.enabled=false"
        ).run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void localEnabledAcceptsInlinePassword() {
        context(
                "local,test",
                "cortex.sync.zeladoria.enabled=true",
                "cortex.sources.zeladoria.url="
                        + "jdbc:mysql://127.0.0.1/zeladoria",
                "cortex.sources.zeladoria.username=zeladoria_local",
                "cortex.sources.zeladoria.password=" + INLINE_SECRET
        ).run(context -> assertThat(context).hasNotFailed());
    }

    private Path passwordFile() throws Exception {
        Path file = tempDir.resolve("zeladoria-password");
        Files.writeString(file, INLINE_SECRET);
        return file;
    }

    private ApplicationContextRunner productionContext(
            String... propertyValues
    ) {
        return context("production", propertyValues);
    }

    private ApplicationContextRunner context(
            String profiles,
            String... propertyValues
    ) {
        return new ApplicationContextRunner()
                .withUserConfiguration(ZeladoriaContextConfiguration.class)
                .withPropertyValues(
                        java.util.stream.Stream.concat(
                                java.util.stream.Stream.of(
                                        "spring.profiles.active=" + profiles
                                ),
                                java.util.Arrays.stream(propertyValues)
                        ).toArray(String[]::new)
                );
    }

    @Configuration(proxyBeanMethods = false)
    @Import(ZeladoriaSourceAdapter.class)
    static class ZeladoriaContextConfiguration {
    }
}
